package dev.ayuislands.licensing

import java.time.LocalDate
import java.time.ZoneId

/**
 * Scoped override for the [LicenseChecker] time seams.
 *
 * [LicenseChecker.nowMsSupplier] and [LicenseChecker.todayUtcSupplier] are object-scoped
 * `var`s — convenient for test determinism, dangerous if a test forgets to restore them.
 * This helper centralizes the pin/restore dance so every time-sensitive test uses the
 * same idiom, and the production defaults are re-installed from exactly one place.
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
 */
internal object LicenseCheckerClockSeam {
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

    /** Reset both seams to their production defaults. Safe to call multiple times. */
    fun restore() {
        LicenseChecker.nowMsSupplier = System::currentTimeMillis
        LicenseChecker.todayUtcSupplier = { LocalDate.now(ZoneId.of("UTC")) }
    }

    /**
     * Pin [LicenseChecker.nowMsSupplier] to [fixedNowMs] for the duration of [block],
     * always restoring the default afterwards — even on exception.
     */
    inline fun <R> withFixedNow(
        fixedNowMs: Long,
        block: () -> R,
    ): R {
        pinNow(fixedNowMs)
        return try {
            block()
        } finally {
            restore()
        }
    }
}
