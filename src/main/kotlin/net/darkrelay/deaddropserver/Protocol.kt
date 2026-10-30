package net.darkrelay.deaddropserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * What travels inside the encrypted envelopes (docs/PROTOCOL.md has the byte layout).
 * A change has the same shape as a row of the phone's change_log table, so the phone's sync can send its log as it is.
 */

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Serializable
data class RpcRequest(val op: String, val ts: Long, val args: JsonObject = JsonObject(emptyMap()))

@Serializable
data class RpcResponse(
    val ok: Boolean,
    /** The request's nonce, so a reply can't be swapped for another one. */
    val re: String,
    val result: JsonElement? = null,
    val error: String? = null,
    val message: String? = null,
)

/** [name] (0.3.0): the device's own name for itself; without one it gets the name the code was made with. */
@Serializable
data class PairRequest(val code: String, val kind: String, val publicKey: String, val name: String? = null)

/** pairing.new (0.3.0): a logged-in device asks for a one-time pairing code for another device. */
@Serializable
data class PairingCodeArgs(val name: String = "Phone", val minutes: Long = 10)

@Serializable
data class PairingCode(val code: String, val name: String, val createdAt: Long, val expiresAt: Long)

/** The reply to pairing, setup and login. [recoveryKeyId] (0.4.0): the account's recovery key id, null when it has none yet. */
@Serializable
data class PairReply(val deviceId: String, val name: String, val kind: String, val serverTime: Long, val recoveryKeyId: String? = null)

/**
 * Creates the server's one account with the one-time setup code (0.2.0); the device sending it, phone or desktop, is its
 * first device. [recoveryKeyId] (0.4.0): the id of the recovery key that device made, 32 lowercase hex characters.
 */
@Serializable
data class SetupRequest(
    val setupCode: String,
    val username: String,
    val password: String,
    val name: String,
    val kind: String,
    val publicKey: String,
    val recoveryKeyId: String? = null,
)

/** Logs a new device in to the account (0.2.0). The reply is a PairReply, as pairing gives. */
@Serializable
data class LoginRequest(val username: String, val password: String, val name: String, val kind: String, val publicKey: String)

/** GET /v1/server-key: the server's public key and its fingerprint (public values), for a device to pin. */
@Serializable
data class ServerKey(val publicKey: String, val fingerprint: String)

/** One change, as the phone's change_log has it: operation INSERT, UPDATE or DELETE, changedFields = the new values. */
@Serializable
data class Change(
    val changeId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val changedFields: JsonObject,
    /** When the device made the change, UTC epoch millis. Last change wins by this time. */
    val timestamp: Long,
)

@Serializable
data class PushArgs(
    val changes: List<Change>,
    /** The last server seq this device has pulled; used to notice changes it overwrote without having seen them. */
    val baseSeq: Long = 0,
    /** Changes made while offline: merged even when another device holds the edit lock. */
    val offline: Boolean = false,
)

@Serializable
data class Conflict(
    val entityType: String,
    val entityId: String,
    val winnerDevice: String,
    val loserDevice: String,
    val note: String,
    val at: Long,
)

@Serializable
data class PushResult(val applied: Int, val duplicates: Int, val lost: Int, val lastSeq: Long, val conflicts: List<Conflict>)

@Serializable
data class PullArgs(val sinceSeq: Long = 0, val limit: Int = 500)

@Serializable
data class ServerChange(
    val seq: Long,
    val changeId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val changedFields: JsonObject,
    val timestamp: Long,
    val deviceId: String,
    val offline: Boolean,
)

@Serializable
data class PullResult(val changes: List<ServerChange>, val lastSeq: Long, val more: Boolean, val conflicts: List<Conflict>)

@Serializable
data class RecordsArgs(val afterType: String = "", val afterId: String = "", val limit: Int = 500)

@Serializable
data class Record(
    val entityType: String,
    val entityId: String,
    val data: JsonObject,
    val deleted: Boolean,
    val changedAt: Long,
    val changedBy: String,
    val seq: Long,
)

@Serializable
data class RecordsResult(val records: List<Record>, val more: Boolean)

@Serializable
data class LockState(
    val holder: String? = null,
    val holderName: String? = null,
    val grantedAt: Long? = null,
    val expiresAt: Long? = null,
    val youHoldIt: Boolean = false,
    /**
     * The handoff notice (0.3.0): another device took the lock over from this one (lock.takeover), and this one hasn't
     * held it since. Its pushes wait (error taken_over) until it holds the lock again.
     */
    val takenBy: String? = null,
    val takenByName: String? = null,
    val takenAt: Long? = null,
)

@Serializable
data class HeartbeatArgs(val open: Boolean = true)

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val kind: String,
    val pairedAt: Long,
    val lastSeen: Long? = null,
    val open: Boolean = false,
    val revokedAt: Long? = null,
)

@Serializable
data class RevokeArgs(val deviceId: String)

@Serializable
data class Status(val serverTime: Long, val lastSeq: Long, val lock: LockState, val you: DeviceInfo, val devices: List<DeviceInfo>)

/** account.password (0.4.0): a new password from a logged-in device. No old password is needed, and there is no reset. */
@Serializable
data class PasswordArgs(val password: String)

@Serializable
data class PasswordChanged(val changedAt: Long)

/**
 * recovery.status (0.4.0). [keyId]: the account's recovery key id. [mine]: the server holds a sealed copy for this
 * device under that id. [needing]: the other active devices with no copy yet, each with its public key to seal one to.
 */
@Serializable
data class RecoveryStatus(val keyId: String? = null, val mine: Boolean, val needing: List<RecoveryDevice>)

@Serializable
data class RecoveryDevice(val deviceId: String, val name: String, val kind: String, val publicKey: String)

/** recovery.set (0.4.0): gives an account made by an older app its recovery key id. Once set, it doesn't change. */
@Serializable
data class RecoverySetArgs(val keyId: String)

/** recovery.put (0.4.0): [sealed] is hex of crypto_box_seal(recovery key, that device's public key), made by the sender. */
@Serializable
data class RecoveryPutArgs(val deviceId: String, val keyId: String, val sealed: String)

@Serializable
data class RecoveryStored(val stored: Boolean = true)

/** recovery.get (0.4.0): this device's sealed copy, when it is for the account's current key id. */
@Serializable
data class RecoveryCopy(val keyId: String? = null, val sealed: String? = null)

/** device.logout (0.4.0): the device revokes itself on close. */
@Serializable
data class LoggedOut(val loggedOut: Boolean = true)
