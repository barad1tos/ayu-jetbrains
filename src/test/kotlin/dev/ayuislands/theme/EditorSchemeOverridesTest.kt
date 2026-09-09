package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.IdeSyntaxSchemeWriter
import dev.ayuislands.syntax.SyntaxRecoveryLedger
import dev.ayuislands.syntax.SyntaxSchemeChange
import dev.ayuislands.syntax.SyntaxSchemeTransaction
import dev.ayuislands.syntax.SyntaxTransactionResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorSchemeOverridesTest {
    private val editorColorsManager = mockk<EditorColorsManager>()
    private val colorKey = mockk<ColorKey>()
    private val attributesKey = mockk<TextAttributesKey>()
    private val elementOwner = EditorSchemeOwner.Element(AccentElementId.BRACKET_MATCH)

    @BeforeTest
    fun setUp() {
        AyuEditorSchemeScope.resetClaims()
        mockkObject(AyuVariant.Companion)
        mockkStatic(EditorColorsManager::class)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { EditorColorsManager.getInstance() } returns editorColorsManager
        every { editorColorsManager.allSchemes } answers { arrayOf(editorColorsManager.globalScheme) }
        every { colorKey.externalName } returns "TEST_COLOR"
        every { attributesKey.externalName } returns "TEST_ATTRIBUTES"
    }

    @AfterTest
    fun tearDown() {
        AyuEditorSchemeScope.resetClaims()
        unmockkAll()
    }

    @Test
    fun `repeated color writes restore the first user value`() {
        val colors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        every { editorColorsManager.globalScheme } returns scheme(colors = colors)

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.RED, colors[colorKey])
    }

    @Test
    fun `external color change relinquishes ownership across reapply and restore`() {
        val colors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        every { editorColorsManager.globalScheme } returns scheme(colors = colors)

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        colors[colorKey] = Color.GREEN
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)
        AyuEditorSchemeScope.restore(elementOwner)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.BLUE)

        assertEquals(Color.GREEN, colors[colorKey])
    }

    @Test
    fun `null color baseline remains null after restore`() {
        val colors = mutableMapOf<ColorKey, Color?>()
        every { editorColorsManager.globalScheme } returns scheme(colors = colors)

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        AyuEditorSchemeScope.restore(elementOwner)

        assertNull(colors[colorKey])
    }

    @Test
    fun `inherited color remains inherited after restore`() {
        val colors = mutableMapOf<ColorKey, Color?>()
        val inheritedColors = mutableMapOf(colorKey to Color.RED)
        val scheme = scheme(colors = colors, inheritedColors = inheritedColors)
        every { editorColorsManager.globalScheme } returns scheme

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        AyuEditorSchemeScope.restore(elementOwner)
        inheritedColors[colorKey] = Color.GREEN

        assertFalse(colors.containsKey(colorKey))
        assertEquals(Color.GREEN, scheme.getColor(colorKey))
    }

    @Test
    fun `attribute snapshot is isolated from later mutation of the source object`() {
        val original = fullAttributes(Color.RED)
        val expected = original.clone()
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(attributesKey to original)
        every { editorColorsManager.globalScheme } returns scheme(attributes = attributes)

        AyuEditorSchemeScope.writeAttributes(elementOwner, attributesKey, fullAttributes(Color.ORANGE))
        original.foregroundColor = Color.GREEN
        original.fontType = 0
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(expected, attributes[attributesKey])
    }

    @Test
    fun `external attribute change relinquishes ownership`() {
        val attributes =
            mutableMapOf<TextAttributesKey, TextAttributes?>(
                attributesKey to fullAttributes(Color.RED),
            )
        every { editorColorsManager.globalScheme } returns scheme(attributes = attributes)

        AyuEditorSchemeScope.writeAttributes(elementOwner, attributesKey, fullAttributes(Color.ORANGE))
        val external = fullAttributes(Color.GREEN)
        attributes[attributesKey] = external
        AyuEditorSchemeScope.writeAttributes(elementOwner, attributesKey, fullAttributes(Color.YELLOW))
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(external, attributes[attributesKey])
    }

    @Test
    fun `restore preserves a key edited externally after Ayu acquired it`() {
        val syntaxKey = TextAttributesKey.find("TEST_EXTERNALLY_EDITED_SYNTAX_ATTRIBUTES")
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes)

        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.ORANGE),
        )
        val external = fullAttributes(Color.GREEN)
        attributes[syntaxKey] = external

        EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Syntax)

        assertEquals(external, attributes[syntaxKey])
    }

    @Test
    fun `inherited attributes remain inherited after restore`() {
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>()
        val inheritedAttributes = mutableMapOf(attributesKey to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes, inheritedAttributes = inheritedAttributes)
        every { editorColorsManager.globalScheme } returns scheme

        AyuEditorSchemeScope.writeAttributes(elementOwner, attributesKey, fullAttributes(Color.ORANGE))
        AyuEditorSchemeScope.restore(elementOwner)
        inheritedAttributes[attributesKey] = fullAttributes(Color.GREEN)

        assertFalse(attributes.containsKey(attributesKey))
        assertEquals(inheritedAttributes[attributesKey], scheme.getAttributes(attributesKey))
    }

    @Test
    fun `same-name schemes keep independent original values`() {
        val firstColors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        val secondColors = mutableMapOf<ColorKey, Color?>(colorKey to Color.BLUE)
        val firstScheme = scheme(colors = firstColors)
        val secondScheme = scheme(colors = secondColors)
        var currentScheme = firstScheme
        every { editorColorsManager.globalScheme } answers { currentScheme }

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        currentScheme = secondScheme
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.RED, firstColors[colorKey])
        assertEquals(Color.BLUE, secondColors[colorKey])
    }

    @Test
    fun `only an explicit disable enable transition rearms a relinquished element`() {
        val colors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        every { editorColorsManager.globalScheme } returns scheme(colors = colors)
        AyuEditorSchemeScope.observeElementEnabled(AccentElementId.BRACKET_MATCH, true)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        colors[colorKey] = Color.GREEN
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)

        AyuEditorSchemeScope.observeElementEnabled(AccentElementId.BRACKET_MATCH, true)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.BLUE)
        assertEquals(Color.GREEN, colors[colorKey])

        AyuEditorSchemeScope.observeElementEnabled(AccentElementId.BRACKET_MATCH, false)
        AyuEditorSchemeScope.observeElementEnabled(AccentElementId.BRACKET_MATCH, true)
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.BLUE)
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.GREEN, colors[colorKey])
    }

    @Test
    fun `persisted ownership restores the user value after runtime state is lost`() {
        val colors = mutableMapOf<ColorKey, Color?>(colorKey to Color.RED)
        val scheme = scheme(colors = colors)
        every { editorColorsManager.globalScheme } returns scheme

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        AyuEditorSchemeScope.resetClaims()
        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.YELLOW)
        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(Color.RED, colors[colorKey])
    }

    @Test
    fun `syntax ownership restores exact direct attributes after runtime state is lost`() {
        val syntaxKey = TextAttributesKey.find("TEST_SYNTAX_ATTRIBUTES")
        val original = fullAttributes(Color.RED)
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to original)
        val scheme = scheme(attributes = attributes)

        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.ORANGE),
        )
        EditorSchemeOverrides.reset()
        EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Syntax)

        assertEquals(original, attributes[syntaxKey])
    }

    @Test
    fun `inactive syntax cell rearms after reset without overwriting an active external edit`() {
        val syntaxKey = TextAttributesKey.find("TEST_REARMED_SYNTAX_ATTRIBUTES")
        val original = fullAttributes(Color.RED)
        val external = fullAttributes(Color.GREEN)
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to original)
        val scheme = scheme(attributes = attributes)

        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.ORANGE),
        )
        attributes[syntaxKey] = external
        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.YELLOW),
        )
        EditorSchemeOverrides.rearm(
            EditorSchemeOwner.Syntax,
            listOf(scheme),
            mapOf(scheme to setOf(syntaxKey.externalName)),
        )
        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.BLUE),
        )
        assertEquals(external, attributes[syntaxKey], "an active external edit must retain ownership")

        EditorSchemeOverrides.rearm(
            EditorSchemeOwner.Syntax,
            listOf(scheme),
            mapOf(scheme to emptySet()),
        )
        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.BLUE),
        )
        assertEquals(Color.BLUE, attributes[syntaxKey]?.foregroundColor)

        EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Syntax)
        assertEquals(external, attributes[syntaxKey], "re-added tuning must restore the post-edit user value")
    }

    @Test
    fun `targeted syntax rearm leaves other schemes relinquished`() {
        val syntaxKey = TextAttributesKey.find("TEST_TARGETED_REARM_ATTRIBUTES")
        val firstExternal = fullAttributes(Color.GREEN)
        val secondExternal = fullAttributes(Color.CYAN)
        val firstAttributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to fullAttributes(Color.RED))
        val secondAttributes = mutableMapOf<TextAttributesKey, TextAttributes?>(syntaxKey to fullAttributes(Color.BLUE))
        val firstScheme = scheme(name = "First", attributes = firstAttributes)
        val secondScheme = scheme(name = "Second", attributes = secondAttributes)

        listOf(firstScheme, secondScheme).forEach { scheme ->
            EditorSchemeOverrides.writeAttributes(
                scheme,
                EditorSchemeOwner.Syntax,
                syntaxKey,
                fullAttributes(Color.ORANGE),
            )
        }
        firstAttributes[syntaxKey] = firstExternal
        secondAttributes[syntaxKey] = secondExternal
        listOf(firstScheme, secondScheme).forEach { scheme ->
            EditorSchemeOverrides.writeAttributes(
                scheme,
                EditorSchemeOwner.Syntax,
                syntaxKey,
                fullAttributes(Color.YELLOW),
            )
        }

        EditorSchemeOverrides.rearm(
            EditorSchemeOwner.Syntax,
            listOf(firstScheme),
            mapOf(firstScheme to emptySet()),
        )
        EditorSchemeOverrides.writeAttributes(
            firstScheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.MAGENTA),
        )
        EditorSchemeOverrides.writeAttributes(
            secondScheme,
            EditorSchemeOwner.Syntax,
            syntaxKey,
            fullAttributes(Color.MAGENTA),
        )

        assertEquals(Color.MAGENTA, firstAttributes[syntaxKey]?.foregroundColor)
        assertEquals(secondExternal, secondAttributes[syntaxKey])
    }

    @Test
    fun `syntax checkpoint restores direct values and ownership metadata`() {
        val ownedKey = TextAttributesKey.find("TEST_CHECKPOINT_OWNED_ATTRIBUTES")
        val directKey = TextAttributesKey.find("TEST_CHECKPOINT_DIRECT_ATTRIBUTES")
        val originalOwned = fullAttributes(Color.RED)
        val appliedOwned = fullAttributes(Color.ORANGE)
        val originalDirect = fullAttributes(Color.GREEN)
        val attributes =
            mutableMapOf<TextAttributesKey, TextAttributes?>(
                ownedKey to originalOwned,
                directKey to originalDirect,
            )
        val scheme = scheme(attributes = attributes)
        EditorSchemeOverrides.writeAttributes(
            scheme,
            EditorSchemeOwner.Syntax,
            ownedKey,
            appliedOwned,
        )
        val checkpoint =
            EditorSchemeOverrides.checkpoints.capture(
                scheme,
                EditorSchemeOwner.Syntax,
                setOf(ownedKey, directKey),
            )

        EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Syntax)
        scheme.setAttributes(directKey, fullAttributes(Color.YELLOW))
        val rollbackFailures = EditorSchemeOverrides.checkpoints.rollback(checkpoint)

        assertEquals(emptyList(), rollbackFailures)
        assertEquals(appliedOwned, attributes[ownedKey])
        assertEquals(originalDirect, attributes[directKey])
        assertTrue(EditorSchemeOverrides.hasState(scheme, EditorSchemeOwner.Syntax))

        EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Syntax)
        assertEquals(originalOwned, attributes[ownedKey])
    }

    @Test
    fun `checkpoint rollback finishes remaining entries before propagating cancellation`() {
        val cancelledKey = TextAttributesKey.find("TEST_CHECKPOINT_CANCELLED_ATTRIBUTES")
        val restoredKey = TextAttributesKey.find("TEST_CHECKPOINT_RESTORED_ATTRIBUTES")
        val cancelledOriginal = fullAttributes(Color.RED)
        val restoredOriginal = fullAttributes(Color.GREEN)
        val attributes =
            mutableMapOf<TextAttributesKey, TextAttributes?>(
                cancelledKey to cancelledOriginal,
                restoredKey to restoredOriginal,
            )
        val cancellation = ProcessCanceledException()
        val scheme = scheme(attributes = attributes)
        val checkpoint =
            EditorSchemeOverrides.checkpoints.capture(
                scheme,
                EditorSchemeOwner.Syntax,
                linkedSetOf(cancelledKey, restoredKey),
            )
        scheme.setAttributes(cancelledKey, fullAttributes(Color.ORANGE))
        scheme.setAttributes(restoredKey, fullAttributes(Color.YELLOW))
        every { scheme.setAttributes(cancelledKey, any()) } throws cancellation

        val thrown = assertFails { EditorSchemeOverrides.checkpoints.rollback(checkpoint) }

        assertSame(cancellation, thrown)
        assertEquals(Color.ORANGE, attributes[cancelledKey]?.foregroundColor)
        assertEquals(restoredOriginal, attributes[restoredKey])
    }

    @Test
    fun `editable copy inherits the canonical restoration ledger`() {
        val originalColor = ColorUtil.toAlpha(Color.RED, TEST_ALPHA)
        val canonicalColors = mutableMapOf<ColorKey, Color?>(colorKey to originalColor)
        val canonical = scheme(name = "Ayu Islands Mirage", colors = canonicalColors)
        var current = canonical
        every { editorColorsManager.globalScheme } answers { current }

        AyuEditorSchemeScope.writeColor(elementOwner, colorKey, Color.ORANGE)
        val editableColors = canonicalColors.toMutableMap()
        val editable = scheme(colors = editableColors)
        every { editorColorsManager.allSchemes } returns arrayOf(canonical, editable)
        current = editable

        AyuEditorSchemeScope.restore(elementOwner)

        assertEquals(originalColor, editableColors[colorKey])
    }

    @Test
    fun `cancel preserves manual attributes and does not restore other preview entries`() {
        val manualKey = TextAttributesKey.find("TEST_PREVIEW_MANUAL")
        val otherKey = TextAttributesKey.find("TEST_PREVIEW_OTHER")
        val attributes =
            mutableMapOf<TextAttributesKey, TextAttributes?>(
                manualKey to fullAttributes(Color.RED),
                otherKey to fullAttributes(Color.BLUE),
            )
        val scheme = scheme(attributes = attributes)
        val writer = IdeSyntaxSchemeWriter()
        val ledger = SyntaxRecoveryLedger()
        val change =
            SyntaxSchemeChange(
                scheme,
                "preview",
                mapOf(manualKey to fullAttributes(Color.ORANGE), otherKey to fullAttributes(Color.YELLOW)),
                emptySet(),
            )
        assertIs<SyntaxTransactionResult.Applied>(SyntaxSchemeTransaction(writer) {}.apply(listOf(change), ledger))
        val manual = fullAttributes(Color.GREEN)
        scheme.setAttributes(manualKey, manual)
        val current = attributes.toMap()
        var publications = 0

        repeat(2) {
            assertIs<SyntaxTransactionResult.RecoveryRequired>(ledger.restore(writer) { publications += 1 })
            assertEquals(current, attributes)
            assertTrue(ledger.hasPendingFailure)
        }
        assertEquals(0, publications)
        assertFails { ledger.advance(writer) }
    }

    @Test
    fun `preview publication cannot adopt a subscribers manual edit`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_SUBSCRIBER")
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes)
        val writer = IdeSyntaxSchemeWriter()
        val ledger = SyntaxRecoveryLedger()
        val manual = fullAttributes(Color.GREEN)
        val transaction = SyntaxSchemeTransaction(writer) { scheme.setAttributes(key, manual) }
        val change = SyntaxSchemeChange(scheme, "preview", mapOf(key to fullAttributes(Color.ORANGE)), emptySet())

        assertIs<SyntaxTransactionResult.Applied>(transaction.apply(listOf(change), ledger))
        assertIs<SyntaxTransactionResult.RecoveryRequired>(ledger.restore(writer) {})
        assertEquals(manual, attributes[key])
    }

    @Test
    fun `failed transaction recovery finishes before restoring an older preview`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_MIXED_RECOVERY")
        val other = TextAttributesKey.find("TEST_PREVIEW_FAILED_TRANSACTION")
        val attributes =
            mutableMapOf<TextAttributesKey, TextAttributes?>(
                key to fullAttributes(Color.RED),
                other to fullAttributes(Color.BLUE),
            )
        val scheme = scheme(attributes = attributes)
        val writer = IdeSyntaxSchemeWriter()
        val ledger = SyntaxRecoveryLedger()
        val first = SyntaxSchemeChange(scheme, "preview", mapOf(key to fullAttributes(Color.ORANGE)), emptySet())
        assertIs<SyntaxTransactionResult.Applied>(SyntaxSchemeTransaction(writer) {}.apply(listOf(first), ledger))
        var rollbackBlocked = true
        every { scheme.setAttributes(any(), any()) } answers {
            val target = firstArg<TextAttributesKey>()
            val value = secondArg<TextAttributes>()
            check(target != other || value.foregroundColor != Color.GREEN) { "transaction write failed" }
            check(
                !rollbackBlocked || target != key || value.foregroundColor != Color.ORANGE,
            ) { "transaction rollback failed" }
            attributes[target] = value
        }
        val next =
            SyntaxSchemeChange(
                scheme,
                "failed transaction",
                linkedMapOf(key to fullAttributes(Color.YELLOW), other to fullAttributes(Color.GREEN)),
                emptySet(),
            )
        var publications = 0
        val transaction = SyntaxSchemeTransaction(writer) { publications += 1 }
        assertIs<SyntaxTransactionResult.RecoveryRequired>(transaction.apply(listOf(next), ledger))

        assertIs<SyntaxTransactionResult.RecoveryRequired>(ledger.restore(writer) { publications += 1 })
        assertEquals(fullAttributes(Color.YELLOW), attributes[key])
        assertEquals(fullAttributes(Color.BLUE), attributes[other])
        assertEquals(0, publications)
        assertTrue(ledger.hasPendingFailure)
        assertFails { ledger.advance(writer) }

        rollbackBlocked = false
        assertIs<SyntaxTransactionResult.Applied>(ledger.restore(writer) { publications += 1 })
        assertEquals(fullAttributes(Color.RED), attributes[key])
        assertEquals(fullAttributes(Color.BLUE), attributes[other])
        assertEquals(1, publications)
        assertFalse(ledger.hasRecoveryWork)
    }

    @Test
    fun `preview restores the prior owned value and its original recovery metadata`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_OWNED")
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes)
        EditorSchemeOverrides.writeAttributes(scheme, EditorSchemeOwner.Syntax, key, fullAttributes(Color.ORANGE))
        val metadata = scheme.metaProperties.toMap()
        val writer = IdeSyntaxSchemeWriter()
        val ledger = SyntaxRecoveryLedger()
        val change =
            SyntaxSchemeChange(
                scheme,
                "preview",
                mapOf(key to fullAttributes(Color.YELLOW)),
                setOf(key.externalName),
            )
        assertIs<SyntaxTransactionResult.Applied>(SyntaxSchemeTransaction(writer) {}.apply(listOf(change), ledger))

        assertIs<SyntaxTransactionResult.Applied>(ledger.restore(writer) {})

        assertEquals(fullAttributes(Color.ORANGE), attributes[key])
        assertEquals(metadata, scheme.metaProperties.toMap())
        EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Syntax)
        assertEquals(fullAttributes(Color.RED), attributes[key])
    }

    @Test
    fun `metadata write failure retries without replaying restored attributes`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_METADATA_RETRY")
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes)
        val saved = EditorSchemeOverrides.checkpoints.capture(scheme, EditorSchemeOwner.Syntax, setOf(key))
        EditorSchemeOverrides.writeAttributes(scheme, EditorSchemeOwner.Syntax, key, fullAttributes(Color.ORANGE))
        val preview = EditorSchemeOverrides.checkpoints.sealPreview(saved)
        val metadata = scheme.metaProperties
        val propertyName =
            saved.entries.keys
                .single()
                .metadataKey
        val properties = mockk<Properties>()
        every { scheme.metaProperties } returns properties
        every { properties.getProperty(propertyName) } answers { metadata.getProperty(propertyName) }
        every { properties.remove(propertyName) } throws IllegalStateException("metadata write failed")

        val failed = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(preview))

        assertTrue(failed.failures.isNotEmpty())
        assertEquals(fullAttributes(Color.RED), attributes[key])
        every { scheme.setAttributes(key, any()) } throws
            IllegalStateException("completed attribute write was replayed")
        every { properties.remove(propertyName) } answers { metadata.remove(propertyName) }
        val restored = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(preview))
        assertTrue(restored.failures.isEmpty())
        assertEquals(setOf(preview), restored.completed)
        assertNull(metadata.getProperty(propertyName))
    }

    @Test
    fun `restoring an unchanged preview still publishes semantic restoration once`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_SEMANTIC_RESTORE")
        val value = fullAttributes(Color.RED)
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to value)
        val scheme = scheme(attributes = attributes)
        val writer = IdeSyntaxSchemeWriter()
        val ledger = SyntaxRecoveryLedger()
        val change = SyntaxSchemeChange(scheme, "preview", mapOf(key to value), emptySet())
        SyntaxSchemeTransaction(writer) {}.apply(listOf(change), ledger)
        var restored = false
        var publications = 0

        assertIs<SyntaxTransactionResult.Applied>(
            ledger.restore(writer, afterRollback = { restored = true }) {
                assertTrue(restored)
                publications += 1
            },
        )
        ledger.restore(writer) { publications += 1 }

        assertEquals(1, publications)
        assertEquals(value, attributes[key])
        assertFalse(ledger.hasRecoveryWork)
    }

    @Test
    fun `preview chain restores the first baseline including inherited attributes`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_CHAIN")
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>()
        val scheme =
            scheme(attributes = attributes, inheritedAttributes = mutableMapOf(key to fullAttributes(Color.RED)))
        val first = preview(scheme, mapOf(key to fullAttributes(Color.ORANGE)))
        val second = preview(scheme, mapOf(key to fullAttributes(Color.YELLOW)))

        val result = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(first, second))

        assertTrue(result.failures.isEmpty())
        assertEquals(setOf(first, second), result.completed)
        assertFalse(attributes.containsKey(key))
        assertEquals(fullAttributes(Color.RED), scheme.getAttributes(key))
    }

    @Test
    fun `preview chain does not restore through an intervening manual edit`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_CHAIN_CONFLICT")
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes)
        val first = preview(scheme, mapOf(key to fullAttributes(Color.ORANGE)))
        scheme.setAttributes(key, fullAttributes(Color.GREEN))
        val second = preview(scheme, mapOf(key to fullAttributes(Color.YELLOW)))

        val result = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(first, second))

        assertTrue(result.failures.isNotEmpty())
        assertTrue(result.completed.isEmpty())
        assertEquals(fullAttributes(Color.YELLOW), attributes[key])
    }

    @Test
    fun `preview restore preserves raw ownership metadata conflicts`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_METADATA")
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes)
        val receipt = preview(scheme, mapOf(key to fullAttributes(Color.ORANGE)))
        val metadataKey =
            receipt.original.entries.keys
                .single()
                .metadataKey
        val unknown = "future-version; raw user value  "
        scheme.metaProperties.setProperty(metadataKey, unknown)

        val result = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(receipt))

        assertTrue(result.failures.isNotEmpty())
        assertFalse(result.changed)
        assertEquals(unknown, scheme.metaProperties.getProperty(metadataKey))
        assertEquals(fullAttributes(Color.ORANGE), attributes[key])
    }

    @Test
    fun `partial preview restoration blocks older overlapping checkpoints and retries safely`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_RETRY")
        val other = TextAttributesKey.find("TEST_PREVIEW_INDEPENDENT")
        val attributes =
            mutableMapOf<TextAttributesKey, TextAttributes?>(
                key to fullAttributes(Color.RED),
                other to fullAttributes(Color.BLUE),
            )
        val scheme = scheme(attributes = attributes)
        val first = preview(scheme, mapOf(key to fullAttributes(Color.ORANGE)))
        val second = preview(scheme, mapOf(key to fullAttributes(Color.YELLOW), other to fullAttributes(Color.GREEN)))
        var shouldFail = true
        every { scheme.setAttributes(key, any()) } answers {
            if (shouldFail) error("native attribute write failed")
            attributes[key] = secondArg()
        }

        val failed = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(first, second))

        assertTrue(failed.failures.isNotEmpty())
        assertTrue(failed.completed.isEmpty())
        assertEquals(fullAttributes(Color.YELLOW), attributes[key])
        assertEquals(fullAttributes(Color.BLUE), attributes[other])
        shouldFail = false
        val restored = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(first, second))
        assertTrue(restored.failures.isEmpty())
        assertEquals(setOf(first, second), restored.completed)
        assertEquals(fullAttributes(Color.RED), attributes[key])
    }

    @Test
    fun `retry preserves a manual edit made after partial restoration`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_RETRY_MANUAL")
        val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to fullAttributes(Color.RED))
        val scheme = scheme(attributes = attributes)
        val receipt = preview(scheme, mapOf(key to fullAttributes(Color.ORANGE)))
        every { scheme.setAttributes(key, any()) } throws IllegalStateException("native attribute write failed")
        assertTrue(
            EditorSchemeOverrides.checkpoints
                .restorePreviews(listOf(receipt))
                .failures
                .isNotEmpty(),
        )
        attributes[key] = fullAttributes(Color.GREEN)

        val retry = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(receipt))

        assertTrue(retry.failures.isNotEmpty())
        assertFalse(retry.changed)
        assertEquals(fullAttributes(Color.GREEN), attributes[key])
    }

    @Test
    fun `preview cancellation retains proven writes and finishes independent schemes`() {
        val key = TextAttributesKey.find("TEST_PREVIEW_CANCELLATION")
        val firstAttributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to fullAttributes(Color.RED))
        val secondAttributes = mutableMapOf<TextAttributesKey, TextAttributes?>(key to fullAttributes(Color.BLUE))
        val firstScheme = scheme(attributes = firstAttributes)
        val secondScheme = scheme(attributes = secondAttributes)
        val first = preview(firstScheme, mapOf(key to fullAttributes(Color.ORANGE)))
        val second = preview(secondScheme, mapOf(key to fullAttributes(Color.YELLOW)))
        val cancellation = ProcessCanceledException()
        every { secondScheme.setAttributes(key, any()) } answers {
            secondAttributes[key] = secondArg()
            throw cancellation
        }

        val failed = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(first, second))

        assertSame(cancellation, failed.cancellation)
        assertEquals(setOf(first), failed.completed)
        assertEquals(fullAttributes(Color.RED), firstAttributes[key])
        assertEquals(fullAttributes(Color.BLUE), secondAttributes[key])
        val retry = EditorSchemeOverrides.checkpoints.restorePreviews(listOf(second))
        assertEquals(setOf(second), retry.completed)
        assertNull(retry.cancellation)
        assertFalse(retry.changed)
    }

    private fun preview(
        scheme: EditorColorsScheme,
        attributes: Map<TextAttributesKey, TextAttributes>,
    ): EditorSchemeOverrides.PreviewCheckpoint {
        val saved = EditorSchemeOverrides.checkpoints.capture(scheme, EditorSchemeOwner.Syntax, attributes.keys)
        attributes.forEach { (key, value) -> scheme.setAttributes(key, value) }
        return EditorSchemeOverrides.checkpoints.sealPreview(saved)
    }

    private fun scheme(
        name: String = "_@user_Ayu Islands Mirage",
        colors: MutableMap<ColorKey, Color?> = mutableMapOf(),
        attributes: MutableMap<TextAttributesKey, TextAttributes?> = mutableMapOf(),
        inheritedColors: MutableMap<ColorKey, Color> = mutableMapOf(),
        inheritedAttributes: MutableMap<TextAttributesKey, TextAttributes> = mutableMapOf(),
    ): AbstractColorsScheme =
        mockk<AbstractColorsScheme>(relaxed = true) {
            val metadata = Properties()
            every { this@mockk.name } returns name
            every { metaProperties } returns metadata
            every { directlyDefinedColors } answers {
                colors.mapValues { (_, value) -> value ?: AbstractColorsScheme.NULL_COLOR_MARKER }
            }
            every { directlyDefinedAttributes } answers {
                attributes
                    .mapNotNull { (key, value) ->
                        value?.let { key.externalName to it }
                    }.toMap()
            }
            every { getColor(any()) } answers {
                val key = firstArg<ColorKey>()
                if (colors.containsKey(key)) colors[key] else inheritedColors[key]
            }
            every { setColor(any(), any()) } answers {
                val key = firstArg<ColorKey>()
                val value = secondArg<Color?>()
                if (value === AbstractColorsScheme.INHERITED_COLOR_MARKER) {
                    colors.remove(key)
                } else {
                    colors[key] = value
                }
            }
            every { getAttributes(any<TextAttributesKey>()) } answers {
                val key = firstArg<TextAttributesKey>()
                if (attributes.containsKey(key)) attributes[key] else inheritedAttributes[key]
            }
            every { setAttributes(any(), any()) } answers {
                val key = firstArg<TextAttributesKey>()
                val value = secondArg<TextAttributes?>()
                if (value === AbstractColorsScheme.INHERITED_ATTRS_MARKER) {
                    attributes.remove(key)
                } else {
                    attributes[key] = value
                }
            }
        }

    private fun fullAttributes(foreground: Color): TextAttributes =
        TextAttributes().apply {
            foregroundColor = foreground
            backgroundColor = Color.BLACK
            effectColor = Color.CYAN
            errorStripeColor = Color.MAGENTA
            effectType = EffectType.WAVE_UNDERSCORE
            fontType = 3
        }

    private companion object {
        const val TEST_ALPHA = 96
    }
}
