package dev.ayuislands.glow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AccentChangeListener
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.awt.Point
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lifecycle integration coverage for [GlowOverlayManager].
 *
 * Historically `updateGlow()` painted overlays even when no Ayu variant was
 * active, relying on three `else DEFAULT_ACCENT_HEX` fallbacks. The current
 * shape pulls that into a single [AccentContext.detect] guard at the head of
 * `updateGlow()` — overlays get disposed when no accent context is active, and
 * the three fallbacks are gone (regression-locked by [GlowFallbackBannedApiGuardTest]).
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
        state.externalThemeEnhancementsEnabled = false
        state.lastAppliedAccentHex = null
        state.lastApplyOk = false

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#5CCFE6"
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#5CCFE6"

        mockkObject(AyuVariant.Companion)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `updateGlow disposes overlays when AyuVariant isAyuActive is false`() {
        // User switches from Ayu to Darcula. Historically `updateGlow` walked
        // the `else DEFAULT_ACCENT_HEX` fallback and kept the orange glow
        // painting on top of Darcula's blue chrome. The guard at the head
        // disposes every overlay so the screen is left in the LAF's natural
        // state.
        //
        // Seed the overlay map with explicit glassPane/host/layeredPane mocks
        // so we can assert `detachOverlayEntry`'s expected side-effects fire —
        // `stopAnimation` on the glassPane, `remove` + `repaint` on the
        // layeredPane. Map-empty stays as the sanity net but is no longer the
        // only signal.
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project = stubProject("test-project")
        val manager = GlowOverlayManager(project)

        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        val host = mockk<javax.swing.JComponent>(relaxed = true)
        val layeredPane = mockk<javax.swing.JLayeredPane>(relaxed = true)
        seedOverlaysMapWithMocks(manager, glassPane, host, layeredPane)

        manager.updateGlow()

        val overlaysAfter = readOverlaysMap(manager)
        assertTrue(
            overlaysAfter.isEmpty(),
            "updateGlow with isAyuActive=false MUST leave overlays map empty (disposal contract)",
        )
        // `detachOverlayEntry` side-effects must fire on each entry — proves
        // the disposal path actually walked the map rather than just clearing
        // it.
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
    fun `updateGlow continues to paint when external context is active`() {
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeGlowEnabled = true
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project = stubProject("external-theme-project")
        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        every { AccentResolver.resolve(project, AccentContext.External) } returns "#AABBCC"

        manager.updateGlow()

        assertFalse(
            readOverlaysMap(manager).isEmpty(),
            "updateGlow with external accent context MUST NOT dispose pre-existing overlays",
        )
        verify(exactly = 1) { AccentResolver.resolve(project, AccentContext.External) }
        verify(exactly = 1) { glassPane.glowColor = Color.decode("#AABBCC") }
    }

    @Test
    fun `updateGlow disposes external overlays when external glow inheritance is disabled`() {
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeGlowEnabled = false
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project = stubProject("external-theme-project")
        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        manager.updateGlow()

        assertTrue(
            readOverlaysMap(manager).isEmpty(),
            "External Glow permission OFF must dispose overlays instead of painting inherited glow",
        )
        verify(exactly = 0) { AccentResolver.resolve(project, AccentContext.External) }
        verify(exactly = 0) { glassPane.glowColor = any() }
    }

    @Test
    fun `updateGlow paints clean last applied accent instead of project resolver`() {
        // Glow follows the app-global chrome color that was actually painted.
        // A background project may resolve to a different override, but its
        // status bar was already repainted with the last clean apply payload.
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        state.lastAppliedAccentHex = "#5CCFE6"
        state.lastApplyOk = true

        val project = stubProject("background-project")
        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        every { AccentResolver.resolve(project, AccentContext.Ayu(AyuVariant.MIRAGE)) } returns "#FFCC66"

        manager.updateGlow()

        verify(exactly = 1) { glassPane.glowColor = Color.decode("#5CCFE6") }
        verify(exactly = 0) { glassPane.glowColor = Color.decode("#FFCC66") }
        verify(exactly = 0) { AccentResolver.resolve(project, AccentContext.Ayu(AyuVariant.MIRAGE)) }
    }

    @Test
    fun `attachOverlay seeds clean last applied accent instead of project resolver`() {
        // Late-created overlays must match the already-painted status bar even
        // if this project's resolver would choose a different override. This is
        // the startup/late-attach path that does not receive a fresh topic
        // payload after the overlay exists.
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        state.lastAppliedAccentHex = "#5CCFE6"
        state.lastApplyOk = true

        val project = stubProject("late-overlay-project")
        val manager = GlowOverlayManager(project)
        val host = mockk<javax.swing.JComponent>(relaxed = true)
        val rootPane = mockk<javax.swing.JRootPane>(relaxed = true)
        val layeredPane = mockk<javax.swing.JLayeredPane>(relaxed = true)
        every { host.width } returns 120
        every { host.height } returns 80
        every { host.isShowing } returns true
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(0, 0)
        every { AccentResolver.resolve(project, AccentContext.Ayu(AyuVariant.MIRAGE)) } returns "#FFCC66"

        invokeAttachOverlay(manager, "LateOverlay", host)

        assertEquals(
            Color.decode("#5CCFE6"),
            readOverlayGlassPane(manager, "LateOverlay").glowColor,
            "new overlays must seed from the clean app-global applied accent, not the project resolver",
        )
        verify(exactly = 0) { AccentResolver.resolve(project, AccentContext.Ayu(AyuVariant.MIRAGE)) }
    }

    @Test
    fun `AccentChangedTopic event uses applied accent payload for matching project`() {
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs

        val project = stubProject("focused-project")
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val accentListenerSlot = slot<AccentChangeListener>()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every {
            connection.subscribe(eq(AccentChangedTopic.TOPIC), capture(accentListenerSlot))
        } just Runs

        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"

        manager.initialize()

        assertTrue(
            accentListenerSlot.isCaptured,
            "GlowOverlayManager must subscribe to AccentChangedTopic so chrome-only accent refreshes recolor glow",
        )

        accentListenerSlot.captured.accentChanged(
            project,
            AccentHex.unsafeOf("#5CCFE6"),
            AccentResolver.Source.GLOBAL,
        )

        verify(exactly = 1) { glassPane.glowColor = Color.decode("#5CCFE6") }
        verify(exactly = 0) { glassPane.glowColor = Color.decode("#FFCC66") }
    }

    @Test
    fun `AccentChangedTopic event ignores a different project`() {
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs

        val project = stubProject("focused-project")
        val otherProject = stubProject("other-project")
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val accentListenerSlot = slot<AccentChangeListener>()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every {
            connection.subscribe(eq(AccentChangedTopic.TOPIC), capture(accentListenerSlot))
        } just Runs

        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        manager.initialize()

        assertTrue(
            accentListenerSlot.isCaptured,
            "GlowOverlayManager must subscribe before it can filter project-scoped accent events",
        )

        accentListenerSlot.captured.accentChanged(
            otherProject,
            AccentHex.unsafeOf("#D95757"),
            AccentResolver.Source.PROJECT_OVERRIDE,
        )

        verify(exactly = 0) { glassPane.glowColor = any<Color>() }
    }

    @Test
    fun `AccentChangedTopic event reschedules glow update when fired off EDT`() {
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs

        val project = stubProject("focused-project")
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val accentListenerSlot = slot<AccentChangeListener>()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every {
            connection.subscribe(eq(AccentChangedTopic.TOPIC), capture(accentListenerSlot))
        } just Runs

        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"

        manager.initialize()

        assertTrue(
            accentListenerSlot.isCaptured,
            "GlowOverlayManager must subscribe before it can reschedule off-EDT accent events",
        )

        val scheduled = mutableListOf<Runnable>()
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { SwingUtilities.invokeLater(any()) } answers {
            scheduled.add(firstArg<Runnable>())
        }

        accentListenerSlot.captured.accentChanged(
            project,
            AccentHex.unsafeOf("#5CCFE6"),
            AccentResolver.Source.GLOBAL,
        )

        verify(exactly = 0) { glassPane.glowColor = any<Color>() }

        scheduled.forEach { it.run() }

        verify(exactly = 1) { glassPane.glowColor = Color.decode("#5CCFE6") }
        verify(exactly = 0) { glassPane.glowColor = Color.decode("#FFCC66") }
    }

    @Test
    fun `syncGlowForAllProjects continues to second project when first project updateGlow throws`() {
        // Regression lock for the companion-level RuntimeException catch.
        // `syncGlowForAllProjects` iterates every open project; one project
        // whose `updateGlow` throws (e.g. mid-dispose race) MUST NOT block the
        // other projects from being disposed. Pattern B isolation — narrow
        // `RuntimeException` catch, log warning, continue.
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project1 = stubProject("project-1")
        val project2 = stubProject("project-2")
        every { mockProjectManager.openProjects } returns arrayOf(project1, project2)

        val manager1 = mockk<GlowOverlayManager>(relaxed = true)
        val manager2 = GlowOverlayManager(project2)
        seedOverlaysMap(manager2, "p2-sentinel")

        // Project 1's updateGlow throws — must not bubble out of the loop.
        every { manager1.updateGlow() } throws RuntimeException("project-1 glow exploded")

        every { project1.getService(GlowOverlayManager::class.java) } returns manager1
        every { project2.getService(GlowOverlayManager::class.java) } returns manager2

        GlowOverlayManager.syncGlowForAllProjects() // MUST NOT throw

        // Project 2's overlays still cleared — proves the loop continued
        // past project 1's failure rather than aborting on the first throw.
        assertTrue(
            readOverlaysMap(manager2).isEmpty(),
            "syncGlowForAllProjects MUST continue to project 2 after project 1 throws " +
                "(isolation lock — Pattern B)",
        )
        verify(exactly = 1) { manager1.updateGlow() }
    }

    @Test
    fun `syncGlowForAllProjects disposes every project glow when variant becomes null`() {
        // When `AyuIslandsLafListener` detects a non-Ayu LAF, it calls
        // `GlowOverlayManager.syncGlowForAllProjects()` which iterates every
        // open project and triggers per-project disposal via the guard. This
        // test pins the multi-project dispatch — without it, only the focused
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
            "syncGlowForAllProjects MUST dispose overlays for project1 when variant null",
        )
        assertTrue(
            readOverlaysMap(manager2).isEmpty(),
            "syncGlowForAllProjects MUST dispose overlays for project2 when variant null",
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

    private fun readOverlayGlassPane(
        manager: GlowOverlayManager,
        key: String,
    ): GlowGlassPane {
        val entry = readOverlaysMap(manager)[key] ?: error("Overlay '$key' was not attached")
        val field = entry.javaClass.getDeclaredField("glassPane")
        field.isAccessible = true
        return field.get(entry) as GlowGlassPane
    }

    private fun invokeAttachOverlay(
        manager: GlowOverlayManager,
        id: String,
        host: javax.swing.JComponent,
    ) {
        val method =
            GlowOverlayManager::class.java.getDeclaredMethod(
                "attachOverlay",
                String::class.java,
                javax.swing.JComponent::class.java,
                Boolean::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(manager, id, host, false)
    }

    /**
     * Inserts a sentinel entry into the private `overlays` map so the disposal
     * test can prove the map was cleared. The map's value type is the private
     * `OverlayEntry` data class. A `removeAllOverlays()` disposal path runs
     * inside `updateGlow()`'s guard, AND `updateOverlayStyles` iterates the
     * same map on the non-disposal branch — both destructure the value as
     * `OverlayEntry` (`for ((_, entry) in overlays)`), so seeding a
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
        // `MutableMap::class.java` resolves to the same JVM `java.util.Map`
        // class but lets Kotlin's IDE tooling treat the lookup as
        // collection-language-canonical rather than a bare java.* reference.
        val putMethod =
            MutableMap::class.java.getDeclaredMethod(
                "put",
                Any::class.java,
                Any::class.java,
            )
        putMethod.invoke(map, key, makeOverlayEntry())
    }

    /**
     * Seed the overlays map with EXPLICIT mocks (rather than the relaxed-mock
     * anonymous trio inside [makeOverlayEntry]) so tests can verify
     * `detachOverlayEntry`'s side-effects against the same mock instances they
     * passed in. The non-disposal-path `updateOverlayStyles` iteration just
     * assigns properties on the glassPane mock, which relaxed-mock no-ops.
     *
     * Always seeds under the [DISPOSAL_TARGET_KEY] sentinel — the only
     * caller is the disposal-path test, which doesn't need to vary the key.
     */
    private fun seedOverlaysMapWithMocks(
        manager: GlowOverlayManager,
        glassPane: GlowGlassPane,
        host: javax.swing.JComponent,
        layeredPane: javax.swing.JLayeredPane,
    ) {
        val field = GlowOverlayManager::class.java.getDeclaredField("overlays")
        field.isAccessible = true
        val map = field.get(manager) as Map<*, *>
        // `MutableMap::class.java` resolves to the same JVM `java.util.Map`
        // class but lets Kotlin's IDE tooling treat the lookup as
        // collection-language-canonical rather than a bare java.* reference.
        val putMethod =
            MutableMap::class.java.getDeclaredMethod(
                "put",
                Any::class.java,
                Any::class.java,
            )
        putMethod.invoke(map, DISPOSAL_TARGET_KEY, makeOverlayEntryWith(glassPane, host, layeredPane))
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

        /** Sentinel key for the disposal-path test seed. */
        private const val DISPOSAL_TARGET_KEY = "DISPOSAL_TARGET"
    }
}
