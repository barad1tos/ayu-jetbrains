package dev.ayuislands.accent.toolbar

import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.nio.file.Files
import java.nio.file.Paths
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
 * The tests below lock the gate against four classes of regressions:
 *   - predicate sanity — mocking `isLicensedOrGrace` flips visibility,
 *   - source-grep gate count — exactly three `.visibleIf` calls,
 *   - source-grep license-ref count — three gates reference license check,
 *   - Pattern J two-conjunct invariant — no chip-level state leaks into
 *     the popup gate body,
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
    fun `QuickSwitcherPopup source body wraps premium rows in three visibleIf gates`() {
        // Three gates: toggles SectionCard + actions SectionCard + BlockSeparator.
        val source = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        val gateCount = "\\.visibleIf".toRegex().findAll(source).count()
        assertEquals(EXPECTED_GATE_COUNT, gateCount, "Expected exactly $EXPECTED_GATE_COUNT .visibleIf gates")
    }

    @Test
    fun `popup source defines exactly one shared live licenseGate helper`() {
        // The three premium gates MUST share one helper so they cannot drift
        // (a future edit that re-inlines one gate would re-introduce the
        // stale-snapshot bug). Lock the shared-helper pattern by source-grep.
        val source = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        val helperDefs = "private fun licenseGate".toRegex().findAll(source).count()
        assertEquals(1, helperDefs, "Expected exactly one licenseGate() helper definition")
        // All three `.visibleIf` calls must pass the helper-returned predicate,
        // not a `ComponentPredicate.fromValue(...)` snapshot.
        val snapshots =
            "ComponentPredicate\\.fromValue\\(LicenseChecker"
                .toRegex()
                .findAll(source)
                .count()
        assertEquals(
            0,
            snapshots,
            "Live-gate lock: no fromValue snapshot of LicenseChecker may leak back into the popup body",
        )
    }

    @Test
    fun `visibleIf bodies do not leak chip-level predicates (Pattern J two-conjunct invariant)`() {
        // The chip's two-conjunct LAF + state gate lives in
        // `QuickSwitcherWidgetAction.update()`; the popup's premium gate is
        // purely license-based. A future careless edit that adds
        // `state.quickSwitcherWidgetEnabled` or `isAyuActive` into the popup's
        // `.visibleIf` body would shift a chip-level concern into the popup
        // body and trip this assertion.
        val source = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        val forbidden = listOf("quickSwitcherWidgetEnabled", "isAyuActive")
        for (token in forbidden) {
            val matches =
                source.split(".visibleIf").drop(1).any { tail ->
                    // The next ~200 chars after `.visibleIf` covers our compact
                    // lambda or ComponentPredicate body.
                    tail.take(GATE_BODY_SPAN).contains(token)
                }
            assertFalse(matches, ".visibleIf body must not reference $token (chip-level concern)")
        }
    }

    @Test
    fun `popup source contains no upgrade-promo language`() {
        // The locked-with-tooltip pattern was explicitly rejected. A future
        // careless edit adding "Upgrade to unlock" hints in the popup body
        // would trip this test. Case-insensitive match.
        val source = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        val forbidden = listOf("Upgrade", "unlocked", "lockTooltip", "promo")
        for (token in forbidden) {
            val pattern = "(?i)$token".toRegex()
            val count = pattern.findAll(source).count()
            assertEquals(0, count, "Upgrade-promo lock: popup body must not contain `$token` (count=$count)")
        }
    }

    private companion object {
        const val POPUP_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherPopup.kt"

        // Three gates: toggles SectionCard + actions SectionCard +
        // BlockSeparator row are all wrapped in
        // `.visibleIf(ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))`.
        const val EXPECTED_GATE_COUNT = 3
        const val GATE_BODY_SPAN = 200
    }
}
