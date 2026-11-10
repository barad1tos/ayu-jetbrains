package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.ModifiableFontPreferences
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.FontReceiptIdentity
import dev.ayuislands.studio.session.StudioFontWrite
import org.junit.Rule
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioFontWriteTest {
    @get:Rule val applicationRule: ApplicationRule = ApplicationRule()

    private val identity = FontReceiptIdentity(ID, FontSurface.EDITOR, "attempt")

    @Test
    fun `live scheme replacement and duplicate identity cannot receive native writes or restore authority`() {
        SwingUtilities.invokeAndWait {
            val original = scheme()
            val write = capture(original)
            val replacement = scheme()
            assertFailsWith<IllegalStateException> { write.apply(census(replacement)) }
            assertEquals(snapshot(original), snapshot(replacement))
            write.apply(census(original))
            val applied = snapshot(original)
            write.prepareRestore(census(original, replacement), emptyList())
            assertFalse(write.restore(census(original, replacement), emptyList()))
            assertEquals(applied, snapshot(original))
            assertTrue(write.receipts.all { it.status == RestoreStatus.UNAVAILABLE })
            write.prepareRestore(census(replacement), emptyList())
            assertFalse(write.restore(census(replacement), emptyList()))
            write.prepareRestore(census(original), emptyList())
            assertTrue(write.restore(census(original), emptyList()))
            assertEquals(snapshot(replacement), snapshot(original))
        }
    }

    @Test
    fun `native readback rejects a listener change and rollback preserves the foreign group`() {
        SwingUtilities.invokeAndWait {
            val original = scheme()
            val before = snapshot(original)
            val write = capture(original)
            original.changeSpacing = true
            assertFailsWith<IllegalStateException> { write.apply(census(original)) }
            write.prepareRestore(census(original), emptyList())
            assertTrue(write.restore(census(original), emptyList()))
            val expected = FontSnapshot.Explicit(before.preferences.copy(lineSpacing = 1.9f))
            assertEquals(expected, snapshot(original))
            assertTrue(write.receipts.any { it.status == RestoreStatus.FOREIGN })
        }
    }

    @Test
    fun `recovery waits for a unique UUID and then binds once while terminal proof needs no target`() {
        SwingUtilities.invokeAndWait {
            val original = scheme()
            val before = snapshot(original)
            val write = capture(original)
            val uncertain = write.receipts
            write.apply(census(original))
            val recovery = StudioFontWrite.recover(identity, uncertain)
            recovery.prepareRestore(census(), emptyList())
            assertFalse(recovery.restore(census(), emptyList()))
            val reloaded = scheme().apply { fontPreferences = original.fontPreferences }
            recovery.prepareRestore(census(reloaded), emptyList())
            assertTrue(recovery.restore(census(reloaded), emptyList()))
            assertEquals(before, snapshot(reloaded))
            val settled = StudioFontWrite.recover(identity, recovery.receipts)
            assertTrue(settled.restore(census(), emptyList()))
            assertEquals(recovery.receipts, settled.receipts)
        }
    }

    @Test
    fun `unapplied live attempt can settle without reacquiring a removed scheme`() {
        SwingUtilities.invokeAndWait {
            val write = capture(scheme())
            assertTrue(write.restore(census(), emptyList()))
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
        }
    }

    private fun scheme(): ObservedFontScheme =
        ObservedFontScheme().apply {
            name = "Native Font"
            metaProperties.setProperty(FontIdentityRules.METADATA_KEY, ID)
            fontPreferences = FontPreferencesImpl().apply { register("Dialog", 13f) }
        }

    private fun snapshot(source: ObservedFontScheme): FontSnapshot.Explicit =
        FontSnapshot.Explicit(FontData.captureExact(source.fontPreferences))

    private fun capture(source: ObservedFontScheme): StudioFontWrite {
        val target =
            snapshot(source).preferences.copy(
                effectiveFamilies = listOf("Serif"),
                families = listOf(FontFamily("Serif", 19f)),
                templateSize = 19f,
                lineSpacing = 1.4f,
            )
        return StudioFontWrite.capture(identity, source, FontSnapshot.Explicit(target))
    }

    private fun census(vararg sources: ObservedFontScheme): FontSchemeCensus =
        FontSchemeCensus(sources.toList().toTypedArray<EditorColorsScheme>())

    private companion object {
        const val ID = "11800832-b0dc-44ab-a989-d8d333013fbe"
    }
}

private class ObservedFontScheme : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
    var changeSpacing = false

    override fun setFontPreferences(preferences: FontPreferences) {
        super.setFontPreferences(preferences)
        if (changeSpacing) {
            changeSpacing = false
            (super.getFontPreferences() as ModifiableFontPreferences).lineSpacing = 1.9f
        }
    }
}
