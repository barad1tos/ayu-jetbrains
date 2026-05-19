package dev.ayuislands

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
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
 *  - [AyuPlugin.findEnabledPlugin] returns the descriptor for a live plugin,
 *    `null` when the plugin is absent / disabled, `null` in a unit-test
 *    environment without an [Application], and `null` when a mocked test
 *    [Application] returns a non-[PluginManager] `Object` from `getService`.
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
            "Plugin coordinates are load-bearing for the JetBrains Marketplace listing — " +
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
    fun `findEnabledPlugin returns null when Application is not bootstrapped`() {
        // Default unit-test environment has no live IDE: `getApplication()`
        // returns `null` and the wrapper short-circuits without touching
        // `PluginManager.getInstance`. The wrapper SHOULD NOT throw.
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns null

        assertNull(AyuPlugin.findEnabledPlugin(AyuPlugin.ID))
    }

    @Test
    fun `findEnabledPlugin returns descriptor for an installed enabled plugin`() {
        // Real-user scenario: Ayu Islands is installed and enabled — the
        // version-string lookup in `AyuIslandsConfigurable.createPanel` and
        // the manifest probe in `WhatsNewLauncher.openManually` BOTH depend
        // on the wrapper returning a non-null descriptor when the plugin is
        // live in the IDE.
        val app = mockk<Application>()
        val pluginManager = mockk<PluginManager>()
        val descriptor = mockk<IdeaPluginDescriptor>()
        mockkStatic(ApplicationManager::class)
        mockkStatic(PluginManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { PluginManager.getInstance() } returns pluginManager
        every { pluginManager.findEnabledPlugin(AyuPlugin.ID) } returns descriptor

        // Identity assertion (not just non-null) — a future refactor that
        // accidentally wrapped the descriptor (e.g. in a Decorator) would
        // silently change the type read by `.version`/`.pluginPath`/
        // `.pluginClassLoader` callers; assertSame catches that drift.
        assertSame(
            descriptor,
            AyuPlugin.findEnabledPlugin(AyuPlugin.ID),
            "The wrapper must surface the live descriptor verbatim — callers read " +
                ".version, .pluginPath, .pluginClassLoader off it.",
        )
    }

    @Test
    fun `findEnabledPlugin returns null when the requested plugin is not installed`() {
        // Real-user scenario: a third-party integration (CodeGlance Pro,
        // Indent Rainbow) probe runs when those plugins are NOT installed —
        // the wrapper must produce `null` so callers skip the integration path
        // cleanly instead of crashing.
        val app = mockk<Application>()
        val pluginManager = mockk<PluginManager>()
        val absentId = PluginId.getId("com.nasller.CodeGlancePro")
        mockkStatic(ApplicationManager::class)
        mockkStatic(PluginManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { PluginManager.getInstance() } returns pluginManager
        every { pluginManager.findEnabledPlugin(absentId) } returns null

        assertNull(AyuPlugin.findEnabledPlugin(absentId))
    }

    @Test
    fun `findEnabledPlugin returns null when the requested plugin is installed but disabled`() {
        // Real-user scenario: a third-party plugin is INSTALLED but DISABLED.
        // The underlying `PluginManager.findEnabledPlugin` returns null for
        // disabled plugins by contract (that is exactly what differentiates
        // it from `getPlugin`, which still returns a descriptor for disabled
        // installations). The wrapper passes that null through, which lets
        // `ConflictRegistry` correctly skip disabled-plugin conflicts.
        val app = mockk<Application>()
        val pluginManager = mockk<PluginManager>()
        val disabledId = PluginId.getId("indent-rainbow.indent-rainbow")
        mockkStatic(ApplicationManager::class)
        mockkStatic(PluginManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { PluginManager.getInstance() } returns pluginManager
        every { pluginManager.findEnabledPlugin(disabledId) } returns null

        assertNull(
            AyuPlugin.findEnabledPlugin(disabledId),
            "Disabled plugins must look like \"absent\" to downstream filters — that " +
                "is the contract `ConflictRegistry.computeConflicts` relies on.",
        )
    }

    @Test
    fun `findEnabledPlugin survives a mocked Application whose getService returns Object`() {
        // Pins the `catch (_: ClassCastException)` branch in
        // [AyuPlugin.findEnabledPlugin]. Real test-suite scenario: another
        // test in the JVM mocked `ApplicationManager.getApplication()` to
        // return an Application whose `getService(PluginManager::class.java)`
        // returns a bare Object. That shape triggers a ClassCastException
        // inside `PluginManager.getInstance()` (one frame deeper than where
        // we observe it). The wrapper catches it so unrelated tests (Chrome
        // refresh, ConflictRegistry probes) don't crash with platform-
        // internal errors they don't actually exercise.
        //
        // Deleting the production catch will fail this test.
        val app = mockk<Application>()
        mockkStatic(ApplicationManager::class)
        mockkStatic(PluginManager::class)
        every { ApplicationManager.getApplication() } returns app
        every { PluginManager.getInstance() } throws ClassCastException("mock Application returned Object")

        assertNull(AyuPlugin.findEnabledPlugin(AyuPlugin.ID))
    }
}
