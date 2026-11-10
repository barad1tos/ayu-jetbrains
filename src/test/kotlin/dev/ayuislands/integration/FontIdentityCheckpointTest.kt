package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontIdentityCheckpoint
import dev.ayuislands.font.FontIdentityPlan
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontRestoreState
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontWritePhase
import org.junit.Rule
import org.junit.Test
import java.util.Properties
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontIdentityCheckpointTest {
    @get:Rule val applicationRule: ApplicationRule = ApplicationRule()

    @Test
    fun `metadata adoption is capture only and rollback waits for dependent fonts`() {
        SwingUtilities.invokeAndWait {
            val scheme = IdentityFontScheme()
            scheme.metaProperties.setProperty("foreign", "personal\nЖ")
            val checkpoint = checkpoint(scheme)
            assertNull(scheme.metaProperties[KEY])
            assertEquals(FontWritePhase.PREPARED, checkpoint.record.applyPhase)
            checkpoint.apply(census(scheme), emptySet())
            assertEquals(ID, scheme.metaProperties[KEY])
            assertFalse(checkpoint.restore(census(scheme), dependentsSettled = false))
            assertEquals(ID, scheme.metaProperties[KEY])
            assertTrue(checkpoint.restore(census(scheme), dependentsSettled = true))
            assertNull(scheme.metaProperties[KEY])
            assertEquals("personal\nЖ", scheme.metaProperties["foreign"])
        }
    }

    @Test
    fun `mutation then throw on property put or removal remains recoverable`() {
        SwingUtilities.invokeAndWait {
            val scheme = IdentityFontScheme()
            val checkpoint = checkpoint(scheme)
            scheme.properties.failAfterPut = true
            assertFailsWith<IllegalStateException> { checkpoint.apply(census(scheme), emptySet()) }
            assertEquals(ID, scheme.metaProperties[KEY])
            assertEquals(FontWritePhase.WRITING, checkpoint.record.applyPhase)
            scheme.properties.failAfterRemove = true
            assertFalse(checkpoint.restore(census(scheme), dependentsSettled = true))
            assertNull(scheme.metaProperties[KEY])
            assertTrue(checkpoint.restore(census(scheme), dependentsSettled = true))
            assertEquals(FontRestoreState.RESTORED, checkpoint.record.restoreState)
        }
    }

    @Test
    fun `rejected native precondition never removes a foreign equal identity`() {
        SwingUtilities.invokeAndWait {
            val scheme = IdentityFontScheme()
            val checkpoint = checkpoint(scheme)
            scheme.metaProperties.setProperty(KEY, ID)
            assertFailsWith<IllegalStateException> { checkpoint.apply(census(scheme), emptySet()) }
            assertTrue(checkpoint.restore(census(scheme), dependentsSettled = true))
            assertEquals(ID, scheme.metaProperties[KEY])
        }
    }

    @Test
    fun `live replacement and duplicate UUID never inherit another handles restore authority`() {
        SwingUtilities.invokeAndWait {
            val scheme = IdentityFontScheme()
            val checkpoint = checkpoint(scheme)
            checkpoint.apply(census(scheme), emptySet())
            val copy = IdentityFontScheme().apply { metaProperties.setProperty(KEY, ID) }
            assertFalse(checkpoint.restore(census(copy), dependentsSettled = true))
            assertFalse(checkpoint.restore(FontSchemeCensus(arrayOf(scheme, copy)), dependentsSettled = true))
            assertEquals(ID, copy.metaProperties[KEY])
            assertEquals(ID, scheme.metaProperties[KEY])
            scheme.metaProperties[KEY] = "user changed this"
            assertTrue(checkpoint.restore(census(scheme), dependentsSettled = true))
            assertEquals(FontRestoreState.FOREIGN, checkpoint.record.restoreState)
            assertEquals("user changed this", scheme.metaProperties[KEY])
        }
    }

    @Test
    fun `recovery uses saved UUID but an unsaved scheme remains pending until confirmed`() {
        SwingUtilities.invokeAndWait {
            val scheme = EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme).apply { name = "Saved" }
            val before = JDOMUtil.writeElement(scheme.writeScheme())
            val checkpoint = FontIdentityCheckpoint(scheme, FontIdentityPlan.Adopt(ID))
            val pending = checkpoint.record
            checkpoint.apply(FontSchemeCensus(arrayOf(scheme)), emptySet())
            val saved = JDOMUtil.writeElement(scheme.writeScheme())
            val old = EditorColorsSchemeImpl(scheme.parentScheme).apply { readExternal(JDOMUtil.load(before)) }
            val restored = EditorColorsSchemeImpl(scheme.parentScheme).apply { readExternal(JDOMUtil.load(saved)) }
            val unavailable = FontIdentityCheckpoint.recover(FontSchemeCensus(arrayOf(old)), pending)
            assertFalse(unavailable.restore(FontSchemeCensus(arrayOf(old)), dependentsSettled = true))
            assertNull(old.metaProperties[KEY])
            assertTrue(unavailable.restore(FontSchemeCensus(arrayOf(restored)), dependentsSettled = true))
            assertNull(restored.metaProperties[KEY])
        }
    }

    private fun checkpoint(scheme: IdentityFontScheme) = FontIdentityCheckpoint(scheme, FontIdentityPlan.Adopt(ID))

    private fun census(scheme: IdentityFontScheme) = FontSchemeCensus(arrayOf(scheme))

    private companion object {
        const val KEY: String = FontIdentityRules.METADATA_KEY
        const val ID = "11800832-b0dc-44ab-a989-d8d333013fbe"
    }
}

private class IdentityFontScheme : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
    val properties = FaultingIdentityProperties()

    override fun getMetaProperties(): Properties = properties
}

private class FaultingIdentityProperties : Properties() {
    var failAfterPut = false
    var failAfterRemove = false

    override fun put(
        key: Any,
        value: Any,
    ): Any? {
        val previous = super.put(key, value)
        if (failAfterPut) {
            failAfterPut = false
            error("Property put failed after mutation")
        }
        return previous
    }

    override fun remove(key: Any): Any? {
        val previous = super.remove(key)
        if (failAfterRemove) {
            failAfterRemove = false
            error("Property removal failed after mutation")
        }
        return previous
    }
}
