package dev.ayuislands.accent

import com.intellij.ui.ColorUtil
import dev.ayuislands.glow.GlowTabMode
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager

// UIDefaults is a heterogeneous native table; null here means an absent raw override.
internal data class UiOverrideValue(
    val raw: Any?,
)

internal sealed interface UiOverrideState {
    data object Unowned : UiOverrideState

    data class Owned(
        val original: UiOverrideValue,
        val expected: UiOverrideValue,
    ) : UiOverrideState

    data object Relinquished : UiOverrideState
}

internal data class UiOverrideTransition(
    val next: UiOverrideState,
    val write: UiOverrideValue? = null,
)

/** Decides a single override transition without consulting platform state. */
internal fun planUiOverride(
    state: UiOverrideState,
    observed: UiOverrideValue,
    requested: UiOverrideValue?,
): UiOverrideTransition {
    val owned = state as? UiOverrideState.Owned
    val stillOwned = owned != null && observed.raw === owned.expected.raw
    if (requested == null) {
        return UiOverrideTransition(UiOverrideState.Unowned, if (stillOwned) owned.original else null)
    }
    return when {
        state == UiOverrideState.Unowned -> UiOverrideTransition(UiOverrideState.Owned(observed, requested), requested)
        owned != null && stillOwned -> UiOverrideTransition(UiOverrideState.Owned(owned.original, requested), requested)
        else -> UiOverrideTransition(UiOverrideState.Relinquished)
    }
}

/** Settles writes using the actual result, including listeners that mutate or throw. */
internal fun settleUiOverride(
    before: UiOverrideState,
    observed: UiOverrideValue,
    transition: UiOverrideTransition,
    actual: UiOverrideValue,
): UiOverrideState =
    when {
        transition.write == null || actual.raw === transition.write.raw -> transition.next
        actual.raw === observed.raw -> before
        transition.next == UiOverrideState.Unowned -> UiOverrideState.Unowned
        else -> UiOverrideState.Relinquished
    }

/** Recovers a savepoint without claiming values installed by another writer. */
internal fun recoverUiOverride(
    previous: UiOverrideState,
    current: UiOverrideState?,
    observed: UiOverrideValue,
): UiOverrideState =
    when {
        previous is UiOverrideState.Owned && observed.raw === previous.expected.raw -> previous
        current is UiOverrideState.Owned && observed.raw === current.original.raw -> previous
        current is UiOverrideState.Owned && observed.raw === current.expected.raw -> current
        current == UiOverrideState.Unowned || previous == UiOverrideState.Relinquished -> previous
        else -> UiOverrideState.Relinquished
    }

/** Immutable native and claim evidence captured before an acknowledged ownership change. */
internal class UiOverridePreparation(
    val owner: UiDefaultsOwnership,
    val checkpoint: UiDefaultsCheckpoint,
    val native: UiDefaultObservation,
    val capturedClaim: UiOverrideState?,
    val transition: UiOverrideTransition,
)

/** Owns only proven developer overrides across successful applies; all effects run on EDT. */
internal class UiDefaultsOwnership {
    private var defaults: UIDefaults? = null
    private val claims = linkedMapOf<String, UiOverrideState>()

    fun record(
        checkpoint: UiDefaultsCheckpoint,
        keys: Set<String>? = null,
    ) {
        val table = currentDefaults()
        val selected = keys?.toSet()
        val saved = if (selected == null) claims.toMap() else claims.filterKeys { it in selected }
        checkpoint.onRestore {
            if (currentDefaults() === table) {
                for (key in (selected ?: (saved.keys + claims.keys))) {
                    val observed = readRawUiDefault(table, key)
                    val previous = saved[key] ?: UiOverrideState.Unowned
                    val current = claims[key]
                    claims[key] = recoverUiOverride(previous, current, UiOverrideValue(observed))
                }
            }
        }
    }

