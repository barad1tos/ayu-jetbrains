package dev.ayuislands.vcs

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.util.Properties

/** Pure palette preparation; native ownership and rollback are exercised by StudioVcsParticipantTest. */
class VcsColorApplierTest {
    private val mockScheme = mockk<EditorColorsScheme>(relaxed = true)
    private val mockColorsManager = mockk<EditorColorsManager>(relaxed = true)
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val metadata = Properties()
    private val colors = mutableMapOf<ColorKey, Color?>()
    private val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>()

    @BeforeEach
    fun setUp() {
        metadata.clear()
        colors.clear()
        attributes.clear()
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager
        every { mockColorsManager.globalScheme } returns mockScheme
        every { mockScheme.name } returns "Ayu Islands Mirage"
        every { mockScheme.metaProperties } returns metadata
        every { mockScheme.getColor(any<ColorKey>()) } answers { colors[firstArg()] }
        every { mockScheme.setColor(any<ColorKey>(), any()) } answers { colors[firstArg()] = secondArg() }
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } answers { attributes[firstArg()] }
        every { mockScheme.setAttributes(any<TextAttributesKey>(), any()) } answers {
            attributes[firstArg()] = secondArg()
        }

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        // Run `invokeLater { ... }` body synchronously so the applier's EDT
        // hop becomes inline — keeps the test single-threaded.
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } answers {
            dev.ayuislands.studio.StudioFixtures
                .legacyView(state)
                .settings
        }
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `prepare uses explicit choices without legacy context reads or native writes`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        every { AyuVariant.detect() } returns AyuVariant.DARK
        state.vcsColorEnabled = false
        val snapshot = VcsColorSnapshot(true, mapOf(VcsColorCategory.DIFF_VIEWER to 100))
        val prepared =
            VcsColorContext.withSnapshot(VcsColorSnapshot(false, emptyMap())) {
                VcsColorApplier.prepare(mockScheme, snapshot, AyuVariant.LIGHT, isLicensed = true)
            }
        val palette = VcsColorPalette.allCategoriesAndEntries().values.flatten()
        assertEquals(palette.map { it.mode to it.keyName }.toSet().size, prepared.size)
        val entry =
            VcsColorPalette
                .allCategoriesAndEntries()
                .getValue(VcsColorCategory.DIFF_VIEWER)
                .first { it.mode == VcsWriteMode.COLOR_KEY }
        val expected = VcsColorPalette.endpoints(entry, AyuVariant.LIGHT).second
        val value = prepared.getValue(SchemeEntry.ColorEntry(ColorKey.find(entry.keyName))) as SchemeValue.ColorValue
        assertEquals(expected.rgb, value.value?.rgb)
        assertEquals(mapOf(VcsColorCategory.DIFF_VIEWER to 100), snapshot.perCategoryIntensities)
        verify(exactly = 0) { LicenseChecker.isLicensedOrGrace() }
        verify(exactly = 0) { AyuVariant.detect() }
        verify(exactly = 0) { AyuIslandsSettings.getInstance() }
        verify(exactly = 0) { EditorColorsManager.getInstance() }
        verify(exactly = 0) { mockApplication.invokeLater(any()) }
        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
        assertTrue(metadata.isEmpty)
    }

    @Test
    fun `prepare preserves complete attribute bundles and leaves the original unchanged`() {
        val original = layeredAttributes()
        val flyweight = original.flyweight
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns original
        val snapshot = VcsColorSnapshot(true, emptyMap())

        val prepared = VcsColorApplier.prepare(mockScheme, snapshot, AyuVariant.MIRAGE, isLicensed = true)

        val replacements = prepared.values.filterIsInstance<SchemeValue.AttributesValue>()
        assertTrue(replacements.isNotEmpty())
        for (replacement in replacements) {
            val plannedAttributes = requireNotNull(replacement.value)
            assertNotSame(original, plannedAttributes)
            assertEquals(original, plannedAttributes.clone().apply { backgroundColor = original.backgroundColor })
        }
        assertSame(flyweight, original.flyweight)
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `disabled or unavailable VCS prepares no palette writes and retains the requested intensities`() {
        val choices = mapOf(VcsColorCategory.DIFF_VIEWER to 999)
        for ((enabled, licensed) in listOf(false to true, true to false)) {
            val snapshot = VcsColorSnapshot(enabled, choices)
            assertTrue(VcsColorApplier.prepare(mockScheme, snapshot, AyuVariant.MIRAGE, licensed).isEmpty())
            assertEquals(choices, snapshot.perCategoryIntensities)
            assertEquals(enabled, snapshot.enabled)
        }
        verify(exactly = 0) { mockScheme.getAttributes(any<TextAttributesKey>()) }
        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    private fun layeredAttributes(): TextAttributes =
        TextAttributes
            .merge(
                TextAttributes(Color(0x44223355, true), Color.BLUE, Color.RED, EffectType.BOXED, Font.BOLD),
                TextAttributes(null, null, Color.GREEN, EffectType.LINE_UNDERSCORE, Font.ITALIC),
            ).apply { errorStripeColor = Color.YELLOW }
}
