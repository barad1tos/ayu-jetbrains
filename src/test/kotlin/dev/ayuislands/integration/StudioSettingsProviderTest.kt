package dev.ayuislands.integration

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.studio.StudioApplicationGraph
import dev.ayuislands.studio.StudioApplicationState
import dev.ayuislands.studio.StudioProjects
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.ui.StudioSettingsAccess
import dev.ayuislands.studio.ui.settingsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StudioSettingsProviderTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `native Settings choice is durably published before its modal dialog closes`() =
        runBlocking {
            val directory = temporary.newFolder("studio").toPath()
            val options = temporary.newFolder("options").toPath()
            val projects = StudioProjects({ emptyList() }, { null })
            val graph = StudioApplicationGraph.open(directory, options, this, projects)
            val after =
                try {
                    val sessions = assertIs<StudioApplicationState.Ready>(graph.application.start()).sessions
                    val before = sessions.document
                    withContext(Dispatchers.EDT) {
                        hostModal {
                            withTimeout(5.seconds) {
                                val opening =
                                    assertIs<StudioSettingsAccess.Ready>(
                                        graph.settingsProvider()(SettingsOpenContext.EMPTY),
                                    ).opening
                                val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "settingsSelectedTab"))
                                val published = CompletableDeferred<ModalityState>()
                                val expectedModality =
                                    withContext(Dispatchers.EDT) {
                                        opening.binding.controller.attach {
                                            val selected =
                                                sessions.document.auxiliary.ui.records
                                                    .single { it.key == address.field }
                                                    .value
                                            if (selected ==
                                                RawValue.Integer(3)
                                            ) {
                                                published.complete(ModalityState.current())
                                            }
                                        }
                                        opening.metadata(
                                            StudioMetadataCommand.UiChoice(
                                                address,
                                                FieldValue.Present(RawValue.Integer(3)),
                                            ),
                                        )
                                        ModalityState.current()
                                    }
                                assertTrue(expectedModality !== ModalityState.nonModal())
                                val callbackModality = published.await()
                                assertSame(expectedModality, callbackModality)
                                assertEquals(
                                    RawValue.Integer(3),
                                    sessions.document.auxiliary.ui.records
                                        .single {
                                            it.key ==
                                                address.field
                                        }.value,
                                )
                            }
                        }
                    }
                    assertEquals(before.currents, sessions.document.currents)
                    assertEquals(before.catalog, sessions.document.catalog)
                    assertEquals(before.shared, sessions.document.shared)
                    sessions.document
                } finally {
                    graph.close()
                }

            val reopened = StudioApplicationGraph.open(directory, options, this, projects)
            try {
                val restored = assertIs<StudioApplicationState.Ready>(reopened.application.start()).sessions.document
                assertEquals(after.auxiliary, restored.auxiliary)
                assertEquals(after.currents, restored.currents)
            } finally {
                reopened.close()
            }
        }
}
