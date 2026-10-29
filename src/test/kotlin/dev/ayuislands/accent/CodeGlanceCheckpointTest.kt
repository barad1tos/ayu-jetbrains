package dev.ayuislands.accent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.xmlb.XmlSerializer
import com.nasller.codeglance.config.CodeGlanceConfig
import com.nasller.codeglance.config.CodeGlanceConfigService
import dev.ayuislands.AyuPlugin
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodeGlanceCheckpointTest {
    private val application = mockk<Application>(relaxed = true)
    private val settings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val service = CodeGlanceConfigService()
    private val viewport = service.getState()

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        every { settings.state } returns state
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        mockkObject(AyuPlugin)
        val plugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns plugin
        every { plugin.pluginClassLoader } returns service.javaClass.classLoader
        every { application.getService(CodeGlanceConfigService::class.java) } returns service
        state.cgpIntegrationEnabled = true
        state.isCgpOwnershipMigrated = true
        viewport.setViewportColor("123456")
        viewport.setViewportBorderColor("654321")
        viewport.setViewportBorderThickness(3)
        CodeGlanceProIntegration.resetReflectionCache()
    }

    @AfterTest
    fun tearDown() {
        resetIntegrationReceipts(CodeGlanceProIntegration)
        CodeGlanceProIntegration.resetReflectionCache()
        unmockkAll()
    }

    @Test
    fun `outer abort restores the previous viewport and its true native baseline`() {
        assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#AABBCC"))

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            state.indentCustomAlpha = 37
            scope.restore()
        }

        assertViewport(viewport, "AABBCC", "AABBCC", 1)
        assertEquals("OWNED", state.cgpOwnership)
        assertEquals("123456", state.cgpBaseColor)
        assertEquals("654321", state.cgpBaseBorder)
        assertEquals(3, state.cgpBaseThickness)
        assertEquals("AABBCC", state.cgpAppliedColor)
        assertEquals("AABBCC", state.cgpAppliedBorder)
        assertEquals(1, state.cgpAppliedThickness)
        assertEquals(37, state.indentCustomAlpha)
    }

    @Test
    fun `aborted first sync restores native viewport fields and unowned metadata`() {
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            scope.restore()
        }

        assertViewport(viewport, "123456", "654321", 3)
        assertEquals("UNOWNED", state.cgpOwnership)
        assertNull(state.cgpBaseColor)
        assertNull(state.cgpAppliedBorder)
    }

    @Test
    fun `aborted migration gate preserves raw metadata and preferences`() {
        state.isCgpOwnershipMigrated = false
        state.cgpOwnership = "future-owner"
        state.cgpBaseColor = "unknown color"
        state.cgpBaseBorder = null
        state.cgpBaseThickness = -7
        state.cgpAppliedColor = null
        state.cgpAppliedBorder = "unknown border"
        state.cgpAppliedThickness = -3

        withScope { scope ->
            assertIs<IntegrationOutcome.Skipped>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            scope.restore()
        }

        assertFalse(state.isCgpOwnershipMigrated)
        assertEquals("future-owner", state.cgpOwnership)
        assertEquals("unknown color", state.cgpBaseColor)
        assertNull(state.cgpBaseBorder)
        assertEquals(-7, state.cgpBaseThickness)
        assertNull(state.cgpAppliedColor)
        assertEquals("unknown border", state.cgpAppliedBorder)
        assertEquals(-3, state.cgpAppliedThickness)
        assertEquals(true, state.cgpIntegrationEnabled)
        assertViewport(viewport, "123456", "654321", 3)
    }

    @Test
    fun `outer rollback preserves foreign border while restoring the other viewport fields`() {
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            viewport.setViewportBorderColor("foreign border")
            scope.restore()
        }

        assertViewport(viewport, "123456", "foreign border", 3)
        assertEquals("SUSPENDED", state.cgpOwnership)
    }

    @Test
    fun `replacement service is fenced without rebinding or touching either viewport`() {
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            val replacement = CodeGlanceConfigService()
            every { application.getService(CodeGlanceConfigService::class.java) } returns replacement
            scope.restore()

            assertViewport(viewport, "112233", "112233", 1)
            assertViewport(replacement.getState(), "", "", -1)
        }
        assertEquals("SUSPENDED", state.cgpOwnership)
    }

    @Test
    fun `replacement config in the same service is fenced through the existing state getter`() {
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            val replacement = CodeGlanceConfig()
            mockkObject(service)
            every { service.getState() } returns replacement
            scope.restore()

            assertViewport(viewport, "112233", "112233", 1)
            assertViewport(replacement, "", "", -1)
        }
        assertEquals("SUSPENDED", state.cgpOwnership)
    }

    @Test
    fun `failed outer rollback is retried before a newer viewport sync`() {
        assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#AABBCC"))
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            viewport.shouldRejectNextBorder = true
            assertFailsWith<IllegalStateException> { scope.restore() }
            assertEquals("RECOVERY_PENDING", state.cgpOwnership)
            assertEquals(viewport.viewportColor, state.cgpAppliedColor)
            assertEquals(viewport.viewportBorderColor, state.cgpAppliedBorder)
            assertEquals(viewport.viewportBorderThickness, state.cgpAppliedThickness)
            assertEquals("123456", state.cgpBaseColor)
        }

        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#445566"))
            scope.restore()
        }
        assertViewport(viewport, "AABBCC", "AABBCC", 1)
        assertEquals("OWNED", state.cgpOwnership)
        assertEquals("AABBCC", state.cgpAppliedColor)
        assertEquals("123456", state.cgpBaseColor)
        assertEquals("654321", state.cgpBaseBorder)
        assertEquals(3, state.cgpBaseThickness)
    }

    @Test
    fun `aborted disabled gate restores the old viewport without reenabling the preference`() {
        assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#AABBCC"))
        state.cgpIntegrationEnabled = false
        withScope { scope ->
            assertIs<IntegrationOutcome.Restored>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            assertViewport(viewport, "123456", "654321", 3)
            scope.restore()
        }
        assertViewport(viewport, "AABBCC", "AABBCC", 1)
        assertEquals("OWNED", state.cgpOwnership)
        assertFalse(state.cgpIntegrationEnabled)
    }

    @Test
    fun `cancellation after a native setter propagates unchanged without warning or lost baseline`() {
        mockkObject(viewport)
        for (cancelled in listOf(ProcessCanceledException(), CancellationException("cancel viewport sync"))) {
            every { viewport.setViewportBorderColor("112233") } answers {
                callOriginal()
                throw cancelled
            }
            withScope { scope ->
                val warnings = mutableListOf<Throwable>()
                val processor =
                    object : LoggedErrorProcessor() {
                        override fun processWarn(
                            category: String,
                            message: String,
                            t: Throwable?,
                        ): Boolean {
                            if (t != null) warnings += t
                            return false
                        }
                    }
                LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
                    assertSame(
                        cancelled,
                        assertFailsWith<RuntimeException> {
                            scope.restoringOnFailure { CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233") }
                        },
                    )
                }
                assertFalse(warnings.any { it === cancelled })
            }
            assertViewport(viewport, "123456", "654321", 3)
            assertEquals("UNOWNED", state.cgpOwnership)
        }
    }

    @Test
    fun `restart recovery retains the native viewport rather than stray unowned metadata`() {
        state.cgpBaseColor = "stale color"
        state.cgpBaseBorder = "stale border"
        state.cgpBaseThickness = -7
        state.externalThemeAccentSource = "future-source"
        state.forceOverrides.addAll(listOf("future-surface", "PANEL_BORDER"))
        val choices = state.forceOverrides.toList()
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            viewport.shouldRejectNextBorder = true
            assertFailsWith<IllegalStateException> { scope.restore() }
        }
        assertEquals("123456", state.cgpBaseColor)
        assertEquals("654321", state.cgpBaseBorder)
        assertEquals(3, state.cgpBaseThickness)

        val reloaded = XmlSerializer.deserialize(XmlSerializer.serialize(state), AyuIslandsState::class.java)
        resetIntegrationReceipts(CodeGlanceProIntegration)
        CodeGlanceProIntegration.resetReflectionCache()
        every { settings.state } returns reloaded
        val restarted = CodeGlanceConfigService()
        val restoredViewport = restarted.getState()
        restoredViewport.setViewportColor(viewport.viewportColor)
        restoredViewport.setViewportBorderColor(viewport.viewportBorderColor)
        restoredViewport.setViewportBorderThickness(viewport.viewportBorderThickness)
        every { application.getService(CodeGlanceConfigService::class.java) } returns restarted

        assertIs<IntegrationOutcome.Restored>(CodeGlanceProIntegration.restoreOwnedState())
        assertViewport(restoredViewport, "123456", "654321", 3)
        reloaded.cgpIntegrationEnabled = false
        assertIs<IntegrationOutcome.Skipped>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#445566"))
        assertFalse(reloaded.cgpIntegrationEnabled)
        assertEquals("future-source", reloaded.externalThemeAccentSource)
        assertEquals(choices, reloaded.forceOverrides.toList())
        reloaded.cgpIntegrationEnabled = true
        CodeGlanceProIntegration.prepareExplicitEnable()
        assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#445566"))
        assertEquals("123456", reloaded.cgpBaseColor)
        assertEquals("654321", reloaded.cgpBaseBorder)
        assertEquals(3, reloaded.cgpBaseThickness)
        assertTrue(reloaded.cgpIntegrationEnabled)
    }

    @Test
    fun `pending retry reports ordinary failures and propagates control signals`() {
        mockkObject(viewport)
        var retryError: Throwable? = null
        every { viewport.setViewportBorderColor("654321") } answers {
            retryError?.let { failure -> throw failure }
            callOriginal()
        }
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            retryError = IllegalStateException("border restoration rejected")
            assertFailsWith<IllegalStateException> { scope.restore() }
        }

        val outcome = assertIs<IntegrationOutcome.Failed>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#445566"))
        assertSame(retryError, outcome.error)
        assertEquals("RECOVERY_PENDING", state.cgpOwnership)
        for (cancelled in listOf(ProcessCanceledException(), CancellationException("cancel retry"))) {
            retryError = cancelled
            assertSame(
                cancelled,
                assertFailsWith<RuntimeException> { CodeGlanceProIntegration.syncCodeGlanceProViewport("#445566") },
            )
        }
        val fatal = object : VirtualMachineError("fatal retry") {}
        retryError = fatal
        assertSame(
            fatal,
            assertFailsWith<VirtualMachineError> { CodeGlanceProIntegration.syncCodeGlanceProViewport("#445566") },
        )
        retryError = null
        assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#445566"))
        assertViewport(viewport, "445566", "445566", 1)
        assertEquals("123456", state.cgpBaseColor)
        assertEquals("654321", state.cgpBaseBorder)
        assertEquals(3, state.cgpBaseThickness)
    }

    @Test
    fun `restart after failed rollback never claims a foreign viewport field`() {
        withScope { scope ->
            assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
            viewport.setViewportBorderColor("foreign border")
            mockkObject(viewport)
            every { viewport.setViewportColor("123456") } answers {
                callOriginal()
                error("color restoration failed after mutation")
            }
            assertFailsWith<IllegalStateException> { scope.restore() }
        }

        val reloaded = XmlSerializer.deserialize(XmlSerializer.serialize(state), AyuIslandsState::class.java)
        resetIntegrationReceipts(CodeGlanceProIntegration)
        CodeGlanceProIntegration.resetReflectionCache()
        every { settings.state } returns reloaded
        val restarted = CodeGlanceConfigService()
        val restoredViewport = restarted.getState()
        restoredViewport.setViewportColor(viewport.viewportColor)
        restoredViewport.setViewportBorderColor(viewport.viewportBorderColor)
        restoredViewport.setViewportBorderThickness(viewport.viewportBorderThickness)
        every { application.getService(CodeGlanceConfigService::class.java) } returns restarted

        val outcome = CodeGlanceProIntegration.restoreOwnedState()
        assertViewport(restoredViewport, "123456", "foreign border", 3)
        assertIs<IntegrationOutcome.Skipped>(outcome)
        assertEquals("SUSPENDED", reloaded.cgpOwnership)
    }

    @Test
    fun `rollback cancellation wins over an ordinary setter failure and retains both errors`() {
        mockkObject(viewport)
        val writeError = IllegalStateException("border write failed")
        val cancelled = ProcessCanceledException()
        var shouldCancelRollback = true
        every { viewport.setViewportBorderColor("112233") } answers {
            callOriginal()
            throw writeError
        }
        every { viewport.setViewportBorderColor("654321") } answers {
            callOriginal()
            if (shouldCancelRollback) {
                shouldCancelRollback = false
                throw cancelled
            }
        }

        withScope { scope ->
            assertSame(
                cancelled,
                assertFailsWith<RuntimeException> {
                    scope.restoringOnFailure { CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233") }
                },
            )
        }
        assertTrue(cancelled.suppressed.any { it === writeError })
        assertViewport(viewport, "123456", "654321", 3)
        assertEquals("UNOWNED", state.cgpOwnership)
    }

    @Test
    fun `wrapped fatal setter error escapes before another native read`() {
        mockkObject(viewport)
        val fatal = object : VirtualMachineError("synthetic native fatal error") {}
        var hasFailed = false
        var readsAfterFailure = 0
        every { viewport.setViewportColor("112233") } answers {
            callOriginal()
            hasFailed = true
            throw fatal
        }
        every { viewport.viewportBorderColor } answers {
            if (hasFailed) readsAfterFailure++
            callOriginal()
        }
        withScope {
            assertSame(
                fatal,
                assertFailsWith<VirtualMachineError> { CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233") },
            )
        }
        assertEquals(0, readsAfterFailure)
    }

    @Test
    fun `resolution cancellation leaves plugin availability retryable`() {
        val plugin = AyuPlugin.findLoadedPlugin(PluginId.getId("com.nasller.CodeGlancePro"))
        val cancelled = ProcessCanceledException()
        every { AyuPlugin.findLoadedPlugin(any()) } throws cancelled
        assertSame(
            cancelled,
            assertFailsWith<ProcessCanceledException> { CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233") },
        )
        every { AyuPlugin.findLoadedPlugin(any()) } returns plugin
        assertIs<IntegrationOutcome.Applied>(CodeGlanceProIntegration.syncCodeGlanceProViewport("#112233"))
        assertViewport(viewport, "112233", "112233", 1)
    }

    private fun withScope(action: (AccentApplyScope) -> Unit) {
        SwingUtilities.invokeAndWait {
            val scope = AccentApplyScope()
            scope.open()
            try {
                action(scope)
            } finally {
                scope.close()
            }
        }
    }

    private fun assertViewport(
        config: CodeGlanceConfig,
        color: String,
        border: String,
        thickness: Int,
    ) {
        assertEquals(color, config.viewportColor)
        assertEquals(border, config.viewportBorderColor)
        assertEquals(thickness, config.viewportBorderThickness)
    }
}

/** Drops test-owned receipt references after assertions; it never changes persisted settings. */
internal fun resetIntegrationReceipts(adapter: Any) {
    val ownerField = adapter.javaClass.getDeclaredField("receipts")
    ownerField.isAccessible = true
    val owner = ownerField.get(adapter)
    IntegrationReceipts::class.java.declaredFields
        .filter { it.name in setOf("boundScope", "bound", "pending") }
        .forEach { field ->
            field.isAccessible = true
            field.set(owner, null)
        }
}
