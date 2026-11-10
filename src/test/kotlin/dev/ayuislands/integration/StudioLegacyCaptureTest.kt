package dev.ayuislands.integration

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.StoreDataException
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import dev.ayuislands.studio.persistence.legacyFileNames
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StudioLegacyCaptureTest {
    @TempDir
    lateinit var directory: Path
    private var allocatedIds = 0
    private val options: Path get() = directory.resolve("options")
    private val storeDirectory: Path get() = directory.resolve("studio")
    private val userHome = "/home/ayu-test"

    @Test
    fun `typed getters cover the complete catalog and retain actual fresh bean values`() {
        val fields = StudioLegacyFields.capture(freshSettingsState(), SyntaxIntensityBaseState(), AccentMappingsState())
        val expected = StudioFixtures.fields()

        assertEquals(197, fields.records.size)
        assertEquals(StudioFieldCatalog.fields.map { it.source to it.name }.toSet(), keys(fields))
        assertEquals(values(expected), values(fields))
    }

    @Test
    fun `loaded typed capture preserves float bits collection order and dormant choices`() {
        val settings =
            AyuIslandsState().apply {
                glowEnabled = false
                glowShape = "FUTURE_SHAPE"
                forceOverrides.addAll(listOf("future-element", "links", "caretRow"))
            }
        val syntax =
            SyntaxIntensityBaseState().apply {
                customStyles["z-token"] = "future-style"
                customStyles["a-token"] = "BOLD"
            }
        val mappings =
            AccentMappingsState().apply {
                dominanceThreshold = Float.fromBits(0x7fc00123)
                dominanceMarginRatio = -0.0f
                projectAccents["/z-project"] = "unknown-color"
                projectAccents["/a-project"] = "#5CCFE6"
            }
        val before = StudioFixtures.fields(settings, syntax, mappings)

        val fields = StudioLegacyFields.capture(settings, syntax, mappings)

        assertEquals(values(before), values(fields))
        assertEquals(RawValue.FloatBits(0x7fc00123), value(fields, "dominanceThreshold"))
        assertEquals(RawValue.FloatBits((-0.0f).toRawBits()), value(fields, "dominanceMarginRatio"))
        assertEquals(values(before), values(StudioFixtures.fields(settings, syntax, mappings)))
    }

    @Test
    fun `absent files and an existing component with omitted options retain different v2 defaults`() {
        store().use { store ->
            val fresh = capture(store).capture()
            assertEquals(values(StudioFixtures.fields()), values(fresh.fields))
            assertTrue(fresh.source.archives.all { it.file == LegacyFile.Absent })

            writeComponent(LegacyStore.SETTINGS, "")
            val omitted = capture(store).capture()

            assertEquals(values(StudioFixtures.fields(AyuIslandsState())), values(omitted.fields))
            assertNotEquals(values(fresh.fields), values(omitted.fields))
            assertIs<LegacyFile.Present>(
                omitted.source.archives
                    .single { it.store == LegacyStore.SETTINGS }
                    .file,
            )
        }
    }

    @Test
    fun `native application components capture all mixed values and exact file archives without services`() {
        writeMixedFixture()
        store().use { store ->
            val actual = capture(store).capture()

            assertEquals(values(StudioFixtures.load("mixed-user-values").fields), values(actual.fields))
            for ((legacyStore, file) in actual.source.archives) {
                val source = Files.readAllBytes(options.resolve(legacyFileNames.getValue(legacyStore)))
                assertContentEquals(source, assertIs<LegacyFile.Present>(file).bytes.toByteArray())
            }
        }
    }

    @Test
    fun `field lexemes and unknown options retain quotes entities whitespace and Unicode byte exact`() {
        val known = "<option  value = '&#35;5CCFE6' name=\"mirageAccent\" />"
        val unknown = "<option name='futureGlow' value='&#65;β' ><!-- keep -->\r\n</option>"
        val original = writeComponent(LegacyStore.SETTINGS, "$known\r\n$unknown\r\n<option name='lastShuffleColor'/>")
        store().use { store ->
            val captured = capture(store).capture()
            val accent = captured.fields.records.single { it.key.name == "mirageAccent" }
            val history = captured.fields.records.single { it.key.name == "lastShuffleColor" }

            assertEquals(RawValue.Text("#5CCFE6"), accent.value)
            assertEquals(SourcePresence.VALUE, accent.origin.presence)
            assertContentEquals(known.encodeToByteArray(), requireNotNull(accent.origin.lexeme).toByteArray())
            assertEquals(SourcePresence.NULL, history.origin.presence)
            assertTrue(
                captured.fields.extensions.any { it.bytes.toByteArray().contentEquals(unknown.encodeToByteArray()) },
            )
            val archive = captured.source.archives.single { it.store == LegacyStore.SETTINGS }
            assertContentEquals(original, assertIs<LegacyFile.Present>(archive.file).bytes.toByteArray())
        }
    }

    @Test
    fun `unchanged complete source manifest is reused after the store is reopened`() {
        writeComponent(LegacyStore.SETTINGS, "<option name='glowShape' value='WAVEFORM'/>")
        val source =
            store().use { store ->
                val first = capture(store).capture()
                assertEquals(JournalResult.Acknowledged, store.preserveSource(first.source))
                first.source
            }
        store().use { reopened ->
            val retried = capture(reopened).capture()

            assertEquals(source, retried.source)
            assertEquals(source, reopened.findSource(source.archives))
        }
    }

    @Test
    fun `changed source gets a new identity while the older archive remains untouched`() {
        val original = writeComponent(LegacyStore.SETTINGS, "<option name='mirageAccent' value='#5CCFE6'/>")
        store().use { store ->
            val first = capture(store).capture()
            assertEquals(JournalResult.Acknowledged, store.preserveSource(first.source))
            val archive = storeDirectory.resolve("source-backup/${first.source.id}/settings.xml")
            writeComponent(LegacyStore.SETTINGS, "<option name='mirageAccent' value='#FFCD66'/>")

            val changed = capture(store).capture()

            assertNotEquals(first.source.id, changed.source.id)
            assertContentEquals(original, Files.readAllBytes(archive))
            assertEquals(RawValue.Text("#FFCD66"), value(changed.fields, "mirageAccent"))
        }
    }

    @Test
    fun `multiple identical complete manifests choose the matching identity deterministically`() {
        writeComponent(LegacyStore.SETTINGS, "<option name='glowEnabled' value='false'/>")
        store().use { store ->
            val source = capture(store).capture().source
            assertEquals(JournalResult.Acknowledged, store.preserveSource(source.copy(id = "z-source")))
            assertEquals(JournalResult.Acknowledged, store.preserveSource(source.copy(id = "a-source")))

            assertEquals("a-source", capture(store).capture().source.id)
            assertEquals("a-source", store.findSource(source.archives)?.id)
        }
    }

    @Test
    fun `interrupted archive without a manifest is retained but never becomes trusted source evidence`() {
        val incomplete = storeDirectory.resolve("source-backup/incomplete/settings.xml")
        Files.createDirectories(incomplete.parent)
        val partial = "partial archive".encodeToByteArray()
        Files.write(incomplete, partial)
        store().use { store ->
            val captured = capture(store).capture()

            assertNotEquals("incomplete", captured.source.id)
            assertContentEquals(partial, Files.readAllBytes(incomplete))
            assertEquals(null, store.findSource(captured.source.archives))
        }
    }

    @Test
    fun `a complete manifest with damaged bytes fails closed without replacing either source`() {
        val original = writeComponent(LegacyStore.SETTINGS, "<option name='glowEnabled' value='false'/>")
        store().use { store ->
            val source = capture(store).capture().source
            assertEquals(JournalResult.Acknowledged, store.preserveSource(source))
            val archive = storeDirectory.resolve("source-backup/${source.id}/settings.xml")
            val damaged = "damaged archive".encodeToByteArray()
            Files.write(archive, damaged)

            assertFailsWith<StoreDataException> { capture(store).capture() }

            val currentSource = options.resolve(legacyFileNames.getValue(LegacyStore.SETTINGS))
            assertContentEquals(original, Files.readAllBytes(currentSource))
            assertContentEquals(damaged, Files.readAllBytes(archive))
            assertFalse(Files.exists(storeDirectory.resolve("state.xml")))
        }
    }

    @Test
    fun `file read and XML validation failures never become a fresh source`() {
        Files.createDirectories(options.resolve(legacyFileNames.getValue(LegacyStore.SETTINGS)))
        store().use { store ->
            assertFailsWith<IOException> { capture(store).capture() }
        }
        Files.delete(options.resolve(legacyFileNames.getValue(LegacyStore.SETTINGS)))
        val invalid = "<!DOCTYPE application [<!ENTITY surprise 'replacement'>]><application>&surprise;</application>"
        Files.writeString(options.resolve(legacyFileNames.getValue(LegacyStore.SETTINGS)), invalid)
        store().use { store ->
            val failure = assertFailsWith<StoreDataException> { capture(store).capture() }
            assertEquals(StoreIssue.INVALID_DATA, failure.issue)
            assertEquals(invalid, Files.readString(options.resolve(legacyFileNames.getValue(LegacyStore.SETTINGS))))
            assertFalse(Files.exists(storeDirectory.resolve("state.xml")))
        }
    }

    @Test
    fun `cold mapping migration expands the v2 home macro without changing original bytes`() {
        val original =
            writeComponent(
                LegacyStore.MAPPINGS,
                $$"<option name='projectAccents'><map><entry key='$USER_HOME$/one' value='#5CCFE6'/></map></option>",
            )
        store().use { store ->
            val captured = capture(store).capture()
            val pins = assertIs<RawValue.OrderedMap>(value(captured.fields, "projectAccents"))

            assertEquals(listOf("$userHome/one"), pins.entries.map { it.key })
            val archive = captured.source.archives.single { it.store == LegacyStore.MAPPINGS }
            assertContentEquals(original, assertIs<LegacyFile.Present>(archive.file).bytes.toByteArray())
        }
    }

    @Test
    fun `macro collision preserves both original entries and blocks the unsafe transformation`() {
        val original =
            writeComponent(
                LegacyStore.MAPPINGS,
                "<option name='projectAccents'><map>" +
                    $$"<entry key='$USER_HOME$/one' value='#5CCFE6'/>" +
                    "<entry key='$userHome/one' value='#FFCD66'/></map></option>",
            )
        store().use { store ->
            val failure = assertFailsWith<StoreDataException> { capture(store).capture() }

            assertEquals(StoreIssue.CONFLICT, failure.issue)
            val currentSource = options.resolve(legacyFileNames.getValue(LegacyStore.MAPPINGS))
            assertContentEquals(original, Files.readAllBytes(currentSource))
            assertFalse(Files.exists(storeDirectory.resolve("state.xml")))
        }
    }

    private fun store(): StudioAppearanceStore =
        StudioAppearanceStore(openStudioFiles(storeDirectory)).also { assertEquals(StoreLoad.Empty, it.load()) }

    private fun capture(store: StudioAppearanceStore) =
        StudioLegacyCapture(options, store, { "capture-${++allocatedIds}" }, userHome)

    private fun writeComponent(
        store: LegacyStore,
        content: String,
    ): ByteArray {
        Files.createDirectories(options)
        val xml =
            "<application>\r\n<component name='${componentName(store)}'>\r\n" +
                "$content\r\n</component>\r\n</application>"
        val bytes = xml.encodeToByteArray()
        Files.write(options.resolve(legacyFileNames.getValue(store)), bytes)
        return bytes
    }

    private fun writeMixedFixture() {
        Files.createDirectories(options)
        for (store in LegacyStore.entries) {
            val resourceName = "/studio/legacy/mixed-user-values/${store.name.lowercase()}.xml"
            val resource = requireNotNull(javaClass.getResourceAsStream(resourceName))
            val state = resource.use { StudioXml.parse(it.readBytes()) }
            val component = state.copy(name = "component", attributes = mapOf("name" to componentName(store)))
            val bytes = StudioXml.write(StudioXmlNode("application", children = listOf(component)))
            Files.write(options.resolve(legacyFileNames.getValue(store)), bytes)
        }
    }

    private fun componentName(store: LegacyStore): String =
        when (store) {
            LegacyStore.SETTINGS -> "AyuIslandsSettings"
            LegacyStore.SYNTAX -> "AyuIslandsSyntaxIntensityState"
            LegacyStore.MAPPINGS -> "AyuIslandsAccentMappings"
        }

    private fun keys(fields: StudioFields) = fields.records.map { it.key.source to it.key.name }.toSet()

    private fun values(fields: StudioFields) = fields.records.associate { it.key to it.value }

    private fun value(
        fields: StudioFields,
        name: String,
    ): RawValue = fields.records.single { it.key.name == name }.value
}
