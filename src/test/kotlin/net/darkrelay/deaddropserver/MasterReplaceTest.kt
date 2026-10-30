package net.darkrelay.deaddropserver

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Restore while logged in (0.5.1): a restored budget replaces the master copy, after the server's own backup. Test keys, invented data. */
class MasterReplaceTest {
    private val pw = "test-pass-for-unit"

    private fun insert(id: String, name: String, at: Long = System.currentTimeMillis()) =
        Change("c-$id-${System.nanoTime()}", "envelope", id, "INSERT", buildJsonObject { put("name", JsonPrimitive(name)) }, at)

    private fun TestWorld.two(): Pair<Client, Client> {
        val phone = client().also { it.setup(service.startSetup(config.dataDir.resolve(Service.SETUP_FILE), "fp")!!, "tester", pw, "Test phone", "phone") }
        val desk = client().also { it.login("tester", pw, "Test desktop", "desktop") }
        return phone to desk
    }

    private fun backups(w: TestWorld): List<Path> = Files.list(w.backupDir).use { s -> s.filter { it.fileName.toString().endsWith(".ddbak") }.toList() }

    @Test fun theMasterIsReplacedAfterTheServerBacksUpTheOldOne(): Unit = TestWorld().start().use { w ->
        val (phone, desk) = w.two()
        phone.acquire()
        phone.push(listOf(insert("old1", "Old one"), insert("old2", "Old two")))
        val before = phone.status().lastSeq
        assertEquals(0, backups(w).size)
        val r = phone.masterReplace(listOf(insert("new1", "New one"), insert("new2", "New two"), insert("new3", "New three")))
        assertEquals(3, r.applied)
        assertEquals(1, backups(w).size, "the server's backup of the old master was written first")
        assertTrue(r.lastSeq > before, "seqs keep going up")
        val recs = phone.records().records
        assertEquals(setOf("new1", "new2", "new3"), recs.map { it.entityId }.toSet(), "the old rows are gone, the restored ones are the master")
        assertEquals(setOf("New one", "New two", "New three"), recs.map { (it.data["name"] as JsonPrimitive).content }.toSet())
        // the old master is readable in that backup: restore it to a new file and look
        val file = backups(w).single()
        val out = w.root.resolve("check.db")
        w.backups.restore(file, out)
        val copy = Db(out)
        val old = copy.tx { query("SELECT entity_id FROM records ORDER BY entity_id") { it.getString(1) } }
        copy.close()
        assertEquals(listOf("old1", "old2"), old)
    }

    @Test fun otherDevicesAreToldToTakeTheNewMasterOnTheirNextPull(): Unit = TestWorld().start().use { w ->
        val (phone, desk) = w.two()
        phone.acquire()
        phone.push(listOf(insert("a", "A"), insert("b", "B")))
        val seen = desk.pull(0)
        assertFalse(seen.masterReplaced)
        val deskSeq = seen.lastSeq
        val r = phone.masterReplace(listOf(insert("z", "Z")))
        val p = desk.pull(deskSeq)
        assertTrue(p.masterReplaced, "the desktop's last pull is from before the replace")
        assertTrue(p.changes.isEmpty())
        assertEquals(r.lastSeq, p.lastSeq)
        assertEquals(listOf("z"), desk.records().records.map { it.entityId })
        // having taken it, the next pull from the new seq is ordinary again, and later changes still flow
        val again = desk.pull(p.lastSeq)
        assertFalse(again.masterReplaced)
        phone.push(listOf(insert("y", "Y")))
        val next = desk.pull(p.lastSeq)
        assertFalse(next.masterReplaced)
        assertEquals(listOf("y"), next.changes.map { it.entityId })
        // the restoring device's own pulls from its new seq are ordinary too
        assertFalse(phone.pull(r.lastSeq).masterReplaced)
    }

