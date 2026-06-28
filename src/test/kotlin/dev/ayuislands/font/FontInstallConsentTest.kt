package dev.ayuislands.font

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontInstallConsentTest {
    private val mapleEntry = FontCatalog.requirePreset(FontPreset.AMBIENT)

    @BeforeTest
    fun setup() {
        mockkObject(FontInstallConsent)
        every { FontInstallConsent.platformFontDirLabel() } returns "~/Library/Fonts"
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
    }

    private fun acceptedInstallConsent(entry: FontCatalog.Entry): FontInstallConsent.InstallConsent {
        mockkObject(MessageDialogBuilder.Companion)
        val project = mockk<Project>(relaxed = true)
        val dialog = mockk<MessageDialogBuilder.YesNo>(relaxed = true)
        every { MessageDialogBuilder.yesNo(any<String>(), any<String>()) } returns dialog
        every { dialog.yesText(any()) } returns dialog
        every { dialog.noText(any()) } returns dialog
        every { dialog.ask(project) } returns true
        return FontInstallConsent.confirmInstall(entry, project) ?: error("Accepted dialog must return install consent")
    }

    @Test
    fun `install message full copy includes license and path`() {
        val message = FontInstallConsent.buildInstallMessage(mapleEntry, compact = false)
        assertTrue(message.contains("SIL Open Font License"))
        assertTrue(message.contains("no admin rights required"))
        assertTrue(message.contains("${mapleEntry.approxSizeMb} MB"))
        assertTrue(message.contains("~/Library/Fonts"))
        assertTrue(message.contains(mapleEntry.displayName))
    }

    @Test
    fun `install message compact omits license keeps essentials`() {
        val message = FontInstallConsent.buildInstallMessage(mapleEntry, compact = true)
        assertFalse(message.contains("SIL Open Font License"))
        assertFalse(message.contains("no admin rights required"))
        assertTrue(message.contains("${mapleEntry.approxSizeMb} MB"))
        assertTrue(message.contains(mapleEntry.displayName))
        assertTrue(message.contains("~/Library/Fonts"))
    }

    @Test
    fun `install consent proof matches only originating entry`() {
        val consent = acceptedInstallConsent(mapleEntry)

        assertTrue(consent.matches(mapleEntry))
        assertFalse(consent.matches(FontCatalog.requirePreset(FontPreset.WHISPER)))
    }

    @Test
    fun `uninstall message includes absolute path verbatim`() {
        val absPath = "/Users/tester/Library/Fonts"
        val message = FontInstallConsent.buildUninstallMessage(mapleEntry, absPath)
        assertTrue(message.contains(absPath))
        assertTrue(message.contains(mapleEntry.displayName))
    }

    @Test
    fun `uninstall message includes restart warning`() {
        val message = FontInstallConsent.buildUninstallMessage(mapleEntry, "/tmp/fonts")
        assertTrue(message.contains("IDE restart"))
    }

    @Test
    fun `platformFontDirLabel returns recognizable OS-specific path`() {
        unmockkAll()
        val label = FontInstallConsent.platformFontDirLabel()
        assertTrue(
            label.contains("Library/Fonts") ||
                label.contains("LOCALAPPDATA") ||
                label.contains(".local/share/fonts"),
            "Label must be a recognizable platform font dir: $label",
        )
    }
}
