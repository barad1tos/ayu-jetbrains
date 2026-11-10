package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.ActiveFileContext
import dev.ayuislands.settings.SettingsOpenContext
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
import org.junit.rules.TemporaryFolder
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTabbedPane
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioUnavailableSettingsTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    private val scope = appScope()

    @After
    fun closeScope() = scope.cancel()

    @Test
    fun `bootstrap unavailable keeps the captured target and native Retry opens only a ready binding`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val captured = SettingsOpenContext(null, ActiveFileContext("original.kt", "Kotlin", setOf("kotlin")))
                val observations = mutableListOf<SettingsOpenContext>()
                var captures = 0
                val settings =
                    StudioIdeSettings(
                        provider = { context ->
                            observations.add(context)
                            if (observations.size == 1) {
                                StudioSettingsAccess.Unavailable(
                                    "Saved appearance could not be loaded. Your files are unchanged.",
                                )
                            } else {
                                StudioSettingsAccess.Ready(
                                    StudioSettingsBinding(
                                        fixture.binding,
                                        { error("No metadata edit") },
                                        scope,
                                    ) { LicenseEntitlement.LICENSED },
                                )
                            }
                        },
                        capture = {
                            captures++
                            captured
                        },
                    )
                val before = fixture.document
                try {
                    val root = settings.createComponent()
                    settings.reset()
                    settings.apply()
                    assertFalse(settings.isModified())
                    assertTrue(hostDescendants(root).none { it is JTabbedPane })
                    assertTrue(
                        hostDescendants(
                            root,
                        ).filterIsInstance<JLabel>().any { it.text.contains("could not be loaded") },
                    )
                    assertEquals(before, fixture.document)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })

                    hostDescendants(root).filterIsInstance<JButton>().single { it.text == "Retry" }.doClick()
                    settings.reset()

                    val tabs = hostDescendants(root).filterIsInstance<JTabbedPane>().single()
                    assertEquals(
                        StudioSettingsSection.entries.map { it.title },
                        (0 until tabs.tabCount).map(tabs::getTitleAt),
                    )
                    assertEquals(1, captures)
                    assertEquals(listOf(captured, captured), observations)
                    assertSame(root, settings.createComponent())
                    assertEquals(before, fixture.document)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                } finally {
                    settings.disposeUIResources()
                }
            }
        }

    private fun appScope(context: CoroutineContext = Dispatchers.Default): CoroutineScope =
        CoroutineScope(SupervisorJob() + context)
}
