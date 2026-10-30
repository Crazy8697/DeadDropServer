package net.darkrelay.deaddropserver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * End-to-end check against a running server over plain HTTP, with synthetic data only: pairing, device keys, the
 * edit lock (and its timeout), push and pull, last-change-wins notes, replay and tamper refusals, ciphertext on the
 * wire, and revoking. Used by the unit tests and, against a test container, by `deaddrop-server selftest`.
 */
class SelfTest(
    private val url: String,
    private val serverPublic: ByteArray,
    private val codePhone: String,
    private val codeDesktop: String,
    private val lockTimeoutSeconds: Long,
    private val say: (String) -> Unit = ::println,
) {
    private var checks = 0
    private fun check(ok: Boolean, what: String) {
        if (!ok) throw IllegalStateException("FAILED: $what")
        checks++; say("ok  $what")
    }

    private inline fun <reified E : Throwable> fails(what: String, block: () -> Unit, test: (E) -> Boolean = { true }) {
        val e = runCatching(block).exceptionOrNull()
        check(e is E && test(e), "$what (got ${e?.let { it::class.simpleName + ": " + it.message } ?: "no error"})")
    }

    private fun ch(type: String, id: String, op: String, ts: Long, vararg f: Pair<String, Any?>) = Change(
        UUID.randomUUID().toString(), type, id, op,
        kotlinx.serialization.json.JsonObject(f.associate { (k, v) ->
            k to when (v) {
                null -> kotlinx.serialization.json.JsonNull
                is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
            }
        }), ts,
    )

    fun run(): Int {
        val phone = Client(url, serverPublic)
        val desktop = Client(url, serverPublic)

        // ---- device key issue (pairing)
        val p = phone.pair(codePhone, "phone")
        check(p.kind == "phone" && phone.deviceId == p.deviceId, "phone paired with its one-time code, device id ${p.deviceId.take(8)}")
        fails<Client.HttpError>("the same code can't pair a second device", { Client(url, serverPublic).pair(codePhone, "phone") }) { it.status == 403 }
        desktop.pair(codeDesktop, "desktop")
        check(desktop.deviceId != phone.deviceId, "desktop paired with its own code and its own key")
        val imposter = Client(url, serverPublic, deviceId = phone.deviceId)
        fails<Client.HttpError>("a request with the phone's id but another key is refused (401)", { imposter.status() }) { it.status == 401 }
        val wrongServer = Client(url, Crypto.keyPair().publicKey)
        fails<Client.HttpError>("pairing sealed to the wrong server key is refused (403)", { wrongServer.pair("AAAA-AAAA", "phone") }) { it.status == 403 }

        // ---- encrypted connection over plain HTTP
        val marker = "SAMPLE-MARKER-" + UUID.randomUUID().toString().take(8)
        val (body, nonce) = phone.seal("status", kotlinx.serialization.json.buildJsonObject { put("note", kotlinx.serialization.json.JsonPrimitive(marker)) })
        check(!String(body, Charsets.ISO_8859_1).contains(marker) && !String(body, Charsets.ISO_8859_1).contains("status"), "request on the wire is ciphertext (no op name, no data)")
        check(phone.send(body, nonce).ok, "the server opens it and answers")
        val replay = phone.send(body, nonce)
        check(!replay.ok && replay.error == "replay", "the same request sent again is refused as a replay")
        val stale = phone.seal("status", ts = System.currentTimeMillis() - 10 * 60_000).let { (b, n) -> phone.send(b, n) }
        check(!stale.ok && stale.error == "stale", "a request ten minutes old is refused")
        val (b2, _) = phone.seal("status")
        b2[b2.size - 5] = (b2[b2.size - 5].toInt() xor 1).toByte()
        fails<Client.HttpError>("a tampered request is refused (401)", { phone.send(b2, ByteArray(24)) }) { it.status == 401 }
        val rawReply = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url.trimEnd('/') + "/v1/rpc")).POST(HttpRequest.BodyPublishers.ofByteArray(phone.seal("status").first)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        ).body()
        check(!String(rawReply, Charsets.ISO_8859_1).contains("lastSeq"), "the reply on the wire is ciphertext too")

        // ---- edit lock
        check(phone.acquire().youHoldIt, "phone takes the edit lock")
        val seen = desktop.acquire()
        check(!seen.youHoldIt && seen.holder == phone.deviceId, "desktop is view-only while the phone holds it, and sees who does")
        val t0 = System.currentTimeMillis()
        fails<DeadDropError>("desktop can't push while the phone holds the lock", {
            desktop.push(listOf(ch("account", "acct-1", "INSERT", t0, "name" to "Sample Checking")))
        }) { it.code == "locked" }

        // ---- storage and sync: the phone's change log shape, money in cents
        val changes = listOf(
            ch("account", "acct-1", "INSERT", t0, "name" to "Sample Checking", "type" to "BANK", "starting_balance" to 123_45L, "deleted" to false),
            ch("envelope", "env-1", "INSERT", t0, "name" to "Sample Groceries", "target" to 200_00L, "deleted" to false),
            ch("transaction", "tx-1", "INSERT", t0, "account_id" to "acct-1", "amount" to -4_50L, "payee" to marker, "deleted" to false),
        )
        val pushed = phone.push(changes)
        check(pushed.applied == 3 && pushed.lastSeq >= 3, "phone pushes 3 changes (applied ${pushed.applied}, server seq ${pushed.lastSeq})")
        check(phone.push(changes).duplicates == 3, "the same 3 changes pushed again are not applied twice")
        val pulled = desktop.pull(0)
        check(pulled.changes.count { it.deviceId == phone.deviceId } >= 3, "desktop pulls the phone's changes")
        val recs = desktop.records().records.associateBy { it.entityType + "/" + it.entityId }
        check(recs["transaction/tx-1"]?.data?.get("amount")?.toString() == "-450", "the master copy holds the amount in cents, as sent")
        val upd = phone.push(listOf(ch("transaction", "tx-1", "UPDATE", t0 + 10, "amount" to -5_00L)), baseSeq = pushed.lastSeq)
        val tx1 = desktop.records().records.single { it.entityId == "tx-1" }
        check(upd.applied == 1 && tx1.data["amount"].toString() == "-500" && tx1.data["payee"].toString().contains(marker), "an update merges into the record and keeps its other fields")
        phone.push(listOf(ch("envelope", "env-1", "DELETE", t0 + 20, "deleted" to true)), baseSeq = upd.lastSeq)
        check(desktop.records().records.single { it.entityId == "env-1" }.deleted, "a delete keeps the row as a tombstone")

        // ---- lock handoff: closing releases it; the next device takes it
        check(phone.heartbeat(open = false).holder == null, "phone closing releases the lock")
        check(desktop.acquire().youHoldIt, "desktop takes the lock once the phone has closed")

        // ---- offline merge and last-change-wins notes
        val desk = desktop.push(listOf(ch("account", "acct-1", "UPDATE", t0 + 1000, "name" to "Sample Checking (desktop)")), baseSeq = desktop.pull(0).lastSeq)
        val older = phone.push(listOf(ch("account", "acct-1", "UPDATE", t0 + 500, "name" to "Sample Checking (phone offline)")), baseSeq = upd.lastSeq, offline = true)
        check(older.lost == 1 && older.conflicts.single().loserDevice == phone.deviceId, "an older offline edit loses to the newer one, with a note")
        val newer = phone.push(listOf(ch("account", "acct-1", "UPDATE", t0 + 2000, "name" to "Sample Checking (phone, later)")), baseSeq = upd.lastSeq, offline = true)
        check(newer.applied == 1 && newer.conflicts.single().loserDevice == desktop.deviceId, "a newer offline edit wins and notes the desktop change it replaced")
        check(desktop.pull(desk.lastSeq).conflicts.any { it.entityId == "acct-1" }, "the desktop sees the note for the change it lost")

        // ---- open devices and lock timeout
        desktop.heartbeat(open = true)
        check(phone.status().devices.single { it.id == desktop.deviceId }.open, "the server tracks which device is open")
        if (lockTimeoutSeconds <= 10) {
            Thread.sleep((lockTimeoutSeconds + 1) * 1000)
            check(phone.acquire().youHoldIt, "after ${lockTimeoutSeconds}s with no heartbeat the lock lapses and the phone gets it")
        } else say("--  lock timeout check skipped (timeout ${lockTimeoutSeconds}s is too long to wait for)")

        // ---- revoke: the phone is lost; the desktop revokes it
        val r = desktop.revoke(phone.deviceId!!)
        check(r.revokedAt != null, "desktop revokes the lost phone")
        fails<Client.HttpError>("the revoked phone's next request is refused (401)", { phone.status() }) { it.status == 401 }
        check(desktop.status().lock.holder == null, "revoking frees the edit lock the phone held")
        check(desktop.devices().single { it.id == phone.deviceId }.revokedAt != null, "the device list shows it revoked")
        say("selftest passed: $checks checks")
        return checks
    }
}
