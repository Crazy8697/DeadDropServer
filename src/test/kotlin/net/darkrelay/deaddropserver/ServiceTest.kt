package net.darkrelay.deaddropserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceTest {
    private fun TestWorld.device(kind: String): String {
        val keys = Crypto.keyPair()
        return assertNotNull(fakeClockService.pair(PairRequest(fakeClockService.newPairingCode("T $kind"), kind, keys.publicKey.hex()))).id
    }

    private fun change(id: String, ts: Long, vararg f: Pair<String, Any>) = Change(UUID.randomUUID().toString(), "transaction", id, "UPDATE",
        JsonObject(f.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }), ts)

    @Test fun theLockLapsesAfterItsTimeoutWithoutAHeartbeat() = TestWorld(lockTimeoutSeconds = 60).use { w ->
        val phone = w.device("phone"); val desk = w.device("desktop")
        assertTrue(w.fakeClockService.lockAcquire(desk).youHoldIt)
        w.clock += 59_000
        assertFalse(w.fakeClockService.lockAcquire(phone).youHoldIt, "still held at 59 s")
        w.fakeClockService.heartbeat(desk, HeartbeatArgs(open = true))
        w.clock += 59_000
        assertFalse(w.fakeClockService.lockAcquire(phone).youHoldIt, "a heartbeat renewed it")
        w.clock += 2_000
        assertTrue(w.fakeClockService.lockAcquire(phone).youHoldIt, "lapsed 60 s after the last heartbeat: the phone gets it")
    }

    @Test fun anOpenDeviceThatGoesQuietIsNoLongerOpen() = TestWorld(lockTimeoutSeconds = 60).use { w ->
        val desk = w.device("desktop")
        w.fakeClockService.heartbeat(desk, HeartbeatArgs(open = true))
        assertTrue(w.fakeClockService.devices().single().open)
        w.clock += 61_000
        assertFalse(w.fakeClockService.devices().single().open)
    }

    @Test fun pushNeedsTheLockUnlessItIsAnOfflineMerge() = TestWorld().use { w ->
        val phone = w.device("phone")
        val e = assertFailsWith<DeadDropError> { w.fakeClockService.push(phone, PushArgs(listOf(change("t1", w.clock, "amount" to -100)))) }
        assertEquals("locked", e.code)
        assertEquals(1, w.fakeClockService.push(phone, PushArgs(listOf(change("t1", w.clock, "amount" to -100)), offline = true)).applied)
    }

    @Test fun aChangeFromFarInTheFutureIsRefused() = TestWorld().use { w ->
        val phone = w.device("phone")
        w.fakeClockService.lockAcquire(phone)
        val e = assertFailsWith<DeadDropError> { w.fakeClockService.push(phone, PushArgs(listOf(change("t1", w.clock + 2 * 86_400_000L, "amount" to 1)))) }
        assertEquals("bad_request", e.code)
    }

    @Test fun badShapesAreRefusedAndNothingIsHalfApplied() = TestWorld().use { w ->
        val phone = w.device("phone")
        w.fakeClockService.lockAcquire(phone)
        val good = change("t1", w.clock, "amount" to 1)
        val bad = good.copy(changeId = UUID.randomUUID().toString(), entityType = "Robert'); DROP TABLE records;--")
        assertFailsWith<DeadDropError> { w.fakeClockService.push(phone, PushArgs(listOf(good, bad))) }
        assertEquals(0L, w.fakeClockService.lastSeq())
        assertFailsWith<DeadDropError> { w.fakeClockService.push(phone, PushArgs(listOf(good.copy(operation = "UPSERT")))) }
    }

    @Test fun revokingDeletesTheKeyAndFreesTheLock() = TestWorld().use { w ->
        val phone = w.device("phone")
        assertTrue(w.fakeClockService.lockAcquire(phone).youHoldIt)
        assertNotNull(w.fakeClockService.activeKey(phone))
        w.fakeClockService.revoke(phone)
        assertNull(w.fakeClockService.activeKey(phone))
        assertNull(w.fakeClockService.lockAcquire(w.device("desktop")).holder?.takeIf { it == phone })
        assertEquals("not_found", assertFailsWith<DeadDropError> { w.fakeClockService.revoke(phone) }.code)
    }

    @Test fun aPairingCodeWorksOnce() = TestWorld().use { w ->
        val code = w.fakeClockService.newPairingCode("Phone")
        assertNotNull(w.fakeClockService.pair(PairRequest(code, "phone", Crypto.keyPair().publicKey.hex())))
        assertNull(w.fakeClockService.pair(PairRequest(code, "phone", Crypto.keyPair().publicKey.hex())))
    }

    @Test fun theMasterCopyIsReadableJson() = TestWorld().use { w ->
        val phone = w.device("phone")
        w.fakeClockService.lockAcquire(phone)
        w.fakeClockService.push(phone, PushArgs(listOf(change("t1", w.clock, "amount" to -1234, "payee" to "Sample Store"))))
        val raw = java.sql.DriverManager.getConnection("jdbc:sqlite:${w.config.dbFile}").use { c ->
            c.createStatement().executeQuery("SELECT data FROM records").use { it.next(); it.getString(1) }
        }
        assertEquals("""{"amount":-1234,"payee":"Sample Store"}""", raw)
    }
}
