package dev.ayuislands.accent

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
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
    private val log = logger<ChromeBaseColors>()
    private val snapshot = ConcurrentHashMap<String, Color>()

    /**
     * Per-key latch — a `true` entry means "we already logged the missing-key warning".
     * Cleared alongside [snapshot] on LAF refresh so a key that drops between themes
     * gets its own fresh warning. See Phase 40 review Round 3 C-3.
     */
    private val missingKeyLogged = ConcurrentHashMap<String, Boolean>()

    init {
        // Tests that exercise ChromeBaseColors without an IDE container (no
        // MessageBus) swallow the subscription throw silently — the snapshot
        // just won't auto-refresh on LAF change, and refresh() stays callable
        // manually. In production, a throw here means the cache never resets
        // on theme switch, so the user would see wrong base colors after
        // changing theme. Log at WARN so the "chrome tints look stale after
        // theme change" report has a trace in idea.log.
        runCatching {
            val application = ApplicationManager.getApplication()
            application
                .messageBus
                // Anchor the subscription to the Application Disposable so the
                // connection is disposed on plugin / application shutdown instead
                // of leaking across dynamic plugin reloads. See Phase 40 review
                // Round 3 C-4.
                .connect(application)
                .subscribe(LafManagerListener.TOPIC, LafManagerListener { refresh() })
        }.onFailure { exception ->
            log.warn(
                "ChromeBaseColors LAF listener wiring failed; cache will not auto-refresh on theme change",
                exception,
            )
        }
    }

    /**
     * Returns the stock color for [key] — captured the first time this method is
     * called (and every subsequent call returns the same value until the next
     * LAF change). Returns `null` if UIManager has no entry for [key] at capture
     * time; callers fall back to their own default, and the first miss per key
     * is logged at WARN so user-submitted idea.log captures which chrome surface
     * silently skipped tint.
     */
    fun get(key: String): Color? {
        snapshot[key]?.let { return it }
        val current = UIManager.getColor(key)
        if (current == null) {
            if (missingKeyLogged.putIfAbsent(key, true) == null) {
                log.warn(
                    "ChromeBaseColors: UIManager has no entry for '$key' — " +
                        "chrome surface using this key will skip tint until " +
                        "the next LAF event populates it",
                )
            }
            return null
        }
        // Retain first captured value even under concurrent first access.
        return snapshot.putIfAbsent(key, current) ?: current
    }

    /**
     * Clears the snapshot so the next `get` call re-captures from UIManager.
     * Invoked by the LAF listener; exposed for tests.
     */
    fun refresh() {
        snapshot.clear()
        // Rearm the missing-key WARN latch so a key that disappears between
        // themes gets a fresh log line on the next apply cycle.
        missingKeyLogged.clear()
    }
}
