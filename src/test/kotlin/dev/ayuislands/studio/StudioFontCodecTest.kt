package dev.ayuislands.studio

import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.StudioValueXml
import dev.ayuislands.studio.session.StudioFontCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StudioFontCodecTest {
    @Test
    fun `native evidence retains order absent sizes and exact numeric bits through journal values`() {
        val before =
            FontData(
                listOf("Unavailable", "Dialog", "Dialog"),
                listOf(
                    FontFamily("Dialog", -0.0f),
                    FontFamily("Unknown", null),
                    FontFamily("Dialog", Float.fromBits(0x7fc01234)),
                ),
                19.25f,
                Float.fromBits(0x7fc04321),
                true,
                null,
                "",
            )
        val encoded = StudioFontCodec.encode(FontSnapshot.Explicit(before))
        val read = StudioValueXml.readValue(StudioValueXml.writeValue(encoded))
        val restored = assertIs<FontSnapshot.Explicit>(StudioFontCodec.decode(read)).preferences

        assertEquals(before.effectiveFamilies, restored.effectiveFamilies)
        assertEquals(before.families.map { it.name }, restored.families.map { it.name })
        assertEquals(before.families.map { it.size?.toRawBits() }, restored.families.map { it.size?.toRawBits() })
        assertEquals(before.templateSize.toRawBits(), restored.templateSize.toRawBits())
        assertEquals(before.lineSpacing.toRawBits(), restored.lineSpacing.toRawBits())
        assertEquals(before.ligatures, restored.ligatures)
        assertEquals(before.regularSubFamily, restored.regularSubFamily)
        assertEquals(before.boldSubFamily, restored.boldSubFamily)
        assertEquals(encoded, StudioFontCodec.encode(FontSnapshot.Explicit(restored)))
    }

    @Test
    fun `inherited evidence does not become an explicit copy of current global fonts`() {
        val encoded = StudioFontCodec.encode(FontSnapshot.Inherited)
        assertEquals(FontSnapshot.Inherited, StudioFontCodec.decode(encoded))
    }

    @Test
    fun `unknown duplicate or mistyped evidence is rejected instead of replaced with defaults`() {
        val valid = assertIs<RawValue.OrderedMap>(StudioFontCodec.encode(FontSnapshot.Inherited))
        val invalid =
            listOf(
                RawValue.Null,
                RawValue.OrderedMap(StudioList(emptyList())),
                RawValue.OrderedMap(StudioList(valid.entries + valid.entries.first())),
                RawValue.OrderedMap(StudioList(valid.entries + RawValue.Entry("future", RawValue.Null))),
                RawValue.OrderedMap(StudioList(valid.entries.map { it.copy(value = RawValue.Null) })),
                RawValue.OrderedMap(
                    StudioList(
                        valid.entries.map {
                            if (it.key ==
                                "version"
                            ) {
                                it.copy(value = RawValue.Integer(99))
                            } else {
                                it
                            }
                        },
                    ),
                ),
            )

        for (value in invalid) assertFailsWith<IllegalArgumentException> { StudioFontCodec.decode(value) }
    }
}
