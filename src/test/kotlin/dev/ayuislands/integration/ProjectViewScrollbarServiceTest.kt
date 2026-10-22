package dev.ayuislands.integration

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.projectview.ProjectViewScrollbarManager

class ProjectViewScrollbarServiceTest : BasePlatformTestCase() {
    fun testPlatformCreatesServiceWithoutErrors() {
        val manager = ProjectViewScrollbarManager.getInstance(project)

        // Drain the EDT so anything the service queued during construction fails this test if it throws.
        // That the queued apply uses the injected dependencies is pinned by ProjectViewScrollbarManagerTest.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(manager, ProjectViewScrollbarManager.getInstance(project))
    }
}
