package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks in the contract of [AccentApplicator.applyForFocusedProject] — five production
 * callers (the three settings panels, the LAF listener, the rotation tick) previously
 * hand-wired inconsistent variants of this sequence (most skipped the swap-cache sync).
 * These tests are the safety net that keeps the sequence (focused-project selection →
 * resolver → apply → swap-cache sync) from drifting.
 *
 * Uses the real [AccentApplicator] object but mocks every collaborator:
 *  - [com.intellij.openapi.wm.IdeFocusManager] is left unmocked → returns the headless
 *    no-op manager whose `lastFocusedFrame` is `null`, so these tests exercise the
 *    [ProjectManager] fallback path. Primary-path coverage (real focused frame) is left
 *    to manual smoke / IDE integration; the production fallback chain is documented in
 *    the helper's own KDoc.
 *  - [ProjectManager.getInstance] for the fallback focused-project pick (first
 *    non-default, non-disposed open project)
 *  - [AccentResolver.resolve] for the resolver call (covered independently in AccentResolverTest)
 *  - A partial mock on [AccentApplicator] itself to intercept `apply(hex)` — we only care that
 *    the helper forwards the resolver's output, not the full UIManager side-effect chain
 *  - [ProjectAccentSwapService.getInstance] for the notifyExternalApply assertion
 */
class AccentApplicatorFocusedProjectTest {
    private lateinit var swapService: ProjectAccentSwapService

    @BeforeTest
    fun setUp() {
        mockkStatic(ProjectManager::class)
        mockkObject(AccentResolver)
        mockkObject(AccentApplicator, recordPrivateCalls = false)
        every { AccentApplicator.apply(any()) } just Runs

        mockkObject(ProjectAccentSwapService.Companion)
        swapService = mockk(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `applyForFocusedProject forwards the resolver output to apply and the swap cache`() {
        val focused = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(focused)
        every { ProjectManager.getInstance() } returns manager
        every { AccentResolver.resolve(focused, AyuVariant.MIRAGE) } returns "#ABCDEF"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        assertEquals("#ABCDEF", applied)
        verify(exactly = 1) { AccentApplicator.apply("#ABCDEF") }
        verify(exactly = 1) { swapService.notifyExternalApply("#ABCDEF") }
    }

    @Test
    fun `applyForFocusedProject skips default and disposed projects when picking focus`() {
        val defaultProject = stubProject(isDefault = true, isDisposed = false)
        val disposedProject = stubProject(isDefault = false, isDisposed = true)
        val realFocus = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(defaultProject, disposedProject, realFocus)
        every { ProjectManager.getInstance() } returns manager
        every { AccentResolver.resolve(realFocus, AyuVariant.DARK) } returns "#123456"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.DARK)

        assertEquals("#123456", applied)
        verify(exactly = 1) { AccentResolver.resolve(realFocus, AyuVariant.DARK) }
    }

    @Test
    fun `applyForFocusedProject passes null to resolver when no non-default project is open`() {
        // Simulates the "Welcome screen / all projects closed" startup path. Resolver sees a null
        // project, short-circuits to the global accent, and the helper still propagates it.
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns emptyArray()
        every { ProjectManager.getInstance() } returns manager
        every { AccentResolver.resolve(null, AyuVariant.LIGHT) } returns "#F29718"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.LIGHT)

        assertEquals("#F29718", applied)
        verify(exactly = 1) { AccentApplicator.apply("#F29718") }
        verify(exactly = 1) { swapService.notifyExternalApply("#F29718") }
    }

    @Test
    fun `applyForFocusedProject ordering - resolver runs before apply, swap cache after`() {
        // The swap cache's notifyExternalApply must fire AFTER apply, otherwise a concurrent
        // window-activated event would see the cache updated for a hex that's not yet painted.
        val focused = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(focused)
        every { ProjectManager.getInstance() } returns manager
        every { AccentResolver.resolve(focused, AyuVariant.MIRAGE) } returns "#FFCC66"

        AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        io.mockk.verifyOrder {
            AccentResolver.resolve(focused, AyuVariant.MIRAGE)
            AccentApplicator.apply("#FFCC66")
            swapService.notifyExternalApply("#FFCC66")
        }
    }

    private fun stubProject(
        isDefault: Boolean,
        isDisposed: Boolean,
    ): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns isDefault
        every { project.isDisposed } returns isDisposed
        return project
    }
}
