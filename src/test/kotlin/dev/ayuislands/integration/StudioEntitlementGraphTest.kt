package dev.ayuislands.integration

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.studio.StudioApplicationGraph
import dev.ayuislands.studio.StudioApplicationState
import dev.ayuislands.studio.StudioProjects
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.legacyFileNames
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real store, migration, metadata, immediate acceptance, and native availability composition. */
class StudioEntitlementGraphTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()
    private val rawLicense = AtomicReference<Boolean?>(null)
    private val rawReads = AtomicInteger()

    @Before
    fun observeMarketplace() {
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensed() } answers {
            rawReads.incrementAndGet()
            rawLicense.get()
        }
    }

    @After
    fun restoreNativeOwnership() {
        try {
            SwingUtilities.invokeAndWait {
                val owners =
                    listOf(EditorSchemeOwner.Syntax, EditorSchemeOwner.Vcs, EditorSchemeOwner.AlwaysOn) +
                        AccentElementId.entries.map { EditorSchemeOwner.Element(it) }
                for (scheme in EditorColorsManager.getInstance().allSchemes) {
                    owners.forEach { owner -> EditorSchemeOverrides.restore(scheme, owner) }
                }
                EditorSchemeOverrides.reset()
            }
        } finally {
            unmockkObject(LicenseChecker)
        }
    }

    @Test
    fun `unknown Marketplace observation schedules retry without accepting facts or initializing defaults`() {
        val directory = temporary.newFolder("unknown-state").toPath()
        val options = options("unknown-options")
        runBlocking {
            val graph = StudioApplicationGraph.open(directory, options, this, projects())
            try {
                val owner = assertIs<StudioApplicationState.Ready>(graph.application.start()).sessions
                val before = owner.document

                assertEquals(5_000L, graph.refreshEntitlement())

                assertEquals(before, owner.document)
                assertTrue(owner.liveSessions.isEmpty())
                assertNull(graph.immediate.retryPending())
            } finally {
                graph.close()
            }
        }
        assertFalse(Files.exists(directory.resolve("pending.xml")))
    }

    @Test
    fun `later confirmed Pro settles first activation through the shared owner and preserves imported choices`() {
        val directory = temporary.newFolder("activation-state").toPath()
        val options = options("activation-options")
        runBlocking {
            val graph = StudioApplicationGraph.open(directory, options, this, projects())
            try {
                val owner = assertIs<StudioApplicationState.Ready>(graph.application.start()).sessions
                val before = owner.document
                rawLicense.set(true)

                val delay = graph.refreshEntitlement()

                assertTrue(requireNotNull(delay) > 5_000L)
                val accepted = owner.document
                assertEquals("LICENSED", confirmation(accepted))
                assertEquals(before.currents, accepted.currents)
                assertEquals(before.shared, accepted.shared)
                assertEquals(before.catalog, accepted.catalog)
                for (name in listOf("proDefaultsApplied", "workspaceDefaultsApplied")) {
                    assertEquals(
                        RawValue.BooleanValue(true),
                        accepted.auxiliary.legacyMarkers.records
                            .single {
                                it.key.name ==
                                    name
                            }.value,
                    )
                }
                assertTrue(accepted.sessions.any { it.availability == SessionAvailability.RETIRED })
                assertTrue(owner.liveSessions.isEmpty())
                assertNull(graph.immediate.retryPending())

                graph.refreshEntitlement()

                assertEquals(accepted.sessions, owner.document.sessions)
                assertEquals(accepted.currents, owner.document.currents)
                assertEquals(accepted.shared, owner.document.shared)
            } finally {
                graph.close()
            }
        }
        assertFalse(Files.exists(directory.resolve("pending.xml")))
    }

    @Test
    fun `queued license observation reads Marketplace after older metadata settles and keeps latest confirmation`() {
        val directory = temporary.newFolder("queued-state").toPath()
        val options = options("queued-options", initialized = true)
        val files = PausingFiles(openStudioFiles(directory))
        val store = StudioAppearanceStore(files)
        var transferred = false
        try {
            runBlocking {
                val scope = this
                val graph =
                    withContext(Dispatchers.EDT) {
                        StudioApplicationGraph(
                            store,
                            StudioLegacyCapture(options, store, { UUID.randomUUID().toString() }, "/ayu-test"),
                            scope,
                            projects(),
                        )
                    }
                transferred = true
                try {
                    val owner = assertIs<StudioApplicationState.Ready>(graph.application.start()).sessions
                    val before = owner.document
                    rawLicense.set(false)
                    rawReads.set(0)
                    files.pauseNextState()
                    val first = async { graph.refreshEntitlement() }
                    withTimeout(10.seconds) { files.entered.await() }
                    rawLicense.set(true)
                    val secondStarted = CompletableDeferred<Unit>()
                    val second =
                        async {
                            secondStarted.complete(Unit)
                            graph.refreshEntitlement()
                        }
                    secondStarted.await()
                    withContext(Dispatchers.EDT) { assertTrue(second.isActive) }
                    try {
                        assertFalse(first.isCompleted)
                        assertFalse(second.isCompleted)
                        assertEquals(
                            1,
                            rawReads.get(),
                            "The queued callback must not read ahead of metadata settlement",
                        )
                    } finally {
                        files.resume()
                    }
                    first.await()
                    second.await()

                    assertEquals("LICENSED", confirmation(owner.document))
                    assertEquals(before.currents, owner.document.currents)
                    assertEquals(before.shared, owner.document.shared)
                    assertEquals(listOf("UNLICENSED", "LICENSED"), files.confirmations.distinct())
                } finally {
                    files.resume()
                    graph.close()
                }
            }
        } finally {
            if (!transferred) store.close()
        }
        assertFalse(Files.exists(directory.resolve("pending.xml")))
    }

    @Test
    fun `project opened after application Ready receives one independent Current before effects`() {
        val directory = temporary.newFolder("later-project-state").toPath()
        val options = options("later-project-options")
        var opened = emptyList<Project>()
        val project = mockk<Project>()
        every { project.isDisposed } returns false
        every { project.isDefault } returns false
        every { project.name } returns "Later project"
        val census = StudioProjects({ opened }, { null }, { "/work/later" })
        runBlocking {
            val graph = StudioApplicationGraph.open(directory, options, this, census)
            try {
                val owner = assertIs<StudioApplicationState.Ready>(graph.application.start()).sessions
                val before = owner.document
                opened = listOf(project)
                assertIs<AcceptanceResult.Accepted>(graph.initializeProject(project))
                val current =
                    owner.document.currents.projects
                        .single()
                assertEquals("/work/later", current.key.value)
                assertEquals("Later project", current.displayName)
                assertEquals(before.currents.global.appearance, current.current.appearance)
                assertEquals(before.shared, owner.document.shared)
                val accepted = owner.document
                assertEquals(AcceptanceResult.NoChanges, graph.initializeProject(project))
                assertEquals(accepted, owner.document)
            } finally {
                graph.close()
            }
        }
        assertFalse(Files.exists(directory.resolve("pending.xml")))
    }

    private fun options(
        name: String,
        initialized: Boolean = false,
    ): Path {
        val directory = temporary.newFolder(name).toPath()
        Files.writeString(
            directory.resolve(legacyFileNames.getValue(LegacyStore.SETTINGS)),
            """
            <application><component name="AyuIslandsSettings">
              <option name="glowEnabled" value="false"/>
              <option name="glowShape" value="WAVEFORM"/>
              <option name="softIntensity" value="37"/>
              <option name="hideEditorVScrollbar" value="false"/>
              <option name="proDefaultsApplied" value="$initialized"/>
              <option name="workspaceDefaultsApplied" value="$initialized"/>
            </component></application>
            """.trimIndent(),
        )
        return directory
    }

    private fun projects() = StudioProjects({ emptyList() }, { null })

    private fun confirmation(document: StudioDocument): String =
        (
            document.runtime.legacy.records
                .single { it.key.name == "lastConfirmedEntitlement" }
                .value as RawValue.Text
        ).value

    private class PausingFiles(
        private val actual: StudioFiles,
    ) : StudioFiles by actual {
        private val armed = AtomicBoolean()
        private val released = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val confirmations = mutableListOf<String>()

        fun pauseNextState() {
            confirmations.clear()
            armed.set(true)
        }

        fun resume() = released.countDown()

        override fun replace(
            source: String,
            destination: String,
        ) {
            if (destination == "state.xml" && armed.compareAndSet(true, false)) {
                entered.complete(Unit)
                check(released.await(10, TimeUnit.SECONDS)) { "Metadata write was not released" }
            }
            actual.replace(source, destination)
            if (destination == "state.xml") {
                val document =
                    assertIs<DecodeResult.Ready>(
                        StudioStateCodec.decode(requireNotNull(actual.read(destination))),
                    ).document
                confirmations.add(
                    (
                        document.runtime.legacy.records
                            .single { it.key.name == "lastConfirmedEntitlement" }
                            .value as RawValue.Text
                    ).value,
                )
            }
        }
    }
}
