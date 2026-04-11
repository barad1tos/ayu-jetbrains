package dev.ayuislands.font

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontInstallConsentTest {
    private val mapleEntry = FontCatalog.forPreset(FontPreset.AMBIENT)

    @BeforeTest
    fun setup() {
        mockkObject(FontInstallConsent)
        every { FontInstallConsent.platformFontDirLabel() } returns "~/Library/Fonts"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(FontInstallConsent)
    }

    @Test
    fun `confirmInstall_compactFalse includes full copy`() {
        val message = FontInstallConsent.buildInstallMessage(mapleEntry, compact = false)
        assertTrue(message.contains("SIL Open Font License"), "Missing license blurb")
        assertTrue(message.contains("no admin rights required"), "Missing admin reassurance")
        assertTrue(message.contains("${mapleEntry.approxSizeMb} MB"), "Missing size")
        assertTrue(message.contains("~/Library/Fonts"), "Missing path label")
        assertTrue(message.contains(mapleEntry.displayName), "Missing display name")
    }

    @Test
    fun `confirmInstall_compactTrue omits license blurb`() {
        val message = FontInstallConsent.buildInstallMessage(mapleEntry, compact = true)
        assertFalse(message.contains("SIL Open Font License"), "Compact form must omit license blurb")
        assertFalse(message.contains("no admin rights required"), "Compact form must omit admin reassurance")
        assertTrue(message.contains("${mapleEntry.approxSizeMb} MB"), "Compact form must keep size")
        assertTrue(message.contains(mapleEntry.displayName), "Compact form must keep display name")
        assertTrue(message.contains("~/Library/Fonts"), "Compact form must keep path label")
    }

    @Test
    fun `confirmUninstall includes absolute path verbatim`() {
        val absPath = "/Users/tester/Library/Fonts"
        val message = FontInstallConsent.buildUninstallMessage(mapleEntry, absPath)
        assertTrue(message.contains(absPath), "Absolute path must appear verbatim")
        assertTrue(message.contains(mapleEntry.displayName), "Display name must appear")
    }

    @Test
    fun `confirmUninstall includesRestartWarning`() {
        val message = FontInstallConsent.buildUninstallMessage(mapleEntry, "/tmp/fonts")
        assertTrue(message.contains("IDE restart"), "Restart-effective warning is mandatory (RESEARCH A3, D-07)")
    }
}
