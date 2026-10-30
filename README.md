# Dead Drop Server

The sync server for [Dead Drop Finance](https://github.com/Crazy8697/DeadDropFinance), the envelope budget for
Android and Windows. It keeps the Android app and the Windows program in step, and it runs on your own hardware, in
Docker.

**It is optional.** The app and the program each work fine on their own; the server is only for keeping more than one
device on the same budget.

## What it holds

- **The server holds a readable copy of your budget.** It keeps the master copy that your devices sync with, in one
  SQLite file in its data folder, so run it on hardware you control.
- **The connection is encrypted.** Every request between a device and the server, except `/health` and the server's
  public key, is a libsodium `crypto_box` envelope sealed to the server's key, which the device pins the first time it
  connects. The network sees only ciphertext, so plain HTTP on your own network is fine.
- One account per server. You choose which devices get in, and you can remove a lost phone or old computer at any
  time; its key is deleted and it can't connect again.
- The server backs itself up once a day, encrypted with its backup key, to a folder you choose.
- No accounts with anyone else, no ads, no telemetry. The server never contacts anything on its own.

## Set up the server

You need Docker with Docker Compose, on a machine your phone and computer can reach (a home server, a NAS that runs
Docker, a spare computer).

### 1. Make its keys and env file

The server needs two keys, each 32 random bytes written as 64 hex characters. Make each one with:

```
openssl rand -hex 32
```

Put them in a file named `deaddrop.env`:

```
DEADDROP_PORT=5090
DEADDROP_DATA_DIR=/data
DEADDROP_BACKUP_DIR=/backups
DEADDROP_BACKUP_KEY=<64 hex characters>
DEADDROP_SERVER_SECRET_KEY=<64 hex characters>
```

Keep this file private: make it owned by root and readable only by root (`sudo chown root:root deaddrop.env` and
`sudo chmod 600 deaddrop.env`). Keep a copy of it somewhere safe, off the server. **There is no recovery for the backup
key:** without `DEADDROP_BACKUP_KEY` the server's backups can never be read. The secret key is the server's identity;
if it changes, every device has to be added again.

### 2. Make its folders

Pick a user and group for the server to run as (not root), and make two folders owned by them, one for its data and one
for its backups:

```
sudo mkdir -p <data folder> <backup folder>
sudo chown <uid>:<gid> <data folder> <backup folder>
sudo chmod 700 <data folder> <backup folder>
sudo touch <backup folder>/.deaddrop-backups
```

The `.deaddrop-backups` file marks the backup folder. The server writes no backup unless it is there, so a missing
disk (an empty mount point) shows up as a failed backup instead of filling the system disk.

### 3. The compose file

Save this as `docker-compose.yml`, with your own values in place of the `<...>` placeholders:

```yaml
services:
  deaddrop-server:
    image: ghcr.io/crazy8697/deaddrop-server:0.5.1
    restart: unless-stopped
    ports:
      - "<port>:5090"
    environment:
      DEADDROP_RUN_AS: "<uid>:<gid>"
    volumes:
      - <path to deaddrop.env>:/run/secrets/deaddrop.env:ro
      - <data folder>:/data
      - <backup folder>:/backups
    mem_limit: 384m
    read_only: true
    tmpfs: ["/tmp:rw,exec,nosuid,nodev,size=64m,mode=1777"]
    security_opt: ["no-new-privileges:true"]
    cap_drop: [ALL]
    cap_add: [SETUID, SETGID]
    healthcheck:
      test: ["CMD", "curl", "-fsS", "-o", "/dev/null", "http://127.0.0.1:5090/health"]
      interval: 60s
      timeout: 5s
      retries: 3
      start_period: 30s
```

`<port>` is the port your devices connect to (5090 is a good choice). The container starts as root only so its
entrypoint can read the root-only env file; it then switches to `DEADDROP_RUN_AS` before the server starts, and refuses
to run the server as root. Then:

```
docker compose up -d
```

### 4. The setup code and your account

On a start with no account, the server makes a **one-time setup code**. It appears in the server's start line in the
log, and in `setup-code.txt` in the data folder:

```
docker compose logs deaddrop-server
```

In the Dead Drop Finance app or program, open Settings > Server, enter the server's address (`<server address>:<port>`),
the setup code, and a username and password for the account. Check that the fingerprint the app shows matches the one
in the log. That makes the account and adds that device.

- **The setup code works once.** After the account exists it stops working and its file is removed.
- **A restart before the account exists makes a new setup code,** and the old one stops working.
- Every other device logs in with the username and password, or is added from a logged-in device (the program shows
  a QR code for a phone).

### 5. Passwords and the recovery key

- **There is no password reset.** Any logged-in device can set a new password (Settings > Server) without the old one.
  **If the password is forgotten and every device is logged out, the account is locked out for good.**
- The app's and the program's own backups are encrypted with the budget's **recovery key**. **Those backups can't be
  recovered without the recovery key**, and the server can't help: it keeps each device's copy of that key only sealed
  to that device.
- The server's own backups need its backup key (step 1).
- **Restoring a backup in the app or the program while logged in replaces the server's budget** (from server 0.5.1 and app
  1.7.2 or program 0.12.2), after a warning on the device. Before it does, the server writes and checks its own encrypted
  backup of its current budget, in its backup folder; if it can't, nothing is changed. Every other device takes the
  restored budget at its next sync, after saving its own.

## Running it

Admin commands run inside the container through the entrypoint, so they read the same settings and run as the
server's user:

```
docker compose exec deaddrop-server deaddrop-entrypoint devices
docker compose exec deaddrop-server deaddrop-entrypoint revoke <device id>
docker compose exec deaddrop-server deaddrop-entrypoint server-key
docker compose exec deaddrop-server deaddrop-entrypoint backup-now
docker compose exec deaddrop-server deaddrop-entrypoint verify-backup /backups/<file>.ddbak
docker compose exec deaddrop-server deaddrop-entrypoint restore /backups/<file>.ddbak --to /data/restored.db
```

`restore` only ever writes a new file. To put it live: stop the server (`docker compose stop`), move `deaddrop.db` (and
any `-wal` and `-shm` files) out of the data folder, rename `restored.db` to `deaddrop.db`, and start it again
(`docker compose up -d`).

`GET http://<server address>:<port>/health` answers with the version and whether the last backup is fresh; it holds no
budget data.

## Build it yourself

```
./gradlew test          # unit and end-to-end tests, synthetic data, temp folders
./gradlew installDist   # the server in build/install/deaddrop-server
docker build -t deaddrop-server .
```

The wire format is in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## License

Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)). The image's third-party components and their
licenses are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), with the license texts in
[licenses](licenses); the image carries them in `/opt/deaddrop-server`.

Not financial advice. DarkRelay is not a bank and never holds or moves your money.
