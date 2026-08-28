package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.SyntaxCommitResult
import dev.ayuislands.settings.SyntaxPanelSession
import dev.ayuislands.settings.SyntaxRestoreResult
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxOverlayLoader
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Font

class SyntaxPanelSessionIntegrationTest : BasePlatformTestCase() {
    fun testApplyAndCancelPreserveLiveEdits() {
        val manager = EditorColorsManager.getInstance()
        val previousScheme = manager.globalScheme
        val isolatedScheme = previousScheme.clone() as EditorColorsScheme
        isolatedScheme.name = "_@user_Ayu Islands Mirage"
        val selectScheme = manager::setGlobalScheme
        val settings = AyuIslandsSettings.getInstance()
        val previousIgnoreSetting = settings.state.ignorePluginSyntaxColorsEnabled
        selectScheme(isolatedScheme)
        settings.state.ignorePluginSyntaxColorsEnabled = true

        try {
            LicenseChecker.withConfirmedEntitlement(LicenseEntitlement.LICENSED) {
                ApplicationManager.getApplication().invokeAndWait {
                    exerciseSessionContracts(isolatedScheme)
                }
            }
        } finally {
            settings.state.ignorePluginSyntaxColorsEnabled = previousIgnoreSetting
            selectScheme(previousScheme)
        }
    }

    private fun exerciseSessionContracts(scheme: EditorColorsScheme) {
        val key = TextAttributesKey.find("KOTLIN_KEYWORD")
        val original = scheme.getAttributes(key).clone()
        val initial =
            SyntaxPresetConfig(
                selectedPreset = SyntaxPreset.AMBIENT.name,
                customOverrides = emptyMap(),
            )
        val edited =
            initial.copy(
                selectedPreset = SyntaxPreset.CUSTOM.name,
                customOverrides = mapOf("Kotlin" to mapOf("KEYWORD" to 100)),
                customStyles = mapOf("Kotlin" to mapOf("KEYWORD" to Font.PLAIN)),
            )
        var publications = 0
        val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { publications += 1 })
        val service = SyntaxIntensityService()
        val checkpoints = captureSchemeCheckpoints(scheme)

        try {
            val cancelSession = session(initial, service)
            cancelSession.editDiscrete(edited)
            assertNotSame(original, scheme.getAttributes(key))
            assertEquals(Font.PLAIN, scheme.getAttributes(key).fontType)
            assertSame(SyntaxRestoreResult.Restored, cancelSession.cancel())
            assertEquals(original, scheme.getAttributes(key))
            cancelSession.dispose()

            val persisted = mutableListOf<SyntaxPresetConfig>()
            val applySession = session(initial, service, persisted::add)
            applySession.editDiscrete(edited)
            assertSame(SyntaxCommitResult.Applied, applySession.apply(edited))
            val applied = scheme.getAttributes(key).clone()
            assertEquals(listOf(edited), persisted)
            assertSame(SyntaxRestoreResult.Restored, applySession.cancel())
            assertEquals(applied, scheme.getAttributes(key))
            applySession.dispose()

            assertTrue("Preview, restore and apply must publish scheme changes", publications >= 3)
        } finally {
            checkpoints.asReversed().forEach { checkpoint ->
                assertTrue(EditorSchemeOverrides.checkpoints.rollback(checkpoint).isEmpty())
            }
        }
    }

    private fun captureSchemeCheckpoints(
        activeScheme: EditorColorsScheme,
    ): List<EditorSchemeOverrides.AttributesCheckpoint> {
        val loader = SyntaxOverlayLoader.getInstance()
        val manager = EditorColorsManager.getInstance()
        val schemes =
            mapOf(
                "Ayu Islands Mirage" to "Mirage",
                "Ayu Islands Dark" to "Dark",
                "Ayu Islands Light" to "Light",
            ).mapNotNull { (name, variant) -> manager.getScheme(name)?.let { it to variant } } +
                (activeScheme to "Mirage")
        return schemes.distinctBy { (scheme) -> scheme }.map { (scheme, variant) ->
            val keys =
                loader.loadBaselineForVariant(variant).keys +
                    loader.loadOverlayForVariant(variant).keys
            EditorSchemeOverrides.checkpoints.capture(
                scheme = scheme,
                owner = EditorSchemeOwner.Syntax,
                keys = keys.toSet(),
            )
        }
    }

    private fun session(
        initial: SyntaxPresetConfig,
        service: SyntaxIntensityService,
        persist: (SyntaxPresetConfig) -> Unit = {},
    ): SyntaxPanelSession =
        SyntaxPanelSession(
            initialCheckpoint = initial,
            persist = persist,
            onRuntimeApplied = {},
            onRuntimeFailed = { throw it },
            onRelinquished = {},
            onForeignScheme = { fail("Isolated Ayu scheme must stay active") },
            service = service,
            isAyuActive = { true },
        )
}
