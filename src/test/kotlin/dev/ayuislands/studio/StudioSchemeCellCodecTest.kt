package dev.ayuislands.studio

import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioNativeEvidence.record
import dev.ayuislands.studio.session.StudioSchemeCellCodec
import dev.ayuislands.theme.SchemeStoredCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioSchemeCellCodecTest {
    @Test
    fun `all native persisted kinds remain distinct and preserve their payloads`() {
        val values =
            listOf(
                SchemeStoredCell.Omitted,
                SchemeStoredCell.Color(Int.MIN_VALUE),
                SchemeStoredCell.Color(0x44123456),
                SchemeStoredCell.NullColor,
                SchemeStoredCell.InheritedColor("CARET_ROW_COLOR"),
                SchemeStoredCell.InheritedAttributes(""),
                SchemeStoredCell.InheritedAttributes("DEFAULT_KEYWORD"),
                SchemeStoredCell.Attributes(0x123456, null, 0x654321, 0xABCDEF, 7, -1),
                SchemeStoredCell.Attributes(null, 0, null, null, Int.MIN_VALUE, 5),
            )
        for (value in values) {
            assertEquals(value, StudioSchemeCellCodec.decode(StudioSchemeCellCodec.encode(value)))
        }
        assertEquals(values.size, values.map(StudioSchemeCellCodec::encode).toSet().size)
    }

    @Test
    fun `malformed versions kinds fields and scalar values do not become usable recovery evidence`() {
        val color = StudioSchemeCellCodec.encode(SchemeStoredCell.Color(0x123456)) as RawValue.OrderedMap
        val invalid =
            listOf(
                RawValue.Null,
                replace(color, "version", RawValue.Integer(2)),
                replace(color, "kind", RawValue.Text("future-cell")),
                replace(color, "argb", RawValue.LongValue(0x123456)),
                record("version" to RawValue.Integer(1), "kind" to RawValue.Text("color")),
                RawValue.OrderedMap(StudioList(color.entries + RawValue.Entry("argb", RawValue.Integer(0)))),
                RawValue.OrderedMap(StudioList(color.entries + RawValue.Entry("extra", RawValue.Null))),
            )
        for (value in invalid) assertFailsWith<IllegalArgumentException> { StudioSchemeCellCodec.decode(value) }
    }

    @Test
    fun `RGB and effect fields are validated without normalizing a raw native font type`() {
        val attributes = SchemeStoredCell.Attributes(0x123456, null, null, null, 7, 2)
        val value = StudioSchemeCellCodec.encode(attributes) as RawValue.OrderedMap
        for (rgb in listOf(-1, 0x1000000)) {
            assertFailsWith<IllegalArgumentException> {
                StudioSchemeCellCodec.decode(replace(value, "foreground", RawValue.Integer(rgb)))
            }
            assertFailsWith<IllegalArgumentException> {
                StudioSchemeCellCodec.encode(
                    attributes.copy(foregroundRgb = rgb),
                )
            }
        }
        for (effect in listOf(-2, 6)) {
            assertFailsWith<IllegalArgumentException> {
                StudioSchemeCellCodec.decode(replace(value, "effect", RawValue.Integer(effect)))
            }
            assertFailsWith<IllegalArgumentException> {
                StudioSchemeCellCodec.encode(
                    attributes.copy(effectCode = effect),
                )
            }
        }
        assertEquals(7, (StudioSchemeCellCodec.decode(value) as SchemeStoredCell.Attributes).fontType)
    }

    private fun replace(
        value: RawValue.OrderedMap,
        key: String,
        replacement: RawValue,
    ): RawValue =
        RawValue.OrderedMap(
            StudioList(
                value.entries.map {
                    if (it.key ==
                        key
                    ) {
                        it.copy(value = replacement)
                    } else {
                        it
                    }
                },
            ),
        )
}
