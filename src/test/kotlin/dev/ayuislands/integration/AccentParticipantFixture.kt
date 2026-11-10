package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.project.Project
import com.nasller.codeglance.config.CodeGlanceConfig
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentUiKeys
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.CgpAccess
import dev.ayuislands.accent.CgpPart
import dev.ayuislands.accent.CgpViewport
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromeCacheSlot
import dev.ayuislands.accent.ExternalChromeOwnership
import dev.ayuislands.accent.elements.CaretRowElement
import dev.ayuislands.accent.elements.NavBarElement
import dev.ayuislands.accent.elements.ProgressBarElement
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.accent.uiDefaultsOwner
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.runtime.FocusSnapshot
import dev.ayuislands.studio.session.StudioAccentHost
import dev.ayuislands.studio.session.StudioAccentParticipant
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioGlowHolds
import dev.ayuislands.studio.session.StudioIntegrationCapture
import dev.ayuislands.studio.session.StudioNativeFacts
import dev.ayuislands.studio.session.StudioNativeFocus
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioThemeTransition
import dev.ayuislands.theme.EditorSchemeOverrides
import java.awt.Color
import java.time.Instant
import javax.swing.UIManager
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real SDK schemes, Swing defaults and optional-plugin config; only discovery and refresh are test boundaries. */
internal class AccentParticipantFixture : AutoCloseable {
    private val progress = ProgressBarElement()
    private val chromeKeys =
        listOf("NavBar.background", "NavBar.borderColor", "NavBar.foreground", "NavBar.selectedItemForeground")
    private val defaults =
        buildSet {
            addAll(AccentUiKeys.colors)
            add(AccentUiKeys.TAB_HEIGHT)
            add(AccentUiKeys.TAB_ARC)
            add("Island.arc")
            addAll(progress.uiKeys)
            addAll(chromeKeys)
        }
    private val previousDefaults = rawDefaults()
    private val cacheCells = chromeKeys.take(2).flatMap { key -> ChromeCacheSlot.entries.map { it to key } }
    private val previousCache =
        cacheCells.associateWith { (slot, key) ->
            ChromeBaseColors.prepare(slot, key, null).before
        }
    val scheme: EditorColorsSchemeImpl =
        participantScheme(
            assertIs<EditorColorsSchemeImpl>(
                EditorColorsManager.getInstance().getScheme(AyuVariant.MIRAGE.editorSchemeResource),
            ),
        )
    private val schemeHost = ParticipantSchemeHost(listOf(scheme))
    val input =
        participantInput(
            AyuIslandsState().apply {
                mirageAccent = "#FFCC66"
                chromeTintingEnabled = true
                chromeNavBar = true
                chromeTintIntensity = 30
                cgpIntegrationEnabled = true
                isCgpOwnershipMigrated = true
                forceOverrides =
                    linkedSetOf(
                        AccentElementId.NAV_BAR.name,
                        AccentElementId.PROGRESS_BAR.name,
                        AccentElementId.CARET_ROW.name,
                    )
            },
        )
    val codeGlance =
        CodeGlanceConfig().apply {
            viewportColor = "123456"
            viewportBorderColor = "654321"
            viewportBorderThickness = 3
            disabled = true
            mainWidth = 137
        }
    var integrationWrites = 0
        private set
    var beforeRefresh: () -> Unit = {}
    var refreshFailure: IllegalStateException? = null
    var refreshes = 0
        private set
    val runtime = AccentParticipantRuntime(scheme) { refreshes > 0 }
    private val host =
        object : StudioAccentHost {
            override val schemes = schemeHost

            override fun elements(): List<AccentElement> = listOf(progress, CaretRowElement(), NavBarElement())

            override fun systemAccent(): String? = null

            override fun refresh(
                projects: List<Project>,
                repaintWindows: Boolean,
            ) {
                assertTrue(projects.isEmpty())
                beforeRefresh()
                refreshFailure?.let { failure ->
                    refreshFailure = null
                    throw failure
                }
                refreshes++
            }
        }
    private val integrations =
        StudioIntegrationCapture(
            {
                CgpAccess(
                    read = {
                        CgpViewport(
                            codeGlance.viewportColor,
                            codeGlance.viewportBorderColor,
                            codeGlance.viewportBorderThickness,
                        )
                    },
                    write = { part, target ->
                        integrationWrites++
                        when (part) {
                            CgpPart.COLOR -> codeGlance.viewportColor = target.color
                            CgpPart.BORDER -> codeGlance.viewportBorderColor = target.border
                            CgpPart.THICKNESS -> codeGlance.viewportBorderThickness = target.thickness
                        }
                    },
                    targetMatches = { true },
                )
            },
            null,
            EmptyCoroutineContext,
        )
    val participant = StudioAccentParticipant(host, integrations, EmptyCoroutineContext)

