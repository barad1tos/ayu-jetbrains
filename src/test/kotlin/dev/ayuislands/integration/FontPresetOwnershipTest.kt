package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import org.junit.Rule
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Native userspace contracts retained after removal of the legacy global font writer. */
class FontPresetOwnershipTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test fun `disable without ownership preserves personal fonts`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            fixture.disable()
            sameFonts(original, fixture.scheme)
        }

    @Test fun `managed disable restores exact editor and optional console preferences`() =
        withFonts { fixture ->
            for (console in listOf(false, true)) {
                val original = clone(fixture.scheme)
                fixture.apply(font(FontPreset.AMBIENT, console))
                assertEquals(FontPreset.AMBIENT.fontFamily, fixture.scheme.editorFontName)
                if (!console) {
                    FontNativeFixture.samePreferences(
                        original.consoleFontPreferences,
                        fixture.scheme.consoleFontPreferences,
                    )
                }
                fixture.disable()
                sameFonts(original, fixture.scheme)
            }
        }

    @Test fun `manual editor changes survive managed disable and release ownership`() =
        withFonts { fixture ->
            fixture.apply(font())
            fixture.scheme.setEditorFontSize(23.5f)
            val edited = clone(fixture.scheme)
            fixture.disable()
            sameFonts(edited, fixture.scheme)
        }

    @Test fun `repeated apply and raw settings reload retain the original baseline`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            fixture.apply(font())
            fixture.reload()
            fixture.apply(font(FontPreset.WHISPER))
            fixture.reload()
            fixture.disable()
            sameFonts(original, fixture.scheme)
        }

    @Test fun `foreign edit needs fresh review before another apply and remains untouched`() =
        withFonts { fixture ->
            fixture.apply(font())
            fixture.scheme.setEditorFontSize(23.5f)
            val edited = clone(fixture.scheme)
            val records = fixture.state.fontOwnershipSnapshots.toMap()
            fixture.reload()
            assertFailsWith<IllegalStateException> { fixture.apply(font(FontPreset.WHISPER)) }
            sameFonts(edited, fixture.scheme)
            assertEquals(records, fixture.state.fontOwnershipSnapshots)
            fixture.disable()
            sameFonts(edited, fixture.scheme)
        }

    @Test fun `unknown preset is unavailable while disabled cleanup still restores known ownership`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            fixture.apply(font(FontPreset.WHISPER, true))
            val applied = clone(fixture.scheme)
            val unknown =
                fixture.copyState().apply {
                    fontPresetEnabled = true
                    fontPresetName = "FUTURE_PRESET"
                    fontPresetCustomizations["FUTURE_PRESET"] = "opaque|future|customization"
                }
            val raw = unknown.fontPresetCustomizations.toMap()
            assertFailsWith<IllegalStateException> { fixture.accept(unknown) }
            sameFonts(applied, fixture.scheme)
            assertEquals(raw, unknown.fontPresetCustomizations)
            unknown.fontPresetEnabled = false
            fixture.accept(unknown)
            sameFonts(original, fixture.scheme)
            assertEquals(raw, unknown.fontPresetCustomizations)
        }

    @Test fun `disabling console restores only console while a new editor preset remains owned`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            fixture.apply(font(console = true))
            fixture.apply(font(FontPreset.WHISPER))
            FontNativeFixture.samePreferences(original.consoleFontPreferences, fixture.scheme.consoleFontPreferences)
            assertEquals(FontPreset.WHISPER.fontFamily, fixture.scheme.editorFontName)
            fixture.disable()
            sameFonts(original, fixture.scheme)
        }

    @Test fun `manual console edits survive editor restoration`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            fixture.apply(font(console = true))
            fixture.scheme.consoleFontPreferences = FontNativeFixture.preferences("Serif", 21.75f)
            val manual = FontData.captureExact(fixture.scheme.consoleFontPreferences)
            fixture.disable()
            FontNativeFixture.samePreferences(original.fontPreferences, fixture.scheme.fontPreferences)
            assertEquals(manual, FontData.captureExact(fixture.scheme.consoleFontPreferences))
        }

    @Test fun `editor and console inheritance survive apply reload and disable`() =
        withFonts { fixture ->
            fixture.scheme.setUseAppFontPreferencesInEditor()
            fixture.scheme.setUseEditorFontPreferencesInConsole()
            val original = clone(fixture.scheme)
            fixture.apply(font(console = true))
            assertTrue(fixture.scheme.isUseEditorFontPreferencesInConsole)
            fixture.reload()
            fixture.disable()
            sameFonts(original, fixture.scheme)
        }

    @Test fun `global console preferences and hidden local preferences are never overwritten`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            val global = FontNativeFixture.preferences("Serif", 22.5f)
            val before = FontData.captureExact(global)
            var globalVisible = true
            val native =
                object : EditorColorsSchemeImpl(fixture.scheme) {
                    override fun getConsoleFontPreferences(): FontPreferences =
                        if (globalVisible) global else super.getConsoleFontPreferences()
                }
            (fixture.scheme as AbstractColorsScheme).copyTo(native)
            fixture.replace(native)
            fixture.apply(font(console = true))
            globalVisible = false
            FontNativeFixture.samePreferences(original.consoleFontPreferences, native.consoleFontPreferences)
            globalVisible = true
            fixture.disable()
            assertEquals(before, FontData.captureExact(global))
            globalVisible = false
            sameFonts(original, native)
        }

    @Test fun `corrupt and future ownership records retain exact raw bytes and native preferences`() =
        withFonts { fixture ->
            fixture.apply(font())
            val applied = clone(fixture.scheme)
            val key =
                fixture.state.fontOwnershipSnapshots.keys
                    .single()
            val raw = fixture.state.fontOwnershipSnapshots.getValue(key)
            val unknown =
                listOf(
                    raw.replaceFirst("<font-ownership ", "<font-ownership xmlns:x=\"urn:future\" x:status=\"future\" "),
                    raw.replaceFirst("<font-ownership ", "<font-ownership xmlns=\"urn:future\" "),
                    raw.replaceFirst("<baseline ", "<baseline future=\"opaque\" "),
                    raw.replaceFirst("version=\"2\"", "version=\"99\""),
                    raw.replaceFirst("status=\"OWNED\"", "status=\"FUTURE\""),
                    raw.replaceFirst("mode=\"explicit\"", "mode=\"future\""),
                    raw.replaceFirst("ligatures=\"true\"", "ligatures=\"future\""),
                    raw.replaceFirst("spacing=\"1.45\"", "spacing=\"NaN\""),
                    raw.replaceFirst("spacing=\"1.45\"", "spacing=\"Infinity\""),
                    raw.replaceFirst("<baseline ", "opaque text<baseline "),
                    "<broken",
                )
            for (record in unknown) {
                assertNotEquals(raw, record)
                fixture.state.fontOwnershipSnapshots[key] = record
                assertFailsWith<IllegalStateException> { fixture.apply(font(FontPreset.WHISPER)) }
                fixture.disable()
                sameFonts(applied, fixture.scheme)
                assertEquals(record, fixture.state.fontOwnershipSnapshots[key])
            }
        }

    @Test fun `managed disable restores every available owned scheme independently`() =
        withFonts { fixture ->
            val first = fixture.scheme
            val firstBefore = clone(first)
            fixture.apply(font())
            val second =
                EditorColorsSchemeImpl(first).apply {
                    name = "Second font scheme"
                    fontPreferences = FontNativeFixture.preferences("Serif", 21f)
                    consoleFontPreferences = FontNativeFixture.preferences("Dialog", 16f)
                }
            fixture.schemes.add(second)
            fixture.scheme = second
            val secondBefore = clone(second)
            fixture.apply(font(FontPreset.WHISPER))
            fixture.disable()
            sameFonts(firstBefore, first)
            sameFonts(secondBefore, second)
        }

    @Test fun `repeated accepted settings do not rewrite native preferences`() =
        withFonts { fixture ->
            var writes = 0
            val native =
                object : EditorColorsSchemeImpl(fixture.scheme) {
                    override fun setFontPreferences(preferences: FontPreferences) {
                        writes++
                        super.setFontPreferences(preferences)
                    }
                }
            (fixture.scheme as AbstractColorsScheme).copyTo(native)
            fixture.replace(native)
            fixture.apply(font())
            val acceptedWrites = writes
            fixture.apply(font())
            assertEquals(acceptedWrites, writes)
        }

    @Test fun `mutation then throw retains attempt receipts for exact rollback without accepting ownership`() =
        withFonts { fixture ->
            val failure = IllegalStateException("Font cache update failed")
            var failWrites = false
            val native =
                object : EditorColorsSchemeImpl(fixture.scheme) {
                    override fun setFontPreferences(preferences: FontPreferences) {
                        super.setFontPreferences(preferences)
                        if (failWrites) throw failure
                    }
                }
            (fixture.scheme as AbstractColorsScheme).copyTo(native)
            native.name = fixture.scheme.name
            fixture.replace(native)
            val original = clone(native)
            failWrites = true
            val thrown = assertFailsWith<IllegalStateException> { fixture.apply(font()) }
            assertEquals(failure.message, thrown.message)
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
            assertTrue(fixture.state.fontOwnershipSnapshots.isEmpty())
            failWrites = false
            fixture.restoreAttempt()
            sameFonts(original, native)
            fixture.reload()
            fixture.disable()
            sameFonts(original, native)
        }

    @Test fun `disable reenable captures a new manual baseline without rewriting dormant customizations`() =
        withFonts { fixture ->
            val presets = linkedMapOf("FUTURE" to "unknown|opaque|data", "WHISPER" to "16|1.15|false|FUTURE_WEIGHT")
            fixture.state.fontPresetCustomizations.putAll(presets)
            fixture.apply(font())
            fixture.scheme.setEditorFontSize(23.5f)
            fixture.disable()
            val manual = clone(fixture.scheme)
            fixture.reload()
            fixture.apply(font(FontPreset.WHISPER))
            fixture.reload()
            fixture.disable()
            sameFonts(manual, fixture.scheme)
            assertEquals(presets, fixture.state.fontPresetCustomizations)
        }

    @Test fun `missing active scheme cannot acquire native ownership`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            fixture.schemes.clear()
            assertFailsWith<IllegalStateException> { fixture.apply(font()) }
            sameFonts(original, fixture.scheme)
            assertTrue(fixture.state.fontOwnershipSnapshots.isEmpty())
        }

    @Test fun `native reload preserves exact visible fonts and released recovery baselines`() =
        withFonts { fixture ->
            fixture.apply(font(console = true))
            val baselines = baselines(fixture)
            val native = fixture.scheme as AbstractColorsScheme
            val reloaded = EditorColorsSchemeImpl(native.parentScheme)
            reloaded.readExternal(JDOMUtil.load(JDOMUtil.writeElement(native.writeScheme())))
            fixture.replace(reloaded)
            fixture.reload()
            val visible = clone(reloaded)
            fixture.disable()
            sameFonts(visible, reloaded)
            assertEquals(baselines, baselines(fixture))
            fixture.reload()
            fixture.apply(font(FontPreset.WHISPER))
            fixture.disable()
            sameFonts(visible, reloaded)
        }

    @Test fun `a scheme unavailable at disable is restored when it returns`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            fixture.apply(font())
            fixture.schemes.clear()
            fixture.disable()
            assertEquals(FontPreset.AMBIENT.fontFamily, fixture.scheme.editorFontName)
            fixture.reload()
            fixture.schemes.add(fixture.scheme)
            fixture.disable()
            sameFonts(original, fixture.scheme)
        }

    @Test fun `manual console inheritance releases old ownership and later explicit preferences survive`() =
        withFonts { fixture ->
            fixture.apply(font(console = true))
            val applied = FontPreferencesImpl().also { fixture.scheme.consoleFontPreferences.copyTo(it) }
            fixture.scheme.setUseEditorFontPreferencesInConsole()
            fixture.disable()
            fixture.reload()
            fixture.scheme.consoleFontPreferences = applied
            fixture.disable()
            FontNativeFixture.samePreferences(applied, fixture.scheme.consoleFontPreferences)
        }

    @Test fun `future ownership schema preserves all preferences and opaque records`() =
        withFonts { fixture ->
            val original = clone(fixture.scheme)
            val raw = linkedMapOf("FUTURE:scheme" to "opaque snapshot")
            fixture.state.fontOwnershipVersion = 99
            fixture.state.fontOwnershipSnapshots.putAll(raw)
            assertFailsWith<IllegalStateException> { fixture.apply(font()) }
            fixture.disable()
            sameFonts(original, fixture.scheme)
            assertEquals(99, fixture.state.fontOwnershipVersion)
            assertEquals(raw, fixture.state.fontOwnershipSnapshots)
        }

    private fun baselines(fixture: FontNativeFixture): Map<String, String> =
        fixture.state.fontOwnershipSnapshots.mapValues { (_, raw) ->
            JDOMUtil.writeElement(requireNotNull(JDOMUtil.load(raw).getChild("baseline")))
        }

    private fun font(
        preset: FontPreset = FontPreset.AMBIENT,
        console: Boolean = false,
    ) = FontSettings.fromPreset(preset).copy(applyToConsole = console)

    private fun clone(scheme: EditorColorsScheme): EditorColorsScheme = scheme.clone() as EditorColorsScheme

    private fun sameFonts(
        expected: EditorColorsScheme,
        actual: EditorColorsScheme,
    ) {
        assertEquals(expected.isUseAppFontPreferencesInEditor, actual.isUseAppFontPreferencesInEditor)
        assertEquals(expected.isUseEditorFontPreferencesInConsole, actual.isUseEditorFontPreferencesInConsole)
        FontNativeFixture.samePreferences(expected.fontPreferences, actual.fontPreferences)
        FontNativeFixture.samePreferences(expected.consoleFontPreferences, actual.consoleFontPreferences)
    }

    private fun withFonts(test: (FontNativeFixture) -> Unit) =
        SwingUtilities.invokeAndWait { test(FontNativeFixture()) }
}
