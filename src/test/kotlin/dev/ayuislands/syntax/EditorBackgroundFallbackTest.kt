package dev.ayuislands.syntax

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RED → GREEN coverage for [RgbBlend.fallbackEditorBgFor]. Verifies the per-variant
 * editor-background fallback constants used when `scheme.defaultBackground` is
 * unusable (R-1 mitigation). Unknown variants must resolve to the Mirage hex —
 * the same safe default the syntax intensity service uses on resolve.
 */
class EditorBackgroundFallbackTest {
    @Test
    fun `fallback for Mirage returns dark Mirage editor hex`() {
        val out = RgbBlend.fallbackEditorBgFor("Mirage")
        assertEquals(0x1F, out.red, "red of #1F2430")
        assertEquals(0x24, out.green, "green of #1F2430")
        assertEquals(0x30, out.blue, "blue of #1F2430")
    }

    @Test
    fun `fallback for Dark returns dark editor hex`() {
        val out = RgbBlend.fallbackEditorBgFor("Dark")
        assertEquals(0x0D, out.red, "red of #0D1017")
        assertEquals(0x10, out.green, "green of #0D1017")
        assertEquals(0x17, out.blue, "blue of #0D1017")
    }

    @Test
    fun `fallback for Light returns light editor hex`() {
        val out = RgbBlend.fallbackEditorBgFor("Light")
        assertEquals(0xFC, out.red, "red of #FCFCFC")
        assertEquals(0xFC, out.green, "green of #FCFCFC")
        assertEquals(0xFC, out.blue, "blue of #FCFCFC")
    }

    @Test
    fun `fallback for unknown variant returns Mirage default`() {
        val out = RgbBlend.fallbackEditorBgFor("Unknown")
        assertEquals(0x1F, out.red, "red defaults to Mirage")
        assertEquals(0x24, out.green, "green defaults to Mirage")
        assertEquals(0x30, out.blue, "blue defaults to Mirage")
    }
}
