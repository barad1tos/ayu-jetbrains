package dev.ayuislands.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class WhatsNewVirtualFileTest {
    @Test
    fun `file name is What's New in Ayu Islands`() {
        val file = WhatsNewVirtualFile()
        assertEquals("What's New in Ayu Islands", file.name)
    }

    @Test
    fun `file is not writable`() {
        assertFalse(WhatsNewVirtualFile().isWritable)
    }

    @Test
    fun `equals is identity-based`() {
        // Identity equals (each instance is a distinct file) keeps the orchestrator
        // pick logic correct — two instances do not collapse into one tab via
        // FileEditorManager dedup.
        val first = WhatsNewVirtualFile()
        val second = WhatsNewVirtualFile()
        assertEquals(first, first)
        assertNotEquals(first, second)
    }
}
