package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontOwnershipCodec
import dev.ayuislands.font.FontOwnershipRecord
import dev.ayuislands.font.FontOwnershipStatus
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

/** Imported v2 one-shot ownership is preserved by the actual Studio native participant. */
class FontOnboardingOwnershipTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test fun `disabled cleanup and reload preserve imported onboarding fonts and dormant choices`() =
        onEdt { fixture ->
            seedOnboarding(fixture)
            fixture.state.fontPresetEnabled = false
            fixture.state.fontPresetName = "FUTURE_PRESET"
            fixture.state.fontPresetCustomizations["FUTURE_PRESET"] = "opaque|future|customization"
            val before = FontData.captureExact(fixture.scheme.fontPreferences)
            val records = fixture.state.fontOwnershipSnapshots.toMap()
            val customizations = fixture.state.fontPresetCustomizations.toMap()
            fixture.reload()
            fixture.disable()
            assertEquals(before, FontData.captureExact(fixture.scheme.fontPreferences))
            assertEquals(records, fixture.state.fontOwnershipSnapshots)
            assertEquals("FUTURE_PRESET", fixture.state.fontPresetName)
            assertEquals(customizations, fixture.state.fontPresetCustomizations)
        }

    @Test fun `explicit managed choice captures the earlier onboarding choice as its baseline`() =
        onEdt { fixture ->
            seedOnboarding(fixture)
            val onboarding = FontData.captureExact(fixture.scheme.fontPreferences)
            fixture.apply(FontSettings.fromPreset(FontPreset.WHISPER))
            assertEquals(FontPreset.WHISPER.fontFamily, fixture.scheme.editorFontName)
            fixture.reload()
            fixture.disable()
            assertEquals(onboarding, FontData.captureExact(fixture.scheme.fontPreferences))
        }

    @Test fun `manual edits to imported onboarding fonts survive disabled cleanup`() =
        onEdt { fixture ->
            seedOnboarding(fixture)
            fixture.scheme.setEditorFontSize(22.5f)
            val manual = FontData.captureExact(fixture.scheme.fontPreferences)
            val records = fixture.state.fontOwnershipSnapshots.toMap()
            fixture.reload()
            fixture.disable()
            assertEquals(manual, FontData.captureExact(fixture.scheme.fontPreferences))
            assertEquals(records, fixture.state.fontOwnershipSnapshots)
        }

    private fun seedOnboarding(fixture: FontNativeFixture) {
        val baseline = FontSnapshot.Explicit(FontData.captureExact(fixture.scheme.fontPreferences))
        fixture.scheme.fontPreferences =
            FontNativeFixture.preferences(FontPreset.AMBIENT.fontFamily, FontPreset.AMBIENT.fontSize)
        val applied = FontSnapshot.Explicit(FontData.captureExact(fixture.scheme.fontPreferences))
        val id = UUID(0, 42).toString()
        (fixture.scheme as AbstractColorsScheme).metaProperties.setProperty(FontIdentityRules.METADATA_KEY, id)
        fixture.state.fontOwnershipVersion = 2
        fixture.state.fontOwnershipSnapshots[FontIdentityRules.key(id, FontSurface.EDITOR)] =
            FontOwnershipCodec.encode(
                FontOwnershipRecord(FontOwnershipStatus.ONE_SHOT, baseline, applied),
            )
    }

    private fun onEdt(test: (FontNativeFixture) -> Unit) = SwingUtilities.invokeAndWait { test(FontNativeFixture()) }
}
