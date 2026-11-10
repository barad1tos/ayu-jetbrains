package dev.ayuislands.integration

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontPreset
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.studio.StudioApplicationGraph
import dev.ayuislands.studio.StudioApplicationState
import dev.ayuislands.studio.StudioProjects
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.session.StudioSessionDispatchers
import dev.ayuislands.studio.sessionRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Exercises the production composition with isolated files and the SDK application. */
class StudioApplicationGraphTest {
    private val dispatchers by lazy { StudioSessionDispatchers() }

    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @After
    fun restoreMarketplaceProbe() {
        unmockkObject(LicenseChecker)
    }

    @Test
    fun `quick command rejects a replaced captured context without registering or changing user work`() =
        runBlocking {
            val graph =
                StudioApplicationGraph.open(
                    temporary.newFolder("quick-stale").toPath(),
                    temporary.newFolder("quick-options").toPath(),
                    this,
                    StudioProjects({ emptyList() }, { null }),
                )
            try {
                val ready = assertIs<StudioApplicationState.Ready>(graph.application.start())
                val before = ready.sessions.document
                val captured = TargetObservation(StudioTarget.Global, 99, TargetPresence.ACTIVE)

                val result =
                    assertIs<ImmediateExecutionResult.Complete>(
                        graph.submitQuick(captured, StudioQuickCommand.GlowEnabled(true)),
                    )

                assertEquals(
                    listOf(AcceptanceBlock.TARGET_REPLACED),
                    assertIs<AcceptanceResult.Blocked>(result.acceptance).reasons,
                )
                assertSame(before, ready.sessions.document)
                assertEquals(emptyList(), ready.sessions.liveSessions)
            } finally {
                graph.close()
            }
        }

    @Test
    fun `quick no change retires its application owned work without creating a Settings binding`() =
        runBlocking {
            val graph =
                StudioApplicationGraph.open(
                    temporary.newFolder("quick-current").toPath(),
                    temporary.newFolder("current-options").toPath(),
                    this,
                    StudioProjects({ emptyList() }, { null }),
                )
            try {
                val ready = assertIs<StudioApplicationState.Ready>(graph.application.start())
                val before = ready.sessions.document
                val current =
                    before.currents.global.appearance.glow.records
                        .single { it.key.name == "glowEnabled" }
                        .value
                val captured = TargetObservation(StudioTarget.Global, 1, TargetPresence.ACTIVE)

                val result =
                    assertIs<ImmediateExecutionResult.Complete>(
                        graph.submitQuick(
                            captured,
                            StudioQuickCommand.GlowEnabled((current as RawValue.BooleanValue).value),
                        ),
                    )

                assertEquals(AcceptanceResult.NoChanges, result.acceptance)
                assertEquals(before.currents, ready.sessions.document.currents)
                assertEquals(before.catalog, ready.sessions.document.catalog)
                assertEquals(before.shared, ready.sessions.document.shared)
                assertEquals(emptyList(), ready.sessions.liveSessions)
            } finally {
                graph.close()
            }
        }

    @Test
    fun `font choice accepted during download requires review of the original onboarding intent`() =
        runBlocking {
            mockkObject(LicenseChecker)
            every { LicenseChecker.isLicensed() } returns true
            val graph =
                StudioApplicationGraph.open(
                    temporary.newFolder("download-baseline").toPath(),
                    temporary.newFolder("download-options").toPath(),
                    this,
                    StudioProjects({ emptyList() }, { null }),
                )
            try {
                val ready = assertIs<StudioApplicationState.Ready>(graph.application.start())
                val baseline = ready.sessions.document
                val original = sessionRecord(baseline)
                val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "fontPresetName"))
                val changed =
                    StudioDraftPolicy.edit(
                        original.work.drafts.single(),
                        address,
                        FieldValue.Present(RawValue.Text(FontPreset.NEON.name)),
                    )
                val record =
                    original.copy(
                        id = "later-font-choice",
                        generation = 1,
                        work = original.work.copy(drafts = StudioList(listOf(changed))),
                    )
                withContext(Dispatchers.EDT) {
                    val outcome =
                        ready.sessions.coordinator.submit(
                            AcceptanceRequest(record, "later-font-choice", 1, AcceptanceOrigin.SETTINGS),
                        )
                    val detail =
                        when (outcome) {
                            is AcceptanceResult.NotAccepted ->
                                "code=${outcome.failure.code}, owner=${outcome.failure.owner}"
                            is AcceptanceResult.RecoveryRequired ->
                                "code=${outcome.failure.code}, owner=${outcome.failure.owner}"
                            else -> outcome::class.simpleName.orEmpty()
                        }
                    assertIs<AcceptanceResult.Accepted>(outcome, detail)
                }
                val latest = ready.sessions.document
                val captured = TargetObservation(StudioTarget.Global, 1, TargetPresence.ACTIVE)

                val result =
                    assertIs<ImmediateExecutionResult.Complete>(
                        graph.submitQuick(
                            captured,
                            StudioQuickCommand.Onboarding(FontPreset.WHISPER, null),
                            baseline,
                        ),
                    )

