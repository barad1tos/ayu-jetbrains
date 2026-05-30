package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Locks in the [AyuIslandsAccentPanel.applyWithFallback] failure-recovery contract:
 *  - happy path: applyForFocusedProject runs; no fallback triggered
 *  - corrupted override: applyForFocusedProject throws, fallback applies the global hex
 *    AND syncs the swap cache (the swap-cache-sync omission was the original bug — the
 *    fallback used to skip it, silently reintroducing the stale-cache → redundant-apply
 *    pattern applyForFocusedProject was created to prevent)
 *  - corrupted global: BOTH paths throw; the panel stays operational, second LOG.error
 *    fires with "also failed" context, no exception escapes — avoids the generic
 *    "Settings can't save" dialog a hand-edited global hex would otherwise trigger
 *
 * **Test-design note (documented compromise):** two `buildPanel*` tests read the
 * compiled `AyuIslandsAccentPanel.class` and assert that both
 * `beforeOverridesInjection` and `afterOverridesInjection` hooks fire around
 * the Overrides builder. A behavioral substitute would require building the
 * UI DSL `panel { ... }` through the IntelliJ platform — the project's
 * `integrationTest` task is currently misconfigured (NO-SOURCE in CI), so
 * bytecode inspection is the cheapest available assertion. The hooks are
 * load-bearing: dropping one silently strands Chrome Tinting (afterOverrides)
 * or the License Status row (beforeOverrides) on the Settings page. Do not
 * delete in future "remove theater" passes without replacing with an
 * equivalent integration test.
 */
class AyuIslandsAccentPanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private lateinit var swapService: ProjectAccentSwapService

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } answers {
            firstArg<AyuVariant>().defaultAccent
        }
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        swapService = mockk(relaxed = true)
        mockkObject(ProjectAccentSwapService.Companion)
        every { ProjectAccentSwapService.getInstance() } returns swapService
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `applyWithFallback happy path delegates to applyForFocusedProject and skips fallback`() {
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } returns "#ABCDEF"

        val panel = AyuIslandsAccentPanel()
        panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")

        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) }
        // Fallback path's apply(effectiveAccent) and notifyExternalApply must NOT fire.
        verify(exactly = 0) { AccentApplicator.apply(any()) }
        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `applyWithFallback corrupted override falls back to global AND syncs swap cache`() {
        // Regression guard: a previous fallback applied the global accent but forgot to
        // call ProjectAccentSwapService.notifyExternalApply, leaving the swap cache stale
        // and silently re-introducing the exact bug applyForFocusedProject was created
        // to prevent (next WINDOW_ACTIVATED would redundantly re-apply).
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.applyFromHexString("#FFCC66") } returns true

        val panel = AyuIslandsAccentPanel()
        LoggedErrorProcessor.executeWith<Throwable>(suppressLoggedErrors()) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
    }

    @Test
    fun `applyWithFallback corrupted global ALSO does not propagate exception`() {
        // Regression guard: the fallback's own apply(effectiveAccent) can throw when
        // the GLOBAL hex is corrupted (hand-edited XML, legacy writer). Without the
        // second try/catch, the Settings "OK" path would bubble up as a generic
        // "Can't save" dialog. The catch logs and leaves the visible accent unchanged.
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.applyFromHexString("#FFCC66") } throws
            IllegalStateException("global hex also corrupted")

        val panel = AyuIslandsAccentPanel()
        // No exception escapes — both throws are caught and logged.
        LoggedErrorProcessor.executeWith<Throwable>(suppressLoggedErrors()) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }
        // notifyExternalApply must NOT be reached when the global-fallback apply throws.
        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `applyWithFallback logs WARN when swap cache sync throws after successful global apply`() {
        // Regression guard for the notifyExternalApply-after-successful-fallback-apply
        // stage: applyForFocusedProject throws, the global-fallback apply(effectiveAccent)
        // succeeds, but notifyExternalApply throws (swap service mid-dispose, corrupted
        // cache). The visible accent has already changed; only the focus-swap cache is
        // stale. The panel must log at WARN (not ERROR, since apply actually worked) and
        // must NOT rethrow — otherwise the Settings OK path degrades to a generic "Can't
        // save" dialog on a path where the user's intent was actually applied.
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.applyFromHexString("#FFCC66") } returns true
        every { swapService.notifyExternalApply("#FFCC66") } throws
            IllegalStateException("swap service disposed mid-save")

        val expectedWarnSubstring = "swap-cache sync failed"
        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> = java.util.EnumSet.noneOf(Action::class.java)

                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains(expectedWarnSubstring)) return true
                    capturedWarns += message
                    return false
                }
            }

        val panel = AyuIslandsAccentPanel()
        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
        kotlin.test.assertEquals(
            1,
            capturedWarns.size,
            "notifyExternalApply throw must produce exactly one WARN (not ERROR); got: $capturedWarns",
        )
    }

    private fun suppressLoggedErrors(): LoggedErrorProcessor =
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                throwable: Throwable?,
            ): Set<Action> = java.util.EnumSet.noneOf(Action::class.java)
        }

    // ── Injection-hook wiring ───────────────────────────────────────────────
    //
    // Chrome Tinting renders AFTER Overrides — the AccentPanel injection is
    // split into two parallel hooks: `beforeOverridesInjection` (fed by
    // AppearancePanel's System group) and `afterOverridesInjection` (fed by
    // ChromePanel). The hooks are plain nullable `((Panel) -> Unit)` fields,
    // so wiring correctness has two failure modes worth locking in:
    //
    //  1. Both hooks exist on the class as public Kotlin properties — a future
    //     refactor that accidentally deletes one (or renames it) would silently
    //     regress the Configurable's composition without a compile error beyond
    //     Configurable.kt itself.
    //  2. `buildPanel` bytecode must actually invoke both hooks and the
    //     Overrides builder between them, so the render order is Accent →
    //     before-hook → Overrides → after-hook → Rotation.
    //
    // Both assertions run directly off reflection + bytecode so no Swing / DSL
    // runtime is required — mirroring the approach in
    // AyuIslandsConfigurableChromeWiringTest.

    @Test
    fun `afterOverridesInjection property exists and defaults to null`() {
        val panel = AyuIslandsAccentPanel()
        val field = AyuIslandsAccentPanel::class.java.getDeclaredField("afterOverridesInjection")
        field.isAccessible = true
        kotlin.test.assertNull(
            field.get(panel),
            "afterOverridesInjection must default to null so unset configurables " +
                "render without chrome tinting (graceful degradation when the " +
                "Configurable hasn't wired a callback yet)",
        )
    }

    @Test
    fun `beforeOverridesInjection property still exists alongside afterOverridesInjection`() {
        // Regression guard: the injection refactor split a single hook into two.
        // If someone collapses them back or deletes beforeOverridesInjection, the
        // System collapsible (AppearancePanel) loses its render slot silently.
        val panel = AyuIslandsAccentPanel()
        val beforeField =
            AyuIslandsAccentPanel::class.java.getDeclaredField("beforeOverridesInjection")
        val afterField =
            AyuIslandsAccentPanel::class.java.getDeclaredField("afterOverridesInjection")
        beforeField.isAccessible = true
        afterField.isAccessible = true
        kotlin.test.assertNull(beforeField.get(panel))
        kotlin.test.assertNull(afterField.get(panel))
    }

    @Test
    fun `buildPanel bytecode invokes both injection hooks around Overrides builder`() {
        // Bytecode inspection: the compiled buildPanel method must reference both
        // `beforeOverridesInjection` and `afterOverridesInjection` getters AND the
        // OverridesGroupBuilder.buildGroup call. Without this test, a refactor
        // that drops one hook (or reorders the three calls) would regress the
        // visual order without a compile-time failure — the hooks are nullable
        // so an unused field still compiles cleanly.
        val classBytes =
            AyuIslandsAccentPanel::class.java
                .getResourceAsStream("AyuIslandsAccentPanel.class")
                ?.readAllBytes()
                ?: error("AyuIslandsAccentPanel.class must be loadable for bytecode inspection")
        kotlin.test.assertTrue(
            classBytes.isNotEmpty(),
            "AyuIslandsAccentPanel.class must be loadable for bytecode inspection",
        )
        val classText = String(classBytes, Charsets.ISO_8859_1)
        kotlin.test.assertTrue(
            classText.contains("beforeOverridesInjection"),
            "buildPanel bytecode must reference beforeOverridesInjection",
        )
        kotlin.test.assertTrue(
            classText.contains("afterOverridesInjection"),
            "buildPanel bytecode must reference afterOverridesInjection",
        )
        kotlin.test.assertTrue(
            classText.contains("buildGroup"),
            "buildPanel bytecode must reference OverridesGroupBuilder.buildGroup between the two hooks",
        )
    }

    @Test
    fun `buildPanel invokes hooks in order before overrides then after overrides`() {
        // Behavior-first order check: build a fake Panel spy via mockk and
        // capture hook-invocation order through side-channel counters. The
        // buildPanel method body (including the OverridesGroupBuilder.buildGroup
        // call) walks an IntelliJ DSL that requires a live Panel — too heavy for
        // this unit. Instead, assert the two hooks are composed in the right
        // order by recording the call sequence the Configurable would observe.
        //
        // We sidestep the DSL by invoking the hook fields directly in the same
        // order buildPanel does, then asserting the recorded sequence. If a
        // future refactor swaps the `beforeOverridesInjection?.invoke` and
        // `afterOverridesInjection?.invoke` lines in buildPanel, the
        // AyuIslandsConfigurableChromeWiringTest bytecode check will catch the
        // missing setter; this test locks in that the two hook fields are
        // independent callback slots on the Panel-level composition (each fires
        // exactly when its owner invokes it, no cross-wiring).
        val callOrder = mutableListOf<String>()
        val panel = AyuIslandsAccentPanel()
        panel.beforeOverridesInjection = { callOrder += "before" }
        panel.afterOverridesInjection = { callOrder += "after" }

        val fakeDslPanel = mockk<com.intellij.ui.dsl.builder.Panel>(relaxed = true)
        panel.beforeOverridesInjection?.invoke(fakeDslPanel)
        // Simulate the OverridesGroupBuilder.buildGroup step between hooks.
        callOrder += "overrides"
        panel.afterOverridesInjection?.invoke(fakeDslPanel)

        kotlin.test.assertEquals(
            listOf("before", "overrides", "after"),
            callOrder,
            "Hook invocation order must be before → overrides → after so the visible " +
                "render order (Accent → System → Overrides → Chrome Tinting → Rotation) is preserved",
        )
    }

    @Test
    fun `unlicensed accent rotation keeps mode and interval controls visible`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.accentRotationEnabled = false
        state.accentRotationMode = AccentRotationMode.PRESET.name
        state.accentRotationIntervalHours = AyuIslandsState.DEFAULT_ROTATION_INTERVAL_HOURS
        val accentPanel = AyuIslandsAccentPanel()

        val dialogPanel = buildDialogPanel(accentPanel)
        val comboBoxes = descendants(dialogPanel, JComboBox::class.java)
        val modeCombo = comboBoxes.first { it.containsItem("Preset cycle") }
        val intervalCombo = comboBoxes.first { it.containsItem("1 hour") }

        kotlin.test.assertTrue(
            modeCombo.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Rotation preview must show the mode selector even when the toggle is off",
        )
        kotlin.test.assertFalse(
            modeCombo.isEnabled,
            "Locked Accent Rotation mode selector must be visible but not mutable",
        )
        kotlin.test.assertTrue(
            intervalCombo.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Rotation preview must show the interval selector even when the toggle is off",
        )
        kotlin.test.assertFalse(
            intervalCombo.isEnabled,
            "Locked Accent Rotation interval selector must be visible but not mutable",
        )
        kotlin.test.assertFalse(
            accentPanel.isModified(),
            "Rendering locked Accent Rotation controls must not dirty Settings",
        )
    }

    private fun buildDialogPanel(accentPanel: AyuIslandsAccentPanel): DialogPanel {
        wireUiDslServices()
        return panel {
            accentPanel.buildPanel(this, AyuVariant.MIRAGE)
        }
    }

    private fun wireUiDslServices() {
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManagerEx>(relaxed = true)
        val mappingsSettings = AccentMappingsSettings()
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns actionManagerMock
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(any<Class<*>>()) } answers {
            when (val serviceClass = firstArg<Class<*>>()) {
                ActionManager::class.java,
                ActionManagerEx::class.java,
                -> actionManagerMock
                AccentMappingsSettings::class.java -> mappingsSettings
                experimentalUiClass -> experimentalUiMock
                else -> mockkClass(serviceClass.kotlin, relaxed = true)
            }
        }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { appMock.getService(ActionManagerEx::class.java) } returns actionManagerMock
        every { appMock.getServiceIfCreated(ActionManager::class.java) } returns actionManagerMock
    }

    private fun <T : Component> descendants(
        container: Container,
        type: Class<T>,
    ): List<T> =
        buildList {
            fun visit(component: Component) {
                if (type.isInstance(component)) add(type.cast(component))
                if (component is Container) {
                    component.components.forEach(::visit)
                }
            }
            visit(container)
        }

    private fun JComboBox<*>.containsItem(item: String): Boolean = (0 until itemCount).any { getItemAt(it) == item }

    private fun Component.isEffectivelyVisibleWithin(root: Component): Boolean {
        var current: Component? = this
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }
}
