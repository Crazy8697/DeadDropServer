package net.darkrelay.deaddropserver

import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

private const val USAGE = """deaddrop-server $VERSION

  serve                              run the server (default)
  pair-code <name> [--minutes N]     one-time pairing code for a new device (10 minutes by default)
  devices                            paired devices
  revoke <device-id>                 revoke a device: deletes its key
  server-key                         the server's public key and fingerprint (public, safe to show)
  backup-now                         write an encrypted backup to the NAS folder now
  verify-backup <file>               decrypt a backup and check it reads back
  restore <file> --to <new.db>       decrypt a backup into a NEW database file (never the live one)
  selftest --url U --server-key HEX --code-phone C --code-desktop C [--lock-timeout S]
                                     end-to-end check against a running server (synthetic data)
  version
Settings come from the environment: DEADDROP_PORT, DEADDROP_DATA_DIR, DEADDROP_BACKUP_DIR, DEADDROP_BACKUP_KEY,
DEADDROP_SERVER_SECRET_KEY."""

fun main(argv: Array<String>) {
    val args = argv.toList().ifEmpty { listOf("serve") }
    fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    try {
        when (args[0]) {
            "version", "--version" -> println(VERSION)
            "help", "--help", "-h" -> println(USAGE)
            "selftest" -> {
                SelfTest(opt("--url")!!, opt("--server-key")!!.unhex(), opt("--code-phone")!!, opt("--code-desktop")!!,
                    opt("--lock-timeout")?.toLong() ?: 120).run()
            }
            else -> {
                val config = Config.fromEnv()
                Db(config.dbFile).use { db ->
                    val backups = Backups(config, db)
                    val service = Service(db, config, backupNow = { backups.runBackup() })
                    when (args[0]) {
                        "serve" -> {
                            backups.schedule()
                            val fp = Crypto.fingerprint(config.serverPublicKey)
                            println("deaddrop-server $VERSION on port ${config.port}, server key fingerprint $fp")
                            // the one place the setup code is shown besides its file: the container log, on a start with no account
                            service.startSetup(config.dataDir.resolve(Service.SETUP_FILE), fp)?.let {
                                println("No account yet. One-time setup code: $it (also in ${Service.SETUP_FILE} in the data folder)")
                            }
                            Server(config, service, backups).start(wait = true)
                        }
                        "pair-code" -> {
                            val name = args.getOrNull(1) ?: error("pair-code needs a device name")
                            val minutes = opt("--minutes")?.toLong() ?: 10
                            val code = service.newPairingCode(name, minutes)
                            println("Pairing code for \"$name\": $code (one use, $minutes minutes)")
                            println("Server key: ${config.serverPublicKey.hex()}")
                            println("Fingerprint: ${Crypto.fingerprint(config.serverPublicKey)}")
                        }
                        "devices" -> service.devices().forEach {
                            val state = if (it.revokedAt != null) "revoked ${Instant.ofEpochMilli(it.revokedAt)}" else if (it.open) "open" else "active"
                            println("${it.id}  ${it.kind.padEnd(7)}  ${it.name}  ($state, paired ${Instant.ofEpochMilli(it.pairedAt)})")
                        }
                        "revoke" -> service.revoke(args.getOrNull(1) ?: error("revoke needs a device id")).let { println("revoked ${it.id} (${it.name}); its key is deleted") }
                        "server-key" -> println("${config.serverPublicKey.hex()}  fingerprint ${Crypto.fingerprint(config.serverPublicKey)}")
                        "backup-now" -> println("backup written: ${backups.runBackup()}")
                        "verify-backup" -> println("backup reads back: ${backups.verify(Path.of(args[1]))} bytes")
                        "restore" -> {
                            val (r, c) = backups.restore(Path.of(args[1]), Path.of(opt("--to") ?: error("restore needs --to <new.db>")))
                            println("restored to ${opt("--to")}: $r records, $c changes, integrity ok")
                        }
                        else -> { System.err.println(USAGE); exitProcess(2) }
                    }
                }
            }
        }
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}
