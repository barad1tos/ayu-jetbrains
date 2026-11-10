package dev.ayuislands.integration

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.font.FontPanelResources
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AccentPanelContext
import dev.ayuislands.settings.IgnorePreviewInput
import dev.ayuislands.settings.PluginTarget
import dev.ayuislands.settings.SyntaxCapabilityEvidence
import dev.ayuislands.settings.SyntaxCapabilityState
import dev.ayuislands.settings.mappings.MappingsContext
import dev.ayuislands.settings.mappings.ProjectLanguageResolutionPanel
import dev.ayuislands.studio.ManualSessionScheduler
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.FontJobStatus
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.session.SessionWriter
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioSessionBinding
import dev.ayuislands.studio.sessionInputs
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.sessionState
import dev.ayuislands.studio.ui.StudioEditorContext
import dev.ayuislands.studio.ui.StudioHostDialogs
import dev.ayuislands.studio.ui.StudioReviewDialog
import dev.ayuislands.studio.ui.StudioSettingsContext
import dev.ayuislands.studio.ui.StudioSettingsHost
import dev.ayuislands.studio.value
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxOverlayLoader
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxPreviewCatalog
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Real store/coordinator; only native effects, dialogs and external font completion are controlled. */
internal class StudioHostFixture(
    directory: Path,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : AutoCloseable {
    val apply = hostModal { withContext(ioContext) { StudioApplyFixture(directory) } }
    private val latest = AtomicReference(apply.initial)
    private val serial = AtomicInteger()
    private val drafts = StudioDraftStore(apply.store)
    private val frame =
        lazy {
            JFrame("Studio host test").apply {
                isAutoRequestFocus = false
                setLocation(-10_000, -10_000)
                setSize(1_040, 900)
            }
        }
    val document: StudioDocument get() = latest.get()
    private val editorScheme = EditorColorsManager.getInstance().globalScheme
    private val editor =
        hostModal {
            withContext(ioContext) {
                val loader = SyntaxOverlayLoader.getInstance()
                val base =
                    SyntaxIntensityApplicator.Request(
                        SyntaxPreset.AMBIENT,
                        "Mirage",
                        java.awt.Color(0x1F2430),
                        loader.loadBaselineForVariant("Mirage"),
                        loader.loadOverlayForVariant("Mirage"),
                        fallbacks = loader.fallbacksFor("Mirage"),
                    )
                val sample = requireNotNull(SyntaxPreviewCatalog.find("Ignore files")).fixtures.first()
                val code =
                    requireNotNull(
                        javaClass.getResourceAsStream(
                            "/dev/ayuislands/settings/syntax-preview/${sample.resourceName}",
                        ),
                    ).bufferedReader().use { it.readText() }
                val keys =
                    (base.baseline.keys + base.overlay.keys).filter {
                        it.externalName in SyntaxIntensityService.IGNORE_PLUGIN_KEY_NAMES
                    }
                val ignore =
                    IgnorePreviewInput(
                        code,
                        base,
                        keys.associateWith {
                            SyntaxIntensityService.ignorePluginStockAttributes("Mirage", it.externalName)
                        },
                    )
                StudioEditorContext(editorScheme, base, ignore)
            }
        }
    val scheduler = ManualSessionScheduler()
    val publicationThreads = mutableListOf<Boolean>()
    val updates = mutableListOf<StudioApplyUpdate>()
    val coordinators = mutableListOf<StudioApplyCoordinator>()
    val ioThreads = mutableListOf<Boolean>()
    val resources = mutableListOf<FontJob>()
    val names = ArrayDeque<String>()
    var fontAvailability = FontAvailability.AVAILABLE
    var installedFontFamilies = listOf("Monospaced")
    var review: (StudioReviewDialog) -> Boolean = { error("Unexpected review") }
    var reviewCount = 0
        private set
    val binding =
        StudioSessionBinding(
            initial = sessionState(document).copy(record = null, phase = SessionPhase.Loading),
            inputs = ::inputs,
            scheduler = scheduler,
            writer = writer(),
            coordinator = ::coordinator,
            loadRecord = { withContext(ioContext) { document.sessions.firstOrNull() ?: sessionRecord(document) } },
            resource = resources::add,
            ids = ::nextId,
        )
    val host =
        StudioSettingsHost(
            binding,
            ::context,
            object : StudioHostDialogs {
                override fun profileName(currentName: String?): String? = names.removeFirstOrNull()

                override fun review(dialog: StudioReviewDialog): Boolean {
                    reviewCount++
                    return this@StudioHostFixture.review.invoke(dialog)
                }
            },
            actions = mockk(),
        )
    var root: JComponent = JPanel()
        private set

    fun open(): JComponent {
        root = requireNotNull(host.createComponent())
        frame.value.contentPane = root
        frame.value.isVisible = true
        host.reset()
        frame.value.validate()
        return root
    }

    fun saveDraft() {
        scheduler.advance(250)
        readStoredDocument()
    }

    fun readStoredDocument() {
        hostModal {
            withContext(ioContext) {
                apply.mutex.withLock { latest.set(apply.document()) }
            }
        }
    }

    fun completeResource(
        job: FontJob,
        status: FontJobStatus,
    ) {
        hostModal { withContext(ioContext) { apply.mutex.withLock { recordInstalled(job.fontId) } } }
        binding.controller.dispatch(
            SessionEvent.Observation(SessionObservation.ResourceInventory(job.fontId, FontAvailability.AVAILABLE)),
        )
        binding.controller.dispatch(
            SessionEvent.EffectCompleted(
                CallbackStamp(job.operation, "resource-complete"),
                SessionEffect.ResourceFinished(status),
            ),
        )
    }

    fun installedFonts(): List<String> =
        assertIs<RawValue.OrderedSet>(document.auxiliary.resources.value("installedFonts")).values

    override fun close() {
        try {
            host.disposeUIResources()
        } finally {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            if (frame.isInitialized()) frame.value.dispose()
            hostModal { withContext(ioContext) { apply.close() } }
        }
    }

    private fun inputs(state: StudioSessionState) =
        sessionInputs(document, state.copy(record = state.record ?: sessionRecord(document))).let { input ->
            val work = (state.record ?: sessionRecord(document)).work
            input.copy(
                context =
                    input.context.copy(
                        font =
                            input.context.font.copy(
                                requested =
                                    work.drafts
                                        .single { it.id == work.selected }
                                        .appearance.font,
                                availability = fontAvailability,
                            ),
                    ),
            )
        }

    private fun writer() =
        SessionWriter { record, completion ->
            val result =
                hostModal {
                    withContext(ioContext) {
                        ioThreads.add(SwingUtilities.isEventDispatchThread())
                        apply.mutex.withLock {
                            drafts.save(record, nextId()).also { latest.set(apply.document()) }
                        }
                    }
                }
            completion(result)
        }

    private fun coordinator(receive: suspend (StudioApplyUpdate) -> Unit): StudioApplyCoordinator {
        val environment =
            object : StudioApplyEnvironment by apply {
                override suspend fun flush(record: StudioSessionRecord) =
                    withContext(ioContext) {
                        ioThreads.add(SwingUtilities.isEventDispatchThread())
                        apply.flush(record).also { latest.set(apply.document()) }
                    }

                override suspend fun publish(update: StudioApplyUpdate) {
                    apply.publish(update)
                    withContext(ioContext) { latest.set(apply.document()) }
                    withContext(Dispatchers.EDT) {
                        publicationThreads.add(SwingUtilities.isEventDispatchThread())
                        updates.add(update)
                        receive(update)
                    }
                }
            }
        return StudioApplyCoordinator(apply.store, environment, apply.effects, apply.mutex).also(coordinators::add)
    }

    fun context() =
        StudioSettingsContext(
            entitlement = LicenseEntitlement.LICENSED,
            accent =
                AccentPanelContext(
                    variant = AyuVariant.MIRAGE,
                    isSystemAppearanceSupported = true,
                    isSystemAccentSupported = true,
                    isIslandsUi = true,
                    isCustomHeaderActive = true,
                    toolbarUnavailableReason = null,
                ),
            font = FontPanelResources(fontAvailability, installedFontFamilies, true, "BUILTIN", true),
            editor = editor,
            mappings =
                MappingsContext(
                    null,
                    "#FFCD66",
                    false,
                    ProjectLanguageResolutionPanel.State(
                        ProjectLanguageVerdict.Unavailable,
                        null,
                        null,
                        AccentResolver.Source.GLOBAL,
                        canMutate = false,
                        canRescan = false,
                    ),
                ),
            plugins = PluginTarget.entries.toSet(),
            syntax =
                SyntaxCapabilityState.Confirmed(
                    "kotlin",
                    SyntaxCapabilityEvidence("kotlin", PrimitiveCategory.entries.toSet()),
                ),
        )

    private fun recordInstalled(fontId: String) {
        val previous = apply.document()
        val inventory = previous.auxiliary.resources
        val records =
            inventory.records.map { field ->
                if (field.key.name == "installedFonts") {
                    val ids = assertIs<RawValue.OrderedSet>(field.value).values
                    StudioFieldEdits.edit(
                        field,
                        FieldPart.Value,
                        FieldValue.Present(RawValue.OrderedSet(StudioList((ids + fontId).distinct()))),
                    )
                } else {
                    field
                }
            }
        val candidate =
            previous.copy(
                header = previous.header.copy(revision = previous.header.revision + 1, transactionId = nextId()),
                auxiliary = previous.auxiliary.copy(resources = inventory.copy(records = StudioList(records))),
            )
        val token =
            assertIs<PrepareResult.Prepared>(
                apply.store.prepare(
                    previous.header.revision,
                    candidate,
                    RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                ),
            ).token
        assertIs<CommitResult.Committed>(apply.store.commit(token, candidate.runtime))
        assertEquals(JournalResult.Acknowledged, apply.store.finishAccepted(token))
        latest.set(apply.document())
    }

    private fun nextId(): String = "host-${serial.incrementAndGet()}"
}

internal fun <T> hostModal(action: suspend () -> T): T =
    runWithModalProgressBlocking(ModalTaskOwner.component(JPanel()), "Verify Studio host") { action() }
