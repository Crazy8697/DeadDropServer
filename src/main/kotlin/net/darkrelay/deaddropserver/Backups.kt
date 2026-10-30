package net.darkrelay.deaddropserver

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.name

@Serializable
data class BackupStatus(
    val lastAttempt: Long? = null,
    val lastSuccess: Long? = null,
    val lastFile: String? = null,
    val ok: Boolean = false,
    val error: String? = null,
)

/**
 * Encrypted backups of the master copy to the NAS folder: a consistent copy (VACUUM INTO), encrypted with libsodium
 * secretstream under the backup key, written as a .part file, decrypted again and compared, then renamed into place.
 * The folder must hold the marker file, so nothing is written to an empty mount point when the NAS is missing.
 * Without the backup key the backups can't be read: there is no recovery key.
 */
class Backups(private val config: Config, private val db: Db, private val now: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger(Backups::class.java)
    private val statusFile: Path get() = config.dataDir.resolve("backup-status.json")

    @Synchronized
    fun runBackup(): Path {
        val t = now()
        try {
            val dir = config.backupDir
            require(Files.isRegularFile(dir.resolve(Config.BACKUP_MARKER))) { "backup folder is not ready (no ${Config.BACKUP_MARKER} marker; is the NAS mounted?)" }
            val stamp = STAMP.format(Instant.ofEpochMilli(t))
            val copy = config.dataDir.resolve(".backup-$stamp.db")
            val part = dir.resolve("deaddrop-$stamp$EXT.part")
            val final = dir.resolve("deaddrop-$stamp$EXT")
            Files.deleteIfExists(copy)
            try {
                db.copyTo(copy)
                val plainHash = MessageDigest.getInstance("SHA-256").let { md -> Files.newInputStream(copy).use { md.update(it.readAllBytes()) }; md.digest() }
                ownerOnly(Files.createFile(part))
                Files.newOutputStream(part).use { out ->
                    out.write(MAGIC)
                    Files.newInputStream(copy).use { Crypto.encryptStream(config.backupKey, it, out) }
                }
                // read it back before it counts
                val md = MessageDigest.getInstance("SHA-256")
                decrypt(part, DigestOutputStream(OutputStream.nullOutputStream(), md))
                check(md.digest().contentEquals(plainHash)) { "backup did not read back the same" }
                Files.move(part, final, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(copy)
                Files.deleteIfExists(part)
            }
            prune(dir)
            writeStatus(BackupStatus(t, t, final.name, true, null))
            log.info("backup written: {}", final.name)
            return final
        } catch (e: Exception) {
            val prev = status()
            writeStatus(prev.copy(lastAttempt = t, ok = false, error = e.message ?: e.javaClass.simpleName))
            log.warn("backup failed: {}", e.message)
            throw e
        }
    }

    private fun prune(dir: Path) {
        val all = Files.list(dir).use { s -> s.filter { it.name.startsWith("deaddrop-") && it.name.endsWith(EXT) }.sorted().toList() }
        all.dropLast(config.backupKeep).forEach { Files.deleteIfExists(it) }
    }

    fun decrypt(file: Path, out: OutputStream) {
        Files.newInputStream(file).use { input ->
            val magic = input.readNBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "not a Dead Drop backup" }
            Crypto.decryptStream(config.backupKey, input, out)
        }
    }

    /** Restores [file] into a NEW database file [target] (never the live one), then checks it. Returns record and change counts. */
    fun restore(file: Path, target: Path): Pair<Long, Long> {
        require(!Files.exists(target)) { "$target already exists; restore only writes a new file" }
        require(target.toAbsolutePath().normalize() != config.dbFile.toAbsolutePath().normalize()) { "restore never writes the live database" }
        val part = target.resolveSibling(target.name + ".part")
        Files.deleteIfExists(part)
        try {
            ownerOnly(Files.createFile(part))
            Files.newOutputStream(part).use { decrypt(file, it) }
            DriverManager.getConnection("jdbc:sqlite:${part.toAbsolutePath()}").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("PRAGMA integrity_check").use { rs -> rs.next(); check(rs.getString(1) == "ok") { "restored database fails integrity_check" } }
                }
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(part)
        }
        return DriverManager.getConnection("jdbc:sqlite:${target.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                val r = st.executeQuery("SELECT COUNT(*) FROM records").use { it.next(); it.getLong(1) }
                val ch = st.executeQuery("SELECT COUNT(*) FROM changes").use { it.next(); it.getLong(1) }
                r to ch
            }
        }
    }

    fun verify(file: Path): Long {
        var n = 0L
        decrypt(file, object : OutputStream() {
            override fun write(b: Int) { n++ }
            override fun write(b: ByteArray, off: Int, len: Int) { n += len }
        })
        return n
    }

    fun status(): BackupStatus = runCatching { json.decodeFromString<BackupStatus>(Files.readString(statusFile)) }.getOrDefault(BackupStatus())

    /** Last good backup within 36 hours (one missed daily run is tolerated). */
    fun fresh(): Boolean = status().let { it.ok && it.lastSuccess != null && now() - it.lastSuccess < 36L * 3600 * 1000 }

    private fun writeStatus(s: BackupStatus) {
        val tmp = statusFile.resolveSibling("backup-status.json.tmp")
        Files.writeString(tmp, json.encodeToString(BackupStatus.serializer(), s))
        Files.move(tmp, statusFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Runs a backup after the first delay, then every interval; a failure is logged and tried again next time. */
    fun schedule(): Thread = Thread({
        try {
            Thread.sleep(config.backupFirstDelaySeconds * 1000)
            while (true) {
                runCatching { runBackup() }
                Thread.sleep(config.backupIntervalHours * 3600 * 1000)
            }
        } catch (_: InterruptedException) {
        }
    }, "backups").apply { isDaemon = true; start() }

    /** Backups and restored copies are readable by the server's own user only. */
    private fun ownerOnly(p: Path) {
        runCatching { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")) }
    }

    companion object {
        const val EXT = ".ddbak"
        private val MAGIC = "DDBAK1\n".toByteArray()
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
