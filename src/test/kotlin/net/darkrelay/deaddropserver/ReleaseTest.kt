package net.darkrelay.deaddropserver

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a release relies on (0.5.0): the version in one place, and the notices pointing only at files that exist. */
class ReleaseTest {
    @Test fun theServerReportsTheBuildsVersion() {
        val build = File("build.gradle.kts").readText()
        val version = Regex("""^version = "([^"]+)"""", RegexOption.MULTILINE).find(build)?.groupValues?.get(1)
        assertEquals(version, VERSION, "Server.kt VERSION (what /health says) is the build's version")
    }

    @Test fun everyLicenseFileTheNoticesNameExists() {
        val notices = File("THIRD-PARTY-NOTICES.md").readText() + File("NOTICE").readText()
        val named = Regex("""licenses/[A-Za-z0-9_./+-]+\.(?:txt|md|copyright)""").findAll(notices).map { it.value }.toSet()
        assertTrue(named.size > 140, "the notices name the license files (${named.size})")
        val missing = named.filterNot { File(it).isFile }
        assertTrue(missing.isEmpty(), "named but missing: $missing")
        assertTrue(File("LICENSE").readText().contains("Apache License"), "LICENSE is the Apache License")
    }
}
