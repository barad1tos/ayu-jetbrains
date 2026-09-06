package dev.ayuislands.accent

import com.intellij.openapi.util.SystemInfo
import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import io.mockk.EqMatcher
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemAppearanceProviderTest {
    @Test
    fun `resolve reads dark appearance only on supported macOS platform`() {
        val commandMatcher = EqMatcher(arrayOf("defaults", "read", "-g", "AppleInterfaceStyle"))
        mockkConstructor(ProcessBuilder::class)

        try {
            every { constructedWith<ProcessBuilder>(commandMatcher).start() } returns
                CompletedPreferenceProcess("Dark\n", 0)

            val actualAppearance = SystemAppearanceProvider.resolve()

            if (SystemInfo.isMac) {
                assertEquals(Appearance.DARK, actualAppearance)
            } else {
                assertNull(actualAppearance)
            }
            verify(exactly = if (SystemInfo.isMac) 1 else 0) {
                constructedWith<ProcessBuilder>(commandMatcher).start()
            }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `default reader launches the global macOS appearance command`() {
        val commandMatcher = EqMatcher(arrayOf("defaults", "read", "-g", "AppleInterfaceStyle"))
        mockkConstructor(ProcessBuilder::class)

        try {
            every { constructedWith<ProcessBuilder>(commandMatcher).start() } returns
                CompletedPreferenceProcess("Dark\n", 0)

            assertEquals(Appearance.DARK, SystemAppearanceProvider.readFromSystem())
            verify(exactly = 1) { constructedWith<ProcessBuilder>(commandMatcher).redirectErrorStream(true) }
            verify(exactly = 1) { constructedWith<ProcessBuilder>(commandMatcher).start() }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `successful Dark output selects dark appearance regardless of whitespace or case`() {
        listOf("Dark", "  dark\n", "DARK").forEach { output ->
            assertEquals(Appearance.DARK, readAppearance(output))
        }
    }

    @Test
    fun `other successful output selects light appearance`() {
        listOf("Light", "", "Automatic").forEach { output ->
            assertEquals(Appearance.LIGHT, readAppearance(output))
        }
    }

    @Test
    fun `nonzero exit selects light appearance even when output says Dark`() {
        assertEquals(
            Appearance.LIGHT,
            SystemAppearanceProvider.readFromSystem { CompletedPreferenceProcess("Dark", 1) },
        )
    }

    @Test
    fun `process start failure leaves appearance unresolved`() {
        assertNull(SystemAppearanceProvider.readFromSystem { error("defaults unavailable") })
    }

    @Test
    fun `output read failure leaves appearance unresolved`() {
        assertNull(SystemAppearanceProvider.readFromSystem { AppearanceReadFailureProcess() })
    }

    @Test
    fun `timeout leaves appearance unresolved and terminates process before reading output`() {
        val process = AppearanceTimeoutProcess()

        assertNull(SystemAppearanceProvider.readFromSystem { process })
        assertTrue(process.wasDestroyed)
    }

    private fun readAppearance(output: String): Appearance? =
        SystemAppearanceProvider.readFromSystem { CompletedPreferenceProcess(output, 0) }
}

private class AppearanceReadFailureProcess : CompletedPreferenceProcess("", 0) {
    override fun getInputStream(): InputStream =
        object : InputStream() {
            override fun read(): Int = error("output unavailable")
        }
}

private class AppearanceTimeoutProcess : CompletedPreferenceProcess("", 0) {
    var wasDestroyed = false

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = false

    override fun getInputStream(): InputStream = throw AssertionError("output read before process completion")

    override fun destroyForcibly(): Process {
        wasDestroyed = true
        return this
    }
}
