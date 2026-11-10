package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.studio.EmptyEffectRuntime
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.IdeFontHost
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioFontParticipant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioFontHostTest {
    @get:Rule val applicationRule: ApplicationRule = ApplicationRule()

    @Test
    fun `production host returns to modal EDT after IO and awaits refresh listeners`() {
        val application = ApplicationManager.getApplication()
        val lifetime = Disposer.newDisposable("StudioFontHostTest")
        val calls = AtomicInteger()
        application.messageBus.connect(lifetime).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener {
                assertTrue(application.isWriteAccessAllowed)
                assertTrue(SwingUtilities.isEventDispatchThread())
                assertNotEquals(ModalityState.nonModal(), ModalityState.current())
                calls.incrementAndGet()
            },
        )
        try {
            val document =
                assertIs<MigrationResult.Ready>(
                    StudioLegacyMigration.plan(StudioFixtures.capture()),
                ).document
            SwingUtilities.invokeAndWait {
                runWithModalProgressBlocking(ModalTaskOwner.component(JPanel()), "Verify Font host") {
                    val host = IdeFontHost()
                    host.resolveFamily(FontSettings.fromPreset(FontPreset.AMBIENT))
                    val observed = withContext(Dispatchers.EDT) { host.observe() }
                    assertTrue(observed.schemes.isNotEmpty())
                    val operation =
                        StudioFontParticipant(host).prepare(
                            StudioEffectInput(document, StudioTarget.Global, StudioList(emptyList())),
                            EmptyEffectRuntime,
                        )
                    operation.apply()
                    assertTrue(operation.runtimeChanges.isEmpty())
                    assertEquals(1, calls.get())
                    val refreshed = withContext(Dispatchers.EDT) { host.observe() }
                    assertSame(observed.active, refreshed.active)
                    assertEquals(observed.schemes, refreshed.schemes)
                }
            }
        } finally {
            Disposer.dispose(lifetime)
        }
    }
}
