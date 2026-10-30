package net.darkrelay.deaddropserver

import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.SecretStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Every cryptographic operation Dead Drop uses, all from libsodium (through lazysodium, with the native library
 * bundled in its jar). Nothing here is home-made crypto: only libsodium's standard constructions are called.
 *  - crypto_box (X25519 + XSalsa20-Poly1305): the request and reply envelopes between a paired device and the server
 *  - crypto_box_seal: the pairing request, which the device sends before the server knows its key
 *  - crypto_secretstream_xchacha20poly1305: encrypted backups
 *  - crypto_pwhash_str (Argon2id): the account password, stored only as this hash (0.2.0)
 * The recovery key's copies (0.4.0) are crypto_box_seal too, but the devices seal and open them: the server only
 * stores the sealed bytes and never calls sealOpen on them.
 */
object Crypto {
    private val na = SodiumJava()

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val MAC_BYTES = 16
    const val SEAL_BYTES = 48
    private const val STREAM_HEADER_BYTES = 24
    private const val STREAM_A_BYTES = 17
    private const val TAG_MESSAGE: Byte = 0
    private const val TAG_FINAL: Byte = 3

    init {
        check(na.sodium_init() >= 0) { "libsodium did not start" }
    }

    class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    fun random(n: Int): ByteArray = ByteArray(n).also { na.randombytes_buf(it, n) }

    fun keyPair(): KeyPair {
        val pk = ByteArray(KEY_BYTES); val sk = ByteArray(KEY_BYTES)
        check(na.crypto_box_keypair(pk, sk) == 0)
        return KeyPair(pk, sk)
    }

    fun publicKeyOf(secretKey: ByteArray): ByteArray {
        require(secretKey.size == KEY_BYTES)
        val pk = ByteArray(KEY_BYTES)
        check(na.crypto_scalarmult_base(pk, secretKey) == 0)
        return pk
    }

    fun box(message: ByteArray, nonce: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray {
        val out = ByteArray(message.size + MAC_BYTES)
        check(na.crypto_box_easy(out, message, message.size.toLong(), nonce, theirPublic, mySecret) == 0)
        return out
    }

    /** Null when the box doesn't open (wrong key, tampered, truncated). */
    fun open(cipher: ByteArray, nonce: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray? {
        if (cipher.size < MAC_BYTES || nonce.size != NONCE_BYTES) return null
        val out = ByteArray(cipher.size - MAC_BYTES)
        return if (na.crypto_box_open_easy(out, cipher, cipher.size.toLong(), nonce, theirPublic, mySecret) == 0) out else null
    }

    fun seal(message: ByteArray, theirPublic: ByteArray): ByteArray {
        val out = ByteArray(message.size + SEAL_BYTES)
        check(na.crypto_box_seal(out, message, message.size.toLong(), theirPublic) == 0)
        return out
    }

    fun sealOpen(cipher: ByteArray, myPublic: ByteArray, mySecret: ByteArray): ByteArray? {
        if (cipher.size < SEAL_BYTES) return null
        val out = ByteArray(cipher.size - SEAL_BYTES)
        return if (na.crypto_box_seal_open(out, cipher, cipher.size.toLong(), myPublic, mySecret) == 0) out else null
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    // Argon2id at libsodium's interactive limits (64 MiB): the container's memory limit leaves room for it
    private const val PWHASH_STR_BYTES = 128
    private const val PWHASH_OPSLIMIT = 2L
    private const val PWHASH_MEMLIMIT = 67_108_864L

    /** The password's Argon2id hash string (salt and limits inside it), for storing instead of the password. */
    fun passwordHash(password: ByteArray): String {
        val out = ByteArray(PWHASH_STR_BYTES)
        check(na.crypto_pwhash_str(out, password, password.size.toLong(), PWHASH_OPSLIMIT, com.sun.jna.NativeLong(PWHASH_MEMLIMIT)) == 0) { "password hashing failed" }
        return String(out, 0, out.indexOf(0).let { if (it < 0) out.size else it }, Charsets.US_ASCII)
    }

    fun passwordMatches(hash: String, password: ByteArray): Boolean {
        val h = hash.toByteArray(Charsets.US_ASCII) + 0
        return na.crypto_pwhash_str_verify(h, password, password.size.toLong()) == 0
    }

    /** Short fingerprint of a public key, for people to compare (first 8 bytes of its SHA-256, as hex). */
    fun fingerprint(publicKey: ByteArray): String = sha256(publicKey).copyOf(8).hex()

    private const val CHUNK = 64 * 1024

    /** Encrypts [input] to [output] with secretstream: header, then [u32 length][chunk] frames, the last one tagged FINAL. */
    fun encryptStream(key: ByteArray, input: InputStream, output: OutputStream) {
        require(key.size == KEY_BYTES)
        val state = SecretStream.State()
        val header = ByteArray(STREAM_HEADER_BYTES)
        check(na.crypto_secretstream_xchacha20poly1305_init_push(state, header, key) == 0)
        output.write(header)
        val buf = ByteArray(CHUNK)
        var n = input.readNBytes(buf, 0, CHUNK)
        while (true) {
            val next = ByteArray(CHUNK)
            val m = if (n == CHUNK) input.readNBytes(next, 0, CHUNK) else 0
            val last = n < CHUNK || m == 0
            val cipher = ByteArray(n + STREAM_A_BYTES)
            val msg = buf.copyOf(n)
            check(na.crypto_secretstream_xchacha20poly1305_push(state, cipher, null, msg, n.toLong(), null, 0, if (last) TAG_FINAL else TAG_MESSAGE) == 0)
            output.write(u32(cipher.size)); output.write(cipher)
            if (last) break
            System.arraycopy(next, 0, buf, 0, m); n = m
        }
    }

    /** Decrypts a stream written by [encryptStream]; throws when it is tampered, truncated or the key is wrong. */
    fun decryptStream(key: ByteArray, input: InputStream, output: OutputStream) {
        require(key.size == KEY_BYTES)
        val header = input.readNBytes(STREAM_HEADER_BYTES)
        require(header.size == STREAM_HEADER_BYTES) { "backup is truncated" }
        val state = SecretStream.State()
        require(na.crypto_secretstream_xchacha20poly1305_init_pull(state, header, key) == 0) { "bad backup header" }
        while (true) {
            val lenBytes = input.readNBytes(4)
            require(lenBytes.size == 4) { "backup is truncated (no final chunk)" }
            val len = readU32(lenBytes)
            require(len in STREAM_A_BYTES..(CHUNK + STREAM_A_BYTES)) { "backup frame has a bad length" }
            val cipher = input.readNBytes(len)
            require(cipher.size == len) { "backup is truncated" }
            val msg = ByteArray(len - STREAM_A_BYTES)
            val tag = ByteArray(1)
            require(na.crypto_secretstream_xchacha20poly1305_pull(state, msg, null, tag, cipher, len.toLong(), null, 0) == 0) {
                "backup does not decrypt (wrong key or damaged file)"
            }
            output.write(msg)
            if (tag[0] == TAG_FINAL) break
        }
        require(input.read() == -1) { "backup has data after its final chunk" }
    }

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readU32(b: ByteArray) = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
        ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hex" }
    return ByteArray(length / 2) { ((Character.digit(this[2 * it], 16) shl 4) or Character.digit(this[2 * it + 1], 16)).toByte() }
}
