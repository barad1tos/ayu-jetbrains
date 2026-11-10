package dev.ayuislands.integration

import com.intellij.openapi.util.JDOMUtil
import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Two real test-host processes share only a temporary configuration, never the user's IDE state. */
class NativeSchemePersistenceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `native rollback survives restart and lost acknowledgement while omitted cells follow canonical fill`() {
        val config = Files.createDirectories(directory.resolve("config"))
        val disabled = Path.of(requireNotNull(System.getProperty("idea.config.path")), "disabled_plugins.txt")
        Files.copy(disabled, config.resolve("disabled_plugins.txt"))
        runProbe("RECOVERY_SAVE", config, registerKeysEarly = true)
        runProbe("RECOVERY_READ", config, registerKeysEarly = true)
        val editableFile =
            Files.list(config.resolve("colors")).use { paths ->
                paths
                    .filter { it.fileName.toString().endsWith(".icls") }
                    .map { JDOMUtil.load(Files.readString(it)) }
                    .filter { it.getAttributeValue("name") == observation("RECOVERY_SAVE").getProperty("name") }
                    .toList()
                    .single()
            }
        assertTrue(
            editableFile.getChild("attributes")?.getChildren("option").orEmpty().none {
                it.getAttributeValue("name") == "DEFAULT_KEYWORD"
            },
        )
        runProbe("RECOVERY_CHECK", config, registerKeysEarly = true)
        val saved = observation("RECOVERY_SAVE")
        val restored = observation("RECOVERY_READ")
        val reloaded = observation("RECOVERY_CHECK")
        assertNotEquals(saved.getProperty("process"), restored.getProperty("process"))
        assertNotEquals(restored.getProperty("process"), reloaded.getProperty("process"))
        for (observed in listOf(restored, reloaded)) {
            assertEquals(saved.getProperty("color"), observed.getProperty("color"))
            assertEquals(saved.getProperty("canonical.keyword"), observed.getProperty("keyword"))
            assertEquals(saved.getProperty("canonical.keyword"), observed.getProperty("canonical.keyword"))
            assertEquals("true", observed.getProperty("keyword.present"))
        }
    }

    @Test
    fun `native scheme files preserve registered inheritance across processes`() =
        assertRoundTrip(registerKeysEarly = true)

    @Test
    fun `native scheme files account for late key registration and canonical fill`() =
        assertRoundTrip(registerKeysEarly = false)

    @Test
    fun `canonical mutation can erase an unsaved hidden dependent field across native restart`() {
        val config = Files.createDirectories(directory.resolve("config"))
        val disabled = Path.of(requireNotNull(System.getProperty("idea.config.path")), "disabled_plugins.txt")
        Files.copy(disabled, config.resolve("disabled_plugins.txt"))
        runProbe("C1_SAVE", config, registerKeysEarly = false)
        val files =
            Files.list(config.resolve("colors")).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".icls") }.toList().associateWith(Files::readAllBytes)
            }
        assertTrue(files.isNotEmpty())
        runProbe("C1_READ", config, registerKeysEarly = false)
        files.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        val saved = observation("C1_SAVE")
        val loaded = observation("C1_READ")
        val userColor = 0xFF135724.toInt().toString()
        assertNotEquals(saved.getProperty("process"), loaded.getProperty("process"))
        assertEquals("true", saved.getProperty("inventory.omitsHidden"))
        assertEquals("true", saved.getProperty("hidden.unsaved"))
        assertEquals("135724", saved.getProperty("control.before.option"))
        assertEquals("135724", saved.getProperty("control.after.option"))
        assertEquals("", saved.getProperty("hidden.after.option"))
        assertEquals(userColor, saved.getProperty("hidden.local.before"))
        assertEquals(userColor, saved.getProperty("hidden.local.after"))
        assertEquals(userColor, saved.getProperty("canonical.after"))
        assertEquals(saved.getProperty("canonical.before"), loaded.getProperty("canonical"))
        assertEquals(userColor, loaded.getProperty("control.local"))
        assertEquals("false", loaded.getProperty("hidden.local.present"))
        assertEquals(loaded.getProperty("parent.effective"), loaded.getProperty("hidden.effective"))
        assertNotEquals(userColor, loaded.getProperty("hidden.effective"))
    }

    @Test
    fun `editable mutation survives native restart without changing canonical dependents`() {
        val config = Files.createDirectories(directory.resolve("config"))
        val disabled = Path.of(requireNotNull(System.getProperty("idea.config.path")), "disabled_plugins.txt")
        Files.copy(disabled, config.resolve("disabled_plugins.txt"))
        runProbe("EDITABLE_SAVE", config, registerKeysEarly = false)
        val files =
            Files.list(config.resolve("colors")).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".icls") }.toList().associateWith(Files::readAllBytes)
            }
        assertTrue(files.isNotEmpty())
        runProbe("EDITABLE_READ", config, registerKeysEarly = false)
        files.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        val saved = observation("EDITABLE_SAVE")
        val loaded = observation("EDITABLE_READ")
        val userColor = 0xFF135724.toInt().toString()
        assertNotEquals(saved.getProperty("process"), loaded.getProperty("process"))
        assertEquals("true", saved.getProperty("inventory.omitsHidden"))
        assertEquals("true", saved.getProperty("hidden.unsaved"))
        assertEquals("135724", saved.getProperty("control.before.option"))
        assertEquals("135724", saved.getProperty("control.after.option"))
        assertEquals("135724", saved.getProperty("hidden.after.option"))
        assertEquals("135724", saved.getProperty("editable.after.option"))
        assertEquals(userColor, saved.getProperty("hidden.local.before"))
        assertEquals(userColor, saved.getProperty("hidden.local.after"))
        assertEquals(saved.getProperty("canonical.before"), saved.getProperty("canonical.after"))
        assertEquals(saved.getProperty("canonical.before"), loaded.getProperty("canonical"))
        assertNotEquals(userColor, loaded.getProperty("canonical"))
        assertEquals(userColor, saved.getProperty("editable.after"))
        assertEquals(userColor, loaded.getProperty("editable"))
        assertEquals(userColor, loaded.getProperty("control.local"))
        assertEquals("true", loaded.getProperty("hidden.local.present"))
        assertEquals(userColor, loaded.getProperty("hidden.effective"))
    }

    private fun assertRoundTrip(registerKeysEarly: Boolean) {
        val config = Files.createDirectories(directory.resolve("config"))
        val disabled = Path.of(requireNotNull(System.getProperty("idea.config.path")), "disabled_plugins.txt")
        Files.copy(disabled, config.resolve("disabled_plugins.txt"))
        runProbe("SAVE", config, registerKeysEarly)
        val files =
            Files.list(config.resolve("colors")).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".icls") }.toList().associateWith(Files::readAllBytes)
            }
        assertTrue(files.isNotEmpty(), "Native save did not create a scheme file")
        runProbe("READ", config, registerKeysEarly)
        files.forEach { (path, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(path), path.fileName.toString())
        }
        val saved = observation("SAVE")
        val loaded = observation("READ")
        assertNotEquals(saved.getProperty("process"), loaded.getProperty("process"))
        assertEquals("true", loaded.getProperty("replacementRejected"))
        assertEquals("Ayu Persistence Probe", loaded.getProperty("name"))
        assertEquals("Ayu Islands Mirage", loaded.getProperty("parent"))
        for (prefix in listOf("", "editable.")) {
            assertEquals(saved.getProperty(prefix + "name"), loaded.getProperty(prefix + "name"))
            assertEquals(saved.getProperty(prefix + "parent"), loaded.getProperty(prefix + "parent"))
            assertEquals(0x44123456.toString(), saved.getProperty(prefix + "color"))
            assertEquals(saved.getProperty(prefix + "color"), loaded.getProperty(prefix + "color"))
            assertEquals("probe-owner", loaded.getProperty(prefix + "metadata"))
            assertEquals("false", loaded.getProperty(prefix + "absent"))
            assertEquals("true", loaded.getProperty(prefix + "nullMarker"))
            assertEquals("true", saved.getProperty(prefix + "color.inherited"))
            assertEquals(
                (prefix.isEmpty() || registerKeysEarly).toString(),
                loaded.getProperty(
                    prefix + "color.inherited",
                ),
            )
            assertEquals("true", loaded.getProperty(prefix + "attributes.inherited"))
            assertEquals(" \towner\nЖ \t", saved.getProperty(prefix + "rawMetadata"))
            assertEquals("owner\nЖ", loaded.getProperty(prefix + "rawMetadata"))
            assertEquals(0x44223355.toString(), saved.getProperty(prefix + "attributes.foreground"))
            assertEquals(0xff223355.toInt().toString(), loaded.getProperty(prefix + "attributes.foreground"))
            assertEquals(
                (saved.getProperty(prefix + "projection.foreground").toInt() or 0xFF000000.toInt()).toString(),
                loaded.getProperty(prefix + "attributes.foreground"),
            )
            assertEquals(
                saved.getProperty(prefix + "projection.inherited"),
                loaded.getProperty(
                    prefix + "color.inherited",
                ),
            )
            assertEquals("2", saved.getProperty(prefix + "attributes.effects"))
            assertEquals("1", loaded.getProperty(prefix + "attributes.effects"))
            assertEquals(saved.getProperty(prefix + "attributes.font"), loaded.getProperty(prefix + "attributes.font"))
            assertEquals(
                saved.getProperty(prefix + "attributes.effect"),
                loaded.getProperty(
                    prefix + "attributes.effect",
                ),
            )
        }
        val editableFile =
            files.values
                .map { JDOMUtil.load(String(it, Charsets.UTF_8)) }
                .single { it.getAttributeValue("name") == loaded.getProperty("editable.name") }
        val inheritedColor =
            editableFile.getChild("colors").getChildren("option").singleOrNull {
                it.getAttributeValue("name") == "STICKY_LINES_HOVERED_COLOR"
            }
        assertEquals(registerKeysEarly, inheritedColor != null)
        assertEquals(registerKeysEarly.toString(), saved.getProperty("editable.registeredKey"))
        assertEquals(if (registerKeysEarly) "CARET_ROW_COLOR" else "", saved.getProperty("editable.storedFallback"))
        if (!registerKeysEarly) {
            assertEquals(saved.getProperty("editable.baseColor"), loaded.getProperty("editable.color.raw"))
        }
        assertTrue(
            editableFile.getChild("attributes").getChildren("option").none {
                it.getAttributeValue("name") == "DEFAULT_KEYWORD"
            },
        )
        assertEquals("true", saved.getProperty("editable.keyword"))
        assertEquals("true", loaded.getProperty("editable.keyword"))
    }

    private fun observation(mode: String): Properties =
        Properties().apply {
            Files.newInputStream(directory.resolve("$mode.properties")).use { load(it) }
        }

    private fun runProbe(
        mode: String,
        config: Path,
        registerKeysEarly: Boolean,
    ) {
        val runtime = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = requireNotNull(System.getProperty("ayu.scheme.probe.classpath"))
        val arguments = mutableListOf(runtime, "-ea", "-Xmx768m")
        arguments +=
            ManagementFactory.getRuntimeMXBean().inputArguments.filter {
                it.startsWith("--add-opens=") || it.startsWith("--add-exports=") || it.startsWith("-Xbootclasspath/a:")
            }
        for (name in HOST_PROPERTIES) {
            System.getProperty(name)?.let { arguments += "-D$name=$it" }
        }
        val paths =
            mapOf(
                "idea.config.path" to config,
                "idea.system.path" to directory.resolve("$mode-system"),
                "idea.log.path" to directory.resolve("$mode-log"),
                "java.io.tmpdir" to directory.resolve("$mode-tmp"),
            )
        paths.forEach { (name, path) -> arguments += "-D$name=${Files.createDirectories(path)}" }
        arguments +=
            listOf(
                "-cp",
                classpath,
                "dev.ayuislands.integration.NativeSchemeProbe",
                mode,
                directory.toString(),
                registerKeysEarly.toString(),
            )
        val output = directory.resolve("$mode-output.log")
        val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            val exited = process.waitFor(90, TimeUnit.SECONDS)
            if (!exited) captureThreads(mode, process)
            val report =
                if (!exited || process.exitValue() != 0) preserveLogs(mode, output) else output
            assertTrue(exited, "Native $mode probe timed out; log: $report")
            assertEquals(0, process.exitValue(), "Native $mode probe failed; log: $report")
            assertTrue(Files.exists(directory.resolve("$mode.properties")), "Native probe omitted its observation")
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(20, TimeUnit.SECONDS)) { "Native probe did not terminate" }
            }
        }
    }

    private fun captureThreads(
        mode: String,
        process: Process,
    ) {
        val reports = Files.createDirectories(Path.of(requireNotNull(System.getProperty("ayu.scheme.probe.reports"))))
        val command = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString()
        val diagnostic =
            ProcessBuilder(command, process.pid().toString(), "Thread.print")
                .redirectErrorStream(true)
                .redirectOutput(reports.resolve("$mode-threads.log").toFile())
                .start()
        if (!diagnostic.waitFor(10, TimeUnit.SECONDS)) {
            diagnostic.destroyForcibly()
            check(diagnostic.waitFor(10, TimeUnit.SECONDS)) { "Native probe diagnostic did not terminate" }
        }
    }

    private fun preserveLogs(
        mode: String,
        output: Path,
    ): Path {
        val reports = Files.createDirectories(Path.of(requireNotNull(System.getProperty("ayu.scheme.probe.reports"))))
        val saved = reports.resolve("$mode-output.log")
        Files.copy(output, saved, StandardCopyOption.REPLACE_EXISTING)
        val nativeLog = directory.resolve("$mode-log/idea.log")
        if (Files.exists(nativeLog)) {
            Files.copy(nativeLog, reports.resolve("$mode-idea.log"), StandardCopyOption.REPLACE_EXISTING)
        }
        return saved
    }

    companion object {
        private val HOST_PROPERTIES =
            listOf(
                "java.system.class.loader",
                "java.awt.headless",
                "idea.classpath.index.enabled",
                "idea.use.core.classloader.for.plugin.path",
                "idea.force.use.core.classloader",
                "idea.is.unit.test",
                "intellij.testFramework.rethrow.logged.errors",
                "idea.home.path",
                "idea.platform.prefix",
                "intellij.platform.runtime.repository.path",
                "jna.boot.library.path",
                "pty4j.preferred.native.folder",
                "idea.plugins.path",
                "plugin.path",
            )
    }
}
