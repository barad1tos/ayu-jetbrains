package dev.ayuislands.accent

import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChromeStockObservationTest {
    @Test
    fun `cold lazy stock is observed once before raw rollback capture without publishing caches`() =
        onEdt {
            var evaluations = 0
            UIManager.put(
                KEY,
                UIDefaults.LazyValue {
                    evaluations++
                    Color.BLUE
                },
            )
            val observation = ChromeBaseColors.observeStock(KEY)
            assertSame(Color.BLUE, observation.stock)
            assertEquals(1, evaluations)
            assertEmptyCaches()
            val checkpoint = UiDefaultsCheckpoint()
            val write = requireNotNull(checkpoint.prepare(KEY, Color.GREEN))
            assertSame(Color.BLUE, write.before)
            assertTrue(checkpoint.apply(write))
            checkpoint.restore()
            assertEquals(1, evaluations)
            assertSame(Color.BLUE, readRawUiDefault(UIManager.getDefaults(), KEY))
        }

    @Test
    fun `active stock keeps the original provider and does not evaluate it during freshness or rollback`() =
        onEdt {
            var evaluations = 0
            val provider =
                UIDefaults.ActiveValue {
                    evaluations++
                    Color.BLUE
                }
            UIManager.put(KEY, provider)
            val observation = ChromeBaseColors.observeStock(KEY)
            assertSame(Color.BLUE, observation.stock)
            assertTrue(ChromeBaseColors.isStockCurrent(observation))
            val checkpoint = UiDefaultsCheckpoint()
            val write = requireNotNull(checkpoint.prepare(KEY, Color.GREEN))
            assertSame(provider, write.before)
            assertTrue(checkpoint.apply(write))
            checkpoint.restore()
            assertSame(provider, readRawUiDefault(UIManager.getDefaults(), KEY))
            assertEquals(1, evaluations)
            assertEmptyCaches()
        }

    @Test
    fun `fallback stock leaves the developer override absent throughout capture and rollback`() =
        onEdt {
            UIManager.getLookAndFeelDefaults()[KEY] = Color.BLUE
            val observation = ChromeBaseColors.observeStock(KEY)
            assertSame(Color.BLUE, observation.stock)
            assertNull(readRawUiDefault(UIManager.getDefaults(), KEY))
            val checkpoint = UiDefaultsCheckpoint()
            val write = requireNotNull(checkpoint.prepare(KEY, Color.GREEN))
            assertNull(write.before)
            assertTrue(checkpoint.apply(write))
            checkpoint.restore()
            assertNull(readRawUiDefault(UIManager.getDefaults(), KEY))
            assertSame(Color.BLUE, UIManager.getColor(KEY))
            assertEmptyCaches()
        }

    @Test
    fun `fallback changes invalidate a stock observation without evaluating a provider`() =
        onEdt {
            UIManager.getLookAndFeelDefaults()[KEY] = Color.BLUE
            val observation = ChromeBaseColors.observeStock(KEY)
            assertTrue(ChromeBaseColors.isStockCurrent(observation))
            UIManager.getLookAndFeelDefaults()[KEY] = UIDefaults.ActiveValue { error("Validation must not evaluate") }
            assertFalse(ChromeBaseColors.isStockCurrent(observation))
            assertEmptyCaches()
        }

    @Test
    fun `cached stock skips native providers and detects identity or generation changes`() =
        onEdt {
            UIManager.put(KEY, UIDefaults.ActiveValue { error("Cached stock must not read the provider") })
            setCache(ChromeCacheSlot.CURRENT, Color.BLUE)
            val observation = ChromeBaseColors.observeStock(KEY)
            assertSame(Color.BLUE, observation.stock)
            assertTrue(ChromeBaseColors.isStockCurrent(observation))
            setCache(ChromeCacheSlot.CURRENT, Color(Color.BLUE.rgb))
            assertFalse(ChromeBaseColors.isStockCurrent(observation))
            val replaced = ChromeBaseColors.observeStock(KEY)
            ChromeBaseColors.refresh()
            assertFalse(ChromeBaseColors.isStockCurrent(replaced))
        }

    @Test
    fun `a native getter may populate the current cache before its observation completes`() =
        onEdt {
            var evaluations = 0
            UIManager.put(
                KEY,
                UIDefaults.ActiveValue {
                    evaluations++
                    setCache(ChromeCacheSlot.CURRENT, Color.GREEN)
                    Color.BLUE
                },
            )
            val observation = ChromeBaseColors.observeStock(KEY)
            assertSame(Color.GREEN, observation.stock)
            assertSame(Color.GREEN, observation.cached)
            assertTrue(ChromeBaseColors.isStockCurrent(observation))
            assertEquals(1, evaluations)
            assertNull(ChromeBaseColors.prepare(ChromeCacheSlot.STOCK, KEY, null).before)
            assertNull(ChromeBaseColors.prepare(ChromeCacheSlot.TINT, KEY, null).before)
        }

    @Test
    fun `a matching prior tint recovers stock by color equality without publishing it`() =
        onEdt {
            setCache(ChromeCacheSlot.STOCK, Color.BLUE)
            setCache(ChromeCacheSlot.TINT, Color.ORANGE)
            UIManager.put(KEY, Color(Color.ORANGE.rgb))
            val observation = ChromeBaseColors.observeStock(KEY)
            assertSame(Color.BLUE, observation.stock)
            assertNull(observation.cached)
            assertNull(ChromeBaseColors.prepare(ChromeCacheSlot.CURRENT, KEY, null).before)
            assertSame(Color.BLUE, ChromeBaseColors.prepare(ChromeCacheSlot.STOCK, KEY, null).before)
            assertSame(Color.ORANGE, ChromeBaseColors.prepare(ChromeCacheSlot.TINT, KEY, null).before)
        }

    @Test
    fun `a getter that refreshes the generation rejects capture without publishing stock`() =
        onEdt {
            UIManager.put(
                KEY,
                UIDefaults.ActiveValue {
                    ChromeBaseColors.refresh()
                    Color.BLUE
                },
            )
            assertFailsWith<IllegalStateException> { ChromeBaseColors.observeStock(KEY) }
            assertEmptyCaches()
        }

    @Test
    fun `missing native stock stays absent without a cache claim`() =
        onEdt {
            val observation = ChromeBaseColors.observeStock(KEY)
            assertNull(observation.stock)
            assertTrue(ChromeBaseColors.isStockCurrent(observation))
            assertEmptyCaches()
        }

    private fun assertEmptyCaches() {
        ChromeCacheSlot.entries.forEach { assertNull(ChromeBaseColors.prepare(it, KEY, null).before) }
    }

    private fun setCache(
        slot: ChromeCacheSlot,
        color: Color?,
    ) {
        val change = ChromeBaseColors.prepare(slot, KEY, color)
        check(change.apply())
        change.commit()
    }

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait {
            val defaults = UIManager.getDefaults()
            val laf = UIManager.getLookAndFeelDefaults()
            val original = readRawUiDefault(defaults, KEY)
            val fallback = readRawUiDefault(laf, KEY)
            val caches = ChromeCacheSlot.entries.associateWith { ChromeBaseColors.prepare(it, KEY, null).before }
            try {
                UIManager.put(KEY, null)
                laf[KEY] = null
                ChromeCacheSlot.entries.forEach { setCache(it, null) }
                action()
            } finally {
                UIManager.put(KEY, original)
                laf[KEY] = fallback
                caches.forEach { (slot, color) -> setCache(slot, color) }
            }
        }
    }

    companion object {
        private const val KEY = "Ayu.test.chromeStockObservation"
    }
}
