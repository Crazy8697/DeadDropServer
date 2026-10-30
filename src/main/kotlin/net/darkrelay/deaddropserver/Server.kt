package net.darkrelay.deaddropserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.math.abs

const val VERSION = "0.5.0"

/**
 * HTTP side. Plain HTTP is fine on the LAN: /v1/rpc, /v1/pair, /v1/setup and /v1/login carry only libsodium-encrypted
 * envelopes (Envelope.kt). /health and /v1/server-key are the plain endpoints and hold no budget data: the server's
 * public key is public, and the device pins it on first contact.
 */
class Server(private val config: Config, private val service: Service, private val backups: Backups?, private val now: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger(Server::class.java)

    fun start(host: String = "0.0.0.0", wait: Boolean = false): EmbeddedServer<*, *> =
        embeddedServer(CIO, port = config.port, host = host) { module() }.start(wait = wait)

    fun Application.module() {
        routing {
            get("/health") {
                val body = buildJsonObject {
                    put("status", "ok")
                    put("service", "deaddrop-server")
                    put("version", VERSION)
                    put("backup_fresh", backups?.fresh() ?: false)
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            }
            get("/v1/server-key") {
                val key = ServerKey(config.serverPublicKey.hex(), Crypto.fingerprint(config.serverPublicKey))
                call.respondText(json.encodeToString(ServerKey.serializer(), key), ContentType.Application.Json)
            }
            post("/v1/pair") { pairCall(call) }
            post("/v1/setup") { accountCall(call, "setup") }
            post("/v1/login") { accountCall(call, "login") }
            post("/v1/rpc") { rpcCall(call) }
        }
    }

    private suspend fun body(call: ApplicationCall): ByteArray? {
        val len = call.request.header("Content-Length")?.toLongOrNull()
        if (len != null && len > Envelope.MAX_BODY) return null
        val b = call.receive<ByteArray>()
        return if (b.size > Envelope.MAX_BODY) null else b
    }

    private suspend fun pairCall(call: ApplicationCall) {
        val body = body(call) ?: return call.respond(HttpStatusCode.PayloadTooLarge)
        val plain = Envelope.openPairing(body, config.serverPublicKey, config.serverSecretKey)
        val req = plain?.let { runCatching { json.decodeFromString(PairRequest.serializer(), it.decodeToString()) }.getOrNull() }
        val dev = req?.let { service.pair(it) }
        if (req == null || dev == null) {
            log.info("pairing refused")
            return call.respond(HttpStatusCode.Forbidden)
        }
        log.info("paired device {} ({}, {})", dev.id.take(8), dev.kind, dev.name)
        val reply = json.encodeToString(PairReply.serializer(), PairReply(dev.id, dev.name, dev.kind, now(), service.recoveryKeyId())).toByteArray()
        call.respondBytes(Envelope.reply(reply, req.publicKey.unhex(), config.serverSecretKey), ContentType.Application.OctetStream)
    }

    /** Setup and login: like pairing, a sealed request and a reply boxed to the new device's key. No password or code is ever logged. */
    private suspend fun accountCall(call: ApplicationCall, kind: String) {
        val body = body(call) ?: return call.respond(HttpStatusCode.PayloadTooLarge)
        val plain = Envelope.openPairing(body, config.serverPublicKey, config.serverSecretKey)?.decodeToString()
        val (dev, deviceKey) = when (kind) {
            "setup" -> plain?.let { runCatching { json.decodeFromString(SetupRequest.serializer(), it) }.getOrNull() }
                ?.let { req -> service.setup(req) { java.nio.file.Files.deleteIfExists(config.dataDir.resolve(Service.SETUP_FILE)) } to req.publicKey }
            else -> plain?.let { runCatching { json.decodeFromString(LoginRequest.serializer(), it) }.getOrNull() }
                ?.let { req -> service.login(req) to req.publicKey }
        } ?: (null to null)
        if (dev == null || deviceKey == null) {
            log.info("{} refused", kind)
            return call.respond(HttpStatusCode.Forbidden)
        }
        log.info("{}: device {} ({}, {})", if (kind == "setup") "account created" else "logged in", dev.id.take(8), dev.kind, dev.name)
        val reply = json.encodeToString(PairReply.serializer(), PairReply(dev.id, dev.name, dev.kind, now(), service.recoveryKeyId())).toByteArray()
        call.respondBytes(Envelope.reply(reply, deviceKey.unhex(), config.serverSecretKey), ContentType.Application.OctetStream)
    }

    private suspend fun rpcCall(call: ApplicationCall) {
        val body = body(call) ?: return call.respond(HttpStatusCode.PayloadTooLarge)
        val env = Envelope.parseRequest(body) ?: return call.respond(HttpStatusCode.BadRequest)
        // unknown, revoked, or a box that doesn't open: the same bare 401, so nothing is learned from it
        val key = service.activeKey(env.deviceId) ?: return call.respond(HttpStatusCode.Unauthorized)
        val plain = Crypto.open(env.cipher, env.nonce, key, config.serverSecretKey) ?: return call.respond(HttpStatusCode.Unauthorized)
        val re = env.nonce.hex()
        val resp = try {
            val req = json.decodeFromString(RpcRequest.serializer(), plain.decodeToString())
            if (abs(now() - req.ts) > Service.MAX_SKEW_MS) throw DeadDropError("stale", "request time is too far from the server's (check the device clock)")
            if (!service.rememberNonce(re)) throw DeadDropError("replay", "this request was already received")
            val result = dispatch(env.deviceId, req)
            log.info("{} {} ok", env.deviceId.take(8), req.op)
            RpcResponse(ok = true, re = re, result = result)
        } catch (e: DeadDropError) {
            log.info("{} error {}", env.deviceId.take(8), e.code)
            RpcResponse(ok = false, re = re, error = e.code, message = e.message)
        } catch (e: kotlinx.serialization.SerializationException) {
            RpcResponse(ok = false, re = re, error = "bad_request", message = "request JSON is not valid")
        } catch (e: IllegalArgumentException) {
            RpcResponse(ok = false, re = re, error = "bad_request", message = e.message)
        }
        val out = json.encodeToString(RpcResponse.serializer(), resp).toByteArray()
        call.respondBytes(Envelope.reply(out, key, config.serverSecretKey), ContentType.Application.OctetStream)
    }

    private fun <T> enc(s: KSerializer<T>, v: T): JsonElement = json.encodeToJsonElement(s, v)
    private fun <T> args(s: KSerializer<T>, req: RpcRequest): T = json.decodeFromJsonElement(s, req.args)

    private fun dispatch(device: String, req: RpcRequest): JsonElement = when (req.op) {
        "status" -> enc(Status.serializer(), service.status(device))
        "heartbeat" -> enc(LockState.serializer(), service.heartbeat(device, args(HeartbeatArgs.serializer(), req)))
        "lock.acquire" -> enc(LockState.serializer(), service.lockAcquire(device))
        "lock.release" -> enc(LockState.serializer(), service.lockRelease(device))
        "lock.takeover" -> enc(LockState.serializer(), service.lockTakeover(device).also { log.info("{} took over the edit lock", device.take(8)) })
        // a one-time pairing code for a new phone (0.3.0), shown as a QR code on the device that asked; never logged
        "pairing.new" -> enc(PairingCode.serializer(), args(PairingCodeArgs.serializer(), req).let { service.pairingCode(it.name, it.minutes) })
        "push" -> enc(PushResult.serializer(), service.push(device, args(PushArgs.serializer(), req)))
        "pull" -> enc(PullResult.serializer(), service.pull(device, args(PullArgs.serializer(), req)))
        "records" -> enc(RecordsResult.serializer(), service.records(args(RecordsArgs.serializer(), req)))
        "devices.list" -> enc(ListSerializer(DeviceInfo.serializer()), service.devices())
        "devices.revoke" -> enc(DeviceInfo.serializer(), service.revoke(args(RevokeArgs.serializer(), req).deviceId))
        // 0.4.0: a new password from a logged-in device (the only way to change it; there is no reset), the recovery key's
        // sealed copies, and log out on close. Like every op, only the op name and the device id's start are logged.
        "account.password" -> enc(PasswordChanged.serializer(), service.changePassword(device, args(PasswordArgs.serializer(), req)))
        "recovery.status" -> enc(RecoveryStatus.serializer(), service.recoveryStatus(device))
        "recovery.set" -> enc(RecoveryStatus.serializer(), service.recoverySet(device, args(RecoverySetArgs.serializer(), req)))
        "recovery.put" -> enc(RecoveryStored.serializer(), service.recoveryPut(args(RecoveryPutArgs.serializer(), req)))
        "recovery.get" -> enc(RecoveryCopy.serializer(), service.recoveryGet(device))
        "device.logout" -> enc(LoggedOut.serializer(), service.logout(device))
        else -> throw DeadDropError("unknown_op", "unknown op ${req.op}")
    }
}
