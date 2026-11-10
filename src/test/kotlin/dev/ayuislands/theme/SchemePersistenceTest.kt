package dev.ayuislands.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemePersistenceTest {
    @Test
    fun `color persistence retains alpha and treats an explicit null differently from absence`() {
        val color = SchemeCellFacts.Color(plain(0x44123456))
        assertEquals(SchemeStoredCell.Color(0x44123456), project(color))
        assertEquals(SchemeStoredCell.Omitted, project(color, SchemeCellFacts.Color(plain(0x44123456))))
        val marker = SchemeColorFacts.Marker(Any(), SchemeColorMarker.NULL)
        val explicitNull = SchemeCellFacts.Color(marker)
        assertEquals(SchemeStoredCell.NullColor, project(explicitNull))
        assertEquals(SchemeStoredCell.Omitted, project(explicitNull, explicitNull))
        assertEquals(SchemeStoredCell.Color(0x44123456), project(color, explicitNull))
    }

    @Test
    fun `inherited color requires the stored keys fallback and an explicit base cell`() {
        val inherited = SchemeCellFacts.Color(SchemeColorFacts.Marker(Any(), SchemeColorMarker.INHERITED))
        val explicit = SchemeCellFacts.Color(plain(0xFF112233.toInt()))
        assertEquals(SchemeStoredCell.Omitted, project(inherited, explicit))
        assertEquals(SchemeStoredCell.InheritedColor("BASE"), project(inherited, explicit, "BASE"))
        assertEquals(SchemeStoredCell.Omitted, project(inherited, inherited, "BASE"))
        assertEquals(SchemeStoredCell.Omitted, project(inherited, SchemeCellFacts.Color(null), "BASE"))
    }

    @Test
    fun `inherited attributes preserve an empty fallback but still need an explicit base`() {
        val inherited = SchemeCellFacts.InheritedAttributes
        assertEquals(SchemeStoredCell.InheritedAttributes(""), project(inherited, attributes()))
        assertEquals(SchemeStoredCell.InheritedAttributes("BASE"), project(inherited, attributes(), "BASE"))
        assertEquals(SchemeStoredCell.Omitted, project(inherited, inherited))
        assertEquals(SchemeStoredCell.Omitted, project(inherited, SchemeCellFacts.Attributes(null)))
    }

    @Test
    fun `unknown full attribute equality cannot become an omitted or scalar-only receipt`() {
        val before = attributes()
        assertEquals(SchemeStoredCell.Omitted, project(before, before))
        assertNull(project(before, attributes()))
        assertEquals(
            SchemeStoredCell.Attributes(0x123456, null, 0x654321, 0xABCDEF, 3, 2),
            project(before, attributes(fontType = 0)),
        )
    }

    @Test
    fun `native attribute payload stores RGB scalar effects and raw font type`() {
        val effects =
            listOf(
                "BOXED",
                "LINE_UNDERSCORE",
                "WAVE_UNDERSCORE",
                "STRIKEOUT",
                "BOLD_LINE_UNDERSCORE",
                "BOLD_DOTTED_LINE",
            )
        for ((code, effect) in effects.withIndex()) {
            assertEquals(
                SchemeStoredCell.Attributes(0x123456, null, 0x654321, 0xABCDEF, 7, code),
                project(attributes(fontType = 7, effectType = effect)),
            )
        }
        for (effect in listOf(null, "SEARCH_MATCH", "ROUNDED_BOX")) {
            assertEquals(
                SchemeStoredCell.Attributes(0x123456, null, 0x654321, 0xABCDEF, 3, -1),
                project(attributes(effectType = effect)),
            )
        }
    }

    @Test
    fun `opaque cells and colors are never evaluated to manufacture persisted evidence`() {
        val poison = Poison()
        assertNull(project(SchemeCellFacts.Color(SchemeColorFacts.Opaque(poison))))
        val native = attributes().value!!
        val opaque =
            SchemeCellFacts.Attributes(
                SchemeAttributeFacts.Native(
                    Any(),
                    Any(),
                    native.fields.copy(foreground = SchemeColorFacts.Opaque(poison)),
                ),
            )
        assertNull(project(opaque))
        assertNull(project(SchemeCellFacts.Unavailable))
    }

    private fun project(
        local: SchemeCellFacts,
        base: SchemeCellFacts =
            if (local is SchemeCellFacts.Color) {
                SchemeCellFacts.Color(
                    null,
                )
            } else {
                SchemeCellFacts.Attributes(null)
            },
        fallback: String? = null,
    ): SchemeStoredCell? = SchemePersistence.project(local, base, base, fallback)

    private fun attributes(
        fontType: Int = 3,
        effectType: String? = "WAVE_UNDERSCORE",
    ): SchemeCellFacts.Attributes =
        SchemeCellFacts.Attributes(
            SchemeAttributeFacts.Native(
                Any(),
                Any(),
                SchemeAttributeFields(
                    fontType,
                    null,
                    plain(0x44654321),
                    effectType,
                    plain(0x22ABCDEF),
                    plain(0x11123456),
                ),
            ),
        )

    private fun plain(argb: Int): SchemeColorFacts.Plain = SchemeColorFacts.Plain(Any(), argb)

    private class Poison {
        override fun equals(other: Any?): Boolean = error("Foreign equality must not run")

        override fun hashCode(): Int = error("Foreign hash must not run")

        override fun toString(): String = error("Foreign string conversion must not run")
    }
}
