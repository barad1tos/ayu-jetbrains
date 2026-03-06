package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class BracketScopeRendererTest {
    @Test
    fun `renderer has CUSTOM position for full gutter width access`() {
        val renderer = BracketScopeRenderer(Color(92, 207, 230), startLine = 5, endLine = 15)
        assertEquals(LineMarkerRendererEx.Position.CUSTOM, renderer.position)
    }

    @Test
    fun `renderer can span single line`() {
        val renderer = BracketScopeRenderer(Color(100, 150, 200), startLine = 10, endLine = 10)
        assertEquals(LineMarkerRendererEx.Position.CUSTOM, renderer.position)
    }

    @Test
    fun `renderer accepts zero-based line numbers`() {
        val renderer = BracketScopeRenderer(Color(255, 204, 102), startLine = 0, endLine = 0)
        assertEquals(LineMarkerRendererEx.Position.CUSTOM, renderer.position)
    }

    @Test
    fun `renderer can be created with any color`() {
        val transparent = Color(0, 0, 0, 0)
        val renderer = BracketScopeRenderer(transparent, startLine = 0, endLine = 100)
        assertEquals(LineMarkerRendererEx.Position.CUSTOM, renderer.position)
    }
}
