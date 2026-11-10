package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.components.ActionLink
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.SettingsBadges
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.persistence.StudioMetadataStore
import dev.ayuislands.studio.session.StudioSessionIo
import dev.ayuislands.studio.ui.StudioIdeSettings
import dev.ayuislands.studio.ui.StudioSettingsAccess
import dev.ayuislands.studio.ui.StudioSettingsBinding
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.JTabbedPane
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSettingsBadgesTest {
    @get:Rule val application = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()
    private val scope = scope()

    @After fun closeScope() = scope.cancel()

    @Test fun `Settings badge review keeps compact navigation and updates only after accepted metadata`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                seedBadge(fixture)
                val commands = mutableListOf<StudioMetadataCommand>()
                val settings = settings(fixture, commands)
                try {
                    val component = settings.createComponent()
                    settings.reset()
                    val tabs = hostDescendants(component).filterIsInstance<JTabbedPane>().single()
                    val review =
                        hostDescendants(component).filterIsInstance<ActionLink>().single {
                            it.text ==
                                "Review new settings"
                        }
                    val accepted = fixture.document
                    val glow = StudioSettingsSection.GLOW.ordinal
                    assertTrue(review.isVisible)
                    assertTrue(review.toolTipText.contains("ECG waveform glow"))
                    assertTrue(review.preferredSize.width < 420)
                    assertEquals(
                        "Glow, contains new settings",
                        tabs.getTabComponentAt(glow).accessibleContext.accessibleName,
                    )

                    review.doClick()

                    assertEquals(glow, tabs.selectedIndex)
                    assertTrue(
                        commands.filterIsInstance<StudioMetadataCommand.BadgesSeen>().any { "glow-waveform" in it.ids },
                    )
                    assertEquals(accepted.auxiliary.ui, fixture.document.auxiliary.ui)
                    assertEquals(
                        "Glow, contains new settings",
                        tabs.getTabComponentAt(glow).accessibleContext.accessibleName,
                    )
                    write(fixture, commands.toList())
                    fixture.binding.controller.dispatch(
                        SessionEvent.Observation(SessionObservation.IntentRevision(fixture.document.header.revision)),
                    )

                    assertFalse(review.isVisible)
                    assertFalse(
                        tabs
                            .getTabComponentAt(glow)
                            ?.accessibleContext
                            ?.accessibleName
                            .orEmpty()
                            .contains("new settings"),
                    )
                    assertEquals(accepted.currents, fixture.document.currents)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                } finally {
                    settings.disposeUIResources()
                }
            }
        }

    @Test fun `disposing Settings removes its badge listener without removing a later owner callback`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                seedBadge(fixture)
                val commands = mutableListOf<StudioMetadataCommand>()
                val settings = settings(fixture, commands)
                try {
                    val component = settings.createComponent()
                    settings.reset()
                    val tabs = hostDescendants(component).filterIsInstance<JTabbedPane>().single()
                    assertNotNull(SettingsBadges.onBadgesChanged)
                    val laterOwner = { }
                    SettingsBadges.onBadgesChanged = laterOwner
                    settings.disposeUIResources()
                    commands.clear()
                    tabs.selectedIndex = StudioSettingsSection.GLOW.ordinal
                    assertTrue(commands.isEmpty())
                    assertSame(laterOwner, SettingsBadges.onBadgesChanged)
                } finally {
                    SettingsBadges.onBadgesChanged = null
                    settings.disposeUIResources()
                }
            }
        }

    private fun settings(
        fixture: StudioHostFixture,
        commands: MutableList<StudioMetadataCommand>,
    ) = StudioIdeSettings(
        provider = {
            StudioSettingsAccess.Ready(
                StudioSettingsBinding(fixture.binding, commands::add, scope) {
                    LicenseEntitlement.LICENSED
                },
            )
        },
        capture = { SettingsOpenContext.EMPTY },
    )

    private fun seedBadge(fixture: StudioHostFixture) =
        write(
            fixture,
            listOf(
                StudioMetadataCommand.ReleaseSeen("2.9.9", 1_000L),
                StudioMetadataCommand.UiChoice(
                    FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "acknowledgedSettingsBadges")),
                    FieldValue.Present(
                        RawValue.OrderedSet(
                            StudioList(
                                SettingsBadges.registry.map { it.id }.filterNot {
                                    it ==
                                        "glow-waveform"
                                },
                            ),
                        ),
                    ),
                ),
            ),
        )

    private fun write(
        fixture: StudioHostFixture,
        commands: List<StudioMetadataCommand>,
    ) {
        val session = mockk<StudioSessionIo>()
        coEvery { session.publish(any()) } returns Unit
        val writer = StudioMetadataStore(fixture.apply.store, session, fixture.apply.mutex)
        hostModal {
            commands.forEachIndexed { index, command ->
                val result = writer.submit(command, "badge-${fixture.document.header.revision}-$index")
                assertTrue(result is AcceptanceResult.Accepted || result == AcceptanceResult.NoChanges)
            }
        }
        fixture.readStoredDocument()
    }

    private fun scope(context: CoroutineContext = Dispatchers.Default) = CoroutineScope(SupervisorJob() + context)
}
