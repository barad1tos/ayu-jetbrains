package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AccentControls
import dev.ayuislands.settings.ActiveFileContext
import dev.ayuislands.settings.FontControls
import dev.ayuislands.settings.GlowControls
import dev.ayuislands.settings.PluginsControls
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.settings.SyntaxControls
import dev.ayuislands.settings.VcsControls
import dev.ayuislands.settings.WorkspaceControls
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.ui.StudioIdeSettings
import dev.ayuislands.studio.ui.StudioProfileControls
import dev.ayuislands.studio.ui.StudioSettingsAccess
import dev.ayuislands.studio.ui.StudioSettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioIdeSettingsTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    private val scope = appScope()

    @After
    fun closeScope() = scope.cancel()

    @Test
    fun `opening captures context before readiness and reuses the real seven section host`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val captured = SettingsOpenContext(null, ActiveFileContext("source.kt", "Kotlin", setOf("kotlin")))
                val metadata = mutableListOf<StudioMetadataCommand>()
                var captures = 0
                var provisions = 0
                val native = EditorColorsManager.getInstance().globalScheme
                val fontName = native.editorFontName
                val document = fixture.document
                val settings =
                    StudioIdeSettings(
                        provider = { opened ->
                            assertSame(captured, opened)
                            assertEquals(1, captures)
                            provisions++
                            offEdt { assertFalse(SwingUtilities.isEventDispatchThread()) }
                            StudioSettingsAccess.Ready(opening(fixture, metadata::add))
                        },
                        capture = {
                            assertTrue(SwingUtilities.isEventDispatchThread())
                            captures++
                            captured
                        },
                    )
                try {
                    assertEquals(0, provisions)
                    val component = requireNotNull(settings.createComponent())
                    settings.reset()
                    assertSame(component, settings.createComponent())
                    assertEquals(1, provisions)
                    val children = hostDescendants(component)
                    assertEquals(1, children.filterIsInstance<StudioProfileControls>().size)
                    for (type in listOf(
                        AccentControls::class.java,
                        FontControls::class.java,
                        GlowControls::class.java,
                        SyntaxControls::class.java,
                        VcsControls::class.java,
                        WorkspaceControls::class.java,
                        PluginsControls::class.java,
                    )) {
                        assertEquals(1, children.count(type::isInstance), type.simpleName)
                    }
                    assertEquals(document, fixture.document)
                    assertSame(native, EditorColorsManager.getInstance().globalScheme)
                    assertEquals(fontName, native.editorFontName)
                    assertTrue(metadata.isEmpty())
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                } finally {
                    settings.disposeUIResources()
                }
            }
        }

    @Test
    fun `view recreation retains the same dirty binding and original captured file`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                var provisions = 0
                var captures = 0
                var focused = SettingsOpenContext(null, ActiveFileContext("source.kt", "Kotlin", setOf("kotlin")))
                val first = focused
                val settings =
                    StudioIdeSettings(
                        provider = { opened ->
                            assertSame(first, opened)
                            provisions++
                            StudioSettingsAccess.Ready(opening(fixture) { error("No explicit UI edit") })
                        },
                        capture = {
                            captures++
                            focused
                        },
                    )
                try {
                    val original = requireNotNull(settings.createComponent())
                    settings.reset()
                    editGlow(fixture)
                    val dirty = requireNotNull(fixture.binding.controller.state.record)
                    assertTrue(settings.isModified())
                    settings.disposeUIResources()
                    focused = SettingsOpenContext(null, ActiveFileContext("later.py", "Python", setOf("Python")))
                    val reopened = requireNotNull(settings.createComponent())
                    settings.reset()

                    assertNotSame(original, reopened)
                    assertEquals(dirty, fixture.binding.controller.state.record)
                    assertEquals(SessionPhase.Editing, fixture.binding.controller.state.phase)
                    assertTrue(settings.isModified())
                    assertEquals(1, captures)
                    assertEquals(1, provisions)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                } finally {
                    settings.disposeUIResources()
                }
            }
        }

    @Test
    fun `Apply and Cancel route through the existing binding without another coordinator`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val settings =
                    StudioIdeSettings(
                        provider = { StudioSettingsAccess.Ready(opening(fixture) { error("No explicit UI edit") }) },
                        capture = { SettingsOpenContext.EMPTY },
                    )
                try {
                    settings.createComponent()
                    settings.reset()
                    editGlow(fixture)
                    val requested = fixture.selectedDraft().appearance
                    settings.apply()
                    fixture.readStoredDocument()
                    assertEquals(requested, fixture.document.currents.global.appearance)
                    assertFalse(settings.isModified())
                    assertEquals(1, fixture.coordinators.size)

                    editGlow(fixture)
                    settings.cancel()
                    fixture.readStoredDocument()
                    assertEquals(SessionPhase.Retired, fixture.binding.controller.state.phase)
                    assertEquals(requested, fixture.document.currents.global.appearance)
                } finally {
                    settings.disposeUIResources()
                }
            }
        }

    @Test
    fun `explicit section navigation emits one typed choice without changing accepted metadata`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val metadata = mutableListOf<StudioMetadataCommand>()
                val settings =
                    StudioIdeSettings(
                        provider = { StudioSettingsAccess.Ready(opening(fixture, metadata::add)) },
                        capture = { SettingsOpenContext.EMPTY },
                    )
                try {
                    val component = requireNotNull(settings.createComponent())
                    settings.reset()
                    val accepted = fixture.document
                    val before =
                        accepted.auxiliary.ui.records
                            .single { it.key.name == "settingsSelectedTab" }
                    val tabs = hostDescendants(component).filterIsInstance<JTabbedPane>().single()
                    val destination = StudioSettingsSection.entries.first { it.ordinal != tabs.selectedIndex }
                    assertTrue(metadata.isEmpty())

                    tabs.selectedIndex = destination.ordinal

                    assertEquals(
                        listOf<StudioMetadataCommand>(
                            StudioMetadataCommand.UiChoice(
                                FieldAddress(before.key),
                                FieldValue.Present(RawValue.Integer(destination.ordinal)),
                            ),
                        ),
                        metadata,
                    )
                    assertEquals(accepted, fixture.document)
                    assertFalse(settings.isModified())
                } finally {
                    settings.disposeUIResources()
                }
            }
        }

    private fun editGlow(fixture: StudioHostFixture) {
        val draft = fixture.selectedDraft()
        val field =
            draft.appearance.glow.records
                .single { it.key.name == "glowEnabled" }
        val enabled = field.value as RawValue.BooleanValue
        fixture.binding.controller.dispatch(
            SessionEvent.Intent(
                SessionIntent.EditField(
                    draft.id,
                    FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled")),
                    FieldValue.Present(RawValue.BooleanValue(!enabled.value)),
                ),
            ),
        )
    }

    @Test
    fun `rapid navigation back to the accepted tab retains the final explicit choice before publication`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val metadata = mutableListOf<StudioMetadataCommand>()
                val settings =
                    StudioIdeSettings(
                        provider = { StudioSettingsAccess.Ready(opening(fixture, metadata::add)) },
                        capture = { SettingsOpenContext.EMPTY },
                    )
                try {
                    val component = requireNotNull(settings.createComponent())
                    settings.reset()
                    val tabs = hostDescendants(component).filterIsInstance<JTabbedPane>().single()
                    val initial = tabs.selectedIndex
                    val destination = StudioSettingsSection.entries.first { it.ordinal != initial }.ordinal
                    val accepted = fixture.document
                    assertTrue(metadata.isEmpty())

                    tabs.selectedIndex = destination
                    tabs.selectedIndex = initial

                    assertEquals(
                        listOf(destination, initial).map { FieldValue.Present(RawValue.Integer(it)) },
                        metadata.map { (it as StudioMetadataCommand.UiChoice).after },
                    )
                    assertEquals(accepted, fixture.document)
                    assertFalse(settings.isModified())
                } finally {
                    settings.disposeUIResources()
                }
            }
        }

    private fun opening(
        fixture: StudioHostFixture,
        metadata: (StudioMetadataCommand) -> Unit,
    ) = StudioSettingsBinding(fixture.binding, metadata, scope) { LicenseEntitlement.LICENSED }

    private fun appScope(context: CoroutineContext = Dispatchers.Default): CoroutineScope =
        CoroutineScope(SupervisorJob() + context)

    private suspend fun <T> offEdt(
        context: CoroutineContext = Dispatchers.IO,
        action: () -> T,
    ): T = withContext(context) { action() }
}
