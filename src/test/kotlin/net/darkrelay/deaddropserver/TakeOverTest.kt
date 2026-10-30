package net.darkrelay.deaddropserver

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Take over (0.3.0) and pairing codes asked for by a logged-in device (the desktop's QR). Test keys, invented names. */
class TakeOverTest {
    private val pw = "test-pass-for-unit"

    private fun change(id: String, at: Long, name: String = "Groceries") =
        Change("c-$id-${System.nanoTime()}", "envelope", id, "UPDATE", buildJsonObject { put("name", JsonPrimitive(name)) }, at)

    /** A phone and a desktop on one account. */
    private fun TestWorld.two(): Pair<Client, Client> {
        val phone = client().also { it.setup(service.startSetup(config.dataDir.resolve(Service.SETUP_FILE), "fp")!!, "tester", pw, "Test phone", "phone") }
        val desk = client().also { it.login("tester", pw, "Test desktop", "desktop") }
        return phone to desk
    }

    @Test fun takeOverMovesTheLockAndTheOldHolderTurnsReadOnly(): Unit = TestWorld().start().use { w ->
        val (phone, desk) = w.two()
        assertTrue(phone.acquire().youHoldIt)
        assertFalse(desk.acquire().youHoldIt, "the phone holds it")
        val l = desk.takeover()
        assertTrue(l.youHoldIt)
        assertEquals(desk.deviceId, l.holder)
        assertNull(l.takenBy, "the device that took over has no notice")
        // the phone's next heartbeat: not the holder, and the notice says who took over
        val p = phone.heartbeat(open = true)
        assertFalse(p.youHoldIt)
        assertEquals(desk.deviceId, p.holder)
        assertEquals(desk.deviceId, p.takenBy)
        assertEquals("Test desktop", p.takenByName)
        assertTrue((p.takenAt ?: 0) > 0)
        // the phone can't take it back by acquiring, and its changes wait, even as offline changes
        assertFalse(phone.acquire().youHoldIt)
        assertEquals("taken_over", assertFailsWith<DeadDropError> { phone.push(listOf(change("e1", System.currentTimeMillis()))) }.code)
        assertEquals("taken_over", assertFailsWith<DeadDropError> { phone.push(listOf(change("e1", System.currentTimeMillis())), offline = true) }.code)
        assertEquals(1, desk.push(listOf(change("e2", System.currentTimeMillis()))).applied)
        // the desktop lets go; the phone takes the lock again, the notice ends, and its waiting change goes in
        desk.heartbeat(open = false)
        val back = phone.acquire()
        assertTrue(back.youHoldIt)
        assertNull(back.takenBy)
        assertEquals(1, phone.push(listOf(change("e1", System.currentTimeMillis()))).applied)
    }

    @Test fun heartbeatsFromTheOldHolderNeitherRenewNorReleaseTheLock(): Unit = TestWorld(lockTimeoutSeconds = 120).use { w ->
        val s = w.fakeClockService
        val keys = { Crypto.keyPair().publicKey.hex() }
        val a = s.pair(PairRequest(s.newPairingCode("Phone A"), "phone", keys()))!!.id
        val b = s.pair(PairRequest(s.newPairingCode("Desk B"), "desktop", keys()))!!.id
        assertTrue(s.lockAcquire(a).youHoldIt)
        w.clock += 1_000
        assertTrue(s.lockTakeover(b).youHoldIt)
        // A closing doesn't free B's lock
        s.heartbeat(a, HeartbeatArgs(open = false))
        assertEquals(b, s.lockAcquire(b).holder)
        // A's heartbeats don't keep it alive: B's lock lapses 120 s after B's own last word
        w.clock += 100_000
        assertFalse(s.heartbeat(a, HeartbeatArgs(open = true)).youHoldIt)
        w.clock += 25_000
        val after = s.heartbeat(a, HeartbeatArgs(open = true))
        assertNull(after.holder, "lapsed: nobody holds it")
        assertEquals(b, after.takenBy, "A's notice stays until A holds the lock again")
        assertTrue(s.lockAcquire(a).youHoldIt)
        assertNull(s.lockAcquire(a).takenBy)
    }

    @Test fun aSchema2DatabaseMigratesAndKeepsItsDevices() {
        val dir = java.nio.file.Files.createTempDirectory("deaddrop-migrate")
        try {
            val file = dir.resolve("deaddrop.db")
            Db(file).use { db ->
                db.tx {
                    exec("INSERT INTO devices (id, name, kind, public_key, paired_at) VALUES ('d1', 'Old phone', 'phone', 'ab', 1)")
                    exec("ALTER TABLE devices DROP COLUMN lock_taken_by")
                    exec("ALTER TABLE devices DROP COLUMN lock_taken_at")
                    // and what schema 4 (0.4.0) added
                    exec("ALTER TABLE account DROP COLUMN recovery_key_id")
                    exec("DROP TABLE recovery_keys")
                    exec("UPDATE meta SET value = '2' WHERE key = 'schema_version'")
                }
            }
            Db(file).use { db ->
                db.tx {
                    assertEquals("4", query("SELECT value FROM meta WHERE key = 'schema_version'") { it.getString(1) }.single())
                    assertEquals(listOf("Old phone"), query("SELECT name FROM devices WHERE lock_taken_by IS NULL") { it.getString(1) })
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun takingOverAFreeLockIsJustTakingIt(): Unit = TestWorld().start().use { w ->
        val (phone, desk) = w.two()
        assertTrue(desk.takeover().youHoldIt)
        assertNull(phone.status().lock.takenBy, "nobody had it, so nobody gets a notice")
        assertTrue(desk.takeover().youHoldIt, "taking over your own lock just renews it")
    }

    @Test fun aLoggedInDeviceGetsAOneTimePairingCode(): Unit = TestWorld().start().use { w ->
        val (_, desk) = w.two()
        val before = System.currentTimeMillis()
        val pc = desk.pairingCode("Phone", 10)
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(pc.code))
        assertTrue(pc.expiresAt in (before + 600_000 - 5_000)..(System.currentTimeMillis() + 600_000))
        val newPhone = w.client()
        val r = newPhone.pair(pc.code, "phone", "Test tablet")
        assertEquals("Test tablet", r.name, "the device's own name wins over the code's")
        assertEquals(r.deviceId, newPhone.status().you.id)
        // used once only
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().pair(pc.code, "phone") }.status)
    }

    @Test fun anExpiredPairingCodeIsRefused(): Unit = TestWorld().start().use { w ->
        val (_, desk) = w.two()
        val pc = desk.pairingCode()
        w.db.tx { exec("UPDATE pairing_codes SET expires_at = ?", System.currentTimeMillis() - 1) }
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().pair(pc.code, "phone") }.status)
        // a pairing code needs a logged-in device: a stranger's request doesn't open
        val stranger = Client(w.url, w.config.serverPublicKey, deviceId = desk.deviceId)
        assertEquals(401, assertFailsWith<Client.HttpError> { stranger.pairingCode() }.status)
    }

    @Test fun takeOverAndPairingCodesLeaveNothingSecretInTheLog(): Unit = TestWorld().start().use { w ->
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val seen = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        try {
            val (phone, desk) = w.two()
            phone.acquire()
            desk.takeover()
            runCatching { phone.push(listOf(change("e1", System.currentTimeMillis()))) }
            val pc = desk.pairingCode()
            w.client().pair(pc.code, "phone", "Test tablet")
            val all = seen.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            assertTrue(all.contains("took over the edit lock"))
            for (secret in listOf(pc.code, pc.code.replace("-", ""), pw, desk.keys.secretKey.hex(), phone.keys.secretKey.hex())) {
                assertFalse(all.contains(secret), "the log holds a secret")
            }
        } finally {
            root.detachAppender(seen)
        }
    }
}
