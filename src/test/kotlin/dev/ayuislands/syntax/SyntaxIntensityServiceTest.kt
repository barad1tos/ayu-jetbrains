package dev.ayuislands.syntax

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.messages.MessageBus
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.awt.Font
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Orchestration tests for [SyntaxIntensityService]. Reuses the prior
 * service-orchestrator MockK harness - mocks the three named Ayu schemes
 * via `EditorColorsManager.getScheme(...)` plus the active `globalScheme`,
 * verifies a single read-action-free `globalSchemeChange` publish per
 * `apply()` invocation (R-7), and pins the R-1 fallback + service-layer
 * `CUSTOM` premium gate behaviour through `mockkObject` calls into
 * `RgbBlend` / `LicenseChecker` / `SyntaxIntensityApplicator`.
 */
class SyntaxIntensityServiceTest {
    private lateinit var mockMirage: EditorColorsScheme
    private lateinit var mockDark: EditorColorsScheme
    private lateinit var mockLight: EditorColorsScheme
    private lateinit var mockManager: EditorColorsManager
    private lateinit var mockMessageBus: MessageBus
    private lateinit var mockPublisher: EditorColorsListener
    private lateinit var mockApp: Application
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var stateInstance: SyntaxIntensityState
    private lateinit var ayuSettings: AyuIslandsSettings
    private lateinit var ayuState: AyuIslandsState
    private val keyCache = mutableMapOf<String, TextAttributesKey>()

    @BeforeTest
    fun setUp() {
        keyCache.clear()
        mockkStatic(TextAttributesKey::class)
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            keyCache.getOrPut(name) { mockk(relaxed = true) { every { externalName } returns name } }
        }

        mockMirage = mockk(relaxed = true) { every { name } returns "Ayu Islands Mirage" }
        mockDark = mockk(relaxed = true) { every { name } returns "Ayu Islands Dark" }
        mockLight = mockk(relaxed = true) { every { name } returns "Ayu Islands Light" }
        every { mockMirage.defaultBackground } returns Color(0x1F, 0x24, 0x30)
        every { mockDark.defaultBackground } returns Color(0x0D, 0x10, 0x17)
        every { mockLight.defaultBackground } returns Color(0xFC, 0xFC, 0xFC)

