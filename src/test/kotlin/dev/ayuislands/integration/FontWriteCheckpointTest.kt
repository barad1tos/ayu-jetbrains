package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.ModifiableFontPreferences
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontPreferenceField
import dev.ayuislands.font.FontRestoreState
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.font.FontWriteCheckpoint
import org.junit.Rule
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontWriteCheckpointTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `native setter returning without copying does not falsely settle rollback`() {
        SwingUtilities.invokeAndWait {
            var ignoreWrites = false
            val scheme =
                object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                    override fun setFontPreferences(preferences: FontPreferences) {
                        if (!ignoreWrites) super.setFontPreferences(preferences)
                    }
                }
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            ignoreWrites = true
            assertFalse(restore(checkpoint))
            assertEquals(requested(), FontData.captureExact(scheme.fontPreferences))
            ignoreWrites = false
            assertTrue(restore(checkpoint))
            assertEquals(original(), FontData.captureExact(scheme.fontPreferences))
        }
    }

    @Test
    fun `foreign listener write after restoration is classified as foreign instead of restored`() {
        SwingUtilities.invokeAndWait {
            var changeSpacing = false
            val baseline = original()
            val scheme =
                object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                    override fun setFontPreferences(preferences: FontPreferences) {
                        super.setFontPreferences(preferences)
                        val actual = super.getFontPreferences() as ModifiableFontPreferences
                        if (changeSpacing && actual.lineSpacing == baseline.lineSpacing) {
                            changeSpacing = false
                            actual.lineSpacing = 1.9f
                        }
                    }
                }
            scheme.fontPreferences = baseline.toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            changeSpacing = true
            assertTrue(restore(checkpoint))
            assertEquals(FontRestoreState.FOREIGN, checkpoint.record.fields[FontPreferenceField.SPACING])
            assertEquals(baseline.copy(lineSpacing = 1.9f), FontData.captureExact(scheme.fontPreferences))
        }
    }

    @Test
    fun `capture does not write and rollback preserves a foreign scalar independently`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            val before = original()
            scheme.fontPreferences = before.toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            assertEquals(before, FontData.capture(scheme.fontPreferences))
            checkpoint.apply()
            (scheme.fontPreferences as ModifiableFontPreferences).lineSpacing = 1.9f
            assertTrue(restore(checkpoint))
            assertEquals(before.copy(lineSpacing = 1.9f), FontData.capture(scheme.fontPreferences))
        }
    }

    @Test
    fun `late setter failure restores the last accepted appearance rather than the managed baseline`() {
        SwingUtilities.invokeAndWait {
            var fail = false
            val scheme =
                object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                    override fun setFontPreferences(preferences: FontPreferences) {
                        super.setFontPreferences(preferences)
                        if (fail) {
                            fail = false
                            error("native setter failed after writing")
                        }
                    }
                }
            val before = original()
            scheme.fontPreferences = before.toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            fail = true
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertTrue(restore(checkpoint))
            assertEquals(before, FontData.capture(scheme.fontPreferences))
            assertTrue(restore(checkpoint))
        }
    }

    @Test
    fun `explicit console rollback does not replace editor preferences`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = requested().toPreferences()
            scheme.consoleFontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.CONSOLE, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            (scheme.consoleFontPreferences as ModifiableFontPreferences).regularSubFamily = "Personal"
            assertTrue(restore(checkpoint))
            assertEquals(
                original().copy(regularSubFamily = "Personal"),
                FontData.capture(scheme.consoleFontPreferences),
            )
            assertEquals(requested(), FontData.capture(scheme.fontPreferences))
        }
    }

    @Test
    fun `interrupted native copy restores a recognized partial family or scalar write`() {
        SwingUtilities.invokeAndWait {
            for (fault in listOf("families", "ligatures")) {
                val preferences = FaultingFontPreferences()
                val scheme =
                    object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                        override fun getFontPreferences(): FontPreferences = preferences

                        override fun setFontPreferences(source: FontPreferences) = source.copyTo(preferences)

                        override fun isUseAppFontPreferencesInEditor(): Boolean = false
                    }
                scheme.fontPreferences = original().toPreferences()
                val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
                preferences.failOn = fault
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                preferences.lineSpacing = 1.9f
                assertTrue(restore(checkpoint))
                assertEquals(original().copy(lineSpacing = 1.9f), FontData.capture(preferences), fault)
            }
        }
    }

    @Test
    fun `interrupted apply restores inherited fonts without flattening them`() {
        SwingUtilities.invokeAndWait {
            val scheme = FaultingFontScheme()
            scheme.setUseAppFontPreferencesInEditor()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            scheme.local.failOn = "families"
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertTrue(restore(checkpoint))
            assertTrue(scheme.isUseAppFontPreferencesInEditor)
        }
    }

    @Test
    fun `interrupted rollback from inheritance remains retryable until the explicit baseline is restored`() {
        SwingUtilities.invokeAndWait {
            val scheme = FaultingFontScheme()
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Inherited)
            checkpoint.apply()
            scheme.local.failOn = "families"
            assertFalse(restore(checkpoint))
            assertTrue(restore(checkpoint))
            assertFalse(scheme.isUseAppFontPreferencesInEditor)
            assertEquals(original(), FontData.capture(scheme.fontPreferences))
        }
    }

    @Test
    fun `rejected preparation cannot claim a foreign value that happens to equal its requested font`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            scheme.fontPreferences = requested().toPreferences()
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertTrue(restore(checkpoint))
            assertEquals(requested(), FontData.capture(scheme.fontPreferences))
        }
    }

    @Test
    fun `unchanged fonts do not invoke a native setter`() {
        SwingUtilities.invokeAndWait {
            var writes = 0
            val scheme =
                object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                    override fun setFontPreferences(preferences: FontPreferences) {
                        writes++
                        super.setFontPreferences(preferences)
                    }
                }
            scheme.fontPreferences = original().toPreferences()
            writes = 0
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(original()))
            checkpoint.apply()
            assertEquals(0, writes)
            assertTrue(restore(checkpoint))
            assertEquals(0, writes)
        }
    }

    @Test
    fun `unrecognized partial inheritance restore stays unresolved instead of reporting success`() {
        assertUnconfirmedRestore(FontSnapshot.Inherited)
    }

    @Test
    fun `unrecognized partial family restore stays unresolved instead of reporting success`() {
        assertUnconfirmedRestore(FontSnapshot.Explicit(requested()))
    }

    @Test
    fun `rollback retains raw fallback sizes instead of flattening them to the template`() {
        SwingUtilities.invokeAndWait {
            for (rawSize in listOf(0f, -2f, Float.fromBits(0x7fc01234))) {
                val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
                val before = original().copy(families = listOf(FontFamily("Dialog", rawSize)))
                scheme.fontPreferences = before.toPreferences()
                val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
                checkpoint.apply()

                assertTrue(restore(checkpoint))

                val sizes = RawFontSizes()
                scheme.fontPreferences.copyTo(sizes)
                assertEquals(rawSize.toRawBits(), sizes.bits["Dialog"])
                (scheme.fontPreferences as ModifiableFontPreferences).setTemplateFontSize(22f)
                assertEquals(
                    if (rawSize.isNaN()) FontPreferences.DEFAULT_FONT_SIZE.toFloat() else 22f,
                    scheme.fontPreferences.getSize2D("Dialog"),
                )
            }
        }
    }

    @Test
    fun `rollback compares the resolved native spacing without rewriting requested intent`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val intent = requested().copy(lineSpacing = 5f)
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(intent))
            checkpoint.apply()
            assertEquals(3f, scheme.fontPreferences.lineSpacing)

            assertTrue(restore(checkpoint))

            assertEquals(original(), FontData.capture(scheme.fontPreferences))
            assertEquals(5f, intent.lineSpacing)
        }
    }

    @Test
    fun `foreign NaN payload is not mistaken for the exact prepared size`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            val before = original().copy(families = listOf(FontFamily("Dialog", Float.fromBits(0x7fc01234))))
            scheme.fontPreferences = before.toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            (scheme.fontPreferences as ModifiableFontPreferences).setFontSize("Dialog", Float.fromBits(0x7fc05678))

            assertFailsWith<IllegalStateException> { checkpoint.apply() }

            assertTrue(restore(checkpoint))
            val sizes = RawFontSizes()
            scheme.fontPreferences.copyTo(sizes)
            assertEquals(0x7fc05678, sizes.bits["Dialog"])
        }
    }

    @Test
    fun `preparation preserves effective-only sizes that native copy cannot restore`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().copy(families = listOf(FontFamily("Dialog", 17.5f))).toPreferences()
            (scheme.fontPreferences as ModifiableFontPreferences).setFontSize("Monospaced", 22.5f)

            assertFailsWith<IllegalStateException> {
                FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            }

            assertEquals(listOf("Dialog"), scheme.fontPreferences.realFontFamilies)
            assertEquals(listOf("Dialog", "Monospaced"), scheme.fontPreferences.effectiveFontFamilies)
            assertEquals(22.5f, scheme.fontPreferences.getSize2D("Monospaced"))
            assertEquals(17.5f, scheme.fontPreferences.getSize2D("Dialog"))
        }
    }

    @Test
    fun `recovered checkpoint restores its original before-state without capturing a new baseline`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            val recovered = FontWriteCheckpoint.recover(scheme, FontSurface.EDITOR, checkpoint.record)

            assertTrue(restore(recovered))
            assertEquals(original(), FontData.captureExact(scheme.fontPreferences))
            assertTrue(restore(recovered))
        }
    }

    @Test
    fun `pending durable evidence may precede a native write that lost its acknowledgement`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            val armed = checkpoint.record
            checkpoint.apply()
            val recovered = FontWriteCheckpoint.recover(scheme, FontSurface.EDITOR, armed)

            assertTrue(restore(recovered))
            assertEquals(original(), FontData.captureExact(scheme.fontPreferences))
        }
    }

    @Test
    fun `recovered inherited rollback reuses its captured restore origin`() {
        SwingUtilities.invokeAndWait {
            val scheme = FaultingFontScheme()
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Inherited)
            checkpoint.apply()
            checkpoint.prepareRestore()
            scheme.local.failOn = "families"
            assertFalse(checkpoint.restore())
            val recorded = checkpoint.record
            assertEquals(1, recorded.origins.size)
            val recovered = FontWriteCheckpoint.recover(scheme, FontSurface.EDITOR, recorded)

            assertTrue(restore(recovered))
            assertEquals(original(), FontData.captureExact(scheme.fontPreferences))
            assertEquals(recorded.origins, recovered.record.origins)
        }
    }

    @Test
    fun `newer overlapping receipt blocks its field while independent groups restore`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()

            checkpoint.prepareRestore(setOf(FontPreferenceField.SPACING))
            assertFalse(checkpoint.restore(setOf(FontPreferenceField.SPACING)))
            assertEquals(
                original().copy(lineSpacing = requested().lineSpacing),
                FontData.captureExact(scheme.fontPreferences),
            )
            assertTrue(restore(checkpoint))
            assertEquals(original(), FontData.captureExact(scheme.fontPreferences))
        }
    }

    @Test
    fun `rejected live write exports settled evidence instead of leaving phantom pending fields`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            scheme.fontPreferences = requested().toPreferences()
            assertFailsWith<IllegalStateException> { checkpoint.apply() }

            assertTrue(restore(checkpoint))
            assertTrue(FontPreferenceField.entries.all { checkpoint.record.fields[it] == FontRestoreState.RESTORED })
            assertEquals(requested(), FontData.captureExact(scheme.fontPreferences))
        }
    }

    @Test
    fun `settled evidence does not read or reclaim a subsequently unavailable native owner`() {
        assertUnavailableRecovery(false)
    }

    @Test
    fun `settled evidence can be recovered when the native owner is already unavailable`() {
        assertUnavailableRecovery(true)
    }

    private fun assertUnavailableRecovery(isAlreadyUnavailable: Boolean) {
        SwingUtilities.invokeAndWait {
            var isUnavailable = false
            val scheme =
                object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                    override fun getFontPreferences(): FontPreferences {
                        check(!isUnavailable) { "Native owner is unavailable" }
                        return super.getFontPreferences()
                    }
                }
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            assertTrue(restore(checkpoint))
            isUnavailable = isAlreadyUnavailable
            val recovered = FontWriteCheckpoint.recover(scheme, FontSurface.EDITOR, checkpoint.record)
            isUnavailable = true

            assertTrue(restore(recovered))
        }
    }

    @Test
    fun `recovered foreign group remains foreign even when a later user value equals the old target`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            (scheme.fontPreferences as ModifiableFontPreferences).lineSpacing = 1.9f
            assertTrue(restore(checkpoint))
            assertEquals(FontRestoreState.FOREIGN, checkpoint.record.fields[FontPreferenceField.SPACING])
            (scheme.fontPreferences as ModifiableFontPreferences).lineSpacing = requested().lineSpacing
            val recovered = FontWriteCheckpoint.recover(scheme, FontSurface.EDITOR, checkpoint.record)

            assertTrue(restore(recovered))
            assertEquals(requested().lineSpacing, scheme.fontPreferences.lineSpacing)
            assertEquals(FontRestoreState.FOREIGN, recovered.record.fields[FontPreferenceField.SPACING])
        }
    }

    @Test
    fun `replacement owner does not settle an overlapping unresolved newer receipt`() {
        SwingUtilities.invokeAndWait {
            var owner = original().toPreferences()
            val scheme =
                object : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
                    override fun getFontPreferences(): FontPreferences = owner

                    override fun setFontPreferences(source: FontPreferences) = source.copyTo(owner)

                    override fun isUseAppFontPreferencesInEditor(): Boolean = false
                }
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            val foreign = requested().copy(lineSpacing = 1.9f)
            owner = foreign.toPreferences()

            checkpoint.prepareRestore(setOf(FontPreferenceField.SPACING))
            assertFalse(checkpoint.restore(setOf(FontPreferenceField.SPACING)))
            assertEquals(foreign, FontData.captureExact(owner))
            assertEquals(null, checkpoint.record.fields[FontPreferenceField.SPACING])
            assertTrue(restore(checkpoint))
            assertEquals(FontRestoreState.FOREIGN, checkpoint.record.fields[FontPreferenceField.SPACING])
            assertEquals(foreign, FontData.captureExact(owner))
        }
    }

    @Test
    fun `prepare captures stable restoration evidence for changed groups without native writes`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()

            checkpoint.prepareRestore()
            val armed = checkpoint.record
            assertEquals(4, armed.origins.size)
            assertEquals(requested(), FontData.captureExact(scheme.fontPreferences))
            checkpoint.prepareRestore()
            assertEquals(armed, checkpoint.record)
            assertTrue(checkpoint.restore())
            assertEquals(original(), FontData.captureExact(scheme.fontPreferences))
        }
    }

    @Test
    fun `armed family rollback stays unresolved after losing the post-write acknowledgement`() {
        SwingUtilities.invokeAndWait {
            val scheme = FaultingFontScheme()
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            checkpoint.prepareRestore()
            val armed = checkpoint.record
            scheme.local.failOn = "families"
            scheme.local.hasUnrecognizedWrite = true
            assertFalse(checkpoint.restore())
            val interrupted = FontData.captureExact(scheme.fontPreferences)
            val recovered = FontWriteCheckpoint.recover(scheme, FontSurface.EDITOR, armed)

            assertFalse(restore(recovered))
            assertEquals(interrupted, FontData.captureExact(scheme.fontPreferences))
        }
    }

    @Test
    fun `foreign and blocked groups do not create restoration evidence`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            (scheme.fontPreferences as ModifiableFontPreferences).lineSpacing = 1.9f

            checkpoint.prepareRestore(setOf(FontPreferenceField.FAMILIES))

            assertEquals(2, checkpoint.record.origins.size)
            assertEquals(FontRestoreState.FOREIGN, checkpoint.record.fields[FontPreferenceField.SPACING])
            assertEquals(requested().copy(lineSpacing = 1.9f), FontData.captureExact(scheme.fontPreferences))
        }
    }

    @Test
    fun `a foreign family edit before the first restore setter is not claimed as our partial prefix`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme)
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
            checkpoint.apply()
            checkpoint.prepareRestore()
            (scheme.fontPreferences as ModifiableFontPreferences).setEffectiveFontFamilies(original().effectiveFamilies)

            assertTrue(checkpoint.restore())

            assertEquals(
                original().copy(families = requested().families, templateSize = requested().templateSize),
                FontData.captureExact(scheme.fontPreferences),
            )
            assertEquals(FontRestoreState.FOREIGN, checkpoint.record.fields[FontPreferenceField.FAMILIES])
        }
    }

    @Test
    fun `changed inherited source needs fresh acknowledged evidence without abandoning the original baseline`() {
        SwingUtilities.invokeAndWait {
            val scheme = FaultingFontScheme()
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Inherited)
            checkpoint.apply()
            checkpoint.prepareRestore()
            val updated = requested().copy(lineSpacing = 1.7f)
            scheme.inheritedSource = updated.toPreferences()

            assertFalse(checkpoint.restore())
            assertTrue(scheme.isUseAppFontPreferencesInEditor)
            checkpoint.prepareRestore()
            assertEquals(2, checkpoint.record.origins.size)
            assertTrue(checkpoint.restore())
            assertEquals(original(), FontData.captureExact(scheme.fontPreferences))
            assertEquals(updated, FontData.captureExact(requireNotNull(scheme.inheritedSource)))
        }
    }

    private fun assertUnconfirmedRestore(target: FontSnapshot) {
        SwingUtilities.invokeAndWait {
            val scheme = FaultingFontScheme()
            scheme.fontPreferences = original().toPreferences()
            val checkpoint = FontWriteCheckpoint(scheme, FontSurface.EDITOR, target)
            checkpoint.apply()
            scheme.local.failOn = "families"
            scheme.local.hasUnrecognizedWrite = true
            assertFalse(restore(checkpoint))
            val unconfirmed = FontData.capture(scheme.fontPreferences)
            assertFalse(restore(checkpoint))
            assertEquals(unconfirmed, FontData.capture(scheme.fontPreferences))
        }
    }

    private fun original(): FontData =
        FontData(
            listOf("Dialog", "Monospaced"),
            listOf(FontFamily("Dialog", 17.5f), FontFamily("Monospaced", null)),
            19.25f,
            1.3f,
            false,
            null,
            "",
        )

    private fun requested(): FontData =
        FontData(listOf("Monospaced"), listOf(FontFamily("Monospaced", 14.5f)), 14.5f, 1.1f, true, "Medium", "")

    private fun restore(checkpoint: FontWriteCheckpoint): Boolean {
        checkpoint.prepareRestore()
        return checkpoint.restore()
    }
}

