package net.darkrelay.deaddropserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupsTest {
    private fun TestWorld.fill(n: Int) {
        val keys = Crypto.keyPair()
        val dev = service.pair(PairRequest(service.newPairingCode("Phone"), "phone", keys.publicKey.hex()))!!.id
        service.lockAcquire(dev)
        service.push(dev, PushArgs((1..n).map {
            Change(UUID.randomUUID().toString(), "transaction", "tx-$it", "INSERT",
                JsonObject(mapOf("amount" to JsonPrimitive(-it * 100L), "payee" to JsonPrimitive("Sample payee $it"))), System.currentTimeMillis())
        }))
    }

    @Test fun aBackupIsEncryptedAndRestoresToANewFile() = TestWorld().use { w ->
        w.fill(25)
        val file = w.backups.runBackup()
        assertTrue(file.name.matches(Regex("deaddrop-\\d{8}-\\d{6}Z\\.ddbak")), file.name)
        val bytes = Files.readAllBytes(file)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Sample payee"), "no plaintext in the backup")
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("SQLite format"), "not a bare SQLite file")
        val target = w.root.resolve("restored.db")
        val (records, changes) = w.backups.restore(file, target)
        assertEquals(25L, records); assertEquals(25L, changes)
        assertTrue(w.backups.status().ok)
        assertTrue(w.backups.fresh())
    }

    @Test fun theWrongKeyOrATamperedFileDoesNotRestore() = TestWorld().use { w ->
        w.fill(3)
        val file = w.backups.runBackup()
        val other = TestWorld()
        try {
            val wrongKey = Backups(Config(1, other.config.dataDir, w.backupDir, Crypto.random(32), other.config.serverSecretKey), other.db)
            assertFailsWith<IllegalArgumentException> { wrongKey.restore(file, w.root.resolve("x.db")) }
        } finally { other.close() }
        val bytes = Files.readAllBytes(file); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
        val bad = w.root.resolve("tampered.ddbak"); Files.write(bad, bytes)
        assertFailsWith<IllegalArgumentException> { w.backups.restore(bad, w.root.resolve("y.db")) }
        val cut = w.root.resolve("cut.ddbak"); Files.write(cut, Files.readAllBytes(file).copyOf(Files.size(file).toInt() - 10))
        assertFailsWith<IllegalArgumentException> { w.backups.restore(cut, w.root.resolve("z.db")) }
        assertFalse(Files.exists(w.root.resolve("x.db")) || Files.exists(w.root.resolve("y.db")) || Files.exists(w.root.resolve("z.db")))
    }

    @Test fun restoreNeverOverwrites() = TestWorld().use { w ->
        w.fill(1)
        val file = w.backups.runBackup()
        assertFailsWith<IllegalArgumentException> { w.backups.restore(file, w.config.dbFile) }
        val exists = Files.createFile(w.root.resolve("exists.db"))
        assertFailsWith<IllegalArgumentException> { w.backups.restore(file, exists) }
    }

    @Test fun noMarkerMeansNoBackupAndAFailedStatus() = TestWorld().use { w ->
        Files.delete(w.backupDir.resolve(Config.BACKUP_MARKER))
        assertFailsWith<IllegalArgumentException> { w.backups.runBackup() }
        assertFalse(w.backups.status().ok)
        assertTrue(w.backups.status().error!!.contains("marker"))
        assertFalse(w.backups.fresh())
        assertEquals(0, Files.list(w.backupDir).use { it.count() }.toInt())
    }

    @Test fun oldBackupsArePrunedToTheKeepCount() = TestWorld(keep = 2).use { w ->
        w.fill(1)
        listOf("deaddrop-20200101-000000Z.ddbak", "deaddrop-20200102-000000Z.ddbak", "deaddrop-20200103-000000Z.ddbak")
            .forEach { Files.write(w.backupDir.resolve(it), byteArrayOf(1)) }
        val newest = w.backups.runBackup()
        val left = Files.list(w.backupDir).use { s -> s.map { it.name }.filter { it.endsWith(".ddbak") }.sorted().toList() }
        assertEquals(listOf("deaddrop-20200103-000000Z.ddbak", newest.name), left)
    }

    @Test fun streamRoundTripsAtChunkEdges() {
        val key = Crypto.random(32)
        for (n in listOf(0, 1, 65_535, 65_536, 65_537, 3 * 65_536, 200_001)) {
            val data = Crypto.random(n.coerceAtLeast(1)).copyOf(n)
            val enc = ByteArrayOutputStream().also { Crypto.encryptStream(key, ByteArrayInputStream(data), it) }.toByteArray()
            val dec = ByteArrayOutputStream().also { Crypto.decryptStream(key, ByteArrayInputStream(enc), it) }.toByteArray()
            assertContentEquals(data, dec, "size $n")
        }
    }
}
