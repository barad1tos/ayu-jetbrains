package dev.ayuislands.indent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

        resetSyncState()
    }

    /**
     * Helper that invokes [IndentRainbowSync.apply] with the single test accent hex.
     * Most tests in this suite verify flow (integration flag, plugin availability,
     * reflection fallbacks, palette composition) rather than hex-specific behavior —
     * pulling the hex into one constant removes 20 hardcoded strings and keeps each
     * test readable as "call apply for variant X".
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
        unmockkAll()
        clearAllMocks()
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
        setPrivateField("methodsResolved", true)
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
            resetSyncState()
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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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
        val harness = installIrHarness(FakePaletteType.CUSTOM, "ayu-palette", 11)

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
        val harness = installIrHarness(FakePaletteType.RAINBOW, "user-palette", 4)
        IndentRainbowSync.apply(AyuVariant.MIRAGE, TEST_ACCENT_HEX)
        val applied =
            IrPalette(
                type = harness.type.name,
                palette = harness.palette,
                colorCount = harness.colorCount,
            )
        val updateMethod = getPrivateField<Method>("cachedDataUpdateMethod")
        var shouldRejectNextUpdate = true
        every { updateMethod.invoke(any(), any()) } answers {
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
    fun `failed first Indent Rainbow write recovers the original baseline before retry`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(FakePaletteType.RAINBOW, "user-palette", 4)
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
    fun `missing Indent Rainbow baseline never forces DEFAULT`() {
        val harness =
            installIrHarness(
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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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
        val harness = installIrHarness(FakePaletteType.RAINBOW, "user-palette", 4)
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
        val harness = installIrHarness(FakePaletteType.RAINBOW, "user-palette", 4)

        val outcome = IndentRainbowSync.revert()

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(FakePaletteType.RAINBOW, harness.type)
        assertEquals("user-palette", harness.palette)
        assertEquals(4, harness.colorCount)
    }

    @Test
    fun `revert restores every captured Indent Rainbow field`() {
        state.irIntegrationEnabled = true
        val harness = installIrHarness(FakePaletteType.RAINBOW, "user-palette", 4)
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
        val harness = installIrHarness(FakePaletteType.RAINBOW, "user-palette", 4)
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
        val harness = installIrHarness(FakePaletteType.RAINBOW, "user-palette", 4)
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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("defaultEnumValue", null)
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", null)
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

        callApply()

        verify(exactly = 0) { mockUpdateMethod.invoke(any(), any()) }
    }

    @Test
    fun `apply suspends ownership when Indent Rainbow schema is unavailable`() {
        state.irIntegrationEnabled = true
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns this::class.java.classLoader

        val outcome = callApply()

        assertTrue(outcome is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.irOwnership)
        assertTrue(getPrivateField("methodsResolved"))
        assertNull(getPrivateField("irConfig"))
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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", null)
        setPrivateField("customPaletteNumberColorsField", mockIntField())
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", null)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("customPaletteField", mockCustomPaletteField)
        setPrivateField("customPaletteNumberColorsField", mockNumberColorsField)
        setPrivateField("customEnumValue", "CUSTOM_ENUM")
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

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

    private fun setPrivateField(
        fieldName: String,
        value: Any?,
    ) {
        val field = IndentRainbowSync::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(IndentRainbowSync, value)
    }

    private fun resetSyncState() {
        val fields =
            listOf(
                "irConfig",
                "paletteTypeField",
                "customPaletteField",
                "customPaletteNumberColorsField",
                "customEnumValue",
                "defaultEnumValue",
                "paletteEnumValues",
                "cachedDataUpdateMethod",
                "cachedDataCompanion",
                "refreshMethod",
                "irColorsInstance",
                "resolutionFailure",
            )
        for (fieldName in fields) {
            setPrivateField(fieldName, null)
        }
        setPrivateField("methodsResolved", false)
    }

    private fun installIrHarness(
        type: FakePaletteType,
        palette: String,
        colorCount: Int,
    ): IrHarness {
        val harness = IrHarness(type, palette, colorCount)
        val config = Any()
        val paletteType = mockField()
        val customPalette = mockField()
        val count = mockIntField()

        every { paletteType.get(config) } answers { harness.type }
        every { paletteType.set(config, any()) } answers {
            harness.type = secondArg()
        }
        every { customPalette.get(config) } answers { harness.palette }
        every { customPalette.set(config, any()) } answers {
            harness.palette = secondArg()
            if (harness.shouldFailNextPalette) {
                harness.shouldFailNextPalette = false
                error("palette write rejected after mutation")
            }
        }
        every { count.getInt(config) } answers { harness.colorCount }
        every { count.setInt(config, any()) } answers {
            harness.colorCount = secondArg()
            if (harness.shouldFailNextCountAndRollbackPalette) {
                harness.shouldFailNextCountAndRollbackPalette = false
                harness.shouldFailNextPalette = true
                error("color count write rejected after mutation")
            }
        }

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", config)
        setPrivateField("paletteTypeField", paletteType)
        setPrivateField("customPaletteField", customPalette)
        setPrivateField("customPaletteNumberColorsField", count)
        setPrivateField("customEnumValue", FakePaletteType.CUSTOM)
        setPrivateField("defaultEnumValue", FakePaletteType.DEFAULT)
        setPrivateField("paletteEnumValues", FakePaletteType.entries.associateBy { it.name })
        setPrivateField("cachedDataUpdateMethod", mockMethod())
        setPrivateField("cachedDataCompanion", Any())
        setPrivateField("refreshMethod", mockMethod())
        setPrivateField("irColorsInstance", Any())
        return harness
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

    private data class IrHarness(
        var type: FakePaletteType,
        var palette: String,
        var colorCount: Int,
        var shouldFailNextCountAndRollbackPalette: Boolean = false,
        var shouldFailNextPalette: Boolean = false,
    )

    private enum class FakePaletteType {
        DEFAULT,
        CUSTOM,
        RAINBOW,
    }
}
