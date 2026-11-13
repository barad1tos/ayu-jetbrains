package dev.ayuislands.studio

import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class StudioValuesTest {
    @Test
    fun `mixed raw snapshot keeps nested values after source collections change`() {
        val inputBytes = byteArrayOf(0, -1, 127)
        val inputNames = mutableListOf("z", "a", "z")
        val inputValues = mutableListOf(RawValue.FloatBits((-0.0f).toRawBits()), RawValue.Null)
        val inputMap =
            linkedMapOf(
                "flag" to RawValue.BooleanValue(true),
                "integer" to RawValue.Integer(Int.MIN_VALUE),
                "long" to RawValue.LongValue(Long.MAX_VALUE),
                "names" to RawValue.OrderedSet(StudioList(inputNames)),
                "values" to RawValue.OrderedList(StudioList(inputValues)),
                "bytes" to RawValue.Bytes(StudioBytes(inputBytes)),
            )
        val accepted = RawValue.OrderedMap(StudioList(inputMap.map { RawValue.Entry(it.key, it.value) }))
        inputBytes.fill(42)
        inputNames.reverse()
        inputNames.clear()
        inputValues.clear()
        inputMap.clear()

        val expectedValues = StudioList(listOf(RawValue.FloatBits((-0.0f).toRawBits()), RawValue.Null))
        val expected =
            RawValue.OrderedMap(
                StudioList(
                    listOf(
                        RawValue.Entry("flag", RawValue.BooleanValue(true)),
                        RawValue.Entry("integer", RawValue.Integer(Int.MIN_VALUE)),
                        RawValue.Entry("long", RawValue.LongValue(Long.MAX_VALUE)),
                        RawValue.Entry("names", RawValue.OrderedSet(StudioList(listOf("z", "a", "z")))),
                        RawValue.Entry("values", RawValue.OrderedList(expectedValues)),
                        RawValue.Entry("bytes", RawValue.Bytes(StudioBytes(byteArrayOf(0, -1, 127)))),
                    ),
                ),
            )
        assertEquals(expected, accepted)
        assertEquals(expected.hashCode(), accepted.hashCode())
    }

    @Test
    fun `source bytes cannot mutate accepted bytes through input or output`() {
        val input = byteArrayOf(1, 2, 3)
        val accepted = StudioBytes(input)
        val expected = StudioBytes(byteArrayOf(1, 2, 3))
        input[0] = 9
        accepted.toByteArray()[1] = 8

        assertContentEquals(byteArrayOf(1, 2, 3), accepted.toByteArray())
        assertNotEquals(StudioBytes(byteArrayOf(1, 2, 4)), accepted)
        assertEquals(expected, accepted)
        assertEquals(expected.hashCode(), accepted.hashCode())
    }

    @Test
    fun `collection values retain encounter order and reject mutation through a Java view`() {
        val input = mutableListOf("z", "a", "z")
        val accepted = StudioList(input)
        input.reverse()
        input.clear()

        assertEquals(listOf("z", "a", "z"), accepted)
        assertFailsWith<UnsupportedOperationException> { java.util.Collections.reverse(accepted) }
    }

    @Test
    fun `raw equality retains signed zero nan payloads and record order`() {
        assertNotEquals(RawValue.FloatBits(0.0f.toRawBits()), RawValue.FloatBits((-0.0f).toRawBits()))
        assertNotEquals(RawValue.FloatBits(0x7fc00001), RawValue.FloatBits(0x7fc00002))
        val first = RawValue.Entry("z", RawValue.Text(""))
        val second = RawValue.Entry("a", RawValue.Null)
        assertNotEquals(
            RawValue.OrderedMap(StudioList(listOf(first, second))),
            RawValue.OrderedMap(StudioList(listOf(second, first))),
        )
    }
}
