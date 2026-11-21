package dev.ayuislands.studio.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowGlassPane
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.GlowGroupPanel
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.catalogTestWork
import dev.ayuislands.studio.glowManager
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioGlowEdit
import dev.ayuislands.studio.model.StudioGlowField
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.ApplyExecution
import dev.ayuislands.studio.session.ApplyInputs
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyLease
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioGlowEditing
import dev.ayuislands.studio.session.StudioGlowHolds
import dev.ayuislands.studio.session.StudioGlowOperation
import dev.ayuislands.studio.session.StudioGlowPanePlanning
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.studio.session.StudioGlowTarget
import dev.ayuislands.studio.session.StudioSessionRecords
import dev.ayuislands.studio.session.Submission
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioGlowSectionTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Before
    fun guardAmbientAccess() {
        val shortcuts = mockk<ActionManager>()
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns shortcuts
        every { shortcuts.getAction(any()) } returns null
        mockkObject(AyuIslandsSettings.Companion, LicenseChecker, GlowOverlayManager.Companion)
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Expected explicit draft data")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Expected explicit entitlement")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("Expected a host action")
        every { GlowOverlayManager.getInstance(any()) } throws AssertionError("The contour belongs to Settings")
    }

    @After
    fun restoreAmbientAccess() {
        unmockkObject(AyuIslandsSettings.Companion, LicenseChecker, GlowOverlayManager.Companion)
        unmockkStatic(ActionManager::class)
    }

    @Test
    fun `waveform gestures update the draft contour immediately and Cancel restores the accepted contour`() {
        SwingUtilities.invokeAndWait {
            val editor = GlowFixture()
            val border = editor.contour
            border.setSize(540, 360)
            val accepted = pixels(border)

            editor.section.content.preview(PreviewMode.DRAFT)
            val solidDraft = pixels(border)
            editor.combo(GlowShape.SOLID.displayName).selectedItem = GlowShape.WAVEFORM.displayName

            assertEquals(GlowShape.WAVEFORM, border.glowShape)
            assertFalse(solidDraft.contentEquals(pixels(border)))
            assertEquals(setOf("glowShape"), editor.touchedFields())
            assertEquals(listOf("glow-chaotic-routing"), editor.badgesSeen)
            editor.combo(WaveformMovement.CLOCKWISE.displayName).selectedItem = WaveformMovement.CHAOTIC.displayName
            assertEquals(WaveformMovement.CHAOTIC, border.waveformConfig.movement)
            assertEquals(setOf("glowShape", "waveformDirection"), editor.touchedFields())

            editor.section.content.preview(PreviewMode.CURRENT)
            assertEquals(GlowShape.SOLID, border.glowShape)
            assertTrue(accepted.contentEquals(pixels(border)))
            assertEquals(2, editor.edits.size)
            assertSame(border, editor.contour)

            editor.render(editor.view.copy(draft = StudioDraftPolicy.discard(editor.view.draft)))
            editor.section.content.preview(PreviewMode.DRAFT)
            assertEquals(editor.original.appearance, editor.view.draft.appearance)
            assertEquals(editor.original.base, editor.view.draft.base)
            assertTrue(editor.touchedFields().isEmpty())
            assertEquals(GlowShape.SOLID, border.glowShape)
            assertEquals(2, editor.edits.size)
        }
    }

    @Test
    fun `locked and readonly controls keep the preview visible and send unlock to the host`() {
        SwingUtilities.invokeAndWait {
            val editor = GlowFixture()
            val enable = editor.button("Enable glow")
            val sample = editor.contour
            editor.render(editor.view.copy(isEditable = false, isLicensed = false))
            assertTrue(sample.glowVisible)
            assertFalse(enable.isEnabled)
            editor.combo(GlowShape.SOLID.displayName).selectedItem = GlowShape.WAVEFORM.displayName
            enable.doClick()
            assertTrue(editor.edits.isEmpty())
            editor
                .children()
                .filterIsInstance<ActionLink>()
                .single { it.text == "Unlock Pro" }
                .doClick()
            assertEquals(1, editor.unlocks)

            editor.render(editor.view.copy(isLicensed = true))
            assertFalse(enable.isEnabled)
            editor.render(editor.view.copy(isEditable = true))
            editor.section.content.preview(PreviewMode.DRAFT)
            enable.doClick()
            assertEquals(setOf("glowEnabled"), editor.touchedFields())
            assertFalse(sample.glowVisible)
            assertSame(sample, editor.contour)
        }
    }

    @Test
    fun `render reuses the contour and familiar groups while badges use the supplied view`() {
        SwingUtilities.invokeAndWait {
            val editor = GlowFixture()
            val groups = editor.children().filterIsInstance<TitledSeparator>()
            assertTrue(groups.map { it.text }.containsAll(listOf("Style", "Placement", "Targets")))
            editor.render(editor.view.copy(pendingBadges = StudioList(listOf("glow-waveform"))))
            val badge =
                editor.children().filterIsInstance<JLabel>().single {
                    it.text == "New" && it.isVisible
                }
            assertEquals("New in this release", badge.accessibleContext.accessibleDescription)
            editor.render(editor.view.copy(pendingBadges = StudioList(emptyList())))
            assertFalse(badge.isVisible)
            assertEquals(groups, editor.children().filterIsInstance<TitledSeparator>())
            assertTrue(editor.edits.isEmpty())
            assertTrue(editor.badgesSeen.isEmpty())
        }
    }

    @Test
    fun `solid controls update only the draft style slot and available targets`() {
        SwingUtilities.invokeAndWait {
            val editor = GlowFixture()
            editor.section.content.preview(PreviewMode.DRAFT)
            editor.button(GlowPreset.CUSTOM.displayName).doClick()
            val sample = editor.contour
            sample.setSize(540, 360)
            val before = pixels(sample)

            editor.combo(GlowStyle.SOFT.displayName).selectedItem = GlowStyle.SHARP_NEON.displayName
            val sliders = editor.children().filterIsInstance<JSlider>()
            sliders.single { it.minimum == 2 && it.maximum == 16 }.value = 12
            sliders.single { it.minimum == 0 && it.maximum == 100 }.value = 78
            editor.combo(GlowAnimation.NONE.displayName).selectedItem = GlowAnimation.PULSE.displayName
            assertEquals(GlowStyle.SHARP_NEON, sample.glowStyle)
            assertEquals(12, sample.glowWidth)
            assertEquals(78, sample.glowIntensity)
            assertFalse(before.contentEquals(pixels(sample)))

            val placements =
                editor.children().filterIsInstance<AbstractButton>().filter {
                    it.text ==
                        GlowPlacement.SIDE_EDGES.displayName
                }
            assertEquals(2, placements.size)
            placements.forEach { it.doClick() }

            val targets = editor.children().filterIsInstance<TitledSeparator>().single { it.text == "Targets" }
            targets.dispatchEvent(
                MouseEvent(targets, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
            )
            assertTrue(editor.button("Editor").isVisible)
            editor.button("Editor").doClick()
            assertFalse(editor.button("Editor").isSelected)
            assertTrue(editor.button("Terminal").isSelected)
            editor
                .children()
                .filterIsInstance<ActionLink>()
                .single { it.text == "Disable all" }
                .doClick()
            assertFalse(editor.button("Terminal").isSelected)
            editor
                .children()
                .filterIsInstance<ActionLink>()
                .single { it.text == "Enable all" }
                .doClick()
            assertTrue(editor.button("Terminal").isSelected)
            assertEquals(
                setOf(
                    "glowPreset",
                    "glowStyle",
                    "sharpNeonWidth",
                    "sharpNeonIntensity",
                    "glowAnimation",
                    "glowEditorPlacement",
                    "glowToolWindowPlacement",
                ),
                editor.touchedFields(),
            )
            assertEquals(editor.original.base, editor.view.draft.base)
        }
    }

    @Test
    fun `applying a Glow draft persists and installs only its selected shape`() = exerciseMountedGlow(abort = false)

    @Test
    fun `failed Glow apply restores the shape and leaves a foreign width`() = exerciseMountedGlow(abort = true)

    private fun exerciseMountedGlow(abort: Boolean) =
        runBlocking {
            val edt = Executor(SwingUtilities::invokeLater).asCoroutineDispatcher()
            val initial = mountedGlowDocument()
            val target = StudioTarget.Global
            val draftId = catalogTestWork(initial).selected
            val session = requireNotNull(StudioSessionRecords.current(initial, target, "glow-flow", draftId))
            val fixture = withContext(edt) { GlowFixture(session.work.drafts.single()) }
            val color = Color(0xFFCD66)
            val pane =
                withContext(edt) {
                    GlowGlassPane(color, GlowStyle.SOFT, 49, 8).apply {
                        configureWaveform(GlowShape.SOLID, WaveformConfig(amplitude = 29))
                    }
                }
            val manager = withContext(edt) { glowManager(pane) }
            val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                withContext(io) {
                    StudioAppearanceStore(openStudioFiles(directory.root.toPath())).use { store ->
                        initializeStore(store, initial)
                        val (draft, before) =
                            withContext(edt) {
                                val before = pane.captureAppearance()
                                fixture.combo(GlowShape.SOLID.displayName).selectedItem = GlowShape.WAVEFORM.displayName
                                assertEquals(before, pane.captureAppearance())
                                fixture.edits.single() to before
                            }
                        val installed = before.copy(shape = GlowShape.WAVEFORM)
                        val coordinator =
                            mountedCoordinator(store, io) { plan ->
                                object : StudioApplyLease {
                                    private val holds =
                                        StudioGlowHolds { error("An unchanged environment requested a refresh") }

                                    override suspend fun qualifies(): Boolean = true

                                    override suspend fun capture(owner: EffectOwner): StudioGlowOperation? {
                                        assertEquals(EffectOwner.GLOW, owner)
                                        val selection = plan.route.glow
                                        assertEquals(listOf(FieldAddress(StudioGlowField.SHAPE.key)), selection.fields)
                                        val appearance = plan.document.currents.global.appearance
                                        val settings = StudioGlowProjection.settings(appearance)
                                        val request =
                                            StudioGlowTarget(manager) { isEditor, current ->
                                                StudioGlowPanePlanning.edit(
                                                    selection,
                                                    settings,
                                                    color,
                                                    isEditor,
                                                    current,
                                                )
                                            }
                                        return StudioGlowOperation.capture(
                                            listOf(request),
                                            holds,
                                            StudioProcess(17, Instant.EPOCH),
                                            plan.document.header.transactionId,
                                            edt,
                                        )
                                    }

                                    override suspend fun settledRuntime(candidate: StudioDocument) =
                                        withContext(edt) {
                                            assertEquals(installed, pane.captureAppearance())
                                            if (abort) {
                                                pane.glowWidth = 11
                                                error("Runtime projection failed")
                                            }
                                            candidate.runtime
                                        }

                                    override suspend fun release(accepted: StudioDocument?) =
                                        withContext(edt) { holds.release() }
                                }
                            }
                        val work = session.work.copy(drafts = StudioList(listOf(draft)))
                        val edited = session.copy(generation = 1, work = work)
                        val execution =
                            assertIs<Submission.Admitted<ApplyExecution>>(
                                coordinator.submit(ApplyRequest(edited, 1), UUID.randomUUID()),
                            ).result
                        val state =
                            assertIs<StoreLoad.Ready>(store.load())
                                .document.currents.global.appearance
                        if (abort) {
                            assertEquals(ApplySettlement.NotAccepted, execution.settlement)
                            assertEquals(initial.currents.global.appearance, state)
                            withContext(edt) { assertEquals(before.copy(width = 11), pane.captureAppearance()) }
                        } else {
                            assertIs<ApplySettlement.Accepted>(execution.settlement)
                            assertEquals(draft.appearance, state)
                            withContext(edt) { assertEquals(installed, pane.captureAppearance()) }
                        }
                    }
                }
            } finally {
                io.close()
                withContext(edt) { manager.dispose() }
            }
        }

    private fun mountedCoordinator(
        store: StudioAppearanceStore,
        io: CoroutineDispatcher,
        acquire: suspend (AcceptancePlan.Commit) -> StudioApplyLease,
    ): StudioApplyCoordinator {
        val drafts = StudioDraftStore(store)
        return StudioApplyCoordinator(
            store,
            Mutex(),
            io,
            flush = { record -> withContext(io) { drafts.save(record, UUID.randomUUID().toString()) } },
            observe = { _, _ ->
                ApplyInputs(
                    ApplyContext(
                        AcceptanceObservation(
                            AuthorityStatus.READY,
                            TargetObservation(StudioTarget.Global, 1, TargetPresence.ACTIVE),
                        ),
                        StudioList(emptyList()),
                    ),
                    EffectObservation(1),
                )
            },
            acquire = acquire,
            recover = { _, _ -> error("The fixture retains the native Glow owner") },
        )
    }

    private fun initializeStore(
        store: StudioAppearanceStore,
        initial: StudioDocument,
    ) {
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(initial.header.migration))
        val route = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
        val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, initial, route)).token
        assertIs<CommitResult.Committed>(store.commit(token, initial.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
    }

    private fun mountedGlowDocument(): StudioDocument {
        val base = catalogTestDocument()
        val starting = catalogTestWork(base).drafts.single()
        val editable = StudioGlowEditing.edit(starting, StudioGlowEdit.Enable(true)).appearance
        val disabled = RawValue.BooleanValue(false)
        val fields =
            editable.glow.records.map { field ->
                if (field.key == StudioGlowField.FOCUS_RING.key) field.copy(value = disabled) else field
            }
        val appearance = editable.copy(glow = editable.glow.copy(records = StudioList(fields)))
        return base.copy(currents = base.currents.copy(global = base.currents.global.copy(appearance = appearance)))
    }

    private class GlowFixture(
        val original: DraftRecord =
            catalogTestWork(catalogTestDocument()).drafts.single().let { empty ->
                val accepted = StudioGlowEditing.edit(empty, StudioGlowEdit.Enable(true)).appearance
                empty.copy(
                    appearance = accepted,
                    base = empty.base.copy(appearance = accepted),
                    tracking = DraftTracking(accepted),
                )
            },
    ) {
        var view =
            StudioGlowView(
                draft = original,
                accepted = original.appearance,
                acceptedAccent = Color(0xFFCD66),
                draftAccent = Color(0x5CCFE6),
                isEditable = true,
                isLicensed = true,
            )
            private set
        val edits = mutableListOf<DraftRecord>()
        val badgesSeen = mutableListOf<String>()
        var unlocks = 0
            private set
        val section =
            StudioGlowSection(
                onDraftChanged = { revision ->
                    edits.add(revision)
                    render(view.copy(draft = revision))
                },
                onUnlock = { unlocks++ },
                onBadgeSeen = badgesSeen::add,
            )

        init {
            render(view)
        }

        val contour: GlowGroupPanel
            get() = children().filterIsInstance<GlowGroupPanel>().single()

        fun render(revision: StudioGlowView) {
            view = revision
            section.render(revision)
        }

        fun children(): List<Component> = descendants(section.content.component)

        fun touchedFields(): Set<String> =
            view.draft.tracking.touched
                .map { it.field.name }
                .toSet()

        fun button(title: String): AbstractButton =
            children().filterIsInstance<AbstractButton>().single { it.text == title }

        fun combo(firstOption: String): JComboBox<*> =
            children().filterIsInstance<JComboBox<*>>().single { it.getItemAt(0) == firstOption }
    }

    private fun pixels(panel: GlowGroupPanel): IntArray {
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private companion object {
        fun descendants(root: Container): List<Component> =
            buildList {
                for (child in root.components) {
                    add(child)
                    if (child is Container) addAll(descendants(child))
                }
            }
    }
}
