package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.session.SchemeReceiptIdentity
import dev.ayuislands.studio.session.StudioNativeTokens
import dev.ayuislands.studio.session.StudioSchemeWrite
import dev.ayuislands.studio.session.StudioVcsParticipant
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.vcs.VcsColorApplier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioVcsParticipantTest {
    @get:Rule val applicationRule = ApplicationRule()

    @After
    fun clearOwnership() {
        SwingUtilities.invokeAndWait { EditorSchemeOverrides.reset() }
    }

    @Test
    fun `real VCS preparation reaches native receipts and participant Apply without normalizing foreign fields`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val fixture = ParticipantVcs()
                val before = ParticipantSnapshot(fixture.scheme)
                val snapshot = StudioVcsProjection.snapshot(fixture.input.candidate.currents.global.appearance)
                val prepared = VcsColorApplier.prepare(fixture.scheme, snapshot, AyuVariant.MIRAGE, true)
                assertTrue(prepared.keys.any { it is SchemeEntry.ColorEntry })
                assertTrue(prepared.keys.any { it is SchemeEntry.AttributesEntry })
                val target =
                    assertNotNull(SchemeTarget.capture(SchemeSource.Named(fixture.scheme.name)) { fixture.scheme })
                val batch =
                    EditorSchemeOverrides.checkpoints
                        .prepareOverrides(
                            target,
                            EditorSchemeOwner.Vcs,
                            prepared,
                            prepared.keys,
                        ).batch
                val captured =
                    StudioSchemeWrite.capture(
                        SchemeReceiptIdentity(
                            EffectOwner.VCS,
                            UUID(0, 100).toString(),
                            "producer",
                            participantProcess,
                        ),
                        target,
                        batch,
                        StudioNativeTokens("vcs-producer"),
                    )
                assertTrue(captured.receipts.isNotEmpty())
                before.assertUnchanged()
                val operation = fixture.participant.prepare(fixture.input, participantRuntime())
                try {
                    before.assertUnchanged()
                    val candidate = fixture.input.candidate

                    operation.apply()

                    for ((entry, value) in prepared) {
                        when (entry) {
                            is SchemeEntry.ColorEntry -> {
                                val expected = assertIs<SchemeValue.ColorValue>(value).value
                                assertEquals(expected?.rgb, fixture.scheme.getColor(entry.key)?.rgb)
                            }

                            is SchemeEntry.AttributesEntry -> {
                                assertEquals(
                                    assertIs<SchemeValue.AttributesValue>(value).value,
                                    fixture.scheme.getAttributes(entry.key),
                                )
                            }
                        }
                    }
                    assertEquals(candidate, fixture.input.candidate)
                } finally {
                    settleParticipant(operation)
                }
                before.assertValues()
            }
        }
    }

    @Test
    fun `disabled and explicitly unlicensed VCS restore accepted claims without rewriting requested choices`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                for ((enabled, licensed) in listOf(false to true, true to false)) {
                    val fixture = ParticipantVcs()
                    val before = ParticipantSnapshot(fixture.scheme)
                    val metadata = fixture.scheme.metaProperties.toMap()
                    val original = fixture.participant.prepare(fixture.input, participantRuntime())
                    original.apply()
                    original.accept()
                    val id = assertNotNull(fixture.scheme.metaProperties[FontIdentityRules.METADATA_KEY])
                    val settings =
                        vcsParticipantSettings().apply {
                            vcsColorEnabled = enabled
                            vcsDiffIntensity = 137
                            vcsMergePreset = "future-vcs-preset"
                        }
                    val input = participantInput(settings)
                    val requested = input.candidate.currents.global.appearance.vcs
                    val gated = fixture.participant.prepare(input, participantRuntime(licensed = licensed))

                    gated.apply()
                    gated.accept()

                    before.assertCells()
                    assertEquals(
                        metadata + (FontIdentityRules.METADATA_KEY to id),
                        fixture.scheme.metaProperties.toMap(),
                    )
                    assertEquals(requested, input.candidate.currents.global.appearance.vcs)
                    assertEquals(137, settings.vcsDiffIntensity)
                    assertEquals("future-vcs-preset", settings.vcsMergePreset)
                    assertEquals(enabled, settings.vcsColorEnabled)
                }
            }
        }
    }

    @Test
    fun `committed VCS cleanup preserves native objects and never replays Apply even without process observation`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val fixture = ParticipantVcs()
                val operation = fixture.participant.prepare(fixture.input, participantRuntime())
                operation.apply()
                operation.accept()
                val committed = operation.receipts
                val before = ParticipantSnapshot(fixture.scheme)
                val selected = fixture.host.active
                val cleanup = fixture.participant.recover(fixture.input, committed, participantRuntime(process = null))

                assertFailsWith<IllegalStateException> { cleanup.apply() }
                cleanup.accept()
                cleanup.accept()

                before.assertUnchanged()
                assertSame(selected, fixture.host.active)
                assertEquals(committed, cleanup.receipts)
            }
        }
    }
}

private fun vcsParticipantSettings(): AyuIslandsState =
    AyuIslandsState().apply {
        vcsColorEnabled = true
        vcsDiffPreset = "CUSTOM"
        vcsDiffIntensity = 67
    }

private class ParticipantVcs {
    private val source =
        assertIs<EditorColorsSchemeImpl>(
            EditorColorsManager.getInstance().getScheme(AyuVariant.MIRAGE.editorSchemeResource),
        )
    val scheme = participantScheme(source)
    val host = ParticipantSchemeHost(listOf(scheme))
    val input = participantInput(vcsParticipantSettings())
    val participant = StudioVcsParticipant(host, EmptyCoroutineContext)
}
