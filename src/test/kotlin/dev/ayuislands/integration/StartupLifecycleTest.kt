package dev.ayuislands.integration

import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.AyuIslandsStartupActivity
import dev.ayuislands.AyuLaf
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.licensing.ReconciliationResult
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import dev.ayuislands.theme.AyuEditorSchemeBinder
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.AWTEventListenerProxy
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.UIDefaults
import javax.swing.UIManager

class StartupLifecycleTest : BasePlatformTestCase() {
    private lateinit var settings: AyuIslandsSettings
    private lateinit var originalXml: String
    private lateinit var mappings: AccentMappingsSettings
    private lateinit var originalMappingsXml: String
    private lateinit var originalUiDefaults: Map<Any, Any>
    private lateinit var originalLafDefaults: Map<Any, Any>
    private lateinit var originalProperties: Map<String, String?>
    private lateinit var originalGlobalScheme: EditorColorsScheme
    private lateinit var swapServiceLifetime: Disposable
    private val awtSentinel = AWTEventListener {}
    private val uiSentinelKey = Any()
    private val uiSentinelValue = Any()
    private var hasAppliedAccent = false

    override fun setUp() {
        super.setUp()
        settings = AyuIslandsSettings.getInstance()
        originalXml = encodedState(settings.state)
        mappings = AccentMappingsSettings.getInstance()
        originalMappingsXml = encodedMappings(mappings.state)
        swapServiceLifetime = Disposer.newDisposable("StartupLifecycleTest.swapService")
        ApplicationManager
            .getApplication()
            .replaceService(ProjectAccentSwapService::class.java, ProjectAccentSwapService(), swapServiceLifetime)
        Toolkit.getDefaultToolkit().addAWTEventListener(awtSentinel, AWTEvent.WINDOW_EVENT_MASK)
        UIManager.put(uiSentinelKey, uiSentinelValue)
        originalUiDefaults = ownDefaults(UIManager.getDefaults())
        originalLafDefaults = ownDefaults(UIManager.getLookAndFeelDefaults())
        val properties = PropertiesComponent.getInstance()
        originalProperties = PROPERTY_KEYS.associateWith(properties::getValue)
        val schemeManager = EditorColorsManager.getInstance()
        originalGlobalScheme = schemeManager.globalScheme
        assertFalse(AyuEditorSchemeBinder.isAyuScheme(originalGlobalScheme.name))
        assertTrue(schemeManager.allSchemes.none { AyuEditorSchemeBinder.isAyuScheme(it.name) })
    }

    override fun tearDown() {
        try {
            restoreFixtureState()
        } finally {
            super.tearDown()
        }
    }

    fun testExternalStartupWithEnhancementsDisabledPreservesSettings() {
        assertNull("The fixture must start on an external look and feel", AyuVariant.detect())
        settings.state.externalThemeEnhancementsEnabled = false
        settings.state.glowEnabled = false
        settings.state.fontPresetName = "FUTURE_PRESET"
        settings.state.fontPresetCustomizations["FUTURE_PRESET"] = "opaque|future|customization"
        val expectedXml = encodedState(settings.state)
        val expectedAccent = UIManager.get(ACCENT_KEY)

        runStartup()

        assertEquals(expectedXml, encodedState(settings.state))
        assertSame(expectedAccent, UIManager.get(ACCENT_KEY))
    }

    fun testExternalStartupWithEnhancementsEnabledAppliesStoredAccentWithoutChangingSettings() {
        assertNull("The fixture must start on an external look and feel", AyuVariant.detect())
        val accent = "#3A7BD5"
        settings.state.externalThemeEnhancementsEnabled = true
        settings.state.externalThemeAccentSource = "MANUAL"
        settings.state.externalThemeAccent = accent
        settings.state.externalThemeGlowEnabled = false
        settings.state.followSystemAppearance = false
        settings.state.lastLightAppearanceTheme = "Ayu Light (Islands UI)"
        settings.state.lastDarkAppearanceTheme = "Ayu Mirage (Islands UI)"
        settings.state.isCgpOwnershipMigrated = true
        settings.state.isIrOwnershipMigrated = true
        settings.state.lastAppliedAccentHex = accent
        settings.state.lastApplyOk = true
        val expectedXml = encodedState(settings.state)

        hasAppliedAccent = true
        runStartup()

        assertEquals(Color.decode(accent), UIManager.getColor(ACCENT_KEY))
        assertEquals(expectedXml, encodedState(settings.state))
    }

