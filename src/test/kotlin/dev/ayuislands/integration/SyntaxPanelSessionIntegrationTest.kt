package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
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
import dev.ayuislands.syntax.SyntaxTransactionResult
import dev.ayuislands.theme.EditorSchemeChange
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Color
import java.awt.Font
import kotlin.test.assertIs

class SyntaxPanelSessionIntegrationTest : BasePlatformTestCase() {
    fun testApplyAndCancelPreserveLiveEdits() {
        withIsolatedScheme(::exerciseSessionContracts)
    }

    fun testExternalPublicationRestoresUnchangedSettingsOnResetAndCancel() {
        withIsolatedScheme { scheme ->
            val key = TextAttributesKey.find("KOTLIN_KEYWORD")
            val baseline =
                scheme.getAttributes(key).clone().apply {
                    foregroundColor = Color.MAGENTA
                    fontType = Font.BOLD or Font.ITALIC
                }
            scheme.setAttributes(key, baseline)
            scheme.metaProperties.setProperty("test.syntax.unknown", " unknown raw value  ")
            val editorFonts = FontPreferencesImpl().also(scheme.fontPreferences::copyTo)
            val consoleFonts = FontPreferencesImpl().also(scheme.consoleFontPreferences::copyTo)
            val initial =
                SyntaxPresetConfig(
                    selectedPreset = SyntaxPreset.CUSTOM.name,
                    customOverrides =
                        linkedMapOf(
                            "Unavailable" to mapOf("FUTURE" to 37),
                            "Kotlin" to mapOf("KEYWORD" to 100),
                        ),
                    customStyles =
                        linkedMapOf(
                            "Unavailable" to mapOf("FUTURE" to 3),
                            "Kotlin" to mapOf("KEYWORD" to Font.PLAIN),
                        ),
                )
            val service = SyntaxIntensityService()
            for (close in listOf(false, true)) {
                val persisted = mutableListOf<SyntaxPresetConfig>()
                val pending = session(initial, service, persisted::add)
                try {
                    EditorSchemeChange.publish()
                    assertEquals(Font.PLAIN, scheme.getAttributes(key).fontType)
                    assertFalse(baseline == scheme.getAttributes(key))
                    if (close) {
                        assertSame(SyntaxRestoreResult.Restored, pending.cancel())
                    } else {
                        assertSame(SyntaxRestoreResult.Restored, pending.reset())
                    }
                    assertEquals(baseline, scheme.getAttributes(key))
                    assertTrue(persisted.isEmpty())
                    assertEquals(" unknown raw value  ", scheme.metaProperties.getProperty("test.syntax.unknown"))
                    assertEquals(editorFonts, FontPreferencesImpl().also(scheme.fontPreferences::copyTo))
                    assertEquals(consoleFonts, FontPreferencesImpl().also(scheme.consoleFontPreferences::copyTo))
                } finally {
                    pending.dispose()
                }
            }
        }
    }

    fun testCancelAndRecoveryPreserveManualEdits() {
        withIsolatedScheme { scheme ->
            val key = TextAttributesKey.find("KOTLIN_KEYWORD")
            val initial = SyntaxPresetConfig(SyntaxPreset.AMBIENT.name, emptyMap())
            val edited = initial.copy(selectedPreset = SyntaxPreset.WHISPER.name)
            val service = SyntaxIntensityService()
            val failures = mutableListOf<RuntimeException>()
            val pending = session(initial, service, onFailure = failures::add)
            try {
                pending.editDiscrete(edited)
                val previewValue = scheme.getAttributes(key).clone()
                val manual = previewValue.clone().apply { foregroundColor = Color.MAGENTA }
                scheme.setAttributes(key, manual)
                val metadata = scheme.metaProperties.toMap()
                assertIs<SyntaxRestoreResult.Failed>(pending.cancel())
                assertEquals(manual, scheme.getAttributes(key))
                assertEquals(metadata, scheme.metaProperties.toMap())
                assertFalse(failures.isEmpty())
                pending.dispose()

                val recovery = service.openRuntimeSession()
                try {
                    assertIs<SyntaxTransactionResult.RecoveryRequired>(recovery.restore())
                    assertEquals(manual, scheme.getAttributes(key))
                    assertEquals(metadata, scheme.metaProperties.toMap())
                    scheme.setAttributes(key, previewValue)
                    assertIs<SyntaxTransactionResult.Applied>(recovery.restore())
                } finally {
                    assertNull(recovery.close())
                }
            } finally {
                pending.dispose()
            }
        }
    }

    fun testToggleRoundTripAndSchemeReloadPreservePreviewBaseline() {
        withIsolatedScheme { scheme ->
            val key = TextAttributesKey.find("KOTLIN_KEYWORD")
            val baseline = scheme.getAttributes(key).clone()
            scheme.metaProperties.setProperty("test.syntax.unknown", "future value  ")
            val initial =
                SyntaxPresetConfig(
                    selectedPreset = SyntaxPreset.CUSTOM.name,
                    customOverrides =
                        linkedMapOf(
                            "Missing language" to mapOf("FUTURE" to 41),
                            "Kotlin" to mapOf("KEYWORD" to 70),
                        ),
                )
            val persisted = mutableListOf<SyntaxPresetConfig>()
            val pending = session(initial, SyntaxIntensityService(), persisted::add)
            val settings = AyuIslandsSettings.getInstance().state
            val originalToggle = settings.ignorePluginSyntaxColorsEnabled
            try {
                EditorSchemeChange.publish()
                settings.ignorePluginSyntaxColorsEnabled = false
                EditorSchemeChange.publish()
                settings.ignorePluginSyntaxColorsEnabled = true
                EditorSchemeChange.publish()
                assertSame(SyntaxRestoreResult.Restored, pending.reset())
                assertEquals(baseline, scheme.getAttributes(key))
                assertTrue(persisted.isEmpty())
                val native = assertIs<EditorColorsSchemeImpl>(scheme)
                val reloaded = EditorColorsSchemeImpl(native.parentScheme).apply { readExternal(native.writeScheme()) }
                assertEquals(baseline, reloaded.getAttributes(key))
                assertEquals("future value  ", reloaded.metaProperties.getProperty("test.syntax.unknown"))
                assertSame(SyntaxCommitResult.Applied, pending.apply(initial))
                assertEquals(listOf(initial), persisted)
                assertEquals(
                    listOf("Missing language", "Kotlin"),
                    persisted
                        .single()
                        .customOverrides.keys
                        .toList(),
                )
            } finally {
                settings.ignorePluginSyntaxColorsEnabled = originalToggle
                pending.dispose()
            }
        }
    }

    private fun withIsolatedScheme(exercise: (EditorColorsScheme) -> Unit) {
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
                    val checkpoints = captureSchemeCheckpoints(isolatedScheme)
                    try {
                        exercise(isolatedScheme)
                    } finally {
                        checkpoints.asReversed().forEach { checkpoint ->
                            assertTrue(EditorSchemeOverrides.checkpoints.rollback(checkpoint).isEmpty())
                        }
                    }
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
        onFailure: (RuntimeException) -> Unit = { throw it },
    ): SyntaxPanelSession =
        SyntaxPanelSession(
            initialCheckpoint = initial,
            persist = persist,
            onRuntimeApplied = {},
            onRuntimeFailed = onFailure,
            onRelinquished = {},
            onForeignScheme = { fail("Isolated Ayu scheme must stay active") },
            service = service,
            isAyuActive = { true },
        )
}
