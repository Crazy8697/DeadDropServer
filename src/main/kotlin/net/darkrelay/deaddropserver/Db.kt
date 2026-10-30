package net.darkrelay.deaddropserver

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The master copy: one SQLite file, readable with any SQLite tool (no database encryption, by design).
 *
 * Tables
 *  - records: the budget, one row per synced row of the phone (entity_type + entity_id), its fields as a JSON object.
 *    Money stays a whole number of cents, as the phone writes it. Deletes are tombstones (deleted = 1).
 *  - changes: every change the server accepted, in server order (seq). Devices pull from here.
 *  - applied_changes: change ids already seen, so a change pushed twice is applied once.
 *  - conflicts: notes for the rare same-item conflict (last change wins).
 *  - devices: paired devices and their public keys. Revoking deletes the key; the row stays as a record.
 *  - pairing_codes, edit_lock, seen_nonces, meta: pairing, the edit lock, replay protection, schema version.
 *  - account (schema 2): the server's one account, its password only as an Argon2id hash; setup_code: the hash of
 *    the one-time code that creates it, while there is no account. failed_pairings counts failed pairings, setups and
 *    logins alike (5 in 10 minutes lock all three).
 *  - devices.lock_taken_by, lock_taken_at (schema 3): another device took the edit lock over from this one.
 *  - account.recovery_key_id (schema 4): the id of the account's recovery key, a public label, never the key itself.
 *  - recovery_keys (schema 4): one sealed copy of the recovery key per device, crypto_box_seal to that device's public
 *    key and made by another device. The server can't open them; revoking or logging out a device deletes its copy.
 */
class Db(file: Path) : AutoCloseable {
    private val conn: Connection
    private val txScope by lazy { Tx() }

    init {
        Files.createDirectories(file.toAbsolutePath().parent)
        conn = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        conn.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA busy_timeout=5000")
            it.execute("PRAGMA foreign_keys=ON")
            it.execute("PRAGMA synchronous=FULL")
        }
        migrate()
    }

    private fun migrate() = tx {
        exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val v = query("SELECT value FROM meta WHERE key = 'schema_version'") { it.getString(1) }.firstOrNull()?.toInt() ?: 0
        if (v < 1) {
            exec("""CREATE TABLE records (
                entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, data TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
                changed_at INTEGER NOT NULL, changed_by TEXT NOT NULL, seq INTEGER NOT NULL,
                PRIMARY KEY (entity_type, entity_id))""")
            exec("""CREATE TABLE changes (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, change_id TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
                operation TEXT NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')), changed_fields TEXT NOT NULL,
                changed_at INTEGER NOT NULL, device_id TEXT NOT NULL, received_at INTEGER NOT NULL, offline INTEGER NOT NULL DEFAULT 0)""")
            exec("CREATE INDEX changes_entity ON changes (entity_type, entity_id)")
            exec("CREATE TABLE applied_changes (change_id TEXT PRIMARY KEY, seq INTEGER, received_at INTEGER NOT NULL)")
            exec("""CREATE TABLE conflicts (
                id INTEGER PRIMARY KEY AUTOINCREMENT, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, winner_device TEXT NOT NULL,
                loser_device TEXT NOT NULL, note TEXT NOT NULL, at INTEGER NOT NULL)""")
            exec("""CREATE TABLE devices (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, public_key TEXT UNIQUE, paired_at INTEGER NOT NULL,
                last_seen INTEGER, is_open INTEGER NOT NULL DEFAULT 0, revoked_at INTEGER,
                CHECK ((public_key IS NULL) = (revoked_at IS NOT NULL)))""")
            exec("""CREATE TABLE pairing_codes (
                code_hash TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                used_at INTEGER, device_id TEXT)""")
            exec("""CREATE TABLE edit_lock (
                id INTEGER PRIMARY KEY CHECK (id = 1), holder TEXT, granted_at INTEGER, expires_at INTEGER)""")
            exec("INSERT INTO edit_lock (id) VALUES (1)")
            exec("CREATE TABLE seen_nonces (nonce TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            exec("CREATE TABLE failed_pairings (at INTEGER NOT NULL)")
            exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '1')")
        }
        if (v < 2) {
            exec("""CREATE TABLE account (
                id INTEGER PRIMARY KEY CHECK (id = 1), username TEXT NOT NULL, password_hash TEXT NOT NULL, created_at INTEGER NOT NULL)""")
            exec("CREATE TABLE setup_code (id INTEGER PRIMARY KEY CHECK (id = 1), code_hash TEXT NOT NULL, created_at INTEGER NOT NULL)")
            exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '2')")
        }
        if (v < 3) {
            // take over (0.3.0): the device the lock was taken from remembers who took it, until it holds the lock again
            exec("ALTER TABLE devices ADD COLUMN lock_taken_by TEXT")
            exec("ALTER TABLE devices ADD COLUMN lock_taken_at INTEGER")
            exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '3')")
        }
        if (v < 4) {
            // recovery key (0.4.0): the account's key id, and each device's sealed copy of the key (hex), opened only on that device
            exec("ALTER TABLE account ADD COLUMN recovery_key_id TEXT")
            exec("CREATE TABLE recovery_keys (device_id TEXT PRIMARY KEY, key_id TEXT NOT NULL, sealed TEXT NOT NULL, stored_at INTEGER NOT NULL)")
            exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '4')")
        }
    }

    // ---- small JDBC helpers; every public use goes through tx, which holds the one connection

    inner class Tx {
        fun exec(sql: String, vararg args: Any?): Int = conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }

        fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
        }

        fun lastId(): Long = query("SELECT last_insert_rowid()") { it.getLong(1) }.single()
    }

    /** Runs [block] in one transaction; everything is serialized on this object. */
    @Synchronized
    fun <T> tx(block: Tx.() -> T): T {
        conn.autoCommit = false
        try {
            val r = txScope.block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** A consistent copy of the live database into [target] (SQLite VACUUM INTO). */
    @Synchronized
    fun copyTo(target: Path) {
        require(!Files.exists(target)) { "$target already exists" }
        conn.createStatement().use { it.execute("VACUUM INTO '${target.toAbsolutePath().toString().replace("'", "''")}'") }
    }

    @Synchronized
    override fun close() = conn.close()
}

fun ResultSet.longOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }
