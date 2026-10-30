# Protocol (version 1)

Plain HTTP. Everything except `/health` and `/v1/server-key` is a libsodium envelope, so someone watching the LAN sees a device id, a
nonce and ciphertext, never budget data or even which operation was asked for.

All crypto is libsodium (lazysodium on the JVM; lazysodium-android or libsodium bindings on the phone):

| Use | libsodium call |
|---|---|
| Requests and replies | `crypto_box_easy` / `crypto_box_open_easy` (X25519, XSalsa20-Poly1305) |
| Pairing, setup and login requests | `crypto_box_seal` to the server's public key |
| The recovery key's copies (0.4.0) | `crypto_box_seal` to a device's public key, sealed and opened only on devices |
| The account password, on the server | `crypto_pwhash_str` (Argon2id), stored only as that hash |
| Backups | `crypto_secretstream_xchacha20poly1305` |

## Keys

- Server: one X25519 key pair. The secret key is `DEADDROP_SERVER_SECRET_KEY` in the env file. The public key and a short
  fingerprint are printed by `deaddrop-server pair-code` and `server-key`.
- Device: makes its own X25519 key pair when it pairs. Only the public key is sent; the secret key stays on the device
  (Android Keystore wrapped, or the desktop's secure store, in later stages). The server stores the public key in
  `devices.public_key`. Revoking sets it to NULL, so nothing from that device opens again.

## Server key and pinning

`GET /v1/server-key` (plain JSON): `{"publicKey": "<64 hex>", "fingerprint": "<16 hex>"}`. The key is public. A device
fetches it on first contact, shows the fingerprint (the setup code file and the server's start line show it too), and
pins it. Every later reply only opens with that key, so a different key later is refused with an error, never trusted.

## The account (0.2.0)

One account per server. A new server has none.

1. On any start with no account the server makes a one-time setup code `XXXX-XXXX`, keeps only its hash, prints it once
   to the container log and writes it to `setup-code.txt` in the data folder (owner-only). Each start replaces the
   previous code. With an account, no code is made and the file is deleted.
2. Device to `POST /v1/setup`: `0x01 | crypto_box_seal(JSON, server public key)` with
   `{"setupCode", "username", "password", "name", "kind", "publicKey", "recoveryKeyId"}`. Any device can make the
   account, phone or desktop. The username is 1 to 60 characters, the password 8 to 1024. `recoveryKeyId` (0.4.0,
   optional) is the id of the recovery key the device made: exactly 32 lowercase hex characters (16 bytes), or the setup
   is refused like any other bad setup. On success the account exists, the code and its file are gone, and the device
   is the first one.
3. Every other device to `POST /v1/login`, the same envelope, with `{"username", "password", "name", "kind", "publicKey"}`.
   The username matches without regard to case. The device's password is not stored on the device.
4. Both answer like pairing (below): `200` with the reply boxed to the new device's key, or a bare `403`. Failed setups,
   logins and pairings share one window: five in 10 minutes refuse all three until it passes. Once the account exists,
   `/v1/setup` always refuses.
5. Each setup or login makes a new device with its own key, so the device list and revoke work as for paired devices.

## Pairing

1. A one-time code `XXXX-XXXX` (10 minutes by default): from a logged-in device with `pairing.new` (0.3.0; the desktop
   program shows it as a QR code with the server address and key fingerprint), or on the server with
   `deaddrop-server pair-code "Phone"`. Only its hash is stored, and it is never logged.
2. Device to `POST /v1/pair`: `0x01 | crypto_box_seal(JSON, server public key)` with
   `{"code": "XXXX-XXXX", "kind": "phone" | "desktop", "publicKey": "<64 hex>", "name": "<optional>"}`. A `name` (1 to 60
   characters) names the new device; without one it gets the name the code was made with.
3. Server answers `200`: `0x01 | nonce(24) | crypto_box(JSON, nonce, device public, server secret)` with
   `{"deviceId", "name", "kind", "serverTime", "recoveryKeyId"}`. The reply only opens with the right server key, so a
   fake server is noticed. `recoveryKeyId` (0.4.0) is the account's recovery key id, left out while it has none; setup
   and login replies carry it too.
4. Any failure is a bare `403`. Five failures within 10 minutes refuse all pairing until the window passes.

## Requests

`POST /v1/rpc`, body: `0x01 | device id (16 bytes, the UUID's bytes) | nonce (24) | crypto_box(JSON, nonce, server public, device secret)`

Inner JSON: `{"op": "...", "ts": <epoch millis>, "args": {...}}`

In every JSON the server sends, a field that is null is left out.

Reply `200`: `0x01 | nonce (24) | crypto_box(JSON, nonce, device public, server secret)` with
`{"ok": true, "re": "<request nonce hex>", "result": ...}` or `{"ok": false, "re": ..., "error": "<code>", "message": "..."}`.
The device checks `re` matches its request.

A bare `401` means the device is unknown or revoked, or the box didn't open (wrong key, tampered). `400` is a malformed
envelope, `413` a body over 8 MB.

Replay protection: `ts` must be within 5 minutes of the server's clock (`stale`), and each nonce is accepted once (`replay`).

## Operations

| op | args | result |
|---|---|---|
| `status` | | server time, last seq, lock state, this device, all devices |
| `heartbeat` | `{"open": true\|false}` | lock state. Renews the lock this device holds; `open: false` (closing) releases it |
| `lock.acquire` | | lock state; `youHoldIt` says whether it was granted. A held lock shows `holder` and `holderName` |
| `lock.release` | | lock state |
| `lock.takeover` | | lock state (0.3.0). The lock moves to this device even if another holds it; that device gets the handoff notice |
| `pairing.new` | `{"name": "Phone", "minutes": 10}` | `{"code", "name", "createdAt", "expiresAt"}` (0.3.0): a one-time pairing code, 0 to 60 minutes |
| `push` | `{"changes": [...], "baseSeq": n, "offline": bool}` | `{"applied", "duplicates", "lost", "lastSeq", "conflicts"}` |
| `pull` | `{"sinceSeq": n, "limit": 500}` | `{"changes": [...], "lastSeq", "more", "conflicts"}` (conflicts this device lost) |
| `records` | `{"afterType", "afterId", "limit"}` | the master copy, paged by (entityType, entityId) |
| `devices.list` | | every paired device, revoked ones included |
| `devices.revoke` | `{"deviceId"}` | the revoked device (any paired device can revoke another, for a lost phone). Its recovery key copy is deleted too |
| `account.password` | `{"password"}` | `{"changedAt"}` (0.4.0): a new password, 8 to 1024 characters, without the old one. Other devices stay logged in |
| `recovery.status` | | `{"keyId", "mine", "needing": [{"deviceId", "name", "kind", "publicKey"}]}` (0.4.0) |
| `recovery.set` | `{"keyId"}` | as `recovery.status` (0.4.0). Sets the key id only while the account has none; a different one is `conflict` |
| `recovery.put` | `{"deviceId", "keyId", "sealed"}` | `{"stored": true}` (0.4.0): one device's sealed copy of the recovery key |
| `recovery.get` | | `{"keyId", "sealed"}` (0.4.0): this device's sealed copy, if it is for the current key id |
| `device.logout` | | `{"loggedOut": true}` (0.4.0): log out on close. Frees the lock if held and revokes this device, as `devices.revoke` does |
| `master.check` | | `{"backedUp": true}` (0.5.1): the device holds the edit lock, and the server wrote and verified an encrypted backup of its current master copy. Changes nothing else |
| `master.replace` | `{"changes": [...]}` | `{"applied", "lastSeq"}` (0.5.1): restore while logged in. Needs the edit lock. The server backs up its master copy first (a failed backup is `backup_failed` and nothing changes), then replaces it with these changes (one INSERT per row, plus the synced meta values) in one transaction |

Error codes: `locked`, `taken_over`, `bad_request`, `stale`, `replay`, `unknown_op`, `not_found`, `conflict`, `backup_failed` (0.5.1).

Every op is logged as its name and the first 8 characters of the device id, never its arguments.

### Replacing the master copy (0.5.1)

A device that restored a backup while logged in sends the restored budget with `master.replace`. The server's records, change
history and conflict notes are replaced by it in one transaction; seqs keep counting up. Every other device finds out at its
next `pull`: if its `sinceSeq` is at or below the last seq the old master had, the reply has `masterReplaced: true`, no
changes, and `lastSeq` of the new master, and the device takes the whole copy with `records`, as it does at login (after a
backup of its own budget on that device). A body over 8 MB is refused (`413`), which bounds a restored budget at roughly 15,000
rows.

### The password (0.4.0)

`account.password` is the only way to change the password after setup. It needs no old password: the device already
proved itself with its own key, and a revoked or logged out device gets a bare `401` before any op runs. There is no
password reset of any kind, not as an op, not as a server command. A forgotten password with every device logged out
means the account is locked out for good.

### The recovery key (0.4.0)

The app and the program encrypt their backups with a recovery key that only devices hold. The account has one, named
by its id (16 random bytes as 32 lowercase hex characters, a label, not the key). The server passes the key from
device to device without ever holding it in a form it can read:

1. The device that makes the key sends its id with setup, or with `recovery.set` for an account made by an older app.
2. A device that has the key calls `recovery.status`. `needing` lists the other active devices with no copy under the
   current id, each with its public key (a public value).
3. For each one it makes `crypto_box_seal(recovery key, that device's public key)` and sends it as hex with
   `recovery.put` (`deviceId`, the current `keyId`, `sealed`). `sealed` must be hex of 48 to 256 bytes. An unknown or
   revoked device is `not_found`, another key id is `conflict`. A second put for the same device replaces its copy.
4. That device calls `recovery.get` and opens `sealed` with its own secret key (`crypto_box_seal_open`). `mine` in
   `recovery.status` says it has a copy.

The server only stores the sealed bytes and never tries to open them; only the device it was sealed to can. Revoking a
device, or its own `device.logout`, deletes its copy.

### Take over (0.3.0)

The lock state carries a handoff notice for the device the lock was taken from: `takenBy`, `takenByName` and `takenAt`.
It lasts until that device holds the lock again (`lock.acquire` or `lock.takeover`). Until then its `push` is refused
with `taken_over`, even for offline changes, so the device keeps its waiting changes and sends them once it has the lock
back (last change wins applies as usual). Its heartbeats renew and release only a lock it holds, so they don't touch
the new holder's lock.

### Changes

A change has the shape of a row of the phone's `change_log` table:

```json
{"changeId": "<uuid>", "entityType": "transaction", "entityId": "<uuid>", "operation": "INSERT|UPDATE|DELETE",
 "changedFields": {"amount": -450, "payee": "..."}, "timestamp": 1761700000000}
```

- `changedFields` merge into the record's fields (`records.data`, a JSON object). Money stays whole cents, as sent.
- `DELETE` keeps the row as a tombstone (`deleted = 1`); a `deleted` field in `changedFields` sets it too.
- A change id is applied once; pushing the same changes again counts them as `duplicates`.
- Last change wins by `timestamp`. A change older than the record's current one is not applied (`lost`) and, when the
  newer change came from another device, a conflict note is written. A newer change that replaces another device's change
  this device hadn't pulled yet (record seq > `baseSeq`) is applied and also noted.
- `push` needs the edit lock, unless `offline: true` (edits made while the device was offline, merged by the same rule).
- Timestamps more than a day in the future are refused (a wrong device clock would otherwise win every conflict).
