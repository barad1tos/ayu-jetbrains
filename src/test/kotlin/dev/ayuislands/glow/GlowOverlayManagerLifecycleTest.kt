package dev.ayuislands.glow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-02 + D-03 lifecycle integration coverage for [GlowOverlayManager].
 *
 * Pre-40.1: `updateGlow()` painted overlays even when no Ayu variant was active,
 * relying on three `else DEFAULT_ACCENT_HEX` fallbacks at :214 / :254 / :401.
 * Post-40.1: a single `if (!AyuVariant.isAyuActive()) { removeAllOverlays(); return }`
 * guard at the head of `updateGlow()` disposes overlays when the user switches to a
 * non-Ayu LAF — and the three fallbacks go away (regression-locked by
 * [GlowFallbackBannedApiGuardTest]).
 *
 * These tests reference `AyuVariant.isAyuActive()` directly. The symbol does NOT
 * exist in production until Wave 1 plan 01 lands the helper; until then, this file
 * fails to compile. That IS the red state — the inverted-gate verification in
 * Task 5 of plan 40.1-00 asserts an `unresolved reference: isAyuActive` error
 * shows up in `compileTestKotlin`.
 */
class GlowOverlayManagerLifecycleTest {
    private val mockApplication = mockk<com.intellij.openapi.application.Application>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val mockProjectManager = mockk<ProjectManager>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state
        state.glowEnabled = true

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any()) } returns "#5CCFE6"

        mockkObject(AyuVariant.Companion)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `updateGlow disposes overlays when AyuVariant isAyuActive is false`() {
        // Bug A: user switches from Ayu to Darcula. Pre-40.1, `updateGlow` walked
        // the `else DEFAULT_ACCENT_HEX` fallback and kept the orange glow painting
        // on top of Darcula's blue chrome. Post-40.1, the new guard at the head
        // disposes every overlay so the screen is left in the LAF's natural state.
        //
        // C-4 strengthening (review-loop): seed the overlay map with explicit
        // glassPane/host/layeredPane mocks so we can assert detachOverlayEntry's
        // expected side-effects fire — stopAnimation on the glassPane,
        // remove + repaint on the layeredPane. Map empty stays as the sanity
        // net but is no longer the only signal.
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project = stubProject("test-project")
        val manager = GlowOverlayManager(project)

        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        val host = mockk<javax.swing.JComponent>(relaxed = true)
        val layeredPane = mockk<javax.swing.JLayeredPane>(relaxed = true)
        seedOverlaysMapWithMocks(manager, "DISPOSAL_TARGET", glassPane, host, layeredPane)

        manager.updateGlow()

        val overlaysAfter = readOverlaysMap(manager)
        assertTrue(
            overlaysAfter.isEmpty(),
            "updateGlow with isAyuActive=false MUST leave overlays map empty (D-02 disposal contract)",
        )
        // C-4: detachOverlayEntry side-effects must fire on each entry —
        // proves the disposal path actually walked the map rather than
        // just clearing it.
        verify { glassPane.stopAnimation() }
        verify { layeredPane.remove(glassPane) }
        verify { layeredPane.repaint(any<Int>(), any<Int>(), any<Int>(), any<Int>()) }
    }

    @Test
    fun `updateGlow continues to paint when AyuVariant isAyuActive is true`() {
        // Sanity: when the user IS on Ayu, the guard does NOT short-circuit and
        // the rest of the method runs. We only assert the guard didn't dispose —
        // overlay attachment requires a live Swing tree which is out of scope
        // for this unit test.
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        val project = stubProject("test-project")
        val manager = GlowOverlayManager(project)

        // Seed the overlays map with a sentinel so we can prove the guard did
        // NOT dispose. If updateGlow walked the disposal path, the sentinel
        // would be cleared.
        val sentinelKey = "GUARD_SENTINEL"
        seedOverlaysMap(manager, sentinelKey)

        manager.updateGlow()

        val overlaysAfter = readOverlaysMap(manager)
        assertFalse(
            overlaysAfter.isEmpty() && !overlaysAfter.containsKey(sentinelKey),
            "updateGlow with isAyuActive=true MUST NOT dispose pre-existing overlays",
        )
    }

    @Test
    fun `syncGlowForAllProjects disposes every project glow when variant becomes null`() {
        // D-03: when AyuIslandsLafListener detects a non-Ayu LAF, it calls
        // GlowOverlayManager.syncGlowForAllProjects() which iterates every open
        // project and triggers per-project disposal via the new guard. This test
        // pins the multi-project dispatch — without it, only the focused
        // project's overlay would be disposed.
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project1 = stubProject("project-1")
        val project2 = stubProject("project-2")
        every { mockProjectManager.openProjects } returns arrayOf(project1, project2)

        val manager1 = GlowOverlayManager(project1)
        val manager2 = GlowOverlayManager(project2)
        seedOverlaysMap(manager1, "p1-sentinel")
        seedOverlaysMap(manager2, "p2-sentinel")

        // Stub project.getService(GlowOverlayManager::class.java) so the
        // companion's `getInstance(project)` lookup returns our seeded managers
        // instead of trying to spin up a real ProjectComponent.
        every { project1.getService(GlowOverlayManager::class.java) } returns manager1
        every { project2.getService(GlowOverlayManager::class.java) } returns manager2

        GlowOverlayManager.syncGlowForAllProjects()

        assertTrue(
            readOverlaysMap(manager1).isEmpty(),
            "syncGlowForAllProjects MUST dispose overlays for project1 when variant null (D-03)",
        )
        assertTrue(
            readOverlaysMap(manager2).isEmpty(),
            "syncGlowForAllProjects MUST dispose overlays for project2 when variant null (D-03)",
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stubProject(name: String): Project =
        mockk(relaxed = true) {
            every { isDisposed } returns false
            every { isDefault } returns false
            every { this@mockk.name } returns name
        }

    /**
     * Reads the private `overlays` MutableMap from a [GlowOverlayManager] instance
     * via reflection. The map is the disposal contract's observable surface — an
     * empty map after `updateGlow()` proves the guard fired. Star projection
     * `Map<*, *>` keeps the cast checked-by-Kotlin (no UNCHECKED_CAST warning)
     * since we only need `isEmpty()` and `containsKey(...)` from the read side.
     */
    private fun readOverlaysMap(manager: GlowOverlayManager): Map<*, *> {
        val field = GlowOverlayManager::class.java.getDeclaredField("overlays")
        field.isAccessible = true
        return field.get(manager) as Map<*, *>
    }

    /**
     * Inserts a sentinel entry into the private `overlays` map so the disposal
     * test can prove the map was cleared. The map's value type is the private
     * `OverlayEntry` data class. Plan 40.1-01 added a `removeAllOverlays()`
     * disposal path inside `updateGlow()`'s new guard, AND `updateOverlayStyles`
     * iterates the same map on the non-disposal branch — both destructure the
     * value as `OverlayEntry` (`for ((_, entry) in overlays)`), so seeding a
     * non-`OverlayEntry` value triggers a `ClassCastException` at runtime.
     *
     * We construct a real `OverlayEntry` via its synthetic data-class
     * constructor (the class is `private`, so we reach it through
     * `getDeclaredConstructors`) with mockk-relaxed Swing peers. The disposal
     * path's `detachOverlayEntry(entry)` calls `glassPane.stopAnimation()`,
     * `host.removeComponentListener(...)`, `layeredPane.remove(...)`, and
     * `layeredPane.repaint(...)` — all no-ops against relaxed mocks. The
     * non-disposal path's `updateOverlayStyles` only assigns properties on the
     * mocked `glassPane`, also a no-op.
     */
    private fun seedOverlaysMap(
        manager: GlowOverlayManager,
        key: String,
    ) {
        val field = GlowOverlayManager::class.java.getDeclaredField("overlays")
        field.isAccessible = true
        val map = field.get(manager) as Map<*, *>
        val putMethod =
            java.util.Map::class.java.getDeclaredMethod(
                "put",
                Any::class.java,
                Any::class.java,
            )
        putMethod.invoke(map, key, makeOverlayEntry())
    }

    /**
     * C-4 strengthening: seed the overlays map with EXPLICIT mocks (rather
     * than the relaxed-mock anonymous trio inside [makeOverlayEntry]) so
     * tests can verify detachOverlayEntry's side-effects against the same
     * mock instances they passed in. The non-disposal-path
     * `updateOverlayStyles` iteration just assigns properties on the
     * glassPane mock, which relaxed-mock no-ops.
     */
    private fun seedOverlaysMapWithMocks(
        manager: GlowOverlayManager,
        key: String,
        glassPane: GlowGlassPane,
        host: javax.swing.JComponent,
        layeredPane: javax.swing.JLayeredPane,
    ) {
        val field = GlowOverlayManager::class.java.getDeclaredField("overlays")
        field.isAccessible = true
        val map = field.get(manager) as Map<*, *>
        val putMethod =
            java.util.Map::class.java.getDeclaredMethod(
                "put",
                Any::class.java,
                Any::class.java,
            )
        putMethod.invoke(map, key, makeOverlayEntryWith(glassPane, host, layeredPane))
    }

    private fun makeOverlayEntryWith(
        glassPane: GlowGlassPane,
        host: javax.swing.JComponent,
        layeredPane: javax.swing.JLayeredPane,
    ): Any {
        val entryClass =
            GlowOverlayManager::class.java.declaredClasses
                .first { it.simpleName == "OverlayEntry" }
        val ctor =
            entryClass.declaredConstructors
                .first { it.parameterCount == OVERLAY_ENTRY_PRIMARY_CTOR_ARITY }
        ctor.isAccessible = true
        return ctor.newInstance(glassPane, host, layeredPane, null, null)
    }

    /**
     * Builds a real `GlowOverlayManager$OverlayEntry` via reflection. The
     * primary data-class constructor takes 5 args (glassPane, host,
     * layeredPane, componentListener, hierarchyBoundsListener). Kotlin also
     * generates a synthetic default-argument constructor with extra
     * (Int $mask, DefaultConstructorMarker) trailing slots — we pin to the
     * exact 5-param ctor so the synthetic one is never picked. All five are
     * mocked so `detachOverlayEntry` / `updateOverlayStyles` see well-typed
     * objects but every call routes to a relaxed-mock no-op.
     */
    private fun makeOverlayEntry(): Any {
        val entryClass =
            GlowOverlayManager::class.java.declaredClasses
                .first { it.simpleName == "OverlayEntry" }
        val ctor =
            entryClass.declaredConstructors
                .first { it.parameterCount == OVERLAY_ENTRY_PRIMARY_CTOR_ARITY }
        ctor.isAccessible = true
        return ctor.newInstance(
            mockk<GlowGlassPane>(relaxed = true),
            mockk<javax.swing.JComponent>(relaxed = true),
            mockk<javax.swing.JLayeredPane>(relaxed = true),
            // Nullable componentListener / hierarchyBoundsListener — null
            // matches the editor-overlay production branch and exercises the
            // `?.let { ... }` guards inside detachOverlayEntry.
            null,
            null,
        )
    }

    private companion object {
        /** Primary data-class ctor of OverlayEntry: 5 declared parameters. */
        private const val OVERLAY_ENTRY_PRIMARY_CTOR_ARITY = 5
    }
}
