package dev.ayuislands.settings

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.licensing.LicenseChecker

/**
 * Accent-tab section for the premium "accent from project icon" toggle.
 *
 * Extracted from [AyuIslandsAccentPanel] — the panel sits at detekt's
 * LargeClass/TooManyFunctions caps — following the collaborator contract its
 * overrides and quick-switcher sections already use: build / isModified /
 * apply / reset over a pending+stored pair. The toggle itself is consumed by
 * `ProjectIconAccentAssigner` on project open.
 */
internal class ProjectIconAccentSection {
    private var pending: Boolean = false
    private var stored: Boolean = false
    private var checkbox: JBCheckBox? = null

    fun load() {
        stored = AyuIslandsSettings.getInstance().state.projectIconAccentEnabled
        pending = stored
    }

    fun buildRow(panel: Panel) {
        val gate =
            PremiumFeatureGate(
                featureName = "Accent from project icon",
                lockedDescription = "Deriving a project's accent from its icon is a Pro feature.",
                requestMessage = "Unlock accent from project icon",
            )
        panel.row {
            val box = checkBox("Automatically pin an accent from the project icon").component
            box.isSelected = pending
            box.applyPremiumLock(gate)
            box.addActionListener {
                if (!gate.isUnlocked) return@addActionListener
                pending = box.isSelected
            }
            checkbox = box
            newFeatureBadge("accent-from-project-icon")
        }
        panel.row {
            comment(
                "Projects without an override take their accent from .idea/icon.png when they open. " +
                    "Delete a row above to re-derive it on the next open.",
            )
        }
    }

    fun isModified(): Boolean = pending != stored

    fun apply() {
        if (pending == stored) return
        if (!LicenseChecker.isLicensedOrGrace() && pending) return
        AyuIslandsSettings.getInstance().state.projectIconAccentEnabled = pending
        stored = pending
    }

    fun reset() {
        pending = stored
        checkbox?.isSelected = stored
    }

    @org.jetbrains.annotations.TestOnly
    internal fun setPendingForTest(value: Boolean) {
        pending = value
    }
}
