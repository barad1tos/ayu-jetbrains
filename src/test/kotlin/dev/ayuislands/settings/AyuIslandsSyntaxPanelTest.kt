package dev.ayuislands.settings

import dev.ayuislands.syntax.StyleAxis
import dev.ayuislands.syntax.SyntaxModeBaseState
import dev.ayuislands.syntax.SyntaxModeService
import dev.ayuislands.syntax.SyntaxModeState
import dev.ayuislands.syntax.SyntaxMood
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JRadioButton
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [AyuIslandsSyntaxPanel] (Phase 49, Plan 49-03).
 *
 * Coverage targets:
 *  - D-02 default mood (MAXIMUM) on null `state.mood`.
 *  - Apply-before-persist ordering (Anti-Pattern #4): [SyntaxModeService.apply]
 *    MUST run BEFORE the state.mood / state.axes mutation.
 *  - Null-safe `reset()` (warning #8 fix): calling reset() BEFORE buildPanel()
 *    must not throw.
 *  - Pattern L source-regex regression locks for SYNTAX-08 (no [LicenseChecker]
 *    references in the panel source) and SYNTAX-12 (browserLink to JetBrains
 *    Color Scheme help page).
 *
 * Plain kotlin.test + MockK. No platform fixture; the panel's buildPanel
 * uses the Kotlin UI DSL whose direct invocation requires an EDT-managed
 * DialogPanel, so the tests exercise apply / reset / isModified on freshly
 * constructed instances and verify behavior via the mocked service / state.
 */
class AyuIslandsSyntaxPanelTest {
    private lateinit var stateBase: SyntaxModeBaseState
    private lateinit var stateService: SyntaxModeState
    private lateinit var modeService: SyntaxModeService

    @BeforeTest
    fun setUp() {
        stateBase = SyntaxModeBaseState()
        stateService = mockk(relaxed = true)
        every { stateService.state } returns stateBase
        mockkObject(SyntaxModeState.Companion)
        every { SyntaxModeState.getInstance() } returns stateService

        modeService = mockk(relaxed = true)
        mockkObject(SyntaxModeService.Companion)
        every { SyntaxModeService.getInstance() } returns modeService
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ---------- loadStateIntoPending (via reflection on private helper) ----------

    private fun panelWithLoadedState(): AyuIslandsSyntaxPanel {
        val panel = AyuIslandsSyntaxPanel()
        // The private helper `loadStateIntoPending` is invoked by buildPanel.
        // Tests invoke it directly via reflection to avoid the EDT-dependent DSL.
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("loadStateIntoPending")
        method.isAccessible = true
        method.invoke(panel)
        return panel
    }

    @Test
    fun `loadStateIntoPending initializes pendingMood from state via fromName (D-02 default = MAXIMUM)`() {
        stateBase.mood = null
        val panel = panelWithLoadedState()
        assertSame(SyntaxMood.MAXIMUM, readPendingMood(panel))
        assertSame(SyntaxMood.MAXIMUM, readStoredMood(panel))
    }

    @Test
    fun `loadStateIntoPending uses stored mood enum name`() {
        stateBase.mood = "STANDARD"
        val panel = panelWithLoadedState()
        assertSame(SyntaxMood.STANDARD, readPendingMood(panel))
    }

    @Test
    fun `loadStateIntoPending initializes pendingAxes filtering unknown enum names`() {
        stateBase.mood = "MAXIMUM"
        stateBase.axes.clear()
        stateBase.axes.addAll(setOf("ITALIC_DECLARATIONS", "BOGUS"))
        val panel = panelWithLoadedState()
        val pending = readPendingAxes(panel)
        assertEquals(setOf(StyleAxis.ITALIC_DECLARATIONS), pending)
    }

    // ---------- isModified ----------

    @Test
    fun `isModified returns false when nothing changed`() {
        stateBase.mood = "MAXIMUM"
        val panel = panelWithLoadedState()
        assertFalse(panel.isModified())
    }

    @Test
    fun `isModified returns true after mood change in pending buffer`() {
        stateBase.mood = "MAXIMUM"
        val panel = panelWithLoadedState()
        writePendingMood(panel, SyntaxMood.MINIMAL)
        assertTrue(panel.isModified())
    }

    @Test
    fun `isModified returns true after axis add in pending buffer`() {
        stateBase.mood = "MAXIMUM"
        val panel = panelWithLoadedState()
        readPendingAxes(panel) // ensure init
        writePendingAxes(panel, mutableSetOf(StyleAxis.DIMMED_COMMENTS))
        assertTrue(panel.isModified())
    }

    // ---------- apply ----------

    @Test
    fun `apply when not modified is no-op (no service call)`() {
        stateBase.mood = "MAXIMUM"
        val panel = panelWithLoadedState()
        panel.apply()
        verify(exactly = 0) { modeService.apply(any(), any()) }
    }

    @Test
    fun `apply calls SyntaxModeService apply BEFORE persisting state (apply-before-persist invariant)`() {
        stateBase.mood = "MAXIMUM"
        val panel = panelWithLoadedState()
        writePendingMood(panel, SyntaxMood.RICH)

        panel.apply()

        verifyOrder {
            // 1. Service call lands FIRST (Anti-Pattern #4)
            modeService.apply(SyntaxMood.RICH, any())
            // 2. State.state is read for persistence (post-apply)
            stateService.state
        }
    }

    @Test
    fun `apply persists mood name as enum string`() {
        stateBase.mood = "MAXIMUM"
        val panel = panelWithLoadedState()
        writePendingMood(panel, SyntaxMood.MINIMAL)
        panel.apply()
        assertEquals("MINIMAL", stateBase.mood)
    }

    @Test
    fun `apply persists axes as Set of enum names`() {
        stateBase.mood = "MAXIMUM"
        val panel = panelWithLoadedState()
        writePendingAxes(
            panel,
            mutableSetOf(StyleAxis.ITALIC_DECLARATIONS, StyleAxis.DIMMED_COMMENTS),
        )
        panel.apply()
        assertEquals(setOf("ITALIC_DECLARATIONS", "DIMMED_COMMENTS"), stateBase.axes.toSet())
    }

    @Test
    fun `apply updates stored buffers so subsequent isModified returns false`() {
        stateBase.mood = "MAXIMUM"
        val panel = panelWithLoadedState()
        writePendingMood(panel, SyntaxMood.RICH)
        panel.apply()
        assertFalse(panel.isModified(), "after apply the stored == pending so isModified is false")
    }

    // ---------- reset ----------

    @Test
    fun `reset reverts pendingMood and pendingAxes to stored values`() {
        stateBase.mood = "STANDARD"
        stateBase.axes.clear()
        stateBase.axes.addAll(setOf("ITALIC_DECLARATIONS"))
        val panel = panelWithLoadedState()
        // Seed a single radio + checkbox so reset() passes the moodRadios.isEmpty()
        // null-guard and exercises the buffer-revert + UI-refresh path. The
        // separate `reset_before_buildPanel_is_noop_does_not_throw` test covers
        // the deviated-lifecycle no-op contract.
        seedSingleRadio(panel)
        writePendingMood(panel, SyntaxMood.MAXIMUM)
        writePendingAxes(panel, mutableSetOf(StyleAxis.BOLD_TYPE_REFERENCES))

        panel.reset()

        assertSame(SyntaxMood.STANDARD, readPendingMood(panel))
        assertEquals(setOf(StyleAxis.ITALIC_DECLARATIONS), readPendingAxes(panel))
    }

    @Test
    fun `reset before buildPanel is noop does not throw (warning 8 fix)`() {
        // Construct a panel WITHOUT calling buildPanel() (simulates deviated
        // Configurable lifecycle: reset before first build). The moodRadios /
        // axisCheckboxes maps are empty, so reset() must hit the null-guard
        // and return early without mutating anything.
        val panel = AyuIslandsSyntaxPanel()
        panel.reset() // Must NOT throw.
        // Default-constructed pendingMood is MAXIMUM per D-02; reset must not
        // change it.
        assertSame(SyntaxMood.MAXIMUM, readPendingMood(panel))
    }

    // ---------- Pattern L source-regex regression locks ----------

    @Test
    fun `panel source contains no LicenseChecker references (SYNTAX-08 Pattern L lock)`() {
        val source = readPanelSource()
        assertFalse(
            source.contains("LicenseChecker"),
            "SYNTAX-08: Syntax tab is a FREE feature (D-01) — no LicenseChecker references allowed",
        )
    }

    @Test
    fun `panel source contains browserLink to JetBrains Color Scheme docs (SYNTAX-12)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("browserLink"),
            "SYNTAX-12: panel must contain a browserLink pointer",
        )
        assertTrue(
            source.contains("https://www.jetbrains.com/help/idea/configuring-colors-and-fonts.html"),
            "SYNTAX-12: browserLink URL must point at the JetBrains Color Scheme help page",
        )
    }

    @Test
    fun `panel source references all 4 SyntaxMood entries`() {
        val source = readPanelSource()
        // The panel iterates SyntaxMood.entries which transitively covers all 4 moods.
        assertTrue(
            source.contains("SyntaxMood.entries"),
            "panel must iterate SyntaxMood.entries (covers MINIMAL/STANDARD/RICH/MAXIMUM)",
        )
    }

    @Test
    fun `panel source references all 4 StyleAxis entries`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("StyleAxis.entries"),
            "panel must iterate StyleAxis.entries (covers ITALIC_DECLARATIONS / BOLD_TYPE_REFERENCES " +
                "/ DIMMED_COMMENTS / ITALIC_DOC_TAGS)",
        )
    }

    // ---------- Reflection helpers ----------

    private fun readPendingMood(panel: AyuIslandsSyntaxPanel): SyntaxMood {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingMood")
        field.isAccessible = true
        return field.get(panel) as SyntaxMood
    }

    private fun readStoredMood(panel: AyuIslandsSyntaxPanel): SyntaxMood {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("storedMood")
        field.isAccessible = true
        return field.get(panel) as SyntaxMood
    }

    private fun writePendingMood(
        panel: AyuIslandsSyntaxPanel,
        mood: SyntaxMood,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingMood")
        field.isAccessible = true
        field.set(panel, mood)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readPendingAxes(panel: AyuIslandsSyntaxPanel): Set<StyleAxis> {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingAxes")
        field.isAccessible = true
        return (field.get(panel) as MutableSet<StyleAxis>).toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun writePendingAxes(
        panel: AyuIslandsSyntaxPanel,
        axes: MutableSet<StyleAxis>,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingAxes")
        field.isAccessible = true
        val current = field.get(panel) as MutableSet<StyleAxis>
        current.clear()
        current.addAll(axes)
    }

    private fun readPanelSource(): String =
        Files.readString(
            Path.of("src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt"),
        )

    /**
     * Seeds a single entry into the private moodRadios map so reset() bypasses
     * the warning-#8 null-guard and exercises the buffer-revert path. A real
     * JRadioButton is used; reset() only sets `isSelected` on it which is
     * harmless off-EDT for a never-displayed Swing component.
     */
    @Suppress("UNCHECKED_CAST")
    private fun seedSingleRadio(panel: AyuIslandsSyntaxPanel) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("moodRadios")
        field.isAccessible = true
        val map = field.get(panel) as MutableMap<SyntaxMood, JRadioButton>
        map[SyntaxMood.MAXIMUM] = JRadioButton()
    }
}
