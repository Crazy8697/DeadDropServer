package net.darkrelay.deaddropserver

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recovery key (0.4.0): the account's key id, set at setup from any device, and each device's sealed copy, passed
 * from one device to another through the server, which can't open it. Test keys, invented names.
 */
class RecoveryKeyTest {
    private val pw = "test-pass-for-unit"

    private fun keyId() = Crypto.random(16).hex()
    private fun TestWorld.code() = service.startSetup(config.dataDir.resolve(Service.SETUP_FILE), "fp")!!
    private fun TestWorld.copies() = db.tx { query("SELECT device_id, key_id, sealed FROM recovery_keys") { Triple(it.getString(1), it.getString(2), it.getString(3)) } }

    /** A desktop that made the account (with [id] as its recovery key id) and a phone logged in to it. */
    private fun TestWorld.two(id: String?): Pair<Client, Client> {
        val desk = client().also { it.setup(code(), "tester", pw, "Test desktop", "desktop", id) }
        val phone = client().also { it.login("tester", pw, "Test phone", "phone") }
        return desk to phone
    }

    @Test fun setupStoresTheRecoveryKeyIdFromADesktopOrAPhone(): Unit = TestWorld().start().use { w ->
        val id = keyId()
        val r = w.client().setup(w.code(), "tester", pw, "Test desktop", "desktop", id)
        assertEquals("desktop", r.kind, "the desktop program can make the account")
        assertEquals(id, r.recoveryKeyId)
        assertEquals(id, w.db.tx { query("SELECT recovery_key_id FROM account") { it.getString(1) }.single() })
    }

    @Test fun setupWithoutARecoveryKeyIdLeavesItEmpty(): Unit = TestWorld().start().use { w ->
        val r = w.client().setup(w.code(), "tester", pw, "Test phone", "phone")
        assertNull(r.recoveryKeyId)
        assertNull(w.db.tx { query("SELECT recovery_key_id FROM account") { it.getString(1) }.single() })
    }

    @Test fun aBadRecoveryKeyIdIsARefusedSetupAndCountsAsAFailure(): Unit = TestWorld().start().use { w ->
        val code = w.code()
        for (bad in listOf(keyId().uppercase(), keyId().take(30), keyId() + "00", "z".repeat(32), "")) {
            assertEquals(403, assertFailsWith<Client.HttpError> { w.client().setup(code, "tester", pw, "Test phone", "phone", bad) }.status)
        }
        assertFalse(w.service.hasAccount())
        assertEquals(5, w.db.tx { query("SELECT COUNT(*) FROM failed_pairings") { it.getInt(1) }.single() })
        w.db.tx { exec("DELETE FROM failed_pairings") }
        w.client().setup(code, "tester", pw, "Test phone", "phone", keyId())
    }

    @Test fun thePairReplyCarriesTheKeyIdOnSetupLoginAndPair(): Unit = TestWorld().start().use { w ->
        val id = keyId()
        val desk = w.client()
        assertEquals(id, desk.setup(w.code(), "tester", pw, "Test desktop", "desktop", id).recoveryKeyId)
        assertEquals(id, w.client().login("tester", pw, "Test phone", "phone").recoveryKeyId)
        assertEquals(id, w.client().pair(desk.pairingCode().code, "phone", "Test tablet").recoveryKeyId)
    }

