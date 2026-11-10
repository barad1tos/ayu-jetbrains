package dev.ayuislands.theme

import org.junit.Test
import kotlin.test.assertEquals

class SchemeComparisonTest {
    @Test
    fun `outer comparisons short circuit identity and either null without foreign callbacks`() {
        val identity = PoisonIdentity()
        val color = SchemeColorFacts.Opaque(identity)
        assertEquals(SchemeEquality.EQUAL, SchemeComparison.colors(color, SchemeColorFacts.Opaque(identity)))
        assertEquals(SchemeEquality.DIFFERENT, SchemeComparison.colors(color, null))
        assertEquals(SchemeEquality.DIFFERENT, SchemeComparison.colors(null, color))
        val attribute = SchemeAttributeFacts.Opaque(identity)
        assertEquals(
            SchemeEquality.EQUAL,
            SchemeComparison.attributes(attribute, SchemeAttributeFacts.Opaque(identity)),
        )
        assertEquals(SchemeEquality.DIFFERENT, SchemeComparison.attributes(attribute, null))
        assertEquals(SchemeEquality.DIFFERENT, SchemeComparison.attributes(null, attribute))
    }

    @Test
    fun `static colors compare captured argb while opaque callbacks remain unknown`() {
        val red = SchemeColorFacts.Plain(PoisonIdentity(), 0xFFFF0000.toInt())
        val same = SchemeColorFacts.Plain(PoisonIdentity(), red.argb)
        val blue = SchemeColorFacts.Plain(PoisonIdentity(), 0xFF0000FF.toInt())
        val opaque = SchemeColorFacts.Opaque(PoisonIdentity())
        assertEquals(SchemeEquality.EQUAL, SchemeComparison.colors(red, same))
        assertEquals(SchemeEquality.DIFFERENT, SchemeComparison.colors(red, blue))
        assertEquals(SchemeEquality.UNKNOWN, SchemeComparison.colors(red, opaque))
        assertEquals(SchemeEquality.UNKNOWN, SchemeComparison.colors(opaque, red))
        assertEquals(SchemeEquality.UNKNOWN, SchemeComparison.colors(opaque, SchemeColorFacts.Opaque(PoisonIdentity())))
    }

    @Test
    fun `marker on the left proves mismatch without evaluating the right`() {
        val marker = SchemeColorFacts.Marker(PoisonIdentity(), SchemeColorMarker.INHERITED)
        val opaque = SchemeColorFacts.Opaque(PoisonIdentity())
        assertEquals(SchemeEquality.DIFFERENT, SchemeComparison.colors(marker, opaque))
        assertEquals(SchemeEquality.UNKNOWN, SchemeComparison.colors(opaque, marker))
    }

    @Test
    fun `shared native flyweight proves equality even with opaque fields and unknown effects`() {
        val flyweight = PoisonIdentity()
        val fields = emptyFields.copy(background = SchemeColorFacts.Opaque(PoisonIdentity()))
        assertEquals(
            SchemeEquality.EQUAL,
            SchemeComparison.attributes(native(fields, flyweight), native(fields, flyweight)),
        )
        assertEquals(SchemeEquality.UNKNOWN, SchemeComparison.attributes(native(fields), native(fields)))
    }

    @Test
    fun `font mismatch precedes opaque fields and field mismatch precedes unknown effects`() {
        val opaque = SchemeColorFacts.Opaque(PoisonIdentity())
        val first = emptyFields.copy(fontType = 1, background = opaque)
        val second = emptyFields.copy(fontType = 2, background = opaque)
        assertEquals(SchemeEquality.DIFFERENT, compareFields(first, second))
        assertEquals(
            SchemeEquality.DIFFERENT,
            compareFields(emptyFields, emptyFields.copy(foreground = opaque)),
        )
    }

    @Test
    fun `field equality preserves native direction for opaque against null`() {
        val opaque = SchemeColorFacts.Opaque(PoisonIdentity())
        val present = emptyFields.copy(background = opaque)
        assertEquals(SchemeEquality.DIFFERENT, compareFields(emptyFields, present))
        assertEquals(SchemeEquality.UNKNOWN, compareFields(present, emptyFields))
        val laterMismatch = emptyFields.copy(effectType = "LINE_UNDERSCORE")
        assertEquals(SchemeEquality.UNKNOWN, compareFields(present, laterMismatch))
    }

    @Test
    fun `effect type precedes stripe and stripe precedes foreground`() {
        val opaque = SchemeColorFacts.Opaque(PoisonIdentity())
        val first = emptyFields.copy(effectType = "LINE_UNDERSCORE", errorStripe = opaque)
        assertEquals(SchemeEquality.DIFFERENT, compareFields(first, emptyFields))
        val stripeFirst = emptyFields.copy(errorStripe = opaque)
        val foregroundLater = emptyFields.copy(foreground = opaque)
        assertEquals(SchemeEquality.UNKNOWN, compareFields(stripeFirst, foregroundLater))
    }

    @Test
    fun `distinct flyweights remain unknown when every supported scalar field matches`() {
        assertEquals(SchemeEquality.UNKNOWN, compareFields(emptyFields, emptyFields))
    }

    @Test
    fun `unqualified attributes do not become comparable through a native peer`() {
        val opaque = SchemeAttributeFacts.Opaque(PoisonIdentity())
        assertEquals(SchemeEquality.UNKNOWN, SchemeComparison.attributes(opaque, native(emptyFields)))
        assertEquals(SchemeEquality.UNKNOWN, SchemeComparison.attributes(native(emptyFields), opaque))
    }

    private fun compareFields(
        parent: SchemeAttributeFields,
        local: SchemeAttributeFields,
    ): SchemeEquality = SchemeComparison.attributes(native(parent), native(local))

    private fun native(
        fields: SchemeAttributeFields,
        flyweight: Any = PoisonIdentity(),
    ): SchemeAttributeFacts = SchemeAttributeFacts.Native(PoisonIdentity(), flyweight, fields)

    private class PoisonIdentity {
        override fun equals(other: Any?): Boolean = error("Foreign equality must not run: ${other === this}")

        override fun hashCode(): Int = error("Foreign hash must not run")
    }

    private companion object {
        val emptyFields = SchemeAttributeFields(0, null, null, null, null, null)
    }
}
