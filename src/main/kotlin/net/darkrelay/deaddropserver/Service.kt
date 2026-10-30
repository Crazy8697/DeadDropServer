package net.darkrelay.deaddropserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

class DeadDropError(val code: String, message: String) : Exception(message)

/** The server's rules: storage and sync, the edit lock, devices and pairing, the account and its recovery key copies. */
class Service(
    private val db: Db,
    private val config: Config,
    private val now: () -> Long = System::currentTimeMillis,
    /** Writes and verifies an encrypted backup of the master copy (backup-now); throws when it can't. Used before the master is replaced. */
    private val backupNow: () -> Unit = { throw IllegalStateException("no backups here") },
) {

    // ---- storage and sync

    fun lastSeq(): Long = db.tx { query("SELECT COALESCE(MAX(seq), 0) FROM changes") { it.getLong(1) }.single() }

    fun push(deviceId: String, args: PushArgs): PushResult {
        if (args.changes.size > MAX_PUSH) throw DeadDropError("bad_request", "at most $MAX_PUSH changes per push")
        args.changes.forEach(::validate)
        return db.tx {
            val t = now()
            if (takenFrom(deviceId)) throw DeadDropError("taken_over", "another device took over editing; this device's changes wait until it holds the lock again")
            if (!args.offline) {
                val holder = lockHolder(t)
                if (holder != deviceId) throw DeadDropError("locked", if (holder == null) "take the edit lock first" else "another device holds the edit lock")
            }
            var applied = 0; var duplicates = 0; var lost = 0
            val conflicts = mutableListOf<Conflict>()
            for (c in args.changes) {
                if (query("SELECT 1 FROM applied_changes WHERE change_id = ?", c.changeId) { 1 }.isNotEmpty()) { duplicates++; continue }
                val existing = query("SELECT data, deleted, changed_at, changed_by, seq FROM records WHERE entity_type = ? AND entity_id = ?",
                    c.entityType, c.entityId) { Existing(json.parseToJsonElement(it.getString(1)) as JsonObject, it.getInt(2) == 1, it.getLong(3), it.getString(4), it.getLong(5)) }
                    .firstOrNull()
                if (existing != null && c.timestamp < existing.changedAt) {
                    // an older change: the newer one already here wins
                    exec("INSERT INTO applied_changes (change_id, seq, received_at) VALUES (?, NULL, ?)", c.changeId, t)
                    lost++
                    if (existing.changedBy != deviceId) {
                        conflicts += note(c, winner = existing.changedBy, loser = deviceId, t,
                            "This change lost to a newer change from another device (last change wins).")
                    }
                    continue
                }
                if (existing != null && existing.changedBy != deviceId && existing.seq > args.baseSeq) {
                    conflicts += note(c, winner = deviceId, loser = existing.changedBy, t,
                        "This change replaced another device's change that this device had not seen yet (last change wins).")
                }
                val merged = JsonObject((existing?.data ?: JsonObject(emptyMap())) + c.changedFields)
                val deleted = when {
                    c.operation == "DELETE" -> true
                    else -> (c.changedFields["deleted"] as? JsonPrimitive)?.booleanOrNull ?: existing?.deleted ?: false
                }
                exec("""INSERT INTO changes (change_id, entity_type, entity_id, operation, changed_fields, changed_at, device_id, received_at, offline)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    c.changeId, c.entityType, c.entityId, c.operation, c.changedFields.toString(), c.timestamp, deviceId, t, if (args.offline) 1 else 0)
                val seq = lastId()
                exec("""INSERT INTO records (entity_type, entity_id, data, deleted, changed_at, changed_by, seq) VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (entity_type, entity_id) DO UPDATE SET data = excluded.data, deleted = excluded.deleted,
                        changed_at = excluded.changed_at, changed_by = excluded.changed_by, seq = excluded.seq""",
                    c.entityType, c.entityId, merged.toString(), if (deleted) 1 else 0, c.timestamp, deviceId, seq)
                exec("INSERT INTO applied_changes (change_id, seq, received_at) VALUES (?, ?, ?)", c.changeId, seq, t)
                applied++
            }
            val last = query("SELECT COALESCE(MAX(seq), 0) FROM changes") { it.getLong(1) }.single()
            PushResult(applied, duplicates, lost, last, conflicts)
        }
    }

    private class Existing(val data: JsonObject, val deleted: Boolean, val changedAt: Long, val changedBy: String, val seq: Long)

    private fun Db.Tx.note(c: Change, winner: String, loser: String, t: Long, text: String): Conflict {
        exec("INSERT INTO conflicts (entity_type, entity_id, winner_device, loser_device, note, at) VALUES (?, ?, ?, ?, ?, ?)",
            c.entityType, c.entityId, winner, loser, text, t)
        return Conflict(c.entityType, c.entityId, winner, loser, text, t)
    }

    fun pull(deviceId: String, args: PullArgs): PullResult = db.tx {
        val limit = args.limit.coerceIn(1, 1000)
        if (args.sinceSeq in 1..masterFloor()) {
            // the master copy was replaced since this device's last pull (0.5.1): no changes, it takes the whole copy
            val last = query("SELECT COALESCE(MAX(seq), 0) FROM changes") { it.getLong(1) }.single()
            return@tx PullResult(emptyList(), last, false, emptyList(), masterReplaced = true)
        }
        val rows = query("""SELECT seq, change_id, entity_type, entity_id, operation, changed_fields, changed_at, device_id, offline
                            FROM changes WHERE seq > ? ORDER BY seq LIMIT ?""", args.sinceSeq, limit + 1) {
            ServerChange(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5),
                json.parseToJsonElement(it.getString(6)) as JsonObject, it.getLong(7), it.getString(8), it.getInt(9) == 1)
        }
        val page = rows.take(limit)
        // conflicts this device lost, so its app can show a note
        val conflicts = query("SELECT entity_type, entity_id, winner_device, loser_device, note, at FROM conflicts WHERE loser_device = ? ORDER BY id DESC LIMIT 50", deviceId) {
            Conflict(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getLong(6))
        }
        val last = page.lastOrNull()?.seq ?: query("SELECT COALESCE(MAX(seq), 0) FROM changes") { it.getLong(1) }.single()
        PullResult(page, last, rows.size > limit, conflicts)
    }

    fun records(args: RecordsArgs): RecordsResult = db.tx {
        val limit = args.limit.coerceIn(1, 1000)
        val rows = query("""SELECT entity_type, entity_id, data, deleted, changed_at, changed_by, seq FROM records
                            WHERE (entity_type, entity_id) > (?, ?) ORDER BY entity_type, entity_id LIMIT ?""",
            args.afterType, args.afterId, limit + 1) {
            Record(it.getString(1), it.getString(2), json.parseToJsonElement(it.getString(3)) as JsonObject, it.getInt(4) == 1,
                it.getLong(5), it.getString(6), it.getLong(7))
        }
        RecordsResult(rows.take(limit), rows.size > limit)
    }

    // ---- replacing the master copy with a restored backup (0.5.1)

    private fun Db.Tx.requireLockHeld(deviceId: String, t: Long) {
        if (takenFrom(deviceId)) throw DeadDropError("taken_over", "another device took over editing; take the edit lock back before replacing the budget")
        val holder = lockHolder(t)
        if (holder != deviceId) throw DeadDropError("locked", if (holder == null) "take the edit lock first" else "another device holds the edit lock")
    }

    /** The server's own encrypted backup of the current master copy, written and read back; any failure refuses the replace. */
    private fun backupFirst() {
        try {
            backupNow()
        } catch (e: Exception) {
            throw DeadDropError("backup_failed", "The server couldn't back up its current budget first, so it did not replace it. Nothing was changed. " +
                "Check that its backup folder is ready, then try again.")
        }
    }

    /** Pre-flight for a restore (before the device changes anything): the lock is held, and the server's backup works. */
    fun masterCheck(deviceId: String): MasterChecked {
        db.tx { requireLockHeld(deviceId, now()) }
        backupFirst()
        return MasterChecked(true)
    }

    /**
     * Replaces the master copy by the restored budget in one transaction, after the server has backed up the current one
     * (a failed backup refuses the replace and changes nothing). Needs the edit lock. Every other device finds out at its
     * next pull ([PullResult.masterReplaced]); the change history starts over with this budget, its seqs continuing upward.
     */
    fun masterReplace(deviceId: String, args: MasterReplaceArgs): MasterReplaced {
        if (args.changes.size > MAX_REPLACE) throw DeadDropError("bad_request", "at most $MAX_REPLACE changes in a replace")
        args.changes.forEach(::validate)
        if (args.changes.map { it.changeId }.toSet().size != args.changes.size) throw DeadDropError("bad_request", "a change id is used twice")
        db.tx { requireLockHeld(deviceId, now()) }
        backupFirst()
        return db.tx {
            val t = now()
            requireLockHeld(deviceId, t)
            val floor = query("SELECT COALESCE(MAX(seq), 0) FROM changes") { it.getLong(1) }.single()
            exec("DELETE FROM records")
            exec("DELETE FROM changes")
            exec("DELETE FROM applied_changes")
            exec("DELETE FROM conflicts")
            for (c in args.changes) {
                val existing = query("SELECT data, deleted FROM records WHERE entity_type = ? AND entity_id = ?", c.entityType, c.entityId) {
                    json.parseToJsonElement(it.getString(1)) as JsonObject to (it.getInt(2) == 1)
                }.firstOrNull()
                val merged = JsonObject((existing?.first ?: JsonObject(emptyMap())) + c.changedFields)
                val deleted = when {
                    c.operation == "DELETE" -> true
                    else -> (c.changedFields["deleted"] as? JsonPrimitive)?.booleanOrNull ?: existing?.second ?: false
                }
                exec("""INSERT INTO changes (change_id, entity_type, entity_id, operation, changed_fields, changed_at, device_id, received_at, offline)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                    c.changeId, c.entityType, c.entityId, c.operation, c.changedFields.toString(), c.timestamp, deviceId, t)
                val seq = lastId()
                exec("""INSERT INTO records (entity_type, entity_id, data, deleted, changed_at, changed_by, seq) VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (entity_type, entity_id) DO UPDATE SET data = excluded.data, deleted = excluded.deleted,
                        changed_at = excluded.changed_at, changed_by = excluded.changed_by, seq = excluded.seq""",
                    c.entityType, c.entityId, merged.toString(), if (deleted) 1 else 0, c.timestamp, deviceId, seq)
                exec("INSERT INTO applied_changes (change_id, seq, received_at) VALUES (?, ?, ?)", c.changeId, seq, t)
            }
            // every device whose last pull is at or below this seq must take the new copy whole
            exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('master_floor', ?)", floor.toString())
            MasterReplaced(args.changes.size, query("SELECT COALESCE(MAX(seq), 0) FROM changes") { it.getLong(1) }.single())
        }
    }

    private fun Db.Tx.masterFloor(): Long = query("SELECT value FROM meta WHERE key = 'master_floor'") { it.getString(1).toLong() }.firstOrNull() ?: 0L

    private fun validate(c: Change) {
        fun bad(m: String): Nothing = throw DeadDropError("bad_request", m)
        if (!ID.matches(c.changeId)) bad("bad changeId")
        if (!TYPE.matches(c.entityType)) bad("bad entityType")
        if (!ID.matches(c.entityId)) bad("bad entityId")
        if (c.operation !in OPERATIONS) bad("operation must be INSERT, UPDATE or DELETE")
        if (c.timestamp <= 0) bad("bad timestamp")
        if (c.timestamp > now() + MAX_CLOCK_AHEAD_MS) bad("timestamp is too far in the future (check the device clock)")
        if (c.changedFields.toString().length > MAX_FIELDS_CHARS) bad("changedFields is too large")
    }

    // ---- edit lock: one device edits at a time; the lock lapses without a heartbeat from its holder

    private fun Db.Tx.lockHolder(t: Long): String? =
        query("SELECT holder, expires_at FROM edit_lock WHERE id = 1") { it.getString(1) to it.longOrNull("expires_at") }
            .single().let { (h, exp) -> if (h != null && exp != null && exp > t) h else null }

    private fun Db.Tx.lockState(deviceId: String, t: Long): LockState {
        val row = query("SELECT l.holder, d.name, l.granted_at, l.expires_at FROM edit_lock l LEFT JOIN devices d ON d.id = l.holder WHERE l.id = 1") {
            LockState(it.getString(1), it.getString(2), it.longOrNull("granted_at"), it.longOrNull("expires_at"))
        }.single()
        val base = if (row.holder == null || (row.expiresAt ?: 0) <= t) LockState() else row.copy(youHoldIt = row.holder == deviceId)
        val taken = query("""SELECT d.lock_taken_by, b.name, d.lock_taken_at FROM devices d LEFT JOIN devices b ON b.id = d.lock_taken_by
                             WHERE d.id = ? AND d.lock_taken_by IS NOT NULL""", deviceId) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }
            .firstOrNull() ?: return base
        return base.copy(takenBy = taken.first, takenByName = taken.second, takenAt = taken.third)
    }

    private fun Db.Tx.takenFrom(deviceId: String): Boolean =
        query("SELECT 1 FROM devices WHERE id = ? AND lock_taken_by IS NOT NULL", deviceId) { 1 }.isNotEmpty()

    private fun Db.Tx.grant(deviceId: String, holder: String?, t: Long) {
        val granted = if (holder == deviceId) query("SELECT granted_at FROM edit_lock WHERE id = 1") { it.getLong(1) }.single() else t
        exec("UPDATE edit_lock SET holder = ?, granted_at = ?, expires_at = ? WHERE id = 1", deviceId, granted, t + config.lockTimeoutSeconds * 1000)
        // holding the lock again ends a handoff notice: its waiting changes can go now
        exec("UPDATE devices SET lock_taken_by = NULL, lock_taken_at = NULL WHERE id = ?", deviceId)
    }

    fun lockAcquire(deviceId: String): LockState = db.tx {
        val t = now()
        val holder = lockHolder(t)
        if (holder == null || holder == deviceId) grant(deviceId, holder, t)
        lockState(deviceId, t)
    }

    /**
     * Take over (0.3.0): the lock moves to this device even while another holds it. The other device gets the handoff
     * notice (LockState.takenBy), turns read only, and its changes wait until it holds the lock again. Its heartbeats
     * no longer renew or release this lock, since it isn't the holder.
     */
    fun lockTakeover(deviceId: String): LockState = db.tx {
        val t = now()
        val holder = lockHolder(t)
        if (holder != null && holder != deviceId) exec("UPDATE devices SET lock_taken_by = ?, lock_taken_at = ? WHERE id = ?", deviceId, t, holder)
        grant(deviceId, holder, t)
        lockState(deviceId, t)
    }

    fun lockRelease(deviceId: String): LockState = db.tx {
        val t = now()
        exec("UPDATE edit_lock SET holder = NULL, granted_at = NULL, expires_at = NULL WHERE id = 1 AND holder = ?", deviceId)
        lockState(deviceId, t)
    }

    /** The device says it is open (or closing). Renews the lock it holds; closing releases it. */
    fun heartbeat(deviceId: String, args: HeartbeatArgs): LockState = db.tx {
        val t = now()
        exec("UPDATE devices SET last_seen = ?, is_open = ? WHERE id = ?", t, if (args.open) 1 else 0, deviceId)
        if (lockHolder(t) == deviceId) {
            if (args.open) exec("UPDATE edit_lock SET expires_at = ? WHERE id = 1", t + config.lockTimeoutSeconds * 1000)
            else exec("UPDATE edit_lock SET holder = NULL, granted_at = NULL, expires_at = NULL WHERE id = 1")
        }
        lockState(deviceId, t)
    }

    // ---- devices

    private fun Db.Tx.devicesNow(t: Long): List<DeviceInfo> =
        query("SELECT id, name, kind, paired_at, last_seen, is_open, revoked_at FROM devices ORDER BY paired_at") {
            val lastSeen = it.longOrNull("last_seen")
            DeviceInfo(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), lastSeen,
                // open = said so, and heard from within the lock timeout
                it.getInt(6) == 1 && lastSeen != null && t - lastSeen < config.lockTimeoutSeconds * 1000, it.longOrNull("revoked_at"))
        }

    fun devices(): List<DeviceInfo> = db.tx { devicesNow(now()) }

    fun status(deviceId: String): Status = db.tx {
        val t = now()
        val all = devicesNow(t)
        Status(t, query("SELECT COALESCE(MAX(seq), 0) FROM changes") { it.getLong(1) }.single(), lockState(deviceId, t),
            all.single { it.id == deviceId }, all)
    }

    /** The public key of an active (paired, not revoked) device, or null. */
    fun activeKey(deviceId: String): ByteArray? = db.tx {
        query("SELECT public_key FROM devices WHERE id = ? AND public_key IS NOT NULL", deviceId) { it.getString(1) }.firstOrNull()?.unhex()
    }

    /**
     * Revoking deletes the device's key, so nothing it sends opens again, frees the edit lock if it held it, and deletes
     * its sealed copy of the recovery key (0.4.0).
     */
    fun revoke(deviceId: String): DeviceInfo = db.tx {
        val t = now()
        revokeIn(deviceId, t)
        devicesNow(t).single { it.id == deviceId }
    }

    private fun Db.Tx.revokeIn(deviceId: String, t: Long) {
        exec("UPDATE edit_lock SET holder = NULL, granted_at = NULL, expires_at = NULL WHERE id = 1 AND holder = ?", deviceId)
        val n = exec("UPDATE devices SET public_key = NULL, revoked_at = ?, is_open = 0 WHERE id = ? AND revoked_at IS NULL", t, deviceId)
        if (n == 0) throw DeadDropError("not_found", "no active device with that id")
        exec("DELETE FROM recovery_keys WHERE device_id = ?", deviceId)
    }

    /**
     * Log out on close (0.4.0): in one go the device lets go of the edit lock if it holds it and revokes itself as
     * [revoke] does (key deleted, recovery key copy deleted). Coming back means logging in again, as a new device.
     */
    fun logout(deviceId: String): LoggedOut = db.tx {
        revokeIn(deviceId, now())
        LoggedOut()
    }

    private fun Db.Tx.active(deviceId: String): Boolean =
        query("SELECT 1 FROM devices WHERE id = ? AND revoked_at IS NULL", deviceId) { 1 }.isNotEmpty()

    // ---- pairing: a one-time code made on the server (pair-code), used once within its lifetime

    fun newPairingCode(name: String, minutes: Long = 10): String = pairingCode(name, minutes).code

    /** A new one-time pairing code, from pair-code or a logged-in device (pairing.new, 0.3.0). Only its hash is stored. */
    fun pairingCode(name: String, minutes: Long = 10): PairingCode {
        require(name.isNotBlank() && name.length <= 60) { "name must be 1 to 60 characters" }
        require(minutes in 0..60) { "minutes must be 0 to 60" }
        val raw = code()
        val t = now()
        db.tx {
            exec("INSERT INTO pairing_codes (code_hash, name, created_at, expires_at) VALUES (?, ?, ?, ?)",
                codeHash(raw), name.trim(), t, t + minutes * 60_000)
        }
        return PairingCode(raw.chunked(4).joinToString("-"), name.trim(), t, t + minutes * 60_000)
    }

    /** Null when pairing is refused (wrong, used or expired code, bad key, or too many failures lately). */
    fun pair(req: PairRequest): DeviceInfo? = db.tx {
        val t = now()
        if (lockedOut(t)) return@tx null
        val key = deviceKey(req.publicKey, req.kind)
        val hash = codeHash(req.code)
        val name = query("SELECT name FROM pairing_codes WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?", hash, t) { it.getString(1) }.firstOrNull()
        if (name == null || key == null) return@tx failed(t)
        val dev = addDevice(req.name?.trim()?.takeIf(::validName) ?: name, req.kind, key, t)
        exec("UPDATE pairing_codes SET used_at = ?, device_id = ? WHERE code_hash = ?", t, dev.id, hash)
        dev
    }

    /** The account's recovery key id (0.4.0), for the pairing, setup and login replies. Null without one. */
    fun recoveryKeyId(): String? = db.tx { currentKeyId() }

    // ---- the account (0.2.0): one per server, made once with the setup code, then logged in to from each device

    /** The failure window: 5 failed pairings, setups or logins in 10 minutes refuse all three until it passes. */
    private fun Db.Tx.lockedOut(t: Long): Boolean {
        exec("DELETE FROM failed_pairings WHERE at < ?", t - FAIL_WINDOW_MS)
        return query("SELECT COUNT(*) FROM failed_pairings") { it.getInt(1) }.single() >= MAX_FAILED_PAIRINGS
    }

    private fun Db.Tx.failed(t: Long): DeviceInfo? {
        exec("INSERT INTO failed_pairings (at) VALUES (?)", t)
        return null
    }

    /** A device's public key when it's usable: 32 bytes, a known kind, and not some other device's key. */
    private fun Db.Tx.deviceKey(hex: String, kind: String): ByteArray? {
        val key = runCatching { hex.unhex() }.getOrNull() ?: return null
        if (key.size != Crypto.KEY_BYTES || kind !in KINDS) return null
        if (query("SELECT 1 FROM devices WHERE public_key = ?", key.hex()) { 1 }.isNotEmpty()) return null
        return key
    }

    private fun Db.Tx.addDevice(name: String, kind: String, key: ByteArray, t: Long): DeviceInfo {
        val id = UUID.randomUUID().toString()
        exec("INSERT INTO devices (id, name, kind, public_key, paired_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)", id, name, kind, key.hex(), t, t)
        return devicesNow(t).single { it.id == id }
    }

    fun hasAccount(): Boolean = db.tx { query("SELECT 1 FROM account WHERE id = 1") { 1 }.isNotEmpty() }

    /**
     * On a start with no account: a new one-time setup code (any earlier one stops working), written to [file]
     * (owner-only) and returned so the start can print it once to the container log. With an account: the file goes
     * and null comes back. The code itself is never stored, only its hash.
     */
    fun startSetup(file: java.nio.file.Path, fingerprint: String): String? {
        if (hasAccount()) {
            java.nio.file.Files.deleteIfExists(file)
            db.tx { exec("DELETE FROM setup_code") }
            return null
        }
        val raw = code()
        db.tx { exec("INSERT OR REPLACE INTO setup_code (id, code_hash, created_at) VALUES (1, ?, ?)", codeHash(raw), now()) }
        val code = raw.chunked(4).joinToString("-")
        java.nio.file.Files.createDirectories(file.toAbsolutePath().parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        java.nio.file.Files.deleteIfExists(tmp)
        val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        runCatching { java.nio.file.Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(perms)) }
            .getOrElse { java.nio.file.Files.createFile(tmp) }
        java.nio.file.Files.writeString(
            tmp,
            "Dead Drop server setup code: $code\n" +
                "It creates the server's account once, from the app or the program (Settings > Server), then stops working.\n" +
                "Server key fingerprint (the app shows it too; they must match): $fingerprint\n",
        )
        java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return code
    }

    /**
     * Creates the account and its first device, a phone or a desktop. Null when refused: no setup code (an account
     * exists already), a wrong code, a bad username, password, key or recovery key id, or the failure window. On success
     * the code is gone and [onUsed] runs (it deletes the code's file).
     */
    fun setup(req: SetupRequest, onUsed: () -> Unit = {}): DeviceInfo? {
        val result = db.tx {
            val t = now()
            if (lockedOut(t)) return@tx null
            val want = query("SELECT code_hash FROM setup_code WHERE id = 1") { it.getString(1) }.firstOrNull()
            val key = deviceKey(req.publicKey, req.kind)
            val ok = want != null && want == codeHash(req.setupCode) && key != null && validAccount(req.username, req.password) &&
                validName(req.name) && (req.recoveryKeyId == null || KEY_ID.matches(req.recoveryKeyId)) &&
                query("SELECT 1 FROM account") { 1 }.isEmpty()
            if (!ok) return@tx failed(t)
            exec(
                "INSERT INTO account (id, username, password_hash, created_at, recovery_key_id) VALUES (1, ?, ?, ?, ?)",
                req.username.trim(), Crypto.passwordHash(req.password.toByteArray(Charsets.UTF_8)), t, req.recoveryKeyId,
            )
            exec("DELETE FROM setup_code")
            addDevice(req.name.trim(), req.kind, key!!, t)
        }
        if (result != null) onUsed()
        return result
    }

    /** A new device for the account: the right username and password. Null when refused (and counted as a failure). */
    fun login(req: LoginRequest): DeviceInfo? = db.tx {
        val t = now()
        if (lockedOut(t)) return@tx null
        val acct = query("SELECT username, password_hash FROM account WHERE id = 1") { it.getString(1) to it.getString(2) }.firstOrNull()
        val key = deviceKey(req.publicKey, req.kind)
        val passwordOk = acct != null && Crypto.passwordMatches(acct.second, req.password.toByteArray(Charsets.UTF_8))
        if (acct == null || !passwordOk || !acct.first.equals(req.username.trim(), ignoreCase = true) || key == null || !validName(req.name)) {
            return@tx failed(t)
        }
        addDevice(req.name.trim(), req.kind, key, t)
    }

    private fun validAccount(username: String, password: String) =
        username.trim().length in 1..60 && validPassword(password)

    private fun validPassword(password: String) = password.length in MIN_PASSWORD..1024

    // ---- a new password and the recovery key (0.4.0)

    /**
     * A new password, set from a logged-in device without the old one: the device already proved itself with its own
     * key. Other devices stay logged in. This is the only way the password changes after setup. There is no reset,
     * by design: a forgotten password with every device logged out locks the account for good.
     */
    fun changePassword(deviceId: String, args: PasswordArgs): PasswordChanged {
        if (!validPassword(args.password)) throw DeadDropError("bad_request", "the password must be $MIN_PASSWORD to 1024 characters")
        val hash = Crypto.passwordHash(args.password.toByteArray(Charsets.UTF_8))
        return db.tx {
            val t = now()
            if (!active(deviceId)) throw DeadDropError("not_found", "no active device with that id")
            if (exec("UPDATE account SET password_hash = ? WHERE id = 1", hash) == 0) throw DeadDropError("not_found", "there is no account yet")
            PasswordChanged(t)
        }
    }

    private fun Db.Tx.currentKeyId(): String? = query("SELECT recovery_key_id FROM account WHERE id = 1") { it.getString(1) }.firstOrNull()

    private fun Db.Tx.recoveryState(deviceId: String): RecoveryStatus {
        val keyId = currentKeyId() ?: return RecoveryStatus(null, false, emptyList())
        val mine = query("SELECT 1 FROM recovery_keys WHERE device_id = ? AND key_id = ?", deviceId, keyId) { 1 }.isNotEmpty()
        val needing = query("""SELECT d.id, d.name, d.kind, d.public_key FROM devices d
                               WHERE d.revoked_at IS NULL AND d.id <> ?
                               AND NOT EXISTS (SELECT 1 FROM recovery_keys r WHERE r.device_id = d.id AND r.key_id = ?)
                               ORDER BY d.paired_at""", deviceId, keyId) {
            RecoveryDevice(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }
        return RecoveryStatus(keyId, mine, needing)
    }

    /** The account's recovery key id, whether this device has its copy, and which other devices still need one. */
    fun recoveryStatus(deviceId: String): RecoveryStatus = db.tx { recoveryState(deviceId) }

    /** Sets the recovery key id of an account that has none (made by an older app). A set id never changes. */
    fun recoverySet(deviceId: String, args: RecoverySetArgs): RecoveryStatus {
        if (!KEY_ID.matches(args.keyId)) throw DeadDropError("bad_request", "keyId must be 32 lowercase hex characters")
        return db.tx {
            val acct = query("SELECT recovery_key_id FROM account WHERE id = 1") { listOf(it.getString(1)) }.firstOrNull()
                ?: throw DeadDropError("not_found", "there is no account yet")
            when (acct.single()) {
                null -> exec("UPDATE account SET recovery_key_id = ? WHERE id = 1", args.keyId)
                args.keyId -> Unit
                else -> throw DeadDropError("conflict", "the account has a different recovery key already")
            }
            recoveryState(deviceId)
        }
    }

    /**
     * Stores one device's sealed copy of the recovery key, made by the sending device with that device's public key.
     * The server can't open it and never tries: it only keeps the bytes for that device to fetch.
     */
    fun recoveryPut(args: RecoveryPutArgs): RecoveryStored = db.tx {
        if (!active(args.deviceId)) throw DeadDropError("not_found", "no active device with that id")
        if (args.keyId != currentKeyId()) throw DeadDropError("conflict", "that is not the account's recovery key id")
        val sealed = runCatching { args.sealed.unhex() }.getOrNull()
        if (sealed == null || sealed.size !in SEALED_MIN..SEALED_MAX) throw DeadDropError("bad_request", "sealed must be hex of $SEALED_MIN to $SEALED_MAX bytes")
        exec("""INSERT INTO recovery_keys (device_id, key_id, sealed, stored_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (device_id) DO UPDATE SET key_id = excluded.key_id, sealed = excluded.sealed, stored_at = excluded.stored_at""",
            args.deviceId, args.keyId, sealed.hex(), now())
        RecoveryStored()
    }

    /** This device's sealed copy, when it is for the account's current recovery key id; else sealed is null. */
    fun recoveryGet(deviceId: String): RecoveryCopy = db.tx {
        val keyId = currentKeyId()
        val sealed = keyId?.let { k -> query("SELECT sealed FROM recovery_keys WHERE device_id = ? AND key_id = ?", deviceId, k) { it.getString(1) }.firstOrNull() }
        RecoveryCopy(keyId, sealed)
    }

    private fun validName(name: String) = name.trim().length in 1..60

    private fun code(): String = buildString {
        // rejection sampling, so every character is equally likely
        while (length < 8) {
            val b = Crypto.random(1)[0].toInt() and 0xff
            if (b < 240) append(CODE_ALPHABET[b % CODE_ALPHABET.length])
        }
    }

    private fun codeHash(code: String) = Crypto.sha256(code.uppercase().filter { it.isLetterOrDigit() }.toByteArray()).hex()

    // ---- replay protection for the encrypted envelopes

    /** False when this nonce was seen already. Old nonces are dropped once they are past the time window. */
    fun rememberNonce(nonceHex: String): Boolean = db.tx {
        val t = now()
        exec("DELETE FROM seen_nonces WHERE at < ?", t - 2 * MAX_SKEW_MS)
        exec("INSERT OR IGNORE INTO seen_nonces (nonce, at) VALUES (?, ?)", nonceHex, t) == 1
    }

    companion object {
        const val MAX_PUSH = 5000
        const val MAX_REPLACE = 100_000
        const val MAX_FIELDS_CHARS = 64 * 1024
        const val MAX_CLOCK_AHEAD_MS = 24L * 3600 * 1000
        const val MAX_SKEW_MS = 5L * 60 * 1000
        const val MAX_FAILED_PAIRINGS = 5
        const val MIN_PASSWORD = 8
        const val SETUP_FILE = "setup-code.txt"
        const val FAIL_WINDOW_MS = 10L * 60 * 1000
        val OPERATIONS = setOf("INSERT", "UPDATE", "DELETE")
        val KINDS = setOf("phone", "desktop")
        /** A recovery key id: 16 random bytes as 32 lowercase hex characters, a label for the key, never the key. */
        val KEY_ID = Regex("^[0-9a-f]{32}$")
        /** A sealed recovery key: crypto_box_seal adds 48 bytes to the key. */
        const val SEALED_MIN = 48
        const val SEALED_MAX = 256
        private val ID = Regex("^[A-Za-z0-9._:-]{1,100}$")
        private val TYPE = Regex("^[a-z][a-z0-9_]{0,63}$")
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
    }
}
