package net.darkrelay.deaddropserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A device's side of the protocol, used by the tests and the live self test (and a reference for the phone and
 * desktop sync code later). The device's secret key never leaves it; the server only ever gets the public key.
 */
class Client(
    private val baseUrl: String,
    private val serverPublicKey: ByteArray,
    val keys: Crypto.KeyPair = Crypto.keyPair(),
    var deviceId: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    class HttpError(val status: Int) : Exception("HTTP $status")

    private fun post(path: String, body: ByteArray): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    fun pair(code: String, kind: String, name: String? = null): PairReply {
        val req = json.encodeToString(PairRequest.serializer(), PairRequest(code, kind, keys.publicKey.hex(), name)).toByteArray()
        val resp = post("/v1/pair", Envelope.pairing(req, serverPublicKey))
        if (resp.statusCode() != 200) throw HttpError(resp.statusCode())
        val plain = Envelope.openReply(resp.body(), serverPublicKey, keys.secretKey) ?: error("pairing reply did not open: wrong server key?")
        return json.decodeFromString(PairReply.serializer(), plain.decodeToString()).also { deviceId = it.deviceId }
    }

    fun setup(setupCode: String, username: String, password: String, name: String, kind: String, recoveryKeyId: String? = null): PairReply =
        account("/v1/setup", json.encodeToString(SetupRequest.serializer(), SetupRequest(setupCode, username, password, name, kind, keys.publicKey.hex(), recoveryKeyId)))

    fun login(username: String, password: String, name: String, kind: String): PairReply =
        account("/v1/login", json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password, name, kind, keys.publicKey.hex())))

    private fun account(path: String, req: String): PairReply {
        val resp = post(path, Envelope.pairing(req.toByteArray(), serverPublicKey))
        if (resp.statusCode() != 200) throw HttpError(resp.statusCode())
        val plain = Envelope.openReply(resp.body(), serverPublicKey, keys.secretKey) ?: error("reply did not open: wrong server key?")
        return json.decodeFromString(PairReply.serializer(), plain.decodeToString()).also { deviceId = it.deviceId }
    }

    /** The raw bytes of a request, for tests that replay or tamper with one. */
    fun seal(op: String, args: JsonObject = JsonObject(emptyMap()), ts: Long = clock()): Pair<ByteArray, ByteArray> {
        val plain = json.encodeToString(RpcRequest.serializer(), RpcRequest(op, ts, args)).toByteArray()
        return Envelope.request(deviceId ?: error("not paired"), plain, serverPublicKey, keys.secretKey)
    }

    fun send(body: ByteArray, nonce: ByteArray): RpcResponse {
        val resp = post("/v1/rpc", body)
        if (resp.statusCode() != 200) throw HttpError(resp.statusCode())
        val plain = Envelope.openReply(resp.body(), serverPublicKey, keys.secretKey) ?: error("reply did not open")
        val r = json.decodeFromString(RpcResponse.serializer(), plain.decodeToString())
        check(r.re == nonce.hex()) { "reply is not for this request" }
        return r
    }

    fun raw(op: String, args: JsonObject = JsonObject(emptyMap())): RpcResponse = seal(op, args).let { (b, n) -> send(b, n) }

    fun <T> call(op: String, out: KSerializer<T>, args: JsonObject = JsonObject(emptyMap())): T {
        val r = raw(op, args)
        if (!r.ok) throw DeadDropError(r.error ?: "error", r.message ?: "")
        return json.decodeFromJsonElement(out, r.result!!)
    }

    private fun <A> obj(s: KSerializer<A>, a: A) = json.encodeToJsonElement(s, a) as JsonObject

    fun status() = call("status", Status.serializer())
    fun heartbeat(open: Boolean = true) = call("heartbeat", LockState.serializer(), obj(HeartbeatArgs.serializer(), HeartbeatArgs(open)))
    fun acquire() = call("lock.acquire", LockState.serializer())
    fun release() = call("lock.release", LockState.serializer())
    fun takeover() = call("lock.takeover", LockState.serializer())
    fun pairingCode(name: String = "Phone", minutes: Long = 10) =
        call("pairing.new", PairingCode.serializer(), obj(PairingCodeArgs.serializer(), PairingCodeArgs(name, minutes)))
    fun push(changes: List<Change>, baseSeq: Long = 0, offline: Boolean = false) =
        call("push", PushResult.serializer(), obj(PushArgs.serializer(), PushArgs(changes, baseSeq, offline)))
    fun pull(sinceSeq: Long = 0, limit: Int = 500) = call("pull", PullResult.serializer(), obj(PullArgs.serializer(), PullArgs(sinceSeq, limit)))
    fun records(afterType: String = "", afterId: String = "", limit: Int = 500) =
        call("records", RecordsResult.serializer(), obj(RecordsArgs.serializer(), RecordsArgs(afterType, afterId, limit)))
    fun devices() = call("devices.list", ListSerializer(DeviceInfo.serializer()))
    fun revoke(id: String) = call("devices.revoke", DeviceInfo.serializer(), obj(RevokeArgs.serializer(), RevokeArgs(id)))
    fun changePassword(password: String) = call("account.password", PasswordChanged.serializer(), obj(PasswordArgs.serializer(), PasswordArgs(password)))
    fun recoveryStatus() = call("recovery.status", RecoveryStatus.serializer())
    fun recoverySet(keyId: String) = call("recovery.set", RecoveryStatus.serializer(), obj(RecoverySetArgs.serializer(), RecoverySetArgs(keyId)))
    fun recoveryPut(deviceId: String, keyId: String, sealed: String) =
        call("recovery.put", RecoveryStored.serializer(), obj(RecoveryPutArgs.serializer(), RecoveryPutArgs(deviceId, keyId, sealed)))
    fun recoveryGet() = call("recovery.get", RecoveryCopy.serializer())
    fun logout() = call("device.logout", LoggedOut.serializer())
}