    fun testAyuStartupPreservesUnknownFontAcrossToggleReloads() {
        mockkObject(AyuLaf)
        every { AyuLaf.currentThemeName() } returns "Ayu Mirage (Islands UI)"
        settings.state.apply {
            fontPresetEnabled = true
            fontPresetName = "FUTURE_PRESET"
            fontPresetCustomizations["FUTURE_PRESET"] = "opaque|future|customization"
            fontOwnershipVersion = 99
            installedFontsSeeded = true
            premiumOnboardingShown = true
            freeOnboardingShown = true
            followSystemAppearance = false
            glowEnabled = false
            vcsColorEnabled = false
            isCgpOwnershipMigrated = true
            isIrOwnershipMigrated = true
            mirageAccent = "#5B8DEF"
            lastAppliedAccentHex = "#5B8DEF"
            lastApplyOk = true
        }

        try {
            assertStartupPreservesState()
            settings.state.fontPresetEnabled = false
            settings.loadState(decodedState(encodedState(settings.state)))
            assertStartupPreservesState()
            settings.state.fontPresetEnabled = true
            settings.loadState(decodedState(encodedState(settings.state)))
            assertStartupPreservesState()
        } finally {
            unmockkObject(AyuLaf)
        }

        assertEquals(Color.decode("#5B8DEF"), UIManager.getColor(ACCENT_KEY))
        assertEquals("FUTURE_PRESET", settings.state.fontPresetName)
        assertEquals("opaque|future|customization", settings.state.fontPresetCustomizations["FUTURE_PRESET"])
    }

    fun testAyuStartupRecoversAfterNativeProjectLookupFailure() {
        val accent = "#7A5AF8"
        val loggedErrors = mutableListOf<Pair<String, Throwable?>>()
        mockkObject(AyuLaf)
        every { AyuLaf.currentThemeName() } returns "Ayu Mirage (Islands UI)"
        settings.state.apply {
            fontPresetEnabled = false
            fontPresetName = "GLOW_WRITER"
            fontOwnershipVersion = 99
            installedFontsSeeded = true
            premiumOnboardingShown = true
            freeOnboardingShown = true
            followSystemAppearance = false
            glowEnabled = false
            vcsColorEnabled = false
            isCgpOwnershipMigrated = true
            isIrOwnershipMigrated = true
            mirageAccent = accent
        }
        hasAppliedAccent = true

        try {
            mockkStatic(ProjectUtil::class)
            every { ProjectUtil.getActiveProject() } throws IllegalStateException("native project lookup failed")
            try {
                LoggedErrorProcessor.executeWith<RuntimeException>(capturingProcessor(loggedErrors)) {
                    runStartup(createStartup())
                }
            } finally {
                unmockkStatic(ProjectUtil::class)
            }

            assertEquals("WHISPER", settings.state.fontPresetName)
            assertEquals(1, loggedErrors.size)
            val (message, error) = loggedErrors.single()
            assertTrue(message.startsWith("Startup accent apply failed"))
            assertEquals("native project lookup failed", error?.message)

            runStartup(createStartup())

            assertEquals(Color.decode(accent), UIManager.getColor(ACCENT_KEY))
            assertTrue(settings.state.lastApplyOk)
        } finally {
            unmockkObject(AyuLaf)
        }
    }

    private fun assertStartupPreservesState() {
        val expectedXml = encodedState(settings.state)
        hasAppliedAccent = true
        runStartup(createStartup())
        assertEquals(expectedXml, encodedState(settings.state))
    }

    private fun createStartup() =
        AyuIslandsStartupActivity(
            entitlementProvider = { LicenseEntitlement.UNKNOWN },
            reconcile = { _, _ -> ReconciliationResult.Success },
            scheduleRecheck = { _, _ -> },
            recheckDelayProvider = { null },
            projectsProvider = { listOf(project) },
        )

