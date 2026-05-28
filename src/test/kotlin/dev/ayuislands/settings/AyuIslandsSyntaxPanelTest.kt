package dev.ayuislands.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import java.awt.Color
import java.awt.Container
import java.awt.Font
import java.io.File
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.Timer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [AyuIslandsSyntaxPanel] - pill row + Custom premium gate.
 *
 * Coverage:
 *  - Default preset on null / unknown persisted name = `AMBIENT` (D-23).
 *  - Pill selection invokes [SyntaxIntensityService.apply] and persists
 *    `state.selectedPreset` (apply-on-click, no Apply button).
 *  - Apply-FIRST persist-SECOND ordering (Anti-Pattern #4 / Phase 40.4):
 *    a service throw leaves `state.selectedPreset` untouched.
 *  - Custom rejection for unlicensed users: `LicenseChecker.requestLicense`
 *    is invoked, pending stays at the previous preset, no service call,
 *    no state mutation.
 *  - Custom accepted for licensed users: pill selection routes through the
 *    same apply path as the four named pills.
 *  - `reset()` reverts the pending buffer to the stored value.
 *
 * Plain kotlin.test + MockK. The Kotlin UI DSL requires EDT-managed
 * `DialogPanel` lifecycle, so the tests exercise apply / reset / isModified
 * on freshly constructed panels and drive the pill selection through the
 * private `onPresetChosen` seam via reflection.
 *
 * **Test-design note (documented compromise):** a handful of [readPanelSource]
 * source-regex checks remain in this suite and guard real user-facing bugs
 * that have no cheap unit-level behavioral substitute. Each one catches a
 * concrete regression:
 *
 *  - Two-column grouped layout (`CUSTOM_COLUMN_GROUPS` + `buildCategoryGroup`):
 *    a refactor back to the single unbroken table or to the deleted master /
 *    detail JBList would be a visible regression. Verifying the actual two
 *    column-level panels requires running the UI DSL with the platform.
 *  - Tick-free slider cell (`paintTicks = false` / `paintLabels = false`):
 *    visible tick marks across 64 sliders would be the Direction B
 *    regression we shipped to avoid. The DSL `slider()` cell is also
 *    platform-bound — bare `JSlider(...)` would bypass UI-DSL theming.
 *  - Binary-compat spacing-configuration ban: matches the documented
 *    `gotcha_platform_interface_delegation_binary_compat` lesson — a `by`
 *    delegation onto the platform `SpacingConfiguration` interface compiled
 *    against 2025.1 throws `AbstractMethodError` on newer runtime IDEs and
 *    hangs the settings page on "Loading…". Unit tests against the embedded
 *    SDK cannot reproduce the runtime failure.
 *  - `InplaceButton`-only trailing reset slot: `ActionButton` re-introduces
 *    the `ActionToolbar.updateUI` `SlowOperations SEVERE` crash documented in
 *    the project's `feedback_no_threshold_or_ignore_changes` lesson. The
 *    fixed-slot `GridLayout(1, TRAILING_SLOT_COUNT, ...)` plus
 *    `TRAILING_SLOT_*` constants are DSL-build internals — building the panel
 *    under unit tests requires a full IntelliJ platform.
 *
 * Do not delete these source-regex assertions in future "remove theater"
 * cleanup passes without first wiring a working `integrationTest` task that
 * actually builds the panel under a live IntelliJ application.
 */
class AyuIslandsSyntaxPanelTest {
    private companion object {
        val readabilityCheckboxTexts =
            linkedSetOf(
                "Dim comments",
                "Soften documentation",
                "Quiet operators",
                "Emphasize declarations",
            )
    }

    private lateinit var stateBase: SyntaxIntensityBaseState
    private lateinit var stateService: SyntaxIntensityState
    private lateinit var intensityService: SyntaxIntensityService

    @BeforeTest
    fun setUp() {
        stateBase = SyntaxIntensityBaseState()
        stateService = mockk(relaxed = true)
        every { stateService.state } returns stateBase
        mockkObject(SyntaxIntensityState.Companion)
        every { SyntaxIntensityState.getInstance() } returns stateService

        intensityService = mockk(relaxed = true)
        mockkObject(SyntaxIntensityService.Companion)
        every { SyntaxIntensityService.getInstance() } returns intensityService

        mockkObject(LicenseChecker)
        // Default: licensed. Individual tests override to false where needed.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { LicenseChecker.requestLicense(any()) } returns Unit

        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManager>(relaxed = true)
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns actionManagerMock
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(experimentalUiClass) } returns experimentalUiMock
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ---------- Test 1 - initial state defaults to AMBIENT (D-23) ----------

    @Test
    fun `loadStateIntoPending defaults to AMBIENT when state selectedPreset is null`() {
        stateBase.selectedPreset = null
        val panel = panelWithLoadedState()
        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
        assertSame(SyntaxPreset.AMBIENT, readStoredPreset(panel))
    }

    @Test
    fun `loadStateIntoPending honors explicit AMBIENT default`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()
        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
    }

    @Test
    fun `loadStateIntoPending honors a non-default persisted preset name`() {
        stateBase.selectedPreset = "NEON"
        val panel = panelWithLoadedState()
        assertSame(SyntaxPreset.NEON, readPendingPreset(panel))
    }

    @Test
    fun `loadStateIntoPending normalizes unlicensed persisted Custom to Ambient`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        stateBase.selectedPreset = "CUSTOM"
        stateBase.subordinatePreset = "NEON"
        stateBase.customOverrides["Java|KEYWORD"] = "85"
        stateBase.customStyles["Java|KEYWORD"] = "BOLD"

        val panel = panelWithLoadedState()

        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
        assertSame(SyntaxPreset.AMBIENT, readStoredPreset(panel))
        assertTrue(readPendingOverrides(panel).isEmpty(), "unlicensed Custom load must hide slider overrides")
        assertTrue(readPendingStyles(panel).isEmpty(), "unlicensed Custom load must hide style overrides")
    }

    // ---------- Test 2 - pill selection applies + persists ----------

    @Test
    fun `pill selection invokes SyntaxIntensityService apply with empty overrides`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.NEON)

        verify(exactly = 1) { intensityService.apply(SyntaxPreset.NEON, emptyMap(), any(), emptyMap()) }
    }

    @Test
    fun `pill selection persists selectedPreset to state`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.WHISPER)

        assertEquals("WHISPER", stateBase.selectedPreset)
    }

    @Test
    fun `pill selection updates stored buffer so subsequent isModified returns false`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CYBERPUNK)

        assertFalse(panel.isModified(), "after pill click stored == pending so isModified is false")
    }

    // ---------- Test 3 - apply-FIRST persist-SECOND ordering ----------

    @Test
    fun `apply orders service call BEFORE state persistence (Anti-Pattern 4)`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.NEON)

        panel.apply()

        verifyOrder {
            intensityService.apply(SyntaxPreset.NEON, emptyMap(), any(), emptyMap())
            stateService.state
        }
    }

    @Test
    fun `apply ordering - service throw leaves state selectedPreset UNCHANGED`() {
        stateBase.selectedPreset = "AMBIENT"
        every {
            intensityService.apply(any(), any(), any(), any())
        } throws RuntimeException("simulated apply failure")
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.NEON)

        assertFailsWith<RuntimeException> { panel.apply() }

        assertEquals(
            "AMBIENT",
            stateBase.selectedPreset,
            "apply-FIRST persist-SECOND: a service throw must NOT mutate state.selectedPreset",
        )
    }

    // ---------- Test 4 - Custom rejection for unlicensed users ----------

    @Test
    fun `Custom pill rejected for unlicensed users - requestLicense fires, no service call, no persist`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CUSTOM)

        verify(exactly = 1) {
            LicenseChecker.requestLicense("Unlock per-language syntax customization")
        }
        verify(exactly = 0) { intensityService.apply(any(), any(), any(), any()) }
        assertEquals("AMBIENT", stateBase.selectedPreset)
        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
    }

    // ---------- Test 5 - Custom accepted for licensed users ----------

    @Test
    fun `Custom pill accepted for licensed users - apply with empty overrides + persist`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CUSTOM)

        verify(exactly = 1) { intensityService.apply(SyntaxPreset.CUSTOM, emptyMap(), any(), emptyMap()) }
        assertEquals("CUSTOM", stateBase.selectedPreset)
        verify(exactly = 0) { LicenseChecker.requestLicense(any()) }
    }

    // ---------- Test 6 - reset reverts pending to stored ----------

    @Test
    fun `reset reverts pendingPreset to storedPreset`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.NEON)
        assertTrue(panel.isModified())

        panel.reset()

        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
        assertFalse(panel.isModified())
    }

    @Test
    fun `loadStateIntoPending loads readability toggles from state`() {
        stateBase.dimComments = true
        stateBase.softenDocumentation = true
        stateBase.quietOperators = true
        stateBase.emphasizeDeclarations = true

        val panel = panelWithLoadedState()

        assertTrue(readPendingBoolean(panel, "pendingDimComments"))
        assertTrue(readPendingBoolean(panel, "pendingSoftenDocumentation"))
        assertTrue(readPendingBoolean(panel, "pendingQuietOperators"))
        assertTrue(readPendingBoolean(panel, "pendingEmphasizeDeclarations"))
        assertFalse(panel.isModified(), "freshly loaded readability state must not dirty the panel")
    }

    @Test
    fun `apply passes readability options before persisting them`() {
        stateBase.selectedPreset = "AMBIENT"
        stateBase.schemaVersion = 2
        val panel = panelWithLoadedState()
        writePendingBoolean(panel, "pendingDimComments", true)
        writePendingBoolean(panel, "pendingQuietOperators", true)

        panel.apply()

        verifyOrder {
            intensityService.apply(
                SyntaxPreset.AMBIENT,
                emptyMap(),
                any(),
                emptyMap(),
                SyntaxReadabilityOptions(dimComments = true, quietOperators = true),
            )
            stateService.state
        }
        assertTrue(stateBase.dimComments)
        assertTrue(stateBase.quietOperators)
        assertFalse(stateBase.softenDocumentation)
        assertFalse(stateBase.emphasizeDeclarations)
        assertEquals(3, stateBase.schemaVersion)
        assertFalse(panel.isModified(), "persisted readability toggles must become the stored buffer")
    }

    @Test
    fun `reset reverts pending readability toggles to stored values`() {
        stateBase.dimComments = true
        val panel = panelWithLoadedState()
        writePendingBoolean(panel, "pendingDimComments", false)
        writePendingBoolean(panel, "pendingEmphasizeDeclarations", true)
        assertTrue(panel.isModified())

        panel.reset()

        assertTrue(readPendingBoolean(panel, "pendingDimComments"))
        assertFalse(readPendingBoolean(panel, "pendingEmphasizeDeclarations"))
        assertFalse(panel.isModified())
    }

    @Test
    fun `dim comments checkbox previews and reset restores stored readability`() {
        stateBase.selectedPreset = SyntaxPreset.AMBIENT.name
        val panel = AyuIslandsSyntaxPanel()

        try {
            val component = buildSyntaxPanel(panel)
            val dimComments = findDimCommentsCheckBox(component)
            io.mockk.clearMocks(intensityService, answers = false, recordedCalls = true)

            dimComments.doClick()

            verify(exactly = 1) {
                intensityService.apply(
                    SyntaxPreset.AMBIENT,
                    emptyMap(),
                    any(),
                    emptyMap(),
                    SyntaxReadabilityOptions(dimComments = true),
                )
            }
            assertTrue(panel.isModified(), "toggling the real checkbox must dirty the syntax panel")

            io.mockk.clearMocks(intensityService, answers = false, recordedCalls = true)
            panel.reset()

            verify(exactly = 1) {
                intensityService.apply(
                    SyntaxPreset.AMBIENT,
                    emptyMap(),
                    any(),
                    emptyMap(),
                    SyntaxReadabilityOptions.DEFAULT,
                )
            }
            assertFalse(dimComments.isSelected, "reset must return the visible checkbox to stored state")
            assertFalse(panel.isModified(), "reset must leave pending and stored readability in sync")
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `unlicensed build shows readability controls disabled without preview writes`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        stateBase.dimComments = true
        val panel = AyuIslandsSyntaxPanel()

        try {
            val component = buildSyntaxPanel(panel)
            val readabilityControls =
                findCheckBoxes(component).filter { it.text in readabilityCheckboxTexts }

            assertEquals(
                readabilityCheckboxTexts,
                readabilityControls.mapTo(linkedSetOf()) { it.text },
                "free users must still see the premium readability controls",
            )
            readabilityControls.forEach { checkbox ->
                assertFalse(checkbox.isEnabled, "${checkbox.text} must be disabled without a Pro license")
                assertFalse(checkbox.isSelected, "${checkbox.text} must not expose persisted premium state")
            }

            io.mockk.clearMocks(intensityService, answers = false, recordedCalls = true)
            findDimCommentsCheckBox(component).doClick()

            verify(exactly = 0) {
                intensityService.apply(any(), any(), any(), any(), any())
            }
            assertFalse(panel.isModified(), "disabled readability controls must not dirty the panel")
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `reset disables readability controls when license flips to free`() {
        stateBase.selectedPreset = SyntaxPreset.AMBIENT.name
        stateBase.dimComments = true
        val panel = AyuIslandsSyntaxPanel()

        try {
            val component = buildSyntaxPanel(panel)
            val dimComments = findDimCommentsCheckBox(component)
            assertTrue(dimComments.isEnabled, "licensed users can edit readability controls")

            every { LicenseChecker.isLicensedOrGrace() } returns false
            io.mockk.clearMocks(intensityService, answers = false, recordedCalls = true)

            panel.reset()

            assertFalse(dimComments.isEnabled, "reset must disable readability after license loss")
            assertFalse(dimComments.isSelected, "reset must hide persisted premium readability after license loss")

            verify(exactly = 1) {
                intensityService.apply(
                    SyntaxPreset.AMBIENT,
                    emptyMap(),
                    any(),
                    emptyMap(),
                    SyntaxReadabilityOptions.DEFAULT,
                )
            }
            io.mockk.clearMocks(intensityService, answers = false, recordedCalls = true)

            dimComments.doClick()

            verify(exactly = 0) {
                intensityService.apply(any(), any(), any(), any(), any())
            }
            assertFalse(panel.isModified(), "disabled readability controls must not dirty the panel")
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `unlicensed preset row disables Custom pill affordance`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val panel = AyuIslandsSyntaxPanel()

        buildPresetPanel(panel)
        val customPill =
            panel.customPresetPresentationForTest()
                ?: error("Could not find Custom preset presentation")

        assertFalse(customPill.enabled, "free users must see Custom as disabled")
        assertEquals("Pro Feature", customPill.toolTipText)
    }

    // ---------- Test 8 - composite-key identity round-trip (Pitfall 1/2) ----------

    @Test
    fun `panel composite key resolves in the applicator and transforms the foreground`() {
        // Build the composite key exactly as the panel does: the language half
        // is the SyntaxLanguageRegistry displayName, the category half is
        // PrimitiveCategory.name (NOT displayName).
        val key = "Java|" + PrimitiveCategory.KEYWORD.name
        assertEquals("Java|KEYWORD", key, "panel key form must be displayName|CATEGORY_ENUM_NAME")

        // Reshape via the SAME `|` split the panel's buildNestedOverrides uses.
        val pipeIdx = key.indexOf('|')
        val language = key.substring(0, pipeIdx)
        val category = key.substring(pipeIdx + 1)
        val nested = mapOf(language to mapOf(category to 75))

        val baselineFg = Color(0xE6, 0xB6, 0x73)
        val javaKeywordKey = TextAttributesKey.createTextAttributesKey("JAVA_KEYWORD")
        val result =
            SyntaxIntensityApplicator.compute(
                SyntaxIntensityApplicator.Request(
                    preset = SyntaxPreset.CUSTOM,
                    variantName = "Mirage",
                    editorBg = Color(0x1F, 0x24, 0x30),
                    baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg)),
                    overlay = emptyMap(),
                    customOverrides = nested,
                    subordinatePreset = SyntaxPreset.AMBIENT,
                ),
            )
        assertNotNull(result[javaKeywordKey], "the composite key must resolve to a transformed entry")
        assertNotEquals(
            baselineFg.rgb,
            result[javaKeywordKey]?.foregroundColor?.rgb,
            "panel key form, classify().displayName, and resolveCurve lookup must all agree on " +
                "'Java' + 'KEYWORD' so slider 75 transforms the foreground (no silent no-op)",
        )
    }

    // ---------- Test 9 - license-invariant write paths (INTENSITY-16 behavioral) ----------

    @Test
    fun `LicenseChecker is never consulted by the slider-override apply path (INTENSITY-16)`() {
        // The service-layer enforceCustomGate is the defense-in-depth; the
        // panel's free/override write path must not consult the license
        // checker. Drive the apply / slider-change / rebind paths and verify
        // no LicenseChecker.isLicensedOrGrace() invocation lands on any of
        // them.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)

        // loadStateIntoPending already fired the only legitimate license call
        // (Custom normalization); reset the recorded calls so the next
        // verify() observes only the paths under test.
        io.mockk.clearMocks(LicenseChecker, answers = false, recordedCalls = true)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        try {
            invokeOnJavaKeywordSliderChanged(panel, 80)
            invokeRebindSlidersForJava(panel)
            writePendingPreset(panel, SyntaxPreset.CUSTOM)
            seedPendingOverride(panel, "Java|KEYWORD", "80")
            panel.apply()

            verify(exactly = 0) { LicenseChecker.isLicensedOrGrace() }
            verify(exactly = 0) { LicenseChecker.requestLicense(any()) }
        } finally {
            panel.dispose()
        }
    }

    // ---------- Test 10 - per-language master reset behavior (INTENSITY-15) ----------

    @Test
    fun `onResetCurrentLanguage clears only the active language's overrides, leaving others intact`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()

        // Seed two languages' overrides directly into the pending buffer, then
        // pin the active language and run the per-language reset.
        seedPendingOverride(panel, "Java|KEYWORD", "75")
        seedPendingOverride(panel, "Java|STRING_LITERAL", "20")
        seedPendingOverride(panel, "Kotlin|KEYWORD", "60")
        writeCurrentLanguage(panel, "Java")

        try {
            invokeOnResetCurrentLanguage(panel)

            val overrides = readPendingOverrides(panel)
            assertFalse(
                overrides.keys.any { it.startsWith("Java|") },
                "INTENSITY-15: per-language reset must drop every Java override cell.",
            )
            assertEquals(
                "60",
                overrides["Kotlin|KEYWORD"],
                "INTENSITY-15: other languages' overrides must survive a per-language reset.",
            )
        } finally {
            // onResetCurrentLanguage arms the debounce timer; stop it so the
            // platform's SwingTimerWatcherExtension does not flag a live timer.
            panel.dispose()
        }
    }

    // ---------- Test 11 - signed-delta readout (Direction B presentation) ----------

    @Test
    fun `signedReadout maps stored value to signed delta from identity`() {
        val panel = AyuIslandsSyntaxPanel()
        assertEquals("0", invokeSignedReadout(panel, 50), "identity (50) reads as 0")
        assertEquals("+25", invokeSignedReadout(panel, 75), "above identity reads +N")
        assertEquals("\u221220", invokeSignedReadout(panel, 30), "below identity reads \u2212N with U+2212 minus")
        assertEquals("+50", invokeSignedReadout(panel, 100), "max reads +50")
        assertEquals("\u221250", invokeSignedReadout(panel, 0), "min reads \u221250")
    }

    // ---------- Test 21 - CATEGORY_GROUPS coverage invariant ----------

    @Test
    fun `CATEGORY_GROUPS covers every PrimitiveCategory exactly once (16 entries, four buckets)`() {
        val groups = readCategoryGroups()
        val flattened = groups.flatMap { it.second }
        assertEquals(
            PrimitiveCategory.entries.size,
            flattened.size,
            "CATEGORY_GROUPS must cover all 16 categories with no dupes - a future 17th enum " +
                "must be assigned to a bucket, not silently dropped from the UI.",
        )
        assertEquals(
            PrimitiveCategory.entries.toSet(),
            flattened.toSet(),
            "CATEGORY_GROUPS flat-map must equal PrimitiveCategory.entries as a set.",
        )
        assertEquals(
            flattened.size,
            flattened.toSet().size,
            "CATEGORY_GROUPS must contain no duplicate categories.",
        )
        assertEquals(
            listOf(4, 5, 3, 4),
            groups.map { it.second.size },
            "Direction B bucket sizes are 4 / 5 / 3 / 4 by visual weight.",
        )
        assertEquals(
            listOf("Declarations", "Identifiers & Members", "Literals", "Keywords & Docs"),
            groups.map { it.first },
            "Direction B group titles in visual-weight order.",
        )
    }

    // ---------- Test 21b - documented compromise: grouped two-column Custom grid ----------

    @Test
    fun `panel source renders grouped semantic categories in two stable columns (documented compromise)`() {
        // Documented compromise: the two-column grouped layout lives in the
        // UI DSL `buildCustomFoldOut` / `buildCategoryGroup` build path. A
        // refactor back to the single unbroken table or to the deleted
        // master/detail JBList would be a visible regression. Verifying the
        // actual two column-level panels requires materialising the
        // DialogPanel under a live IntelliJ application.
        val source = readPanelSource()
        assertTrue(
            source.contains("private val CUSTOM_COLUMN_GROUPS: List<List<CategoryGroup>>"),
            "The Custom layout must declare stable grouped column assignments.",
        )
        assertTrue(
            source.contains("listOf(CATEGORY_GROUPS[0], CATEGORY_GROUPS[3])") &&
                source.contains("listOf(CATEGORY_GROUPS[1], CATEGORY_GROUPS[2])"),
            "The left column must hold Declarations + Keywords, and the right column Identifiers + Literals.",
        )
        assertTrue(
            source.contains("private fun Panel.buildCategoryGroup(") &&
                source.contains("group(categoryGroup.title)") &&
                source.contains("for (category in categoryGroup.categories)"),
            "The Custom UI must render named semantic groups rather than one unbroken table.",
        )
        assertTrue(
            source.contains("for (categoryGroup in CUSTOM_COLUMN_GROUPS.first())") &&
                source.contains("for (categoryGroup in CUSTOM_COLUMN_GROUPS.last())"),
            "The Custom UI must use two column-level panels.",
        )
        assertFalse(
            source.contains("RightGap.COLUMNS"),
            "The Custom matrix must not use the 60px platform column gap; the default adjacent-cell gap is enough.",
        )
        assertFalse(
            source.contains("CATEGORY_TABLE_ORDER") || source.contains("categoryHeaderRow()"),
            "The single table layout must stay gone.",
        )
        assertFalse(
            source.contains("JBList<PrimitiveCategory>") || source.contains("JBScrollPane(list)"),
            "The nested master/detail category list must stay gone.",
        )
    }

    // ---------- Test 21c - readout color signals default vs moved ----------

    @Test
    fun `applyReadout leaves identity visually empty and strengthens a moved readout`() {
        val panel = AyuIslandsSyntaxPanel()
        val identityLabel = JLabel()
        val movedLabel = JLabel()

        invokeApplyReadout(panel, identityLabel, 50)
        invokeApplyReadout(panel, movedLabel, 75)

        assertEquals("", identityLabel.text, "identity readout is visually empty")
        assertEquals("+25", movedLabel.text, "moved readout text is the signed delta")
        assertNotEquals(
            identityLabel.foreground.rgb,
            movedLabel.foreground.rgb,
            "identity (dimmed contextHelp) and moved (label) foregrounds must differ to signal state",
        )
    }

    @Test
    fun `applyReadout below identity is also rendered in the moved foreground`() {
        val panel = AyuIslandsSyntaxPanel()
        val identityLabel = JLabel()
        val belowLabel = JLabel()

        invokeApplyReadout(panel, identityLabel, 50)
        invokeApplyReadout(panel, belowLabel, 30)

        assertEquals("\u221220", belowLabel.text, "below identity reads \u2212N with U+2212 minus")
        assertNotEquals(
            identityLabel.foreground.rgb,
            belowLabel.foreground.rgb,
            "a below-identity cell is 'moved' and must use the stronger foreground",
        )
    }

    // ---------- Test 22 - documented compromise: Direction B DSL slider build ----------

    @Test
    fun `panel slider cell is tick-free (documented compromise, Direction B)`() {
        // Documented compromise: the DSL `slider()` cell's `paintTicks` /
        // `paintLabels` properties are set during the UI-DSL build; a
        // behavioral substitute requires the IntelliJ platform to materialise
        // the DialogPanel. Visible tick marks across 64 sliders would be the
        // Direction B regression we shipped to avoid.
        val source = readPanelSource()
        assertTrue(source.contains("paintTicks = false"), "Direction B: the slider must hide tick marks.")
        assertTrue(source.contains("paintLabels = false"), "Direction B: the slider must hide tick labels.")
    }

    @Test
    fun `panel builds sliders via the UI DSL slider cell, never a bare JSlider constructor (documented compromise)`() {
        // Documented compromise: a bare `JSlider(...)` constructor bypasses
        // UI-DSL theming and would look out-of-place against the surrounding
        // settings rows. The DSL build site cannot be exercised without
        // wiring the IntelliJ platform.
        val source = readPanelSource()
        // Allow the documentation code span `JSlider(...)` (backtick-quoted) but
        // forbid a real constructor call: JSlider( preceded by neither an
        // identifier char nor a backtick.
        assertFalse(
            Regex("""[^a-zA-Z_`]JSlider\(""").containsMatchIn(source),
            "Direction B: sliders must be built via the UI-DSL slider() cell, never new JSlider(...).",
        )
        assertTrue(
            source.contains("slider(SLIDER_MIN, SLIDER_MAX, 0, 0)"),
            "Direction B: the tick-free slider cell must be built with zero tick spacing.",
        )
    }

    // ---------- Test 22b - shared label column width behavior ----------

    @Test
    fun `labelColumnWidth is at least the widest PrimitiveCategory displayName so no label clips`() {
        val panel = AyuIslandsSyntaxPanel()
        val width = readLabelColumnWidth(panel)
        val font = UIUtil.getLabelFont()
        val metrics = JLabel().getFontMetrics(font)
        val widest = PrimitiveCategory.entries.maxOf { metrics.stringWidth(it.displayName) }
        assertTrue(
            width >= widest,
            "the shared label column ($width) must be at least the widest displayName ($widest).",
        )
        assertTrue(width > 0, "labelColumnWidth must be positive.")
    }

    @Test
    fun `panel never delegates a platform SpacingConfiguration (documented compromise, binary-compat guard)`() {
        // Documented compromise: matches the project's
        // `gotcha_platform_interface_delegation_binary_compat` lesson — a
        // `by` delegation onto the platform `SpacingConfiguration` interface
        // compiled against 2025.1 throws `AbstractMethodError` on newer
        // runtime IDEs and hangs the settings page on "Loading…". Unit
        // tests run against the embedded SDK so they cannot reproduce the
        // runtime failure; the source-regex check is the cheapest guard.
        val source = readPanelSource()
        val forbiddenSpacingSymbols =
            listOf(
                "SpacingConfiguration",
                "customizeSpacingConfiguration",
                "IntelliJSpacingConfiguration",
                "tightenedSpacing",
                "HALF_HORIZONTAL_GAP",
            )
        for (forbidden in forbiddenSpacingSymbols) {
            assertFalse(
                source.contains(forbidden),
                "binary-compat guard: '$forbidden' must not appear in the panel source.",
            )
        }
    }

    @Test
    fun `compact size constants stay pinned to the values the grouped rows depend on`() {
        // Read the production companion constants directly: the grouped
        // two-column layout depends on these numbers staying paired so rows
        // line up across both column panels without horizontal bloat.
        assertEquals(28, readPrivateConst("READOUT_WIDTH"), "right-aligned readout cell must stay compact at 28.")
        assertEquals(8, readPrivateConst("LABEL_PADDING"), "leading-label trailing padding must stay 8.")
        assertEquals(
            140,
            readPrivateConst("SLIDER_TRACK_WIDTH"),
            "slider tracks must stay 140 to avoid horizontal bloat in the two-column matrix.",
        )
        assertEquals(
            20,
            readPrivateConst("TRAILING_ZONE_WIDTH"),
            "fixed reset-only trailing zone must stay compact at 20.",
        )
    }

    @Test
    fun `scaled readout cell still fits the widest live signed value without clipping`() {
        // The readout cell is right-aligned and fixed-width. Verify the chosen
        // READOUT_WIDTH (scaled) holds the widest signed string the live model
        // reaches: "\u221250" / "+50" (3 glyphs) at the label font, so the number
        // never clips when right-aligned. Trimming to 28 trades the prior
        // 4-glyph "\u2212100" headroom (unreachable by the +/-50 model) for the
        // trailing zone's width without clipping any live value.
        val width = readReadoutWidthScaled()
        val font = UIUtil.getLabelFont()
        val metrics = JLabel().getFontMetrics(font)
        val widestSigned = maxOf(metrics.stringWidth("\u221250"), metrics.stringWidth("+50"))
        assertTrue(
            width >= widestSigned,
            "the scaled readout cell ($width) must be at least the widest live signed value " +
                "($widestSigned for +/-50) so the number never clips when right-aligned.",
        )
    }

    // ---------- Test 23 - slider-change behavior (readout + reset icon + sparse write) ----------

    @Test
    fun `onSliderChanged updates readout, enables reset, and records the sparse override`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)
        widgets.slider.value = 80

        try {
            invokeOnJavaKeywordSliderChanged(panel, 80)

            assertEquals("+30", widgets.label.text, "readout must render the signed delta")
            assertTrue(widgets.resetButton.isVisible, "category reset must appear once the cell diverges")
            assertEquals(
                "80",
                readPendingOverrides(panel)["Java|KEYWORD"],
                "the moved cell must be recorded as a sparse composite-key override",
            )
            assertTrue(panel.isModified(), "a sparse slider override must mark the panel modified so Apply enables")
            val accessibleName = widgets.slider.accessibleContext.accessibleName
            assertTrue(
                accessibleName.contains("+30 from default"),
                "the slider must announce its signed distance from identity",
            )
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `slider preview applies pending overrides without persisting Settings state`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)

        try {
            invokeOnJavaKeywordSliderChanged(panel, 80)
            invokePreview(panel)

            verify(exactly = 1) {
                intensityService.apply(
                    SyntaxPreset.CUSTOM,
                    mapOf("Java" to mapOf("KEYWORD" to 80)),
                    any(),
                    emptyMap(),
                )
            }
            assertTrue(stateBase.customOverrides.isEmpty(), "preview must not persist pending slider overrides")
            assertTrue(panel.isModified(), "preview must leave the Settings Apply button dirty")
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `onSliderChanged back to identity removes the sparse override`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)
        seedPendingOverride(panel, "Java|KEYWORD", "80")

        try {
            invokeOnJavaKeywordSliderChanged(panel, 50)

            assertEquals("", widgets.label.text, "identity readout is visually empty")
            assertFalse(widgets.resetButton.isVisible, "reset hides at identity with no style")
            assertFalse(readPendingOverrides(panel).containsKey("Java|KEYWORD"))
        } finally {
            panel.dispose()
        }
    }

    // ---------- Test 25 - master reset button enablement tracks active language ----------

    @Test
    fun `refreshMasterResetButton labels and shows only when the active language has customizations`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Kotlin")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)

        invokeRefreshMasterResetButton(panel)
        assertEquals("Reset Kotlin customizations", widgets.button.text)
        assertFalse(widgets.button.isVisible, "no Kotlin override yet -> hidden")
        assertFalse(widgets.button.isEnabled, "no Kotlin override yet -> disabled")

        seedPendingOverride(panel, "Kotlin|KEYWORD", "70")
        invokeRefreshMasterResetButton(panel)
        assertTrue(widgets.button.isVisible, "a Kotlin override shows the master reset")
        assertTrue(widgets.button.isEnabled, "a Kotlin override enables the master reset")

        seedPendingOverride(panel, "Java|KEYWORD", "70")
        writeCurrentLanguage(panel, "Java")
        invokeRefreshMasterResetButton(panel)
        assertEquals("Reset Java customizations", widgets.button.text, "label tracks the active language")
        assertTrue(widgets.button.isVisible, "a Java override shows the master reset")
        assertTrue(widgets.button.isEnabled, "a Java override enables the master reset")
    }

    // ---------- Test 26 - buildNestedOverrides reshapes + guards the sparse map ----------

    @Test
    fun `apply reshapes seeded overrides into nested language-category-int and skips malformed keys`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.CUSTOM)
        seedPendingOverride(panel, "Java|KEYWORD", "75")
        seedPendingOverride(panel, "|KEYWORD", "60") // empty language half -> skipped
        seedPendingOverride(panel, "Java|", "40") // empty category half -> skipped
        seedPendingOverride(panel, "Java|STRING_LITERAL", "notAnInt") // non-int -> skipped

        panel.apply()

        verify(exactly = 1) {
            intensityService.apply(
                SyntaxPreset.CUSTOM,
                mapOf("Java" to mapOf("KEYWORD" to 75)),
                any(),
                emptyMap(),
            )
        }
    }

    // ---------- Test 27 - rebindSlidersFor snaps seeded widgets to stored values ----------

    @Test
    fun `rebindSlidersFor snaps a seeded slider to the stored override and identity otherwise`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val keyword = seedWidgets(panel, PrimitiveCategory.KEYWORD)
        val stringLiteral = seedWidgets(panel, PrimitiveCategory.STRING_LITERAL)
        seedPendingOverride(panel, "Java|KEYWORD", "85")
        seedPendingStyle(panel, "Java|KEYWORD", "BOLD")

        invokeRebindSlidersForJava(panel)

        assertEquals(85, keyword.slider.value, "stored override snaps the slider")
        assertEquals("+35", keyword.label.text, "readout reflects the snapped signed delta")
        assertTrue(keyword.resetButton.isVisible, "customized cell reveals the category reset")
        assertEquals(50, stringLiteral.slider.value, "untouched cell snaps to identity")
        assertEquals("", stringLiteral.label.text, "untouched identity readout is visually empty")
        assertFalse(stringLiteral.resetButton.isVisible, "untouched cell hides the category reset")
    }

    // ---------- Part B Test 29 - legacy styles still count as modifications ----------

    @Test
    fun `isModified is true after a legacy style-only change (slider untouched)`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)
        assertFalse(panel.isModified(), "fresh CUSTOM panel with no changes is not modified")

        try {
            seedPendingStyle(panel, "Java|KEYWORD", "BOLD")
            assertTrue(
                panel.isModified(),
                "a legacy style-only change (no slider move) must mark the panel modified so Apply enables",
            )
        } finally {
            panel.dispose()
        }
    }

    // ---------- Part B Test 30 - per-row reset clears BOTH dimensions ----------

    @Test
    fun `per-row reset clears BOTH the slider override and the style for the cell`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)
        // Seed both a slider override and a style override for the same cell.
        seedPendingOverride(panel, "Java|KEYWORD", "80")
        seedPendingStyle(panel, "Java|KEYWORD", "BOLD")
        widgets.slider.value = 80

        try {
            // The production Reset category button delegates to resetCell; the
            // test drives the same private method directly.
            invokeResetKeywordCell(panel)

            assertFalse(
                readPendingOverrides(panel).containsKey("Java|KEYWORD"),
                "per-row reset must drop the slider override for the cell",
            )
            assertFalse(
                readPendingStyles(panel).containsKey("Java|KEYWORD"),
                "per-row reset must drop the style override for the cell",
            )
            assertEquals(50, widgets.slider.value, "per-row reset snaps the slider back to identity")
            assertFalse(widgets.resetButton.isVisible, "per-row reset hides the category reset")
        } finally {
            panel.dispose()
        }
    }

    // ---------- Part B Test 31 - master reset clears both maps for the active language ----------

    @Test
    fun `onResetCurrentLanguage clears both overrides and styles for the active language only`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)
        seedPendingOverride(panel, "Java|KEYWORD", "75")
        seedPendingStyle(panel, "Java|STRING_LITERAL", "ITALIC")
        seedPendingOverride(panel, "Kotlin|KEYWORD", "60")
        seedPendingStyle(panel, "Kotlin|KEYWORD", "BOLD")

        try {
            invokeOnResetCurrentLanguage(panel)

            val overrides = readPendingOverrides(panel)
            val styles = readPendingStyles(panel)
            assertFalse(overrides.keys.any { it.startsWith("Java|") }, "master reset drops Java overrides")
            assertFalse(styles.keys.any { it.startsWith("Java|") }, "master reset drops Java styles")
            assertEquals("60", overrides["Kotlin|KEYWORD"], "other languages' overrides survive")
            assertEquals("BOLD", styles["Kotlin|KEYWORD"], "other languages' styles survive")
        } finally {
            panel.dispose()
        }
    }

    // ---------- Part B Test 32 - buildNested decodes styles and skips bad cells (via apply) ----------

    @Test
    fun `apply threads decoded font styles to the service and skips malformed style cells`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.CUSTOM)
        seedPendingStyle(panel, "Java|KEYWORD", "BOLD") // -> Font.BOLD (1)
        seedPendingStyle(panel, "Java|STRING_LITERAL", "BOLD_ITALIC") // -> Font.BOLD or ITALIC (3)
        seedPendingStyle(panel, "|KEYWORD", "BOLD") // empty language -> skipped
        seedPendingStyle(panel, "Java|", "ITALIC") // empty category -> skipped
        seedPendingStyle(panel, "Java|COMMENT", "NOT_A_STYLE") // undecodable -> skipped

        panel.apply()

        verify(exactly = 1) {
            intensityService.apply(
                SyntaxPreset.CUSTOM,
                emptyMap(),
                any(),
                mapOf(
                    "Java" to
                        mapOf(
                            "KEYWORD" to (Font.BOLD),
                            "STRING_LITERAL" to (Font.BOLD or Font.ITALIC),
                        ),
                ),
            )
        }
    }

    // ---------- Part B Test 33 - documented compromise: InplaceButton-only trailing reset ----------

    @Test
    fun `trailing reset uses InplaceButton, never ActionButton (documented compromise)`() {
        // Documented compromise: `ActionButton` plus `updateComponentTreeUI`
        // reproduce the `ActionToolbar.updateUI`
        // `SlowOperations SEVERE` crash documented in the project's
        // testing-philosophy notes. The fixed-slot
        // `GridLayout(1, TRAILING_SLOT_COUNT, ...)` plus `TRAILING_SLOT_*`
        // constants are DSL-build internals; a behavioral substitute would
        // need to materialise the trailing zone JPanel via the platform.
        val source = readPanelSource()
        assertTrue(
            source.contains("InplaceButton("),
            "The trailing reset control must be InplaceButton.",
        )
        assertTrue(
            source.contains("JPanel(GridLayout(1, TRAILING_SLOT_COUNT, 0, 0))"),
            "The trailing reset zone must use a fixed slot so reset visibility never shifts the row.",
        )
        assertEquals(
            1,
            readPrivateConst("TRAILING_SLOT_COUNT"),
            "the trailing zone must reserve one stable reset slot.",
        )
        assertEquals(
            20,
            readPrivateConst("TRAILING_SLOT_SIDE"),
            "the trailing reset slot must stay 20px so reset visibility never shifts the row.",
        )
        assertFalse(
            source.contains("JToggleButton"),
            "Part B: no bare JToggleButton - the reset is a lightweight InplaceButton.",
        )
        assertFalse(
            Regex("""[^a-zA-Z_]ActionButton\b""").containsMatchIn(source.substringBefore("JBUI.CurrentTheme")) &&
                source.contains("import com.intellij.openapi.actionSystem.impl.ActionButton"),
            "Part B: no ActionButton - it trips the ActionToolbar.updateUI SlowOperations SEVERE.",
        )
        assertFalse(
            source.contains("updateComponentTreeUI"),
            "Part B: NEVER updateComponentTreeUI - SlowOperations SEVERE crash.",
        )
    }

    @Test
    fun `refreshResetVisibility shows the reset on a style-only override (behavioral)`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)

        try {
            // No slider move; just a style-only override. The reset must surface
            // because pendingStyles is dirty for this cell.
            seedPendingStyle(panel, "Java|KEYWORD", "BOLD")
            invokeRefreshResetVisibility(panel)
            assertTrue(
                widgets.resetButton.isVisible,
                "a style-only override must keep the cell resettable, not just a slider divergence.",
            )

            // Drop the style — slider still at identity — and the reset hides.
            pendingStylesField(panel).clear()
            invokeRefreshResetVisibility(panel)
            assertFalse(
                widgets.resetButton.isVisible,
                "an untouched cell (no style, no slider move) must hide the reset.",
            )
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `apply persists pending styles into state customStyles after the service call (behavioral)`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.CUSTOM)
        seedPendingStyle(panel, "Java|KEYWORD", "BOLD")
        seedPendingStyle(panel, "Java|STRING_LITERAL", "ITALIC")

        panel.apply()

        verifyOrder {
            // Service call first.
            intensityService.apply(any(), any(), any(), any())
            // Then the persistence reads state.
            stateService.state
        }
        assertEquals(
            mapOf("Java|KEYWORD" to "BOLD", "Java|STRING_LITERAL" to "ITALIC"),
            stateBase.customStyles,
            "apply must persist pendingStyles into state.customStyles (clear + putAll).",
        )
    }

    // ---------- Test 18 - debounce behavior (INTENSITY-13 / D-19, behavioral) ----------

    @Test
    fun `applyTimer is a single-shot 100ms timer that previews without persisting`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)

        try {
            val timer = readApplyTimer(panel)
            assertFalse(timer.isRepeats, "D-19: the debounce timer must be single-shot.")
            assertEquals(100, timer.delay, "D-19: the debounce window must be exactly 100ms.")

            // Clear the apply call recorded by panelWithLoadedState() and any
            // earlier setup so we observe only the slider-change path.
            io.mockk.clearMocks(intensityService, answers = false, recordedCalls = true)
            invokeOnJavaKeywordSliderChanged(panel, 80)

            verify(exactly = 0) {
                intensityService.apply(any(), any(), any(), any())
            }
            assertTrue(
                timer.isRunning,
                "D-19: the slider change listener must arm the debounce timer for a deferred preview.",
            )
            assertTrue(
                stateBase.customOverrides.isEmpty(),
                "D-19: the slider change listener must NOT persist Settings state synchronously.",
            )
        } finally {
            panel.dispose()
        }
    }

    // ---------- Reflection helpers ----------

    private fun attrsWithFg(color: Color): TextAttributes {
        val attrs = TextAttributes()
        attrs.foregroundColor = color
        return attrs
    }

    private fun panelWithLoadedState(): AyuIslandsSyntaxPanel {
        val panel = AyuIslandsSyntaxPanel()
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("loadStateIntoPending")
        method.isAccessible = true
        method.invoke(panel)
        return panel
    }

    private fun buildSyntaxPanel(syntaxPanel: AyuIslandsSyntaxPanel): DialogPanel =
        panel {
            syntaxPanel.buildReadabilityBlockForTest(this)
        }

    private fun buildPresetPanel(syntaxPanel: AyuIslandsSyntaxPanel): DialogPanel =
        panel {
            syntaxPanel.buildPresetBlockForTest(this)
        }

    private fun findDimCommentsCheckBox(container: Container): JCheckBox =
        findDimCommentsCheckBoxOrNull(container)
            ?: error("Could not find checkbox with text: Dim comments")

    private fun findDimCommentsCheckBoxOrNull(container: Container): JCheckBox? {
        for (component in container.components) {
            if (component is JCheckBox && component.text == "Dim comments") return component
            if (component is Container) {
                val nested = findDimCommentsCheckBoxOrNull(component)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun findCheckBoxes(container: Container): List<JCheckBox> =
        container.components.flatMap { component ->
            val nested = if (component is Container) findCheckBoxes(component) else emptyList()
            if (component is JCheckBox) listOf(component) + nested else nested
        }

    private fun invokeOnPresetChosen(
        panel: AyuIslandsSyntaxPanel,
        preset: SyntaxPreset,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "onPresetChosen",
                SyntaxPreset::class.java,
            )
        method.isAccessible = true
        method.invoke(panel, preset)
    }

    private fun readPendingPreset(panel: AyuIslandsSyntaxPanel): SyntaxPreset {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingPreset")
        field.isAccessible = true
        return field.get(panel) as SyntaxPreset
    }

    private fun readStoredPreset(panel: AyuIslandsSyntaxPanel): SyntaxPreset {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("storedPreset")
        field.isAccessible = true
        return field.get(panel) as SyntaxPreset
    }

    private fun writePendingPreset(
        panel: AyuIslandsSyntaxPanel,
        preset: SyntaxPreset,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingPreset")
        field.isAccessible = true
        field.set(panel, preset)
    }

    private fun readPendingBoolean(
        panel: AyuIslandsSyntaxPanel,
        fieldName: String,
    ): Boolean {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getBoolean(panel)
    }

    private fun writePendingBoolean(
        panel: AyuIslandsSyntaxPanel,
        fieldName: String,
        value: Boolean,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setBoolean(panel, value)
    }

    private fun writeCurrentLanguage(
        panel: AyuIslandsSyntaxPanel,
        language: String,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("currentLanguage")
        field.isAccessible = true
        field.set(panel, language)
    }

    private fun pendingOverridesField(panel: AyuIslandsSyntaxPanel): MutableMap<*, *> {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingOverrides")
        field.isAccessible = true
        return field.get(panel) as MutableMap<*, *>
    }

    /** Snapshot the pending override map as `String -> String` without an unchecked cast. */
    private fun readPendingOverrides(panel: AyuIslandsSyntaxPanel): Map<String, String> =
        pendingOverridesField(panel).entries.associate { (key, value) ->
            (key as String) to (value as String)
        }

    private fun seedPendingOverride(
        panel: AyuIslandsSyntaxPanel,
        key: String,
        value: String,
    ) {
        // Reflective put avoids the parameterized-cast warning: the runtime
        // map element type is erased, so the put goes through java.util.Map.
        val map = pendingOverridesField(panel)
        val putMethod = map.javaClass.getMethod("put", Any::class.java, Any::class.java)
        putMethod.invoke(map, key, value)
    }

    private fun pendingStylesField(panel: AyuIslandsSyntaxPanel): MutableMap<*, *> {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingStyles")
        field.isAccessible = true
        return field.get(panel) as MutableMap<*, *>
    }

    /** Snapshot the pending style map as `String -> String` without an unchecked cast. */
    private fun readPendingStyles(panel: AyuIslandsSyntaxPanel): Map<String, String> =
        pendingStylesField(panel).entries.associate { (key, value) ->
            (key as String) to (value as String)
        }

    private fun seedPendingStyle(
        panel: AyuIslandsSyntaxPanel,
        key: String,
        value: String,
    ) {
        val map = pendingStylesField(panel)
        val putMethod = map.javaClass.getMethod("put", Any::class.java, Any::class.java)
        putMethod.invoke(map, key, value)
    }

    private fun invokeOnResetCurrentLanguage(panel: AyuIslandsSyntaxPanel) {
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("onResetCurrentLanguage")
        method.isAccessible = true
        method.invoke(panel)
    }

    private fun invokeResetKeywordCell(panel: AyuIslandsSyntaxPanel) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "resetCell",
                PrimitiveCategory::class.java,
            )
        method.isAccessible = true
        method.invoke(panel, PrimitiveCategory.KEYWORD)
    }

    private fun invokeOnJavaKeywordSliderChanged(
        panel: AyuIslandsSyntaxPanel,
        value: Int,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "onSliderChanged",
                String::class.java,
                PrimitiveCategory::class.java,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(panel, "Java", PrimitiveCategory.KEYWORD, value)
    }

    private fun invokePreview(panel: AyuIslandsSyntaxPanel) {
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("preview")
        method.isAccessible = true
        method.invoke(panel)
    }

    private fun invokeRefreshMasterResetButton(panel: AyuIslandsSyntaxPanel) {
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("refreshMasterResetButton")
        method.isAccessible = true
        method.invoke(panel)
    }

    /**
     * Materialize one category's slider / readout / reset-icon widgets and the
     * master reset button so logic methods can be driven without a built
     * [com.intellij.openapi.ui.DialogPanel].
     */
    private fun seedWidgets(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
    ): SeededWidgets {
        val slider = JSlider(0, 100, 50)
        val label = JLabel("0")
        val resetButton = InplaceButton("Reset", AllIcons.Actions.Rollback) {}
        val button = JButton()
        putIntoMapField(panel, "sliders", category, slider)
        putIntoMapField(panel, "sliderLabels", category, label)
        putIntoMapField(panel, "resetButtons", category, resetButton)
        val buttonField = AyuIslandsSyntaxPanel::class.java.getDeclaredField("masterResetButton")
        buttonField.isAccessible = true
        buttonField.set(panel, button)
        return SeededWidgets(slider, label, resetButton, button)
    }

    private fun putIntoMapField(
        panel: AyuIslandsSyntaxPanel,
        fieldName: String,
        category: PrimitiveCategory,
        value: Any,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        val map = field.get(panel) as MutableMap<*, *>
        val putMethod = map.javaClass.getMethod("put", Any::class.java, Any::class.java)
        putMethod.invoke(map, category, value)
    }

    private data class SeededWidgets(
        val slider: JSlider,
        val label: JLabel,
        val resetButton: InplaceButton,
        val button: JButton,
    )

    private fun invokeRebindSlidersForJava(panel: AyuIslandsSyntaxPanel) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("rebindSlidersFor", String::class.java)
        method.isAccessible = true
        method.invoke(panel, "Java")
    }

    private fun invokeApplyReadout(
        panel: AyuIslandsSyntaxPanel,
        label: JLabel,
        value: Int,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "applyReadout",
                JLabel::class.java,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(panel, label, value)
    }

    private fun invokeSignedReadout(
        panel: AyuIslandsSyntaxPanel,
        value: Int,
    ): String {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "signedReadout",
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        return method.invoke(panel, value) as String
    }

    /**
     * Reflect the private `CATEGORY_GROUPS` companion list into a stable
     * `List<Pair<title, categories>>` shape the coverage-invariant test can
     * assert against without depending on the private `CategoryGroup` type.
     */
    private fun readCategoryGroups(): List<Pair<String, List<PrimitiveCategory>>> {
        // A private val on a private companion compiles to a private static
        // backing field on the OUTER class with no getter, so read it directly.
        val groupsField = AyuIslandsSyntaxPanel::class.java.getDeclaredField("CATEGORY_GROUPS")
        groupsField.isAccessible = true
        val groups = groupsField.get(null) as List<*>
        return groups.map { group ->
            requireNotNull(group)
            val titleMethod = group.javaClass.getDeclaredMethod("getTitle")
            titleMethod.isAccessible = true
            val categoriesMethod = group.javaClass.getDeclaredMethod("getCategories")
            categoriesMethod.isAccessible = true
            val title = titleMethod.invoke(group) as String
            val categories =
                (categoriesMethod.invoke(group) as List<*>).map { it as PrimitiveCategory }
            title to categories
        }
    }

    private fun readLabelColumnWidth(panel: AyuIslandsSyntaxPanel): Int {
        val getter = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("getLabelColumnWidth")
        getter.isAccessible = true
        return getter.invoke(panel) as Int
    }

    /**
     * Read the private companion `READOUT_WIDTH` const (a private static field
     * on the outer class) and return it scaled by [JBUI.scale], matching the
     * runtime width the readout cell is pinned to.
     */
    private fun readReadoutWidthScaled(): Int {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("READOUT_WIDTH")
        field.isAccessible = true
        return JBUI.scale(field.getInt(null))
    }

    /** Read any of the panel's private companion `Int` constants by name. */
    private fun readPrivateConst(name: String): Int {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getInt(null)
    }

    private fun invokeRefreshResetVisibility(panel: AyuIslandsSyntaxPanel) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "refreshResetVisibility",
                PrimitiveCategory::class.java,
            )
        method.isAccessible = true
        method.invoke(panel, PrimitiveCategory.KEYWORD)
    }

    private fun readApplyTimer(panel: AyuIslandsSyntaxPanel): Timer {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("applyTimer")
        field.isAccessible = true
        return field.get(panel) as Timer
    }

    private fun readPanelSource(): String {
        val sourceFile = File("src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt")
        return FileUtil.loadFile(sourceFile)
    }
}