                val review = assertIs<AcceptanceResult.ReviewRequired>(result.acceptance)
                val field = review.items.filterIsInstance<AcceptanceReview.Field>().single()
                assertEquals(address, field.conflict.address)
                assertEquals(latest.currents, ready.sessions.document.currents)
                assertEquals(latest.shared, ready.sessions.document.shared)
            } finally {
                graph.close()
            }
        }

    @Test
    fun `a delayed command preserves unrelated metadata accepted after its captured baseline`() =
        runBlocking {
            val graph =
                StudioApplicationGraph.open(
                    temporary.newFolder("metadata-baseline").toPath(),
                    temporary.newFolder("metadata-options").toPath(),
                    this,
                    StudioProjects({ emptyList() }, { null }),
                )
            try {
                val ready = assertIs<StudioApplicationState.Ready>(graph.application.start())
                val baseline = ready.sessions.document
                val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "settingsSelectedTab"))
                assertIs<AcceptanceResult.Accepted>(
                    graph.submitMetadata(
                        StudioMetadataCommand.UiChoice(address, FieldValue.Present(RawValue.Integer(4))),
                    ),
                )
                val latest = ready.sessions.document
                val enabled =
                    (
                        baseline.currents.global.appearance.glow.records
                            .single {
                                it.key.name == "glowEnabled"
                            }.value as RawValue.BooleanValue
                    ).value
                val result =
                    assertIs<ImmediateExecutionResult.Complete>(
                        graph.submitQuick(
                            TargetObservation(StudioTarget.Global, 1, TargetPresence.ACTIVE),
                            StudioQuickCommand.GlowEnabled(enabled),
                            baseline,
                        ),
                    )

                assertEquals(AcceptanceResult.NoChanges, result.acceptance)
                assertEquals(latest.auxiliary.ui, ready.sessions.document.auxiliary.ui)
                assertEquals(latest.currents, ready.sessions.document.currents)
            } finally {
                graph.close()
            }
        }

    @Test
    fun `one application graph publishes metadata to its retained sessions and reloads without resetting appearance`() =
        runBlocking {
            val directory = temporary.newFolder("studio").toPath()
            val options = temporary.newFolder("options").toPath()
            val graph = StudioApplicationGraph.open(directory, options, this, StudioProjects({ emptyList() }, { null }))
            val accepted =
                try {
                    val ready = assertIs<StudioApplicationState.Ready>(graph.application.start())
                    assertSame(ready.sessions, graph.sessions)
                    assertSame(
                        ready.sessions,
                        assertIs<StudioApplicationState.Ready>(graph.application.start()).sessions,
                    )
                    val before = ready.sessions.document
                    val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "settingsSelectedTab"))
                    assertIs<AcceptanceResult.Accepted>(
                        graph.submitMetadata(
                            StudioMetadataCommand.UiChoice(address, FieldValue.Present(RawValue.Integer(3))),
                        ),
                    )
                    val after = ready.sessions.document
                    assertEquals(before.currents, after.currents)
                    assertEquals(before.catalog, after.catalog)
                    assertEquals(before.shared, after.shared)
                    assertEquals(
                        RawValue.Integer(3),
                        after.auxiliary.ui.records
                            .single { it.key == address.field }
                            .value,
                    )
                    after
                } finally {
                    graph.close()
                }
            assertIs<StudioApplicationState.Closed>(graph.application.state)
            assertNull(graph.sessions)
            val reopened =
                StudioApplicationGraph.open(
                    directory,
                    options,
                    this,
                    StudioProjects({ emptyList() }, { null }),
                )
            try {
                val ready = assertIs<StudioApplicationState.Ready>(reopened.application.start())
                assertEquals(accepted.currents, ready.sessions.document.currents)
                assertEquals(accepted.catalog, ready.sessions.document.catalog)
                assertEquals(accepted.auxiliary, ready.sessions.document.auxiliary)
            } finally {
                reopened.close()
            }
        }

    @Test
    fun `project closed after startup census does not prevent later appearance changes`() =
        runBlocking {
            mockkObject(LicenseChecker)
            every { LicenseChecker.isLicensed() } returns false
            var isClosed = false
            val closing = mockk<Project>()
            every { closing.isDefault } returns false
            every { closing.isDisposed } answers { isClosed }
            val projects =
                StudioProjects(
                    readProjects = { if (isClosed) emptyList() else listOf(closing) },
                    readFocus = { null },
                    readKey = {
                        isClosed = true
                        "/work/closed"
                    },
                )
            val graph =
                StudioApplicationGraph.open(
                    temporary.newFolder("closed-project").toPath(),
                    temporary.newFolder("closed-project-options").toPath(),
                    this,
                    projects,
                )
            try {
                val ready = assertIs<StudioApplicationState.Ready>(graph.application.start())
                val before = ready.sessions.document
                val enabled =
                    before.currents.global.appearance.glow.records
                        .single { it.key.name == "glowEnabled" }
                        .value as RawValue.BooleanValue
                val result =
                    assertIs<ImmediateExecutionResult.Complete>(
                        graph.submitQuick(
                            TargetObservation(StudioTarget.Global, 1, TargetPresence.ACTIVE),
                            StudioQuickCommand.GlowEnabled(!enabled.value),
                        ),
                    )

                assertIs<AcceptanceResult.Accepted>(result.acceptance)
                assertEquals(emptyList(), ready.sessions.document.currents.projects)
                assertEquals(before.shared, ready.sessions.document.shared)
            } finally {
                graph.close()
            }
        }

    @Test
    fun `invalid persisted authority never constructs sessions or replaces the users file`() =
        runBlocking {
            val directory = temporary.newFolder("broken").toPath()
            val options = temporary.newFolder("options").toPath()
            val invalid = "<unrecognized-user-state/>".toByteArray()
            val path = directory.resolve("state.xml")
            withContext(dispatchers.io) { Files.write(path, invalid) }
            val graph = StudioApplicationGraph.open(directory, options, this, StudioProjects({ emptyList() }, { null }))
            try {
                assertIs<StudioApplicationState.Unavailable>(graph.application.start())
                assertNull(graph.sessions)
                assertIs<StudioApplicationState.Unavailable>(graph.application.retry())
                assertNull(graph.sessions)
                assertContentEquals(invalid, withContext(dispatchers.io) { Files.readAllBytes(path) })
            } finally {
                graph.close()
            }
        }
}
