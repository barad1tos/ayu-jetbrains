package dev.ayuislands.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.studio.ui.StudioAccentSettingsScenarios

class StudioSettingsHostTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testAccentAppliesAndReopensInTheNativeSettingsPanel() = StudioAccentSettingsScenarios.previewApplyAndReopen()

    fun testNativeAccentRollbackRetainsForeignChanges() = StudioAccentSettingsScenarios.restoreOwnedValues()
}