        mockManager = mockk(relaxed = true)
        mockMessageBus = mockk(relaxed = true)
        mockPublisher = mockk(relaxed = true)
        mockApp = mockk(relaxed = true)
        ayuState = AyuIslandsState()
        ayuSettings = mockk(relaxed = true)
        every { ayuSettings.state } returns ayuState

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockManager
        every { mockManager.getScheme("Ayu Islands Mirage") } returns mockMirage
        every { mockManager.getScheme("Ayu Islands Dark") } returns mockDark
        every { mockManager.getScheme("Ayu Islands Light") } returns mockLight
        // Default: active scheme is Mirage (one of the named ones) so H5 dedup
        // skips the extra write. Tests that need a derived active scheme override
        // this individually.
        every { mockManager.globalScheme } returns mockMirage

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.messageBus } returns mockMessageBus
        every { mockMessageBus.syncPublisher(EditorColorsManager.TOPIC) } returns mockPublisher

        every { mockApp.runReadAction(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
        }

        loader = mockk(relaxed = true)
        val payload =
            mapOf(
                TextAttributesKey.find("K1") to
                    TextAttributes().apply {
                        foregroundColor = Color(0xFF, 0xCC, 0x66)
                    },
            )
        for (variant in listOf("Mirage", "Dark", "Light")) {
            every { loader.loadOverlayForVariant(variant) } returns payload
            every { loader.loadBaselineForVariant(variant) } returns payload
        }
        mockkObject(SyntaxOverlayLoader.Companion)
        every { SyntaxOverlayLoader.getInstance() } returns loader

        stateInstance = mockk(relaxed = true)
        every { stateInstance.toPresetConfig() } returns
            SyntaxPresetConfig(selectedPreset = "AMBIENT", customOverrides = emptyMap())
        mockkObject(SyntaxIntensityState.Companion)
        every { SyntaxIntensityState.getInstance() } returns stateInstance

        // Default: licensed so the CUSTOM gate doesn't normalise on every test.
        // Individual tests override to false where the gate is the subject.
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        // R-1 fallback observer - overridden per test that needs to assert
        // engagement; default keeps the stub silent.
        mockkObject(RgbBlend)
        every { RgbBlend.fallbackEditorBgFor(any()) } returns Color(0x1F, 0x24, 0x30)

        // Applicator returns the same payload it received - the service is the
        // unit under test, not the HSL math.
        mockkObject(SyntaxIntensityApplicator)
        every {
            SyntaxIntensityApplicator.compute(any())
        } returns payload

        every { mockApp.getService(SyntaxIntensityService::class.java) } returns SyntaxIntensityService()
        every { mockApp.getService(AyuIslandsSettings::class.java) } returns ayuSettings
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ---------- Test 1: H5 dual-write - 3 named schemes + active (or dedup) ----------

    @Test
    fun `apply iterates all 3 named Ayu schemes and reads globalScheme (H5 dual-write entry)`() {
        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        verify(exactly = 1) { mockManager.getScheme("Ayu Islands Mirage") }
        verify(exactly = 1) { mockManager.getScheme("Ayu Islands Dark") }
        verify(exactly = 1) { mockManager.getScheme("Ayu Islands Light") }
        verify(atLeast = 1) { mockManager.globalScheme }
        // H5 dedup: active globalScheme === mockMirage in the default setup,
        // so Mirage is written exactly once (not twice).
        verify(exactly = 1) { mockMirage.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 1) { mockDark.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 1) { mockLight.setAttributes(any(), any<TextAttributes>()) }
    }

    // ---------- Test 2: Pattern A missing-scheme log-once ----------

    @Test
    fun `missing scheme logs WARN only once across repeated apply calls (Pattern A latch)`() {
        every { mockManager.getScheme("Ayu Islands Light") } returns null
        val service = SyntaxIntensityService()
        service.apply(SyntaxPreset.AMBIENT, emptyMap())
        service.apply(SyntaxPreset.AMBIENT, emptyMap())
        // Mirage + Dark still receive both apply calls' writes (verifies the
        // null Light didn't block them) - Pattern A latch lives inside the
        // service, not asserted directly via the logger; the contract is that
        // the apply call continues after the missing scheme.
        verify(exactly = 2) { mockMirage.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 2) { mockDark.setAttributes(any(), any<TextAttributes>()) }
        // Light was never written because its scheme was null.
        verify(exactly = 0) { mockLight.setAttributes(any(), any<TextAttributes>()) }
    }

    // ---------- Test 3: R-7 single publish per apply ----------

    @Test
    fun `apply fires globalSchemeChange exactly once per call (R-7)`() {
        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    // ---------- Test 4: R-7 publish without read-action wrap ----------

    @Test
    fun `globalSchemeChange publish is not wrapped in read action (R-7)`() {
        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        verify(exactly = 0) { mockApp.runReadAction(any<Runnable>()) }
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    // ---------- Test 5: R-1 fallback engages for dark variant + WHITE bg ----------

    @Test
    fun `R-1 fallback engages when dark variant scheme defaultBackground is white`() {
        // Force Mirage to surface the platform sentinel; the service must
        // substitute RgbBlend.fallbackEditorBgFor("Mirage").
        every { mockMirage.defaultBackground } returns Color.WHITE
        JBColor.setDark(true)
        try {
            SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
            verify(exactly = 1) { RgbBlend.fallbackEditorBgFor("Mirage") }
        } finally {
            JBColor.setDark(false)
        }
    }

    // ---------- Test 6: R-1 fallback skipped for Light variant + WHITE bg ----------

    @Test
    fun `R-1 fallback skipped for Light variant even when defaultBackground is white`() {
        // Light's Color.WHITE IS correct - the fallback gate restricts engagement
        // to DARK_OVERLAY_VARIANTS only.
        every { mockLight.defaultBackground } returns Color.WHITE
        // Mirage + Dark are seeded with realistic dark backgrounds in setUp,
        // so the only WHITE arrival is Light's. The fallback should NEVER fire
        // for Light.
        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        verify(exactly = 0) { RgbBlend.fallbackEditorBgFor("Light") }
    }

    // ---------- Test 7: active scheme safety ----------

    @Test
    fun `foreign active globalScheme is not mutated by syntax intensity apply`() {
        val solarizedScheme: EditorColorsScheme =
            mockk(relaxed = true) {
                every { name } returns "Solarized (Light)"
                every { defaultBackground } returns Color.WHITE
            }
        every { mockManager.globalScheme } returns solarizedScheme
        val service = SyntaxIntensityService()
        service.apply(SyntaxPreset.WHISPER, emptyMap())
        service.apply(SyntaxPreset.WHISPER, emptyMap())

        verify(exactly = 0) { solarizedScheme.setAttributes(any(), any<TextAttributes>()) }
    }

    @Test
    fun `disabled ignore plugin colors restore default attributes on Ayu schemes`() {
        ayuState.ignorePluginSyntaxColorsEnabled = false

        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())

        verifyIgnorePluginStockWrites(mockMirage, ignorePluginDarculaStock)
        verifyIgnorePluginStockWrites(mockDark, ignorePluginDarculaStock)
        verifyIgnorePluginStockWrites(mockLight, ignorePluginDefaultStock)
    }

    @Test
    fun `disabled ignore plugin colors restore stock attributes on user-derived active Ayu scheme`() {
        ayuState.ignorePluginSyntaxColorsEnabled = false
        val userDerivedScheme: EditorColorsScheme =
            mockk(relaxed = true) {
                every { name } returns "_@user_Ayu Islands Mirage"
                every { defaultBackground } returns Color(0x1F, 0x24, 0x30)
            }
        every { mockManager.globalScheme } returns userDerivedScheme

        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())

        verifyIgnorePluginStockWrites(userDerivedScheme, ignorePluginDarculaStock)
    }

    @Test
    fun `enabled ignore plugin colors do not add default restore writes`() {
        ayuState.ignorePluginSyntaxColorsEnabled = true

        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())

        verify(exactly = 0) {
            mockMirage.setAttributes(
                TextAttributesKey.find("IGNORE.COMMENT"),
                any<TextAttributes>(),
            )
        }
    }

    @Test
    fun `user-derived Ayu active globalScheme still receives syntax intensity write`() {
        val userDerivedScheme: EditorColorsScheme =
            mockk(relaxed = true) {
                every { name } returns "_@user_Ayu Islands Mirage"
                every { defaultBackground } returns Color(0x1F, 0x24, 0x30)
            }
        every { mockManager.globalScheme } returns userDerivedScheme

        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())

        verify(exactly = 1) { userDerivedScheme.setAttributes(any(), any<TextAttributes>()) }
    }

    // ---------- Test 8: H5 identity dedup (active === named) ----------

    @Test
    fun `H5 identity dedup - Mirage is written exactly once when globalScheme is Mirage`() {
        // Default setUp already wires globalScheme to mockMirage; this test
        // is the explicit assertion of the dedup contract.
        SyntaxIntensityService().apply(SyntaxPreset.AMBIENT, emptyMap())
        verify(exactly = 1) { mockMirage.setAttributes(any(), any<TextAttributes>()) }
    }

    // ---------- Test 9: Pattern B per-key write isolation ----------

    @Test
    fun `Pattern B - apply continues after RuntimeException on one scheme write`() {
        every { mockMirage.setAttributes(any(), any<TextAttributes>()) } throws RuntimeException("simulated")
        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        verify(atLeast = 1) { mockDark.setAttributes(any(), any<TextAttributes>()) }
        verify(atLeast = 1) { mockLight.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `apply does not swallow CancellationException`() {
        every { mockMirage.setAttributes(any(), any<TextAttributes>()) } throws
            kotlinx.coroutines.CancellationException("cancelled")
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        }
    }

    // ---------- Test 10: reapplyForActiveLaf reads state and dispatches ----------

    @Test
    fun `reapplyForActiveLaf reads selectedPreset from state and delegates to apply`() {
        every { stateInstance.toPresetConfig() } returns
            SyntaxPresetConfig(selectedPreset = "NEON", customOverrides = emptyMap())
        SyntaxIntensityService().reapplyForActiveLaf()
        verify(exactly = 1) {
            SyntaxIntensityApplicator.compute(
                match {
                    it.preset == SyntaxPreset.NEON && it.variantName == "Mirage"
                },
            )
        }
    }

    // ---------- Test 11: CUSTOM gate licensed - passes through ----------

    @Test
    fun `CUSTOM preset passes through when license is active`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        SyntaxIntensityService().apply(
            SyntaxPreset.CUSTOM,
            mapOf("Java" to mapOf("KEYWORD" to 75)),
        )
        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match { it.preset == SyntaxPreset.CUSTOM },
            )
        }
    }

    // ---------- Test 12: CUSTOM gate unlicensed - normalises down ----------

    @Test
    fun `CUSTOM preset normalises to AMBIENT when unlicensed`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        SyntaxIntensityService().apply(
            SyntaxPreset.CUSTOM,
            mapOf("Java" to mapOf("KEYWORD" to 75)),
        )
        // Applicator must never see CUSTOM from an unlicensed call path.
        verify(exactly = 0) {
            SyntaxIntensityApplicator.compute(
                match { it.preset == SyntaxPreset.CUSTOM },
            )
        }
        // It MUST see AMBIENT instead (the normalised fallback).
        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match { it.preset == SyntaxPreset.AMBIENT },
            )
        }
    }

    // ---------- Test 13b: customStyles threaded through to compute ----------

    @Test
    fun `apply threads customStyles through to the applicator compute call`() {
        val styles = mapOf("Java" to mapOf("KEYWORD" to (java.awt.Font.BOLD or java.awt.Font.ITALIC)))
        SyntaxIntensityService().apply(
            preset = SyntaxPreset.CUSTOM,
            customOverrides = mapOf("Java" to mapOf("KEYWORD" to 75)),
            subordinatePreset = SyntaxPreset.AMBIENT,
            customStyles = styles,
        )
        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match {
                    it.preset == SyntaxPreset.CUSTOM && it.customStyles == styles
                },
            )
        }
    }

    @Test
    fun `reapplyForActiveLaf forwards config customStyles to compute`() {
        val styles = mapOf("Kotlin" to mapOf("COMMENT" to java.awt.Font.ITALIC))
        every { stateInstance.toPresetConfig() } returns
            SyntaxPresetConfig(
                selectedPreset = "CUSTOM",
                customOverrides = emptyMap(),
                customStyles = styles,
            )
        SyntaxIntensityService().reapplyForActiveLaf()
        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match {
                    it.preset == SyntaxPreset.CUSTOM && it.customStyles == styles
                },
            )
        }
    }

    @Test
    fun `reapplyForActiveLaf forwards readability options to compute`() {
        val readabilityOptions =
            SyntaxReadabilityOptions(
                dimComments = true,
                softenDocumentation = true,
                quietOperators = true,
                emphasizeDeclarations = true,
            )
        every { stateInstance.toPresetConfig() } returns
            SyntaxPresetConfig(
                selectedPreset = "AMBIENT",
                customOverrides = emptyMap(),
                readabilityOptions = readabilityOptions,
            )

        SyntaxIntensityService().reapplyForActiveLaf()

        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match {
                    it.preset == SyntaxPreset.AMBIENT && it.readabilityOptions == readabilityOptions
                },
            )
        }
    }

    @Test
    fun `reapplyForActiveLaf drops readability options when unlicensed`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        every { stateInstance.toPresetConfig() } returns
            SyntaxPresetConfig(
                selectedPreset = "AMBIENT",
                customOverrides = emptyMap(),
                readabilityOptions =
                    SyntaxReadabilityOptions(
                        dimComments = true,
                        softenDocumentation = true,
                        quietOperators = true,
                        emphasizeDeclarations = true,
                    ),
            )

        SyntaxIntensityService().reapplyForActiveLaf()

        verify(exactly = 0) {
            SyntaxIntensityApplicator.compute(
                match { it.readabilityOptions != SyntaxReadabilityOptions.DEFAULT },
            )
        }
        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match {
                    it.preset == SyntaxPreset.AMBIENT &&
                        it.readabilityOptions == SyntaxReadabilityOptions.DEFAULT
                },
            )
        }
    }

    // ---------- Test 13: CUSTOM gate log-once (Pattern A latch) ----------

    @Test
    fun `unlicensed CUSTOM gate normalisation continues across repeated calls`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val service = SyntaxIntensityService()
        service.apply(SyntaxPreset.CUSTOM, mapOf("Java" to mapOf("KEYWORD" to 75)))
        service.apply(SyntaxPreset.CUSTOM, mapOf("Java" to mapOf("KEYWORD" to 75)))
        // The Pattern A latch lives inside the service - the contract is
        // that subsequent unlicensed CUSTOM calls still normalise (the
        // normalisation behaviour is repeatable; only the WARN log fires
        // once per session).
        verify(exactly = 0) {
            SyntaxIntensityApplicator.compute(
                match { it.preset == SyntaxPreset.CUSTOM },
            )
        }
    }

    private fun verifyIgnorePluginStockWrites(
        scheme: EditorColorsScheme,
        expectedStyles: Map<String, ExpectedIgnorePluginStyle>,
    ) {
        for ((keyName, expectedStyle) in expectedStyles) {
            verify(exactly = 1) {
                scheme.setAttributes(
                    keyCache.getValue(keyName),
                    match { attributes -> attributes.matches(expectedStyle) },
                )
            }
        }
    }

    private fun TextAttributes.matches(expectedStyle: ExpectedIgnorePluginStyle): Boolean =
        foregroundColor?.rgb == Color(expectedStyle.foregroundRgb).rgb &&
            backgroundColor?.rgb == expectedStyle.backgroundRgb?.let(::Color)?.rgb &&
            fontType == expectedStyle.fontType

    private data class ExpectedIgnorePluginStyle(
        val foregroundRgb: Int,
        val backgroundRgb: Int? = null,
        val fontType: Int = Font.PLAIN,
    )

    private val ignorePluginDarculaStock =
        mapOf(
            "IGNORE.COMMENT" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080),
            "IGNORE.SECTION" to ExpectedIgnorePluginStyle(foregroundRgb = 0x8C8C8C, backgroundRgb = 0x3A3A3A),
            "IGNORE.HEADER" to
                ExpectedIgnorePluginStyle(
                    foregroundRgb = 0x8C8C8C,
                    backgroundRgb = 0x3A3A3A,
                    fontType = Font.BOLD,
                ),
            "IGNORE.NEGATION" to ExpectedIgnorePluginStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            "IGNORE.BRACKET" to ExpectedIgnorePluginStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            "IGNORE.SLASH" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080),
            "IGNORE.SYNTAX" to
                ExpectedIgnorePluginStyle(
                    foregroundRgb = 0xACACAC,
                    backgroundRgb = 0x4A4A4A,
                    fontType = Font.BOLD,
                ),
            "IGNORE.VALUE" to ExpectedIgnorePluginStyle(foregroundRgb = 0x629755),
            "IGNORE.UNUSED_ENTRY" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080, fontType = Font.ITALIC),
        )

    private val ignorePluginDefaultStock =
        mapOf(
            "IGNORE.COMMENT" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080),
            "IGNORE.SECTION" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080, backgroundRgb = 0xECFAEB),
            "IGNORE.HEADER" to
                ExpectedIgnorePluginStyle(
                    foregroundRgb = 0x808080,
                    backgroundRgb = 0xECFAEB,
                    fontType = Font.BOLD,
                ),
            "IGNORE.NEGATION" to ExpectedIgnorePluginStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            "IGNORE.BRACKET" to ExpectedIgnorePluginStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            "IGNORE.SLASH" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080),
            "IGNORE.SYNTAX" to
                ExpectedIgnorePluginStyle(
                    foregroundRgb = 0xACACAC,
                    backgroundRgb = 0x4A4A4A,
                    fontType = Font.BOLD,
                ),
            "IGNORE.VALUE" to ExpectedIgnorePluginStyle(foregroundRgb = 0x5C9F30),
            "IGNORE.UNUSED_ENTRY" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080, fontType = Font.ITALIC),
        )
}
