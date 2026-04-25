package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.theme.AyuEditorSchemeBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Locks the three load-bearing invariants of the modified `AyuIslandsLafListener`:
 *   1. The `syncEditorScheme` settings flag gates `AyuEditorSchemeBinder.bindForVariant`.
 *   2. When the gate is open and a variant is active, `bindForVariant` runs
 *      BEFORE `AccentApplicator.applyForFocusedProject` — required because
 *      `AccentApplicator.applyAlwaysOnEditorKeys` mutates `globalScheme`
 *      in-place and a swap-after-mutate would strand the prior scheme with
 *      accent overrides forever.
 *   3. The `variant == null` early-return path (theme switched AWAY from
 *      Ayu) does NOT call `bindForVariant` — the binder has no revert path.
 *
 * Pattern G — symmetry between the bind/apply order and the revert path.
 */
@Suppress("UnstableApiUsage")
class AyuIslandsLafListenerTest {
    private val state = AyuIslandsState()
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val mockProjectManager = mockk<ProjectManager>(relaxed = true)

    @BeforeTest
    fun setUp() {
        every { mockSettings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(AyuVariant.Companion)
        mockkObject(AyuEditorSchemeBinder)
        mockkObject(AccentApplicator)
        mockkObject(FontPresetApplicator)
        mockkObject(GlowOverlayManager)
        mockkObject(AppearanceSyncService.Companion)

        every { AccentApplicator.applyForFocusedProject(any()) } returns "#FFCC66"
        every { AccentApplicator.revertAll() } returns Unit
        every { FontPresetApplicator.applyFromState() } returns Unit
        every { FontPresetApplicator.revert() } returns Unit
        every { GlowOverlayManager.syncGlowForAllProjects() } returns Unit
        every { AyuEditorSchemeBinder.bindForVariant(any()) } returns true

        val mockSyncService = mockk<AppearanceSyncService>(relaxed = true)
        every { AppearanceSyncService.getInstance() } returns mockSyncService
        every { mockSyncService.programmaticSwitch } returns false
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `lookAndFeelChanged calls binder when syncEditorScheme is true and variant is active`() {
        state.syncEditorScheme = true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val mockLafManager = mockk<LafManager>(relaxed = true)
        every { mockLafManager.currentUIThemeLookAndFeel.name } returns "Ayu Mirage (Islands UI)"

        AyuIslandsLafListener().lookAndFeelChanged(mockLafManager)

        verify(exactly = 1) { AyuEditorSchemeBinder.bindForVariant(AyuVariant.MIRAGE) }
    }

    @Test
    fun `lookAndFeelChanged skips binder when syncEditorScheme is false`() {
        state.syncEditorScheme = false
        every { AyuVariant.detect() } returns AyuVariant.DARK
        val mockLafManager = mockk<LafManager>(relaxed = true)
        every { mockLafManager.currentUIThemeLookAndFeel.name } returns "Ayu Dark (Islands UI)"

        AyuIslandsLafListener().lookAndFeelChanged(mockLafManager)

        verify(exactly = 0) { AyuEditorSchemeBinder.bindForVariant(any()) }
        // Pattern G regression lock: AccentApplicator still runs even when binder is gated off.
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AyuVariant.DARK) }
    }

    @Test
    fun `lookAndFeelChanged invokes binder BEFORE AccentApplicator on Ayu variant`() {
        // Pattern G ordering lock — the load-bearing invariant the listener KDoc
        // claims to defend. AccentApplicator mutates globalScheme in-place via
        // applyAlwaysOnEditorKeys; the binder MUST swap globalScheme first so
        // the mutation lands on the freshly-swapped Ayu scheme, not the prior
        // (Default/Darcula/another-Ayu) scheme that would otherwise be left
        // polluted with accent overrides forever.
        state.syncEditorScheme = true
        every { AyuVariant.detect() } returns AyuVariant.LIGHT
        val mockLafManager = mockk<LafManager>(relaxed = true)
        every { mockLafManager.currentUIThemeLookAndFeel.name } returns "Ayu Light (Islands UI)"

        AyuIslandsLafListener().lookAndFeelChanged(mockLafManager)

        verifyOrder {
            AyuEditorSchemeBinder.bindForVariant(AyuVariant.LIGHT)
            AccentApplicator.applyForFocusedProject(AyuVariant.LIGHT)
        }
    }

    @Test
    fun `lookAndFeelChanged on non-Ayu LAF reverts and does NOT bind`() {
        // Pattern J — the listener's `variant == null` early-return must NOT
        // call the binder. The binder has no revert path (Ayu→non-Ayu would
        // require persisting the user's prior scheme — separate feature).
        state.syncEditorScheme = true
        every { AyuVariant.detect() } returns null
        val mockLafManager = mockk<LafManager>(relaxed = true)

        AyuIslandsLafListener().lookAndFeelChanged(mockLafManager)

        verify(exactly = 0) { AyuEditorSchemeBinder.bindForVariant(any()) }
        verify(exactly = 1) { AccentApplicator.revertAll() }
        verify(exactly = 1) { FontPresetApplicator.revert() }
        verify(exactly = 1) { GlowOverlayManager.syncGlowForAllProjects() }
    }
}
