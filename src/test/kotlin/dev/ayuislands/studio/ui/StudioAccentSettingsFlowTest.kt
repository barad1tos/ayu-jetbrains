package dev.ayuislands.studio.ui

import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentUiInput
import dev.ayuislands.accent.AccentUiKeys
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.UiDefaultsOwnership
import dev.ayuislands.accent.accentUiPolicy
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.studio.FaultingStudioFiles
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.StudioBootstrap
import dev.ayuislands.studio.StudioBootstrapResult
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.ApplyExecution
import dev.ayuislands.studio.session.ApplyInputs
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioAccentUiOperation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyLease
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.studio.session.Submission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioAccentSettingsFlowTest {
    @Test
    fun `profile preview cancel manual apply and reopen`() = StudioAccentSettingsScenarios.previewApplyAndReopen()

    @Test
    fun `failed apply preserves newer native values`() = StudioAccentSettingsScenarios.restoreOwnedValues()

    @Test
    fun `stale prepared write is rejected`() = StudioAccentSettingsScenarios.rejectStalePreparation()

    @Test
    fun `cancelled caller does not leave the retired draft open`() =
        StudioAccentSettingsScenarios.finishCancelledRetirement()

    @Test
    fun `cancel leaves accepted cleanup available for repeated apply`() =
        StudioAccentSettingsScenarios.resumeAcceptedCleanup()

    @Test
    fun `apply settles accepted cleanup before submitting a newer draft`() =
        StudioAccentSettingsScenarios.applyNewDraftAfterCleanup()

    @Test
    fun `an unconfirmed cancel quarantines its draft before the next apply`() =
        StudioAccentSettingsScenarios.quarantineUnconfirmedRetirement()
}

internal object StudioAccentSettingsScenarios {
    private val edt =
        object : CoroutineDispatcher() {
            override fun dispatch(
                context: CoroutineContext,
                block: Runnable,
            ) = SwingUtilities.invokeLater(block)
        }

    fun previewApplyAndReopen() =
        exercise { fixture, host ->
            val initial = fixture.document
            val picker = components(host.panel).filterIsInstance<JComboBox<*>>().single()
            assertEquals(MIGRATED_ACCENT, previewHex(host))
            picker.selectedIndex = 1
            assertNotEquals(MIGRATED_ACCENT, previewHex(host))
            assertSame(expected = fixture.originalUnderline, actual = UIManager.get(AccentUiKeys.TAB_UNDERLINE))
            assertEquals(initial, fixture.stored())

            host.cancel()
            assertEquals(MIGRATED_ACCENT, previewHex(host))
            assertFalse(host.isModified)
            assertEquals(initial, fixture.stored())

            val chosen = AYU_ACCENT_PRESETS.first().hex
            host.clickSwatch(chosen)
            val previewButtons = components(host.panel).filterIsInstance<JToggleButton>().associateBy { it.text }
            previewButtons.getValue("Current").doClick()
            assertEquals(MIGRATED_ACCENT, previewHex(host))
            previewButtons.getValue("Draft").doClick()
            assertEquals(chosen, previewHex(host))
            assertTrue(host.isModified)
            assertSame(expected = fixture.originalUnderline, actual = UIManager.get(AccentUiKeys.TAB_UNDERLINE))
            assertEquals(initial, fixture.stored())

            val accepted = assertIs<ApplySettlement.Accepted>(host.applyDraft().settlement).document
            assertFalse(host.isModified)
            assertEquals(chosen, currentAccent(accepted))
            assertEquals(Color.decode(chosen).rgb, UIManager.getColor(AccentUiKeys.TAB_UNDERLINE).rgb)
            assertEquals(initial.currents.global.appearance.glow, accepted.currents.global.appearance.glow)
            assertEquals(initial.shared, accepted.shared)
            assertEquals(initial.runtime.rotation, accepted.runtime.rotation)
            val reopened = fixture.host(fixture.reopen())
            assertEquals(chosen, previewHex(reopened))
            assertContentEquals(fixture.legacy, fixture.readLegacy())
        }

