package dev.ayuislands.accent

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import javax.swing.UIManager

/**
 * Caches stock UIManager colors captured BEFORE any chrome tint is applied, so
 * subsequent tint operations always blend from the true base — not from a previously
 * tinted value (which would compound saturation per-apply).
 *
 * First access to each key captures the current UIManager value and locks it; later
 * reads return the cached value. Resets on LAF change so theme switches pick up the
 * new base palette.
 *
 * Thread-safe — ConcurrentHashMap; access is primarily EDT but may be touched from
 * background threads in tests. The cache is intentionally pessimistic — it captures
 * on first read, not at class load, so the snapshot takes the UIManager state AFTER
 * the platform initializes the active LAF.
 *
 * Subscribing to [LafManagerListener.TOPIC] is deliberately NOT symmetric to
 * publishing it: the banned-API guard blocks `syncPublisher(LafManagerListener.TOPIC)`
 * because that would recurse through the LAF apply/revert cycle. This object is a
 * passive listener that only clears its own cache — no tree updates, no re-entry.
 */
object ChromeBaseColors {
    private val snapshot = ConcurrentHashMap<String, Color>()

    init {
        runCatching {
            ApplicationManager
                .getApplication()
                .messageBus
                .connect()
                .subscribe(LafManagerListener.TOPIC, LafManagerListener { refresh() })
        }
        // Suppressed on purpose — tests that exercise ChromeBaseColors without an
        // IDE container (no MessageBus) must still be able to load the object.
        // refresh() is still callable manually in those setups.
    }

    /**
     * Returns the stock color for [key] — captured the first time this method is
     * called (and every subsequent call returns the same value until the next
     * LAF change). Returns `null` if UIManager has no entry for [key] at capture
     * time; callers fall back to their own default.
     */
    fun get(key: String): Color? {
        snapshot[key]?.let { return it }
        val current = UIManager.getColor(key) ?: return null
        // Retain first captured value even under concurrent first access.
        return snapshot.putIfAbsent(key, current) ?: current
    }

    /**
     * Clears the snapshot so the next `get` call re-captures from UIManager.
     * Invoked by the LAF listener; exposed for tests.
     */
    fun refresh() {
        snapshot.clear()
    }
}
