package dev.ayuislands.accent.toolbar

import com.intellij.openapi.util.io.FileUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Policy regression locks for the popup's premium block. Behavioral coverage
 * for licensed and unlicensed popup construction lives in [QuickSwitcherPopupTest].
 *
 * These source checks retain the explicit-click and context policies without
 * reflecting into implementation details.
 */
class QuickSwitcherPremiumBlockGateTest {
    @Test
    fun `premium explanation uses an explicit license request without auto-browsing`() {
        val source = FileUtil.loadFile(File(POPUP_SOURCE_PATH))

        assertTrue(source.contains("Learn about Premium"))
        assertTrue(source.contains("LicenseChecker.requestLicense"))
        assertFalse(source.contains("BrowserUtil.browse"))
    }

    @Test
    fun `popup source builds variant card only for Ayu context`() {
        val source = FileUtil.loadFile(File(POPUP_SOURCE_PATH))

        assertTrue(
            source.contains("AccentContext.External"),
            "Popup must explicitly support external accent context",
        )
        assertTrue(
            source.contains("is AccentContext.Ayu"),
            "Variant switcher card must render only for Ayu context",
        )
        assertTrue(
            source.contains("AccentContext.External -> null"),
            "External context must skip the variant switcher card",
        )
    }

    private companion object {
        const val POPUP_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherPopup.kt"
    }
}