    private fun runStartup(activity: AyuIslandsStartupActivity = AyuIslandsStartupActivity()) {
        PlatformTestUtil.callOnBgtSynchronously(
            { runBlocking { activity.execute(project) } },
            30,
        )
        drainStartupCallbacks()
    }

    private fun drainStartupCallbacks() {
        val markerReached = AtomicBoolean()
        ApplicationManager.getApplication().invokeLater { markerReached.set(true) }
        PlatformTestUtil.waitWithEventsDispatching(
            "Startup callbacks did not complete",
            markerReached::get,
            10,
        )
    }

    private fun encodedState(state: AyuIslandsState): String = JDOMUtil.writeElement(XmlSerializer.serialize(state))

    private fun decodedState(xml: String): AyuIslandsState =
        XmlSerializer.deserialize(JDOMUtil.load(xml), AyuIslandsState::class.java)

    private fun encodedMappings(state: AccentMappingsState): String =
        JDOMUtil.writeElement(XmlSerializer.serialize(state))

    private fun decodedMappings(xml: String): AccentMappingsState =
        XmlSerializer.deserialize(JDOMUtil.load(xml), AccentMappingsState::class.java)

    private fun restoreFixtureState() {
        var firstFailure: Throwable? = null
        val restorationSteps =
            listOf<() -> Unit>(
                { if (hasAppliedAccent) AccentApplicator.revertAll() },
                { settings.loadState(decodedState(originalXml)) },
                { mappings.loadState(decodedMappings(originalMappingsXml)) },
                ::restoreProperties,
                ::restoreUiDefaults,
                ::verifyUiState,
                { UIManager.put(uiSentinelKey, null) },
                ::verifyEditorSchemesUnchanged,
                { Disposer.dispose(swapServiceLifetime) },
                ::verifyAwtSentinel,
                { Toolkit.getDefaultToolkit().removeAWTEventListener(awtSentinel) },
            )
        for (restore in restorationSteps) {
            val failure = runCatching(restore).exceptionOrNull()
            if (failure != null) {
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun restoreProperties() {
        val properties = PropertiesComponent.getInstance()
        for ((key, originalValue) in originalProperties) {
            if (originalValue == null) {
                properties.unsetValue(key)
            } else {
                properties.setValue(key, originalValue)
            }
        }
    }

    private fun restoreUiDefaults() {
        val defaults = UIManager.getDefaults()
        val currentDefaults = ownDefaults(defaults)
        for (key in currentDefaults.keys - originalUiDefaults.keys) {
            defaults[key] = null
        }
        originalUiDefaults.forEach(defaults::put)
    }

    private fun verifyUiState() {
        val defaults = UIManager.getDefaults()
        assertSame(uiSentinelValue, defaults[uiSentinelKey])
        assertEquals(originalLafDefaults, ownDefaults(UIManager.getLookAndFeelDefaults()))
    }

    private fun verifyAwtSentinel() {
        val toolkit = Toolkit.getDefaultToolkit()
        val listeners =
            toolkit
                .getAWTEventListeners(AWTEvent.WINDOW_EVENT_MASK)
                .map { listener ->
                    if (listener is AWTEventListenerProxy) listener.listener else listener
                }
        assertTrue(listeners.any { listener -> listener === awtSentinel })
    }

    private fun verifyEditorSchemesUnchanged() {
        val schemeManager = EditorColorsManager.getInstance()
        assertSame(originalGlobalScheme, schemeManager.globalScheme)
        assertTrue(schemeManager.allSchemes.none { AyuEditorSchemeBinder.isAyuScheme(it.name) })
    }

    private fun ownDefaults(defaults: UIDefaults): Map<Any, Any> {
        val snapshot = LinkedHashMap<Any, Any>()
        defaults.forEach { key, value -> snapshot[key] = value }
        return snapshot
    }

    private fun capturingProcessor(captured: MutableList<Pair<String, Throwable?>>) =
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                throwable: Throwable?,
            ): Set<Action> {
                captured += message to throwable
                return EnumSet.noneOf(Action::class.java)
            }
        }

    private companion object {
        const val ACCENT_KEY = "Component.focusedBorderColor"
        val PROPERTY_KEYS =
            setOf(
                "ayu.syntax.intensity.notified",
                "ayu.syntax.visibility.retired.schemes",
            )
    }
}
