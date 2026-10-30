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
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A new password from a logged-in device (0.4.0), and no reset of any kind. Plus the log check for everything 0.4.0
 * adds. Test keys, invented names.
 */
class PasswordChangeTest {
    private val pw = "test-pass-for-unit"
    private val newPw = "new-test-pass-for-unit"

    private fun TestWorld.code() = service.startSetup(config.dataDir.resolve(Service.SETUP_FILE), "fp")!!
    private fun TestWorld.hash() = db.tx { query("SELECT password_hash FROM account") { it.getString(1) }.single() }

    /** A phone that made the account and a desktop logged in to it. */
    private fun TestWorld.two(): Pair<Client, Client> {
        val phone = client().also { it.setup(code(), "tester", pw, "Test phone", "phone") }
        val desk = client().also { it.login("tester", pw, "Test desktop", "desktop") }
        return phone to desk
    }

    @Test fun aLoggedInDeviceSetsANewPasswordWithoutTheOldOne(): Unit = TestWorld().start().use { w ->
        val (phone, desk) = w.two()
        val old = w.hash()
        val before = System.currentTimeMillis()
        assertTrue(desk.changePassword(newPw).changedAt >= before)
        assertTrue(w.hash().startsWith("\$argon2id\$") && w.hash() != old)
        assertFalse(Files.readAllBytes(w.config.dbFile).decodeToString().contains(newPw))
        // the old password no longer logs in, the new one does
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().login("tester", pw, "Test laptop", "desktop") }.status)
        w.client().login("tester", newPw, "Test laptop", "desktop").deviceId
        // and the devices already logged in stay logged in
        assertEquals(phone.deviceId, phone.status().you.id)
        assertEquals(desk.deviceId, desk.status().you.id)
    }

    @Test fun theNewPasswordFollowsTheSetupRules(): Unit = TestWorld().start().use { w ->
        val (phone, _) = w.two()
        val old = w.hash()
        assertEquals("bad_request", assertFailsWith<DeadDropError> { phone.changePassword("short") }.code)
        assertEquals("bad_request", assertFailsWith<DeadDropError> { phone.changePassword("x".repeat(1025)) }.code)
        assertEquals(old, w.hash())
        w.client().login("tester", pw, "Test laptop", "desktop")
    }

    @Test fun aRevokedDeviceCantChangeThePassword(): Unit = TestWorld().start().use { w ->
        val (phone, desk) = w.two()
        phone.revoke(desk.deviceId!!)
        val old = w.hash()
        // through the real HTTP path: its key is gone, so the envelope check refuses it before any op runs
        assertEquals(401, assertFailsWith<Client.HttpError> { desk.changePassword(newPw) }.status)
        val stranger = Client(w.url, w.config.serverPublicKey, deviceId = phone.deviceId)
        assertEquals(401, assertFailsWith<Client.HttpError> { stranger.changePassword(newPw) }.status, "another key with a real device id")
        assertEquals(old, w.hash())
        w.client().login("tester", pw, "Test laptop", "desktop")
        // and a device that logged itself out can't either
        phone.logout()
        assertEquals(401, assertFailsWith<Client.HttpError> { phone.changePassword(newPw) }.status)
        assertEquals(old, w.hash())
    }

    @Test fun thereIsNoPasswordResetPath(): Unit = TestWorld().start().use { w ->
        val (phone, _) = w.two()
        val main = Path.of("src/main/kotlin/net/darkrelay/deaddropserver")
        val sources = Files.list(main).use { files -> files.toList() }.associate { it.fileName.toString() to Files.readString(it) }
        // no CLI command touches the password
        val cli = sources.getValue("Main.kt").lowercase()
        for (word in listOf("password", "reset", "passwd", "recover")) assertFalse(cli.contains(word), "Main.kt mentions $word")
        // the one op that sets a password is account.password, which needs an active device's envelope
        val ops = Regex("""^\s*"([a-z.]+)" ->""", RegexOption.MULTILINE).findAll(sources.getValue("Server.kt")).map { it.groupValues[1] }.toList()
        assertTrue("status" in ops && "device.logout" in ops, "the op list was read: $ops")
        assertEquals(listOf("account.password"), ops.filter { it.contains("password") || it.contains("reset") || it.contains("passwd") })
        val routes = Regex("""post\("([^"]+)"\)""").findAll(sources.getValue("Server.kt")).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("/v1/pair", "/v1/setup", "/v1/login", "/v1/rpc"), routes)
        // the password hash is written in two places only: setup's INSERT and account.password's UPDATE
        val all = sources.values.joinToString("\n")
        assertEquals(1, Regex("INSERT INTO account").findAll(all).count())
        assertEquals(1, Regex("UPDATE account SET password_hash").findAll(all).count())
        assertEquals(1, Regex("password_hash = \\?").findAll(all).count())
        // over the wire: reset-like ops are unknown, reset-like paths don't exist
        for (op in listOf("account.reset", "password.reset", "account.recover", "reset", "account.setPassword", "admin.password")) {
            assertEquals("unknown_op", phone.raw(op).error, op)
        }
        for (path in listOf("/v1/reset", "/v1/password", "/v1/recover", "/v1/account")) {
            val status = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(w.url + path)).POST(HttpRequest.BodyPublishers.ofByteArray(ByteArray(64))).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()
            assertEquals(404, status, path)
        }
        // setup refuses once the account exists, even with a setup code it would otherwise take
        assertNull(w.service.startSetup(w.config.dataDir.resolve(Service.SETUP_FILE), "fp"))
        w.db.tx { exec("INSERT OR REPLACE INTO setup_code (id, code_hash, created_at) VALUES (1, ?, 1)", Crypto.sha256("TESTCODE".toByteArray()).hex()) }
        val old = w.hash()
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().setup("TEST-CODE", "tester", newPw, "Test laptop", "desktop") }.status)
        assertEquals(old, w.hash())
        assertEquals(403, assertFailsWith<Client.HttpError> { w.client().login("tester", newPw, "Test laptop", "desktop") }.status)
    }

    @Test fun passwordsCodesAndRecoveryCopiesNeverReachTheLog(): Unit = TestWorld().start().use { w ->
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val seen = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        try {
            val keyId = Crypto.random(16).hex()
            val otherId = Crypto.random(16).hex()
            val recoveryKey = Crypto.random(32)
            val code = w.code()
            runCatching { w.client().setup(code, "tester", pw, "Test desktop", "desktop", keyId.uppercase()) }
            val desk = w.client().also { it.setup(code, "tester", pw, "Test desktop", "desktop", keyId) }
            val phone = w.client().also { it.login("tester", pw, "Test phone", "phone") }
            val pc = desk.pairingCode()
            val tablet = w.client().also { it.pair(pc.code, "phone", "Test tablet") }
            val sealed = Crypto.seal(recoveryKey, phone.keys.publicKey).hex()
            val sealedTablet = Crypto.seal(recoveryKey, tablet.keys.publicKey).hex()
            desk.recoveryStatus()
            desk.recoveryPut(phone.deviceId!!, keyId, sealed)
            desk.recoveryPut(tablet.deviceId!!, keyId, sealedTablet)
            runCatching { desk.recoveryPut(phone.deviceId!!, otherId, sealed) }
            runCatching { desk.recoverySet(otherId) }
            desk.recoverySet(keyId)
            phone.recoveryGet()
            runCatching { phone.changePassword("short") }
            phone.changePassword(newPw)
            runCatching { w.client().login("tester", pw, "Test laptop", "desktop") }
            w.client().login("tester", newPw, "Test laptop", "desktop")
            tablet.acquire()
            tablet.logout()
            runCatching { tablet.changePassword(pw) }
            val all = seen.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            for (op in listOf("account.password ok", "recovery.put ok", "recovery.get ok", "device.logout ok", "error conflict")) {
                assertTrue(all.contains(op), "the log has $op")
            }
            val secrets = listOf(pw, newPw, code, code.replace("-", ""), pc.code, pc.code.replace("-", ""), recoveryKey.hex(), sealed, sealedTablet,
                sealed.take(32), keyId, keyId.uppercase(), otherId, desk.keys.secretKey.hex(), phone.keys.secretKey.hex())
            for (secret in secrets) assertFalse(all.contains(secret), "the log holds a secret")
        } finally {
            root.detachAppender(seen)
        }
    }
}