    fun restoreOwnedValues() =
        exercise { fixture, host ->
            val foreign = Color(0x825741)
            fixture.afterEffects = {
                UIManager.put(AccentUiKeys.TAB_BACKGROUND, foreign)
                error("The runtime projection failed")
            }
            host.clickSwatch(AYU_ACCENT_PRESETS.first().hex)
            assertEquals(ApplySettlement.NotAccepted, host.applyDraft().settlement)
            assertSame(expected = fixture.originalUnderline, actual = UIManager.get(AccentUiKeys.TAB_UNDERLINE))
            assertSame(expected = foreign, actual = UIManager.get(AccentUiKeys.TAB_BACKGROUND))
            assertEquals(MIGRATED_ACCENT, currentAccent(fixture.stored()))
            assertTrue(host.isModified)
            host.cancel()
            assertEquals(MIGRATED_ACCENT, previewHex(host))
            assertFalse(host.isModified)
        }

    fun rejectStalePreparation() =
        exercise { fixture, host ->
            val foreign = Color(0x967856)
            fixture.afterCapture = { UIManager.put(AccentUiKeys.TAB_UNDERLINE, foreign) }
            host.clickSwatch(AYU_ACCENT_PRESETS.first().hex)
            assertEquals(ApplySettlement.NotAccepted, host.applyDraft().settlement)
            assertSame(expected = foreign, actual = UIManager.get(AccentUiKeys.TAB_UNDERLINE))
            assertEquals(MIGRATED_ACCENT, currentAccent(fixture.stored()))
        }

    fun finishCancelledRetirement() =
        exercise { fixture, host ->
            val chosen = AYU_ACCENT_PRESETS.first().hex
            fixture.afterEffects = { error("The runtime projection failed") }
            host.clickSwatch(chosen)
            assertEquals(ApplySettlement.NotAccepted, host.applyDraft().settlement)
            val pending = fixture.stored().sessions.single()
            assertEquals(SessionAvailability.LIVE, pending.availability)

            coroutineScope {
                val cancelCaller = launch(start = CoroutineStart.LAZY) { host.cancel() }
                fixture.afterRetirement = { cancelCaller.cancel() }
                cancelCaller.start()
                cancelCaller.join()
                assertTrue(cancelCaller.isCancelled)
            }

            val retired = fixture.stored().sessions.single()
            assertEquals(SessionAvailability.RETIRED, retired.availability)
            assertEquals(MIGRATED_ACCENT, previewHex(host))
            assertFalse(host.isModified)

            fixture.afterEffects = {}
            host.clickSwatch(chosen)
            val accepted = assertIs<ApplySettlement.Accepted>(host.applyDraft().settlement).document
            assertEquals(chosen, currentAccent(accepted))
        }

    fun resumeAcceptedCleanup() =
        exercise { fixture, host ->
            val chosen = AYU_ACCENT_PRESETS.first().hex
            fixture.afterRelease = { error("The native lease could not be released") }
            host.clickSwatch(chosen)
            val pending = assertIs<ApplySettlement.AcceptedCleanupPending>(host.applyDraft().settlement)
            assertEquals(chosen, previewHex(host))
            assertTrue(host.isModified)
            val status = components(host.panel).filterIsInstance<JLabel>()
            assertTrue(status.any { it.text == "Applied; cleanup needs attention" })

            assertIs<DraftWriteResult.Saved>(host.cancel())
            assertTrue(host.isModified)
            assertEquals(Color.decode(chosen).rgb, UIManager.getColor(AccentUiKeys.TAB_UNDERLINE).rgb)
            fixture.afterRelease = {}
            val settled = assertIs<ApplySettlement.Accepted>(host.applyDraft().settlement)
            assertEquals(pending.document, settled.document)
            assertFalse(host.isModified)
            assertEquals(chosen, previewHex(host))
            assertEquals(pending.document, fixture.stored())
        }

    fun quarantineUnconfirmedRetirement() =
        exercise { fixture, host ->
            fixture.afterEffects = { error("The runtime projection failed") }
            host.clickSwatch(AYU_ACCENT_PRESETS.first().hex)
            assertEquals(ApplySettlement.NotAccepted, host.applyDraft().settlement)
            val abandoned = fixture.stored().sessions.single()

            fixture.failRetirementReadback()
            assertEquals(DraftWriteResult.Failed("save-unconfirmed"), host.cancel())
            assertEquals(MIGRATED_ACCENT, previewHex(host))
            assertFalse(host.isModified)

            fixture.afterEffects = {}
            val nextColor = AYU_ACCENT_PRESETS[1].hex
            host.clickSwatch(nextColor)
            val accepted = assertIs<ApplySettlement.Accepted>(host.applyDraft().settlement).document
            assertEquals(nextColor, currentAccent(accepted))
            assertEquals(SessionAvailability.RETIRED, accepted.sessions.single { it.id == abandoned.id }.availability)
        }

