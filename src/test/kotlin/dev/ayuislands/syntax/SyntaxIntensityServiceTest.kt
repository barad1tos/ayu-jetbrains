package dev.ayuislands.syntax

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.JBColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.theme.EditorSchemeChange
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.OverrideWriteResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.awt.Font
import kotlin.coroutines.cancellation.CancellationException
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

/**
 * Orchestration tests for [SyntaxIntensityService]. Reuses the prior
 * service-orchestrator MockK harness - mocks the three named Ayu schemes
 * via `EditorColorsManager.getScheme(...)` plus the active `globalScheme`,
 * verifies a single shared scheme-change publish per `apply()` invocation
 * (R-7), and pins the R-1 fallback + service-layer
 * `CUSTOM` premium gate behaviour through `mockkObject` calls into
 * `RgbBlend` / `LicenseChecker` / `SyntaxIntensityApplicator`.
 */
class SyntaxIntensityServiceTest {
    companion object {
        /**
         * The single key the stubbed loader payload carries. Write assertions match on
         * it so they count real payload writes and stay indifferent to the retired-key
         * pass that also calls `setAttributes`.
         */
        private const val PAYLOAD_KEY_NAME = "K1"
        private const val RETIREMENT_FLAG_KEY = "ayu.syntax.visibility.retired.schemes"

        private val RETIRED_KEY_NAMES =
            listOf(
                "PUBLIC_REFERENCE",
                "PROTECTED_REFERENCE",
                "PACKAGE_PRIVATE_REFERENCE",
                "PRIVATE_REFERENCE",
            )
    }

    private lateinit var mockMirage: EditorColorsScheme
    private lateinit var mockDark: EditorColorsScheme
    private lateinit var mockLight: EditorColorsScheme
    private lateinit var mockManager: EditorColorsManager
    private lateinit var mockApp: Application
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var stateInstance: SyntaxIntensityState
    private lateinit var ayuSettings: AyuIslandsSettings
    private lateinit var ayuState: AyuIslandsState
    private lateinit var props: PropertiesComponent
    private lateinit var overrideCheckpoints: EditorSchemeOverrides.AttributeCheckpoints
    private val keyCache = mutableMapOf<String, TextAttributesKey>()

