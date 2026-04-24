package dev.ayuislands.accent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Behavior-locked coverage for [AccentHex]. Every production code path
 * that calls `Color.decode` on an [AccentHex] relies on the invariant
 * that a constructed value is always a valid `#RRGGBB` literal — every
 * test here red/green gates that invariant so a future "simplification"
 * cannot silently remove the validation layer.
 */
class AccentHexTest {
    @Test
    fun `of returns AccentHex for a canonical RRGGBB hex`() {
        val result = AccentHex.of("#5CCFE6")
        assertNotNull(result, "valid canonical hex must produce an AccentHex")
        assertEquals("#5CCFE6", result?.value)
    }

    @Test
    fun `of accepts mixed case hex digits`() {
        val result = AccentHex.of("#aAbBcC")
        assertNotNull(result, "mixed-case hex must be accepted")
        assertEquals("#aAbBcC", result?.value)
    }

    @Test
    fun `of trims leading and trailing whitespace before matching`() {
        // Phase 40.2 M-2: a persisted XML with accidental whitespace must not
        // silently fall through to the resolver. trim() is part of the type's
        // canonical form so the stored .value is the bare hex.
        val result = AccentHex.of("  #FFCC66\t")
        assertNotNull(result, "whitespace-padded hex must be accepted after trim")
        assertEquals("#FFCC66", result?.value)
    }

    @Test
    fun `of returns null when the hex is the wrong length`() {
        assertNull(AccentHex.of("#FFF"), "#RGB shorthand must be rejected")
        assertNull(AccentHex.of("#FFCC6"), "5-digit hex must be rejected")
        assertNull(AccentHex.of("#FFCC66FF"), "8-digit (ARGB) hex must be rejected")
    }

    @Test
    fun `of returns null when the hash prefix is missing`() {
        assertNull(AccentHex.of("FFCC66"), "missing hash prefix must be rejected")
    }

    @Test
    fun `of returns null when non-hex characters appear`() {
        assertNull(AccentHex.of("#GGCC66"), "non-hex digit G must be rejected")
        assertNull(AccentHex.of("#FFCC6Z"), "non-hex digit Z must be rejected")
    }

    @Test
    fun `of returns null for null input`() {
        assertNull(AccentHex.of(null), "null input must return null")
    }

    @Test
    fun `of returns null for empty input`() {
        assertNull(AccentHex.of(""), "empty input must return null")
    }

    @Test
    fun `of returns null for a lone hash`() {
        assertNull(AccentHex.of("#"), "# alone must be rejected")
    }

    @Test
    fun `require throws IllegalStateException on invalid input`() {
        // require() uses Kotlin's `error(...)` which throws IllegalStateException.
        // The contract is "a programmer invariant failed" — callers that need
        // argument-validation semantics should use of() and handle null.
        val exception =
            assertThrows(IllegalStateException::class.java) {
                AccentHex.require("#nothex")
            }
        // Message must surface the offending value so user-submitted logs
        // pin down the corrupted source without reverse-engineering.
        assert(exception.message?.contains("#nothex") == true) {
            "require error message must include the raw input for diagnosis; got: ${exception.message}"
        }
    }

    @Test
    fun `require throws on null input`() {
        assertThrows(IllegalStateException::class.java) {
            AccentHex.require(null)
        }
    }

    @Test
    fun `require returns an AccentHex on valid input`() {
        val result = AccentHex.require("#E6B450")
        assertEquals("#E6B450", result.value)
    }

    @Test
    fun `unsafeOf wraps without validation for compile-time literals`() {
        // unsafeOf is the explicit-trust escape hatch for constants like
        // AyuVariant.defaultAccent. Its contract is "the caller has proven
        // the input is well-formed" — validation cost would be redundant.
        // We still trim() so an accidental whitespace-padded literal in
        // source code doesn't leak past the type boundary. The `unsafe`
        // prefix follows the Kotlin convention so IDE completion warns
        // readers this is a bypass, not a sibling of `of`.
        val result = AccentHex.unsafeOf("  #FFCC66  ")
        assertEquals("#FFCC66", result.value)
    }

    @Test
    fun `toColor decodes value into an AWT Color by construction`() {
        val accent = AccentHex.require("#5CCFE6")
        val color = accent.toColor()
        assertEquals(0x5C, color.red)
        assertEquals(0xCF, color.green)
        assertEquals(0xE6, color.blue)
    }

    @Test
    fun `toColor on every Ayu preset produces a non-null Color`() {
        // Regression guard: the canonical preset list feeds the applicator
        // via AccentColor.hex → AccentHex.unsafeOf(...). If any preset
        // string drifts out of #RRGGBB form, this test catches it before
        // the applicator throws on the first painted frame.
        for (preset in AYU_ACCENT_PRESETS) {
            val accent = AccentHex.of(preset.hex)
            assertNotNull(accent, "preset '${preset.name}' hex '${preset.hex}' must be a valid AccentHex")
            val color: Color = accent!!.toColor()
            assertEquals(accent.value.substring(1).toInt(radix = 16), color.rgb and 0x00FFFFFF)
        }
    }
}