    /** Captures the existing claim and requested transition without changing either surface. */
    fun prepare(
        key: String,
        requested: UiOverrideValue?,
        checkpoint: UiDefaultsCheckpoint,
    ): UiOverridePreparation? {
        val table = currentDefaults()
        val capturedClaim = claims[key]
        val observed = UiOverrideValue(readRawUiDefault(table, key))
        val transition = planUiOverride(capturedClaim ?: UiOverrideState.Unowned, observed, requested)
        val native =
            if (transition.write == null) {
                checkpoint.observe(key)
            } else {
                checkpoint.prepare(key, transition.write.raw) ?: return null
            }
        return UiOverridePreparation(this, checkpoint, native, capturedClaim, transition)
    }

    /** Applies only this captured claim; a stale preparation never re-plans against newer state. */
    fun apply(preparation: UiOverridePreparation): Boolean {
        val table = currentDefaults()
        if (preparation.owner !== this) return false
        val native = preparation.native
        if (claims[native.key] !== preparation.capturedClaim) {
            preparation.checkpoint.discard(native)
            return false
        }
        var published = false
        try {
            if (native is UiDefaultWrite) {
                return preparation.checkpoint.apply(native) {
                    claims[native.key] = preparation.transition.next
                    published = true
                }
            }
            if (!preparation.checkpoint.confirm(native)) return false
            claims[native.key] = preparation.transition.next
            published = true
            return true
        } finally {
            val actual = UiOverrideValue(readRawUiDefault(table, native.key))
            if (published && claims[native.key] === preparation.transition.next) {
                claims[native.key] =
                    settleUiOverride(
                        preparation.capturedClaim ?: UiOverrideState.Unowned,
                        UiOverrideValue(native.before),
                        preparation.transition,
                        actual,
                    )
            } else if (!published && claims[native.key] === preparation.capturedClaim && actual.raw !== native.before) {
                claims[native.key] = UiOverrideState.Relinquished
            }
        }
    }

    fun put(
        key: String,
        value: Any?,
        checkpoint: UiDefaultsCheckpoint,
    ) {
        prepare(key, UiOverrideValue(value), checkpoint)?.let(::apply)
    }

    /** Restores only surviving claims, reobserving each key after earlier listeners have run. */
    fun restore(
        keys: Set<String>? = null,
        checkpoint: UiDefaultsCheckpoint? = null,
    ) {
        currentDefaults()
        var failure: Throwable? = null
        for (key in (keys ?: claims.keys.toSet())) {
            val error =
                runCatching {
                    if (checkpoint == null) restoreClaim(key) else prepare(key, null, checkpoint)?.let(::apply)
                }.exceptionOrNull()
            if (error is VirtualMachineError) throw error
            if (error != null) failure = combineUiFailures(failure, error)
        }
        failure?.let { throw it }
    }

    /** Observes provenance without clearing claims when another defaults table has become active. */
    fun observeClaim(key: String): UiOverrideState? {
        check(SwingUtilities.isEventDispatchThread()) { "UI defaults ownership observation must run on EDT" }
        return if (defaults === UIManager.getDefaults()) claims[key] else null
    }

    fun ownsAny(keys: Set<String>): Boolean {
        val table = currentDefaults()
        return keys.any { key ->
            val claim = claims[key]
            claim is UiOverrideState.Owned && readRawUiDefault(table, key) === claim.expected.raw
        }
    }

    private fun restoreClaim(key: String) {
        val table = currentDefaults()
        val before = claims[key] ?: UiOverrideState.Unowned
        val observed = UiOverrideValue(readRawUiDefault(table, key))
        val transition = planUiOverride(before, observed, null)
        claims[key] = transition.next
        val write = transition.write ?: return
        try {
            UIManager.put(key, write.raw)
        } finally {
            // A nested apply may have installed a newer claim while a property listener ran.
            if (claims[key] === transition.next) {
                claims[key] =
                    settleUiOverride(before, observed, transition, UiOverrideValue(readRawUiDefault(table, key)))
            }
        }
    }

