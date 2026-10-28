package dev.ayuislands.accent

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
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
 * The cache also remembers the last color this plugin wrote for each key. Some
 * platform startup paths fire several LAF events after the first accent apply; if
 * a late cache refresh is followed by another apply before the platform restores
 * stock UIDefaults, a naive read would capture our own tinted value as the new
 * base and compound the status bar toward bright cyan. When the current
 * UIManager value equals the last plugin tint, [get] recovers the last stock
 * value instead of treating the tinted value as stock.
 *
 * Thread-safe — ConcurrentHashMap; access is primarily EDT but may be touched from
 * background threads in tests. The cache is intentionally pessimistic — it captures
 * on first read, not at class load, so the snapshot takes the UIManager state AFTER
 * the platform initializes the active LAF.
 *
 * Subscribing to [LafManagerListener.TOPIC] is deliberately NOT symmetric to
 * publishing it: the banned-API guard blocks `syncPublisher(LafManagerListener.TOPIC)`
 * because that would recurse through the LAF apply/revert cycle. The lifecycle
 * service is a passive listener that only clears this cache — no tree updates,
 * no re-entry.
 */
object ChromeBaseColors {
    private val log = logger<ChromeBaseColors>()

    // Bounded implicitly by the finite set of UIManager keys consumed by chrome
    // elements (~20). No explicit cap needed — no caller feeds user-controlled
    // keys in, and a LAF refresh() clears the whole map anyway. Do not introduce
    // a TTL or LRU cache here speculatively; the growth ceiling is the key list
    // above, not user activity.
    private val snapshot = ConcurrentHashMap<String, Color>()

    /**
     * Set of keys we've already logged a "UIManager has no entry" warning for.
     * Cleared alongside [snapshot] on LAF refresh so a key that drops between themes
     * gets its own fresh warning. Backed by `ConcurrentHashMap.newKeySet()` so
     * `.add(key)` returns the log-once gate in a single atomic step.
     */
    private val missingKeyLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Last confirmed stock value per key. Kept across [refresh] so a late LAF
     * event cannot make the next [get] recapture this plugin's own tint as the
     * stock baseline.
     */
    private val lastStockSnapshot = ConcurrentHashMap<String, Color>()

    /** Last tinted color written by this plugin per key. */
    private val lastPluginTint = ConcurrentHashMap<String, Color>()

    private val isLafListenerRequested = AtomicBoolean(false)
    private var generation = 0L

