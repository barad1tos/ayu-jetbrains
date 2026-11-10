package dev.ayuislands.studio

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentUiKeys
import dev.ayuislands.accent.AccentUiPolicy
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.UiDefaultsCheckpoint
import dev.ayuislands.accent.UiDefaultsOwnership
import dev.ayuislands.accent.UiOverrideState
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioAccentFields
import dev.ayuislands.studio.session.StudioAccentReferences
import dev.ayuislands.studio.session.StudioAccentUiSettings
import dev.ayuislands.studio.session.StudioTabGeometry
import java.awt.Color
import java.beans.PropertyChangeListener
import java.time.Instant
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioAccentFieldsTest {
    @Test
    fun `omitted keys release ownership while present null removes raw value and zero stays transparent`() =
        withDefaults { owner ->
            val key = AccentUiKeys.accent.first()
            UIManager.put(key, Color.BLUE)
            val initial = UiDefaultsCheckpoint()
            owner.record(initial)
            owner.put(key, Color.RED, initial)
            initial.commit()
            UIManager.put(AccentUiKeys.TAB_UNDERLINE, Color.GREEN)
            val input =
                settings().copy(
                    context = AccentContext.External,
                    tabMode = GlowTabMode.OFF,
                    policy = AccentUiPolicy(overrideNative = false, tintTabColors = true, overrideTabGeometry = false),
                )
            val fields = StudioAccentFields.colors(references(), input, Color.ORANGE, owner)
            assertSame(Color.RED, raw(key))
            assertSame(Color.GREEN, raw(AccentUiKeys.TAB_UNDERLINE))
            fields.apply()
            assertSame(Color.BLUE, raw(key))
            assertSame(UiOverrideState.Unowned, owner.observeClaim(key))
            assertNull(raw(AccentUiKeys.TAB_UNDERLINE))
            assertEquals(0, (raw(AccentUiKeys.TAB_BACKGROUND) as Color).alpha)
            fields.restore(emptyList())
            assertSame(Color.RED, raw(key))
            assertSame(Color.GREEN, raw(AccentUiKeys.TAB_UNDERLINE))
        }

    @Test
    fun `tab observation happens before raw baseline capture and rollback never evaluates provider twice`() =
        withDefaults { owner ->
            UIManager.put(AccentUiKeys.TAB_HEIGHT, 3)
            UIManager.put(AccentUiKeys.TAB_ARC, 5)
            var reads = 0
            UIManager.put(
                "Island.arc",
                UIDefaults.ActiveValue {
                    reads++
                    UIManager.put(AccentUiKeys.TAB_HEIGHT, 99)
                    14
                },
            )
            val fields = StudioAccentFields.tabs(references(), settings(), owner)
            assertEquals(1, reads)
            assertEquals(99, raw(AccentUiKeys.TAB_HEIGHT))
            assertEquals(5, raw(AccentUiKeys.TAB_ARC))
            fields.apply()
            assertEquals(7, raw(AccentUiKeys.TAB_HEIGHT))
            assertEquals(14, raw(AccentUiKeys.TAB_ARC))
            fields.restore(emptyList())
            assertEquals(99, raw(AccentUiKeys.TAB_HEIGHT))
            assertEquals(5, raw(AccentUiKeys.TAB_ARC))
            assertEquals(1, reads)
            assertTrue(fields.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `changed arc provider rejects prepared geometry without writing a height`() =
        withDefaults { owner ->
            UIManager.put(AccentUiKeys.TAB_HEIGHT, 3)
            UIManager.put("Island.arc", 14)
            val fields = StudioAccentFields.tabs(references(), settings(), owner)
            UIManager.put("Island.arc", UIDefaults.ActiveValue { error("Validation must not evaluate a provider") })
            assertFailsWith<IllegalStateException> { fields.apply() }
            assertEquals(3, raw(AccentUiKeys.TAB_HEIGHT))
            fields.restore(emptyList())
            assertEquals(3, raw(AccentUiKeys.TAB_HEIGHT))
        }

    @Test
    fun `unchanged geometry is inert and release does not sample arc`() =
        withDefaults { owner ->
            UIManager.put("Island.arc", UIDefaults.ActiveValue { error("No geometry observation expected") })
            UIManager.put(AccentUiKeys.TAB_HEIGHT, 3)
            val unchanged =
                StudioAccentFields.tabs(
                    references(),
                    settings().copy(geometry = StudioTabGeometry.Unchanged),
                    owner,
                )
            assertTrue(unchanged.receipts.isEmpty())
            unchanged.apply()
            unchanged.accept()
            assertEquals(3, raw(AccentUiKeys.TAB_HEIGHT))
            val release =
                StudioAccentFields.tabs(
                    references(),
                    settings().copy(geometry = StudioTabGeometry.Release),
                    owner,
                )
            release.apply()
            release.restore(emptyList())
            assertEquals(3, raw(AccentUiKeys.TAB_HEIGHT))
        }

    @Test
    fun `accepted colors retain provenance for ordinary cleanup while rollback preserves foreign colors`() =
        withDefaults { owner ->
            val key = AccentUiKeys.accent.first()
            UIManager.put(key, Color.BLUE)
            val fields = StudioAccentFields.colors(references(), settings(), Color.ORANGE, owner)
            fields.apply()
            val receipts = fields.receipts
            fields.accept()
            fields.accept()
            assertEquals(receipts, fields.receipts)
            assertFailsWith<IllegalStateException> { fields.restore(emptyList()) }
            owner.restore()
            assertSame(Color.BLUE, raw(key))
            val next = StudioAccentFields.colors(references(), settings(), Color.ORANGE, owner)
            next.apply()
            val foreign = Color(Color.ORANGE.rgb)
            UIManager.put(key, foreign)
            next.restore(emptyList())
            assertSame(foreign, raw(key))
            assertTrue(next.receipts.any { it.status == RestoreStatus.FOREIGN })
        }

    @Test
    fun `cancel after geometry release restores the accepted ownership and dimensions`() =
        withDefaults { owner ->
            UIManager.put(AccentUiKeys.TAB_HEIGHT, 3)
            UIManager.put(AccentUiKeys.TAB_ARC, 5)
            UIManager.put("Island.arc", 14)
            val accepted = StudioAccentFields.tabs(references(), settings(), owner)
            accepted.apply()
            accepted.accept()
            val released =
                StudioAccentFields.tabs(references(), settings().copy(geometry = StudioTabGeometry.Release), owner)
            released.apply()
            assertEquals(3, raw(AccentUiKeys.TAB_HEIGHT))
            assertEquals(5, raw(AccentUiKeys.TAB_ARC))
            released.restore(emptyList())
            assertEquals(7, raw(AccentUiKeys.TAB_HEIGHT))
            assertEquals(14, raw(AccentUiKeys.TAB_ARC))
            owner.restore()
            assertEquals(3, raw(AccentUiKeys.TAB_HEIGHT))
            assertEquals(5, raw(AccentUiKeys.TAB_ARC))
        }

    @Test
    fun `setter mutation then failure remains recoverable without accepting partial colors`() =
        withDefaults { owner ->
            val first = AccentUiKeys.accent[0]
            val second = AccentUiKeys.accent[1]
            UIManager.put(first, Color.BLUE)
            UIManager.put(second, Color.GREEN)
            val fields = StudioAccentFields.colors(references(), settings(), Color.ORANGE, owner)
            val listener =
                PropertyChangeListener { change ->
                    if (change.propertyName == second) error("Native setter failed after mutation")
                }
            val defaults = UIManager.getDefaults()
            defaults.addPropertyChangeListener(listener)
            try {
                assertFailsWith<IllegalStateException> { fields.apply() }
            } finally {
                defaults.removePropertyChangeListener(listener)
            }
            assertEquals(Color.ORANGE.rgb, (raw(first) as Color).rgb)
            assertEquals(Color.ORANGE.rgb, (raw(second) as Color).rgb)
            assertFailsWith<IllegalStateException> { fields.accept() }
            fields.restore(emptyList())
            assertSame(Color.BLUE, raw(first))
            assertSame(Color.GREEN, raw(second))
            assertTrue(fields.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `cancelling a prepared palette leaves untouched ownership absent`() =
        withDefaults { owner ->
            val fields = StudioAccentFields.colors(references(), settings(), Color.ORANGE, owner)
            AccentUiKeys.colors.forEach { assertNull(owner.observeClaim(it)) }
            fields.restore(emptyList())
            AccentUiKeys.colors.forEach { assertNull(owner.observeClaim(it), it) }
            assertTrue(fields.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `failed arc restoration still restores independent height and can retry`() =
        withDefaults { owner ->
            UIManager.put(AccentUiKeys.TAB_HEIGHT, 3)
            UIManager.put(AccentUiKeys.TAB_ARC, 5)
            UIManager.put("Island.arc", 14)
            val fields = StudioAccentFields.tabs(references(), settings(), owner)
            fields.apply()
            val listener =
                PropertyChangeListener { change ->
                    if (change.propertyName == AccentUiKeys.TAB_ARC) error("Restoration listener failed")
                }
            val defaults = UIManager.getDefaults()
            defaults.addPropertyChangeListener(listener)
            try {
                assertFailsWith<IllegalStateException> { fields.restore(emptyList()) }
                assertEquals(3, raw(AccentUiKeys.TAB_HEIGHT))
                assertEquals(5, raw(AccentUiKeys.TAB_ARC))
            } finally {
                defaults.removePropertyChangeListener(listener)
            }
            fields.restore(emptyList())
            assertTrue(fields.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `inactive release captures without effects and restores accepted colors and geometry on abort`() =
        withDefaults { owner ->
            val keys = AccentUiKeys.colors + setOf(AccentUiKeys.TAB_HEIGHT, AccentUiKeys.TAB_ARC)
            AccentUiKeys.colors.forEach { UIManager.put(it, Color.BLUE) }
            UIManager.put(AccentUiKeys.TAB_HEIGHT, 3)
            UIManager.put(AccentUiKeys.TAB_ARC, 5)
            UIManager.put("Island.arc", 14)
            val baseline = keys.associateWith(::raw)
            val colors = StudioAccentFields.colors(references(), settings(), Color.ORANGE, owner)
            colors.apply()
            colors.accept()
            val tabs = StudioAccentFields.tabs(references(), settings(), owner)
            tabs.apply()
            tabs.accept()
            val accepted = keys.associateWith(::raw)
            val claims = keys.associateWith(owner::observeClaim)
            UIManager.put("Island.arc", UIDefaults.ActiveValue { error("Inactive cleanup must not sample geometry") })

            val release = StudioAccentFields.release(references(), owner)

            accepted.forEach { (key, value) -> assertSame(value, raw(key), key) }
            assertEquals(claims, keys.associateWith(owner::observeClaim))
            release.apply()
            baseline.forEach { (key, value) -> assertSame(value, raw(key), key) }
            release.restore(emptyList())
            accepted.forEach { (key, value) -> assertSame(value, raw(key), key) }
            assertEquals(claims, keys.associateWith(owner::observeClaim))

            val committed = StudioAccentFields.release(references(), owner)
            committed.apply()
            committed.accept()
            baseline.forEach { (key, value) -> assertSame(value, raw(key), key) }
        }

    @Test
    fun `inactive release preserves a foreign equal color and unowned geometry`() =
        withDefaults { owner ->
            val key = AccentUiKeys.accent.first()
            UIManager.put(key, Color.BLUE)
            UIManager.put(AccentUiKeys.TAB_HEIGHT, 3)
            val colors = StudioAccentFields.colors(references(), settings(), Color.ORANGE, owner)
            colors.apply()
            colors.accept()
            val foreign = Color((raw(key) as Color).rgb)
            UIManager.put(key, foreign)

            val release = StudioAccentFields.release(references(), owner)
            assertSame(foreign, raw(key))
            release.apply()
            release.accept()

            assertSame(foreign, raw(key))
            assertEquals(3, raw(AccentUiKeys.TAB_HEIGHT))
        }

    private fun settings() =
        StudioAccentUiSettings(
            AccentContext.Ayu(AyuVariant.MIRAGE),
            GlowTabMode.MINIMAL,
            AccentUiPolicy(overrideNative = true, tintTabColors = true, overrideTabGeometry = true),
            StudioTabGeometry.Set(7),
        )

    private fun references() = StudioAccentReferences("fields", StudioProcess(42, Instant.EPOCH))

    private fun raw(key: String): Any? = readRawUiDefault(UIManager.getDefaults(), key)

    private fun withDefaults(action: (UiDefaultsOwnership) -> Unit) {
        SwingUtilities.invokeAndWait {
            val keys = AccentUiKeys.colors + setOf(AccentUiKeys.TAB_HEIGHT, AccentUiKeys.TAB_ARC, "Island.arc")
            val original = keys.associateWith(::raw)
            val owner = UiDefaultsOwnership()
            try {
                action(owner)
            } finally {
                owner.restore()
                original.forEach { (key, value) -> UIManager.put(key, value) }
            }
        }
    }
}