    fun applyNewDraftAfterCleanup() =
        exercise { fixture, host ->
            fixture.afterRelease = { error("The native lease could not be released") }
            host.clickSwatch(AYU_ACCENT_PRESETS.first().hex)
            assertIs<ApplySettlement.AcceptedCleanupPending>(host.applyDraft().settlement)

            fixture.afterRelease = {}
            val nextColor = AYU_ACCENT_PRESETS[1].hex
            host.clickSwatch(nextColor)
            assertEquals(nextColor, previewHex(host))
            val accepted = assertIs<ApplySettlement.Accepted>(host.applyDraft().settlement).document
            assertEquals(nextColor, currentAccent(accepted))
            assertEquals(accepted, fixture.stored())
            assertFalse(host.isModified)
        }

    private fun exercise(action: suspend (Fixture, StudioAccentSettingsHost) -> Unit) {
        runBlocking {
            val root = createTempDirectory("studio-accent")
            try {
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                    Fixture(root, dispatcher).use { fixture ->
                        withContext(edt) {
                            val defaults = UIManager.getDefaults()
                            val snapshots = AccentUiKeys.colors.associateWith { readRawUiDefault(defaults, it) }
                            UIManager.put(AccentUiKeys.TAB_UNDERLINE, fixture.originalUnderline)
                            try {
                                action(fixture, fixture.host(fixture.document))
                            } finally {
                                snapshots.forEach { (key, raw) -> UIManager.put(key, raw) }
                            }
                        }
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    private class Fixture(
        private val root: Path,
        private val ioDispatcher: CoroutineDispatcher,
    ) : AutoCloseable {
        private val queue = Mutex()
        private val defaultsOwner = UiDefaultsOwnership()
        private var files = openFiles()
        private var store = StudioAppearanceStore(files)
        private var drafts = StudioDraftStore(store)
        private val options = root.resolve("options")
        private val settings: Path = options.resolve("ayuIslands.xml")
        val originalUnderline: Color = Color.decode(MIGRATED_ACCENT)
        val legacy =
            """
            <application><component name="AyuIslandsSettings">
                <option name="mirageAccent" value="$MIGRATED_ACCENT" />
                <option name="glowEnabled" value="false" />
                <option name="glowShape" value="WAVEFORM" />
                <option name="accentRotationEnabled" value="true" />
                <option name="accentRotationIntervalHours" value="6" />
                <option name="accentRotationLastSwitchMs" value="1234" />
            </component></application>
            """.trimIndent().encodeToByteArray()
        val document: StudioDocument
        var afterCapture: () -> Unit = {}
        var afterEffects: () -> Unit = {}
        var afterRetirement: () -> Unit = {}
        var afterRelease: () -> Unit = {}

        init {
            Files.createDirectories(options)
            Files.write(settings, legacy)
            Files.writeString(options.resolve("ayuIslandsAccentMappings.xml"), mappings)
            document = assertIs<StudioBootstrapResult.Ready>(bootstrap(store).load("migration")).document
        }

        private fun coordinator() =
            StudioApplyCoordinator(
                store,
                queue,
                ioDispatcher,
                flush = { record -> write { drafts.save(record, UUID.randomUUID().toString()) } },
                observe = { _, _ ->
                    ApplyInputs(
                        ApplyContext(
                            AcceptanceObservation(
                                AuthorityStatus.READY,
                                TargetObservation(StudioTarget.Global, GENERATION, TargetPresence.ACTIVE),
                            ),
                            StudioList(emptyList()),
                        ),
                        EffectObservation(GENERATION),
                    )
                },
                acquire = { plan -> lease(plan.document) },
                recover = { _, _ -> error("The open fixture owns its native handles") },
            )

        private fun lease(candidate: StudioDocument): StudioApplyLease =
            object : StudioApplyLease {
                override suspend fun qualifies(): Boolean = true

                override suspend fun capture(owner: EffectOwner) =
                    withContext(edt) {
                        if (owner == EffectOwner.GLOW) {
                            assertFalse(StudioGlowProjection.settings(candidate.currents.global.appearance).enabled)
                            return@withContext null
                        }
                        assertEquals(EffectOwner.ACCENT, owner)
                        val process = ProcessHandle.current()
                        val prepared =
                            StudioAccentUiOperation.capture(
                                accentInput(candidate),
                                StudioProcess(process.pid(), process.info().startInstant().orElse(null)),
                                candidate.header.transactionId,
                                edt,
                                defaultsOwner,
                            )
                        afterCapture()
                        prepared
                    }

                override suspend fun settledRuntime(candidate: StudioDocument) =
                    withContext(edt) {
                        afterEffects()
                        candidate.runtime
                    }

                override suspend fun release(accepted: StudioDocument?) = afterRelease()
            }

        fun host(document: StudioDocument): StudioAccentSettingsHost =
            StudioAccentSettingsHost(
                document,
                AyuVariant.MIRAGE,
                StudioSettingsSection.entries.filterNot { it == StudioSettingsSection.ACCENT }.map {
                    StudioSectionContent(it, JPanel()) {}
                },
                coordinator(),
                retireDraft = { id ->
                    write {
                        val result = drafts.finish(id, SessionAvailability.RETIRED, UUID.randomUUID().toString())
                        afterRetirement()
                        result
                    }
                },
                createAccentSection = { editAccent ->
                    StudioAccentSection(
                        editAccent,
                        chooseCustom = { null },
                        shuffleColor = { error("Shuffle was not requested") },
                    )
                },
                version = "3.0",
                openCommunityLink = { error("Community link was not requested") },
            )

        suspend fun readLegacy(): ByteArray = withContext(ioDispatcher) { Files.readAllBytes(settings) }

        suspend fun stored(): StudioDocument =
            withContext(ioDispatcher) { assertIs<StoreLoad.Ready>(store.load()).document }

        suspend fun failRetirementReadback() =
            withContext(ioDispatcher) {
                files.arm(
                    IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                    IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                )
            }

        suspend fun reopen(): StudioDocument =
            withContext(ioDispatcher) {
                store.close()
                files = openFiles()
                store = StudioAppearanceStore(files)
                drafts = StudioDraftStore(store)
                assertIs<StudioBootstrapResult.Ready>(bootstrap(store).load("reopen")).document
            }

        private suspend fun <T> write(action: () -> T): T = queue.withLock { withContext(ioDispatcher) { action() } }

        private fun bootstrap(store: StudioAppearanceStore) = StudioBootstrap(store, options, "/home/ayu")

        private fun openFiles() = FaultingStudioFiles(openStudioFiles(root.resolve("studio")))

        override fun close() = store.close()
    }

    private const val GENERATION = 1L
    private const val MIGRATED_ACCENT = "#FFCD66"
    private val mappings =
        """
        <application><component name="AyuIslandsAccentMappings">
            <option name="projectAccents"><map>
                <entry key="/work/z" value="#5CCFE6" />
                <entry key="/work/a" value="#F07178" />
            </map></option>
        </component></application>
        """.trimIndent()

    private fun currentAccent(document: StudioDocument): String =
        StudioAccentEditing.sample(document.currents.global.appearance, AyuVariant.MIRAGE).value

    private fun accentInput(document: StudioDocument): AccentUiInput =
        AccentUiInput(
            Color.decode(currentAccent(document)),
            Color.decode(AyuVariant.MIRAGE.neutralGray),
            GlowTabMode.MINIMAL,
            Color.WHITE.rgb,
            accentUiPolicy(
                external = false,
                overrideEnabled = false,
                chromeAllowed = false,
                tabMode = GlowTabMode.MINIMAL,
            ),
        )

    private fun components(root: Component): List<Component> =
        listOf(root) + if (root is Container) root.components.flatMap(::components) else emptyList()

    private fun previewHex(host: StudioAccentSettingsHost): String {
        val sample =
            components(host.panel).filterIsInstance<JComponent>().single {
                it.accessibleContext?.accessibleName == "Global accent sample"
            }
        return sample.accessibleContext.accessibleDescription.substringAfterLast(' ')
    }

    private suspend fun StudioAccentSettingsHost.applyDraft(): ApplyExecution =
        assertIs<Submission.Admitted<ApplyExecution>>(apply(UUID.randomUUID(), GENERATION)).result

    private fun StudioAccentSettingsHost.clickSwatch(hex: String) {
        components(panel)
            .filterIsInstance<JToggleButton>()
            .single { it.accessibleContext.accessibleName.endsWith("($hex)") }
            .doClick()
    }
}
