package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsConfigurable
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.ui.StudioIdeSettings
import dev.ayuislands.studio.ui.StudioSettingsAccess
import dev.ayuislands.studio.ui.StudioSettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import javax.swing.JTabbedPane
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SettingsConfigurableIntegrationTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = org.junit.rules.TemporaryFolder()
    private val scope = appScope()

    @After
    fun closeScope() = scope.cancel()

    @Test
    fun `registered Settings adapter exposes every native section and accepts through the shared binding`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val configurable = configurable(fixture)
                try {
                    val component = requireNotNull(configurable.createComponent())
                    configurable.reset()
                    assertEquals("Ayu Islands", configurable.displayName)
                    assertSame(component, configurable.createComponent())
                    val tabs = hostDescendants(component).filterIsInstance<JTabbedPane>().single()
                    assertEquals(
                        StudioSettingsSection.entries.map { it.title },
                        (0 until tabs.tabCount).map(tabs::getTitleAt),
                    )
                    assertFalse(configurable.isModified())

                    editGlow(fixture)
                    assertTrue(configurable.isModified())
                    configurable.reset()
                    assertFalse(configurable.isModified())
                    assertEquals(fixture.document.currents.global.appearance, fixture.selectedDraft().appearance)

                    editGlow(fixture)
                    val requested = fixture.selectedDraft().appearance
                    configurable.apply()
                    fixture.readStoredDocument()

                    assertEquals(requested, fixture.document.currents.global.appearance)
                    assertFalse(configurable.isModified())
                    assertEquals(1, fixture.coordinators.size)
                } finally {
                    configurable.disposeUIResources()
                }
            }
        }

    @Test
    fun `registered Settings adapter detaches only its view and Cancel retires its retained draft`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val configurable = configurable(fixture)
                try {
                    val original = requireNotNull(configurable.createComponent())
                    configurable.reset()
                    editGlow(fixture)
                    val pending = fixture.binding.controller.state.record
                    val accepted = fixture.document.currents

                    configurable.disposeUIResources()
                    val reopened = requireNotNull(configurable.createComponent())
                    configurable.reset()

                    assertNotSame(original, reopened)
                    assertEquals(pending, fixture.binding.controller.state.record)
                    assertTrue(configurable.isModified())
                    configurable.cancel()
                    fixture.readStoredDocument()
                    assertEquals(SessionPhase.Retired, fixture.binding.controller.state.phase)
                    assertEquals(accepted, fixture.document.currents)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                } finally {
                    configurable.disposeUIResources()
                }
            }
        }

    private fun configurable(fixture: StudioHostFixture) =
        AyuIslandsConfigurable(
            StudioIdeSettings(
                provider = {
                    StudioSettingsAccess.Ready(
                        StudioSettingsBinding(fixture.binding, {}, scope) { LicenseEntitlement.LICENSED },
                    )
                },
                capture = { SettingsOpenContext.EMPTY },
            ),
        )

    private fun editGlow(fixture: StudioHostFixture) {
        val draft = fixture.selectedDraft()
        val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val original =
            draft.appearance.glow.records
                .single { it.key == address.field }
                .value as RawValue.BooleanValue
        fixture.binding.controller.dispatch(
            SessionEvent.Intent(
                SessionIntent.EditField(draft.id, address, FieldValue.Present(RawValue.BooleanValue(!original.value))),
            ),
        )
    }

    private fun appScope(context: CoroutineContext = Dispatchers.Default): CoroutineScope =
        CoroutineScope(SupervisorJob() + context)
}
