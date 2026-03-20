package dev.ayuislands.commitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class CommitPanelAutoFitManagerTest {
    private lateinit var project: Project
    private lateinit var toolWindowManager: ToolWindowManager
    private lateinit var toolWindowEx: ToolWindowEx
    private lateinit var settingsMock: AyuIslandsSettings
    private lateinit var realState: AyuIslandsState
    private lateinit var connection: MessageBusConnection

    @BeforeTest
    fun setUp() {
        mockkStatic(ToolWindowManager::class)
        mockkObject(AyuIslandsSettings.Companion)
        mockkObject(LicenseChecker)

        realState = AyuIslandsState()
        settingsMock =
            mockk<AyuIslandsSettings> {
                every { state } returns realState
            }
        every {
            AyuIslandsSettings.getInstance()
        } returns settingsMock

        connection = mockk(relaxed = true)
        val messageBus =
            mockk<MessageBus> {
                every {
                    connect(any<Disposable>())
                } returns connection
            }

        project =
            mockk(relaxed = true) {
                every { isDisposed } returns false
                every { this@mockk.messageBus } returns messageBus
            }

        toolWindowManager = mockk(relaxed = true)
        every {
            ToolWindowManager.getInstance(project)
        } returns toolWindowManager

        toolWindowEx = mockk(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply does nothing when not licensed`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns false

            val manager = CommitPanelAutoFitManager(project)
            manager.apply()

            // No ToolWindowManager interaction expected
            verify(exactly = 0) {
                toolWindowManager.getToolWindow(any())
            }
        }
    }

    @Test
    fun `apply with DEFAULT mode does not stretch`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.commitPanelWidthMode =
                PanelWidthMode.DEFAULT.name

            // No tool window needed; DEFAULT just removes
            // expansion listener (no-op if none installed)
            every {
                toolWindowManager.getToolWindow("Commit")
            } returns null

            val manager = CommitPanelAutoFitManager(project)
            manager.apply()

            // Should not call stretchWidth
            verify(exactly = 0) {
                toolWindowEx.stretchWidth(any())
            }
        }
    }

    @Test
    fun `apply with FIXED mode calls stretchWidth`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.commitPanelWidthMode =
                PanelWidthMode.FIXED.name
            realState.commitPanelFixedWidth = 350

            val panel = JPanel(FlowLayout())
            panel.setSize(200, 400)
            every {
                toolWindowManager.getToolWindow("Commit")
            } returns toolWindowEx
            every {
                toolWindowEx.component
            } returns panel
            every {
                toolWindowEx.type
            } returns ToolWindowType.DOCKED

            val manager = CommitPanelAutoFitManager(project)
            manager.apply()

            verify { toolWindowEx.stretchWidth(any()) }
        }
    }

    @Test
    fun `dispose removes expansion listener safely`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            every {
                toolWindowManager.getToolWindow("Commit")
            } returns null

            val manager = CommitPanelAutoFitManager(project)
            // Should not throw
            manager.dispose()
        }
    }

    @Test
    fun `init subscribes to ToolWindowManagerListener`() {
        CommitPanelAutoFitManager(project)

        verify {
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                any(),
            )
        }
    }
}
