package dev.ayuislands.indent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AccentApplyScope
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IndentRainbowSyncTest {
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private lateinit var state: AyuIslandsState
    private val mockApplication = mockk<com.intellij.openapi.application.Application>(relaxed = true)

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        state.isIrOwnershipMigrated = true
        // getAccentForVariant stub used to be required because IR read the global accent
        // itself. After the resolver refactor the caller passes the resolved hex in; the
        // mock becomes dead code. Removed to keep setUp honest.

        mockkObject(AyuPlugin)
        every { AyuPlugin.findLoadedPlugin(any()) } returns null

        resetIrBindings()
    }

    /**
     * Helper that invokes [IndentRainbowSync.apply] with the single test accent hex. Most tests in
     * this suite verify flow (integration flag, plugin availability, reflection fallbacks, palette
     * composition) rather than hex-specific behavior — pulling the hex into one constant removes 20
     * hardcoded strings and keeps each test readable as "call apply for variant X".
     */
    private fun callApply(variant: AyuVariant = AyuVariant.MIRAGE): IntegrationOutcome =
        IndentRainbowSync.apply(variant, TEST_ACCENT_HEX)

    private fun callApplyExternal(): IntegrationOutcome = IndentRainbowSync.apply(AccentContext.External, "#AABBCC")

    private companion object {
        /** Fixed accent used by every `callApply(...)` invocation in this suite. */
        const val TEST_ACCENT_HEX = "#FFCC66"
    }

    @AfterTest
    fun tearDown() {
        resetIrBindings()
        unmockkAll()
    }

    @Test
    fun `outer abort restores the previous accent palette and its ownership metadata`() {
        state.irIntegrationEnabled = true
        state.indentPresetName = "AMBIENT"
        val harness = installIrHarness(mockApplication, FakePaletteType.DEFAULT, "native palette", 3)
        assertIs<IntegrationOutcome.Applied>(callApply())
        val previousPalette = harness.palette
        val previousCount = harness.colorCount
        val failure = IllegalStateException("a later integration failed")

        withScope { scope ->
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    scope.restoringOnFailure {
                        scope.runStep {
                            assertIs<IntegrationOutcome.Applied>(
                                IndentRainbowSync.apply(AyuVariant.MIRAGE, "#112233"),
                            )
                            assertNotEquals(previousPalette, harness.palette)
                        }
                        scope.runStep { throw failure }
                    }
                },
            )
        }

        assertEquals(FakePaletteType.CUSTOM, harness.type)
        assertEquals(previousPalette, harness.palette)
        assertEquals(previousCount, harness.colorCount)
        assertEquals("OWNED", state.irOwnership)
        assertEquals("DEFAULT", state.irBaseType)
        assertEquals("native palette", state.irBasePalette)
        assertEquals(3, state.irBaseColorCount)
        assertEquals("CUSTOM", state.irAppliedType)
        assertEquals(previousPalette, state.irAppliedPalette)
        assertEquals(previousCount, state.irAppliedColorCount)
        assertEquals("AMBIENT", state.indentPresetName)
    }

    @Test
    fun `outer abort restores raw metadata changed by the migration gate without touching preferences`() {
        state.irIntegrationEnabled = true
        state.isIrOwnershipMigrated = false
        state.irOwnership = "future-owner"
        state.irBaseType = "future-type"
        state.irBasePalette = null
        state.irBaseColorCount = -7
        state.irAppliedType = null
        state.irAppliedPalette = "unknown raw palette"
        state.irAppliedColorCount = -3
        state.indentPresetName = "future-preset"
        state.indentCustomAlpha = -17

        withScope { scope ->
            assertIs<IntegrationOutcome.Skipped>(scope.runStep { callApply() })
            assertTrue(state.isIrOwnershipMigrated)
            scope.restore()
        }

        assertFalse(state.isIrOwnershipMigrated)
        assertEquals("future-owner", state.irOwnership)
        assertEquals("future-type", state.irBaseType)
        assertNull(state.irBasePalette)
        assertEquals(-7, state.irBaseColorCount)
        assertNull(state.irAppliedType)
        assertEquals("unknown raw palette", state.irAppliedPalette)
        assertEquals(-3, state.irAppliedColorCount)
        assertTrue(state.irIntegrationEnabled)
        assertEquals("future-preset", state.indentPresetName)
        assertEquals(-17, state.indentCustomAlpha)
    }

    @Test
    fun `aborted first sync restores the native palette and preserves a changed preference`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(callApply())
            state.indentCustomAlpha = 37
            scope.restore()
        }

        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user palette", harness.palette)
        assertEquals(4, harness.colorCount)
        assertEquals("UNOWNED", state.irOwnership)
        assertNull(state.irBaseType)
        assertNull(state.irAppliedPalette)
        assertEquals(37, state.indentCustomAlpha)
    }

    @Test
    fun `aborted disabled gate restores the old accent without reenabling the preference`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        assertIs<IntegrationOutcome.Applied>(callApply())
        val previousPalette = harness.palette
        state.irIntegrationEnabled = false

        withScope { scope ->
            assertIs<IntegrationOutcome.Restored>(callApply())
            assertEquals("user palette", harness.palette)
            scope.restore()
        }

        assertEquals(previousPalette, harness.palette)
        assertEquals(FakePaletteType.CUSTOM, harness.type)
        assertEquals("OWNED", state.irOwnership)
        assertEquals("user palette", state.irBasePalette)
        assertEquals(previousPalette, state.irAppliedPalette)
        assertFalse(state.irIntegrationEnabled)
    }

    @Test
    fun `outer rollback preserves a foreign palette and releases ownership of the triplet`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(callApply())
            harness.palette = "foreign palette"
            scope.restore()
        }

        assertEquals("foreign palette", harness.palette)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals(4, harness.colorCount)
        assertEquals("SUSPENDED", state.irOwnership)
    }

    @Test
    fun `outer rollback never restores a replaced IR config`() {
        state.irIntegrationEnabled = true
        val original = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(callApply())
            val attemptedPalette = original.palette
            val replacement =
                installIrHarness(mockApplication, FakePaletteType.DEFAULT, "replacement palette", 2)
            scope.restore()

            assertEquals(attemptedPalette, original.palette)
            assertEquals("replacement palette", replacement.palette)
            assertEquals(FakePaletteType.DEFAULT, replacement.type)
            assertEquals(2, replacement.colorCount)
        }
        assertEquals("SUSPENDED", state.irOwnership)
    }

    @Test
    fun `failed outer rollback is retried before the next sync without losing the native baseline`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        assertIs<IntegrationOutcome.Applied>(callApply())
        val previousPalette = harness.palette

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(IndentRainbowSync.apply(AyuVariant.MIRAGE, "#112233"))
            harness.shouldFailNextPalette = true
            assertFailsWith<IllegalStateException> { scope.restore() }
            assertEquals("RECOVERY_PENDING", state.irOwnership)
            assertEquals(harness.palette, state.irAppliedPalette)
            assertEquals("user palette", state.irBasePalette)
        }

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(IndentRainbowSync.apply(AyuVariant.MIRAGE, "#445566"))
            scope.restore()
        }

        assertEquals(previousPalette, harness.palette)
        assertEquals("OWNED", state.irOwnership)
        assertEquals(previousPalette, state.irAppliedPalette)
        assertEquals("user palette", state.irBasePalette)
    }

    @Test
    fun `outer rollback preserves a foreign ownership marker even when native fields changed`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(callApply())
            harness.palette = "foreign palette"
            state.irOwnership = "future-owner"
            scope.restore()
        }

        assertEquals("foreign palette", harness.palette)
        assertEquals("future-owner", state.irOwnership)
    }

    @Test
    fun `foreign applied metadata cannot leave the restored palette claimed as owned`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        assertIs<IntegrationOutcome.Applied>(callApply())
        val previousPalette = harness.palette

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(IndentRainbowSync.apply(AyuVariant.MIRAGE, "#112233"))
            state.irAppliedPalette = "foreign metadata"
            scope.restore()
        }

        assertEquals(previousPalette, harness.palette)
        assertEquals("foreign metadata", state.irAppliedPalette)
        assertEquals("SUSPENDED", state.irOwnership)
    }

    @Test
    fun `service replacement without a binding reset prevents restoration of the obsolete config`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(callApply())
            val attemptedPalette = harness.palette
            val config = getPrivateField<Any>("irConfig")
            every { mockApplication.getService(config.javaClass) } returns Any()
            scope.restore()
            assertEquals(attemptedPalette, harness.palette)
        }

        assertEquals("SUSPENDED", state.irOwnership)
    }

    @Test
    fun `outer rollback does not refresh an already recovered failed operation twice`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        harness.shouldFailNextPalette = true

        withScope { scope ->
            assertIs<IntegrationOutcome.Failed>(callApply())
            assertEquals("user palette", harness.palette)
            val cacheUpdate = getPrivateField<Method>("cachedDataUpdateMethod")
            every { cacheUpdate.invoke(any(), any()) } throws IllegalStateException("duplicate refresh")
            scope.restore()
        }

        assertEquals("UNOWNED", state.irOwnership)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `cache cancellation propagates unchanged after local rollback without warning`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        val cacheUpdate = getPrivateField<Method>("cachedDataUpdateMethod")
        for (
        cancelled in
        listOf(ProcessCanceledException(), CancellationException("cancel palette sync"))
        ) {
            var shouldCancel = true
            every { cacheUpdate.invoke(any(), any()) } answers
                {
                    if (shouldCancel) {
                        shouldCancel = false
                        throw cancelled
                    }
                    Unit
                }
            withScope { scope ->
                val warnings = mutableListOf<Throwable>()
                val processor =
                    object : LoggedErrorProcessor() {
                        override fun processWarn(
                            category: String,
                            message: String,
                            t: Throwable?,
                        ): Boolean {
                            if (t != null) warnings += t
                            return false
                        }
                    }
                LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
                    assertSame(
                        cancelled,
                        assertFailsWith<RuntimeException> { scope.restoringOnFailure { callApply() } },
                    )
                }
                assertFalse(warnings.any { it === cancelled })
            }
            assertEquals("user palette", harness.palette)
            assertEquals(FakePaletteType.RAINBOW, harness.type)
            assertEquals(4, harness.colorCount)
            assertEquals("UNOWNED", state.irOwnership)
        }
    }

    @Test
    fun `restart palette recovery keeps the native baseline and raw preferences`() {
        state.irIntegrationEnabled = true
        state.irBaseType = "future-type"
        state.irBasePalette = "stale palette"
        state.irBaseColorCount = -7
        state.indentPresetName = "future-preset"
        state.indentCustomAlpha = -17
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(callApply())
            harness.shouldFailNextPalette = true
            assertFailsWith<IllegalStateException> { scope.restore() }
        }
        assertEquals("RAINBOW", state.irBaseType)
        assertEquals("user palette", state.irBasePalette)
        assertEquals(4, state.irBaseColorCount)

        state = XmlSerializer.deserialize(XmlSerializer.serialize(state), AyuIslandsState::class.java)
        every { mockSettings.state } returns state
        resetIrBindings()
        val restarted =
            installIrHarness(mockApplication, harness.type, harness.palette, harness.colorCount)
        assertIs<IntegrationOutcome.Restored>(IndentRainbowSync.restoreOwnedState())
        assertEquals("user palette", restarted.palette)
        assertEquals(FakePaletteType.RAINBOW, restarted.type)
        assertEquals(4, restarted.colorCount)
        state.irIntegrationEnabled = false
        assertIs<IntegrationOutcome.Skipped>(callApply())
        assertFalse(state.irIntegrationEnabled)
        assertEquals("future-preset", state.indentPresetName)
        assertEquals(-17, state.indentCustomAlpha)
        state.irIntegrationEnabled = true
        IndentRainbowSync.prepareExplicitEnable()
        assertIs<IntegrationOutcome.Applied>(callApply())
        assertEquals("user palette", state.irBasePalette)
        assertEquals("RAINBOW", state.irBaseType)
        assertEquals(4, state.irBaseColorCount)
    }

    @Test
    fun `pending retry reports ordinary failures and propagates control signals`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        val refresh = mockk<Method>(relaxed = true)
        var retryError: Throwable? = null
        every { refresh.invoke(any()) } answers {
            retryError?.let { failure -> throw failure }
            null
        }
        setIrField("refreshMethod", refresh)
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(callApply())
            retryError = IllegalStateException("cache refresh rejected")
            assertFailsWith<IllegalStateException> { scope.restore() }
        }

        val outcome = assertIs<IntegrationOutcome.Failed>(callApply())
        assertSame(retryError, outcome.error)
        assertEquals("RECOVERY_PENDING", state.irOwnership)
        for (cancelled in listOf(ProcessCanceledException(), CancellationException("cancel retry"))) {
            retryError = cancelled
            assertSame(cancelled, assertFailsWith<RuntimeException> { callApply() })
        }
        val fatal = object : VirtualMachineError("fatal retry") {}
        retryError = fatal
        assertSame(fatal, assertFailsWith<VirtualMachineError> { callApply() })
        retryError = null
        assertIs<IntegrationOutcome.Applied>(callApply())
        assertEquals(FakePaletteType.CUSTOM, harness.type)
        assertEquals("user palette", state.irBasePalette)
        assertEquals(4, state.irBaseColorCount)
    }

    @Test
    fun `restart after failed rollback never claims a foreign palette field`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(callApply())
            harness.palette = "foreign palette"
            harness.shouldFailNextCountAndRollbackPalette = true
            assertFailsWith<IllegalStateException> { scope.restore() }
        }

        state = XmlSerializer.deserialize(XmlSerializer.serialize(state), AyuIslandsState::class.java)
        every { mockSettings.state } returns state
        resetIrBindings()
        val restarted = installIrHarness(mockApplication, harness.type, harness.palette, harness.colorCount)

        val outcome = IndentRainbowSync.restoreOwnedState()
        assertEquals("foreign palette", restarted.palette)
        assertEquals(FakePaletteType.RAINBOW, restarted.type)
        assertEquals(4, restarted.colorCount)
        assertIs<IntegrationOutcome.Skipped>(outcome)
        assertEquals("SUSPENDED", state.irOwnership)
    }

    @Test
    fun `replacement during rollback cache refresh releases metadata ownership`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        assertIs<IntegrationOutcome.Applied>(callApply())
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(IndentRainbowSync.apply(AyuVariant.MIRAGE, "#112233"))
            val config = getPrivateField<Any>("irConfig")
            val cacheUpdate = getPrivateField<Method>("cachedDataUpdateMethod")
            every { cacheUpdate.invoke(any(), any()) } answers
                {
                    every { mockApplication.getService(config.javaClass) } returns Any()
                    Unit
                }
            scope.restore()
        }
        assertEquals("SUSPENDED", state.irOwnership)
        assertEquals(FakePaletteType.CUSTOM, harness.type)
    }

    @Test
    fun `rollback cache cancellation wins over the original write failure`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user palette", 4)
        val cacheUpdate = getPrivateField<Method>("cachedDataUpdateMethod")
        val writeError = IllegalStateException("cache write failed")
        val cancelled = CancellationException("cache rollback cancelled")
        var updates = 0
        every { cacheUpdate.invoke(any(), any()) } answers
            {
                when (++updates) {
                    1 -> throw writeError
                    2 -> throw cancelled
                    else -> Unit
                }
            }
        withScope { scope ->
            assertSame(
                cancelled,
                assertFailsWith<RuntimeException> { scope.restoringOnFailure { callApply() } },
            )
        }
        assertTrue(cancelled.suppressed.any { it === writeError })
        assertEquals("user palette", harness.palette)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals(4, harness.colorCount)
        assertEquals("UNOWNED", state.irOwnership)
    }

    @Test
    fun `failed persisted palette recovery captures the actual partial triplet before retry`() {
        val harness = installIrHarness(mockApplication, FakePaletteType.CUSTOM, "partial palette", 11)
        state.irOwnership = "RECOVERY_PENDING"
        state.irBaseType = "RAINBOW"
        state.irBasePalette = "user palette"
        state.irBaseColorCount = 4
        state.irAppliedType = "CUSTOM"
        state.irAppliedPalette = "partial palette"
        state.irAppliedColorCount = 11
        harness.shouldFailNextPalette = true

        assertIs<IntegrationOutcome.Failed>(IndentRainbowSync.restoreOwnedState())
        assertEquals(harness.type.name, state.irAppliedType)
        assertEquals(harness.palette, state.irAppliedPalette)
        assertEquals(harness.colorCount, state.irAppliedColorCount)
        assertEquals("RECOVERY_PENDING", state.irOwnership)
        assertIs<IntegrationOutcome.Restored>(IndentRainbowSync.restoreOwnedState())
        assertEquals("user palette", harness.palette)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals(4, harness.colorCount)
        assertEquals("UNOWNED", state.irOwnership)
    }

    @Test
    fun `resolution cancellation does not cache absent IR bindings`() {
        state.irIntegrationEnabled = true
        val cancelled = ProcessCanceledException()
        every { AyuPlugin.findLoadedPlugin(any()) } throws cancelled
        assertSame(cancelled, assertFailsWith<ProcessCanceledException> { callApply() })
        assertFalse(getPrivateField("methodsResolved"))
        every { AyuPlugin.findLoadedPlugin(any()) } returns null
        assertIs<IntegrationOutcome.Skipped>(callApply())
        assertTrue(getPrivateField("methodsResolved"))
    }

    private fun withScope(action: (AccentApplyScope) -> Unit) {
        SwingUtilities.invokeAndWait {
            val scope = AccentApplyScope()
            scope.open()
            try {
                action(scope)
            } finally {
                scope.close()
            }
        }
    }

    @Test
    fun `apply with integration disabled calls revert gracefully`() {
        state.irIntegrationEnabled = false

        callApply()

        // Should not throw — revert also gracefully returns when IR not installed
    }

    @Test
    fun `apply with integration enabled but no IR plugin does not throw`() {
        state.irIntegrationEnabled = true

        callApply()

        // resolveReflection finds no plugin, resolveOrReturn returns null, method exits
    }

    @Test
    fun `revert when IR plugin not installed does not throw`() {
        IndentRainbowSync.revert()
    }

    @Test
    fun `resolveReflection sets methodsResolved even when plugin not found`() {
        invokePrivate("resolveReflection")

        val resolved = getPrivateField<Boolean>("methodsResolved")
        assertTrue(resolved, "methodsResolved should be true after first call")
    }

    @Test
    fun `resolveReflection is idempotent`() {
        invokePrivate("resolveReflection")
        invokePrivate("resolveReflection")

        // The second call exits immediately via guard
        val resolved = getPrivateField<Boolean>("methodsResolved")
        assertTrue(resolved)
    }

    @Test
    fun `resolveReflection returns when plugin classloader is null`() {
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns null

        invokePrivate("resolveReflection")

        assertNull(getPrivateField("irConfig"))
        assertTrue(getPrivateField("methodsResolved"))
    }

    @Test
    fun `resolveOrReturn returns null when fields not resolved`() {
        setIrField("methodsResolved", true)
        // All fields remain null

        val method =
            IndentRainbowSync::class.java.declaredMethods.first { it.name == "resolveOrReturn" }
        method.isAccessible = true
        val result = method.invoke(IndentRainbowSync)
        assertNull(result, "resolveOrReturn should return null when irConfig is null")
    }

    @Test
    fun `apply for each variant does not throw when IR not installed`() {
        state.irIntegrationEnabled = true
        for (variant in AyuVariant.entries) {
            resetIrBindings()
            callApply(variant)
        }
    }

    @Test
    fun `apply with enabled integration writes to mock IR fields`() {
        state.irIntegrationEnabled = true
        state.indentPresetName = "AMBIENT"

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()

        // Verify a palette type was set to CUSTOM
        verify { mockPaletteTypeField[mockConfig] = "CUSTOM_ENUM" }
        // Verify custom palette string was written
        verify { mockCustomPaletteField[mockConfig] = any<String>() }
        // Verify number of colors = 11 (IR ignores this field, pass full count)
        verify { mockNumberColorsField.setInt(mockConfig, 11) }
        // Verify cache flush
        verify { mockUpdateMethod.invoke(mockCompanion, mockConfig) }
        verify { mockRefreshMethod.invoke(mockColorsInstance) }
    }

    @Test
    fun `first Indent Rainbow sync captures exact state and restore reproduces it`() {
        state.irIntegrationEnabled = true
        val harness =
            installIrHarness(
                mockApplication,
                type = FakePaletteType.RAINBOW,
                palette = "user-one, user-two",
                colorCount = 2,
            )

        val applyOutcome = IndentRainbowSync.apply(AyuVariant.MIRAGE, TEST_ACCENT_HEX)

        assertEquals(IntegrationOutcome.Applied, applyOutcome)
        assertEquals(IntegrationOwnership.OWNED.name, state.irOwnership)
        assertEquals(FakePaletteType.RAINBOW.name, state.irBaseType)
        assertEquals("user-one, user-two", state.irBasePalette)
        assertEquals(2, state.irBaseColorCount)
        assertEquals(FakePaletteType.CUSTOM.name, state.irAppliedType)
        assertEquals(11, state.irAppliedColorCount)

        val restoreOutcome = IndentRainbowSync.restoreOwnedState()

        assertEquals(IntegrationOutcome.Restored, restoreOutcome)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-one, user-two", harness.palette)
        assertEquals(2, harness.colorCount)
        assertEquals(IntegrationOwnership.UNOWNED.name, state.irOwnership)
        assertEquals(null, state.irBaseType)
        assertEquals(null, state.irAppliedType)
    }

    @Test
    fun `manual Indent Rainbow edit suspends ownership without another Ayu write`() {
        state.irIntegrationEnabled = true
        val harness =
            installIrHarness(
                mockApplication,
                type = FakePaletteType.DEFAULT,
                palette = "user-palette",
                colorCount = 1,
            )
        IndentRainbowSync.apply(AyuVariant.MIRAGE, TEST_ACCENT_HEX)
        harness.palette = "manual-palette"

        val outcome = IndentRainbowSync.apply(AyuVariant.MIRAGE, "#AABBCC")

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.irOwnership)
        assertEquals("manual-palette", harness.palette)
        assertEquals(FakePaletteType.CUSTOM, harness.type)
        assertEquals(11, harness.colorCount)
    }

    @Test
    fun `legacy Indent Rainbow state suspends instead of capturing an Ayu palette as baseline`() {
        state.irIntegrationEnabled = true
        state.isIrOwnershipMigrated = false
        val harness = installIrHarness(mockApplication, FakePaletteType.CUSTOM, "ayu-palette", 11)

        val outcome = callApply()

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.irOwnership)
        assertTrue(state.isIrOwnershipMigrated)
        assertEquals(FakePaletteType.CUSTOM, harness.type)
        assertEquals("ayu-palette", harness.palette)
        assertEquals(11, harness.colorCount)
        assertEquals(null, state.irBaseType)
    }

    @Test
    fun `failed Indent Rainbow restore rolls a partial write back to the applied palette`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        IndentRainbowSync.apply(AyuVariant.MIRAGE, TEST_ACCENT_HEX)
        val applied =
            IrPalette(
                type = harness.type.name,
                palette = harness.palette,
                colorCount = harness.colorCount,
            )
        val updateMethod = getPrivateField<Method>("cachedDataUpdateMethod")
        var shouldRejectNextUpdate = true
        every { updateMethod.invoke(any(), any()) } answers
            {
                if (shouldRejectNextUpdate) {
                    shouldRejectNextUpdate = false
                    throw java.lang.reflect.InvocationTargetException(
                        IllegalStateException("cache update rejected after field writes"),
                    )
                }
                null
            }

        val outcome = IndentRainbowSync.restoreOwnedState()

        assertTrue(outcome is IntegrationOutcome.Failed)
        assertEquals(applied.type, harness.type.name)
        assertEquals(applied.palette, harness.palette)
        assertEquals(applied.colorCount, harness.colorCount)
        assertEquals(IntegrationOwnership.OWNED.name, state.irOwnership)
        assertEquals(FakePaletteType.RAINBOW.name, state.irBaseType)
    }

    @Test
    fun `failed Indent Rainbow restore and rollback remain recoverable`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        IndentRainbowSync.apply(AyuVariant.MIRAGE, TEST_ACCENT_HEX)
        harness.shouldFailNextCountAndRollbackPalette = true

        val failed = IndentRainbowSync.restoreOwnedState()

        assertTrue(failed is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.RECOVERY_PENDING.name, state.irOwnership)
        assertEquals(IntegrationOutcome.Restored, IndentRainbowSync.restoreOwnedState())
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `transient Indent Rainbow resolution failure preserves owned recovery`() {
        state.irOwnership = IntegrationOwnership.OWNED.name
        state.irBaseType = FakePaletteType.RAINBOW.name
        state.irBasePalette = "user-palette"
        state.irBaseColorCount = 4
        state.irAppliedType = FakePaletteType.CUSTOM.name
        state.irAppliedPalette = "ayu-palette"
        state.irAppliedColorCount = 11
        setIrField("methodsResolved", true)
        setIrField("resolutionFailure", ClassNotFoundException("temporary"))

        val failed = IndentRainbowSync.restoreOwnedState()

        assertTrue(failed is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.OWNED.name, state.irOwnership)
        assertEquals(false, getPrivateField("methodsResolved"))
        assertEquals(null, getPrivateField<Throwable?>("resolutionFailure"))

        val harness = installIrHarness(mockApplication, FakePaletteType.CUSTOM, "ayu-palette", 11)
        assertEquals(IntegrationOutcome.Restored, IndentRainbowSync.restoreOwnedState())
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `failed first Indent Rainbow write recovers the original baseline before retry`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        harness.shouldFailNextCountAndRollbackPalette = true

        val failed = IndentRainbowSync.apply(AyuVariant.MIRAGE, TEST_ACCENT_HEX)

        assertTrue(failed is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.RECOVERY_PENDING.name, state.irOwnership)
        assertEquals(FakePaletteType.RAINBOW.name, state.irBaseType)
        assertEquals("user-palette", state.irBasePalette)
        assertEquals(4, state.irBaseColorCount)

        assertEquals(
            IntegrationOutcome.Applied,
            IndentRainbowSync.apply(AyuVariant.MIRAGE, "#AABBCC"),
        )
        assertEquals(IntegrationOutcome.Restored, IndentRainbowSync.restoreOwnedState())
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `manual Indent Rainbow edit prevents pending recovery from overwriting the palette`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        harness.shouldFailNextCountAndRollbackPalette = true
        assertTrue(
            IndentRainbowSync.apply(AyuVariant.MIRAGE, TEST_ACCENT_HEX) is IntegrationOutcome.Failed,
        )
        harness.type = FakePaletteType.DEFAULT
        harness.palette = "manual-palette"
        harness.colorCount = 1

        val outcome = IndentRainbowSync.restoreOwnedState()

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.irOwnership)
        assertEquals(FakePaletteType.DEFAULT, harness.type)
        assertEquals("manual-palette", harness.palette)
        assertEquals(1, harness.colorCount)
    }

    @Test
    fun `missing recovery enum keeps Indent Rainbow recovery retryable`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        harness.shouldFailNextCountAndRollbackPalette = true
        assertTrue(
            IndentRainbowSync.apply(AyuVariant.MIRAGE, TEST_ACCENT_HEX) is IntegrationOutcome.Failed,
        )
        setIrField(
            "paletteEnumValues",
            FakePaletteType.entries.filterNot { it == FakePaletteType.RAINBOW }.associateBy { it.name },
        )

        assertTrue(IndentRainbowSync.restoreOwnedState() is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.RECOVERY_PENDING.name, state.irOwnership)

        setIrField("paletteEnumValues", FakePaletteType.entries.associateBy { it.name })
        assertEquals(IntegrationOutcome.Restored, IndentRainbowSync.restoreOwnedState())
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `missing Indent Rainbow baseline never forces DEFAULT`() {
        val harness =
            installIrHarness(
                mockApplication,
                type = FakePaletteType.CUSTOM,
                palette = "ayu-palette",
                colorCount = 11,
            )
        state.irOwnership = IntegrationOwnership.OWNED.name
        state.irAppliedType = FakePaletteType.CUSTOM.name
        state.irAppliedPalette = "ayu-palette"
        state.irAppliedColorCount = 11

        val outcome = IndentRainbowSync.restoreOwnedState()

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.irOwnership)
        assertEquals(FakePaletteType.CUSTOM, harness.type)
        assertEquals("ayu-palette", harness.palette)
        assertEquals(11, harness.colorCount)
    }

    @Test
    fun `apply external context writes external palette to mock IR fields`() {
        state.irIntegrationEnabled = true
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeIndentRainbowEnabled = true
        state.indentPresetName = "AMBIENT"

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApplyExternal()

        verify { mockPaletteTypeField[mockConfig] = "CUSTOM_ENUM" }
        verify {
            mockCustomPaletteField[mockConfig] =
                match<String> { palette ->
                    palette.split(", ").size == 11 &&
                        palette.contains("F27983") &&
                        palette.contains("AABBCC")
                }
        }
        verify { mockNumberColorsField.setInt(mockConfig, 11) }
        verify { mockUpdateMethod.invoke(mockCompanion, mockConfig) }
        verify { mockRefreshMethod.invoke(mockColorsInstance) }
    }

    @Test
    fun `apply external context restores owned state when inheritance is disabled`() {
        state.irIntegrationEnabled = true
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeIndentRainbowEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        assertEquals(IntegrationOutcome.Applied, callApplyExternal())
        state.externalThemeIndentRainbowEnabled = false

        val outcome = callApplyExternal()

        assertEquals(IntegrationOutcome.Restored, outcome)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `revert leaves unowned Indent Rainbow state unchanged`() {
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)

        val outcome = IndentRainbowSync.revert()

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `revert restores every captured Indent Rainbow field`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        assertEquals(IntegrationOutcome.Applied, callApply())

        val outcome = IndentRainbowSync.revert()

        assertEquals(IntegrationOutcome.Restored, outcome)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `apply with disabled integration restores captured state`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        assertEquals(IntegrationOutcome.Applied, callApply())
        state.irIntegrationEnabled = false

        val outcome = callApply()

        assertEquals(IntegrationOutcome.Restored, outcome)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `apply without a license restores captured state without changing the setting`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(mockApplication, FakePaletteType.RAINBOW, "user-palette", 4)
        assertEquals(IntegrationOutcome.Applied, callApply())
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val outcome = callApply()

        assertEquals(IntegrationOutcome.Restored, outcome)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
        assertTrue(state.irIntegrationEnabled)
    }

    @Test
    fun `apply handles InvocationTargetException from flushCache`() {
        state.irIntegrationEnabled = true

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockk<Method>(relaxed = true)
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        every { mockUpdateMethod.invoke(any(), any()) } throws
            java.lang.reflect.InvocationTargetException(RuntimeException("inner"))

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()
        // Should not throw — exception is caught and logged
    }

    @Test
    fun `apply handles ReflectiveOperationException from flushCache`() {
        state.irIntegrationEnabled = true

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockk<Method>(relaxed = true)
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        every { mockUpdateMethod.invoke(any(), any()) } throws IllegalAccessException("denied")

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()
    }

    @Test
    fun `revert handles exception from flushCache`() {
        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockUpdateMethod = mockk<Method>(relaxed = true)
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        every { mockUpdateMethod.invoke(any(), any()) } throws RuntimeException("flush failed")

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        IndentRainbowSync.revert()
        // Should not throw
    }

    @Test
    fun `logResolutionWarning does not throw`() {
        invokePrivate("logResolutionWarning", IllegalArgumentException("test message"))
    }

    @Test
    fun `logResolutionWarning handles exception with null message`() {
        invokePrivate("logResolutionWarning", IllegalArgumentException())
    }

    @Test
    fun `apply uses correct preset alpha`() {
        state.irIntegrationEnabled = true
        state.indentPresetName = "NEON"

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()

        // Verify the palette string was written (contains NEON alpha-based values)
        verify {
            mockCustomPaletteField[mockConfig] =
                match<String> { palette ->
                    // NEON alpha = 0x4D, 11 colors joined with ", "
                    palette.split(", ").size == 11
                }
        }
    }

    @Test
    fun `apply falls back to custom alpha when preset is CUSTOM`() {
        state.irIntegrationEnabled = true
        state.indentPresetName = "CUSTOM"
        state.indentCustomAlpha = 0x50

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply(AyuVariant.DARK)

        verify { mockCustomPaletteField[mockConfig] = any<String>() }
    }

    @Test
    fun `IR_PLUGIN_ID constant has correct value`() {
        val pluginId = getPrivateField<String>("IR_PLUGIN_ID")
        assertEquals("indent-rainbow.indent-rainbow", pluginId)
    }

    @Test
    fun `revert exits when defaultEnumValue is null`() {
        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("defaultEnumValue", null)
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        IndentRainbowSync.revert()

        // flushCache should NOT be called since the method exits early
        verify(exactly = 0) { mockUpdateMethod.invoke(any(), any()) }
    }

    @Test
    fun `apply exits when customEnumValue is null`() {
        state.irIntegrationEnabled = true

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", null)
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()

        verify(exactly = 0) { mockUpdateMethod.invoke(any(), any()) }
    }

    @Test
    fun `apply keeps ownership retryable when Indent Rainbow schema is unavailable`() {
        state.irIntegrationEnabled = true
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        val schemaUnavailableLoader =
            object : ClassLoader(this::class.java.classLoader) {
                override fun loadClass(
                    name: String,
                    resolve: Boolean,
                ): Class<*> {
                    if (name.startsWith("indent.rainbow.")) throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
        every { AyuPlugin.findLoadedPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns schemaUnavailableLoader

        val outcome = callApply()

        assertIs<ClassNotFoundException>(assertIs<IntegrationOutcome.Failed>(outcome).error)
        assertEquals(IntegrationOwnership.UNOWNED.name, state.irOwnership)
        assertEquals(false, getPrivateField("methodsResolved"))
        assertNull(getPrivateField("irConfig"))
        assertNull(getPrivateField("resolutionFailure"))
    }

    @Test
    fun `apply returns early when customPaletteField is null`() {
        state.irIntegrationEnabled = true

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", null)
        setIrField("customPaletteNumberColorsField", mockIntField())
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()

        verify(exactly = 0) { mockUpdateMethod.invoke(any(), any()) }
    }

    @Test
    fun `apply returns early when customPaletteNumberColorsField is null`() {
        state.irIntegrationEnabled = true

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", null)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()

        verify(exactly = 0) { mockUpdateMethod.invoke(any(), any()) }
    }

    @Test
    fun `apply catches RuntimeException from paletteTypeField set`() {
        state.irIntegrationEnabled = true

        val mockConfig = Any()
        val mockPaletteTypeField = mockk<Field>(relaxed = true)
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        every { mockPaletteTypeField[any()] = any() } throws RuntimeException("field set exploded")

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()
        // Should not throw — caught by RuntimeException handler
    }

    @Test
    fun `apply uses accent error color when irErrorHighlightEnabled is false`() {
        state.irIntegrationEnabled = true
        state.irErrorHighlightEnabled = false
        state.indentPresetName = "AMBIENT"

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockCustomPaletteField = mockField()
        val mockNumberColorsField = mockIntField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("customPaletteField", mockCustomPaletteField)
        setIrField("customPaletteNumberColorsField", mockNumberColorsField)
        setIrField("customEnumValue", "CUSTOM_ENUM")
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        callApply()

        // Verify palette string: error color (index 0) should use accent, not red
        verify {
            mockCustomPaletteField[mockConfig] =
                match<String> { palette ->
                    val colors = palette.split(", ")
                    // Error color (first) should use accent hex (FFCC66), not red (F27983)
                    colors[0].endsWith("FFCC66") && !colors[0].endsWith("F27983")
                }
        }
    }

    @Test
    fun `revert catches ReflectiveOperationException from paletteTypeField set`() {
        val mockConfig = Any()
        val mockPaletteTypeField = mockk<Field>(relaxed = true)
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        every { mockPaletteTypeField[any()] = any() } throws IllegalAccessException("access denied")

        setIrField("methodsResolved", true)
        setIrField("irConfig", mockConfig)
        setIrField("paletteTypeField", mockPaletteTypeField)
        setIrField("defaultEnumValue", "DEFAULT_ENUM")
        setIrField("cachedDataUpdateMethod", mockUpdateMethod)
        setIrField("cachedDataCompanion", mockCompanion)
        setIrField("refreshMethod", mockRefreshMethod)
        setIrField("irColorsInstance", mockColorsInstance)

        IndentRainbowSync.revert()
        // Should not throw — caught by ReflectiveOperationException handler
    }

    // Helpers

    private fun invokePrivate(
        methodName: String,
        vararg args: Any,
    ) {
        val method = IndentRainbowSync::class.java.declaredMethods.first { it.name == methodName }
        method.isAccessible = true
        method.invoke(IndentRainbowSync, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(fieldName: String): T {
        val field = IndentRainbowSync::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(IndentRainbowSync) as T
    }

    private fun mockField(): Field =
        mockk<Field>(relaxed = true).also { field ->
            every { field.get(any()) } returns "DEFAULT_ENUM"
        }

    private fun mockIntField(): Field {
        val field = mockk<Field>(relaxed = true)
        every { field.setInt(any(), any()) } returns Unit
        return field
    }

    private fun mockMethod(): Method = mockk<Method>(relaxed = true)
}
