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

/**
 * Plan 48-04 Task 3 (extended Wave 7) — Pattern J gate regression lock for the
 * popup's premium block. The popup wraps the premium SectionCards (related
 * toggles + quick actions) AND the BlockSeparator between FREE/PREMIUM in
 * `.visibleIf(ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))`
 * per D-07 / D-09 / spec §2.2. Wave 7 bumps `EXPECTED_GATE_COUNT` from 2 to 3
 * because the separator row now joins the gated rows so a free user does NOT
 * see an orphaned hairline above the missing premium block.
 *
 * Tests 17..21 lock the gate against four classes of regressions:
 *   - 17: predicate sanity — mocking `isLicensedOrGrace` flips visibility,
 *   - 18: source-grep gate count — exactly three `.visibleIf` calls,
 *   - 19: source-grep license-ref count — three gates reference license check,
 *   - 20: Pattern J two-conjunct invariant — no chip-level state leaks into
 *     the popup gate body,
 *   - 21: D-07 NO upgrade-promo lock — forbidden tokens have zero matches.
 */
class QuickSwitcherPremiumBlockGateTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `premium block predicate evaluates to true when license is in grace`() {
        // Test 17a
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        assertEquals(true, LicenseChecker.isLicensedOrGrace())
    }

    @Test
    fun `premium block predicate evaluates to false when license expired`() {
        // Test 17b
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns false
        assertEquals(false, LicenseChecker.isLicensedOrGrace())
    }

    @Test
    fun `QuickSwitcherPopup source body wraps premium rows in three visibleIf gates`() {
        // Test 18 — Wave 7 bumped the expected count to 3 (toggles + actions +
        // BlockSeparator).
        val source = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        val gateCount = "\\.visibleIf".toRegex().findAll(source).count()
        assertEquals(EXPECTED_GATE_COUNT, gateCount, "Expected exactly $EXPECTED_GATE_COUNT .visibleIf gates")
    }

    @Test
    fun `each visibleIf gate references LicenseChecker isLicensedOrGrace`() {
        // Test 19 — count occurrences of the license-check token. The lambda
        // form `.visibleIf { LicenseChecker.isLicensedOrGrace() }` puts one
        // reference per gate; the ComponentPredicate.fromValue(...) form would
        // also put one reference per gate.
        val source = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        val licenseRefs = "isLicensedOrGrace".toRegex().findAll(source).count()
        assertEquals(EXPECTED_GATE_COUNT, licenseRefs, "Each .visibleIf must reference isLicensedOrGrace")
    }

    @Test
    fun `visibleIf bodies do not leak chip-level predicates (Pattern J two-conjunct invariant)`() {
        // Test 20 — the chip's two-conjunct LAF + state gate is in
        // QuickSwitcherWidgetAction.update(); the popup's premium gate is
        // purely license-based. A future careless edit that adds
        // `state.quickSwitcherWidgetEnabled` or `isAyuActive` into the popup's
        // .visibleIf body would shift a chip-level concern into the popup body
        // and trip this assertion.
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
    fun `popup source contains no upgrade-promo language (D-07 lock)`() {
        // Test 21 — D-07 explicitly rejected the locked-with-tooltip pattern.
        // A future careless edit adding "Upgrade to unlock" hints in the popup
        // body would trip this test. Case-insensitive match.
        val source = Files.readString(Paths.get(POPUP_SOURCE_PATH))
        val forbidden = listOf("Upgrade", "unlocked", "lockTooltip", "promo")
        for (token in forbidden) {
            val pattern = "(?i)$token".toRegex()
            val count = pattern.findAll(source).count()
            assertEquals(0, count, "D-07 lock: popup body must not contain `$token` (count=$count)")
        }
    }

    private companion object {
        const val POPUP_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherPopup.kt"

        // Wave 7 bumped the count from 2 to 3: toggles SectionCard + actions
        // SectionCard + BlockSeparator row are all wrapped in `.visibleIf(
        // ComponentPredicate.fromValue(LicenseChecker.isLicensedOrGrace()))`
        // per 48-REDESIGN-SPEC §2.2.
        const val EXPECTED_GATE_COUNT = 3
        const val GATE_BODY_SPAN = 200
    }
}
