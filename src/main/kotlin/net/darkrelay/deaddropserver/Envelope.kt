package net.darkrelay.deaddropserver

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Byte layout of the encrypted envelopes (docs/PROTOCOL.md). Plain HTTP carries only these bytes, so anyone on the
 * LAN sees ciphertext, a device id and a nonce, never budget data.
 *
 *   request  (POST /v1/rpc):  0x01 | device id (16 bytes, UUID) | nonce (24) | crypto_box(JSON, nonce, server public, device secret)
 *   reply    (200):           0x01 | nonce (24) | crypto_box(JSON, nonce, device public, server secret)
 *   pairing  (POST /v1/pair): 0x01 | crypto_box_seal(JSON, server public)
 *   pair ok  (200):           0x01 | nonce (24) | crypto_box(JSON, nonce, device public, server secret)
 */
object Envelope {
    const val VERSION: Byte = 1
    const val MAX_BODY = 8 * 1024 * 1024

    class Request(val deviceId: String, val nonce: ByteArray, val cipher: ByteArray)

    fun request(deviceId: String, plain: ByteArray, serverPublic: ByteArray, deviceSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = Crypto.random(Crypto.NONCE_BYTES)
        val cipher = Crypto.box(plain, nonce, serverPublic, deviceSecret)
        val id = UUID.fromString(deviceId)
        val out = ByteBuffer.allocate(1 + 16 + nonce.size + cipher.size)
            .put(VERSION).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).put(nonce).put(cipher)
        return out.array() to nonce
    }

    fun parseRequest(body: ByteArray): Request? {
        if (body.size < 1 + 16 + Crypto.NONCE_BYTES + Crypto.MAC_BYTES || body[0] != VERSION) return null
        val b = ByteBuffer.wrap(body, 1, body.size - 1)
        val id = UUID(b.getLong(), b.getLong()).toString()
        val nonce = ByteArray(Crypto.NONCE_BYTES).also { b.get(it) }
        val cipher = ByteArray(b.remaining()).also { b.get(it) }
        return Request(id, nonce, cipher)
    }

    fun reply(plain: ByteArray, devicePublic: ByteArray, serverSecret: ByteArray): ByteArray {
        val nonce = Crypto.random(Crypto.NONCE_BYTES)
        return byteArrayOf(VERSION) + nonce + Crypto.box(plain, nonce, devicePublic, serverSecret)
    }

    fun openReply(body: ByteArray, serverPublic: ByteArray, deviceSecret: ByteArray): ByteArray? {
        if (body.size < 1 + Crypto.NONCE_BYTES + Crypto.MAC_BYTES || body[0] != VERSION) return null
        val nonce = body.copyOfRange(1, 1 + Crypto.NONCE_BYTES)
        return Crypto.open(body.copyOfRange(1 + Crypto.NONCE_BYTES, body.size), nonce, serverPublic, deviceSecret)
    }

    fun pairing(plain: ByteArray, serverPublic: ByteArray): ByteArray = byteArrayOf(VERSION) + Crypto.seal(plain, serverPublic)

    fun openPairing(body: ByteArray, serverPublic: ByteArray, serverSecret: ByteArray): ByteArray? {
        if (body.size < 1 + Crypto.SEAL_BYTES || body[0] != VERSION) return null
        return Crypto.sealOpen(body.copyOfRange(1, body.size), serverPublic, serverSecret)
    }
}