    @BeforeTest
    fun setUp() {
        keyCache.clear()

        // Default: no scheme has been retired yet, so the one-shot pass fires. Tests
        // that assert the "already retired" branch override this.
        props = mockk(relaxed = true)
        mockkStatic(PropertiesComponent::class)
        every { PropertiesComponent.getInstance() } returns props
        every { props.getList(RETIREMENT_FLAG_KEY) } returns null
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
        every { mockApp.runReadAction(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
        }

        mockkObject(EditorSchemeChange)
        every { EditorSchemeChange.publish() } returns Unit
        mockkObject(EditorSchemeOverrides)
        overrideCheckpoints = mockk()
        every { EditorSchemeOverrides.checkpoints } returns overrideCheckpoints
        every { EditorSchemeOverrides.restore(any(), EditorSchemeOwner.Syntax) } returns Unit
        every { EditorSchemeOverrides.rearm(EditorSchemeOwner.Syntax, any(), any()) } returns Unit
        every {
            overrideCheckpoints.capture(any(), EditorSchemeOwner.Syntax, any())
        } returns mockk(relaxed = true)
        every { overrideCheckpoints.rollback(any()) } returns emptyList()
        every {
            EditorSchemeOverrides.writeAttributes(any(), EditorSchemeOwner.Syntax, any(), any())
        } returns OverrideWriteResult.APPLIED

        loader = mockk(relaxed = true)
        val payload =
            mapOf(
                TextAttributesKey.find(PAYLOAD_KEY_NAME) to
                    TextAttributes().apply {
                        foregroundColor = Color(0xFF, 0xCC, 0x66)
                    },
            )
        for (variant in listOf("Mirage", "Dark", "Light")) {
            every { loader.loadOverlayForVariant(variant) } returns payload
            every { loader.loadBaselineForVariant(variant) } returns payload
            every { loader.fallbacksFor(variant) } returns emptyMap()
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
        every { SyntaxIntensityApplicator.tunableCategories(any(), any(), any()) } returns emptyMap()

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
        // so Mirage is written exactly once (not twice). Counted on the payload key
        // so the retired-key writes don't inflate the total.
        val payloadKey = TextAttributesKey.find(PAYLOAD_KEY_NAME)
        verify(exactly = 1) { mockMirage.setAttributes(payloadKey, any<TextAttributes>()) }
        verify(exactly = 1) { mockDark.setAttributes(payloadKey, any<TextAttributes>()) }
        verify(exactly = 1) { mockLight.setAttributes(payloadKey, any<TextAttributes>()) }
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
        val payloadKey = TextAttributesKey.find(PAYLOAD_KEY_NAME)
        verify(exactly = 2) { mockMirage.setAttributes(payloadKey, any<TextAttributes>()) }
        verify(exactly = 2) { mockDark.setAttributes(payloadKey, any<TextAttributes>()) }
        // Light was never written at all because its scheme was null — not even the
        // retirement pass, which only runs on schemes the service actually resolved.
        verify(exactly = 0) { mockLight.setAttributes(any(), any<TextAttributes>()) }
    }

    // ---------- Test 3: R-7 single publish per apply ----------

    @Test
    fun `apply publishes one shared scheme change per call (R-7)`() {
        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        verify(exactly = 1) { EditorSchemeChange.publish() }
    }

    @Test
    fun `active operator replacement exposes exact font style to range highlighters`() {
        val service = SyntaxIntensityService()
        service.apply(
            SyntaxPresetConfig(
                selectedPreset = SyntaxPreset.CUSTOM.name,
                subordinatePreset = SyntaxPreset.AMBIENT.name,
                customOverrides = emptyMap(),
                customStyles =
                    mapOf(
                        "Swift" to mapOf(PrimitiveCategory.OPERATOR.name to Font.PLAIN),
                    ),
                customEmphasis =
                    mapOf(
                        "Swift" to mapOf(PrimitiveCategory.OPERATOR.name to Font.BOLD),
                    ),
            ),
        )

        assertEquals(
            Font.BOLD,
            service.replacementFontType("Swift", PrimitiveCategory.OPERATOR),
        )

        service.apply(SyntaxPreset.AMBIENT, emptyMap())

        assertNull(service.replacementFontType("Swift", PrimitiveCategory.OPERATOR))
    }

    @Test
    fun `tunable categories return effective map without scheme writes or publication`() {
        val expected = mapOf("Swift" to setOf(PrimitiveCategory.FUNCTION_DECL))
        every { SyntaxIntensityApplicator.tunableCategories(any(), any(), any()) } returns expected
        val service = SyntaxIntensityService()
        service.apply(SyntaxPreset.AMBIENT, emptyMap())
        clearMocks(loader, mockMirage, mockDark, mockLight, answers = false, recordedCalls = true)
        clearMocks(EditorSchemeChange, SyntaxIntensityApplicator, answers = false, recordedCalls = true)

        val result = service.tunableCategories(AyuVariant.MIRAGE)

        assertEquals(expected, result)
        verify(exactly = 0) { EditorSchemeChange.publish() }
        verify(exactly = 0) { loader.loadBaselineForVariant(any()) }
        verify(exactly = 0) { loader.loadOverlayForVariant(any()) }
        verify(exactly = 0) { SyntaxIntensityApplicator.compute(any()) }
        verify(exactly = 0) { SyntaxIntensityApplicator.tunableCategories(any(), any(), any()) }
        verify(exactly = 0) { mockMirage.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 0) { mockDark.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 0) { mockLight.setAttributes(any(), any<TextAttributes>()) }
    }

    @Test
    fun `capabilities include inherited language keys omitted from computed writes`() {
        val inheritedSwiftKey = TextAttributesKey.find("SWIFT.BRACKETS")
        val inheritedSwiftAttributes = TextAttributes()
        val capabilitySnapshots = mutableListOf<List<String>>()
        every { loader.loadBaselineForVariant("Mirage") } returns
            mapOf(
                TextAttributesKey.find(PAYLOAD_KEY_NAME) to TextAttributes(),
                inheritedSwiftKey to inheritedSwiftAttributes,
            )
        every { SyntaxIntensityApplicator.tunableCategories(any(), any(), any()) } answers {
            capabilitySnapshots +=
                firstArg<Map<TextAttributesKey, TextAttributes>>().keys.map { key -> key.externalName }
            emptyMap()
        }

        SyntaxIntensityService().apply(SyntaxPreset.AMBIENT, emptyMap())

        assertTrue(capabilitySnapshots.any { "SWIFT.BRACKETS" in it })
    }

    @Test
    fun `apply restores syntax-owned attributes before writing the current pass`() {
        SyntaxIntensityService().apply(SyntaxPreset.AMBIENT, emptyMap())

        verify(exactly = 1) { EditorSchemeOverrides.restore(mockMirage, EditorSchemeOwner.Syntax) }
        verify(exactly = 1) { EditorSchemeOverrides.restore(mockDark, EditorSchemeOwner.Syntax) }
        verify(exactly = 1) { EditorSchemeOverrides.restore(mockLight, EditorSchemeOwner.Syntax) }
    }

    @Test
    fun `inherited materialization uses exact-restoration ownership instead of a direct write`() {
        val swiftFallback = TextAttributesKey.find("DEFAULT_BRACKETS")
        val swiftBrackets = TextAttributesKey.find("SWIFT.BRACKETS")
        every { swiftBrackets.fallbackAttributeKey } returns swiftFallback
        every { loader.loadBaselineForVariant("Mirage") } returns mapOf(swiftBrackets to TextAttributes())
        every { loader.loadOverlayForVariant("Mirage") } returns emptyMap()
        every { SyntaxIntensityApplicator.compute(any()) } answers {
            val request = firstArg<SyntaxIntensityApplicator.Request>()
            if (request.variantName == "Mirage") {
                mapOf(swiftBrackets to TextAttributes().apply { foregroundColor = Color(0xCC, 0xCA, 0xC2) })
            } else {
                emptyMap()
            }
        }

        SyntaxIntensityService().apply(SyntaxPreset.CUSTOM, emptyMap())

        verify(exactly = 1) {
            EditorSchemeOverrides.writeAttributes(
                mockMirage,
                EditorSchemeOwner.Syntax,
                swiftBrackets,
                any<TextAttributes>(),
            )
        }
        verify(exactly = 0) { mockMirage.setAttributes(swiftBrackets, any<TextAttributes>()) }
        verify(exactly = 1) {
            EditorSchemeOverrides.rearm(
                EditorSchemeOwner.Syntax,
                listOf(mockMirage),
                mapOf(mockMirage to setOf("SWIFT.BRACKETS")),
            )
        }
    }

    @Test
    fun `syntax ownership restore propagates platform cancellation`() {
        every {
            EditorSchemeOverrides.restore(mockMirage, EditorSchemeOwner.Syntax)
        } throws ProcessCanceledException()

        assertFailsWith<ProcessCanceledException> {
            SyntaxIntensityService().apply(SyntaxPreset.AMBIENT, emptyMap())
        }

        verify(exactly = 0) { EditorSchemeChange.publish() }
    }

    @Test
    fun `syntax ownership write propagates platform cancellation`() {
        val swiftFallback = TextAttributesKey.find("DEFAULT_BRACKETS")
        val swiftBrackets = TextAttributesKey.find("SWIFT.BRACKETS")
        every { swiftBrackets.fallbackAttributeKey } returns swiftFallback
        every { loader.loadBaselineForVariant("Mirage") } returns mapOf(swiftBrackets to TextAttributes())
        every { loader.loadOverlayForVariant("Mirage") } returns emptyMap()
        every { SyntaxIntensityApplicator.compute(any()) } answers {
            val request = firstArg<SyntaxIntensityApplicator.Request>()
            if (request.variantName == "Mirage") {
                mapOf(swiftBrackets to TextAttributes())
            } else {
                emptyMap()
            }
        }
        every {
            EditorSchemeOverrides.writeAttributes(
                mockMirage,
                EditorSchemeOwner.Syntax,
                swiftBrackets,
                any(),
            )
        } throws ProcessCanceledException()

        assertFailsWith<ProcessCanceledException> {
            SyntaxIntensityService().apply(SyntaxPreset.CUSTOM, emptyMap())
        }

        verify(exactly = 0) { EditorSchemeChange.publish() }
    }

    @Test
    fun `tunable categories fail open before a variant snapshot exists`() {
        val result = SyntaxIntensityService().tunableCategories(AyuVariant.MIRAGE)

        assertNull(result)
        verify(exactly = 0) { loader.loadBaselineForVariant(any()) }
        verify(exactly = 0) { loader.loadOverlayForVariant(any()) }
        verify(exactly = 0) { SyntaxIntensityApplicator.compute(any()) }
        verify(exactly = 0) { SyntaxIntensityApplicator.tunableCategories(any(), any(), any()) }
        verify(exactly = 0) { EditorSchemeChange.publish() }
    }

    // ---------- Test 4: R-7 delegates read-action handling ----------

    @Test
    fun `apply delegates scheme change without application read action (R-7)`() {
        SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        verify(exactly = 0) { mockApp.runReadAction(any<Runnable>()) }
        verify(exactly = 1) { EditorSchemeChange.publish() }
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
    fun `runtime preview writes only the active Ayu scheme`() {
        val runtime = SyntaxIntensityService().openRuntimeSession()

        runtime.preview(SyntaxPresetConfig(selectedPreset = "WHISPER", customOverrides = emptyMap()))

        val payloadKey = TextAttributesKey.find(PAYLOAD_KEY_NAME)
        verify(exactly = 1) { mockMirage.setAttributes(payloadKey, any<TextAttributes>()) }
        verify(exactly = 0) { mockDark.setAttributes(payloadKey, any<TextAttributes>()) }
        verify(exactly = 0) { mockLight.setAttributes(payloadKey, any<TextAttributes>()) }
        verify(exactly = 1) { EditorSchemeChange.publish() }
    }

    @Test
    fun `runtime preview skips a foreign active scheme without publishing`() {
        val foreign: EditorColorsScheme =
            mockk(relaxed = true) {
                every { name } returns "Solarized"
                every { defaultBackground } returns Color.WHITE
            }
        every { mockManager.globalScheme } returns foreign
        val runtime = SyntaxIntensityService().openRuntimeSession()

        val result =
            runtime.preview(SyntaxPresetConfig(selectedPreset = "WHISPER", customOverrides = emptyMap()))

        assertIs<SyntaxTransactionResult.Applied>(result)
        verify(exactly = 0) { foreign.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 0) { EditorSchemeChange.publish() }
    }

    @Test
    fun `runtime cancel restores retained active checkpoints once`() {
        val runtime = SyntaxIntensityService().openRuntimeSession()
        runtime.preview(SyntaxPresetConfig(selectedPreset = "WHISPER", customOverrides = emptyMap()))

        val result = runtime.restore()

        assertIs<SyntaxTransactionResult.Applied>(result)
        verify(exactly = 1) { overrideCheckpoints.rollback(any()) }
        verify(exactly = 2) { EditorSchemeChange.publish() }
    }

    @Test
    fun `service retains incomplete rollback for recovery before the next apply`() {
        val darkCheckpoint = mockk<EditorSchemeOverrides.AttributesCheckpoint>(relaxed = true)
        every {
            overrideCheckpoints.capture(mockDark, EditorSchemeOwner.Syntax, any())
        } returns darkCheckpoint
        every {
            EditorSchemeOverrides.restore(mockDark, EditorSchemeOwner.Syntax)
        } throws IllegalStateException("write failed") andThen Unit
        every {
            overrideCheckpoints.rollback(darkCheckpoint)
        } returns listOf(IllegalStateException("rollback failed")) andThen emptyList()
        val service = SyntaxIntensityService()

        assertFailsWith<IllegalStateException> {
            service.apply(SyntaxPreset.WHISPER, emptyMap())
        }
        service.apply(SyntaxPreset.WHISPER, emptyMap())

        verify(exactly = 2) { overrideCheckpoints.rollback(darkCheckpoint) }
    }

    @Test
    fun `runtime cancel restores semantic font checkpoint`() {
        val service = SyntaxIntensityService()
        service.apply(
            SyntaxPresetConfig(
                selectedPreset = "CUSTOM",
                customOverrides = emptyMap(),
                customStyles = mapOf("Kotlin" to mapOf("KEYWORD" to Font.BOLD)),
            ),
        )
        val runtime = service.openRuntimeSession()
        runtime.preview(
            SyntaxPresetConfig(
                selectedPreset = "CUSTOM",
                customOverrides = emptyMap(),
                customStyles = mapOf("Kotlin" to mapOf("KEYWORD" to Font.ITALIC)),
            ),
        )

        runtime.restore()

        assertEquals(Font.BOLD, service.replacementFontType("Kotlin", PrimitiveCategory.KEYWORD))
    }

    @Test
    fun `runtime cancellation restores semantic font checkpoint before propagation`() {
        val service = SyntaxIntensityService()
        service.apply(
            SyntaxPresetConfig(
                selectedPreset = "CUSTOM",
                customOverrides = emptyMap(),
                customStyles = mapOf("Kotlin" to mapOf("KEYWORD" to Font.BOLD)),
            ),
        )
        val runtime = service.openRuntimeSession()
        val cancellation = CancellationException("preview cancelled")
        every { EditorSchemeChange.publish() } throws cancellation

        val thrown =
            assertFailsWith<CancellationException> {
                runtime.preview(
                    SyntaxPresetConfig(
                        selectedPreset = "CUSTOM",
                        customOverrides = emptyMap(),
                        customStyles = mapOf("Kotlin" to mapOf("KEYWORD" to Font.ITALIC)),
                    ),
                )
            }

        assertSame(cancellation, thrown)
        assertEquals(Font.BOLD, service.replacementFontType("Kotlin", PrimitiveCategory.KEYWORD))
    }

    @Test
    fun `runtime materialization never runs legacy retirement outside its journal`() {
        val runtime = SyntaxIntensityService().openRuntimeSession()

        runtime.materialize(SyntaxPresetConfig(selectedPreset = "WHISPER", customOverrides = emptyMap()))

        for (keyName in RETIRED_KEY_NAMES) {
            val retiredKey = TextAttributesKey.find(keyName)
            verify(exactly = 0) {
                mockMirage.setAttributes(retiredKey, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                mockDark.setAttributes(retiredKey, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                mockLight.setAttributes(retiredKey, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            }
        }
        verify(exactly = 0) { props.setList(RETIREMENT_FLAG_KEY, any()) }
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

        val payloadKey = TextAttributesKey.find(PAYLOAD_KEY_NAME)
        verify(exactly = 1) { userDerivedScheme.setAttributes(payloadKey, any<TextAttributes>()) }
    }

    // ---------- Test 8: H5 identity dedup (active === named) ----------

    @Test
    fun `H5 identity dedup - Mirage is written exactly once when globalScheme is Mirage`() {
        // Default setUp already wires globalScheme to mockMirage; this test
        // is the explicit assertion of the dedup contract.
        SyntaxIntensityService().apply(SyntaxPreset.AMBIENT, emptyMap())
        val payloadKey = TextAttributesKey.find(PAYLOAD_KEY_NAME)
        verify(exactly = 1) { mockMirage.setAttributes(payloadKey, any<TextAttributes>()) }
    }

    // Atomic scheme writes.

    @Test
    fun `apply rolls back every target and publishes nothing after a write failure`() {
        every { props.getList(RETIREMENT_FLAG_KEY) } returns
            listOf("Ayu Islands Mirage", "Ayu Islands Dark", "Ayu Islands Light")
        val payloadKey = TextAttributesKey.find(PAYLOAD_KEY_NAME)
        every {
            mockMirage.setAttributes(payloadKey, any<TextAttributes>())
        } throws RuntimeException("simulated")

        assertFailsWith<RuntimeException> {
            SyntaxIntensityService().apply(SyntaxPreset.WHISPER, emptyMap())
        }

        verify(exactly = 3) { overrideCheckpoints.rollback(any()) }
        verify(exactly = 0) { mockDark.setAttributes(payloadKey, any<TextAttributes>()) }
        verify(exactly = 0) { mockLight.setAttributes(payloadKey, any<TextAttributes>()) }
        verify(exactly = 0) { EditorSchemeChange.publish() }
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

    // ---------- Retiring keys the overlay no longer owns ----------

    @Test
    fun `apply hands the Java visibility keys back to the platform on every scheme`() {
        SyntaxIntensityService().apply(
            preset = SyntaxPreset.AMBIENT,
            customOverrides = emptyMap(),
            subordinatePreset = SyntaxPreset.AMBIENT,
            customStyles = emptyMap(),
        )

        // A version before the issue #290 fix could bake a foreground for these into a
        // persisted _@user_ scheme. Dropping them from the overlay stops new writes but
        // leaves that value; only the inherited marker clears it.
        for (scheme in listOf(mockMirage, mockDark, mockLight)) {
            for (keyName in RETIRED_KEY_NAMES) {
                val key = TextAttributesKey.find(keyName)
                verify(atLeast = 1) {
                    scheme.setAttributes(key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                }
            }
        }
    }

    @Test
    fun `apply retires the Java visibility keys on a derived active scheme too`() {
        val derived =
            mockk<EditorColorsScheme>(relaxed = true) {
                every { name } returns "_@user_Ayu Islands Dark"
                every { defaultBackground } returns Color(0x0D, 0x10, 0x17)
            }
        every { mockManager.globalScheme } returns derived

        SyntaxIntensityService().apply(
            preset = SyntaxPreset.AMBIENT,
            customOverrides = emptyMap(),
            subordinatePreset = SyntaxPreset.AMBIENT,
            customStyles = emptyMap(),
        )

        // The derived _@user_ copy is the one that survives on disk across upgrades,
        // so it is the scheme that most needs clearing.
        val publicReference = TextAttributesKey.find("PUBLIC_REFERENCE")
        verify(atLeast = 1) {
            derived.setAttributes(publicReference, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        }
    }

    @Test
    fun `apply leaves a scheme's visibility keys alone once it has been retired`() {
        // Past its own pass a scheme's visibility keys belong to the user again: someone
        // who sets Java visibility colours by hand in Settings must keep them.
        every { props.getList(RETIREMENT_FLAG_KEY) } returns
            listOf("Ayu Islands Mirage", "Ayu Islands Dark", "Ayu Islands Light")

        SyntaxIntensityService().apply(
            preset = SyntaxPreset.AMBIENT,
            customOverrides = emptyMap(),
            subordinatePreset = SyntaxPreset.AMBIENT,
            customStyles = emptyMap(),
        )

        for (scheme in listOf(mockMirage, mockDark, mockLight)) {
            for (keyName in RETIRED_KEY_NAMES) {
                val key = TextAttributesKey.find(keyName)
                verify(exactly = 0) {
                    scheme.setAttributes(key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                }
            }
        }
        verify(exactly = 0) { props.setList(RETIREMENT_FLAG_KEY, any<List<String>>()) }
    }

    @Test
    fun `apply retires a scheme that an earlier pass has not reached yet`() {
        // A user who alternated light and dark on 2.8.1 carries a flattened copy of each,
        // so recording one must not seal the others away.
        every { props.getList(RETIREMENT_FLAG_KEY) } returns listOf("Ayu Islands Mirage")
        val publicReference = TextAttributesKey.find("PUBLIC_REFERENCE")

        SyntaxIntensityService().apply(
            preset = SyntaxPreset.AMBIENT,
            customOverrides = emptyMap(),
            subordinatePreset = SyntaxPreset.AMBIENT,
            customStyles = emptyMap(),
        )

        verify(exactly = 0) {
            mockMirage.setAttributes(publicReference, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        }
        verify(atLeast = 1) {
            mockDark.setAttributes(publicReference, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        }
    }

    @Test
    fun `apply records every scheme it repaired so none repeats`() {
        val recorded = slot<List<String>>()

        SyntaxIntensityService().apply(
            preset = SyntaxPreset.AMBIENT,
            customOverrides = emptyMap(),
            subordinatePreset = SyntaxPreset.AMBIENT,
            customStyles = emptyMap(),
        )

        verify(exactly = 1) { props.setList(RETIREMENT_FLAG_KEY, capture(recorded)) }
        assertEquals(
            setOf("Ayu Islands Mirage", "Ayu Islands Dark", "Ayu Islands Light"),
            recorded.captured.toSet(),
        )
    }

    @Test
    fun `retiring the bundled scheme does not seal away its editable copy`() {
        // getScheme("Ayu Islands Dark") resolves the bundled instance by bare name, while
        // the copy that actually persists is "_@user_Ayu Islands Dark". They key
        // separately, so repairing one must leave the other still pending.
        every { props.getList(RETIREMENT_FLAG_KEY) } returns
            listOf("Ayu Islands Mirage", "Ayu Islands Dark", "Ayu Islands Light")
        val editableCopy =
            mockk<EditorColorsScheme>(relaxed = true) {
                every { name } returns "_@user_Ayu Islands Dark"
                every { defaultBackground } returns Color(0x0D, 0x10, 0x17)
            }
        every { mockManager.globalScheme } returns editableCopy
        val publicReference = TextAttributesKey.find("PUBLIC_REFERENCE")
        val recorded = slot<List<String>>()

        SyntaxIntensityService().apply(
            preset = SyntaxPreset.AMBIENT,
            customOverrides = emptyMap(),
            subordinatePreset = SyntaxPreset.AMBIENT,
            customStyles = emptyMap(),
        )

        verify(atLeast = 1) {
            editableCopy.setAttributes(publicReference, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        }
        verify(exactly = 1) { props.setList(RETIREMENT_FLAG_KEY, capture(recorded)) }
        assertTrue("_@user_Ayu Islands Dark" in recorded.captured)
    }

    @Test
    fun `a failed retirement is not recorded so the next apply retries that scheme`() {
        val publicReference = TextAttributesKey.find("PUBLIC_REFERENCE")
        every {
            mockDark.setAttributes(publicReference, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        } throws RuntimeException("simulated")
        val recorded = slot<List<String>>()

        SyntaxIntensityService().apply(
            preset = SyntaxPreset.AMBIENT,
            customOverrides = emptyMap(),
            subordinatePreset = SyntaxPreset.AMBIENT,
            customStyles = emptyMap(),
        )

        // Recording a partial pass would leave that scheme flattened forever.
        verify(exactly = 1) { props.setList(RETIREMENT_FLAG_KEY, capture(recorded)) }
        assertFalse("Ayu Islands Dark" in recorded.captured)
    }

    // ---------- Test 13b: customStyles threaded through to compute ----------

    @Test
    fun `apply threads customStyles through to the applicator compute call`() {
        val styles = mapOf("Java" to mapOf("KEYWORD" to (Font.BOLD or Font.ITALIC)))
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
    fun `apply threads explicit scheme fallbacks through to compute`() {
        val fallbacks = mapOf("SWIFT.BRACKETS" to "DEFAULT_BRACES")
        every { loader.fallbacksFor("Mirage") } returns fallbacks

        SyntaxIntensityService().apply(SyntaxPreset.CUSTOM, emptyMap())

        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match { request -> request.variantName == "Mirage" && request.fallbacks == fallbacks },
            )
        }
    }

    @Test
    fun `apply threads customEmphasis through to the applicator compute call`() {
        val emphasis = mapOf("Kotlin" to mapOf("FUNCTION_DECLARATION" to Font.BOLD))

        SyntaxIntensityService().apply(
            config =
                SyntaxPresetConfig(
                    selectedPreset = "CUSTOM",
                    customOverrides = emptyMap(),
                    customEmphasis = emphasis,
                ),
        )

        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match { request ->
                    request.preset == SyntaxPreset.CUSTOM && request.customEmphasis == emphasis
                },
            )
        }
    }

    @Test
    fun `reapplyForActiveLaf forwards config custom style and emphasis maps to compute`() {
        val styles = mapOf("Kotlin" to mapOf("COMMENT" to Font.ITALIC))
        val emphasis = mapOf("Kotlin" to mapOf("FUNCTION_DECLARATION" to Font.BOLD))
        every { stateInstance.toPresetConfig() } returns
            SyntaxPresetConfig(
                selectedPreset = "CUSTOM",
                customOverrides = emptyMap(),
                customStyles = styles,
                customEmphasis = emphasis,
            )
        SyntaxIntensityService().reapplyForActiveLaf()
        verify(atLeast = 1) {
            SyntaxIntensityApplicator.compute(
                match {
                    it.preset == SyntaxPreset.CUSTOM &&
                        it.customStyles == styles &&
                        it.customEmphasis == emphasis
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
