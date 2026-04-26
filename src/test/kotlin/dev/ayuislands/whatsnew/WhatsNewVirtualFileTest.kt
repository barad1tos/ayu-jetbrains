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

    @Test
    fun `equals rejects null and wrong-type instances`() {
        // PR #160 added the `is WhatsNewVirtualFile` type guard for SonarCloud S2097.
        // Locks both branches of that guard so a future revert (or accidental drop)
        // is caught: null and non-matching-type must both produce false WITHOUT
        // identity comparison ever running.
        val file = WhatsNewVirtualFile()
        // Indirect null binding — direct `.equals(null)` trips detekt EqualsNullCall.
        // The Kotlin `==` operator short-circuits to `=== null` for nullable RHS, which
        // would skip the equals override entirely and miss the type-guard branch this
        // test exists to lock. Routing the null through an Any? local forces the
        // override to actually run on a null arg.
        val nullArg: Any? = null
        assertFalse(file.equals(nullArg), "Identity-only equals must reject null")
        assertFalse(
            file.equals("not a virtual file"),
            "Identity-only equals must reject non-WhatsNewVirtualFile types",
        )
        assertFalse(file.equals(Any()), "Identity-only equals must reject Any() too")
    }

    @Test
    fun `hashCode is stable across calls on the same instance`() {
        // Identity-based hashCode (System.identityHashCode) is contractually stable
        // for a single instance. Locks against accidental override that would break
        // FileEditorManager's per-tab map.
        val file = WhatsNewVirtualFile()
        assertEquals(file.hashCode(), file.hashCode())
    }
}
