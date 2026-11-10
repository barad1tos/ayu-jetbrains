package dev.ayuislands.studio

import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontPreset
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioMetadataStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.StudioSessionIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioMetadataStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `UI edit changes only its addressed field and preserves live draft content`() =
        runIo {
            val original =
                sessionDocument().let { source ->
                    source.copy(sessions = StudioList(listOf(requireNotNull(editedSession(source).record))))
                }
            Fixture(directory, original).use { fixture ->
                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(ui(original, 4), "ui-tab"))
                val accepted = fixture.current()

                assertEquals(RawValue.Integer(4), accepted.auxiliary.ui.value("settingsSelectedTab"))
                assertEquals(original.currents, accepted.currents)
                assertEquals(original.catalog, accepted.catalog)
                assertEquals(original.shared, accepted.shared)
                assertEquals(original.runtime, accepted.runtime)
                assertEquals(original.sessions, accepted.sessions)
                assertEquals(original.auxiliary.resources, accepted.auxiliary.resources)
                assertEquals(original.auxiliary.legacyMarkers, accepted.auxiliary.legacyMarkers)
                val unchanged =
                    original.auxiliary.ui.records
                        .filterNot { it.key.name == "settingsSelectedTab" }
                assertEquals(
                    unchanged,
                    accepted.auxiliary.ui.records
                        .filterNot { it.key.name == "settingsSelectedTab" },
                )
                assertEquals(listOf(accepted), fixture.published)
            }
        }

    @Test
    fun `an older UI command cannot overwrite a newer choice at the same field`() =
        runIo {
            Fixture(directory).use { fixture ->
                val stale = ui(fixture.initial, 3)
                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(ui(fixture.initial, 2), "newer-tab"))
                val accepted = fixture.current()

                assertIs<AcceptanceResult.NotAccepted>(fixture.writer.submit(stale, "stale-tab"))

                assertEquals(accepted, fixture.current())
                assertEquals(listOf(accepted), fixture.published)
            }
        }

    @Test
    fun `metadata field edits cannot bypass appearance acceptance or native ownership`() =
        runIo {
            Fixture(directory).use { fixture ->
                val glow =
                    fixture.initial.currents.global.appearance.glow.records
                        .single { it.key.name == "glowEnabled" }
                val enabled = assertIs<RawValue.BooleanValue>(glow.value).value
                val command =
                    StudioMetadataCommand.UiEdit(
                        FieldAddress(glow.key),
                        FieldValue.Present(glow.value),
                        FieldValue.Present(RawValue.BooleanValue(!enabled)),
                    )

                assertIs<AcceptanceResult.NotAccepted>(fixture.writer.submit(command, "invalid-owner"))
                val validUi = ui(fixture.initial, 2)
                val invalidPart = validUi.copy(address = validUi.address.copy(part = FieldPart.MapEntry("invalid")))
                assertIs<AcceptanceResult.NotAccepted>(fixture.writer.submit(invalidPart, "invalid-part"))

                assertEquals(fixture.initial, fixture.current())
                assertTrue(fixture.published.isEmpty())
            }
        }

    @Test
    fun `project initialization appends one independent current and never rewrites an existing record`() =
        runIo {
            Fixture(directory).use { fixture ->
                val key = StudioProjectKey($$"/work/$LITERAL$/project")
                val command = StudioMetadataCommand.InitializeProject(key, "First name", StudioTarget.Global)
                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(command, "project-first"))
                val accepted = fixture.current()
                val project = accepted.currents.projects.single()

                assertEquals(fixture.initial.currents.global.appearance, project.current.appearance)
                assertNotSame(fixture.initial.currents.global.appearance, project.current.appearance)
                assertNull(project.current.source)
                assertEquals(fixture.initial.catalog, accepted.catalog)
                assertEquals(fixture.initial.shared, accepted.shared)
                val repeated = fixture.writer.submit(command.copy(displayName = "Later"), "again")
                assertEquals(AcceptanceResult.NoChanges, repeated)
                assertEquals(accepted, fixture.current())
                assertEquals(listOf(accepted), fixture.published)
            }
        }

    @Test
    fun `an unavailable accepted source does not initialize a project from Global`() =
        runIo {
            Fixture(directory).use { fixture ->
                val command =
                    StudioMetadataCommand.InitializeProject(
                        StudioProjectKey("/work/new"),
                        "New project",
                        StudioTarget.Project(StudioProjectKey("/work/missing-source")),
                    )

                assertIs<AcceptanceResult.NotAccepted>(fixture.writer.submit(command, "missing-source"))

                assertEquals(fixture.initial, fixture.current())
                assertTrue(fixture.published.isEmpty())
            }
        }

    @Test
    fun `font completion survives its closed session and merges against the latest accepted revision`() =
        runIo {
            Fixture(directory).use { fixture ->
                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(ui(fixture.initial, 5), "newer-ui"))
                val latest = fixture.current()
                val entry = requireNotNull(FontCatalog.forPreset(FontPreset.AMBIENT))
                val job =
                    FontJob(OperationStamp("already-closed", 12, "font-job"), entry.familyName, FontAction.INSTALL)
                val actualFamily = "${entry.familyName} Actual"
                val command = StudioMetadataCommand.FontInstalled(job, actualFamily, "/fonts/example.ttf")

                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(command, "font-inventory"))

                val accepted = fixture.current()
                val installed = assertIs<RawValue.OrderedSet>(accepted.auxiliary.resources.value("installedFonts"))
                val paths = assertIs<RawValue.OrderedMap>(accepted.auxiliary.resources.value("installedFontFiles"))
                assertEquals(actualFamily, installed.values.last())
                assertEquals(RawValue.Text("/fonts/example.ttf"), paths.entries.single { it.key == actualFamily }.value)
                assertTrue(paths.entries.none { it.key == job.fontId })
                assertEquals(latest.currents, accepted.currents)
                assertEquals(latest.shared, accepted.shared)
                assertEquals(latest.sessions, accepted.sessions)
                assertEquals(latest.auxiliary.ui, accepted.auxiliary.ui)
                assertEquals(latest.header.revision + 1, accepted.header.revision)
            }
        }

    @Test
    fun `confirmed entitlement keeps the latest stamp while UNKNOWN writes no facts`() =
        runIo {
            Fixture(directory).use { fixture ->
                val confirmed = StudioMetadataCommand.Entitlement(rawLicense = true, nowMs = 5_000)
                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(confirmed, "licensed"))
                val accepted = fixture.current()

                assertEquals(RawValue.LongValue(5_000), accepted.runtime.legacy.value("lastKnownLicensedMs"))
                assertEquals(RawValue.Text("LICENSED"), accepted.runtime.legacy.value("lastConfirmedEntitlement"))
                assertEquals(AcceptanceResult.NoChanges, fixture.writer.submit(confirmed.copy(nowMs = 4_000), "older"))
                val unknown = StudioMetadataCommand.Entitlement(rawLicense = null, nowMs = 6_000)
                assertEquals(AcceptanceResult.NoChanges, fixture.writer.submit(unknown, "unknown"))
                assertEquals(accepted, fixture.current())
                assertEquals(fixture.initial.currents, accepted.currents)
                assertEquals(fixture.initial.auxiliary, accepted.auxiliary)
                assertEquals(listOf(accepted), fixture.published)
            }
        }

    @Test
    fun `failed decision does not publish metadata or leave an accepted generation`() =
        runIo {
            Fixture(directory).use { fixture ->
                fixture.files.arm(IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.BEFORE))

                assertIs<AcceptanceResult.NotAccepted>(fixture.writer.submit(ui(fixture.initial, 2), "failed"))

                assertEquals(fixture.initial, fixture.current())
                assertTrue(fixture.published.isEmpty())
                assertEquals(0, fixture.files.pendingFailures)
            }
        }

    @Test
    fun `accepted cleanup is settled before another metadata command`() =
        runIo {
            Fixture(directory).use { fixture ->
                fixture.files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))

                assertIs<AcceptanceResult.AcceptedCleanupPending>(
                    fixture.writer.submit(ui(fixture.initial, 2), "cleanup"),
                )
                val pending = assertIs<StoreLoad.Recovering>(fixture.store.load())
                assertEquals(listOf(pending.document), fixture.published)
                assertTrue(
                    pending.journal.recovery.owners
                        .isEmpty(),
                )
                assertTrue(pending.journal.receipts.isEmpty())
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(ui(requireNotNull(pending.document), 3), "while-pending"),
                )
                val accepted = fixture.current()
                assertEquals(RawValue.Integer(3), accepted.auxiliary.ui.value("settingsSelectedTab"))
                assertEquals(listOf(pending.document, accepted), fixture.published.distinct())
            }
        }

    @Test
    fun `queued explicit A to B to A choices settle the final intent from an unchanged view`() =
        runIo {
            Fixture(directory).use { fixture ->
                val view = fixture.initial
                val field =
                    view.auxiliary.ui.records
                        .single { it.key.name == "settingsSelectedTab" }
                val address = FieldAddress(field.key)
                val first = StudioMetadataCommand.UiChoice(address, FieldValue.Present(RawValue.Integer(4)))
                val last = StudioMetadataCommand.UiChoice(address, FieldValue.Present(field.value))

                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(first, "choose-b"))
                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(last, "choose-a"))

                val accepted = fixture.current()
                assertEquals(field.value, accepted.auxiliary.ui.value("settingsSelectedTab"))
                assertEquals(view.currents, accepted.currents)
                assertEquals(view.shared, accepted.shared)
                assertEquals(view.catalog, accepted.catalog)
                assertEquals(view.sessions, accepted.sessions)
                assertEquals(view.header.revision + 2, accepted.header.revision)
                assertEquals(2, fixture.published.size)
                assertEquals(AcceptanceResult.NoChanges, fixture.writer.submit(last, "same-a"))
            }
        }

    @Test
    fun `explicit UI choice still cannot escape its owner or address a scalar map entry`() =
        runIo {
            Fixture(directory).use { fixture ->
                val glow =
                    fixture.initial.currents.global.appearance.glow.records
                        .single { it.key.name == "glowEnabled" }
                val wrongOwner =
                    StudioMetadataCommand.UiChoice(
                        FieldAddress(glow.key),
                        FieldValue.Present(RawValue.BooleanValue(true)),
                    )
                val ui = ui(fixture.initial, 2)
                val wrongPart =
                    StudioMetadataCommand.UiChoice(
                        ui.address.copy(part = FieldPart.MapEntry("invalid")),
                        ui.after,
                    )

                assertIs<AcceptanceResult.NotAccepted>(fixture.writer.submit(wrongOwner, "wrong-owner"))
                assertIs<AcceptanceResult.NotAccepted>(fixture.writer.submit(wrongPart, "wrong-part"))

                assertEquals(fixture.initial, fixture.current())
                assertTrue(fixture.published.isEmpty())
            }
        }

    @Test
    fun `completed font removal preserves unrelated inventory order and accepted preferences`() =
        runIo {
            Fixture(directory).use { fixture ->
                val entry = requireNotNull(FontCatalog.forPreset(FontPreset.AMBIENT))
                val install = FontJob(OperationStamp("closed", 2, "install"), entry.familyName, FontAction.INSTALL)
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.FontInstalled(install, entry.familyName, "/fonts/first.ttf"),
                        "installed",
                    ),
                )
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.FontInstalled(install, "Other Family", "/fonts/other.ttf"),
                        "other-font",
                    ),
                )
                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(ui(fixture.current(), 5), "latest-ui"))
                val before = fixture.current()
                val resources = before.auxiliary.resources
                val installed = assertIs<RawValue.OrderedSet>(resources.value("installedFonts"))
                val paths = assertIs<RawValue.OrderedMap>(resources.value("installedFontFiles"))
                val removed = assertIs<RawValue.OrderedSet>(resources.value("explicitlyUninstalledFonts"))
                val job = install.copy(operation = OperationStamp("closed", 3, "remove"), action = FontAction.UNINSTALL)
                val command = StudioMetadataCommand.FontRemoved(job, entry.familyName)

                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(command, "removed"))

                val accepted = fixture.current()
                assertEquals(
                    RawValue.OrderedSet(StudioList(installed.values.filterNot { it == entry.familyName })),
                    accepted.auxiliary.resources.value("installedFonts"),
                )
                assertEquals(
                    RawValue.OrderedMap(StudioList(paths.entries.filterNot { it.key == entry.familyName })),
                    accepted.auxiliary.resources.value("installedFontFiles"),
                )
                assertEquals(
                    RawValue.OrderedSet(StudioList((removed.values + entry.familyName).distinct())),
                    accepted.auxiliary.resources.value("explicitlyUninstalledFonts"),
                )
                assertEquals(before.currents, accepted.currents)
                assertEquals(before.shared, accepted.shared)
                assertEquals(before.runtime, accepted.runtime)
                assertEquals(before.catalog, accepted.catalog)
                assertEquals(before.sessions, accepted.sessions)
                assertEquals(before.auxiliary.ui, accepted.auxiliary.ui)
                assertEquals(before.auxiliary.legacyMarkers, accepted.auxiliary.legacyMarkers)
                assertEquals(AcceptanceResult.NoChanges, fixture.writer.submit(command, "repeat-removal"))
            }
        }

    @Test
    fun `install jobs and blank families cannot remove font inventory`() =
        runIo {
            Fixture(directory).use { fixture ->
                val entry = requireNotNull(FontCatalog.forPreset(FontPreset.AMBIENT))
                val job = FontJob(OperationStamp("closed", 2, "install"), entry.familyName, FontAction.INSTALL)
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.FontInstalled(job, entry.familyName, "/fonts/first.ttf"),
                        "installed",
                    ),
                )
                val before = fixture.current()

                assertIs<AcceptanceResult.NotAccepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.FontRemoved(job, entry.familyName),
                        "wrong-action",
                    ),
                )
                assertIs<AcceptanceResult.NotAccepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.FontRemoved(job.copy(action = FontAction.UNINSTALL), "  "),
                        "empty-family",
                    ),
                )

                assertEquals(before, fixture.current())
                assertEquals(listOf(before), fixture.published)
            }
        }

    @Test
    fun `retry completes acknowledged font inventory without another accepted generation`() =
        runIo {
            Fixture(directory).use { fixture ->
                val entry = requireNotNull(FontCatalog.forPreset(FontPreset.AMBIENT))
                val job =
                    FontJob(OperationStamp("closed-view", 1, "font-install"), entry.familyName, FontAction.INSTALL)
                val command = StudioMetadataCommand.FontInstalled(job, entry.familyName, "/fonts/installed.ttf")
                fixture.files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.writer.submit(command, "font-metadata"))
                val accepted = requireNotNull(assertIs<StoreLoad.Recovering>(fixture.store.load()).document)

                val retried = fixture.writer.submit(command, "font-metadata-retry")

                assertEquals(AcceptanceResult.NoChanges, retried)
                assertEquals(accepted, fixture.current())
                assertEquals(accepted, fixture.published.last())
                assertEquals(0, fixture.files.pendingFailures)
            }
        }

    @Test
    fun `retry resolves mutation then unreadable outcome before applying more metadata`() =
        runIo {
            Fixture(directory).use { fixture ->
                val command = ui(fixture.initial, 3)
                fixture.files.arm(
                    IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                    IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                )
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.writer.submit(command, "unknown-metadata"))
                assertTrue(fixture.published.isEmpty())
                val journal = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal

                assertEquals(AcceptanceResult.NoChanges, fixture.writer.submit(command, "retry-unknown"))

                assertEquals(journal.candidate, fixture.current())
                assertEquals(listOf(journal.candidate), fixture.published)
                assertEquals(0, fixture.files.pendingFailures)
            }
        }

    @Test
    fun `retry retires proven uncommitted metadata before applying the retained intent`() =
        runIo {
            Fixture(directory).use { fixture ->
                val command = ui(fixture.initial, 4)
                fixture.files.arm(
                    IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.BEFORE),
                    IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE),
                )
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.writer.submit(command, "not-committed"))
                assertEquals(fixture.initial, assertIs<StoreLoad.Recovering>(fixture.store.load()).document)
                assertTrue(fixture.published.isEmpty())

                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(command, "retained-intent"))

                val accepted = fixture.current()
                assertEquals(RawValue.Integer(4), accepted.auxiliary.ui.value("settingsSelectedTab"))
                assertEquals(fixture.initial.header.revision + 1, accepted.header.revision)
                assertEquals("retained-intent", accepted.header.transactionId)
                assertEquals(listOf(accepted), fixture.published)
                assertEquals(0, fixture.files.pendingFailures)
            }
        }

    @Test
    fun `metadata cannot settle an empty-owner user acceptance journal`() =
        runIo {
            Fixture(directory).use { fixture ->
                val candidate =
                    fixture.initial.copy(
                        header =
                            fixture.initial.header.copy(
                                revision = fixture.initial.header.revision + 1,
                                transactionId = "user-catalog-acceptance",
                            ),
                    )
                val token =
                    assertIs<PrepareResult.Prepared>(
                        fixture.store.prepare(
                            fixture.initial.header.revision,
                            candidate,
                            RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                        ),
                    ).token
                assertIs<CommitResult.Committed>(fixture.store.commit(token, candidate.runtime))
                val pending = assertIs<StoreLoad.Recovering>(fixture.store.load())

                assertIs<AcceptanceResult.RecoveryRequired>(
                    fixture.writer.submit(ui(candidate, 2), "unrelated-metadata"),
                )

                assertEquals(pending, fixture.store.load())
                assertTrue(fixture.published.isEmpty())
            }
        }

    @Test
    fun `confirmed recovery rearms premium onboarding in the same durable candidate`() =
        runIo {
            Fixture(directory).use { fixture ->
                val marker =
                    fixture.initial.auxiliary.ui.records
                        .single { it.key.name == "premiumOnboardingShown" }
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.UiChoice(
                            FieldAddress(marker.key),
                            FieldValue.Present(RawValue.BooleanValue(true)),
                        ),
                        "premium-was-shown",
                    ),
                )
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.Entitlement(rawLicense = false, nowMs = 1_000),
                        "confirmed-free",
                    ),
                )
                val before = fixture.current()
                fixture.files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))

                assertIs<AcceptanceResult.AcceptedCleanupPending>(
                    fixture.writer.submit(
                        StudioMetadataCommand.Entitlement(rawLicense = true, nowMs = 2_000),
                        "confirmed-pro",
                    ),
                )

                val recovering = assertIs<StoreLoad.Recovering>(fixture.store.load())
                val accepted = requireNotNull(recovering.document)
                assertEquals(RawValue.Text("LICENSED"), accepted.runtime.legacy.value("lastConfirmedEntitlement"))
                assertEquals(RawValue.BooleanValue(false), accepted.auxiliary.ui.value("premiumOnboardingShown"))
                assertEquals(before.currents, accepted.currents)
                assertEquals(before.shared, accepted.shared)
                assertEquals(before.catalog, accepted.catalog)
                assertEquals(before.sessions, accepted.sessions)
                assertEquals(before.auxiliary.resources, accepted.auxiliary.resources)
                assertEquals(before.auxiliary.legacyMarkers, accepted.auxiliary.legacyMarkers)
                assertEquals(
                    before.auxiliary.ui.records
                        .filterNot { it.key == marker.key },
                    accepted.auxiliary.ui.records
                        .filterNot { it.key == marker.key },
                )
                assertEquals(accepted, fixture.published.last())
            }
        }

    @Test
    fun `initial repeated unknown and loss observations do not rearm premium onboarding`() =
        runIo {
            Fixture(directory).use { fixture ->
                val marker =
                    fixture.initial.auxiliary.ui.records
                        .single { it.key.name == "premiumOnboardingShown" }
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.UiChoice(
                            FieldAddress(marker.key),
                            FieldValue.Present(RawValue.BooleanValue(true)),
                        ),
                        "premium-was-shown",
                    ),
                )
                val observations =
                    listOf(
                        StudioMetadataCommand.Entitlement(rawLicense = true, nowMs = 1_000),
                        StudioMetadataCommand.Entitlement(rawLicense = true, nowMs = 2_000),
                        StudioMetadataCommand.Entitlement(rawLicense = null, nowMs = 3_000),
                        StudioMetadataCommand.Entitlement(rawLicense = false, nowMs = 200_000_000),
                        StudioMetadataCommand.Entitlement(rawLicense = null, nowMs = 200_000_001),
                    )

                observations.forEachIndexed { index, observation ->
                    val result = fixture.writer.submit(observation, "observation-$index")
                    assertTrue(result is AcceptanceResult.Accepted || result == AcceptanceResult.NoChanges)
                    assertEquals(
                        RawValue.BooleanValue(true),
                        fixture
                            .current()
                            .auxiliary.ui
                            .value("premiumOnboardingShown"),
                    )
                }
            }
        }

    private fun ui(
        document: StudioDocument,
        selectedTab: Int,
    ): StudioMetadataCommand.UiEdit {
        val before =
            document.auxiliary.ui.records
                .single { it.key.name == "settingsSelectedTab" }
        return StudioMetadataCommand.UiEdit(
            FieldAddress(before.key),
            FieldValue.Present(before.value),
            FieldValue.Present(RawValue.Integer(selectedTab)),
        )
    }

    @Test
    fun `font inventory seed preserves existing order and explicit uninstall choices without inventing paths`() =
        runIo {
            val initial =
                assertIs<dev.ayuislands.studio.migration.MigrationResult.Ready>(
                    dev.ayuislands.studio.migration.StudioLegacyMigration.plan(
                        StudioFixtures.capture(
                            dev.ayuislands.settings.AyuIslandsState().apply {
                                installedFonts.add("Personal font")
                                explicitlyUninstalledFonts.add("Maple Mono")
                                installedFontFiles["Personal font"] = " original paths "
                            },
                        ),
                    ),
                ).document
            Fixture(directory, initial).use { fixture ->
                val command =
                    StudioMetadataCommand.SeedFonts(
                        StudioList(listOf("Monaspace Neon", "Maple Mono", "Victor Mono")),
                    )
                assertIs<AcceptanceResult.Accepted>(fixture.writer.submit(command, "font-seed"))
                val accepted = fixture.current()
                assertEquals(
                    listOf("Personal font", "Victor Mono", "Monaspace Neon"),
                    assertIs<RawValue.OrderedSet>(accepted.auxiliary.resources.value("installedFonts")).values,
                )
                assertEquals(
                    initial.auxiliary.resources.value("installedFontFiles"),
                    accepted.auxiliary.resources.value("installedFontFiles"),
                )
                assertEquals(
                    initial.auxiliary.resources.value("explicitlyUninstalledFonts"),
                    accepted.auxiliary.resources.value("explicitlyUninstalledFonts"),
                )
                assertEquals(RawValue.BooleanValue(true), accepted.auxiliary.resources.value("installedFontsSeeded"))
                assertEquals(initial.currents, accepted.currents)
                assertEquals(initial.shared, accepted.shared)
            }
        }

    @Test
    fun `completed font inventory seed never rescans or repopulates later resources`() =
        runIo {
            Fixture(directory).use { fixture ->
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(
                        StudioMetadataCommand.SeedFonts(StudioList(emptyList())),
                        "empty-font-seed",
                    ),
                )
                val accepted = fixture.current()
                assertEquals(
                    AcceptanceResult.NoChanges,
                    fixture.writer.submit(
                        StudioMetadataCommand.SeedFonts(StudioList(listOf("Victor Mono"))),
                        "repeat-font-seed",
                    ),
                )
                assertEquals(accepted, fixture.current())
            }
        }

    @Test
    fun `unavailable font inventory probe keeps resources and records the legacy one-time attempt`() =
        runIo {
            Fixture(directory).use { fixture ->
                assertIs<AcceptanceResult.Accepted>(
                    fixture.writer.submit(StudioMetadataCommand.SeedFonts(null), "failed-font-seed"),
                )
                val accepted = fixture.current()
                assertEquals(RawValue.BooleanValue(true), accepted.auxiliary.resources.value("installedFontsSeeded"))
                assertEquals(
                    fixture.initial.auxiliary.resources.records
                        .filterNot { it.key.name == "installedFontsSeeded" },
                    accepted.auxiliary.resources.records
                        .filterNot { it.key.name == "installedFontsSeeded" },
                )
            }
        }

    private fun <T> runIo(
        context: CoroutineContext = Dispatchers.IO,
        action: suspend CoroutineScope.() -> T,
    ): T = runBlocking(context, action)

    private class Fixture(
        directory: Path,
        val initial: StudioDocument = sessionDocument(),
    ) : AutoCloseable {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        val store = StudioAppearanceStore(files)
        val published = mutableListOf<StudioDocument>()
        val writer =
            StudioMetadataStore(
                store,
                object : StudioSessionIo {
                    override suspend fun publish(update: StudioApplyUpdate) {
                        val committed = assertIs<StudioApplyUpdate.Committed>(update).document
                        val durable =
                            when (val loaded = store.load()) {
                                is StoreLoad.Ready -> loaded.document
                                is StoreLoad.Recovering -> loaded.document
                                else -> error("No accepted document for metadata publication")
                            }
                        assertEquals(durable, committed)
                        published.add(committed)
                    }

                    override suspend fun flush(record: StudioSessionRecord): DraftWriteResult =
                        error("Metadata must not flush an appearance draft")

                    override suspend fun observe(
                        request: AcceptanceRequest,
                        document: StudioDocument,
                    ): AcceptanceContext = error("Metadata must not acquire native effects")
                },
                Mutex(),
                EmptyCoroutineContext,
            )

        init {
            assertEquals(StoreLoad.Empty, store.load())
            assertEquals(JournalResult.Acknowledged, store.preserveSource(initial.header.migration))
            val token =
                assertIs<PrepareResult.Prepared>(
                    store.prepare(-1, initial, RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))),
                ).token
            assertIs<CommitResult.Committed>(store.commit(token, initial.runtime))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
        }

        fun current(): StudioDocument = assertIs<StoreLoad.Ready>(store.load()).document

        override fun close() = store.close()
    }
}
