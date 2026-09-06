package dev.ayuislands.accent

import io.mockk.EqMatcher
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemAccentProviderTest {
    @Test
    fun `default reader launches the global macOS accent command`() {
        val commandMatcher = EqMatcher(arrayOf("defaults", "read", "-g", "AppleAccentColor"))
        mockkConstructor(ProcessBuilder::class)

        try {
            every { constructedWith<ProcessBuilder>(commandMatcher).start() } returns
                CompletedPreferenceProcess("4\n", 0)

            assertEquals("#73D0FF", SystemAccentProvider.readFromSystem())
            verify(exactly = 1) { constructedWith<ProcessBuilder>(commandMatcher).redirectErrorStream(true) }
            verify(exactly = 1) { constructedWith<ProcessBuilder>(commandMatcher).start() }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `maps every macOS accent value to its Ayu color`() {
        val expectedColors =
            mapOf(
                -1 to "#8A9199",
                0 to "#F28779",
                1 to "#FFA659",
                2 to "#FFCD66",
                3 to "#95E6CB",
                4 to "#73D0FF",
                5 to "#DFBFFF",
                6 to "#F27983",
            )

        expectedColors.forEach { (accentValue, expectedColor) ->
            assertEquals(expectedColor, readAccent(accentValue.toString()))
        }
    }

    @Test
    fun `trims scalar output before mapping`() {
        assertEquals("#95E6CB", readAccent("  3\n"))
    }

    @Test
    fun `falls back to blue for invalid successful output`() {
        listOf("", "purple", "7").forEach { output ->
            assertEquals("#73D0FF", readAccent(output))
        }
    }

    @Test
    fun `falls back to blue for nonzero exit`() {
        assertEquals("#73D0FF", SystemAccentProvider.readFromSystem { CompletedPreferenceProcess("missing", 1) })
    }

    @Test
    fun `returns null when process start fails`() {
        assertNull(SystemAccentProvider.readFromSystem { error("defaults unavailable") })
    }

    @Test
    fun `returns null when output read fails`() {
        assertNull(SystemAccentProvider.readFromSystem { ReadFailureProcess() })
    }

    private fun readAccent(output: String): String? =
        SystemAccentProvider.readFromSystem { CompletedPreferenceProcess(output, 0) }
}

private class ReadFailureProcess : CompletedPreferenceProcess("", 0) {
    override fun getInputStream(): InputStream =
        object : InputStream() {
            override fun read(): Int = error("output unavailable")
        }
}
