package dev.ayuislands.accent.toolbar

import com.intellij.openapi.util.io.FileUtil
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pattern J gate regression lock for the popup's premium block. The popup
 * wraps the premium SectionCards (related toggles + quick actions) AND the
 * BlockSeparator between FREE/PREMIUM in
 * `.visibleIf(ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))`.
 * The separator row also joins the gated rows so a free user does NOT see an
 * orphaned hairline above the missing premium block.
 *
 * The tests below lock the gate against two classes of regressions:
 *   - predicate sanity — mocking `isLicensedOrGrace` flips visibility,
 *   - NO upgrade-promo lock — forbidden tokens have zero matches.
 */
class QuickSwitcherPremiumBlockGateTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `live licenseGate invoke returns true when license is in grace, false when expired`() {
        // Behavior test (replaces tautological mocked variants that asserted
        // the mock returns the mocked value). Reflects into the popup's
        // private `licenseGate()` helper and asserts its `invoke()` follows
        // live license state — which is the actual production contract: trial
        // expiring (or license activating) while the popup is open MUST flip
        // the premium rows on the next paint.
        mockkObject(LicenseChecker)

        val popupClass = QuickSwitcherPopup::class.java
        val gateMethod = popupClass.getDeclaredMethod("licenseGate")
        gateMethod.isAccessible = true
        val predicate =
            gateMethod.invoke(QuickSwitcherPopup) as com.intellij.ui.layout.ComponentPredicate

        every { LicenseChecker.isLicensedOrGrace() } returns true
        assertTrue(predicate.invoke(), "Live gate must follow license=true")

        every { LicenseChecker.isLicensedOrGrace() } returns false
        assertFalse(predicate.invoke(), "Live gate must follow license=false")

        // Transition through the lifecycle: grace → expired → licensed → grace
        // (user scenario — trial expires while popup open, then user activates
        // a license; premium rows must follow each flip in turn).
        every { LicenseChecker.isLicensedOrGrace() } returns true
        assertTrue(predicate.invoke())
        every { LicenseChecker.isLicensedOrGrace() } returns false
        assertFalse(predicate.invoke())
        every { LicenseChecker.isLicensedOrGrace() } returns true
        assertTrue(predicate.invoke())
    }

    @Test
    fun `popup source contains no upgrade-promo language`() {
        // The locked-with-tooltip pattern was explicitly rejected. A future
        // careless edit adding "Upgrade to unlock" hints in the popup body
        // would trip this test. Case-insensitive match.
        val source = FileUtil.loadFile(File(POPUP_SOURCE_PATH))
        val forbidden = listOf("Upgrade", "unlocked", "lockTooltip", "promo")
        for (token in forbidden) {
            val pattern = "(?i)$token".toRegex()
            val count = pattern.findAll(source).count()
            assertEquals(0, count, "Upgrade-promo lock: popup body must not contain `$token` (count=$count)")
        }
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
