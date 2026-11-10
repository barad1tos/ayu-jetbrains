package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.ui.StudioSettingsSamples
import dev.ayuislands.studio.ui.StudioSyntaxColors
import dev.ayuislands.syntax.SyntaxPreset
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSettingsSamplesTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `Glow edits reuse syntax samples while syntax edits update both previews and preserve Current`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val document = fixture.document
                val context = fixture.context()
                val initial = preset(sessionRecord(document).work.drafts.single(), SyntaxPreset.WHISPER)
                val syntaxColors = StudioSyntaxColors()
                val current = StudioSettingsSamples(context, syntaxColors)
                val draft = StudioSettingsSamples(context, syntaxColors)
                val accepted = current.syntax(initial.appearance)
                val syntax = draft.syntax(initial.appearance)
                val plugins = draft.plugins(initial.appearance, document.shared)
                val glow =
                    StudioDraftPolicy.edit(
                        initial,
                        FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "softIntensity")),
                        FieldValue.Present(RawValue.Integer(37)),
                    )
                assertNotEquals(initial.appearance.glow, glow.appearance.glow)
                draft.updateContext(fixture.context())
                assertSame(syntax, draft.syntax(glow.appearance))
                assertSame(plugins, draft.plugins(glow.appearance, document.shared))

                val neon = preset(glow, SyntaxPreset.NEON)
                val changed = draft.syntax(neon.appearance)
                assertNotSame(syntax, changed)
                assertTrue(
                    context.editor.syntaxBase.baseline.keys.any {
                        syntax.scheme.getAttributes(it) != changed.scheme.getAttributes(it)
                    },
                )
                assertNotEquals(plugins.ignore, draft.plugins(neon.appearance, document.shared).ignore)
                assertSame(accepted, current.syntax(initial.appearance))
                assertEquals(document, fixture.document)
            }
        }

    @Test
    fun `native scheme refresh replaces the detached sample without changing its old source`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val context = fixture.context()
                val appearance = fixture.document.currents.global.appearance
                val samples = StudioSettingsSamples(context)
                val before = samples.syntax(appearance)
                val original = context.editor.scheme.getColor(EditorColors.CARET_COLOR)
                val replacement = context.editor.scheme.clone() as EditorColorsScheme
                val color = if (original == Color.MAGENTA) Color.CYAN else Color.MAGENTA
                replacement.setColor(EditorColors.CARET_COLOR, color)
                samples.updateContext(context.copy(editor = context.editor.copy(scheme = replacement)))
                val after = samples.syntax(appearance)
                assertNotSame(before, after)
                assertEquals(color, after.scheme.getColor(EditorColors.CARET_COLOR))
                assertEquals(original, before.scheme.getColor(EditorColors.CARET_COLOR))
                assertEquals(original, context.editor.scheme.getColor(EditorColors.CARET_COLOR))
            }
        }

    private fun preset(
        initial: DraftRecord,
        value: SyntaxPreset,
    ): DraftRecord =
        StudioSyntaxEditing.edits(initial, SyntaxPanelEvent.Preset(value)).fold(initial) { current, intent ->
            val edit = assertIs<SessionIntent.EditField>(intent)
            StudioDraftPolicy.edit(current, edit.address, edit.value)
        }
}
