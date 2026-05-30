package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import dev.ayuislands.licensing.LicenseChecker
import javax.swing.JComponent

internal data class PremiumFeatureGate(
    val featureName: String,
    val lockedDescription: String,
    val requestMessage: String,
    val isUnlocked: Boolean = LicenseChecker.isLicensedOrGrace(),
) {
    val tooltip: String = "$featureName requires Ayu Islands Pro"

    fun requestUnlock() {
        LicenseChecker.requestLicense(requestMessage)
    }
}

internal fun Panel.premiumFeatureNotice(gate: PremiumFeatureGate) {
    if (gate.isUnlocked) return
    row { comment(gate.lockedDescription) }
    row {
        link("Unlock Pro") { gate.requestUnlock() }
    }
}

internal fun <T : JComponent> T.applyPremiumLock(
    gate: PremiumFeatureGate,
    enabledWhenUnlocked: Boolean = true,
): T {
    isEnabled = gate.isUnlocked && enabledWhenUnlocked
    toolTipText = if (gate.isUnlocked) null else gate.tooltip
    return this
}

internal fun Row.visibleIfUnlockedOrPreview(
    condition: AtomicBooleanProperty,
    gate: PremiumFeatureGate,
): Row =
    if (gate.isUnlocked) {
        visibleIf(condition)
    } else {
        visible(true)
    }

internal fun <T : JComponent> Cell<T>.visibleIfUnlockedOrPreview(
    condition: AtomicBooleanProperty,
    gate: PremiumFeatureGate,
): Cell<T> =
    if (gate.isUnlocked) {
        visibleIf(condition)
    } else {
        visible(true)
    }

internal fun CollapsibleRow.visibleIfUnlockedOrPreview(
    condition: AtomicBooleanProperty,
    gate: PremiumFeatureGate,
): Row =
    if (gate.isUnlocked) {
        visibleIf(condition)
    } else {
        visible(true)
    }
