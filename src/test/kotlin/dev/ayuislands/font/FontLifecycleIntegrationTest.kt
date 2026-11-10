package dev.ayuislands.font

import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioFontRequest
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.persistence.StudioMetadataStore
import dev.ayuislands.studio.session.StudioFonts
import dev.ayuislands.studio.session.StudioSessionIo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Actual metadata persistence replaces the removed legacy inventory mutation helper. */
class FontLifecycleIntegrationTest {
    @TempDir lateinit var directory: Path

    @AfterTest fun cleanup() = unmockkAll()

    @Test fun `install delete and reinstall round trip preserves appearance and clears only its uninstall marker`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val writer = writer(fixture)
                val path =
                    directory
                        .resolve("Maple.ttf")
                        .toFile()
                        .apply { writeText("font") }
                        .absolutePath
                assertIs<AcceptanceResult.Accepted>(writer.submit(installed("Maple Mono", path), "install"))
                assertEquals(RawValue.Text(path), files(fixture).entries.single { it.key == "Maple Mono" }.value)
                assertIs<AcceptanceResult.Accepted>(writer.submit(removed(), "remove"))
                assertFalse("Maple Mono" in families(fixture, "installedFonts"))
                assertTrue("Maple Mono" in families(fixture, "explicitlyUninstalledFonts"))
                assertIs<AcceptanceResult.Accepted>(writer.submit(installed("Maple Mono", path), "reinstall"))
                assertTrue("Maple Mono" in families(fixture, "installedFonts"))
                assertFalse("Maple Mono" in families(fixture, "explicitlyUninstalledFonts"))
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertTrue(fixture.effects.all { it.inputs.isEmpty() })
            }
        }

    @Test fun `removing one family preserves other family paths and insertion order`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val writer = writer(fixture)
                writer.submit(installed("Maple Mono", "/fonts/maple.ttf"), "maple")
                writer.submit(installed("Victor Mono", "/fonts/victor.ttf"), "victor")
                val before = files(fixture)
                assertIs<AcceptanceResult.Accepted>(writer.submit(removed(), "remove"))
                assertEquals(before.entries.filterNot { it.key == "Maple Mono" }, files(fixture).entries)
                assertTrue("Victor Mono" in families(fixture, "installedFonts"))
            }
        }

    @Test fun `external file deletion is observed as corrupted against exact accepted inventory`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val file = directory.resolve("Maple.ttf").toFile().apply { writeText("font") }
                writer(fixture).submit(installed("Maple Mono", file.absolutePath), "installed")
                assertTrue(file.delete())
                mockkStatic(GraphicsEnvironment::class)
                val environment = mockk<GraphicsEnvironment>()
                every { GraphicsEnvironment.getLocalGraphicsEnvironment() } returns environment
                every { environment.availableFontFamilyNames } returns arrayOf("JetBrains Mono")
                val resources = fixture.document().auxiliary.resources
                val facts = StudioFonts.capture(resources, EmptyCoroutineContext)
                assertEquals(
                    FontAvailability.CORRUPTED,
                    StudioFonts.availability(
                        StudioFontRequest.Enabled(FontSettings.fromPreset(FontPreset.AMBIENT)),
                        resources,
                        facts,
                    ),
                )
            }
        }

    @Test fun `startup seeding preserves an explicit uninstall even when the JVM still sees that family`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val writer = writer(fixture)
                writer.submit(installed("Maple Mono", "/fonts/maple.ttf"), "install")
                writer.submit(removed(), "remove")
                assertIs<AcceptanceResult.Accepted>(
                    writer.submit(
                        StudioMetadataCommand.SeedFonts(StudioList(listOf("Maple Mono", "Victor Mono"))),
                        "seed",
                    ),
                )
                assertFalse("Maple Mono" in families(fixture, "installedFonts"))
                assertTrue("Maple Mono" in families(fixture, "explicitlyUninstalledFonts"))
                assertEquals(fixture.initial.currents, fixture.document().currents)
            }
        }

    private fun writer(fixture: StudioApplyFixture): StudioMetadataStore {
        val io = mockk<StudioSessionIo>()
        coEvery { io.publish(any()) } answers {
            val published = firstArg<StudioApplyUpdate.Committed>().document
            val durable = fixture.document()
            assertEquals(durable.header, published.header)
            assertEquals(durable.auxiliary.resources, published.auxiliary.resources)
        }
        return StudioMetadataStore(fixture.store, io, fixture.mutex, EmptyCoroutineContext)
    }

    private fun installed(
        family: String,
        paths: String,
    ) = StudioMetadataCommand.FontInstalled(job(family, FontAction.INSTALL), family, paths)

    private fun removed() = StudioMetadataCommand.FontRemoved(job("Maple Mono", FontAction.UNINSTALL), "Maple Mono")

    private fun job(
        family: String,
        action: FontAction,
    ) = FontJob(OperationStamp("closed-onboarding", 1, family), family, action)

    private fun families(
        fixture: StudioApplyFixture,
        name: String,
    ) = assertIs<RawValue.OrderedSet>(
        fixture
            .document()
            .auxiliary.resources.records
            .single {
                it.key.name ==
                    name
            }.value,
    ).values

    private fun files(fixture: StudioApplyFixture) =
        assertIs<RawValue.OrderedMap>(
            fixture
                .document()
                .auxiliary.resources.records
                .single {
                    it.key.name ==
                        "installedFontFiles"
                }.value,
        )
}