    private fun currentDefaults(): UIDefaults {
        check(SwingUtilities.isEventDispatchThread()) { "UI defaults ownership must run on EDT" }
        val current = UIManager.getDefaults()
        if (defaults !== current) {
            claims.clear()
            defaults = current
        }
        return current
    }
}

internal val uiDefaultsOwner = UiDefaultsOwnership()

// Declarative keys are shared by palette generation and ownership release.
internal object AccentUiKeys {
    val accent =
        listOf(
            // GotItTooltip
            "GotItTooltip.background",
            "GotItTooltip.borderColor",
            // Default button
            "Button.default.startBackground",
            "Button.default.endBackground",
            // Focus border
            "Component.focusedBorderColor",
            "Component.focusColor",
            // Drag and drop
            "DragAndDrop.borderColor",
            // Trial widget
            "TrialWidget.Alert.borderColor",
            "TrialWidget.Alert.foreground",
            // Native tab indicators and editor tab underline
            "ToolWindow.HeaderTab.underlineColor",
            "TabbedPane.underlineColor",
            TAB_UNDERLINE,
        )
    val foregrounds =
        listOf("GotItTooltip.foreground", "GotItTooltip.Button.foreground", "GotItTooltip.Header.foreground")
    val borders =
        listOf("Button.default.focusedBorderColor", "Button.default.startBorderColor", "Button.default.endBorderColor")
    const val TAB_UNDERLINE = "EditorTabs.underlinedBorderColor"
    const val TAB_BACKGROUND = "EditorTabs.underlinedTabBackground"
    const val TAB_HEIGHT = "EditorTabs.underlineHeight"
    const val TAB_ARC = "EditorTabs.underlineArc"
    val colors =
        buildSet {
            addAll(accent)
            addAll(foregrounds)
            addAll(borders)
            add(TAB_BACKGROUND)
        }
    const val TAB_ALPHA = 50
    const val DARK_FOREGROUND_RGBA = 0xFF1F2430.toInt()
}

internal data class AccentUiPolicy(
    val overrideNative: Boolean,
    val tintTabColors: Boolean,
    val overrideTabGeometry: Boolean,
)

internal fun accentUiPolicy(
    external: Boolean,
    overrideEnabled: Boolean,
    chromeAllowed: Boolean,
    tabMode: GlowTabMode,
): AccentUiPolicy {
    val native = !external || overrideEnabled
    val geometry = !external || (chromeAllowed && tabMode != GlowTabMode.OFF)
    return AccentUiPolicy(native, native || (chromeAllowed && tabMode != GlowTabMode.OFF), geometry)
}

internal data class AccentUiInput(
    val accent: Color,
    val neutral: Color?,
    val tabMode: GlowTabMode,
    val foregroundOnDark: Int,
    val policy: AccentUiPolicy,
)

/** Produces packed ARGB colors; omitted keys are released by the adapter. */
internal fun accentUiPalette(input: AccentUiInput): Map<String, Int?> =
    buildMap {
        if (input.policy.overrideNative) {
            AccentUiKeys.accent.forEach { put(it, input.accent.rgb) }
            val darkAccent = ColorUtil.isDark(input.accent)
            val foreground = if (darkAccent) input.foregroundOnDark else AccentUiKeys.DARK_FOREGROUND_RGBA
            AccentUiKeys.foregrounds.forEach { put(it, foreground) }
            val border = ColorUtil.darker(input.accent, 1)
            AccentUiKeys.borders.forEach { put(it, border.rgb) }
        }
        if (input.policy.tintTabColors) {
            val underline = if (input.tabMode == GlowTabMode.OFF) input.neutral?.rgb else input.accent.rgb
            put(AccentUiKeys.TAB_UNDERLINE, underline)
            val background =
                if (input.tabMode == GlowTabMode.FULL) {
                    ColorUtil.toAlpha(input.accent, AccentUiKeys.TAB_ALPHA).rgb
                } else {
                    0
                }
            put(AccentUiKeys.TAB_BACKGROUND, background)
        }
    }
