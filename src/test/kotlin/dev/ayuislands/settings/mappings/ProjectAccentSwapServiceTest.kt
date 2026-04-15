package dev.ayuislands.settings.mappings

import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Non-AWT behavior of [ProjectAccentSwapService]. The AWTEventListener inside `install()` is
 * covered by manual smoke / integration. These tests exercise only what is unit-testable:
 *  - [ProjectAccentSwapService.notifyExternalApply] is defined and non-throwing for the hex
 *    shapes real callers pass, so downstream code (settings panel, rotation, LAF listener)
 *    can rely on the public API being stable.
 *
 * `install()` and `handleWindowActivated` are deliberately *not* unit-tested here: `install`
 * registers a real AWTEventListener against `Toolkit.getDefaultToolkit()` which leaks across
 * headless test runs, and `handleWindowActivated` is `private` + AWT-event-driven. The prior
 * "install is idempotent" and "getInstance companion" tests asserted tautologies (no-throw
 * + reference equality of `Companion` to itself) and gave false coverage signal — removed.
 */
class ProjectAccentSwapServiceTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `notifyExternalApply is non-throwing for the hex shapes callers pass`() {
        // Real callers pass: valid hex after rotation, different hex after settings Apply, and
        // the blank "follow system accent" sentinel. If any of these shapes starts throwing
        // (e.g. a future null-check on a blank argument), the downstream swap-cache semantics
        // break and every focus-swap would reapply even when hex is unchanged — this test
        // catches that at the smallest unit level.
        val service = ProjectAccentSwapService()

        service.notifyExternalApply("#FFCC66")
        service.notifyExternalApply("#ABCDEF")
        service.notifyExternalApply("")
    }
}