private class RawFontSizes : FontPreferencesImpl() {
    val bits = mutableMapOf<String, Int>()

    override fun setFontSize(
        fontFamily: String,
        size: Float,
    ) {
        bits[fontFamily] = size.toRawBits()
        super.setFontSize(fontFamily, size)
    }
}

private class FaultingFontPreferences : FontPreferencesImpl() {
    var failOn: String? = null
    var hasUnrecognizedWrite = false

    override fun setRealFontFamilies(fontFamilies: List<String>) {
        super.setRealFontFamilies(fontFamilies)
        failOnce("families")
    }

    override fun setUseLigatures(useLigatures: Boolean) {
        super.setUseLigatures(useLigatures)
        failOnce("ligatures")
    }

    private fun failOnce(field: String) {
        if (failOn != field) return
        failOn = null
        if (hasUnrecognizedWrite) super.setRealFontFamilies(listOf("Unconfirmed"))
        error("native $field setter failed after mutation")
    }
}

private class FaultingFontScheme : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
    val local = FaultingFontPreferences()
    var inheritedSource: FontPreferences? = null
    private var inherited = true

    override fun getFontPreferences(): FontPreferences =
        if (inherited) inheritedSource ?: super.getFontPreferences() else local

    override fun isUseAppFontPreferencesInEditor(): Boolean = inherited

    override fun setUseAppFontPreferencesInEditor() {
        inherited = true
        super.setUseAppFontPreferencesInEditor()
    }

    override fun setFontPreferences(source: FontPreferences) {
        if (inherited) {
            val armed = local.failOn
            local.failOn = null
            fontPreferences.copyTo(local)
            local.failOn = armed
            inherited = false
        }
        source.copyTo(local)
    }
}
