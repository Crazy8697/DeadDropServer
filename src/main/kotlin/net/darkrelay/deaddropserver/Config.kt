package net.darkrelay.deaddropserver

import java.nio.file.Path

/**
 * Settings, all from the environment (the entrypoint reads them from the mounted env file).
 * Keys are 32 bytes as 64 hex characters and are never logged or printed.
 */
class Config(
    val port: Int,
    val dataDir: Path,
    val backupDir: Path,
    val backupKey: ByteArray,
    val serverSecretKey: ByteArray,
    /** How long the edit lock lasts without a heartbeat from its holder. */
    val lockTimeoutSeconds: Long = 120,
    val backupIntervalHours: Long = 24,
    val backupFirstDelaySeconds: Long = 300,
    /** Encrypted backups kept on the NAS; older ones are deleted after a good backup. */
    val backupKeep: Int = 30,
) {
    val serverPublicKey: ByteArray = Crypto.publicKeyOf(serverSecretKey)
    val dbFile: Path get() = dataDir.resolve("deaddrop.db")

    companion object {
        const val BACKUP_MARKER = ".deaddrop-backups"

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun need(k: String) = env[k]?.trim()?.takeIf { it.isNotEmpty() } ?: error("$k is not set")
            fun key(k: String): ByteArray {
                val b = runCatching { need(k).unhex() }.getOrElse { error("$k is not hex") }
                require(b.size == Crypto.KEY_BYTES) { "$k must be ${Crypto.KEY_BYTES} bytes (${Crypto.KEY_BYTES * 2} hex characters)" }
                return b
            }
            fun long(k: String, d: Long) = env[k]?.trim()?.takeIf { it.isNotEmpty() }?.toLong() ?: d
            return Config(
                port = need("DEADDROP_PORT").toInt().also { require(it in 1..65535) { "DEADDROP_PORT is out of range" } },
                dataDir = Path.of(need("DEADDROP_DATA_DIR")),
                backupDir = Path.of(need("DEADDROP_BACKUP_DIR")),
                backupKey = key("DEADDROP_BACKUP_KEY"),
                serverSecretKey = key("DEADDROP_SERVER_SECRET_KEY"),
                lockTimeoutSeconds = long("DEADDROP_LOCK_TIMEOUT_SECONDS", 120),
                backupIntervalHours = long("DEADDROP_BACKUP_INTERVAL_HOURS", 24),
                backupFirstDelaySeconds = long("DEADDROP_BACKUP_FIRST_DELAY_SECONDS", 300),
                backupKeep = long("DEADDROP_BACKUP_KEEP", 30).toInt(),
            )
        }
    }
}
