package dev.ayuislands.accent

import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioAccentDefaults
import dev.ayuislands.studio.session.StudioAccentReferences
import java.awt.Color
import java.beans.PropertyChangeListener
import java.time.Instant
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiDefaultsOwnershipTest {
    @Test
    fun `pure writes retain the original value across accent changes`() {
        val original = UiOverrideValue(Color.BLUE)
        val first = planUiOverride(UiOverrideState.Unowned, original, UiOverrideValue(Color.RED))
        val second = planUiOverride(first.next, UiOverrideValue(Color.RED), UiOverrideValue(Color.GREEN))

        assertSame(original, assertIs<UiOverrideState.Owned>(second.next).original)
        assertSame(Color.GREEN, second.write?.raw)
    }

    @Test
    fun `pure release preserves a foreign equal RGB color`() {
        val owned = UiOverrideState.Owned(UiOverrideValue(Color.BLUE), UiOverrideValue(Color.RED))
        val foreign = UiOverrideValue(Color(255, 0, 0))

        val next = planUiOverride(owned, foreign, null)

        assertSame(UiOverrideState.Unowned, next.next)
        assertNull(next.write)
    }

    @Test
    fun `pure automatic refresh relinquishes a foreign value until release`() {
        val owned = UiOverrideState.Owned(UiOverrideValue(Color.BLUE), UiOverrideValue(Color.RED))
        val foreign = UiOverrideValue(Color.YELLOW)
        val lost = planUiOverride(owned, foreign, UiOverrideValue(Color.GREEN))
        val refresh = planUiOverride(lost.next, foreign, UiOverrideValue(Color.CYAN))

        assertSame(UiOverrideState.Relinquished, refresh.next)
        assertNull(refresh.write)
        assertSame(UiOverrideState.Unowned, planUiOverride(refresh.next, foreign, null).next)
    }

    @Test
    fun `completed applies preserve the first baseline until native override is disabled`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            write(owner, KEY, Color.GREEN)

            owner.restore()
            owner.restore()

            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `restored absence follows the current theme instead of the old resolved color`() =
        onEdt { owner ->
            val defaults = UIManager.getLookAndFeelDefaults()
            val original = defaults.put(KEY, Color.BLUE)
            try {
                write(owner, KEY, Color.RED)
                defaults[KEY] = Color.GREEN

                owner.restore()

                assertSame(Color.GREEN, UIManager.get(KEY))
                assertNull(UIManager.put(KEY, null))
            } finally {
                defaults[KEY] = original
            }
        }

    @Test
    fun `native restoration retains lazy providers without evaluating them`() =
        onEdt { owner ->
            var evaluations = 0
            val original =
                UIDefaults.LazyValue {
                    evaluations++
                    Color.BLUE
                }
            UIManager.put(KEY, original)
            write(owner, KEY, Color.RED)

            owner.restore()

            assertEquals(0, evaluations)
            assertSame(UIManager.put(KEY, null), original)
        }

    @Test
    fun `foreign custom colors survive automatic refresh and disabling override`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            val foreign = Color(255, 0, 0)
            UIManager.put(KEY, foreign)
            write(owner, KEY, Color.GREEN)

            owner.restore()

            assertSame(UIManager.get(KEY), foreign)
            write(owner, KEY, Color.YELLOW)
            owner.restore()
            assertSame(UIManager.get(KEY), foreign)
        }

    @Test
    fun `a reentrant foreign write during accent application is preserved`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val foreign = Color.MAGENTA
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.RED) UIManager.put(KEY, foreign)
                }
            withListener(listener) { write(owner, KEY, Color.RED) }

            owner.restore()

            assertSame(foreign, UIManager.get(KEY))
        }

    @Test
    fun `an aborted apply restores earlier ownership for the next disable`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            owner.put(KEY, Color.GREEN, checkpoint)
            val failure = CancellationException("accent apply cancelled")

            assertSame(
                failure,
                assertFailsWith<CancellationException> {
                    restoringScope(checkpoint).restoringOnFailure { throw failure }
                },
            )
            assertSame(Color.RED, UIManager.get(KEY))
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `a cancelled first apply leaves no stale claim over the restored native color`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            owner.put(KEY, Color.RED, checkpoint)
            checkpoint.restore()
            val foreign = Color.CYAN
            UIManager.put(KEY, foreign)

            owner.restore()

            assertSame(foreign, UIManager.get(KEY))
        }

    @Test
    fun `restoration attempts other keys when a property listener fails`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.GREEN)
            write(owner, KEY, Color.RED)
            write(owner, OTHER_KEY, Color.RED)
            val failure = IllegalStateException("native control listener failed")
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.BLUE) throw failure
                }

            withListener(listener) {
                assertSame(failure, assertFailsWith<IllegalStateException> { owner.restore() })
            }
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
            assertSame(Color.GREEN, UIManager.get(OTHER_KEY))
        }

    @Test
    fun `restoration reobserves later keys changed by an earlier native listener`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.GREEN)
            write(owner, KEY, Color.RED)
            write(owner, OTHER_KEY, Color.RED)
            val foreign = Color.MAGENTA
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.BLUE) UIManager.put(OTHER_KEY, foreign)
                }

            withListener(listener) { owner.restore() }

            assertSame(Color.BLUE, UIManager.get(KEY))
            assertSame(foreign, UIManager.get(OTHER_KEY))
        }

    @Test
    fun `cleanup cancellation preserves its cause and still restores remaining controls`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.GREEN)
            write(owner, KEY, Color.RED)
            write(owner, OTHER_KEY, Color.RED)
            val cancellation = CancellationException("native control cancelled")
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.BLUE) throw cancellation
                }

            withListener(listener) {
                assertSame(cancellation, assertFailsWith<CancellationException> { owner.restore() })
            }
            assertSame(Color.BLUE, UIManager.get(KEY))
            assertSame(Color.GREEN, UIManager.get(OTHER_KEY))
            owner.restore()
        }

    @Test
    fun `native opt out keeps explicit tab colors independently for each mode`() {
        for (mode in GlowTabMode.entries) {
            val policy = accentUiPolicy(external = true, overrideEnabled = false, chromeAllowed = true, tabMode = mode)
            val palette = accentUiPalette(AccentUiInput(Color.RED, null, mode, Color.WHITE.rgb, policy))
            assertFalse("Button.default.startBackground" in palette)
            assertFalse("Component.focusColor" in palette)
            assertEquals(mode != GlowTabMode.OFF, AccentUiKeys.TAB_UNDERLINE in palette)
            assertEquals(mode != GlowTabMode.OFF, policy.overrideTabGeometry)
            assertTrue(
                accentUiPalette(
                    AccentUiInput(
                        Color.RED,
                        null,
                        mode,
                        Color.WHITE.rgb,
                        accentUiPolicy(external = true, overrideEnabled = false, chromeAllowed = false, tabMode = mode),
                    ),
                ).isEmpty(),
            )
            val ayu =
                accentUiPalette(
                    AccentUiInput(
                        Color.RED,
                        Color.GRAY,
                        mode,
                        Color.WHITE.rgb,
                        accentUiPolicy(
                            external = false,
                            overrideEnabled = false,
                            chromeAllowed = false,
                            tabMode = mode,
                        ),
                    ),
                )
            assertEquals(Color.RED.rgb, ayu["Component.focusColor"])
            assertEquals(
                if (mode ==
                    GlowTabMode.OFF
                ) {
                    Color.GRAY.rgb
                } else {
                    Color.RED.rgb
                },
                ayu[AccentUiKeys.TAB_UNDERLINE],
            )
        }
    }

    @Test
    fun `prepared ownership has no effect before acknowledgement and retains the native baseline`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            val prepared = requireNotNull(owner.prepare(KEY, UiOverrideValue(Color.RED), checkpoint))
            assertNull(owner.observeClaim(KEY))

            assertSame(Color.BLUE, UIManager.get(KEY))
            assertFalse(owner.ownsAny(setOf(KEY)))
            assertTrue(owner.apply(prepared))
            assertSame(prepared.transition.next, owner.observeClaim(KEY))
            assertFalse(owner.apply(prepared))
            assertTrue(owner.ownsAny(setOf(KEY)))
            checkpoint.commit()
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `prepared release restores absence only after acknowledgement and rollback restores the old claim`() =
        onEdt { owner ->
            write(owner, KEY, Color.RED)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            val release = requireNotNull(owner.prepare(KEY, null, checkpoint))

            assertSame(Color.RED, UIManager.get(KEY))
            assertTrue(owner.ownsAny(setOf(KEY)))
            assertTrue(owner.apply(release))
            assertNull(readRawUiDefault(UIManager.getDefaults(), KEY))
            assertFalse(owner.ownsAny(setOf(KEY)))
            checkpoint.restore()
            assertSame(Color.RED, UIManager.get(KEY))
            assertTrue(owner.ownsAny(setOf(KEY)))
            owner.restore()
            assertNull(readRawUiDefault(UIManager.getDefaults(), KEY))
        }

    @Test
    fun `stale prepared release cannot discard a newer owned value`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            val checkpoint = UiDefaultsCheckpoint()
            val release = requireNotNull(owner.prepare(KEY, null, checkpoint))
            write(owner, KEY, Color.GREEN)

            assertFalse(owner.apply(release))
            assertSame(Color.GREEN, UIManager.get(KEY))
            assertTrue(owner.ownsAny(setOf(KEY)))
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
            assertFalse(owner.apply(release))
        }

    @Test
    fun `foreign raw drift rejects the prepared write and stays relinquished until explicit release`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            val prepared = requireNotNull(owner.prepare(KEY, UiOverrideValue(Color.GREEN), checkpoint))
            val foreign = Color(255, 0, 0)
            UIManager.put(KEY, foreign)

            assertFalse(owner.apply(prepared))
            write(owner, KEY, Color.YELLOW)
            assertSame(expected = foreign, actual = UIManager.get(KEY))
            assertFalse(owner.ownsAny(setOf(KEY)))
            val release = requireNotNull(owner.prepare(KEY, null, checkpoint))
            var events = 0
            withListener({ event -> if (event.propertyName == KEY) events++ }) {
                assertTrue(owner.apply(release))
            }
            assertEquals(0, events)
            checkpoint.commit()
            write(owner, KEY, Color.YELLOW)
            owner.restore()
            assertSame(expected = foreign, actual = UIManager.get(KEY))
        }

    @Test
    fun `unused prepared writes and observations expire with their checkpoint`() =
        onEdt { owner ->
            for (commit in listOf(false, true)) {
                for (requested in listOf(null, UiOverrideValue(Color.RED))) {
                    val checkpoint = UiDefaultsCheckpoint()
                    owner.record(checkpoint)
                    val prepared = requireNotNull(owner.prepare(KEY, requested, checkpoint))
                    if (commit) checkpoint.commit() else checkpoint.restore()
                    assertFalse(owner.apply(prepared))
                    assertFalse(owner.ownsAny(setOf(KEY)))
                    assertNull(readRawUiDefault(UIManager.getDefaults(), KEY))
                }
            }
        }

    @Test
    fun `prepared release over an absent value has no native event unlike an explicit null write`() =
        onEdt { owner ->
            val checkpoint = UiDefaultsCheckpoint()
            val release = requireNotNull(owner.prepare(KEY, null, checkpoint))
            var events = 0
            withListener({ event -> if (event.propertyName == KEY) events++ }) {
                assertTrue(owner.apply(release))
                assertEquals(0, events)
                checkpoint.put(KEY, null)
                assertEquals(1, events)
            }
            checkpoint.restore()
        }

    @Test
    fun `prepared writes retain rollback ownership when a native listener throws`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            val prepared = requireNotNull(owner.prepare(KEY, UiOverrideValue(Color.GREEN), checkpoint))
            val failure = IllegalStateException("listener failed after native mutation")
            withListener({ event ->
                if (event.propertyName == KEY && event.newValue === Color.GREEN) throw failure
            }) {
                assertSame(failure, assertFailsWith<IllegalStateException> { owner.apply(prepared) })
            }
            assertSame(Color.GREEN, UIManager.get(KEY))
            assertTrue(owner.ownsAny(setOf(KEY)))
            assertFalse(owner.apply(prepared))
            checkpoint.restore()
            assertSame(Color.RED, UIManager.get(KEY))
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `a newer claim installed by a native listener survives prepared settlement`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            val prepared = requireNotNull(owner.prepare(KEY, UiOverrideValue(Color.RED), checkpoint))
            withListener({ event ->
                if (event.propertyName == KEY && event.newValue === Color.RED) write(owner, KEY, Color.GREEN)
            }) {
                assertFalse(owner.apply(prepared))
            }
            assertSame(Color.GREEN, UIManager.get(KEY))
            assertTrue(owner.ownsAny(setOf(KEY)))
            checkpoint.restore()
            assertSame(Color.GREEN, UIManager.get(KEY))
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `a prepared lazy release retains raw provider identity without evaluation`() =
        onEdt { owner ->
            var evaluations = 0
            val original =
                UIDefaults.LazyValue {
                    evaluations++
                    Color.BLUE
                }
            UIManager.put(KEY, original)
            write(owner, KEY, Color.RED)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            val release = requireNotNull(owner.prepare(KEY, null, checkpoint))
            assertEquals(0, evaluations)
            assertTrue(owner.apply(release))
            assertEquals(0, evaluations)
            assertSame(original, readRawUiDefault(UIManager.getDefaults(), KEY))
            checkpoint.restore()
            assertSame(Color.RED, UIManager.get(KEY))
            owner.restore()
            assertEquals(0, evaluations)
            assertSame(original, readRawUiDefault(UIManager.getDefaults(), KEY))
        }

    @Test
    fun `a foreign owner cannot consume or apply a prepared claim`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            val prepared = requireNotNull(owner.prepare(KEY, UiOverrideValue(Color.RED), checkpoint))
            val other = UiDefaultsOwnership()
            assertFalse(other.apply(prepared))
            assertSame(Color.BLUE, UIManager.get(KEY))
            assertTrue(owner.apply(prepared))
            checkpoint.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `portable blocker defers claim-only rollback until its newer ownership settles`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            UIManager.put(KEY, Color.YELLOW)
            write(owner, KEY, Color.GREEN)
            assertSame(UiOverrideState.Relinquished, owner.observeClaim(KEY))
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            val release = requireNotNull(owner.prepare(KEY, null, checkpoint))
            assertTrue(owner.apply(release))
            assertSame(UiOverrideState.Unowned, owner.observeClaim(KEY))
            assertFailsWith<IllegalStateException> { checkpoint.restoreFields(setOf(KEY)) }
            assertSame(UiOverrideState.Unowned, owner.observeClaim(KEY))
            assertTrue(checkpoint.hasPending)
            checkpoint.restoreFields(emptySet())
            assertSame(UiOverrideState.Relinquished, owner.observeClaim(KEY))
            assertFalse(checkpoint.hasPending)
            assertSame(Color.YELLOW, UIManager.get(KEY))
        }

    @Test
    fun `scoped ownership rollback leaves unrelated user release available for a later change`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.GREEN)
            write(owner, KEY, Color.RED)
            write(owner, OTHER_KEY, Color.ORANGE)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint, setOf(KEY))
            owner.put(KEY, Color.YELLOW, checkpoint)
            owner.restore(setOf(OTHER_KEY))
            checkpoint.restore()
            assertSame(Color.RED, UIManager.get(KEY))
            assertSame(UiOverrideState.Unowned, owner.observeClaim(OTHER_KEY))
            write(owner, OTHER_KEY, Color.PINK)
            assertSame(Color.PINK, UIManager.get(OTHER_KEY))
            owner.restore()
            assertSame(Color.GREEN, UIManager.get(OTHER_KEY))
        }

    @Test
    fun `Studio default binding freezes evidence and restores only its selected claim`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.GREEN)
            write(owner, KEY, Color.RED)
            write(owner, OTHER_KEY, Color.ORANGE)
            val references = StudioAccentReferences("defaults", StudioProcess(42, Instant.EPOCH))
            val change = StudioAccentDefaults.prepare(references, owner, KEY, UiOverrideValue(Color.YELLOW))
            val original = change.receipts
            assertEquals(2, original.size)
            assertTrue(original.all { it.status == RestoreStatus.PENDING })
            assertSame(Color.RED, UIManager.get(KEY))
            change.apply()
            assertSame(Color.YELLOW, UIManager.get(KEY))
            assertTrue(change.receipts.all { it.status == RestoreStatus.OWNED })
            owner.restore(setOf(OTHER_KEY))
            change.restore(emptyList())
            assertSame(Color.RED, UIManager.get(KEY))
            assertSame(UiOverrideState.Unowned, owner.observeClaim(OTHER_KEY))
            assertTrue(change.receipts.all { it.status == RestoreStatus.RESTORED })
            original.zip(change.receipts).forEach { (before, after) ->
                assertEquals(before, after.copy(status = before.status))
            }
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `Studio claim-only release remains pending behind a newer receipt`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            UIManager.put(KEY, Color.YELLOW)
            write(owner, KEY, Color.GREEN)
            val references = StudioAccentReferences("release", StudioProcess(42, Instant.EPOCH))
            val change = StudioAccentDefaults.prepare(references, owner, KEY, null)
            assertEquals(1, change.receipts.size)
            change.apply()
            assertSame(UiOverrideState.Unowned, owner.observeClaim(KEY))
            val receipt = change.receipts.single()
            assertEquals(RestoreStatus.OWNED, receipt.status)
            val newer = receipt.copy(id = "newer", identity = receipt.identity.copy(ownerToken = "newer"))
            assertFailsWith<IllegalStateException> { change.restore(listOf(newer)) }
            assertSame(UiOverrideState.Unowned, owner.observeClaim(KEY))
            assertEquals(RestoreStatus.PENDING, change.receipts.single().status)
            change.restore(emptyList())
            assertSame(UiOverrideState.Relinquished, owner.observeClaim(KEY))
            assertSame(Color.YELLOW, UIManager.get(KEY))
            assertEquals(RestoreStatus.RESTORED, change.receipts.single().status)
        }

    @Test
    fun `Studio interrupted default write retains rollback and never adopts a foreign color`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val references = StudioAccentReferences("interrupted", StudioProcess(42, Instant.EPOCH))
            val change = StudioAccentDefaults.prepare(references, owner, KEY, UiOverrideValue(Color.RED))
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.RED) error("after write")
                }
            withListener(listener) {
                assertFailsWith<IllegalStateException> { change.apply() }
            }
            assertSame(Color.RED, UIManager.get(KEY))
            assertTrue(change.receipts.all { it.status == RestoreStatus.PENDING })
            val foreign = Color(Color.RED.rgb)
            UIManager.put(KEY, foreign)
            change.restore(emptyList())
            assertSame(expected = foreign, actual = UIManager.get(KEY))
            assertTrue(change.receipts.all { it.status == RestoreStatus.FOREIGN })
            UIManager.put(KEY, Color.RED)
            val terminal = change.receipts
            change.restore(emptyList())
            assertSame(Color.RED, UIManager.get(KEY))
            assertEquals(terminal, change.receipts)
        }

    @Test
    fun `Studio acceptance retains visuals and provenance while cancellation of unused raw writes stays inert`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val references = StudioAccentReferences("accept", StudioProcess(42, Instant.EPOCH))
            val change = StudioAccentDefaults.prepare(references, owner, KEY, UiOverrideValue(Color.RED))
            change.apply()
            val applied = change.receipts
            change.accept()
            assertSame(Color.RED, UIManager.get(KEY))
            assertEquals(applied, change.receipts)
            change.accept()
            assertFailsWith<IllegalStateException> { change.restore(emptyList()) }
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
            val unused = StudioAccentDefaults.prepareRaw(references, KEY, Color.GREEN)
            unused.restore(emptyList())
            assertSame(Color.BLUE, UIManager.get(KEY))
            assertEquals(RestoreStatus.RESTORED, unused.receipts.single().status)
            assertFailsWith<IllegalStateException> { unused.apply() }
        }

    private fun write(
        owner: UiDefaultsOwnership,
        key: String,
        value: Color,
    ) {
        val checkpoint = UiDefaultsCheckpoint()
        owner.record(checkpoint)
        owner.put(key, value, checkpoint)
        checkpoint.commit()
    }

    private fun withListener(
        listener: PropertyChangeListener,
        action: () -> Unit,
    ) {
        val defaults = UIManager.getDefaults()
        defaults.addPropertyChangeListener(listener)
        try {
            action()
        } finally {
            defaults.removePropertyChangeListener(listener)
        }
    }

    private fun onEdt(action: (UiDefaultsOwnership) -> Unit) {
        SwingUtilities.invokeAndWait {
            val saved = listOf(KEY, OTHER_KEY).associateWith { UIManager.put(it, null) }
            val owner = UiDefaultsOwnership()
            try {
                action(owner)
            } finally {
                owner.restore()
                saved.forEach { (key, value) -> UIManager.put(key, value) }
            }
        }
    }

    companion object {
        private const val KEY = "Ayu.test.nativeAccent"
        private const val OTHER_KEY = "Ayu.test.nativeBorder"
    }

    private fun restoringScope(checkpoint: UiDefaultsCheckpoint): AccentApplyScope =
        AccentApplyScope().also { it.record(checkpoint) }
}
