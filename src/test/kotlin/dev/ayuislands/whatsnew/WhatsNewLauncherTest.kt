package dev.ayuislands.whatsnew

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsNewLauncherTest {
    // Pure eligibility helper extracted from openIfEligible — exercises every
    // combination of the persistent gate and the manifest-presence probe so a
    // future refactor that flips the precedence (or drops one of the checks)
    // fails CI loudly.

    @Test
    fun `eligible when no record and manifest present`() {
        assertTrue(
            WhatsNewLauncher.isEligible(
                lastShownVersion = null,
                currentVersion = "2.5.0",
                manifestPresent = true,
            ),
        )
    }

    @Test
    fun `not eligible when version already shown - same string`() {
        // The persistent gate: once the tab opens for v2.5.0, lastShownVersion
        // gets written and subsequent IDE launches must skip the auto-trigger.
        assertFalse(
            WhatsNewLauncher.isEligible(
                lastShownVersion = "2.5.0",
                currentVersion = "2.5.0",
                manifestPresent = true,
            ),
        )
    }

    @Test
    fun `eligible when an older version was previously shown`() {
        // Upgrade path: lastShownVersion records 2.5.0; user upgrades to 2.6.0
        // which ships its own manifest. Auto-trigger must fire again — tracking
        // the last-shown version (not "ever shown") is the whole point of the
        // generalized state field.
        assertTrue(
            WhatsNewLauncher.isEligible(
                lastShownVersion = "2.5.0",
                currentVersion = "2.6.0",
                manifestPresent = true,
            ),
        )
    }

    @Test
    fun `not eligible when manifest absent regardless of state`() {
        // Patch releases like 2.5.1 typically don't ship a manifest. Auto-trigger
        // skips them silently and falls through to the existing balloon path.
        assertFalse(
            WhatsNewLauncher.isEligible(
                lastShownVersion = null,
                currentVersion = "2.5.1",
                manifestPresent = false,
            ),
        )
        assertFalse(
            WhatsNewLauncher.isEligible(
                lastShownVersion = "2.5.0",
                currentVersion = "2.5.1",
                manifestPresent = false,
            ),
        )
    }

    @Test
    fun `not eligible when manifest absent even for a never-shown version`() {
        // Defense-in-depth: the eligibility gate is "manifest exists AND not
        // already shown". Either gate failing means no tab. Splitting the test
        // from the prior one because this regression class (caller drops the
        // manifestPresent check thinking "any new version triggers") would
        // pass the prior tests if tightened to "no record" only.
        assertFalse(
            WhatsNewLauncher.isEligible(
                lastShownVersion = null,
                currentVersion = "3.0.0",
                manifestPresent = false,
            ),
        )
    }

    @Test
    fun `not eligible when stable was shown and dev sandbox runs the same version with -SNAPSHOT`() {
        // Dev sandbox path: maintainer ships 2.5.0 stable, user sees the tab,
        // state stores the normalized "2.5.0". Maintainer then runs ./gradlew
        // runIde — descriptor.version is "2.5.0-SNAPSHOT". Without normalization
        // the raw-string compare would re-trigger the tab, polluting dev runs
        // (and worse: writing back "2.5.0-SNAPSHOT" so the next stable launch
        // re-triggers AGAIN).
        assertFalse(
            WhatsNewLauncher.isEligible(
                lastShownVersion = "2.5.0",
                currentVersion = "2.5.0-SNAPSHOT",
                manifestPresent = true,
            ),
        )
    }

    @Test
    fun `not eligible when SNAPSHOT was shown and stable runs the same version`() {
        // Inverse of the above: dev sandbox showed the tab once with
        // "2.5.0-SNAPSHOT" stored, maintainer now runs the stable "2.5.0".
        // Without normalization, the stable launch would re-trigger.
        assertFalse(
            WhatsNewLauncher.isEligible(
                lastShownVersion = "2.5.0-SNAPSHOT",
                currentVersion = "2.5.0",
                manifestPresent = true,
            ),
        )
    }

    @Test
    fun `not eligible when SNAPSHOT was shown and the same SNAPSHOT runs again`() {
        // Plain identity case under SNAPSHOT — verifies the normalization
        // doesn't accidentally make every dev run eligible.
        assertFalse(
            WhatsNewLauncher.isEligible(
                lastShownVersion = "2.5.0-SNAPSHOT",
                currentVersion = "2.5.0-SNAPSHOT",
                manifestPresent = true,
            ),
        )
    }

    @Test
    fun `eligible when older SNAPSHOT shown and newer stable rolls out`() {
        // Maintainer dev'd against 2.5.0-SNAPSHOT, then user upgrades to
        // 2.6.0 which ships its own manifest — different release, must trigger.
        assertTrue(
            WhatsNewLauncher.isEligible(
                lastShownVersion = "2.5.0-SNAPSHOT",
                currentVersion = "2.6.0",
                manifestPresent = true,
            ),
        )
    }
}