    private fun requestLafListener() {
        if (!isLafListenerRequested.compareAndSet(false, true)) return
        // Tests that exercise ChromeBaseColors without an IDE container (no
        // MessageBus) swallow the subscription throw silently — the snapshot
        // just won't auto-refresh on LAF change, and refresh() stays callable
        // manually. In production, a throw here means the cache never resets
        // on theme switch, so the user would see wrong base colors after
        // changing theme. Log at WARN so the "chrome tints look stale after
        // theme change" report has a trace in idea.log.
        //
        // Pattern B: narrow the catch to [RuntimeException] so catastrophic
        // JVM signals ([OutOfMemoryError], [NoClassDefFoundError],
        // [StackOverflowError]) still propagate during plugin init instead
        // of being demoted to a single WARN line. The MessageBus / LAF
        // subscription surface throws only [RuntimeException] subtypes
        // (NPE on null service, IllegalState on torn-down app).
        try {
            ApplicationManager
                .getApplication()
                .getService(ChromeBaseColorsLifecycle::class.java)
        } catch (exception: RuntimeException) {
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
     *
     * The fast-path `snapshot[key]?.let { return it }` stays outside the lock
     * (already thread-safe via [ConcurrentHashMap]) so repeat reads — the
     * common case — hit no contention. The composite read-UIManager
     * -putIfAbsent for first capture runs inside `synchronized(snapshot)` so
     * [refresh] cannot interleave between the UIManager read and the snapshot
     * write. Without this, a racing `refresh()` between those two lines would
     * let a stale pre-LAF color land in the post-LAF snapshot.
     */
    operator fun get(key: String): Color? {
        requestLafListener()
        snapshot[key]?.let { return it }
        synchronized(snapshot) {
            // Re-check under the lock: a concurrent writer may have populated
            // between the outer fast-path check and acquiring the monitor.
            snapshot[key]?.let { return it }
            val current = UIManager.getColor(key)
            if (current == null) {
                // `Set.add` returns `true` iff the element was newly inserted — that is
                // exactly the log-once gate, in a single atomic step.
                if (missingKeyLogged.add(key)) {
                    log.warn(
                        "ChromeBaseColors: UIManager has no entry for '$key' — " +
                            "chrome surface using this key will skip tint until " +
                            "the next LAF event populates it",
                    )
                }
                return null
            }
            val stock = recoverStockIfCurrentIsPluginTint(key, current)
            CacheCheckpoints.put(lastStockSnapshot, key, stock)
            // A UIManager getter can reenter and capture the key while this read runs.
            snapshot[key]?.let { return it }
            CacheCheckpoints.put(snapshot, key, stock)
            return snapshot[key] ?: stock
        }
    }

    /**
     * Records a UIManager color this plugin just wrote so a later cache refresh
     * can distinguish plugin tint from platform stock.
     */
    fun rememberPluginTint(
        key: String,
        color: Color,
    ) {
        CacheCheckpoints.put(lastPluginTint, key, color)
    }

    /** Clears the remembered plugin tint for [key] after the matching revert. */
    fun forgetPluginTint(key: String) {
        CacheCheckpoints.put(lastPluginTint, key, null)
    }

    /**
     * Clears the snapshot so the next `get` call re-captures from UIManager.
     * Invoked by the LAF listener; exposed for tests.
     *
     * [lastStockSnapshot] and [lastPluginTint] intentionally survive this clear:
     * listener ordering can refresh the cache after this plugin already wrote a
     * tint, and the next [get] must still be able to recover the true stock base.
     *
     * The clear pair runs inside `synchronized(snapshot)` so a `get()` in
     * first-capture mode cannot interleave its read-UIManager-putIfAbsent
     * composite and land a pre-LAF color into the post-LAF snapshot. The
     * latch is still cleared BEFORE the snapshot so a racing `get()` entering
     * the lock just AFTER refresh's latch-clear but BEFORE its snapshot-clear
     * still sees an empty latch and (re-)warns on the next miss. Both clears
     * are visible to the next entrant atomically under the same monitor.
     */
    fun refresh() {
        synchronized(snapshot) {
            // Clear the latch BEFORE the snapshot so a racing `get(key)` between the
            // two clears cannot see a cleared snapshot with a stale latch still full
            // (which would silence a WARN that the new LAF cycle should emit).
            generation++
            missingKeyLogged.clear()
            snapshot.clear()
        }
    }

    private object CacheCheckpoints {
        private val pending = mutableListOf<CacheCheckpoint>()
        private val receipts = mutableListOf<CacheCheckpoint>()
        private var boundScope: AccentApplyScope? = null

        fun put(
            entries: ConcurrentHashMap<String, Color>,
            key: String,
            color: Color?,
        ) {
            synchronized(snapshot) {
                val scope =
                    if (SwingUtilities.isEventDispatchThread()) {
                        AccentApplyScope.active?.takeUnless {
                            it.isCommitted
                        }
                    } else {
                        null
                    }
                if (scope != null) {
                    scope.checkWritable()
                    if (pending.isNotEmpty()) scope.recover { restoreAccentCheckpoints(pending) }
                    bind(scope)
                    val previous =
                        receipts.lastOrNull {
                            it.entries === entries &&
                                it.key == key &&
                                it.capturedGeneration == generation &&
                                it.isUnsettled
                        }
                    if (previous?.permitsWrite() == false) return
                    val checkpoint = CacheCheckpoint(entries, key, color)
                    receipts += checkpoint
                    scope.record(checkpoint)
                }
                if (color == null) entries.remove(key) else entries[key] = color
            }
        }

        private fun bind(scope: AccentApplyScope) {
            if (boundScope === scope) return
            boundScope = scope
            receipts.clear()
            scope.onClose {
                synchronized(snapshot) {
                    receipts.clear()
                    boundScope = null
                }
            }
        }

        private class CacheCheckpoint(
            val entries: ConcurrentHashMap<String, Color>,
            val key: String,
            private val expected: Color?,
        ) : AccentCheckpoint {
            private enum class State { OWNED, RELINQUISHED, RESTORED }

            val capturedGeneration = generation
            private val original = entries[key]
            private var state = State.OWNED

            override val hasPending: Boolean
                get() = state == State.OWNED

            val isUnsettled: Boolean
                get() = state != State.RESTORED

            fun permitsWrite(): Boolean {
                if (state != State.OWNED) return false
                if (capturedGeneration != generation || entries[key] !== expected) state = State.RELINQUISHED
                return state == State.OWNED
            }

            override fun restore(blockedBy: List<AccentCheckpoint>) {
                synchronized(snapshot) {
                    if (!hasPending) return
                    check(
                        blockedBy.none {
                            it is CacheCheckpoint && it.entries === entries && it.key == key && it.hasPending
                        },
                    ) { "Chrome cache restoration blocked by a newer write: $key" }
                    if (!permitsWrite()) return
                    if (original == null) entries.remove(key) else entries[key] = original
                    commit()
                }
            }

            override fun commit() {
                if (state != State.RELINQUISHED) state = State.RESTORED
            }

            override fun retain() {
                if (hasPending && this !in pending) pending += this
            }
        }
    }

    private fun recoverStockIfCurrentIsPluginTint(
        key: String,
        current: Color,
    ): Color {
        val lastTint = lastPluginTint[key] ?: return current
        val lastStock = lastStockSnapshot[key] ?: return current
        return if (current == lastTint) lastStock else current
    }
}

@Service(Service.Level.APP)
internal class ChromeBaseColorsLifecycle : Disposable {
    init {
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { ChromeBaseColors.refresh() })
    }

    override fun dispose() = Unit
}
