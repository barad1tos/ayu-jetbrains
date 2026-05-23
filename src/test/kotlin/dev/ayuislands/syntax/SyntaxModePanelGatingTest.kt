package dev.ayuislands.syntax

import dev.ayuislands.settings.AyuIslandsSyntaxPanel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 49 Plan 49-04 — free-user gating regression for [AyuIslandsSyntaxPanel]
 * (SYNTAX-08, D-01).
 *
 * Pattern L source-regex regression lock: no `LicenseChecker` reference may
 * appear in the panel source. This is the cross-check for the same invariant
 * already locked by [dev.ayuislands.settings.AyuIslandsSyntaxPanelTest] — keep
 * both so accidental introduction surfaces from either subsystem-level test.
 *
 * Also verifies the apply path runs identically for free / Pro users — there
 * is only one code path, no gating branch.
 */
class SyntaxModePanelGatingTest {
    private lateinit var stateBase: SyntaxModeBaseState
    private lateinit var stateService: SyntaxModeState
    private lateinit var modeService: SyntaxModeService

    @BeforeTest
    fun setUp() {
        stateBase = SyntaxModeBaseState()
        stateService = mockk(relaxed = true)
        every { stateService.state } returns stateBase
        mockkObject(SyntaxModeState.Companion)
        every { SyntaxModeState.getInstance() } returns stateService

        modeService = mockk(relaxed = true)
        mockkObject(SyntaxModeService.Companion)
        every { SyntaxModeService.getInstance() } returns modeService
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ----- Pattern L source-regex regression locks -----

    @Test
    fun `AyuIslandsSyntaxPanel source contains no LicenseChecker reference (SYNTAX-08)`() {
        val source = readPanelSource()
        assertFalse(
            source.contains("LicenseChecker"),
            "AyuIslandsSyntaxPanel must NOT reference LicenseChecker (D-01 free feature). " +
                "Any introduction of a license check here is a SYNTAX-08 regression.",
        )
    }

    @Test
    fun `no syntax-package source file references LicenseChecker (SYNTAX-08 cross-check)`() {
        val syntaxDir = Path.of("src/main/kotlin/dev/ayuislands/syntax")
        val offenders =
            Files
                .walk(syntaxDir)
                .filter { it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains("LicenseChecker") }
                .toList()
        assertTrue(
            offenders.isEmpty(),
            "syntax-package sources must NOT reference LicenseChecker (D-01 / SYNTAX-08). " +
                "Offenders: $offenders",
        )
    }

    // ----- behavioral verification -----

    @Test
    fun `apply routes through SyntaxModeService regardless of license state`() {
        // The panel apply path does not branch on license — verify by calling
        // apply twice through the public API surface and observing the service
        // gets the call both times.
        val panel = AyuIslandsSyntaxPanel()

        // Use reflection to flip the dirty-buffer guard so apply does work.
        val moodField = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingMood")
        moodField.isAccessible = true
        moodField.set(panel, SyntaxMood.RICH)

        // First apply — would correspond to a free user pressing OK.
        panel.apply()
        verify(exactly = 1) {
            modeService.apply(SyntaxMood.RICH, emptySet())
        }

        // Reset the pending mood so isModified flips back to true.
        moodField.set(panel, SyntaxMood.STANDARD)

        // Second apply — would correspond to a Pro user pressing OK. Same path.
        panel.apply()
        verify(exactly = 1) {
            modeService.apply(SyntaxMood.STANDARD, emptySet())
        }
    }

    @Test
    fun `panel construction succeeds without any license state setup (free-user smoke test)`() {
        // Construction must NOT crash because LicenseChecker / LicensingFacade is
        // mocked-away — there are no calls into the licensing subsystem from the
        // syntax panel (D-01). This is a positive smoke test for the
        // source-regex locks above.
        val panel = AyuIslandsSyntaxPanel()
        assertEquals(false, panel.isModified(), "fresh panel must not be modified")
    }

    private fun readPanelSource(): String =
        Files.readString(
            Path.of("src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt"),
        )
}
