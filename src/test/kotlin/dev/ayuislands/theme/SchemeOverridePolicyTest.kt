package dev.ayuislands.theme

import dev.ayuislands.theme.EditorSchemeOverrides.OverrideState
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import org.junit.Test
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class SchemeOverridePolicyTest {
    private val baseline = SchemeValue.ColorValue(Color.RED)
    private val previous = SchemeValue.ColorValue(Color.ORANGE)
    private val requested = SchemeValue.ColorValue(Color.PINK)
    private val owned = OverrideState.Owned(EditorSchemeOwner.Syntax, baseline, previous, "raw claim")

    @Test
    fun `a fresh managed claim keeps the captured baseline and requested payload as separate facts`() {
        val plan =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, null, requested, true, baseline, null),
            )
        val state = assertIs<OverrideState.Owned>(plan.state)

        assertSame(requested, plan.value)
        assertEquals(OverrideWriteResult.APPLIED, plan.result)
        assertEquals(EditorSchemeOwner.Syntax, state.owner)
        assertSame(baseline, state.original)
        assertSame(requested, state.lastWritten)
        assertNull(state.encodedMetadata)
    }

    @Test
    fun `owned reapplication preserves the stored baseline while a changed value relinquishes`() {
        val reapplied =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, owned, requested, true, previous, SchemeEquality.EQUAL),
            )
        val updated = assertIs<OverrideState.Owned>(reapplied.state)
        assertNotSame(owned, updated)
        assertSame(baseline, updated.original)
        assertSame(requested, updated.lastWritten)
        assertSame(requested, reapplied.value)
        assertEquals(OverrideWriteResult.APPLIED, reapplied.result)

        val changed =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(
                    EditorSchemeOwner.Syntax,
                    owned,
                    requested,
                    true,
                    previous,
                    SchemeEquality.DIFFERENT,
                ),
            )
        assertNull(changed.value)
        assertEquals(EditorSchemeOwner.Syntax, assertIs<OverrideState.Relinquished>(changed.state).owner)
        assertEquals(OverrideWriteResult.RELINQUISHED, changed.result)
        assertSame(baseline, owned.original)
        assertSame(previous, owned.lastWritten)
        assertEquals("raw claim", owned.encodedMetadata)
    }

    @Test
    fun `retiring an owned key selects its baseline only when the previous write still matches`() {
        for (comparison in listOf(SchemeEquality.EQUAL, SchemeEquality.DIFFERENT)) {
            val plan =
                SchemeOverridePolicy.plan(
                    SchemeOverrideFacts(EditorSchemeOwner.Syntax, owned, null, false, previous, comparison),
                )

            assertSame(if (comparison == SchemeEquality.EQUAL) baseline else null, plan.value)
            assertNull(plan.state)
            assertEquals(OverrideWriteResult.APPLIED, plan.result)
        }
        val ordinary =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(
                    EditorSchemeOwner.Syntax,
                    owned,
                    requested,
                    false,
                    previous,
                    SchemeEquality.DIFFERENT,
                ),
            )
        assertSame(requested, ordinary.value)
        assertNull(ordinary.state)
    }

    @Test
    fun `active relinquished keys keep their exact state while inactive cleanup has no value write`() {
        val state = OverrideState.Relinquished(EditorSchemeOwner.Syntax, "R;S")
        val active =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, state, requested, true, null, null),
            )
        assertNull(active.value)
        assertSame(state, active.state)
        assertEquals(OverrideWriteResult.RELINQUISHED, active.result)

        val retired =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, state, null, false, null, null),
            )
        assertNull(retired.value)
        assertNull(retired.state)
        assertEquals(OverrideWriteResult.APPLIED, retired.result)
    }

    @Test
    fun `unrelated ownership is retained for ordinary writes and protects managed requests`() {
        val unrelated = owned.copy(owner = EditorSchemeOwner.Vcs)
        val ordinary =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, unrelated, requested, false, null, null),
            )
        assertSame(requested, ordinary.value)
        assertSame(unrelated, ordinary.state)
        assertEquals(OverrideWriteResult.APPLIED, ordinary.result)

        val skipped =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, unrelated, requested, true, null, null),
            )
        assertNull(skipped.value)
        assertSame(unrelated, skipped.state)
        assertEquals(OverrideWriteResult.SKIPPED, skipped.result)

        val relinquished = OverrideState.Relinquished(EditorSchemeOwner.Vcs, "R;V")
        val retained =
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, relinquished, requested, true, null, null),
            )
        assertNull(retained.value)
        assertSame(relinquished, retained.state)
        assertEquals(OverrideWriteResult.RELINQUISHED, retained.result)
    }

    @Test
    fun `unknown owned comparison and unavailable fresh baseline cannot select a write`() {
        assertFailsWith<IllegalStateException> {
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, owned, requested, true, previous, SchemeEquality.UNKNOWN),
            )
        }
        assertFailsWith<IllegalStateException> {
            SchemeOverridePolicy.plan(
                SchemeOverrideFacts(EditorSchemeOwner.Syntax, null, requested, true, null, null),
            )
        }
    }
}
