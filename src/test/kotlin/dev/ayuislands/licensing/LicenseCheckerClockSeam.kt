package dev.ayuislands.licensing

import java.time.LocalDate

/**
 * Scoped override for the [LicenseChecker] time seams.
 *
 * [LicenseChecker.nowMsSupplier] and [LicenseChecker.todayUtcSupplier] are object-scoped
 * `var`s — convenient for test determinism, dangerous if a test forgets to restore them.
 * This helper centralizes the pin/restore dance so every time-sensitive test uses the
 * same idiom, and the production defaults are re-installed from exactly one place.
 *
 * **Snapshot at class init, not hardcoded.** The first time this object is touched it
 * captures whatever suppliers [LicenseChecker] currently holds — typically the production
 * defaults (`System::currentTimeMillis`, `LocalDate.now(UTC)`). [restore] hands those
 * original references back, so the helper stays correct even if the production defaults
 * ever change inside [LicenseChecker]. The capture happens before any test can pin a
 * supplier because Kotlin `object` initialization is eager on first access.
 *
 * Typical usage in per-class test state:
 * ```
 * @BeforeTest fun setUp() {
 *     LicenseCheckerClockSeam.pinNow { fakeNowMs }
 *     LicenseCheckerClockSeam.pinToday { fakeTodayUtc }
 * }
 *
 * @AfterTest fun tearDown() {
 *     LicenseCheckerClockSeam.restore()
 * }
 * ```
 *
 * For one-off boundary cases that should not leak their pinned clock to sibling tests:
 * ```
 * LicenseCheckerClockSeam.withFixedNow(fixedNowMs) {
 *     assertFalse(LicenseChecker.isLicensedOrGrace())
 * }
 * ```
 *
 * [withFixedNow] captures the currently-installed supplier before pinning and reinstalls
 * that exact reference in `finally`, so a setUp-level pin survives an inner boundary
 * test's block without being hard-reset to the production default.
 */
internal object LicenseCheckerClockSeam {
    private val originalNowMsSupplier: () -> Long = LicenseChecker.nowMsSupplier
    private val originalTodayUtcSupplier: () -> LocalDate = LicenseChecker.todayUtcSupplier

    /** Pin the millisecond clock to a constant value. */
    fun pinNow(fixedNowMs: Long) {
        LicenseChecker.nowMsSupplier = { fixedNowMs }
    }

    /** Pin the millisecond clock to a dynamic supplier (e.g. a `var` that tests advance). */
    fun pinNow(supplier: () -> Long) {
        LicenseChecker.nowMsSupplier = supplier
    }

    /** Pin the UTC "today" date to a constant value. */
    fun pinToday(fixedToday: LocalDate) {
        LicenseChecker.todayUtcSupplier = { fixedToday }
    }

    /** Pin the UTC "today" date to a dynamic supplier. */
    fun pinToday(supplier: () -> LocalDate) {
        LicenseChecker.todayUtcSupplier = supplier
    }

    /**
     * Reset both seams to the suppliers that [LicenseChecker] held when this helper was
     * first touched. Typically the production defaults, but the snapshot keeps the helper
     * correct even if those defaults ever move. Safe to call multiple times.
     */
    fun restore() {
        LicenseChecker.nowMsSupplier = originalNowMsSupplier
        LicenseChecker.todayUtcSupplier = originalTodayUtcSupplier
    }

    /**
     * Pin [LicenseChecker.nowMsSupplier] to [fixedNowMs] for the duration of [block],
     * always restoring the *previously-installed* supplier afterwards — even on exception.
     *
     * Preserves a caller-level pin (e.g. a `@BeforeTest` pin that wraps a whole class)
     * so a nested boundary test can override the clock for a single assertion without
     * clobbering the outer override.
     */
    inline fun <R> withFixedNow(
        fixedNowMs: Long,
        block: () -> R,
    ): R {
        val previous = LicenseChecker.nowMsSupplier
        LicenseChecker.nowMsSupplier = { fixedNowMs }
        return try {
            block()
        } finally {
            LicenseChecker.nowMsSupplier = previous
        }
    }
}
