package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontApplyOrigin
import dev.ayuislands.font.FontIdentityPlan
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSchemeIdentity
import dev.ayuislands.font.FontSchemeLookup
import dev.ayuislands.font.FontSurface
import org.junit.Rule
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FontSchemeCensusTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `identity proposal reads only and leaves raw metadata and dirty flags unchanged`() {
        SwingUtilities.invokeAndWait {
            val scheme = CensusFontScheme()
            scheme.metaProperties.setProperty("foreign", "  user\nЖ\t")
            val before = scheme.metaProperties.toMap()
            val writes = scheme.dirtyWrites
            val census = FontSchemeCensus(arrayOf(scheme))

            assertEquals(FontIdentityPlan.Adopt(FIRST_ID), census.planIdentity(scheme, explicit, FIRST_ID, emptySet()))
            assertEquals(FontSchemeLookup.Missing, census.lookup(FIRST_ID))
            assertEquals(before, scheme.metaProperties.toMap())
            assertEquals(writes, scheme.dirtyWrites)
            assertTrue(census.duplicateIds.isEmpty())
        }
    }

    @Test
    fun `canonical owners survive rename but duplicates and invalid metadata remain unconfirmed`() {
        SwingUtilities.invokeAndWait {
            val scheme = CensusFontScheme()
            scheme.metaProperties.setProperty(KEY, FIRST_ID)
            val census = FontSchemeCensus(arrayOf(scheme))
            scheme.name = "Renamed"
            assertSame(scheme, assertIs<FontSchemeLookup.Unique>(census.lookup(FIRST_ID)).scheme)
            assertEquals(FontIdentityPlan.Existing(FIRST_ID), census.planIdentity(scheme, null, NEXT_ID, emptySet()))
            val copy = CensusFontScheme().apply { metaProperties.setProperty(KEY, FIRST_ID) }
            val duplicate = FontSchemeCensus(arrayOf(scheme, copy))
            assertEquals(setOf(FIRST_ID), duplicate.duplicateIds)
            assertEquals(FontSchemeLookup.Duplicate, duplicate.lookup(FIRST_ID))
            assertEquals(FontIdentityPlan.Unconfirmed, duplicate.planIdentity(scheme, explicit, NEXT_ID, emptySet()))
            for (raw in listOf(FIRST_ID.uppercase(), "bad uuid", "", 42)) {
                scheme.metaProperties[KEY] = raw
                val invalid = FontSchemeCensus(arrayOf(scheme))
                assertEquals(FontIdentityPlan.Unconfirmed, invalid.planIdentity(scheme, explicit, NEXT_ID, emptySet()))
                assertEquals(raw, scheme.metaProperties[KEY])
            }
        }
    }

    @Test
    fun `adoption requires explicit current membership and avoids every reserved surface key`() {
        SwingUtilities.invokeAndWait {
            val scheme = CensusFontScheme()
            val owned = CensusFontScheme().apply { metaProperties.setProperty(KEY, FIRST_ID) }
            val census = FontSchemeCensus(arrayOf(scheme, owned))
            assertEquals(FontIdentityPlan.Missing, census.planIdentity(scheme, null, NEXT_ID, emptySet()))
            assertEquals(
                FontIdentityPlan.Unconfirmed,
                census.planIdentity(scheme, FontApplyOrigin.AUTOMATIC, NEXT_ID, emptySet()),
            )
            assertEquals(FontIdentityPlan.Collision, census.planIdentity(scheme, explicit, FIRST_ID, emptySet()))
            for (surface in FontSurface.entries) {
                val keys = setOf(FontSchemeIdentity.key(NEXT_ID, surface))
                assertEquals(FontIdentityPlan.Collision, census.planIdentity(scheme, explicit, NEXT_ID, keys))
            }
            val replacement = CensusFontScheme()
            assertFalse(census.contains(replacement))
            assertEquals(
                FontIdentityPlan.Unconfirmed,
                census.planIdentity(replacement, explicit, NEXT_ID, emptySet()),
            )
        }
    }

    private companion object {
        const val KEY = "dev.ayuislands.fontOwnershipId"
        const val FIRST_ID = "79a8527b-4aae-4286-b2d3-b49a864831bc"
        const val NEXT_ID = "e891ee55-e651-40be-8be4-f1101de513bf"
        val explicit = FontApplyOrigin.EXPLICIT
    }
}

private class CensusFontScheme : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
    var dirtyWrites = 0

    override fun setSaveNeeded(value: Boolean) {
        dirtyWrites++
        super.setSaveNeeded(value)
    }
}