    @Test fun aFailedBackupRefusesTheReplaceAndChangesNothing(): Unit = TestWorld().start().use { w ->
        val (phone, _) = w.two()
        phone.acquire()
        phone.push(listOf(insert("keep", "Keep me")))
        // the backup folder isn't ready (no marker file): the backup fails
        Files.delete(w.backupDir.resolve(Config.BACKUP_MARKER))
        val e = assertFailsWith<DeadDropError> { phone.masterReplace(listOf(insert("new", "New"))) }
        assertEquals("backup_failed", e.code)
        assertTrue(e.message!!.contains("did not replace it"))
        assertFalse(e.message!!.contains(w.root.toString()), "no path in the message")
        assertEquals(listOf("keep"), phone.records().records.map { it.entityId }, "the master is as it was")
        assertEquals("backup_failed", assertFailsWith<DeadDropError> { phone.masterCheck() }.code)
        // with the folder ready again it works
        Files.createFile(w.backupDir.resolve(Config.BACKUP_MARKER))
        assertTrue(phone.masterCheck().backedUp)
        assertEquals(1, phone.masterReplace(listOf(insert("new", "New"))).applied)
    }

    @Test fun aDeviceWithoutTheLockOrARevokedOneIsRefused(): Unit = TestWorld().start().use { w ->
        val (phone, desk) = w.two()
        phone.acquire()
        phone.push(listOf(insert("a", "A")))
        assertEquals("locked", assertFailsWith<DeadDropError> { desk.masterReplace(listOf(insert("z", "Z"))) }.code, "the phone holds the lock")
        assertEquals("locked", assertFailsWith<DeadDropError> { desk.masterCheck() }.code)
        assertEquals(0, backups(w).size, "a refused replace writes no backup")
        assertEquals(listOf("a"), phone.records().records.map { it.entityId })
        // taken over: the old holder is refused with taken_over
        desk.takeover()
        assertEquals("taken_over", assertFailsWith<DeadDropError> { phone.masterReplace(listOf(insert("z", "Z"))) }.code)
        // a revoked device can't do anything
        phone.release()
        desk.revoke(phone.deviceId!!)
        assertTrue(runCatching { phone.masterReplace(listOf(insert("z", "Z"))) }.isFailure)
        assertEquals(listOf("a"), desk.records().records.map { it.entityId })
        // the one holding the lock can
        assertEquals(1, desk.masterReplace(listOf(insert("z", "Z"))).applied)
    }

    @Test fun badInputIsRefusedBeforeAnythingChanges(): Unit = TestWorld().start().use { w ->
        val (phone, _) = w.two()
        phone.acquire()
        phone.push(listOf(insert("a", "A")))
        val dup = insert("x", "X")
        assertEquals("bad_request", assertFailsWith<DeadDropError> { phone.masterReplace(listOf(dup, dup)) }.code)
        assertEquals("bad_request", assertFailsWith<DeadDropError> { phone.masterReplace(listOf(insert("x", "X", at = System.currentTimeMillis() + 3 * 24 * 3600 * 1000))) }.code)
        assertEquals(listOf("a"), phone.records().records.map { it.entityId })
        assertEquals(0, backups(w).size)
    }

    @Test fun noBudgetDataOrCodesGoIntoTheLog(): Unit = TestWorld().start().use { w ->
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val app = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            val (phone, _) = w.two()
            phone.acquire()
            phone.push(listOf(insert("a", "Secret envelope name")))
            phone.masterCheck()
            phone.masterReplace(listOf(insert("z", "Another secret name")))
            val lines = app.list.map { it.formattedMessage }
            assertTrue(lines.any { it.contains("replaced the master copy") }, "the replace is logged by device id start only")
            assertTrue(lines.none { it.contains("secret", ignoreCase = true) }, "no budget data in the log")
            assertTrue(lines.none { it.contains(pw) }, "no password in the log")
        } finally {
            logger.detachAppender(app)
        }
    }

    @Test fun anEmptyRestoredBudgetIsAValidMaster(): Unit = TestWorld().start().use { w ->
        val (phone, desk) = w.two()
        phone.acquire()
        phone.push(listOf(insert("a", "A")))
        val seq = desk.pull(0).lastSeq
        val r = phone.masterReplace(emptyList())
        assertEquals(0, r.applied)
        assertTrue(desk.pull(seq).masterReplaced)
        assertEquals(emptyList(), desk.records().records)
    }
}