    @Test fun oneDeviceSealsTheKeyToAnotherAndOnlyThatOneOpensIt(): Unit = TestWorld().start().use { w ->
        val id = keyId()
        val recoveryKey = Crypto.random(32)
        val (desk, phone) = w.two(id)
        // the desktop made the key; it keeps its own copy on the server, sealed to its own key
        desk.recoveryPut(desk.deviceId!!, id, Crypto.seal(recoveryKey, desk.keys.publicKey).hex())
        val s = desk.recoveryStatus()
        assertEquals(id, s.keyId)
        assertTrue(s.mine)
        assertEquals(listOf(phone.deviceId), s.needing.map { it.deviceId }, "the phone still needs its copy")
        assertEquals(phone.keys.publicKey.hex(), s.needing.single().publicKey)
        assertEquals("Test phone", s.needing.single().name)
        assertEquals("phone", s.needing.single().kind)
        val before = phone.recoveryStatus()
        assertFalse(before.mine)
        assertNull(phone.recoveryGet().sealed)
        // the desktop seals the key to the phone's public key, as status gave it
        val sealed = Crypto.seal(recoveryKey, s.needing.single().publicKey.unhex()).hex()
        assertTrue(desk.recoveryPut(phone.deviceId!!, id, sealed).stored)
        assertTrue(phone.recoveryStatus().mine)
        assertTrue(desk.recoveryStatus().needing.isEmpty())
        val got = phone.recoveryGet()
        assertEquals(id, got.keyId)
        assertEquals(sealed, got.sealed)
        // only the phone's secret key opens it, and it opens to the key
        assertContentEquals(recoveryKey, Crypto.sealOpen(got.sealed!!.unhex(), phone.keys.publicKey, phone.keys.secretKey))
        assertNull(Crypto.sealOpen(got.sealed!!.unhex(), desk.keys.publicKey, desk.keys.secretKey))
        assertNull(Crypto.sealOpen(got.sealed!!.unhex(), w.config.serverPublicKey, w.config.serverSecretKey), "the server's key doesn't open it")
        // the server holds ciphertext only: not the key, in the table or anywhere in the database file
        for ((_, _, stored) in w.copies()) {
            assertFalse(stored.contains(recoveryKey.hex()))
            assertTrue(stored.length == (recoveryKey.size + Crypto.SEAL_BYTES) * 2)
        }
        assertFalse(Files.readAllBytes(w.config.dbFile).decodeToString().contains(recoveryKey.hex()))
        // and the server code never opens a sealed copy: sealOpen is only for the pairing, setup and login envelopes
        val main = Path.of("src/main/kotlin/net/darkrelay/deaddropserver")
        val users = Files.list(main).use { files -> files.toList() }.filter { Files.readString(it).contains("sealOpen") }.map { it.fileName.toString() }.sorted()
        assertEquals(listOf("Crypto.kt", "Envelope.kt"), users)
        assertTrue(Files.readString(main.resolve("Envelope.kt")).lines().filter { it.contains("sealOpen") }.all { it.contains("serverPublic, serverSecret") })
    }

    @Test fun withoutAKeyIdNoDeviceIsNeedingAndGetIsEmpty(): Unit = TestWorld().start().use { w ->
        val (desk, phone) = w.two(null)
        val s = desk.recoveryStatus()
        assertNull(s.keyId)
        assertFalse(s.mine)
        assertTrue(s.needing.isEmpty())
        assertEquals(RecoveryCopy(null, null), phone.recoveryGet())
        assertEquals("conflict", assertFailsWith<DeadDropError> {
            desk.recoveryPut(phone.deviceId!!, keyId(), Crypto.seal(Crypto.random(32), phone.keys.publicKey).hex())
        }.code, "no copies before the account has a key id")
    }

    @Test fun aCopyForARevokedOrUnknownDeviceIsRefused(): Unit = TestWorld().start().use { w ->
        val id = keyId()
        val (desk, phone) = w.two(id)
        val sealed = Crypto.seal(Crypto.random(32), phone.keys.publicKey).hex()
        assertEquals("not_found", assertFailsWith<DeadDropError> { desk.recoveryPut(UUID.randomUUID().toString(), id, sealed) }.code)
        desk.revoke(phone.deviceId!!)
        assertEquals("not_found", assertFailsWith<DeadDropError> { desk.recoveryPut(phone.deviceId!!, id, sealed) }.code)
        assertTrue(w.copies().isEmpty())
        assertTrue(desk.recoveryStatus().needing.isEmpty(), "a revoked device needs nothing")
    }

    @Test fun aCopyUnderAnotherKeyIdOrNotSealedHexIsRefused(): Unit = TestWorld().start().use { w ->
        val id = keyId()
        val (desk, phone) = w.two(id)
        val sealed = Crypto.seal(Crypto.random(32), phone.keys.publicKey).hex()
        assertEquals("conflict", assertFailsWith<DeadDropError> { desk.recoveryPut(phone.deviceId!!, keyId(), sealed) }.code)
        for (bad in listOf(Crypto.random(47).hex(), Crypto.random(257).hex(), "not hex at all", sealed + "0")) {
            assertEquals("bad_request", assertFailsWith<DeadDropError> { desk.recoveryPut(phone.deviceId!!, id, bad) }.code)
        }
        assertTrue(w.copies().isEmpty())
        // a second put for the same device replaces its copy
        desk.recoveryPut(phone.deviceId!!, id, sealed)
        val again = Crypto.seal(Crypto.random(32), phone.keys.publicKey).hex()
        desk.recoveryPut(phone.deviceId!!, id, again)
        assertEquals(listOf(again), w.copies().map { it.third })
    }

