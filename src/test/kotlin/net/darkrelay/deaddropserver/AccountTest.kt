package net.darkrelay.deaddropserver

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account (0.2.0): the one-time setup code, login, and the device keys they issue. Test keys, invented names. */
class AccountTest {
    private val pw = "test-pass-for-unit"

    private fun TestWorld.setupFile() = config.dataDir.resolve(Service.SETUP_FILE)
    private fun TestWorld.code() = service.startSetup(setupFile(), "test-fingerprint")!!

    @Test fun theSetupCodeIsWrittenToItsFileAndWorksOnce(): Unit = TestWorld().start().use { w ->
        val code = w.code()
        val file = w.setupFile()
        assertTrue(Files.readString(file).contains(code), "the file holds the code")
        assertTrue(Files.readString(file).contains("test-fingerprint"))
        runCatching {
            val perms = Files.getPosixFilePermissions(file)
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), perms)
        } // Windows has no POSIX permissions; the container (Linux) does
        assertFalse(w.service.hasAccount())
        val phone = w.client()
        val r = phone.setup(code, "tester", pw, "Test phone", "phone")
        assertEquals("phone", r.kind)
        assertEquals(r.deviceId, phone.status().you.id, "the issued device key works for the encrypted calls")
        assertTrue(w.service.hasAccount())
        assertFalse(Files.exists(file), "the code's file is gone once it's used")
        // a second use is refused, even with everything else right
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().setup(code, "tester2", pw, "Other phone", "phone") }.status)
        // and a restart with an account makes no new code
        assertNull(w.service.startSetup(w.setupFile(), "test-fingerprint"))
        assertFalse(Files.exists(file))
    }

    @Test fun aNewStartReplacesAnUnusedCode(): Unit = TestWorld().start().use { w ->
        val first = w.code()
        val second = w.code()
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().setup(first, "tester", pw, "Test phone", "phone") }.status)
        w.client().setup(second, "tester", pw, "Test phone", "phone")
    }

    @Test fun fiveWrongSetupCodesLockItForAWhile(): Unit = TestWorld().start().use { w ->
        val code = w.code()
        repeat(5) { assertFailsWith<Client.HttpError> { w.client().setup("WRNG-CODE", "tester", pw, "Test phone", "phone") } }
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().setup(code, "tester", pw, "Test phone", "phone") }.status,
            "even the right code is refused during the lockout")
        assertFalse(w.service.hasAccount())
        assertTrue(Files.exists(w.setupFile()), "the code is still good once the window passes")
    }

    @Test fun setupRefusesAShortPasswordOrBlankUsername(): Unit = TestWorld().start().use { w ->
        val code = w.code()
        assertFailsWith<Client.HttpError> { w.client().setup(code, "tester", "short", "Test phone", "phone") }
        assertFailsWith<Client.HttpError> { w.client().setup(code, "  ", pw, "Test phone", "phone") }
        assertFalse(w.service.hasAccount())
        w.client().setup(code, "tester", pw, "Test phone", "phone")
    }

    @Test fun loginIssuesANewDeviceKeyAndTheDeviceListShowsIt(): Unit = TestWorld().start().use { w ->
        val phone = w.client().also { it.setup(w.code(), "Tester", pw, "Test phone", "phone") }
        val desk = w.client()
        val r = desk.login("tester", pw, "Test desktop", "desktop")
        assertEquals("desktop", r.kind)
        val ids = phone.devices().filter { it.revokedAt == null }.map { it.id }.toSet()
        assertEquals(setOf(phone.deviceId, desk.deviceId), ids)
        assertEquals(r.deviceId, desk.status().you.id)
        assertTrue(phone.keys.publicKey.hex() != desk.keys.publicKey.hex(), "each device has its own key")
    }

    @Test fun aWrongPasswordOrUsernameFailsAndFiveLockLoginOut(): Unit = TestWorld().start().use { w ->
        w.client().setup(w.code(), "tester", pw, "Test phone", "phone")
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().login("tester", "wrong-password", "Desk", "desktop") }.status)
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().login("someone", pw, "Desk", "desktop") }.status)
        repeat(3) { assertFailsWith<Client.HttpError> { w.client().login("tester", "wrong-password", "Desk", "desktop") } }
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().login("tester", pw, "Desk", "desktop") }.status,
            "the right password is refused during the lockout")
        // once the window passes it works again
        w.db.tx { exec("DELETE FROM failed_pairings") }
        assertNotNull(w.client().login("tester", pw, "Desk", "desktop").deviceId)
    }

    @Test fun loginBeforeAnyAccountIsRefused(): Unit = TestWorld().start().use { w ->
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().login("tester", pw, "Desk", "desktop") }.status)
    }

    @Test fun revokingALoggedInDeviceCutsItOff(): Unit = TestWorld().start().use { w ->
        val phone = w.client().also { it.setup(w.code(), "tester", pw, "Test phone", "phone") }
        val desk = w.client().also { it.login("tester", pw, "Test desktop", "desktop") }
        desk.status()
        phone.revoke(desk.deviceId!!)
        assertEquals(401, assertFailsWith<Client.HttpError> { desk.status() }.status)
        // logging in again makes a new device, with a new key
        val again = w.client().also { it.login("tester", pw, "Test desktop", "desktop") }
        assertTrue(again.deviceId != desk.deviceId)
        again.status()
    }

    @Test fun thePasswordIsStoredOnlyAsAnArgon2idHash() = TestWorld().start().use { w ->
        w.client().setup(w.code(), "tester", pw, "Test phone", "phone")
        val hash = w.db.tx { query("SELECT password_hash FROM account") { it.getString(1) }.single() }
        assertTrue(hash.startsWith("\$argon2id\$"), hash.take(12))
        assertFalse(hash.contains(pw))
        assertFalse(Files.readAllBytes(w.config.dbFile).decodeToString().contains(pw))
    }

    @Test fun theServerKeyIsPublishedForPinning(): Unit = TestWorld().start().use { w ->
        val body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(w.url + "/v1/server-key")).build(), HttpResponse.BodyHandlers.ofString()).body()
        val key = json.decodeFromString(ServerKey.serializer(), body)
        assertEquals(w.config.serverPublicKey.hex(), key.publicKey)
        assertEquals(Crypto.fingerprint(w.config.serverPublicKey), key.fingerprint)
    }

    @Test fun aSetupSealedToAnotherServerKeyIsRefused(): Unit = TestWorld().start().use { w ->
        val code = w.code()
        val wrong = Client(w.url, Crypto.keyPair().publicKey)
        assertEquals(403, assertFailsWith<Client.HttpError> { wrong.setup(code, "tester", pw, "Test phone", "phone") }.status)
    }

    @Test fun noSetupCodeOrPasswordEverReachesTheLog(): Unit = TestWorld().start().use { w ->
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val seen = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        try {
            val code = w.code()
            runCatching { w.client().setup("WRNG-CODE", "tester", pw, "Test phone", "phone") }
            w.client().setup(code, "tester", pw, "Test phone", "phone")
            runCatching { w.client().login("tester", "wrong-password", "Desk", "desktop") }
            w.client().login("tester", pw, "Desk", "desktop")
            val all = seen.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            assertTrue(seen.list.isNotEmpty(), "the log was captured")
            for (secret in listOf(code, code.replace("-", ""), pw, "wrong-password", "WRNG")) assertFalse(all.contains(secret), "log holds $secret")
        } finally {
            root.detachAppender(seen)
        }
    }
}
