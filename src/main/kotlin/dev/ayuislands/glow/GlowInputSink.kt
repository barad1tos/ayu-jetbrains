package dev.ayuislands.glow

import com.intellij.util.concurrency.annotations.RequiresEdt

internal class GlowInputSink(
    private val keystroke: () -> Unit,
    private val powerSaveChange: (Boolean) -> Unit,
) {
    @RequiresEdt
    fun onKeystroke() = keystroke()

    @RequiresEdt
    fun onPowerSaveChanged(enabled: Boolean) = powerSaveChange(enabled)
}
