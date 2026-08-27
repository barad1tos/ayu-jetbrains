package dev.ayuislands.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.EditorTextField
import com.intellij.ui.InplaceButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import dev.ayuislands.syntax.SyntaxTransactionResult
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
import java.awt.GridLayout
import java.awt.Point
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities
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
 *  - Pill and custom-control changes preview without mutating persisted state.
 *  - Apply materializes runtime schemes before persisting pending settings.
 *    A runtime failure leaves `state.selectedPreset` untouched.
 *  - Custom rejection for unlicensed users: `LicenseChecker.requestLicense`
 *    is invoked, pending stays at the previous preset, no service call,
 *    no state mutation.
 *  - Custom accepted for licensed users: selection uses the same live session
 *    as the four named pills.
 *  - `reset()` restores runtime state and reloads the stored buffer.
 *
 * Plain kotlin.test + MockK. The Kotlin UI DSL requires EDT-managed
 * `DialogPanel` lifecycle, so the tests exercise apply / reset / isModified
 * on freshly constructed panels and drive the pill selection through the
 * private `onPresetChosen` seam via reflection.
 *
 * Visual contracts are asserted against the materialized Swing tree rather
 * than production source text, so refactors remain free to change the build
 * mechanism while preserving observable behavior.
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
    private lateinit var runtimeSession: SyntaxIntensityService.SyntaxRuntimeSession
    private lateinit var syntaxPreviewEditorFixture: SyntaxPreviewEditorFixture
    private var schemeName = "Ayu Islands Mirage"

    @BeforeTest
    fun setUp() {
        stateBase = SyntaxIntensityBaseState()
        schemeName = "Ayu Islands Mirage"
        stateService = mockk(relaxed = true)
        every { stateService.state } returns stateBase
        mockkObject(SyntaxIntensityState.Companion)
        every { SyntaxIntensityState.getInstance() } returns stateService

        intensityService = mockk(relaxed = true)
        runtimeSession = mockk(relaxed = true)
        mockkObject(SyntaxIntensityService.Companion)
        every { SyntaxIntensityService.getInstance() } returns intensityService
        every { intensityService.openRuntimeSession() } returns runtimeSession
        val applied = SyntaxTransactionResult.Applied(emptySet(), emptySet())
        every { runtimeSession.preview(any()) } returns applied
        every { runtimeSession.materialize(any()) } returns applied
        every { runtimeSession.restore() } returns applied

        mockkObject(LicenseChecker)
        // Default: licensed. Individual tests override to false where needed.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { LicenseChecker.requestLicense(any()) } returns Unit

        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManagerEx>(relaxed = true)
        val editorColorsManager = mockk<EditorColorsManager>()
        val editorScheme = mockk<EditorColorsScheme>()
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns actionManagerMock
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { appMock.getService(ActionManagerEx::class.java) } returns actionManagerMock
        every { appMock.getServiceIfCreated(ActionManager::class.java) } returns actionManagerMock
        every { appMock.getService(EditorColorsManager::class.java) } returns editorColorsManager
        every { editorColorsManager.globalScheme } returns editorScheme
        every { editorScheme.name } answers { schemeName }
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(experimentalUiClass) } returns experimentalUiMock

        syntaxPreviewEditorFixture = SyntaxPreviewEditorFixture()
        syntaxPreviewEditorFixture.install()
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
    fun `buildPanel initializes syntax preview before first interaction`() {
        val syntaxPanel = AyuIslandsSyntaxPanel()
        val dialogPanel = buildFullSyntaxPanel(syntaxPanel)

        try {
            val preview =
                assertNotNull(
                    findComponent(dialogPanel, SyntaxPreviewComponent::class.java),
                    "Syntax tab must include the native syntax preview component.",
                )
            val editor =
                assertNotNull(
                    findComponent(preview, EditorTextField::class.java),
                    "Syntax preview must embed EditorTextField for native syntax highlighting.",
                )

            assertEquals(AyuVariant.MIRAGE, preview.variantForTest())
            assertEquals("Kotlin", preview.languageForTest(), "Preview must initialize to the Kotlin sample.")
            assertEquals(syntaxPreviewEditorFixture.kotlinFileType, editor.fileType)
            assertSame(syntaxPreviewEditorFixture.previewProject, editor.project)
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `preview plugin availability does not replace the persisted language`() {
        val unavailableFileType = syntaxPreviewEditorFixture.mockFileType("Unavailable", "txt")
        val javaScriptFileType = syntaxPreviewEditorFixture.mockFileType("JavaScript", "js")
        every { syntaxPreviewEditorFixture.fileTypeManager.getStdFileType("Kotlin") } returns unavailableFileType
        every { syntaxPreviewEditorFixture.fileTypeManager.getStdFileType("JAVA") } returns unavailableFileType
        every { syntaxPreviewEditorFixture.fileTypeManager.getStdFileType("JavaScript") } returns javaScriptFileType
        val syntaxPanel = AyuIslandsSyntaxPanel()
        val dialogPanel = buildFullSyntaxPanel(syntaxPanel)

        try {
            val preview =
                assertNotNull(
                    findComponent(dialogPanel, SyntaxPreviewComponent::class.java),
                    "Syntax tab must include the native syntax preview component.",
                )
            val editor =
                assertNotNull(
                    findComponent(preview, EditorTextField::class.java),
                    "Syntax preview must embed EditorTextField for native syntax highlighting.",
                )

            assertEquals("Kotlin", preview.languageForTest())
            assertNotEquals(javaScriptFileType, editor.fileType)
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `language selector updates syntax preview language and file type`() {
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = AyuIslandsSyntaxPanel()
        val dialogPanel = buildFullSyntaxPanel(syntaxPanel)

        try {
            val preview =
                assertNotNull(
                    findComponent(dialogPanel, SyntaxPreviewComponent::class.java),
                    "Syntax tab must include the native syntax preview component.",
                )
            val languageCombo =
                assertNotNull(
                    findComponent(dialogPanel, JComboBox::class.java),
                    "Syntax tab must expose the Custom language selector.",
                )

            languageCombo.selectedItem = "Java"

            val editor =
                assertNotNull(
                    findComponent(preview, EditorTextField::class.java),
                    "Syntax preview must keep the native editor after language changes.",
                )
            assertEquals("Java", preview.languageForTest())
            assertEquals(syntaxPreviewEditorFixture.javaFileType, editor.fileType)
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `completed project scan replaces the cold fallback language`() {
        val project = mockk<Project>(relaxed = true)
        lateinit var refreshLanguage: () -> Unit
        var resolutionCount = 0
        val syntaxPanel =
            AyuIslandsSyntaxPanel(
                resolveLanguage = { _, _, _ ->
                    if (resolutionCount++ == 0) "Kotlin" else "Swift"
                },
                subscribeProjectLanguage = { _, refresh ->
                    refreshLanguage = refresh
                    {}
                },
            )
        val dialogPanel =
            panel {
                syntaxPanel.buildPanel(this, AyuVariant.MIRAGE, contextProject = project)
            }

        try {
            val languageCombo = assertNotNull(findComponent(dialogPanel, JComboBox::class.java))
            assertEquals("Kotlin", languageCombo.selectedItem)

            refreshLanguage()

            assertEquals("Swift", languageCombo.selectedItem)
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `completed project scan preserves a manual language selection`() {
        val project = mockk<Project>(relaxed = true)
        lateinit var refreshLanguage: () -> Unit
        var resolutionCount = 0
        val syntaxPanel =
            AyuIslandsSyntaxPanel(
                resolveLanguage = { _, _, _ ->
                    if (resolutionCount++ == 0) "Kotlin" else "Swift"
                },
                subscribeProjectLanguage = { _, refresh ->
                    refreshLanguage = refresh
                    {}
                },
            )
        val dialogPanel =
            panel {
                syntaxPanel.buildPanel(this, AyuVariant.MIRAGE, contextProject = project)
            }

        try {
            val languageCombo = assertNotNull(findComponent(dialogPanel, JComboBox::class.java))
            languageCombo.selectedItem = "Java"

            refreshLanguage()

            assertEquals("Java", languageCombo.selectedItem)
        } finally {
            syntaxPanel.dispose()
        }
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
    fun `loadStateIntoPending normalizes unlicensed Custom but retains every sparse map`() {
        // Break caught: losing edit access must not make a later unrelated Apply erase saved Custom cells.
        every { LicenseChecker.isLicensedOrGrace() } returns false
        stateBase.selectedPreset = "CUSTOM"
        stateBase.subordinatePreset = "NEON"
        stateBase.customOverrides["Java|KEYWORD"] = "85"
        stateBase.customStyles["Java|KEYWORD"] = "BOLD"
        stateBase.customEmphasis["Java|KEYWORD"] = "ITALIC"

        val panel = panelWithLoadedState()

        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
        assertSame(SyntaxPreset.AMBIENT, readStoredPreset(panel))
        assertEquals(mapOf("Java|KEYWORD" to "85"), readPendingOverrides(panel))
        assertEquals(mapOf("Java|KEYWORD" to "BOLD"), readPendingStyles(panel))
        assertEquals(mapOf("Java|KEYWORD" to "ITALIC"), readPendingEmphasis(panel))
    }

    @Test
    fun `opening the new panel preserves every pre-feature setting`() {
        // Break caught: passive dialog build and reset must never migrate or rewrite persisted userspace.
        stateBase.selectedPreset = "CUSTOM"
        stateBase.subordinatePreset = "NEON"
        stateBase.customOverrides["Java|KEYWORD"] = "72"
        stateBase.customStyles["Java|KEYWORD"] = "BOLD"
        stateBase.dimComments = true
        stateBase.quietOperators = true
        stateBase.schemaVersion = 3
        val beforeOverrides = stateBase.customOverrides.toMap()
        val beforeStyles = stateBase.customStyles.toMap()
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            syntaxPanel.reset()

            assertFalse(syntaxPanel.isModified())
            assertEquals("CUSTOM", stateBase.selectedPreset)
            assertEquals("NEON", stateBase.subordinatePreset)
            assertEquals(beforeOverrides, stateBase.customOverrides)
            assertEquals(beforeStyles, stateBase.customStyles)
            assertTrue(stateBase.customEmphasis.isEmpty())
            assertTrue(stateBase.dimComments)
            assertTrue(stateBase.quietOperators)
            assertEquals(3, stateBase.schemaVersion)
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `free named preset apply preserves all saved premium settings`() {
        // Break caught: runtime license normalization must not convert missing edit access into deletion permission
        // for either sparse maps or premium readability choices.
        every { LicenseChecker.isLicensedOrGrace() } returns false
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customOverrides["Java|KEYWORD"] = "72"
        stateBase.customStyles["Java|KEYWORD"] = "BOLD"
        stateBase.customEmphasis["Java|KEYWORD"] = "ITALIC"
        stateBase.dimComments = true
        stateBase.softenDocumentation = true
        stateBase.quietOperators = true
        stateBase.emphasizeDeclarations = true
        val beforeOverrides = stateBase.customOverrides.toMap()
        val beforeStyles = stateBase.customStyles.toMap()
        val beforeEmphasis = stateBase.customEmphasis.toMap()
        val syntaxPanel = panelWithLoadedState()

        invokeOnPresetChosen(syntaxPanel, SyntaxPreset.NEON)

        assertEquals(beforeOverrides, stateBase.customOverrides)
        assertEquals(beforeStyles, stateBase.customStyles)
        assertEquals(beforeEmphasis, stateBase.customEmphasis)
        assertTrue(stateBase.dimComments)
        assertTrue(stateBase.softenDocumentation)
        assertTrue(stateBase.quietOperators)
        assertTrue(stateBase.emphasizeDeclarations)
    }

    // ---------- Test 2 - pill selection previews without persistence ----------

    @Test
    fun `pill selection previews complete config without persistence`() {
        // Break caught: named preset clicks must preview through the complete configuration boundary.
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.NEON)

        verify(exactly = 1) {
            runtimeSession.preview(
                SyntaxPresetConfig(
                    selectedPreset = SyntaxPreset.NEON.name,
                    customOverrides = emptyMap(),
                    subordinatePreset = SyntaxPreset.NEON.name,
                ),
            )
        }
    }

    @Test
    fun `pill selection keeps selectedPreset pending until Apply`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.WHISPER)

        assertEquals("AMBIENT", stateBase.selectedPreset)
        assertSame(SyntaxPreset.WHISPER, readPendingPreset(panel))
        assertTrue(panel.isModified())
    }

    @Test
    fun `Apply persists a previewed pill and advances the stored buffer`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CYBERPUNK)

        assertTrue(panel.isModified())
        panel.apply()
        assertEquals("CYBERPUNK", stateBase.selectedPreset)
        assertFalse(panel.isModified())
    }

    // ---------- Test 3 - apply-FIRST persist-SECOND ordering ----------

    @Test
    fun `apply orders service call BEFORE state persistence (Anti-Pattern 4)`() {
        // Break caught: persistence must not advance before the runtime configuration succeeds.
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.NEON)

        panel.apply()

        verifyOrder {
            runtimeSession.materialize(any())
            stateService.state
        }
    }

    @Test
    fun `apply ordering - service throw leaves the complete stored snapshot unchanged`() {
        // Break caught: a failed runtime apply must not partially persist any pending field or map.
        stateBase.selectedPreset = "AMBIENT"
        stateBase.subordinatePreset = "WHISPER"
        stateBase.customOverrides["Java|KEYWORD"] = "72"
        stateBase.customStyles["Java|COMMENT"] = "PLAIN"
        stateBase.customEmphasis["Kotlin|STRING_LITERAL"] = "ITALIC"
        stateBase.dimComments = true
        stateBase.softenDocumentation = false
        stateBase.quietOperators = true
        stateBase.emphasizeDeclarations = false
        stateBase.schemaVersion = 3
        val initialOverrides = stateBase.customOverrides.toMap()
        val initialStyles = stateBase.customStyles.toMap()
        val initialEmphasis = stateBase.customEmphasis.toMap()
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.NEON)
        setSubordinateCyberpunk(panel)
        seedPendingOverride(panel, "Java|KEYWORD", "90")
        seedPendingOverride(panel, "Kotlin|COMMENT", "30")
        seedPendingStyle(panel, "Java|COMMENT", "BOLD")
        seedPendingEmphasis(panel, "Kotlin|STRING_LITERAL", "BOLD_ITALIC")
        writePendingBoolean(panel, "pendingDimComments", false)
        writePendingBoolean(panel, "pendingSoftenDocumentation", true)
        writePendingBoolean(panel, "pendingQuietOperators", false)
        writePendingBoolean(panel, "pendingEmphasizeDeclarations", true)
        every { runtimeSession.materialize(any()) } returns
            SyntaxTransactionResult.Failed(RuntimeException("simulated apply failure"), emptyList())

        assertFailsWith<RuntimeException> { panel.apply() }

        assertEquals("AMBIENT", stateBase.selectedPreset)
        assertEquals("WHISPER", stateBase.subordinatePreset)
        assertEquals(initialOverrides, stateBase.customOverrides)
        assertEquals(initialStyles, stateBase.customStyles)
        assertEquals(initialEmphasis, stateBase.customEmphasis)
        assertTrue(stateBase.dimComments)
        assertFalse(stateBase.softenDocumentation)
        assertTrue(stateBase.quietOperators)
        assertFalse(stateBase.emphasizeDeclarations)
        assertEquals(3, stateBase.schemaVersion)
    }

    // ---------- Test 4 - Custom rejection for unlicensed users ----------

    @Test
    fun `Custom pill rejected for unlicensed users - requestLicense fires, no service call, no persist`() {
        // Break caught: an unauthorized Custom selection must not reach either runtime or persistence.
        every { LicenseChecker.isLicensedOrGrace() } returns false
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CUSTOM)

        verify(exactly = 1) {
            LicenseChecker.requestLicense("Unlock per-language syntax customization")
        }
        verify(exactly = 0) { runtimeSession.preview(any()) }
        verify(exactly = 0) { runtimeSession.materialize(any()) }
        assertEquals("AMBIENT", stateBase.selectedPreset)
        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
    }

    // ---------- Test 5 - Custom accepted for licensed users ----------

    @Test
    fun `Custom pill previews for licensed users and persists on Apply`() {
        // Break caught: an authorized Custom selection must stay pending until the framework applies it.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CUSTOM)

        verify(exactly = 1) {
            runtimeSession.preview(
                SyntaxPresetConfig(
                    selectedPreset = SyntaxPreset.CUSTOM.name,
                    customOverrides = emptyMap(),
                ),
            )
        }
        assertEquals("AMBIENT", stateBase.selectedPreset)
        panel.apply()
        verify(exactly = 1) { runtimeSession.materialize(any()) }
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
        // Break caught: readability must travel in the preview config before state and schema persistence.
        stateBase.selectedPreset = "AMBIENT"
        stateBase.schemaVersion = 2
        val panel = panelWithLoadedState()
        writePendingBoolean(panel, "pendingDimComments", true)
        writePendingBoolean(panel, "pendingQuietOperators", true)

        panel.apply()

        verifyOrder {
            runtimeSession.materialize(
                SyntaxPresetConfig(
                    selectedPreset = SyntaxPreset.AMBIENT.name,
                    customOverrides = emptyMap(),
                    readabilityOptions = SyntaxReadabilityOptions(dimComments = true, quietOperators = true),
                ),
            )
            stateService.state
        }
        assertTrue(stateBase.dimComments)
        assertTrue(stateBase.quietOperators)
        assertFalse(stateBase.softenDocumentation)
        assertFalse(stateBase.emphasizeDeclarations)
        assertEquals(4, stateBase.schemaVersion)
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
        // Break caught: a real checkbox must preview pending readability and Reset must restore stored state.
        stateBase.selectedPreset = SyntaxPreset.AMBIENT.name
        val panel = AyuIslandsSyntaxPanel()

        try {
            val component = buildSyntaxPanel(panel)
            val dimComments = findDimCommentsCheckBox(component)
            io.mockk.clearMocks(runtimeSession, answers = false, recordedCalls = true)

            dimComments.doClick()

            verify(exactly = 1) {
                runtimeSession.preview(
                    SyntaxPresetConfig(
                        selectedPreset = SyntaxPreset.AMBIENT.name,
                        customOverrides = emptyMap(),
                        readabilityOptions = SyntaxReadabilityOptions(dimComments = true),
                    ),
                )
            }
            assertTrue(panel.isModified(), "toggling the real checkbox must dirty the syntax panel")

            io.mockk.clearMocks(runtimeSession, answers = false, recordedCalls = true)
            panel.reset()

            verify(exactly = 1) {
                runtimeSession.restore()
            }
            assertFalse(dimComments.isSelected, "reset must return the visible checkbox to stored state")
            assertFalse(panel.isModified(), "reset must leave pending and stored readability in sync")
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `unlicensed build shows readability controls disabled without preview writes`() {
        // Break caught: disabled premium readability controls must not produce a configuration apply.
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

            io.mockk.clearMocks(runtimeSession, answers = false, recordedCalls = true)
            findDimCommentsCheckBox(component).doClick()

            verify(exactly = 0) {
                runtimeSession.preview(any())
            }
            assertFalse(panel.isModified(), "disabled readability controls must not dirty the panel")
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `readability controls stay on one row`() {
        val panel = AyuIslandsSyntaxPanel()

        try {
            val component = buildSyntaxPanel(panel)
            component.size = component.preferredSize
            layoutRecursively(component)

            val readabilityControls =
                findCheckBoxes(component).filter { it.text in readabilityCheckboxTexts }
            val rowPositions =
                readabilityControls
                    .map { checkbox ->
                        val point =
                            SwingUtilities.convertPoint(
                                checkbox.parent,
                                checkbox.location,
                                component,
                            )
                        point.y
                    }.toSet()

            assertEquals(
                readabilityCheckboxTexts,
                readabilityControls.mapTo(linkedSetOf()) { it.text },
                "readability row must expose every toggle",
            )
            assertEquals(
                1,
                rowPositions.size,
                "readability toggles should stay on one settings row",
            )
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `reset disables readability controls when license flips to free`() {
        // Break caught: Reset after license loss must gate readability while retaining the normal preview boundary.
        stateBase.selectedPreset = SyntaxPreset.AMBIENT.name
        stateBase.dimComments = true
        val panel = AyuIslandsSyntaxPanel()

        try {
            val component = buildSyntaxPanel(panel)
            val dimComments = findDimCommentsCheckBox(component)
            assertTrue(dimComments.isEnabled, "licensed users can edit readability controls")

            every { LicenseChecker.isLicensedOrGrace() } returns false
            io.mockk.clearMocks(runtimeSession, answers = false, recordedCalls = true)

            panel.reset()

            assertFalse(dimComments.isEnabled, "reset must disable readability after license loss")
            assertFalse(dimComments.isSelected, "reset must hide persisted premium readability after license loss")

            verify(exactly = 0) {
                runtimeSession.restore()
            }
            io.mockk.clearMocks(runtimeSession, answers = false, recordedCalls = true)

            dimComments.doClick()

            verify(exactly = 0) {
                runtimeSession.preview(any())
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
        assertEquals("0", SyntaxIntensityReadout.signed(50, 50), "identity (50) reads as 0")
        assertEquals("+25", SyntaxIntensityReadout.signed(75, 50), "above identity reads +N")
        assertEquals(
            "\u221220",
            SyntaxIntensityReadout.signed(30, 50),
            "below identity reads \u2212N with U+2212 minus",
        )
        assertEquals("+50", SyntaxIntensityReadout.signed(100, 50), "max reads +50")
        assertEquals("\u221250", SyntaxIntensityReadout.signed(0, 50), "min reads \u221250")
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
    fun `primitive presentation catalog keeps semantic groups in two stable columns`() {
        assertEquals(
            listOf(
                listOf("Declarations", "Keywords & Docs"),
                listOf("Identifiers & Members", "Literals"),
            ),
            readCategoryColumns(),
        )
    }

    // ---------- Test 21c - readout color signals default vs moved ----------

    @Test
    fun `applyReadout leaves identity visually empty and strengthens a moved readout`() {
        val identityLabel = JLabel()
        val movedLabel = JLabel()

        SyntaxIntensityReadout.apply(identityLabel, 50, 50)
        SyntaxIntensityReadout.apply(movedLabel, 75, 50)

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
        val identityLabel = JLabel()
        val belowLabel = JLabel()

        SyntaxIntensityReadout.apply(identityLabel, 50, 50)
        SyntaxIntensityReadout.apply(belowLabel, 30, 50)

        assertEquals("\u221220", belowLabel.text, "below identity reads \u2212N with U+2212 minus")
        assertNotEquals(
            identityLabel.foreground.rgb,
            belowLabel.foreground.rgb,
            "a below-identity cell is 'moved' and must use the stronger foreground",
        )
    }

    // ---------- Test 22 - slider presentation contract ----------

    @Test
    fun `every materialized slider is tick-free and uses the intensity range`() {
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)

            PrimitiveCategory.entries.forEach { category ->
                val slider = readSlider(syntaxPanel, category)
                assertFalse(slider.paintTicks, "${category.name} must hide tick marks")
                assertFalse(slider.paintLabels, "${category.name} must hide tick labels")
                assertFalse(slider.snapToTicks, "${category.name} must move continuously")
                assertEquals(0, slider.minimum, "${category.name} must start at zero intensity")
                assertEquals(100, slider.maximum, "${category.name} must end at full intensity")
            }
        } finally {
            syntaxPanel.dispose()
        }
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
    fun `syntax controls do not implement platform spacing interfaces`() {
        val runtimeTypes = listOf(AyuIslandsSyntaxPanel::class.java, SyntaxControlGrid::class.java)

        runtimeTypes.forEach { runtimeType ->
            assertFalse(
                runtimeType.interfaces.any { it.simpleName == "SpacingConfiguration" },
                "${runtimeType.simpleName} must not delegate a version-sensitive spacing interface",
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
        // Break caught: slider previews must use pending config without writing the sparse state map.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)

        try {
            invokeOnJavaKeywordSliderChanged(panel, 80)
            invokePreview(panel)

            verify(exactly = 1) {
                runtimeSession.preview(
                    SyntaxPresetConfig(
                        selectedPreset = SyntaxPreset.CUSTOM.name,
                        customOverrides = mapOf("Java" to mapOf("KEYWORD" to 80)),
                    ),
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
        // Break caught: malformed flat cells must be excluded from the nested configuration boundary.
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
            runtimeSession.materialize(
                SyntaxPresetConfig(
                    selectedPreset = SyntaxPreset.CUSTOM.name,
                    customOverrides = mapOf("Java" to mapOf("KEYWORD" to 75)),
                ),
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

    @Test
    fun `language reset removes only confirmed visible cells and preserves opaque entries`() {
        every { intensityService.tunableCategories(AyuVariant.MIRAGE) } returns
            mapOf("Swift" to setOf(PrimitiveCategory.KEYWORD, PrimitiveCategory.OPERATOR))
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customOverrides["Swift|KEYWORD"] = "75"
        stateBase.customOverrides["Swift|STATIC_FIELD"] = "35"
        stateBase.customOverrides["Swift|FUTURE_PRIMITIVE"] = "19"
        stateBase.customStyles["Swift|KEYWORD"] = "BOLD"
        stateBase.customStyles["Swift|STATIC_FIELD"] = "ITALIC"
        stateBase.customEmphasis["Swift|FUTURE_PRIMITIVE"] = "BOLD"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            writeCurrentLanguage(syntaxPanel, "Swift")
            invokeRebind(syntaxPanel, "Swift")

            invokeOnResetCurrentLanguage(syntaxPanel)

            assertFalse(readPendingOverrides(syntaxPanel).containsKey("Swift|KEYWORD"))
            assertFalse(readPendingStyles(syntaxPanel).containsKey("Swift|KEYWORD"))
            assertEquals("35", readPendingOverrides(syntaxPanel)["Swift|STATIC_FIELD"])
            assertEquals("ITALIC", readPendingStyles(syntaxPanel)["Swift|STATIC_FIELD"])
            assertEquals("19", readPendingOverrides(syntaxPanel)["Swift|FUTURE_PRIMITIVE"])
            assertEquals("BOLD", readPendingEmphasis(syntaxPanel)["Swift|FUTURE_PRIMITIVE"])
        } finally {
            syntaxPanel.dispose()
        }
    }

    // ---------- Part B Test 32 - buildNested decodes styles and skips bad cells (via apply) ----------

    @Test
    fun `apply threads decoded font styles to the service and skips malformed style cells`() {
        // Break caught: legacy style tokens must remain a distinct validated configuration map.
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
            runtimeSession.materialize(
                SyntaxPresetConfig(
                    selectedPreset = SyntaxPreset.CUSTOM.name,
                    customOverrides = emptyMap(),
                    customStyles =
                        mapOf(
                            "Java" to
                                mapOf(
                                    "KEYWORD" to Font.BOLD,
                                    "STRING_LITERAL" to (Font.BOLD or Font.ITALIC),
                                ),
                        ),
                ),
            )
        }
    }

    // ---------- Part B Test 33 - stable trailing reset ----------

    @Test
    fun `trailing reset materializes as one stable lightweight slot`() {
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            val resetButton = readResetButton(syntaxPanel, PrimitiveCategory.KEYWORD)
            val resetSlot = resetButton.parent

            assertEquals(
                InplaceButton::class.java,
                resetButton.javaClass,
                "The reset control must remain a lightweight InplaceButton",
            )
            assertTrue(resetSlot.layout is GridLayout, "The reset control must occupy a stable grid slot")
            assertEquals(1, resetSlot.componentCount, "The trailing zone must reserve exactly one slot")
            assertSame(resetButton, resetSlot.getComponent(0), "The reset control must own the reserved slot")
            assertEquals(
                JBUI.scale(readPrivateConst("TRAILING_SLOT_SIDE")),
                resetSlot.preferredSize.height,
                "The reset slot height must remain stable",
            )
        } finally {
            syntaxPanel.dispose()
        }

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
        // Break caught: legacy styles must persist only after the configuration apply succeeds.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.CUSTOM)
        seedPendingStyle(panel, "Java|KEYWORD", "BOLD")
        seedPendingStyle(panel, "Java|STRING_LITERAL", "ITALIC")

        panel.apply()

        verifyOrder {
            // Service call first.
            runtimeSession.materialize(any())
            // Then the persistence reads state.
            stateService.state
        }
        assertEquals(
            mapOf("Java|KEYWORD" to "BOLD", "Java|STRING_LITERAL" to "ITALIC"),
            stateBase.customStyles,
            "apply must persist pendingStyles into state.customStyles (clear + putAll).",
        )
    }

    @Test
    fun `emphasis event previews a separate pending layer and persists only on Apply`() {
        // Break caught: the additive Aa control must neither rewrite legacy replacement styles
        // nor persist during preview.
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customStyles["Kotlin|FUNCTION_DECL"] = "BOLD"
        val syntaxPanel = panelWithLoadedState()
        writeCurrentLanguage(syntaxPanel, "Kotlin")

        try {
            setFunctionEmphasis(syntaxPanel, FontEmphasis.ITALIC)

            assertEquals(
                mapOf("Kotlin|FUNCTION_DECL" to "ITALIC"),
                readPendingEmphasis(syntaxPanel),
            )
            assertEquals(mapOf("Kotlin|FUNCTION_DECL" to "BOLD"), readPendingStyles(syntaxPanel))
            assertEquals(mapOf("Kotlin|FUNCTION_DECL" to "BOLD"), stateBase.customStyles)
            assertTrue(stateBase.customEmphasis.isEmpty(), "pending checkbox edits must not persist before Apply")

            io.mockk.clearMocks(runtimeSession, answers = false, recordedCalls = true)
            invokePreview(syntaxPanel)
            val previewConfig = io.mockk.slot<SyntaxPresetConfig>()
            verify(exactly = 1) { runtimeSession.preview(capture(previewConfig)) }
            assertEquals(
                mapOf("Kotlin" to mapOf("FUNCTION_DECL" to Font.BOLD)),
                previewConfig.captured.customStyles,
            )
            assertEquals(
                mapOf("Kotlin" to mapOf("FUNCTION_DECL" to Font.ITALIC)),
                previewConfig.captured.customEmphasis,
            )
            assertTrue(stateBase.customEmphasis.isEmpty(), "preview must remain pending-only")

            syntaxPanel.apply()

            assertEquals("ITALIC", stateBase.customEmphasis["Kotlin|FUNCTION_DECL"])
            assertEquals("BOLD", stateBase.customStyles["Kotlin|FUNCTION_DECL"])
            assertEquals(4, stateBase.schemaVersion)
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `regular style override stays pending until Apply and previews as exact plain`() {
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customStyles["Kotlin|KEYWORD"] = "BOLD"
        val syntaxPanel = panelWithLoadedState()
        writeCurrentLanguage(syntaxPanel, "Swift")

        try {
            setPlainOperatorStyle(syntaxPanel)

            assertEquals("PLAIN", readPendingStyles(syntaxPanel)["Swift|OPERATOR"])
            assertEquals("BOLD", readPendingStyles(syntaxPanel)["Kotlin|KEYWORD"])
            assertFalse(stateBase.customStyles.containsKey("Swift|OPERATOR"))

            io.mockk.clearMocks(runtimeSession, answers = false, recordedCalls = true)
            invokePreview(syntaxPanel)
            val previewConfig = io.mockk.slot<SyntaxPresetConfig>()
            verify(exactly = 1) { runtimeSession.preview(capture(previewConfig)) }
            assertEquals(
                Font.PLAIN,
                previewConfig.captured.customStyles["Swift"]?.get("OPERATOR"),
            )
            assertFalse(stateBase.customStyles.containsKey("Swift|OPERATOR"))

            syntaxPanel.apply()

            assertEquals("PLAIN", stateBase.customStyles["Swift|OPERATOR"])
            assertEquals("BOLD", stateBase.customStyles["Kotlin|KEYWORD"])
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `clearing emphasis returns to inherited state without clearing legacy style`() {
        // Break caught: reaching Aa must remove only the additive sparse cell, never the legacy customStyles token.
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customStyles["Kotlin|FUNCTION_DECL"] = "BOLD"
        stateBase.customEmphasis["Kotlin|FUNCTION_DECL"] = "ITALIC"
        val syntaxPanel = panelWithLoadedState()
        writeCurrentLanguage(syntaxPanel, "Kotlin")

        try {
            setFunctionEmphasis(syntaxPanel, null)

            assertFalse(readPendingEmphasis(syntaxPanel).containsKey("Kotlin|FUNCTION_DECL"))
            assertEquals("BOLD", readPendingStyles(syntaxPanel)["Kotlin|FUNCTION_DECL"])
            assertEquals("BOLD", stateBase.customStyles["Kotlin|FUNCTION_DECL"])
            assertEquals("ITALIC", stateBase.customEmphasis["Kotlin|FUNCTION_DECL"])
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `reset reloads stored emphasis and restores its glyph`() {
        // Break caught: Cancel or Reset must discard pending emphasis and rebind the visible glyph from stored state.
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customEmphasis["Kotlin|FUNCTION_DECL"] = "BOLD"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            setFunctionEmphasis(syntaxPanel, FontEmphasis.ITALIC)
            assertEquals("I", functionStyleGlyph(syntaxPanel))

            syntaxPanel.reset()

            assertEquals("BOLD", readPendingEmphasis(syntaxPanel)["Kotlin|FUNCTION_DECL"])
            assertEquals("B", functionStyleGlyph(syntaxPanel))
            assertFalse(syntaxPanel.isModified())
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `category reset clears all three dimensions for only that cell`() {
        // Break caught: a row reset must include emphasis without widening into neighboring
        // categories or legacy layers elsewhere.
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = panelWithLoadedState()
        writeCurrentLanguage(syntaxPanel, "Java")
        seedWidgets(syntaxPanel, PrimitiveCategory.KEYWORD)
        seedPendingOverride(syntaxPanel, "Java|KEYWORD", "80")
        seedPendingStyle(syntaxPanel, "Java|KEYWORD", "BOLD")
        seedPendingEmphasis(syntaxPanel, "Java|KEYWORD", "ITALIC")
        seedPendingEmphasis(syntaxPanel, "Java|COMMENT", "BOLD")

        try {
            invokeResetKeywordCell(syntaxPanel)

            assertFalse(readPendingOverrides(syntaxPanel).containsKey("Java|KEYWORD"))
            assertFalse(readPendingStyles(syntaxPanel).containsKey("Java|KEYWORD"))
            assertFalse(readPendingEmphasis(syntaxPanel).containsKey("Java|KEYWORD"))
            assertEquals("BOLD", readPendingEmphasis(syntaxPanel)["Java|COMMENT"])
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `language reset clears emphasis only for the active language`() {
        // Break caught: language reset must include the new layer but retain every other language prefix.
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = panelWithLoadedState()
        writeCurrentLanguage(syntaxPanel, "Java")
        seedPendingEmphasis(syntaxPanel, "Java|KEYWORD", "ITALIC")
        seedPendingEmphasis(syntaxPanel, "Kotlin|KEYWORD", "BOLD")

        try {
            invokeOnResetCurrentLanguage(syntaxPanel)

            assertFalse(readPendingEmphasis(syntaxPanel).keys.any { it.startsWith("Java|") })
            assertEquals("BOLD", readPendingEmphasis(syntaxPanel)["Kotlin|KEYWORD"])
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `language capability rebind disables only unavailable controls and preserves every sparse store`() {
        every { intensityService.tunableCategories(AyuVariant.MIRAGE) } returns
            mapOf("Swift" to setOf(PrimitiveCategory.FUNCTION_DECL))
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customOverrides["Swift|KEYWORD"] = "82"
        stateBase.customStyles["Swift|KEYWORD"] = "ITALIC"
        stateBase.customEmphasis["Swift|KEYWORD"] = "BOLD"
        val persistedOverrides = stateBase.customOverrides.toMap()
        val persistedStyles = stateBase.customStyles.toMap()
        val persistedEmphasis = stateBase.customEmphasis.toMap()
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            val pendingOverrides = readPendingOverrides(syntaxPanel)
            val pendingStyles = readPendingStyles(syntaxPanel)
            val pendingEmphasis = readPendingEmphasis(syntaxPanel)
            val storedOverrides = readStoredOverrides(syntaxPanel)
            val storedStyles = readStoredStyles(syntaxPanel)
            val storedEmphasis = readStoredEmphasis(syntaxPanel)

            writeCurrentLanguage(syntaxPanel, "Swift")
            invokeRebind(syntaxPanel, "Swift")
            invokeRefreshMasterResetButton(syntaxPanel)

            assertTrue(readSlider(syntaxPanel, PrimitiveCategory.FUNCTION_DECL).isEnabled)
            assertFalse(readSlider(syntaxPanel, PrimitiveCategory.KEYWORD).isEnabled)
            assertFalse(readStyleControl(syntaxPanel, PrimitiveCategory.KEYWORD).component.isEnabled)
            assertFalse(keywordLabel(syntaxPanel).isEnabled)
            assertFalse(
                readMasterResetButton(syntaxPanel).isVisible,
                "Hidden sparse cells must survive without exposing a reset action that cannot affect them",
            )
            assertEquals(pendingOverrides, readPendingOverrides(syntaxPanel))
            assertEquals(pendingStyles, readPendingStyles(syntaxPanel))
            assertEquals(pendingEmphasis, readPendingEmphasis(syntaxPanel))
            assertEquals(storedOverrides, readStoredOverrides(syntaxPanel))
            assertEquals(storedStyles, readStoredStyles(syntaxPanel))
            assertEquals(storedEmphasis, readStoredEmphasis(syntaxPanel))
            assertEquals(persistedOverrides, stateBase.customOverrides)
            assertEquals(persistedStyles, stateBase.customStyles)
            assertEquals(persistedEmphasis, stateBase.customEmphasis)
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `capability discovery failure keeps every existing row enabled`() {
        every { intensityService.tunableCategories(AyuVariant.MIRAGE) } returns null
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)

            PrimitiveCategory.entries.forEach { category ->
                assertTrue(readSlider(syntaxPanel, category).isEnabled, "$category must fail open")
                assertTrue(
                    readStyleControl(syntaxPanel, category).component.isEnabled,
                    "$category style must fail open",
                )
            }
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `capability snapshot without active language keeps every row unavailable`() {
        every { intensityService.tunableCategories(AyuVariant.MIRAGE) } returns
            mapOf("Kotlin" to PrimitiveCategory.entries.toSet())
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            writeCurrentLanguage(syntaxPanel, "Swift")
            invokeRebind(syntaxPanel, "Swift")

            PrimitiveCategory.entries.forEach { category ->
                assertFalse(readSlider(syntaxPanel, category).isEnabled, "$category must remain unavailable")
                assertFalse(
                    readStyleControl(syntaxPanel, category).component.isEnabled,
                    "$category style must remain unavailable",
                )
            }
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `language capability rebind restores controls without losing preserved values`() {
        every { intensityService.tunableCategories(AyuVariant.MIRAGE) } returns
            mapOf(
                "Swift" to setOf(PrimitiveCategory.FUNCTION_DECL),
                "Kotlin" to PrimitiveCategory.entries.toSet(),
            )
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customOverrides["Swift|KEYWORD"] = "82"
        stateBase.customStyles["Swift|KEYWORD"] = "ITALIC"
        stateBase.customEmphasis["Swift|KEYWORD"] = "BOLD"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            val beforeOverrides = readPendingOverrides(syntaxPanel)
            val beforeStyles = readPendingStyles(syntaxPanel)
            val beforeEmphasis = readPendingEmphasis(syntaxPanel)

            writeCurrentLanguage(syntaxPanel, "Swift")
            invokeRebind(syntaxPanel, "Swift")
            assertFalse(readSlider(syntaxPanel, PrimitiveCategory.KEYWORD).isEnabled)
            assertFalse(readStyleControl(syntaxPanel, PrimitiveCategory.KEYWORD).component.isEnabled)

            writeCurrentLanguage(syntaxPanel, "Kotlin")
            invokeRebind(syntaxPanel, "Kotlin")

            assertTrue(readSlider(syntaxPanel, PrimitiveCategory.KEYWORD).isEnabled)
            assertTrue(readStyleControl(syntaxPanel, PrimitiveCategory.KEYWORD).component.isEnabled)
            assertEquals(beforeOverrides, readPendingOverrides(syntaxPanel))
            assertEquals(beforeStyles, readPendingStyles(syntaxPanel))
            assertEquals(beforeEmphasis, readPendingEmphasis(syntaxPanel))
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `preview adopts a new capability snapshot without changing sparse stores`() {
        val restricted = mapOf("Swift" to setOf(PrimitiveCategory.FUNCTION_DECL))
        every { intensityService.tunableCategories(AyuVariant.MIRAGE) } returnsMany listOf(null, restricted)
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customOverrides["Swift|KEYWORD"] = "82"
        stateBase.customStyles["Swift|KEYWORD"] = "ITALIC"
        stateBase.customEmphasis["Swift|KEYWORD"] = "BOLD"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            writeCurrentLanguage(syntaxPanel, "Swift")
            invokeRebind(syntaxPanel, "Swift")
            val beforeOverrides = readPendingOverrides(syntaxPanel)
            val beforeStyles = readPendingStyles(syntaxPanel)
            val beforeEmphasis = readPendingEmphasis(syntaxPanel)
            assertTrue(readSlider(syntaxPanel, PrimitiveCategory.KEYWORD).isEnabled)

            invokePreview(syntaxPanel)

            assertFalse(readSlider(syntaxPanel, PrimitiveCategory.KEYWORD).isEnabled)
            assertFalse(readStyleControl(syntaxPanel, PrimitiveCategory.KEYWORD).component.isEnabled)
            assertEquals(beforeOverrides, readPendingOverrides(syntaxPanel))
            assertEquals(beforeStyles, readPendingStyles(syntaxPanel))
            assertEquals(beforeEmphasis, readPendingEmphasis(syntaxPanel))
            assertEquals("82", stateBase.customOverrides["Swift|KEYWORD"])
            assertEquals("ITALIC", stateBase.customStyles["Swift|KEYWORD"])
            assertEquals("BOLD", stateBase.customEmphasis["Swift|KEYWORD"])
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `foreign scheme warning remains until an Ayu preview succeeds`() {
        val syntaxPanel = panelWithLoadedState()
        val status = readRuntimeStatus(syntaxPanel)

        try {
            schemeName = "Solarized Dark"
            invokePreview(syntaxPanel)

            assertTrue(status.isVisible)
            assertTrue(status.text.contains("Select an Ayu scheme to resume"))

            schemeName = "Ayu Islands Mirage"
            invokePreview(syntaxPanel)

            assertFalse(status.isVisible)
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `confirmed native capability shows only verified rows and preserves sparse stores`() {
        stateBase.selectedPreset = "CUSTOM"
        stateBase.customOverrides["Kotlin|KEYWORD"] = "82"
        stateBase.customStyles["Kotlin|KEYWORD"] = "ITALIC"
        stateBase.customEmphasis["Kotlin|KEYWORD"] = "BOLD"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            buildFullSyntaxPanel(syntaxPanel)
            val beforeOverrides = readPendingOverrides(syntaxPanel)
            val beforeStyles = readPendingStyles(syntaxPanel)
            val beforeEmphasis = readPendingEmphasis(syntaxPanel)

            syntaxPanel.activateCapabilityForTest(confirmingProbe(setOf(PrimitiveCategory.FUNCTION_DECL)))

            assertTrue(readSlider(syntaxPanel, PrimitiveCategory.FUNCTION_DECL).isVisible)
            assertTrue(readSlider(syntaxPanel, PrimitiveCategory.FUNCTION_DECL).isEnabled)
            assertFalse(readSlider(syntaxPanel, PrimitiveCategory.KEYWORD).isVisible)
            assertFalse(readSlider(syntaxPanel, PrimitiveCategory.KEYWORD).isEnabled)
            assertEquals(beforeOverrides, readPendingOverrides(syntaxPanel))
            assertEquals(beforeStyles, readPendingStyles(syntaxPanel))
            assertEquals(beforeEmphasis, readPendingEmphasis(syntaxPanel))
            assertEquals("82", stateBase.customOverrides["Kotlin|KEYWORD"])
            assertEquals("ITALIC", stateBase.customStyles["Kotlin|KEYWORD"])
            assertEquals("BOLD", stateBase.customEmphasis["Kotlin|KEYWORD"])
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `missing plugin replaces plain preview with actionable recovery`() {
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = AyuIslandsSyntaxPanel()
        val dialogPanel = buildFullSyntaxPanel(syntaxPanel)

        try {
            syntaxPanel.activateCapabilityForTest(missingPluginProbe())

            val preview = assertNotNull(findComponent(dialogPanel, SyntaxPreviewComponent::class.java))
            val editor = assertNotNull(findComponent(preview, EditorTextField::class.java))
            val recovery = assertNotNull(findLabel(preview, PLUGIN_INSTALL_INSTRUCTION))
            assertFalse(editor.isVisible)
            assertTrue(recovery.isVisible)
            PrimitiveCategory.entries.forEach { category ->
                assertFalse(readSlider(syntaxPanel, category).isVisible)
            }
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `semantic-only absence gives recovery guidance without exposing the hidden row`() {
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = AyuIslandsSyntaxPanel()
        val dialogPanel = buildFullSyntaxPanel(syntaxPanel)

        try {
            syntaxPanel.activateCapabilityForTest(
                confirmingProbe(
                    categories = setOf(PrimitiveCategory.KEYWORD),
                    conditionalAbsences =
                        listOf(
                            ConditionalAbsence(
                                PrimitiveCategory.OPERATOR,
                                "Requires semantic highlighting",
                            ),
                        ),
                ),
            )

            assertTrue(readSlider(syntaxPanel, PrimitiveCategory.KEYWORD).isVisible)
            assertFalse(readSlider(syntaxPanel, PrimitiveCategory.OPERATOR).isVisible)
            assertNotNull(
                findLabel(
                    dialogPanel,
                    "Some controls need semantic highlighting. Enable it for Kotlin " +
                        "under Editor | Color Scheme, then return here.",
                ),
            )
        } finally {
            syntaxPanel.dispose()
        }
    }

    @Test
    fun `every category keeps slider and style paired before readout and reset`() {
        // Break caught: the Aa glyph must stay paired with its slider, exactly 8px away, before readout and reset.
        stateBase.selectedPreset = "CUSTOM"
        val syntaxPanel = AyuIslandsSyntaxPanel()

        try {
            val component = buildFullSyntaxPanel(syntaxPanel)
            PrimitiveCategory.entries.forEach { category ->
                readResetButton(syntaxPanel, category).isVisible = true
            }
            component.size = component.preferredSize
            layoutRecursively(component)

            for (category in PrimitiveCategory.entries) {
                val slider = readSlider(syntaxPanel, category)
                val styleControl = readStyleControl(syntaxPanel, category)
                val pairContainer = slider.parent
                val readout = readSliderLabel(syntaxPanel, category)
                val resetButton = readResetButton(syntaxPanel, category)
                val sliderCenter =
                    SwingUtilities.convertPoint(
                        pairContainer,
                        Point(slider.x + slider.width, slider.y + slider.height / 2),
                        component,
                    )
                val styleCenter =
                    SwingUtilities.convertPoint(
                        styleControl.component.parent,
                        Point(
                            styleControl.component.x,
                            styleControl.component.y + styleControl.component.height / 2,
                        ),
                        component,
                    )

                assertSame(
                    pairContainer,
                    styleControl.component.parent,
                    "${category.name} must share one direct parent",
                )
                assertEquals(JBUI.scale(8), styleCenter.x - sliderCenter.x, "${category.name} must use exact gap")
                assertEquals(sliderCenter.y, styleCenter.y, "${category.name} must share the slider centerline")
                assertFalse(
                    SwingUtilities.isDescendingFrom(readout, pairContainer),
                    "${category.name} readout must stay outside the slider/glyph pair",
                )
                assertFalse(
                    SwingUtilities.isDescendingFrom(resetButton, pairContainer),
                    "${category.name} reset must stay outside the slider/glyph pair",
                )

                val pairRightPoint =
                    SwingUtilities.convertPoint(
                        pairContainer.parent,
                        Point(pairContainer.x + pairContainer.width, pairContainer.y),
                        component,
                    )
                val pairRight = pairRightPoint.x
                val readoutLeft =
                    SwingUtilities.convertPoint(readout.parent, Point(readout.x, readout.y), component).x
                val readoutRightPoint =
                    SwingUtilities.convertPoint(
                        readout.parent,
                        Point(readout.x + readout.width, readout.y),
                        component,
                    )
                val readoutRight = readoutRightPoint.x
                val resetLeft =
                    SwingUtilities.convertPoint(resetButton.parent, Point(resetButton.x, resetButton.y), component).x
                assertTrue(pairRight <= readoutLeft, "${category.name} readout must follow the slider/glyph pair")
                assertTrue(readoutRight <= resetLeft, "${category.name} reset must follow the readout")
            }
        } finally {
            syntaxPanel.dispose()
        }
    }

    // ---------- Test 18 - debounce behavior (INTENSITY-13 / D-19, behavioral) ----------

    @Test
    fun `editing session uses one single-shot 100ms debounce without persisting`() {
        // Break caught: debounced edits must not call the runtime configuration boundary synchronously.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)

        try {
            val timer = readDebounceTimer(panel)
            assertFalse(timer.isRepeats, "D-19: the debounce timer must be single-shot.")
            assertEquals(100, timer.delay, "D-19: the debounce window must be exactly 100ms.")

            // Clear the apply call recorded by panelWithLoadedState() and any
            // earlier setup so we observe only the slider-change path.
            io.mockk.clearMocks(runtimeSession, answers = false, recordedCalls = true)
            invokeOnJavaKeywordSliderChanged(panel, 80)

            verify(exactly = 0) {
                runtimeSession.preview(any())
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
        val load = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("loadStateIntoPending")
        load.isAccessible = true
        load.invoke(panel)
        val open = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("openEditingSession")
        open.isAccessible = true
        open.invoke(panel)
        return panel
    }

    private fun buildFullSyntaxPanel(syntaxPanel: AyuIslandsSyntaxPanel): DialogPanel =
        panel {
            syntaxPanel.buildPanel(this, AyuVariant.MIRAGE)
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

    private fun confirmingProbe(
        categories: Set<PrimitiveCategory>,
        conditionalAbsences: List<ConditionalAbsence> = emptyList(),
    ): SyntaxCapabilityProbe =
        object : SyntaxCapabilityProbe {
            override fun start(
                specification: LanguageSpecification,
                generation: Long,
                parent: Disposable,
                completed: (SyntaxProbeResult) -> Unit,
            ) {
                completed(
                    SyntaxProbeResult.Confirmed(
                        languageId = specification.storageId,
                        generation = generation,
                        evidence =
                            SyntaxCapabilityEvidence(
                                languageId = specification.storageId,
                                confirmedCells = categories,
                                conditionalAbsences = conditionalAbsences,
                            ),
                    ),
                )
            }
        }

    private fun missingPluginProbe(): SyntaxCapabilityProbe =
        object : SyntaxCapabilityProbe {
            override fun start(
                specification: LanguageSpecification,
                generation: Long,
                parent: Disposable,
                completed: (SyntaxProbeResult) -> Unit,
            ) {
                completed(
                    SyntaxProbeResult.MissingPlugin(
                        languageId = specification.storageId,
                        generation = generation,
                        recovery = PluginRecovery(),
                    ),
                )
            }
        }

    private fun findLabel(
        container: Container,
        text: String,
    ): JLabel? =
        container.components.firstNotNullOfOrNull { component ->
            when (component) {
                is JLabel -> component.takeIf { it.text == text }
                is Container -> findLabel(component, text)
                else -> null
            }
        }

    private fun <T> findComponent(
        container: Container,
        type: Class<T>,
    ): T? =
        container.components.firstNotNullOfOrNull { component ->
            when {
                type.isInstance(component) -> type.cast(component)
                component is Container -> findComponent(component, type)
                else -> null
            }
        }

    private fun layoutRecursively(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutRecursively)
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

    private fun readRuntimeStatus(panel: AyuIslandsSyntaxPanel): JLabel {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("runtimeStatus")
        field.isAccessible = true
        return (field.get(panel) as RuntimeStatus).component
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

    private fun setSubordinateCyberpunk(panel: AyuIslandsSyntaxPanel) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingSubordinate")
        field.isAccessible = true
        field.set(panel, SyntaxPreset.CYBERPUNK)
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

    private fun readStoredOverrides(panel: AyuIslandsSyntaxPanel): Map<String, String> =
        readStringMapField(panel, "storedOverrides")

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

    private fun readStoredStyles(panel: AyuIslandsSyntaxPanel): Map<String, String> =
        readStringMapField(panel, "storedStyles")

    private fun seedPendingStyle(
        panel: AyuIslandsSyntaxPanel,
        key: String,
        value: String,
    ) {
        val map = pendingStylesField(panel)
        val putMethod = map.javaClass.getMethod("put", Any::class.java, Any::class.java)
        putMethod.invoke(map, key, value)
    }

    private fun pendingEmphasisField(panel: AyuIslandsSyntaxPanel): MutableMap<*, *> {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingEmphasis")
        field.isAccessible = true
        return field.get(panel) as MutableMap<*, *>
    }

    private fun readPendingEmphasis(panel: AyuIslandsSyntaxPanel): Map<String, String> =
        pendingEmphasisField(panel).entries.associate { (key, value) ->
            (key as String) to (value as String)
        }

    private fun readStoredEmphasis(panel: AyuIslandsSyntaxPanel): Map<String, String> =
        readStringMapField(panel, "storedEmphasis")

    private fun readStringMapField(
        panel: AyuIslandsSyntaxPanel,
        fieldName: String,
    ): Map<String, String> {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        val values = field.get(panel) as Map<*, *>
        return values.entries.associate { (key, value) ->
            (key as String) to (value as String)
        }
    }

    private fun seedPendingEmphasis(
        panel: AyuIslandsSyntaxPanel,
        key: String,
        value: String,
    ) {
        val map = pendingEmphasisField(panel)
        val putMethod = map.javaClass.getMethod("put", Any::class.java, Any::class.java)
        putMethod.invoke(map, key, value)
    }

    private fun setFunctionEmphasis(
        panel: AyuIslandsSyntaxPanel,
        emphasis: FontEmphasis?,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "onEmphasisChanged",
                PrimitiveCategory::class.java,
                FontEmphasis::class.java,
            )
        method.isAccessible = true
        method.invoke(panel, PrimitiveCategory.FUNCTION_DECL, emphasis)
    }

    private fun setPlainOperatorStyle(panel: AyuIslandsSyntaxPanel) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "onStyleOverrideChanged",
                PrimitiveCategory::class.java,
                FontStyleOverride::class.java,
            )
        method.isAccessible = true
        method.invoke(panel, PrimitiveCategory.OPERATOR, FontStyleOverride.PLAIN)
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
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("previewDiscrete")
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

    private fun readStyleControl(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
    ): SyntaxStyleControl {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("styleControls")
        field.isAccessible = true
        val controls = field.get(panel) as Map<*, *>
        return controls[category] as SyntaxStyleControl
    }

    private fun functionStyleGlyph(panel: AyuIslandsSyntaxPanel): String {
        val icon = readStyleControl(panel, PrimitiveCategory.FUNCTION_DECL).component.icon as StyleGlyphIcon
        val glyphField = StyleGlyphIcon::class.java.getDeclaredField("glyph")
        glyphField.isAccessible = true
        return glyphField.get(icon) as String
    }

    private fun readSlider(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
    ): JSlider {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("sliders")
        field.isAccessible = true
        val sliders = field.get(panel) as Map<*, *>
        return sliders[category] as JSlider
    }

    private fun keywordLabel(panel: AyuIslandsSyntaxPanel): JLabel {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("categoryLabels")
        field.isAccessible = true
        val labels = field.get(panel) as Map<*, *>
        return labels[PrimitiveCategory.KEYWORD] as JLabel
    }

    private fun readSliderLabel(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
    ): JLabel {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("sliderLabels")
        field.isAccessible = true
        val labels = field.get(panel) as Map<*, *>
        return labels[category] as JLabel
    }

    private fun readResetButton(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
    ): InplaceButton {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("resetButtons")
        field.isAccessible = true
        val buttons = field.get(panel) as Map<*, *>
        return buttons[category] as InplaceButton
    }

    private fun readMasterResetButton(panel: AyuIslandsSyntaxPanel): JButton {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("masterResetButton")
        field.isAccessible = true
        return field.get(panel) as JButton
    }

    private data class SeededWidgets(
        val slider: JSlider,
        val label: JLabel,
        val resetButton: InplaceButton,
        val button: JButton,
    )

    private fun invokeRebindSlidersForJava(panel: AyuIslandsSyntaxPanel) {
        invokeRebind(panel, "Java")
    }

    private fun invokeRebind(
        panel: AyuIslandsSyntaxPanel,
        language: String,
    ) {
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("rebindSlidersFor", String::class.java)
        method.isAccessible = true
        method.invoke(panel, language)
    }

    /**
     * Reflect the private `CATEGORY_GROUPS` companion list into a stable
     * `List<Pair<title, categories>>` shape the coverage-invariant test can
     * assert against without depending on the private `CategoryGroup` type.
     */
    private fun readCategoryGroups(): List<Pair<String, List<PrimitiveCategory>>> {
        // A private val on a private companion compiles to a private static
        // backing field on the OUTER class with no getter, so read it directly.
        val groupsField = SyntaxControlGrid::class.java.getDeclaredField("CATEGORY_GROUPS")
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

    private fun readCategoryColumns(): List<List<String>> {
        val columnsField = SyntaxControlGrid::class.java.getDeclaredField("COLUMN_GROUPS")
        columnsField.isAccessible = true
        val columns = columnsField.get(null) as List<*>
        return columns.map { column ->
            (column as List<*>).map { group ->
                requireNotNull(group)
                val titleMethod = group.javaClass.getDeclaredMethod("getTitle")
                titleMethod.isAccessible = true
                titleMethod.invoke(group) as String
            }
        }
    }

    private fun readLabelColumnWidth(panel: AyuIslandsSyntaxPanel): Int {
        val gridField = AyuIslandsSyntaxPanel::class.java.getDeclaredField("controlGrid")
        gridField.isAccessible = true
        val grid = gridField.get(panel)
        val getter = SyntaxControlGrid::class.java.getDeclaredMethod("getLabelColumnWidth")
        getter.isAccessible = true
        return getter.invoke(grid) as Int
    }

    /**
     * Read the private companion `READOUT_WIDTH` const (a private static field
     * on the outer class) and return it scaled by [JBUI.scale], matching the
     * runtime width the readout cell is pinned to.
     */
    private fun readReadoutWidthScaled(): Int {
        val field = SyntaxControlGrid::class.java.getDeclaredField("READOUT_WIDTH")
        field.isAccessible = true
        return JBUI.scale(field.getInt(null))
    }

    /** Read any of the panel's private companion `Int` constants by name. */
    private fun readPrivateConst(name: String): Int {
        val field = SyntaxControlGrid::class.java.getDeclaredField(name)
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

    private fun readDebounceTimer(panel: AyuIslandsSyntaxPanel): Timer {
        val sessionField = AyuIslandsSyntaxPanel::class.java.getDeclaredField("editingSession")
        sessionField.isAccessible = true
        val panelSession = checkNotNull(sessionField.get(panel))
        val editingField = SyntaxPanelSession::class.java.getDeclaredField("editing")
        editingField.isAccessible = true
        val session = editingField.get(panelSession)
        val debounceField = SyntaxEditingSession::class.java.getDeclaredField("debounce")
        debounceField.isAccessible = true
        val debounce = debounceField.get(session)
        val timerField = debounce.javaClass.getDeclaredField("timer")
        timerField.isAccessible = true
        return timerField.get(debounce) as Timer
    }
}
