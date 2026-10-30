package net.darkrelay.deaddropserver

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/** A throwaway server setup: temp data and backup folders, fresh test keys. Synthetic data only. */
class TestWorld(lockTimeoutSeconds: Long = 120, keep: Int = 30) : AutoCloseable {
    val root: Path = Files.createTempDirectory("deaddrop-test")
    val backupDir: Path = Files.createDirectories(root.resolve("nas/deaddrop")).also { Files.createFile(it.resolve(Config.BACKUP_MARKER)) }
    var clock = System.currentTimeMillis()
    val config = Config(
        port = ServerSocket(0).use { it.localPort },
        dataDir = root.resolve("data"),
        backupDir = backupDir,
        backupKey = Crypto.random(32),
        serverSecretKey = Crypto.keyPair().secretKey,
        lockTimeoutSeconds = lockTimeoutSeconds,
        backupKeep = keep,
    )
    val db = Db(config.dbFile)
    val service = Service(db, config)
    val fakeClockService = Service(db, config) { clock }
    val backups = Backups(config, db)
    val url get() = "http://127.0.0.1:${config.port}"

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    fun start(): TestWorld { server = Server(config, service, backups).start(host = "127.0.0.1"); return this }

    fun client() = Client(url, config.serverPublicKey)

    override fun close() {
        server?.stop(100, 500)
        db.close()
        root.toFile().deleteRecursively()
    }
}
