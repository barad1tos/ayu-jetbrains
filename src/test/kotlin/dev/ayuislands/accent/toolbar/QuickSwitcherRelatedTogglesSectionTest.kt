package dev.ayuislands.accent.toolbar

import com.intellij.openapi.ui.DialogPanel
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JCheckBox
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plan 48-04 Task 1 — locks the related-toggles section's contract:
 *   - exactly four `JCheckBox` descendants in fixed order (D-09 + D-13),
 *   - bound to the four `AyuIslandsState` fields with single-source-of-truth
 *     read/write through `bindSelected` (D-13),
 *   - no manual `AccentApplicator.applyFromHexString` call from inside the
 *     `bindSelected` callbacks (Pattern G adjacency — the existing cascade
 *     handles state propagation),
 *   - the per-checkbox bind site appears at least four times in the source
 *     (Pattern Q self-verify regression lock).
 *
 * Field-mapping note: "Chrome tinting" binds to `chromeStatusBar` because
 * `AyuIslandsState` has no aggregated `chromeTintEnabled` Boolean; see the
 * KDoc on `QuickSwitcherRelatedTogglesSection` for the rationale.
 */
class QuickSwitcherRelatedTogglesSectionTest {
    @BeforeTest
    fun setUp() {
        // Wire `AyuIslandsSettings.getInstance()` to a relaxed mock whose
        // `state` returns a real `AyuIslandsState` instance — the BaseState-
        // backed property delegation needs the real instance for round-trip
        // semantics to fire correctly.
        val state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `component is a non-null DialogPanel (root of the Kotlin UI DSL panel)`() {
        // Test 1 — `component` is typed as JComponent; the runtime instance is a
        // DialogPanel because the DSL `panel { ... }` returns one.
        val section = QuickSwitcherRelatedTogglesSection()
        assertNotNull(section.component)
        assertTrue(section.component is DialogPanel, "component must be a DialogPanel")
    }

    @Test
    fun `section contains exactly four JCheckBox descendants`() {
        // Test 2
        val section = QuickSwitcherRelatedTogglesSection()
        val boxes = collectCheckBoxes(section.component)
        val labels = boxes.map { it.text }
        assertEquals(EXPECTED_TOGGLE_COUNT, boxes.size, "Expected $EXPECTED_TOGGLE_COUNT checkboxes, got $labels")
    }

    @Test
    fun `checkbox labels are in fixed top-to-bottom order`() {
        // Test 3
        val section = QuickSwitcherRelatedTogglesSection()
        val boxes = collectCheckBoxes(section.component)
        val labels = boxes.map { it.text }
        val expected = listOf("Chrome tinting", "Glow", "Accent rotation", "Follow system accent")
        assertEquals(expected, labels, "Toggle labels drifted from D-09 / D-13 order")
    }

    @Test
    fun `bindSelected reads back the matching state field at construction time`() {
        // Test 4 — single-source-of-truth read path
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = false
        state.glowEnabled = true
        state.accentRotationEnabled = false
        state.followSystemAccent = true

        val section = QuickSwitcherRelatedTogglesSection()
        val boxes = collectCheckBoxes(section.component).associateBy { it.text }

        assertEquals(false, boxes["Chrome tinting"]?.isSelected)
        assertEquals(true, boxes["Glow"]?.isSelected)
        assertEquals(false, boxes["Accent rotation"]?.isSelected)
        assertEquals(true, boxes["Follow system accent"]?.isSelected)
    }

    @Test
    fun `bindSelected writes back to the matching state field on apply`() {
        // Test 5 — single-source-of-truth write path
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = false
        state.glowEnabled = false
        state.accentRotationEnabled = false
        state.followSystemAccent = false

        val section = QuickSwitcherRelatedTogglesSection()
        val boxes = collectCheckBoxes(section.component).associateBy { it.text }
        boxes["Chrome tinting"]?.isSelected = true
        boxes["Glow"]?.isSelected = true
        boxes["Accent rotation"]?.isSelected = true
        boxes["Follow system accent"]?.isSelected = true

        // DialogPanel.apply() commits pending bindSelected writes to backing state.
        (section.component as DialogPanel).apply()

        assertEquals(true, state.chromeStatusBar)
        assertEquals(true, state.glowEnabled)
        assertEquals(true, state.accentRotationEnabled)
        assertEquals(true, state.followSystemAccent)
    }

    @Test
    fun `source carries at least four bindSelected calls (Pattern Q regression lock)`() {
        // Test 6
        val source =
            Files.readString(
                Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherRelatedTogglesSection.kt"),
            )
        val count = "bindSelected".toRegex().findAll(source).count()
        assertTrue(count >= EXPECTED_TOGGLE_COUNT, "Expected ≥$EXPECTED_TOGGLE_COUNT bindSelected calls, got $count")
    }

    @Test
    fun `source has zero AccentApplicator calls (Pattern G adjacency lock)`() {
        // Test 7 — the bindSelected callbacks MUST NOT explicitly invoke
        // `AccentApplicator.applyFromHexString`; the existing LafManager /
        // state-observation cascade handles state propagation. A future
        // careless edit that adds a manual apply call would double-apply.
        val source =
            Files.readString(
                Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherRelatedTogglesSection.kt"),
            )
        val applyCalls = "AccentApplicator\\.".toRegex().findAll(source).count()
        val applyHex = "applyFromHexString".toRegex().findAll(source).count()
        assertEquals(0, applyCalls, "Must NOT reference AccentApplicator (Pattern G)")
        assertEquals(0, applyHex, "Must NOT call applyFromHexString (Pattern G)")
    }

    private companion object {
        const val EXPECTED_TOGGLE_COUNT = 4

        private fun collectCheckBoxes(
            root: Container,
            out: MutableList<JCheckBox> = mutableListOf(),
        ): List<JCheckBox> {
            for (child in root.components) {
                if (child is JCheckBox) out.add(child)
                if (child is Container) collectCheckBoxes(child, out)
            }
            return out
        }
    }
}
