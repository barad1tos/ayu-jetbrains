package dev.ayuislands

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Covers the user-observable behaviour of [AyuPlugin]:
 *  - The hardcoded `com.ayuislands.theme` plugin id stays stable across
 *    refactors (any rename would silently break self-introspection like the
 *    version-stamp in the Settings header or the What's New launcher).
 *  - [AyuPlugin.findLoadedPlugin] never reaches internal plugin registry APIs;
 *    known plugins resolve through plugin-aware classloaders and absent or
 *    disabled or broken optional dependencies surface as `null`.
 */
class AyuPluginTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `ID_STRING is the published Marketplace plugin id`() {
        assertEquals(
            "com.ayuislands.theme",
            AyuPlugin.ID_STRING,
            "Plugin coordinates are load-bearing for the JetBrains Marketplace listing - " +
                "changing this silently disconnects the IDE-side descriptor lookup from " +
                "every place the user sees \"Ayu Islands\".",
        )
    }

    @Test
    fun `ID exposes the matching PluginId instance`() {
        // Same content as PluginId.getId(ID_STRING). `getId` interns by string,
        // so identity equality holds and the lazy initializer is a no-op cache.
        assertSame(PluginId.getId(AyuPlugin.ID_STRING), AyuPlugin.ID)
    }

    @Test
    fun `findLoadedPlugin returns null when Application is not bootstrapped`() {
        // Default unit-test environment has no live IDE: `getApplication()`
        // returns `null` and the wrapper short-circuits before classloader lookup.
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns null

        assertNull(AyuPlugin.findLoadedPlugin(AyuPlugin.ID))
    }

    @Test
    fun `findLoadedPlugin returns null for unsupported plugin ids`() {
        val app = mockk<Application>()
        val unsupportedId = PluginId.getId("example.unsupported.plugin")
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app

        assertNull(AyuPlugin.findLoadedPlugin(unsupportedId))
    }

    @Test
    fun `findLoadedPlugin returns null when optional plugin marker class is unavailable`() {
        val app = mockk<Application>()
        val absentId = PluginId.getId("indent-rainbow.indent-rainbow")
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app

        assertNull(AyuPlugin.findLoadedPlugin(absentId))
    }

    @Test
    fun `findLoadedPlugin returns null when marker class is not loaded by a plugin-aware classloader`() {
        val app = mockk<Application>()
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns app

        assertNull(
            AyuPlugin.findLoadedPlugin(AyuPlugin.ID),
            "Self-descriptor lookup must degrade cleanly in non-IDE test classloaders " +
                "instead of breaking settings or update checks.",
        )
    }

    @Test
    fun `descriptorFromPluginAwareClassLoader returns matching plugin descriptor`() {
        val classLoader = mockk<PluginAwareClassLoader>()
        val descriptor = mockk<PluginDescriptor>()
        every { classLoader.pluginDescriptor } returns descriptor
        every { descriptor.pluginId } returns AyuPlugin.ID

        assertSame(
            descriptor,
            AyuPlugin.descriptorFromPluginAwareClassLoader(classLoader, AyuPlugin.ID),
            "The wrapper must surface the live descriptor verbatim - callers read " +
                ".version, .pluginPath, .pluginClassLoader off it.",
        )
    }

    @Test
    fun `descriptorFromPluginAwareClassLoader rejects mismatched plugin descriptor`() {
        val classLoader = mockk<PluginAwareClassLoader>()
        val descriptor = mockk<PluginDescriptor>()
        every { classLoader.pluginDescriptor } returns descriptor
        every { descriptor.pluginId } returns PluginId.getId("other.plugin")

        assertNull(
            AyuPlugin.descriptorFromPluginAwareClassLoader(classLoader, AyuPlugin.ID),
            "A marker class from the wrong plugin must not satisfy Ayu plugin lookup.",
        )
    }

    @Test
    fun `descriptorFromPluginAwareClassLoader returns null when optional plugin bytecode is broken`() {
        val classLoader = mockk<PluginAwareClassLoader>()
        every { classLoader.pluginDescriptor } throws NoClassDefFoundError("missing optional dependency")

        assertNull(
            AyuPlugin.descriptorFromPluginAwareClassLoader(classLoader, AyuPlugin.ID),
            "A broken optional integration must not prevent users from opening Settings " +
                "or starting the IDE with Ayu enabled.",
        )
    }

    @Test
    fun `descriptorFromPluginAwareClassLoader returns null when descriptor lookup fails`() {
        val classLoader = mockk<PluginAwareClassLoader>()
        every { classLoader.pluginDescriptor } throws IllegalStateException("descriptor unavailable")

        assertNull(
            AyuPlugin.descriptorFromPluginAwareClassLoader(classLoader, AyuPlugin.ID),
            "A broken optional integration must not prevent users from opening Settings " +
                "or starting the IDE with Ayu enabled.",
        )
    }
}
