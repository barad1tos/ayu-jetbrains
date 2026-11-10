package dev.ayuislands.studio

import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class StudioValuesTest {
    @Test
    fun `source bytes cannot mutate accepted bytes through input or output`() {
        val input = byteArrayOf(1, 2, 3)
        val accepted = StudioBytes(input)
        val expected = StudioBytes(byteArrayOf(1, 2, 3))
        input[0] = 9
        accepted.toByteArray()[1] = 8

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