    init {
        ExternalChromeOwnership.resetForTests()
        cacheCells.forEach { (slot, key) -> setCache(slot, key, null) }
        defaults.forEach { UIManager.put(it, Color.BLUE) }
        UIManager.put(AccentUiKeys.TAB_HEIGHT, 7)
        UIManager.put(AccentUiKeys.TAB_ARC, 3)
        UIManager.put("Island.arc", 16)
        scheme.setColor(ColorKey.find("BUTTON_BACKGROUND"), Color.BLUE)
        scheme.setColor(ColorKey.find("CARET_COLOR"), Color.BLUE)
        scheme.setColor(ColorKey.find("PROGRESS_BAR_TRACK"), Color.BLUE)
    }

    fun rawDefaults(): Map<String, Any?> = defaults.associateWith { readRawUiDefault(UIManager.getDefaults(), it) }

    fun assertOriginal() {
        for (key in defaults - setOf(AccentUiKeys.TAB_HEIGHT, AccentUiKeys.TAB_ARC, "Island.arc")) {
            assertSame(Color.BLUE, readRawUiDefault(UIManager.getDefaults(), key), key)
        }
        assertEquals(7, UIManager.getInt(AccentUiKeys.TAB_HEIGHT))
        assertEquals(3, UIManager.getInt(AccentUiKeys.TAB_ARC))
        assertEquals("123456", codeGlance.viewportColor)
        assertEquals("654321", codeGlance.viewportBorderColor)
        assertEquals(3, codeGlance.viewportBorderThickness)
        assertTrue(codeGlance.disabled)
        assertEquals(137, codeGlance.mainWidth)
    }

    fun assertApplied() {
        val accent = Color(255, 204, 102).rgb
        assertEquals(accent, UIManager.getColor("Button.default.startBackground").rgb)
        assertEquals(accent, UIManager.getColor("ProgressBar.foreground").rgb)
        assertNotEquals(Color.BLUE.rgb, UIManager.getColor("NavBar.background").rgb)
        assertEquals(accent, scheme.getColor(ColorKey.find("BUTTON_BACKGROUND"))?.rgb)
        assertEquals(accent, scheme.getColor(ColorKey.find("CARET_COLOR"))?.rgb)
        assertEquals(accent, scheme.getColor(ColorKey.find("PROGRESS_BAR_TRACK"))?.rgb)
        assertEquals("FFCC66", codeGlance.viewportColor.uppercase())
        assertEquals("FFCC66", codeGlance.viewportBorderColor.uppercase())
        assertEquals(1, codeGlance.viewportBorderThickness)
        assertTrue(codeGlance.disabled)
        assertEquals(137, codeGlance.mainWidth)
    }

    override fun close() {
        uiDefaultsOwner.restore()
        EditorSchemeOverrides.reset()
        ExternalChromeOwnership.resetForTests()
        previousDefaults.forEach { (key, value) -> UIManager.put(key, value) }
        previousCache.forEach { (cell, value) -> setCache(cell.first, cell.second, value) }
    }

    private fun setCache(
        slot: ChromeCacheSlot,
        key: String,
        value: Color?,
    ) {
        val checkpoint = ChromeBaseColors.prepare(slot, key, value)
        assertTrue(checkpoint.apply())
        checkpoint.commit()
    }
}

internal class AccentParticipantRuntime(
    scheme: EditorColorsSchemeImpl,
    private val hasRefreshed: () -> Boolean,
) : StudioEffectRuntime {
    private val process = StudioProcess(191, Instant.EPOCH)
    override val glow: StudioGlowHolds? = null
    override var accent: StudioAccentResult? = null
        private set
    override val native = StudioNativeFacts(true, process)
    override val observation =
        StudioNativeObservation(
            UIManager.getLookAndFeel(),
            scheme,
            StudioNativeFocus(FocusSnapshot(StudioTarget.Global, 0), null),
            LicenseEntitlement.LICENSED,
            AyuVariant.MIRAGE,
            null,
            StudioList(emptyList()),
            process,
        )
    var confirmations = 0
        private set

    override suspend fun confirmAccent(result: StudioAccentResult) {
        assertTrue(hasRefreshed(), "The shared native result must not precede the last successful refresh")
        confirmations++
        accent = result
    }

    override suspend fun confirmTheme(transition: StudioThemeTransition): Unit =
        error("The Accent owner cannot switch native themes")
}