    @Test fun recoverySetOnlyFillsAnEmptyKeyId(): Unit = TestWorld().start().use { w ->
        val (desk, phone) = w.two(null)
        val id = keyId()
        assertEquals("bad_request", assertFailsWith<DeadDropError> { desk.recoverySet(id.uppercase()) }.code)
        val s = desk.recoverySet(id)
        assertEquals(id, s.keyId)
        assertEquals(listOf(phone.deviceId), s.needing.map { it.deviceId })
        assertEquals(id, desk.recoverySet(id).keyId, "the same id again changes nothing")
        assertEquals("conflict", assertFailsWith<DeadDropError> { phone.recoverySet(keyId()) }.code)
        assertEquals(id, w.service.recoveryKeyId())
        assertEquals(id, w.client().login("tester", pw, "Test laptop", "desktop").recoveryKeyId)
    }

    @Test fun recoverySetOnAnAccountMadeWithAKeyIdIsAConflict(): Unit = TestWorld().start().use { w ->
        val id = keyId()
        val (desk, _) = w.two(id)
        assertEquals("conflict", assertFailsWith<DeadDropError> { desk.recoverySet(keyId()) }.code)
        assertEquals(id, desk.recoverySet(id).keyId)
    }

    @Test fun logOutReleasesTheLockRevokesTheDeviceAndDropsItsCopy(): Unit = TestWorld().start().use { w ->
        val id = keyId()
        val (desk, phone) = w.two(id)
        desk.recoveryPut(phone.deviceId!!, id, Crypto.seal(Crypto.random(32), phone.keys.publicKey).hex())
        desk.recoveryPut(desk.deviceId!!, id, Crypto.seal(Crypto.random(32), desk.keys.publicKey).hex())
        assertTrue(phone.acquire().youHoldIt)
        assertTrue(phone.logout().loggedOut)
        val lock = desk.status().lock
        assertNull(lock.holder, "the lock is free")
        assertTrue(desk.acquire().youHoldIt)
        assertEquals(401, assertFailsWith<Client.HttpError> { phone.status() }.status, "its key is gone")
        assertNull(w.service.activeKey(phone.deviceId!!))
        assertNotNull(desk.devices().single { it.id == phone.deviceId }.revokedAt)
        assertEquals(listOf(desk.deviceId), w.copies().map { it.first }, "its sealed copy is gone, the desktop's stays")
        // logging in again is a new device, which needs a new copy
        val back = w.client().also { it.login("tester", pw, "Test phone", "phone") }
        assertEquals(listOf(back.deviceId), desk.recoveryStatus().needing.map { it.deviceId })
    }

    @Test fun revokingADeviceDeletesItsCopy(): Unit = TestWorld().start().use { w ->
        val id = keyId()
        val (desk, phone) = w.two(id)
        desk.recoveryPut(phone.deviceId!!, id, Crypto.seal(Crypto.random(32), phone.keys.publicKey).hex())
        assertEquals(1, w.copies().size)
        desk.revoke(phone.deviceId!!)
        assertTrue(w.copies().isEmpty())
    }

    @Test fun aSchema3DatabaseMigratesAndKeepsItsAccount() {
        val dir = Files.createTempDirectory("deaddrop-migrate")
        try {
            val file = dir.resolve("deaddrop.db")
            Db(file).use { db ->
                db.tx {
                    exec("INSERT INTO account (id, username, password_hash, created_at) VALUES (1, 'tester', 'h', 1)")
                    exec("INSERT INTO devices (id, name, kind, public_key, paired_at) VALUES ('d1', 'Old phone', 'phone', 'ab', 1)")
                    exec("ALTER TABLE account DROP COLUMN recovery_key_id")
                    exec("DROP TABLE recovery_keys")
                    exec("UPDATE meta SET value = '3' WHERE key = 'schema_version'")
                }
            }
            Db(file).use { db ->
                db.tx {
                    assertEquals("4", query("SELECT value FROM meta WHERE key = 'schema_version'") { it.getString(1) }.single())
                    assertEquals(listOf("tester"), query("SELECT username FROM account WHERE recovery_key_id IS NULL") { it.getString(1) })
                    assertEquals(listOf("Old phone"), query("SELECT name FROM devices") { it.getString(1) })
                    assertEquals(0, query("SELECT COUNT(*) FROM recovery_keys") { it.getInt(1) }.single())
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
