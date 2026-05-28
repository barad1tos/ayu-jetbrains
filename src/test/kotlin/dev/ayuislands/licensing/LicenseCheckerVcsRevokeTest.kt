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
import dev.ayuislands.syntax.SyntaxReadabilityOptions
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
    fun `revertToFreeDefaults resets all 11 VCS intensity fields to AMBIENT_SLIDER`() {
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

        val expected = VcsColorPreset.AMBIENT_SLIDER
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
    fun `revertToFreeDefaults resets all 3 VCS preset names to AMBIENT`() {
        // Seed each section preset to `NEON` so we can see the revert ran. Using
        // `VcsColorPreset.NEON.name` (round-tripped through `byName`) instead of
        // a raw literal protects the assertion from a future enum-name change.
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.NEON.name
        state.vcsBlamePreset = VcsColorPreset.NEON.name

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        val expected = VcsColorPreset.AMBIENT.name
        assertEquals(expected, state.vcsDiffPreset)
        assertEquals(expected, state.vcsMergePreset)
        assertEquals(expected, state.vcsBlamePreset)
    }

    // ---------- 1 master toggle ----------

    @Test
    fun `revertToFreeDefaults clears vcsColorEnabled regardless of prior state`() {
        state.vcsColorEnabled = true
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertFalse(state.vcsColorEnabled, "vcsColorEnabled must be false on free tier")
    }

    // ---------- 3 section-expanded flags ----------

    @Test
    fun `revertToFreeDefaults resets section-expanded flags to defaults`() {
        // Seed all three to false so the diff flag has to flip back to true and
        // the merge/blame flags stay at false — exercises both branches of the
        // expanded-default contract.
        state.vcsDiffSectionExpanded = false
        state.vcsMergeSectionExpanded = false
        state.vcsBlameSectionExpanded = false

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertTrue(state.vcsDiffSectionExpanded, "diff section opens expanded by default")
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
    fun `revertToFreeDefaults resets syntax intensity state and reapplies Ambient`() {
        syntaxState.state.selectedPreset = SyntaxPreset.CUSTOM.name
        syntaxState.state.subordinatePreset = SyntaxPreset.NEON.name
        syntaxState.state.customOverrides["Java|KEYWORD"] = "85"
        syntaxState.state.customStyles["Java|KEYWORD"] = "BOLD"
        syntaxState.state.dimComments = true
        syntaxState.state.softenDocumentation = true
        syntaxState.state.quietOperators = true
        syntaxState.state.emphasizeDeclarations = true

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals(SyntaxPreset.AMBIENT.name, syntaxState.state.selectedPreset)
        assertEquals(SyntaxPreset.AMBIENT.name, syntaxState.state.subordinatePreset)
        assertTrue(syntaxState.state.customOverrides.isEmpty(), "syntax custom overrides must be cleared")
        assertTrue(syntaxState.state.customStyles.isEmpty(), "syntax custom styles must be cleared")
        assertFalse(syntaxState.state.dimComments, "premium readability toggles must be cleared on downgrade")
        assertFalse(syntaxState.state.softenDocumentation, "premium readability toggles must be cleared on downgrade")
        assertFalse(syntaxState.state.quietOperators, "premium readability toggles must be cleared on downgrade")
        assertFalse(syntaxState.state.emphasizeDeclarations, "premium readability toggles must be cleared on downgrade")
        verify(exactly = 1) {
            syntaxService.apply(
                SyntaxPreset.AMBIENT,
                emptyMap(),
                SyntaxPreset.AMBIENT,
                emptyMap(),
                SyntaxReadabilityOptions.DEFAULT,
            )
        }
    }

    // ---------- Defensive: applier exception does not block state reset ----------

    @Test
    fun `revertToFreeDefaults state reset still completes when VcsColorApplier revertAll throws`() {
        // The applier call is wrapped in try-catch(RuntimeException) inside
        // [LicenseChecker.revertToFreeDefaults] (Pattern B compliant). State
        // mutations happen BEFORE the call inside the `synchronized(state)`
        // block, so a thrown RuntimeException from `revertAll` must not roll
        // back the field resets — only the editor scheme write-through is lost.
        every { VcsColorApplier.revertAll() } throws RuntimeException("boom")

        state.vcsColorEnabled = true
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.NEON.name
        state.vcsBlamePreset = VcsColorPreset.NEON.name
        state.vcsDiffIntensity = 80
        state.vcsBlameIntensity = 80
        state.vcsDiffSectionExpanded = false

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertFalse(state.vcsColorEnabled)
        assertEquals(VcsColorPreset.AMBIENT.name, state.vcsDiffPreset)
        assertEquals(VcsColorPreset.AMBIENT.name, state.vcsMergePreset)
        assertEquals(VcsColorPreset.AMBIENT.name, state.vcsBlamePreset)
        assertEquals(VcsColorPreset.AMBIENT_SLIDER, state.vcsDiffIntensity)
        assertEquals(VcsColorPreset.AMBIENT_SLIDER, state.vcsBlameIntensity)
        assertTrue(state.vcsDiffSectionExpanded)
        verify(exactly = 1) { VcsColorApplier.revertAll() }
    }
}
