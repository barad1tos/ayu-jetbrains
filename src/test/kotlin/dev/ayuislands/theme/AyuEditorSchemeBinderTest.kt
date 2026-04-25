package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavior coverage for [AyuEditorSchemeBinder]. The binder is invoked from
 * `AyuIslandsLafListener.lookAndFeelChanged` BEFORE
 * `AccentApplicator.applyForFocusedProject` so the scheme swap lands first
 * and AccentApplicator's in-place mutation targets the correct (Ayu) scheme
 * — restoring revertAll symmetry.
 *
 * Pattern J — gates verified explicitly. Pattern G — apply path locked
 * (revert path is intentionally out of scope per binder KDoc).
 */
class AyuEditorSchemeBinderTest {
    private val mockEcm = mockk<EditorColorsManager>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockEcm
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ── targetSchemeName: naming contract ─────────────────────────────────

    @Test
    fun `targetSchemeName maps MIRAGE to Ayu Islands Mirage`() {
        assertEquals("Ayu Islands Mirage", AyuEditorSchemeBinder.targetSchemeName(AyuVariant.MIRAGE))
    }

    @Test
    fun `targetSchemeName maps DARK to Ayu Islands Dark`() {
        assertEquals("Ayu Islands Dark", AyuEditorSchemeBinder.targetSchemeName(AyuVariant.DARK))
    }

    @Test
    fun `targetSchemeName maps LIGHT to Ayu Islands Light`() {
        assertEquals("Ayu Islands Light", AyuEditorSchemeBinder.targetSchemeName(AyuVariant.LIGHT))
    }

    // ── bindForVariant: happy paths ───────────────────────────────────────

    @Test
    fun `bindForVariant switches when current is platform Default`() {
        val targetScheme = mockScheme("Ayu Islands Mirage")
        every { mockEcm.globalScheme } returns mockScheme("Default")
        every { mockEcm.allSchemes } returns arrayOf(targetScheme)
        every { mockEcm.setGlobalScheme(any()) } just Runs

        val switched = AyuEditorSchemeBinder.bindForVariant(AyuVariant.MIRAGE)

        assertTrue(switched, "Default → Ayu Mirage transition must apply scheme")
        verify(exactly = 1) { mockEcm.setGlobalScheme(targetScheme) }
    }

    @Test
    fun `bindForVariant switches when current is another Ayu scheme`() {
        val targetScheme = mockScheme("Ayu Islands Dark")
        every { mockEcm.globalScheme } returns mockScheme("Ayu Islands Mirage")
        every { mockEcm.allSchemes } returns arrayOf(mockScheme("Ayu Islands Mirage"), targetScheme)
        every { mockEcm.setGlobalScheme(any()) } just Runs

        val switched = AyuEditorSchemeBinder.bindForVariant(AyuVariant.DARK)

        assertTrue(switched, "Ayu Mirage → Ayu Dark transition must swap schemes")
        verify(exactly = 1) { mockEcm.setGlobalScheme(targetScheme) }
    }

    // ── bindForVariant: gate paths ────────────────────────────────────────

    @Test
    fun `bindForVariant is no-op when current already matches target`() {
        every { mockEcm.globalScheme } returns mockScheme("Ayu Islands Light")

        val switched = AyuEditorSchemeBinder.bindForVariant(AyuVariant.LIGHT)

        assertFalse(switched, "Already-correct scheme must not be reapplied")
        verify(exactly = 0) { mockEcm.setGlobalScheme(any()) }
    }

    @Test
    fun `bindForVariant skips when current is user-custom non-Ayu scheme`() {
        every { mockEcm.globalScheme } returns mockScheme("Solarized (Light)")

        val switched = AyuEditorSchemeBinder.bindForVariant(AyuVariant.MIRAGE)

        assertFalse(
            switched,
            "User intent is sacred — custom non-Ayu, non-platform scheme MUST NOT be overwritten",
        )
        verify(exactly = 0) { mockEcm.setGlobalScheme(any()) }
    }

    @Test
    fun `bindForVariant skips when target scheme is missing from EditorColorsManager`() {
        every { mockEcm.globalScheme } returns mockScheme("Default")
        every { mockEcm.allSchemes } returns arrayOf(mockScheme("Default"))

        val switched = AyuEditorSchemeBinder.bindForVariant(AyuVariant.DARK)

        assertFalse(
            switched,
            "Plugin install incomplete (target scheme missing) must not throw — graceful no-op",
        )
        verify(exactly = 0) { mockEcm.setGlobalScheme(any()) }
    }

    // ── NEUTRAL_SCHEMES allowlist contract ────────────────────────────────

    @Test
    fun `NEUTRAL_SCHEMES contains all three Ayu Islands variants and platform defaults`() {
        val expected =
            setOf(
                "Ayu Islands Mirage",
                "Ayu Islands Dark",
                "Ayu Islands Light",
                "Default",
                "Darcula",
                "IntelliJ Light",
                "Light",
                "Dark",
            )
        assertEquals(
            expected,
            AyuEditorSchemeBinder.NEUTRAL_SCHEMES,
            "NEUTRAL_SCHEMES is the allowlist; adding/removing entries must be deliberate",
        )
    }

    @Test
    fun `bindForVariant treats common third-party schemes as user-custom`() {
        every { mockEcm.allSchemes } returns arrayOf(mockScheme("Ayu Islands Mirage"))
        for (custom in listOf("Solarized (Light)", "Material Oceanic", "MyCustomScheme")) {
            every { mockEcm.globalScheme } returns mockScheme(custom)
            assertFalse(
                AyuEditorSchemeBinder.bindForVariant(AyuVariant.MIRAGE),
                "Foreign scheme '$custom' must NOT be overwritten by binder",
            )
        }
        verify(exactly = 0) { mockEcm.setGlobalScheme(any()) }
    }

    private fun mockScheme(name: String): EditorColorsScheme {
        val s = mockk<EditorColorsScheme>(relaxed = true)
        every { s.name } returns name
        return s
    }
}
