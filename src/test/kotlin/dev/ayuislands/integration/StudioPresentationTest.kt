package dev.ayuislands.integration

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AccentPanelAction
import dev.ayuislands.settings.ActiveFileContext
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.persistence.StudioMetadataStore
import dev.ayuislands.studio.session.StudioGlowEditing
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.studio.session.StudioSessionIo
import dev.ayuislands.studio.ui.StudioHostDialogs
import dev.ayuislands.studio.ui.StudioIdeSettings
import dev.ayuislands.studio.ui.StudioSessionPresentation
import dev.ayuislands.studio.ui.StudioSettingsAccess
import dev.ayuislands.studio.ui.StudioSettingsBinding
import dev.ayuislands.studio.ui.StudioSettingsHost
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.JComboBox
import javax.swing.JTabbedPane
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioPresentationTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    private val scope = appScope()

    @After
    fun closeScope() = scope.cancel()

    @Test
    fun `language observation refreshes the retained native controls without a draft edit`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                var host: StudioSettingsHost? = null
                val opening = opening(fixture) { error("Language selection is local preview state") }
                val captured = SettingsOpenContext(null, ActiveFileContext("source.kt", "Kotlin", setOf("kotlin")))
                val presentation = hostModal { StudioSessionPresentation.create(captured, opening) { host?.refresh() } }
                val native =
                    StudioSettingsHost(fixture.binding, presentation::context, mockk<StudioHostDialogs>(), presentation)
                host = native
                var isPresentationDisposed = false
                try {
                    val component = native.createComponent()
                    native.reset()
                    val before = fixture.binding.controller.state.record
                    val language =
                        hostDescendants(component).filterIsInstance<JComboBox<*>>().single { combo ->
                            (0 until combo.itemCount).any { combo.getItemAt(it) == "Kotlin" }
                        }
                    assertEquals("Kotlin", language.selectedItem)

                    presentation.syntax(SyntaxPanelEvent.Language("Python"))

                    assertEquals("Python", language.selectedItem)
                    assertEquals(before, fixture.binding.controller.state.record)
                    assertFalse(native.isModified())
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                    Disposer.dispose(presentation)
                    isPresentationDisposed = true
                    presentation.syntax(SyntaxPanelEvent.Language("Java"))
                    assertEquals("Python", language.selectedItem)
                } finally {
                    native.disposeUIResources()
                    if (!isPresentationDisposed) Disposer.dispose(presentation)
                }
            }
        }

    @Test
    fun `underline sync observes the selected draft glow width and keeps disabled glow unavailable`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                hostModal { fixture.binding.load() }
                val presentation =
                    hostModal {
                        StudioSessionPresentation.create(
                            SettingsOpenContext.EMPTY,
                            opening(fixture) { error("No metadata edit") },
                        ) {}
                    }
                try {
                    val initial = fixture.document
                    for (enabled in listOf(true, false)) {
                        val draft = fixture.selectedDraft()
                        StudioGlowEditing.edits(draft, GlowPanelEvent.Enable(enabled)).forEach { intent ->
                            fixture.binding.controller.dispatch(SessionEvent.Intent(intent))
                        }
                        val settings = StudioGlowProjection.settings(fixture.selectedDraft().appearance)
                        if (enabled) {
                            assertEquals(
                                settings.width.getValue(settings.style),
                                presentation.context().accent.glowWidth,
                            )
                        } else {
                            assertNull(presentation.context().accent.glowWidth)
                        }
                    }
                    assertEquals(initial, fixture.document)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                } finally {
                    Disposer.dispose(presentation)
                }
            }
        }

    @Test
    fun `disposed presentation ignores delayed native action callbacks`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                hostModal { fixture.binding.load() }
                val events = mutableListOf<StudioMetadataCommand>()
                val presentation =
                    hostModal {
                        StudioSessionPresentation.create(SettingsOpenContext.EMPTY, opening(fixture, events::add)) {}
                    }
                Disposer.dispose(presentation)
                val accepted = fixture.document

                assertNull(presentation.accent(AccentPanelAction.Shuffle(AyuVariant.MIRAGE)))

                assertTrue(events.isEmpty())
                assertEquals(accepted, fixture.document)
                assertTrue(fixture.apply.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `unsupported stored tab gets a display fallback and is preserved until explicit navigation`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val field =
                    fixture.document.auxiliary.ui.records
                        .single { it.key.name == "settingsSelectedTab" }
                val unknown = RawValue.Integer(99)
                val seed =
                    StudioMetadataCommand.UiEdit(
                        FieldAddress(field.key),
                        FieldValue.Present(field.value),
                        FieldValue.Present(unknown),
                    )
                seedMetadata(fixture, seed)
                val events = mutableListOf<StudioMetadataCommand>()
                val settings =
                    StudioIdeSettings(
                        { StudioSettingsAccess.Ready(opening(fixture, events::add)) },
                        { SettingsOpenContext.EMPTY },
                    )
                try {
                    val component = requireNotNull(settings.createComponent())
                    settings.reset()
                    val tabs = hostDescendants(component).filterIsInstance<JTabbedPane>().single()
                    assertEquals(StudioSettingsSection.ACCENT.ordinal, tabs.selectedIndex)
                    assertTrue(events.isEmpty())
                    assertEquals(
                        unknown,
                        fixture.document.auxiliary.ui.records
                            .single { it.key == field.key }
                            .value,
                    )

                    tabs.selectedIndex = StudioSettingsSection.FONT.ordinal

                    assertEquals(
                        listOf<StudioMetadataCommand>(
                            StudioMetadataCommand.UiChoice(
                                FieldAddress(field.key),
                                FieldValue.Present(RawValue.Integer(1)),
                            ),
                        ),
                        events,
                    )
                } finally {
                    settings.disposeUIResources()
                }
            }
        }

    private fun seedMetadata(
        fixture: StudioHostFixture,
        command: StudioMetadataCommand.UiEdit,
    ) {
        val session =
            object : StudioSessionIo {
                override suspend fun publish(update: StudioApplyUpdate) = Unit

                override suspend fun flush(record: StudioSessionRecord): DraftWriteResult = error("No draft save")

                override suspend fun observe(
                    request: AcceptanceRequest,
                    document: StudioDocument,
                ): AcceptanceContext = error("No acceptance")
            }
        val store = StudioMetadataStore(fixture.apply.store, session, fixture.apply.mutex)
        assertIs<AcceptanceResult.Accepted>(hostModal { store.submit(command, "seed-tab") })
        fixture.readStoredDocument()
    }

    private fun opening(
        fixture: StudioHostFixture,
        metadata: (StudioMetadataCommand) -> Unit,
    ) = StudioSettingsBinding(fixture.binding, metadata, scope) { LicenseEntitlement.LICENSED }

    private fun appScope(context: CoroutineContext = Dispatchers.Default): CoroutineScope =
        CoroutineScope(SupervisorJob() + context)
}
