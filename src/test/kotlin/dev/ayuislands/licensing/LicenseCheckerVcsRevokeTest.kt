package dev.ayuislands.licensing

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.vcs.VcsColorApplier
import dev.ayuislands.vcs.VcsColorPreset
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * VCS revoke cascade for [LicenseChecker.revertToFreeDefaults].
 *
 * Locks the 15-field VCS reset triplet:
 *  - 1 master toggle (`vcsColorEnabled`)
 *  - 3 section preset names (`vcsDiffPreset`, `vcsMergePreset`, `vcsBlamePreset`)
 *  - 11 per-category intensity sliders (`vcsDiffIntensity` ... `vcsCommitHighlightIntensity`)
 *  - 3 section-expanded flags (`vcsDiffSectionExpanded`=true, `vcsMergeSectionExpanded`=false,
 *    `vcsBlameSectionExpanded`=false)
 *
 * Plus the downstream call to [VcsColorApplier.revertAll] which nulls every VCS
 * `ColorKey` on the active `EditorColorsScheme` so the user sees stock colors
 * restored immediately on downgrade — no theme-switch or restart required.
 *
 * Mirrors the harness from `FreeTierLockdownTest` for setup parity.
 */
class LicenseCheckerVcsRevokeTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private lateinit var syntaxState: SyntaxIntensityState
    private lateinit var syntaxService: SyntaxIntensityService

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        // Stubbed applies report success, so the persisted clean flag must read
        // clean too — ThemeReapplication's tear-escalation check consults it.
        state.lastApplyOk = true
        settings = mockk()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns "#FFCC66"

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } just runs
        every { AccentApplicator.applyFromHexString(any()) } returns true

        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just runs

        mockkObject(VcsColorApplier)
        every { VcsColorApplier.revertAll() } just runs

        syntaxState = SyntaxIntensityState()
        mockkObject(SyntaxIntensityState.Companion)
        every { SyntaxIntensityState.getInstance() } returns syntaxState

        syntaxService = mockk(relaxed = true)
        mockkObject(SyntaxIntensityService.Companion)
        every { SyntaxIntensityService.getInstance() } returns syntaxService

        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        every { app.getService(AccentRotationService::class.java) } returns rotationService

        mockkStatic(NotificationGroupManager::class)
        val ngm = mockk<NotificationGroupManager>()
        val group = mockk<NotificationGroup>()
        val notification = mockk<Notification>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns ngm
        every { ngm.getNotificationGroup(any()) } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification
        every { notification.notify(any()) } just runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ---------- 11 intensity sliders ----------

    @Test
    fun `revertToFreeDefaults preserves all 11 VCS intensity fields`() {
        // Seed every per-category intensity to a non-default value so the revert
        // is observable. 80 is well outside `AMBIENT_SLIDER` and inside the
        // 0..100 slider range, so the field accepts the write.
        val seed = 80
        state.vcsDiffIntensity = seed
        state.vcsProjectViewIntensity = seed
        state.vcsGutterIntensity = seed
        state.vcsConflictMarkerIntensity = seed
        state.vcsMerge3WayIntensity = seed
        state.vcsInlineDiffIntensity = seed
        state.vcsBlameIntensity = seed
        state.vcsLocalHistoryIntensity = seed
        state.vcsBranchIndicatorIntensity = seed
        state.vcsBranchesPopupIntensity = seed
        state.vcsCommitHighlightIntensity = seed

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        val expected = seed
        assertEquals(expected, state.vcsDiffIntensity, "vcsDiffIntensity")
        assertEquals(expected, state.vcsProjectViewIntensity, "vcsProjectViewIntensity")
        assertEquals(expected, state.vcsGutterIntensity, "vcsGutterIntensity")
        assertEquals(expected, state.vcsConflictMarkerIntensity, "vcsConflictMarkerIntensity")
        assertEquals(expected, state.vcsMerge3WayIntensity, "vcsMerge3WayIntensity")
        assertEquals(expected, state.vcsInlineDiffIntensity, "vcsInlineDiffIntensity")
        assertEquals(expected, state.vcsBlameIntensity, "vcsBlameIntensity")
        assertEquals(expected, state.vcsLocalHistoryIntensity, "vcsLocalHistoryIntensity")
        assertEquals(expected, state.vcsBranchIndicatorIntensity, "vcsBranchIndicatorIntensity")
        assertEquals(expected, state.vcsBranchesPopupIntensity, "vcsBranchesPopupIntensity")
        assertEquals(expected, state.vcsCommitHighlightIntensity, "vcsCommitHighlightIntensity")
    }

    // ---------- 3 preset names ----------

    @Test
    fun `revertToFreeDefaults preserves all 3 VCS preset names`() {
        // Seed each section preset to `NEON` so we can see the revert ran. Using
        // `VcsColorPreset.NEON.name` (round-tripped through `byName`) instead of
        // a raw literal protects the assertion from a future enum-name change.
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.NEON.name
        state.vcsBlamePreset = VcsColorPreset.NEON.name

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        val expected = VcsColorPreset.NEON.name
        assertEquals(expected, state.vcsDiffPreset)
        assertEquals(expected, state.vcsMergePreset)
        assertEquals(expected, state.vcsBlamePreset)
    }

    // ---------- 1 master toggle ----------

    @Test
    fun `revertToFreeDefaults preserves vcsColorEnabled`() {
        state.vcsColorEnabled = true
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertTrue(state.vcsColorEnabled, "Runtime entitlement must not overwrite the saved toggle")
    }

    // ---------- 3 section-expanded flags ----------

    @Test
    fun `revertToFreeDefaults preserves section-expanded flags`() {
        // Seed all three to false so the diff flag has to flip back to true and
        // the merge/blame flags stay at false — exercises both branches of the
        // expanded-default contract.
        state.vcsDiffSectionExpanded = false
        state.vcsMergeSectionExpanded = false
        state.vcsBlameSectionExpanded = false

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertFalse(state.vcsDiffSectionExpanded)
        assertFalse(state.vcsMergeSectionExpanded, "merge section collapses on downgrade")
        assertFalse(state.vcsBlameSectionExpanded, "blame section collapses on downgrade")
    }

    // ---------- Downstream applier ----------

    @Test
    fun `revertToFreeDefaults invokes VcsColorApplier revertAll exactly once`() {
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        verify(exactly = 1) { VcsColorApplier.revertAll() }
    }

    @Test
    fun `revertToFreeDefaults preserves syntax intensity state`() {
        syntaxState.state.selectedPreset = SyntaxPreset.CUSTOM.name
        syntaxState.state.subordinatePreset = SyntaxPreset.NEON.name
        syntaxState.state.customOverrides["Java|KEYWORD"] = "85"
        syntaxState.state.customStyles["Java|KEYWORD"] = "BOLD"
        syntaxState.state.dimComments = true
        syntaxState.state.softenDocumentation = true
        syntaxState.state.quietOperators = true
        syntaxState.state.emphasizeDeclarations = true

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals(SyntaxPreset.CUSTOM.name, syntaxState.state.selectedPreset)
        assertEquals(SyntaxPreset.NEON.name, syntaxState.state.subordinatePreset)
        assertEquals("85", syntaxState.state.customOverrides["Java|KEYWORD"])
        assertEquals("BOLD", syntaxState.state.customStyles["Java|KEYWORD"])
        assertTrue(syntaxState.state.dimComments)
        assertTrue(syntaxState.state.softenDocumentation)
        assertTrue(syntaxState.state.quietOperators)
        assertTrue(syntaxState.state.emphasizeDeclarations)
    }

    // ---------- Defensive: runtime cleanup failure does not mutate saved state ----------

    @Test
    fun `revertToFreeDefaults preserves state when VcsColorApplier revertAll throws`() {
        // ThemeReapplication isolates the VCS runtime cleanup failure. Persisted
        // customization must remain untouched even when the IDE color-scheme
        // applier cannot complete the downgrade presentation.
        every { VcsColorApplier.revertAll() } throws RuntimeException("boom")

        state.vcsColorEnabled = true
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.NEON.name
        state.vcsBlamePreset = VcsColorPreset.NEON.name
        state.vcsDiffIntensity = 80
        state.vcsBlameIntensity = 80
        state.vcsDiffSectionExpanded = false

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertTrue(state.vcsColorEnabled)
        assertEquals(VcsColorPreset.NEON.name, state.vcsDiffPreset)
        assertEquals(VcsColorPreset.NEON.name, state.vcsMergePreset)
        assertEquals(VcsColorPreset.NEON.name, state.vcsBlamePreset)
        assertEquals(80, state.vcsDiffIntensity)
        assertEquals(80, state.vcsBlameIntensity)
        assertFalse(state.vcsDiffSectionExpanded)
        verify(exactly = 1) { VcsColorApplier.revertAll() }
    }
}
