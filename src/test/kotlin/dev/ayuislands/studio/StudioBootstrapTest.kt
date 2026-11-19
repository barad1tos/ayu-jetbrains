package dev.ayuislands.studio

import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioBootstrapTest {
    @TempDir
    lateinit var directory: Path

    private val options get() = directory.resolve("options")
    private val studio get() = directory.resolve("studio")

    @Test
    fun `imports exact legacy choices and reopens from the accepted Studio document`() {
        val original = legacySettings()
        val mappings =
            writeLegacy(
                "ayuIslandsAccentMappings.xml",
                """
                <application><component name="AyuIslandsAccentMappings">
                    <option name="projectAccents"><map>
                        <entry key="/work/z" value="#5CCFE6" />
                        <entry key="/work/a" value="#F07178" />
                    </map></option>
                </component></application>
                """.trimIndent(),
            )
        val accepted =
            open().use { store ->
                val ready = assertIs<StudioBootstrapResult.Ready>(bootstrap(store).load("first"))
                val document = ready.document
                val appearance = document.currents.global.appearance
                assertEquals(RawValue.Text("WAVEFORM"), appearance.glow.value("glowShape"))
                assertEquals(RawValue.Text("#FFCD66"), appearance.accent.value("mirageAccent"))
                assertEquals(RawValue.BooleanValue(true), appearance.accent.value("accentRotationEnabled"))
                val rotation = requireNotNull(document.runtime.rotation)
                assertEquals(1234L, rotation.lastSuccessfulStepEpochMillis)
                assertEquals(21_601_234L, rotation.schedule?.deadlineEpochMillis)
                val projectAccents = assertIs<RawValue.OrderedMap>(document.shared.mappings.value("projectAccents"))
                assertEquals(listOf("/work/z", "/work/a"), projectAccents.entries.map { it.key })
                assertEquals(
                    listOf(RawValue.Text("#5CCFE6"), RawValue.Text("#F07178")),
                    projectAccents.entries.map { it.value },
                )
                assertTrue(document.currents.projects.isEmpty())
                val custom = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
                assertEquals(appearance, custom.appearance)
                assertEquals(StoreLoad.Ready(document), store.load())
                assertContentEquals(original, Files.readAllBytes(options.resolve("ayuIslands.xml")))
                assertContentEquals(mappings, Files.readAllBytes(options.resolve("ayuIslandsAccentMappings.xml")))
                assertContentEquals(original, Files.readAllBytes(studio.resolve("source-backup/first/settings.xml")))
                assertContentEquals(mappings, Files.readAllBytes(studio.resolve("source-backup/first/mappings.xml")))
                document
            }

        writeLegacy("ayuIslands.xml", "later legacy edit")
        open().use { store ->
            assertEquals(StudioBootstrapResult.Ready(accepted), bootstrap(store).load("second"))
            assertFalse(Files.exists(studio.resolve("source-backup/second")))
        }
    }

    @Test
    fun `retry of a rolled back first write reuses the archive for identical inputs`() {
        legacySettings()
        val files = openStudioFiles(studio)
        val interrupted =
            object : StudioFiles by files {
                override fun replace(
                    source: String,
                    destination: String,
                ) {
                    if (destination == "state.xml") throw IOException("Initial durable write rejected")
                    files.replace(source, destination)
                }
            }
        val failed =
            StudioAppearanceStore(interrupted).use { store ->
                val recovery = assertIs<StudioBootstrapResult.LegacyReadOnlyRecovery>(bootstrap(store).load("first"))
                assertEquals(StoreIssue.IO, recovery.issue)
                assertEquals(StoreLoad.Empty, store.load())
                recovery.document
            }

        open().use { store ->
            val retried = assertIs<StudioBootstrapResult.Ready>(bootstrap(store).load("retry")).document
            assertEquals(failed.copy(header = failed.header.copy(transactionId = "retry")), retried)
            assertFalse(Files.exists(studio.resolve("source-backup/retry")))
        }
    }

    @Test
    fun `uncertain write waits for verified readback and reopens without recapturing legacy data`() {
        legacySettings()
        val files = openStudioFiles(studio)
        val interrupted =
            object : StudioFiles by files {
                private var blockReadback = false

                override fun replace(
                    source: String,
                    destination: String,
                ) {
                    files.replace(source, destination)
                    if (destination == "state.xml") blockReadback = true
                }

                override fun read(name: String): ByteArray? {
                    if (name == "state.xml" && blockReadback) throw IOException("Acceptance readback unavailable")
                    return files.read(name)
                }
            }

        val pending =
            StudioAppearanceStore(interrupted).use { store ->
                assertIs<StudioBootstrapResult.Recovering>(bootstrap(store).load("first")).state.journal
            }
        writeLegacy("ayuIslands.xml", "later legacy edit")
        open().use { store ->
            val reopened = assertIs<StudioBootstrapResult.Ready>(bootstrap(store).load("reopen")).document
            assertEquals(pending.candidate, reopened)
            assertEquals(StoreLoad.Ready(reopened), store.load())
            assertFalse(Files.exists(studio.resolve("source-backup/reopen")))
        }
    }

    @Test
    fun `lost preparation acknowledgement rolls back before allowing migration retry`() {
        legacySettings()
        val files = openStudioFiles(studio)
        val interrupted =
            object : StudioFiles by files {
                override fun replace(
                    source: String,
                    destination: String,
                ) {
                    files.replace(source, destination)
                    if (destination == "pending.xml") throw IOException("Preparation acknowledgement lost")
                }
            }
        StudioAppearanceStore(interrupted).use { store ->
            val recovery = assertIs<StudioBootstrapResult.LegacyReadOnlyRecovery>(bootstrap(store).load("first"))
            assertEquals("first", recovery.document.header.migration.id)
            assertEquals(StoreIssue.IO, recovery.issue)
            assertEquals(StoreLoad.Empty, store.load())
            assertFalse(Files.exists(studio.resolve("state.xml")))
        }
    }

    @Test
    fun `reopening leaves an existing appearance transaction with its recovery coordinator`() {
        legacySettings()
        val journal =
            open().use { store ->
                val accepted = assertIs<StudioBootstrapResult.Ready>(bootstrap(store).load("first")).document
                val candidate = accepted.copy(header = accepted.header.copy(revision = 1, transactionId = "apply"))
                val plan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
                assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, plan)).journal
            }
        open().use { store ->
            val recovering = assertIs<StudioBootstrapResult.Recovering>(bootstrap(store).load("reopen"))
            assertEquals(journal, recovering.state.journal)
            assertEquals(recovering.state, store.load())
        }
    }

    @Test
    fun `lock contention cannot trigger legacy capture`() {
        legacySettings()
        open().use {
            open().use { contender ->
                val issue = assertIs<StudioBootstrapResult.ReadOnly>(bootstrap(contender).load("first")).state.issue
                assertEquals(StoreIssue.LOCKED, issue)
            }
        }
        assertFalse(Files.exists(studio.resolve("source-backup")))
    }

    @Test
    fun `malformed legacy bytes stay archived without creating accepted settings`() {
        val original = writeLegacy("ayuIslands.xml", "<application>")
        open().use { store ->
            val rejected = assertIs<StudioBootstrapResult.ReadOnly>(bootstrap(store).load("first"))
            assertEquals(StoreIssue.INVALID_DATA, rejected.state.issue)
            assertEquals(StoreLoad.Empty, store.load())
        }
        assertContentEquals(original, Files.readAllBytes(studio.resolve("source-backup/first/settings.xml")))
    }

    @Test
    fun `a source read error cannot create defaults for a missing file`() {
        Files.createDirectories(options.resolve("ayuIslands.xml"))
        open().use { store ->
            val rejected = assertIs<StudioBootstrapResult.ReadOnly>(bootstrap(store).load("first"))
            assertEquals(StoreIssue.IO, rejected.state.issue)
            assertEquals(StoreLoad.Empty, store.load())
        }
        assertFalse(Files.exists(studio.resolve("source-backup")))
    }

    @Test
    fun `archive publication failure stops before the first Studio write`() {
        val original = legacySettings()
        val files = openStudioFiles(studio)
        val interrupted =
            object : StudioFiles by files {
                override fun publishNew(
                    source: String,
                    destination: String,
                ) {
                    if (destination.endsWith("/manifest.xml")) throw IOException("Archive manifest was not published")
                    files.publishNew(source, destination)
                }
            }
        StudioAppearanceStore(interrupted).use { store ->
            val rejected = assertIs<StudioBootstrapResult.ReadOnly>(bootstrap(store).load("first"))
            assertEquals(StoreIssue.IO, rejected.state.issue)
            assertEquals(StoreLoad.Empty, store.load())
        }
        assertFalse(Files.exists(studio.resolve("pending.xml")))
        assertContentEquals(original, Files.readAllBytes(options.resolve("ayuIslands.xml")))
    }

    @Test
    fun `colliding project paths keep the archived mapping without choosing an accent`() {
        val original =
            writeLegacy(
                "ayuIslandsAccentMappings.xml",
                $$"""
                <application><component name="AyuIslandsAccentMappings">
                    <option name="projectAccents"><map>
                        <entry key="$USER_HOME$/project" value="#5CCFE6" />
                        <entry key="/home/ayu/project" value="#F07178" />
                    </map></option>
                </component></application>
                """.trimIndent(),
            )
        open().use { store ->
            val blocked = assertIs<StudioBootstrapResult.ReadOnly>(bootstrap(store).load("first"))
            assertEquals(StoreIssue.CONFLICT, blocked.state.issue)
            assertEquals(StoreLoad.Empty, store.load())
        }
        assertContentEquals(original, Files.readAllBytes(studio.resolve("source-backup/first/mappings.xml")))
    }

    @Test
    fun `failed journal cleanup keeps accepted migration recoverable across restart`() {
        legacySettings()
        val files = openStudioFiles(studio)
        val interrupted =
            object : StudioFiles by files {
                override fun remove(name: String) {
                    if (name == "pending.xml") throw IOException("Accepted journal could not be removed")
                    files.remove(name)
                }
            }
        val committed =
            StudioAppearanceStore(interrupted).use { store ->
                val pending = assertIs<StudioBootstrapResult.Recovering>(bootstrap(store).load("first")).state
                assertEquals(pending, store.load())
                assertEquals(pending.journal.candidate, pending.document)
                pending.journal.candidate
            }
        writeLegacy("ayuIslands.xml", "later legacy edit")
        open().use { store ->
            assertEquals(StudioBootstrapResult.Ready(committed), bootstrap(store).load("reopen"))
            assertEquals(StoreLoad.Ready(committed), store.load())
            assertFalse(Files.exists(studio.resolve("source-backup/reopen")))
        }
    }

    private fun open(): StudioAppearanceStore = StudioAppearanceStore(openStudioFiles(studio))

    private fun bootstrap(store: StudioAppearanceStore): StudioBootstrap = StudioBootstrap(store, options, "/home/ayu")

    private fun legacySettings(): ByteArray =
        writeLegacy(
            "ayuIslands.xml",
            """
            <application><component name="AyuIslandsSettings">
                <option name="mirageAccent" value="#FFCD66" />
                <option name="glowShape" value="WAVEFORM" />
                <option name="accentRotationEnabled" value="true" />
                <option name="accentRotationIntervalHours" value="6" />
                <option name="accentRotationLastSwitchMs" value="1234" />
            </component></application>
            """.trimIndent(),
        )

    private fun writeLegacy(
        name: String,
        xml: String,
    ): ByteArray {
        Files.createDirectories(options)
        val bytes = xml.encodeToByteArray()
        Files.write(options.resolve(name), bytes)
        return bytes
    }

    private fun StudioFields.value(name: String): RawValue = records.single { it.key.name == name }.value
}
