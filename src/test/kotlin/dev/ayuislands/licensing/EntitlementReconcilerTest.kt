package dev.ayuislands.licensing

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.QuickSwitcherPopup
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.vcs.VcsColorApplier
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementReconcilerTest {
    private lateinit var state: AyuIslandsState
    private val project: Project =
        mockk {
            every { isDisposed } returns false
            every { name } returns "active"
        }
    private val projectView = mockk<ProjectViewScrollbarManager>()
    private val editorScrollbars = mockk<EditorScrollbarManager>()
    private val commitPanel = mockk<CommitPanelAutoFitManager>()
    private val gitPanel = mockk<GitPanelAutoFitManager>()
    private val rotation = mockk<AccentRotationService>()
    private val syntax = mockk<SyntaxIntensityService>()
    private val context = AccentContext.Ayu(AyuVariant.MIRAGE)

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(ProjectViewScrollbarManager.Companion)
        every { ProjectViewScrollbarManager.getInstance(project) } returns projectView
        every { projectView.apply() } just Runs
        mockkObject(EditorScrollbarManager.Companion)
        every { EditorScrollbarManager.getInstance(project) } returns editorScrollbars
        every { editorScrollbars.apply() } just Runs
        mockkObject(CommitPanelAutoFitManager.Companion)
        every { CommitPanelAutoFitManager.getInstance(project) } returns commitPanel
        every { commitPanel.apply() } just Runs
        mockkObject(GitPanelAutoFitManager.Companion)
        every { GitPanelAutoFitManager.getInstance(project) } returns gitPanel
        every { gitPanel.apply() } just Runs

        mockkObject(AccentContext.Companion)
        every { AccentContext.detect() } returns context
        mockkObject(AccentApplicator)
        every { AccentApplicator.applyForFocusedProject(context) } returns "#FFCC66"
        mockkObject(QuickSwitcherPopup)
        every { QuickSwitcherPopup.closeOpenPopups() } just Runs
        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just Runs
        mockkObject(VcsColorApplier)
        every { VcsColorApplier.applyAll() } just Runs
        every { VcsColorApplier.revertAll() } just Runs
        mockkObject(SyntaxIntensityService.Companion)
        every { SyntaxIntensityService.getInstance() } returns syntax
        every { syntax.reapplyForActiveLaf() } just Runs
        mockkObject(AccentRotationService.Companion)
        every { AccentRotationService.getInstance() } returns rotation
        every { rotation.stopRotation() } just Runs
        every { rotation.rotateNow() } just Runs
        every { rotation.startRotationWithDelay(any()) } just Runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unknown entitlement is a strict no-op`() {
        EntitlementReconciler.reconcile(LicenseEntitlement.UNKNOWN, listOf(project))

        verify(exactly = 0) { ProjectViewScrollbarManager.getInstance(any()) }
        verify(exactly = 0) { VcsColorApplier.applyAll() }
        verify(exactly = 0) { VcsColorApplier.revertAll() }
        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any<AccentContext>()) }
        verify(exactly = 0) { AccentRotationService.getInstance() }
        verify(exactly = 0) { QuickSwitcherPopup.closeOpenPopups() }
    }

    @Test
    fun `license loss cleans every runtime surface without resuming rotation`() {
        EntitlementReconciler.reconcile(LicenseEntitlement.UNLICENSED, listOf(project))

        verify(exactly = 1) { rotation.stopRotation() }
        verifyWorkspaceApplied()
        verify(exactly = 1) { VcsColorApplier.revertAll() }
        verify(exactly = 0) { VcsColorApplier.applyAll() }
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(context) }
        verify(exactly = 1) { GlowOverlayManager.syncGlowForAllProjects() }
        verify(exactly = 1) { syntax.reapplyForActiveLaf() }
        verify(exactly = 0) { rotation.rotateNow() }
        verify(exactly = 0) { rotation.startRotationWithDelay(any()) }
        verify(exactly = 1) { QuickSwitcherPopup.closeOpenPopups() }
    }

    @Test
    fun `license recovery restores surfaces and enabled rotation`() {
        state.accentRotationEnabled = true
        state.accentRotationLastSwitchMs = 0L

        EntitlementReconciler.reconcile(LicenseEntitlement.LICENSED, listOf(project))

        verifyWorkspaceApplied()
        verify(exactly = 1) { VcsColorApplier.applyAll() }
        verify(exactly = 0) { VcsColorApplier.revertAll() }
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(context) }
        verify(exactly = 1) { GlowOverlayManager.syncGlowForAllProjects() }
        verify(exactly = 1) { syntax.reapplyForActiveLaf() }
        verify(exactly = 1) { rotation.rotateNow() }
        verify(exactly = 0) { rotation.stopRotation() }
        verify(exactly = 1) { QuickSwitcherPopup.closeOpenPopups() }
    }

    @Test
    fun `disabled rotation remains disabled after license recovery`() {
        state.accentRotationEnabled = false

        EntitlementReconciler.reconcile(LicenseEntitlement.LICENSED, emptyList())

        verify(exactly = 0) { AccentRotationService.getInstance() }
    }

    @Test
    fun `follow system accent keeps saved rotation suspended after recovery`() {
        state.accentRotationEnabled = true
        state.followSystemAccent = true

        EntitlementReconciler.reconcile(LicenseEntitlement.LICENSED, emptyList())

        verify(exactly = 0) { AccentRotationService.getInstance() }
    }

    @Test
    fun `disposed projects are excluded from workspace reconciliation`() {
        val disposed =
            mockk<Project> {
                every { isDisposed } returns true
            }

        EntitlementReconciler.reconcile(LicenseEntitlement.UNLICENSED, listOf(disposed))

        verify(exactly = 0) { ProjectViewScrollbarManager.getInstance(disposed) }
        verify(exactly = 0) { EditorScrollbarManager.getInstance(disposed) }
        verify(exactly = 0) { CommitPanelAutoFitManager.getInstance(disposed) }
        verify(exactly = 0) { GitPanelAutoFitManager.getInstance(disposed) }
    }

    @Test
    fun `workspace failure does not block remaining surfaces`() {
        every { projectView.apply() } throws RuntimeException("cleanup failed")

        val result = EntitlementReconciler.reconcile(LicenseEntitlement.UNLICENSED, listOf(project))

        verify(exactly = 1) { editorScrollbars.apply() }
        verify(exactly = 1) { VcsColorApplier.revertAll() }
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(context) }
        assertFalse(result.isSuccess)
        assertEquals(listOf("refresh Project view"), result.failures.map { it.operation })
        assertEquals(
            "cleanup failed",
            result.failures
                .single()
                .error.message,
        )
    }

    @Test
    fun `successful reconciliation returns an empty structured result`() {
        val result = EntitlementReconciler.reconcile(LicenseEntitlement.UNLICENSED, listOf(project))

        assertTrue(result.isSuccess)
        assertTrue(result.failures.isEmpty())
    }

    private fun verifyWorkspaceApplied() {
        verify(exactly = 1) { projectView.apply() }
        verify(exactly = 1) { editorScrollbars.apply() }
        verify(exactly = 1) { commitPanel.apply() }
        verify(exactly = 1) { gitPanel.apply() }
    }
}
