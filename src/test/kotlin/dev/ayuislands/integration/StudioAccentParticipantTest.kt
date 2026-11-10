package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.IntegrationSurface
import dev.ayuislands.studio.session.StudioNativeEvidence
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioAccentParticipantTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `candidate reaches all native Accent surfaces before the confirmed handoff`() =
        onAccentEdt {
            AccentParticipantFixture().use { fixture ->
                val before = ParticipantSnapshot(fixture.scheme)
                val input = fixture.input
                val operation = fixture.participant.prepare(input, fixture.runtime)

                before.assertUnchanged()
                fixture.assertOriginal()
                assertNull(fixture.runtime.accent)
                assertTrue(operation.runtimeChanges.isEmpty())
                assertTrue(operation.receipts.isNotEmpty())
                assertEquals(
                    operation.receipts.size,
                    operation.receipts
                        .map { it.id }
                        .distinct()
                        .size,
                )
                assertTrue(operation.receipts.all { it.status == RestoreStatus.PENDING })
                fixture.beforeRefresh = {
                    fixture.assertApplied()
                    assertNull(fixture.runtime.accent)
                }

                try {
                    operation.apply()

                    fixture.assertApplied()
                    val confirmed = assertNotNull(fixture.runtime.accent)
                    assertEquals(AccentContext.Ayu(AyuVariant.MIRAGE), confirmed.context)
                    assertEquals("#FFCC66", confirmed.focused?.hex)
                    assertNull(confirmed.focusedProject)
                    assertTrue(confirmed.projects.isEmpty())
                    assertEquals(1, fixture.runtime.confirmations)
                    assertEquals(input.candidate, fixture.input.candidate)
                    assertTrue(operation.runtimeChanges.isNotEmpty())
                } finally {
                    fixture.beforeRefresh = {}
                    settleParticipant(operation)
                }

                fixture.assertOriginal()
                before.assertValues()
                assertTrue(operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
            }
        }

    @Test
    fun `failed final refresh rolls back defaults editor Chrome and optional integration without publishing Accent`() =
        onAccentEdt {
            AccentParticipantFixture().use { fixture ->
                val before = ParticipantSnapshot(fixture.scheme)
                val operation = fixture.participant.prepare(fixture.input, fixture.runtime)
                fixture.beforeRefresh = { fixture.assertApplied() }
                fixture.refreshFailure = IllegalStateException("Native Accent refresh failed after writes")

                val failure = assertFailsWith<IllegalStateException> { operation.apply() }

                assertEquals("Native Accent refresh failed after writes", failure.message)
                assertNull(fixture.runtime.accent)
                assertEquals(0, fixture.runtime.confirmations)
                fixture.beforeRefresh = {}
                settleParticipant(operation)

                fixture.assertOriginal()
                before.assertValues()
                assertTrue(operation.runtimeChanges.isEmpty())
                assertTrue(operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
            }
        }

    @Test
    fun `rollback retries a failed final refresh after all native receipts have settled`() =
        onAccentEdt {
            AccentParticipantFixture().use { fixture ->
                val before = ParticipantSnapshot(fixture.scheme)
                val operation = fixture.participant.prepare(fixture.input, fixture.runtime)
                operation.apply()
                fixture.beforeRefresh = {
                    if (operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) }) {
                        error("Restored Accent still needs native refresh")
                    }
                }

                val failure = assertFailsWith<IllegalStateException> { settleParticipant(operation) }

                assertEquals("Restored Accent still needs native refresh", failure.message)
                fixture.assertOriginal()
                before.assertValues()
                assertTrue(operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
                val refreshed = fixture.refreshes
                val writes = fixture.integrationWrites
                fixture.beforeRefresh = {}

                settleParticipant(operation)

                assertEquals(refreshed + 1, fixture.refreshes)
                assertEquals(writes, fixture.integrationWrites)
                assertEquals(1, fixture.runtime.confirmations)
                fixture.assertOriginal()
                before.assertValues()
            }
        }

    @Test
    fun `rollback preserves foreign changes while restoring independent Accent surfaces`() =
        onAccentEdt {
            AccentParticipantFixture().use { fixture ->
                val operation = fixture.participant.prepare(fixture.input, fixture.runtime)
                operation.apply()
                UIManager.put("Button.default.startBackground", Color.GREEN)
                fixture.scheme.setColor(ColorKey.find("CARET_COLOR"), Color.MAGENTA)
                fixture.codeGlance.viewportBorderColor = "AABBCC"

                settleParticipant(operation)

                assertSame(Color.GREEN, readRawUiDefault(UIManager.getDefaults(), "Button.default.startBackground"))
                assertSame(Color.MAGENTA, fixture.scheme.directlyDefinedColors[ColorKey.find("CARET_COLOR")])
                assertEquals("AABBCC", fixture.codeGlance.viewportBorderColor)
                assertEquals("123456", fixture.codeGlance.viewportColor)
                assertEquals(3, fixture.codeGlance.viewportBorderThickness)
                assertSame(Color.BLUE, readRawUiDefault(UIManager.getDefaults(), "ProgressBar.foreground"))
                assertSame(Color.BLUE, readRawUiDefault(UIManager.getDefaults(), "NavBar.background"))
                assertSame(Color.BLUE, fixture.scheme.directlyDefinedColors[ColorKey.find("BUTTON_BACKGROUND")])
                assertTrue(operation.receipts.any { it.status == RestoreStatus.FOREIGN })
                assertTrue(operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
            }
        }

    @Test
    fun `accepting twice keeps the applied appearance without replaying native writes or handoff`() =
        onAccentEdt {
            AccentParticipantFixture().use { fixture ->
                val operation = fixture.participant.prepare(fixture.input, fixture.runtime)
                operation.apply()
                val applied = ParticipantSnapshot(fixture.scheme)
                val defaults = fixture.rawDefaults()
                val writes = fixture.integrationWrites
                val confirmed = fixture.runtime.accent

                operation.accept()
                operation.accept()

                fixture.assertApplied()
                applied.assertValues()
                defaults.forEach { (key, value) ->
                    assertSame(value, readRawUiDefault(UIManager.getDefaults(), key), key)
                }
                assertEquals(writes, fixture.integrationWrites)
                assertEquals(1, fixture.runtime.confirmations)
                assertSame(confirmed, fixture.runtime.accent)
            }
        }

    @Test
    fun `same process lost handles cannot replay Apply or discard persistent integration receipts`() =
        onAccentEdt {
            AccentParticipantFixture().use { fixture ->
                val original = fixture.participant.prepare(fixture.input, fixture.runtime)
                original.apply()
                val applied = ParticipantSnapshot(fixture.scheme)
                val defaults = fixture.rawDefaults()
                val writes = fixture.integrationWrites
                val receipts = original.receipts
                val recovered = fixture.participant.recover(fixture.input, receipts, fixture.runtime)
                try {
                    assertFailsWith<IllegalStateException> { recovered.apply() }
                    recovered.prepareRestore(StudioList(emptyList()))
                    recovered.restore(StudioList(emptyList()))

                    applied.assertValues()
                    defaults.forEach { (key, value) ->
                        assertSame(value, readRawUiDefault(UIManager.getDefaults(), key), key)
                    }
                    assertEquals(writes, fixture.integrationWrites)
                    assertEquals(receipts.map { it.id }.toSet(), recovered.receipts.map { it.id }.toSet())
                    val integration =
                        recovered.receipts.filter { it.identity.resource == IntegrationSurface.CODE_GLANCE.resource }
                    assertEquals(3, integration.size)
                    assertTrue(integration.all { it.status == RestoreStatus.UNAVAILABLE })
                    val ephemeral = recovered.receipts.filter { it.identity.field.startsWith("accent/") }
                    assertTrue(ephemeral.isNotEmpty())
                    assertTrue(ephemeral.all { it.status == RestoreStatus.UNAVAILABLE })
                    assertEquals(1, fixture.runtime.confirmations)
                } finally {
                    settleParticipant(original)
                }
            }
        }
}

private fun onAccentEdt(action: suspend () -> Unit) {
    SwingUtilities.invokeAndWait { runBlocking { action() } }
}
