package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.CgpAccessProvider
import dev.ayuislands.indent.IrAccessProvider
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioIntegrationCapture
import dev.ayuislands.studio.session.StudioNativeFacts
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptionalIntegrationTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `disabled optional plugins leave the core integration loadable and preserve all preferences`() {
        SwingUtilities.invokeAndWait {
            val application = ApplicationManager.getApplication()
            assertNull(AyuPlugin.findLoadedPlugin(PluginId.getId("com.nasller.CodeGlancePro")))
            assertNull(AyuPlugin.findLoadedPlugin(PluginId.getId("indent-rainbow.indent-rainbow")))
            assertNull(application.getService(CgpAccessProvider::class.java))
            assertNull(application.getService(IrAccessProvider::class.java))
            val state =
                AyuIslandsState().apply {
                    cgpIntegrationEnabled = true
                    isCgpOwnershipMigrated = true
                    irIntegrationEnabled = true
                    isIrOwnershipMigrated = true
                    indentPresetName = "future-preset"
                    indentCustomAlpha = -17
                }
            val before = JDOMUtil.writeElement(XmlSerializer.serialize(state))
            val document =
                assertIs<MigrationResult.Ready>(
                    StudioLegacyMigration.plan(StudioFixtures.capture(state)),
                ).document
            val input = StudioEffectInput(document, StudioTarget.Global, StudioList(emptyList()))
            runBlocking {
                val operation =
                    StudioIntegrationCapture(
                        application.getService(CgpAccessProvider::class.java),
                        application.getService(IrAccessProvider::class.java),
                        EmptyCoroutineContext,
                    ).prepare(
                        input,
                        AccentContext.Ayu(AyuVariant.MIRAGE),
                        "#112233",
                        StudioNativeFacts(true, StudioProcess(42, Instant.EPOCH)),
                    )
                operation.apply()
                operation.accept()
                assertTrue(operation.receipts.isEmpty())
                assertTrue(operation.runtimeChanges.isEmpty())
                assertEquals(document, input.candidate)
                assertEquals(before, JDOMUtil.writeElement(XmlSerializer.serialize(state)))
            }
        }
    }
}
