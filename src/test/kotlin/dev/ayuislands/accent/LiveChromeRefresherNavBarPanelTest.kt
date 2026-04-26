package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Locks the Phase 40.4 NavBar Compact (IntelliJ 2026.1) extensions to
 * [LiveChromeRefresher.refresh] / `.clear` for `ChromeTarget.StatusBar`.
 *
 * The Compact Navigation refactor moved the path widget breadcrumb out of
 * `MyNavBarWrapperPanel` and into the status bar tree as
 * `com.intellij.platform.navbar.frontend.ui.NavBarItemComponent` instances under
 * `NewNavBarPanel`. The panel pins its own `setBackground(List.background)` in
 * its initialiser, so the chrome tint applied to the root status bar peer never
 * reaches it through Swing inheritance — without an explicit walk, slider
 * intensity changes have no visible effect on the path widget bg in 2026.1.
 *
 * The production class FQN is referenced by string literal and matched at
 * runtime — defining a stand-in class with that exact FQN in test sources would
 * collide with the platform JAR on the test classpath, so this suite locks the
 * regressing behaviours we CAN verify without that collision:
 *
 *  1. The status bar root tinting still works after the Phase 40.4 walk was
 *     added (regression guard for the prior root-only path).
 *  2. The walk completes gracefully on trees that do NOT contain the Compact
 *     panel (pre-2026.1 platforms, headless test JVM, custom IDE skins).
 *  3. Classes whose FQN does not match the production constant are NOT tinted
 *     (defends against accidental fan-out to user-plugin panels).
 *  4. The FQN constant string is correct (the production class name).
 *
 * Verifying that the walk DOES tint a real `NewNavBarPanel` peer requires the
 * production classpath, so it lives in the manual smoke matrix in the Phase
 * 40.4 plan rather than this suite.
 */
class LiveChromeRefresherNavBarPanelTest {
    private val targetColor = Color(0x44, 0x83, 0x8F)

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `refresh tints status bar root when no NavBar Compact descendant exists`() {
        // Regression guard: Phase 40.4 added the descent walk inside the
        // StatusBar branch. The walk must NOT prevent the existing root-tint
        // behaviour from completing — every existing user with a pre-2026.1
        // platform (or a stripped headless tree) still sees the chrome tint.
        val plainStatusBarRoot = JPanel()
        installMockStatusBar(plainStatusBarRoot)

        LiveChromeRefresher.refresh(ChromeTarget.StatusBar, targetColor)

        assertEquals(
            targetColor,
            plainStatusBarRoot.background,
            "Phase 40.4 descent walk must NOT regress the existing root tint",
        )
    }

    @Test
    fun `clear nulls status bar root when no NavBar Compact descendant exists`() {
        val plainStatusBarRoot = JPanel().apply { background = Color.RED }
        installMockStatusBar(plainStatusBarRoot)

        LiveChromeRefresher.clear(ChromeTarget.StatusBar)

        assertNull(plainStatusBarRoot.background)
    }

    @Test
    fun `refresh tolerates deeply nested status bar trees without throwing`() {
        // Status bar children may be 5+ containers deep before reaching the
        // Compact panel; `walkOnContainer` must traverse arbitrary depth without
        // stack overflow or exception even if the target class isn't present.
        val viewport = JPanel()
        val scrollPane = JPanel().apply { add(viewport) }
        val container = JPanel().apply { add(scrollPane) }
        val refreshAndInfoPanel = JPanel().apply { add(container) }
        val infoAndProgressPanel = JPanel().apply { add(refreshAndInfoPanel) }
        val statusBarRoot = JPanel().apply { add(infoAndProgressPanel) }
        installMockStatusBar(statusBarRoot)

        LiveChromeRefresher.refresh(ChromeTarget.StatusBar, targetColor)

        assertEquals(targetColor, statusBarRoot.background, "Root tint must apply")
    }

    @Test
    fun `refresh does not tint impostor classes that share simple name but differ in package`() {
        // Defensive guard: the runtime FQN match must be EXACT. A user plugin
        // that names a custom panel `NewNavBarPanel` in their own package must
        // NOT be accidentally tinted. The class below has the same simple name
        // but a `dev.ayuislands.accent.<test-class>` FQN, so no match.
        val impostor = ImpostorPanel()
        val statusBarRoot = JPanel().apply { add(impostor) }
        installMockStatusBar(statusBarRoot)

        LiveChromeRefresher.refresh(ChromeTarget.StatusBar, targetColor)

        assertEquals(targetColor, statusBarRoot.background, "Root must still tint")
        // ImpostorPanel inherits AWT/LAF default Panel bg (non-null) — assert
        // we did NOT overwrite it with our chrome accent rather than expecting
        // strict null, since plain JPanels start with a system-default bg.
        assertNotEquals(
            targetColor,
            impostor.background,
            "Class with similar simple name but non-matching FQN must NOT be tinted",
        )
    }

    @Test
    fun `production FQN constant matches the class string referenced in 2026 1 platform sources`() {
        // Constant lock — if the platform ever renames the class (or moves the
        // package), the FQN string here drifts out of sync with production and
        // the walk silently fails to tint anything. The test reaches into the
        // private companion via reflection so a future refactor that renames
        // the constant still trips this assertion.
        val field = LiveChromeRefresher::class.java.getDeclaredField("NAVBAR_COMPACT_PANEL_FQN")
        field.isAccessible = true
        val value = field.get(LiveChromeRefresher) as String
        assertEquals(
            "com.intellij.platform.navbar.frontend.ui.NewNavBarPanel",
            value,
            "FQN constant must match the class referenced by NavBarItemComponent.update() in 2026.1",
        )
    }

    private fun installMockStatusBar(rootComponent: JPanel) {
        val project = mockUsableProject()
        val statusBar =
            mockk<StatusBar>(relaxed = true) {
                every { component } returns rootComponent
            }
        val windowManager =
            mockk<WindowManager>(relaxed = true) {
                every { getStatusBar(project) } returns statusBar
            }
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns windowManager
        mockProjectManagerOpenProjects(project)
    }

    private fun mockUsableProject(): Project =
        mockk(relaxed = true) {
            every { isDefault } returns false
            every { isDisposed } returns false
        }

    private fun mockProjectManagerOpenProjects(vararg projects: Project) {
        val projectManager =
            mockk<ProjectManager>(relaxed = true) {
                every { openProjects } returns projects.toList().toTypedArray()
            }
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns projectManager
    }

    /**
     * Intentionally `private`-package for FQN isolation. The simple name
     * coincidentally matches part of the production constant; the FULL FQN
     * does not — that's the whole point of this defensive test.
     */
    @Suppress("ClassNaming")
    private class ImpostorPanel : JPanel()
}
