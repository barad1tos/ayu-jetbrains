package dev.ayuislands.indent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AyuVariant
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
    private val state = AyuIslandsState()
    private val mockApplication = mockk<com.intellij.openapi.application.Application>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state
        // getAccentForVariant stub used to be required because IR read the global accent
        // itself. After the resolver refactor the caller passes the resolved hex in; the
        // mock becomes dead code. Removed to keep setUp honest.

        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any()) } returns null

        resetSyncState()
    }

    /**
     * Helper that invokes [IndentRainbowSync.apply] with the single test accent hex.
     * Most tests in this suite verify flow (integration flag, plugin availability,
     * reflection fallbacks, palette composition) rather than hex-specific behavior —
     * pulling the hex into one constant removes 20 hardcoded strings and keeps each
     * test readable as "call apply for variant X".
     */
    private fun callApply(variant: AyuVariant = AyuVariant.MIRAGE) {
        IndentRainbowSync.apply(variant, TEST_ACCENT_HEX)
    }

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
        every { PluginManagerCore.getPlugin(any()) } returns mockPlugin
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
    fun `revert writes DEFAULT enum to palette type field`() {
        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

        IndentRainbowSync.revert()

        verify { mockPaletteTypeField[mockConfig] = "DEFAULT_ENUM" }
        verify { mockUpdateMethod.invoke(mockCompanion, mockConfig) }
        verify { mockRefreshMethod.invoke(mockColorsInstance) }
    }

    @Test
    fun `revert does not clear customPalette`() {
        // V-IR-lock regression: revert resets `paletteType` to DEFAULT but MUST NOT
        // touch `customPalette`. Per CONTEXT.md §specifics, IR ignores
        // `customPalette` unless `paletteType == CUSTOM`, so leaving the stale Ayu
        // palette string in the field is the accepted-degradation path. This test
        // locks the decision — a future agent who "helpfully" clears customPalette
        // (thinking they're tidying up) must update CONTEXT first.
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
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

        IndentRainbowSync.revert()

        // paletteType MUST be reset to DEFAULT (existing expectation reinforced).
        verify { mockPaletteTypeField[mockConfig] = "DEFAULT_ENUM" }
        // customPalette MUST be untouched (the new V-IR-lock regression).
        verify(exactly = 0) { mockCustomPaletteField[any()] = any() }
    }

    @Test
    fun `apply with disabled integration reverts to DEFAULT`() {
        state.irIntegrationEnabled = false

        val mockConfig = Any()
        val mockPaletteTypeField = mockField()
        val mockUpdateMethod = mockMethod()
        val mockRefreshMethod = mockMethod()
        val mockCompanion = Any()
        val mockColorsInstance = Any()

        setPrivateField("methodsResolved", true)
        setPrivateField("irConfig", mockConfig)
        setPrivateField("paletteTypeField", mockPaletteTypeField)
        setPrivateField("defaultEnumValue", "DEFAULT_ENUM")
        setPrivateField("cachedDataUpdateMethod", mockUpdateMethod)
        setPrivateField("cachedDataCompanion", mockCompanion)
        setPrivateField("refreshMethod", mockRefreshMethod)
        setPrivateField("irColorsInstance", mockColorsInstance)

        callApply()

        // Should revert, not apply custom
        verify { mockPaletteTypeField[mockConfig] = "DEFAULT_ENUM" }
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

        mockNotificationGroup()

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

        mockNotificationGroup()

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
    fun `notifyFailure suppresses duplicate for same version`() {
        state.irFailedVersion = "old-version"

        val group = mockNotificationGroup()

        // First call — should notify
        invokePrivate("notifyFailure")

        // irFailedVersion is now set to null (plugin version)
        // Second call — same version, should skip
        invokePrivate("notifyFailure")

        // createNotification should be called only once
        verify(exactly = 1) {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    @Test
    fun `notifyFailure catches RuntimeException from notification system`() {
        state.irFailedVersion = "different-version"

        mockkStatic(NotificationGroupManager::class)
        every { NotificationGroupManager.getInstance() } throws RuntimeException("no group")

        invokePrivate("notifyFailure")
        // Should not throw — caught internally
    }

    @Test
    fun `logWarning does not throw`() {
        invokePrivate("logWarning", "test action", RuntimeException("test message"))
    }

    @Test
    fun `logWarning handles exception with null message`() {
        invokePrivate("logWarning", "test action", RuntimeException())
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
    fun `resolveReflection catches ClassNotFoundException when plugin found`() {
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { PluginManagerCore.getPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns this::class.java.classLoader

        // Class.forName("indent.rainbow.settings.IrConfig") will throw
        // ClassNotFoundException (a ReflectiveOperationException) since the
        // class does not exist on the test classpath.

        mockNotificationGroup()

        invokePrivate("resolveReflection")

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

        mockNotificationGroup()

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
                "cachedDataUpdateMethod",
                "cachedDataCompanion",
                "refreshMethod",
                "irColorsInstance",
            )
        for (fieldName in fields) {
            setPrivateField(fieldName, null)
        }
        setPrivateField("methodsResolved", false)
    }

    private fun mockField(): Field = mockk<Field>(relaxed = true)

    private fun mockIntField(): Field {
        val field = mockk<Field>(relaxed = true)
        every { field.setInt(any(), any()) } returns Unit
        return field
    }

    private fun mockMethod(): Method = mockk<Method>(relaxed = true)

    private fun mockNotificationGroup(): NotificationGroup {
        mockkStatic(NotificationGroupManager::class)
        val mockNotifManager = mockk<NotificationGroupManager>(relaxed = true)
        val mockGroup = mockk<NotificationGroup>(relaxed = true)
        val mockNotification = mockk<Notification>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns mockNotifManager
        every { mockNotifManager.getNotificationGroup(any()) } returns mockGroup
        every {
            mockGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns mockNotification
        return mockGroup
    }
}
