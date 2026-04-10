package dev.ayuislands.testing

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Golden-file snapshot comparison utility for state regression testing.
 *
 * On the first run (golden file missing), creates the file and fails
 * with a "snapshot created" message. On subsequent runs, compares the
 * actual string against the golden file — any diff means a test failure
 * with the full expected/actual for review.
 */
internal object SnapshotAssert {
    fun assertMatchesSnapshot(
        name: String,
        actual: String,
    ) {
        val goldenFile = Path.of("src/test/resources/snapshots/$name")
        if (!goldenFile.exists()) {
            goldenFile.parent.createDirectories()
            goldenFile.writeText(actual)
            fail("Snapshot '$name' created — re-run to verify.")
        }
        assertEquals(goldenFile.readText(), actual, "Snapshot '$name' changed")
    }
}
