package dev.ayuislands.integration

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.projectview.ProjectViewScrollbarManager

class ProjectViewScrollbarServiceTest : BasePlatformTestCase() {
    fun testPlatformCreatesServiceAndRunsQueuedInitialApply() {
        val manager = ProjectViewScrollbarManager.getInstance(project)

        // The queued initial apply must run against the real platform without throwing.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(manager, ProjectViewScrollbarManager.getInstance(project))
    }
}
