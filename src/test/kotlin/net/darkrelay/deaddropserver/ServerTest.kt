package net.darkrelay.deaddropserver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    @Test fun theWholeSelfTestPassesOverPlainHttp() = TestWorld(lockTimeoutSeconds = 2).start().use { w ->
        val n = SelfTest(w.url, w.config.serverPublicKey, w.service.newPairingCode("Test phone"), w.service.newPairingCode("Test desktop"), 2) {}.run()
        assertTrue(n >= 30, "ran $n checks")
    }

    @Test fun healthIsPlainAndHoldsNoData() = TestWorld().start().use { w ->
        val body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(w.url + "/health")).build(), HttpResponse.BodyHandlers.ofString()).body()
        assertTrue(body.contains("\"status\":\"ok\"") && body.contains("\"backup_fresh\":false"), body)
        w.backups.runBackup()
        val after = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(w.url + "/health")).build(), HttpResponse.BodyHandlers.ofString()).body()
        assertTrue(after.contains("\"backup_fresh\":true"), after)
    }

    @Test fun anExpiredCodeDoesNotPair() = TestWorld().start().use { w ->
        val code = w.service.newPairingCode("Late phone", minutes = 0)
        val e = assertFailsWith<Client.HttpError> { w.client().pair(code, "phone") }
        assertEquals(403, e.status)
    }

    @Test fun fiveWrongCodesLockPairingForAWhile() = TestWorld().start().use { w ->
        val good = w.service.newPairingCode("Real phone")
        repeat(5) { assertFailsWith<Client.HttpError> { w.client().pair("WRONG-CODE", "phone") } }
        val e = assertFailsWith<Client.HttpError> { w.client().pair(good, "phone") }
        assertEquals(403, e.status, "even the right code is refused during the lockout")
    }

    @Test fun anUnknownKindDoesNotPair(): Unit = TestWorld().start().use { w ->
        assertFailsWith<Client.HttpError> { w.client().pair(w.service.newPairingCode("Toaster"), "toaster") }
    }

    @Test fun revokingFromTheServerCliPathDeletesTheKey() = TestWorld().start().use { w ->
        val c = w.client().also { it.pair(w.service.newPairingCode("Old desktop"), "desktop") }
        assertTrue(c.status().you.revokedAt == null)
        w.service.revoke(c.deviceId!!)
        assertEquals(null, w.service.activeKey(c.deviceId!!))
        assertEquals(401, assertFailsWith<Client.HttpError> { c.status() }.status)
        // the revoked id stays dead: only a new pairing code makes a new device
        val again = Client(w.url, w.config.serverPublicKey, keys = c.keys, deviceId = c.deviceId)
        assertEquals(401, assertFailsWith<Client.HttpError> { again.status() }.status)
    }

    @Test fun anUnknownOpIsAnEncryptedError() = TestWorld().start().use { w ->
        val c = w.client().also { it.pair(w.service.newPairingCode("Phone"), "phone") }
        val r = c.raw("drop.tables")
        assertFalse(r.ok); assertEquals("unknown_op", r.error)
    }
}
