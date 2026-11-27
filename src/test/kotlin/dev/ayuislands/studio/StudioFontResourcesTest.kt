package dev.ayuislands.studio

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontInstallConsent
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontUninstaller
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.studio.session.StudioFontTerminal
import dev.ayuislands.studio.ui.StudioFontAction
import dev.ayuislands.studio.ui.StudioFontOperation
import dev.ayuislands.studio.ui.StudioFontResources
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioFontResourcesTest {
    private val entry = FontCatalog.requirePreset(FontPreset.AMBIENT)
    private val consent = mockk<FontInstallConsent.InstallConsent>()
    private var refreshed = 0
    private val resources = StudioFontResources { refreshed++ }

    @BeforeTest
    fun setUp() {
        mockkObject(FontInstaller, FontInstallConsent, FontUninstaller, LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { LicenseChecker.requestLicense(any()) } answers { }
        every { FontInstallConsent.confirmInstall(entry, null, compact = true) } returns consent
    }

    @AfterTest
    fun cleanup() = unmockkObject(FontInstaller, FontInstallConsent, FontUninstaller, LicenseChecker)

    @Test
    fun `install and reinstall ask for consent and refresh inventory after the resource completes`() {
        val completions = mutableListOf<(FontInstaller.InstallResult) -> Unit>()
        every { FontInstaller.install(entry, consent, null, applyPreset = false, capture(completions)) } answers { }

        manage(StudioFontOperation.INSTALL)
        manage(StudioFontOperation.REINSTALL)
        assertEquals(0, refreshed)
        completions.forEach { it(FontInstaller.InstallResult.Success(entry.familyName)) }

        verify(exactly = 2) { FontInstallConsent.confirmInstall(entry, null, compact = true) }
        verify(exactly = 2) { FontInstaller.install(entry, consent, null, applyPreset = false, any()) }
        verify(exactly = 0) { FontInstaller.install(any(), any(), any(), any()) }
        assertEquals(2, refreshed)
    }

    @Test
    fun `unlicensed and declined installs never queue file operations`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        manage(StudioFontOperation.INSTALL)
        verify(exactly = 1) { LicenseChecker.requestLicense(any()) }
        verify(exactly = 0) { FontInstallConsent.confirmInstall(any(), any(), any()) }

        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { FontInstallConsent.confirmInstall(entry, null, compact = true) } returns null
        manage(StudioFontOperation.REINSTALL)
        verify(exactly = 0) { FontInstaller.install(any(), any(), any(), any(), any()) }
        assertEquals(0, refreshed)
    }

    @Test
    fun `deleting a font requires consent for the actual directory and preserves the v2 entitlement`() {
        val directory = FontInstaller.platformFontDir().absolutePath
        every { LicenseChecker.isLicensedOrGrace() } returns false
        every { FontInstallConsent.confirmUninstall(entry, null, directory) } returns false
        manage(StudioFontOperation.DELETE)
        verify(exactly = 0) { FontUninstaller.uninstall(any(), any(), any()) }

        every { FontInstallConsent.confirmUninstall(entry, null, directory) } returns true
        every { FontUninstaller.uninstall(FontPreset.AMBIENT, null, any()) } answers {
            lastArg<(FontUninstaller.UninstallResult) -> Unit>()(
                FontUninstaller.UninstallResult.Success(entry.familyName, filesRemoved = 1),
            )
        }
        manage(StudioFontOperation.DELETE)
        verify(exactly = 1) { FontUninstaller.uninstall(FontPreset.AMBIENT, null, any()) }
        assertEquals(1, refreshed)
    }

    @Test
    fun `copy and open use the selected curated command on macOS`() {
        val project = mockk<Project>()
        val clipboard = mockk<CopyPasteManager>()
        val manager = mockk<ToolWindowManager>()
        val terminal = mockk<ToolWindow>()
        val copied = mutableListOf<Transferable>()
        val command = "brew install --cask ${entry.brewCaskSlug}"
        mockkStatic(CopyPasteManager::class, ToolWindowManager::class)
        try {
            every { CopyPasteManager.getInstance() } returns clipboard
            every { (clipboard::setContents)(capture(copied)) } returns Unit
            every { ToolWindowManager.getInstance(project) } returns manager
            every { manager.getToolWindow("Terminal") } returns terminal
            every { terminal.activate(null) } answers {
                assertEquals(command, copied.single().getTransferData(DataFlavor.stringFlavor))
            }

            val expectedActivations = if (SystemInfo.isMac) 1 else 0
            resources.handle(project, StudioFontAction.CopyInstallCommand(entry.preset))
            assertEquals(expectedActivations, copied.size)
            copied.forEach { assertEquals(command, it.getTransferData(DataFlavor.stringFlavor)) }
            verify(exactly = 0) { terminal.activate(null) }
            copied.clear()

            resources.handle(project, StudioFontAction.OpenInstallTerminal(entry.preset, StudioFontTerminal.BUILTIN))

            assertEquals(expectedActivations, copied.size)
            verify(exactly = expectedActivations) { terminal.activate(null) }
        } finally {
            unmockkStatic(CopyPasteManager::class, ToolWindowManager::class)
        }
    }

    private fun manage(operation: StudioFontOperation) {
        resources.handle(null, StudioFontAction.ManageFont(FontPreset.AMBIENT, operation))
    }
}
