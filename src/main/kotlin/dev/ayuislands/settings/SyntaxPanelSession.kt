package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.theme.AyuEditorSchemeBinder

/** Owns live syntax editing, active-scheme observation, and their shared lifetime. */
internal class SyntaxPanelSession(
    initialCheckpoint: SyntaxPresetConfig,
    persist: (SyntaxPresetConfig) -> Unit,
    onRuntimeApplied: (SyntaxPresetConfig) -> Unit,
    onRuntimeFailed: (RuntimeException) -> Unit,
    onRelinquished: (String) -> Unit,
    onForeignScheme: () -> Unit,
    service: SyntaxIntensityService = SyntaxIntensityService.getInstance(),
    private val isAyuActive: () -> Boolean = ::ayuIsActive,
) : Disposable {
    private val lifetime = Disposer.newDisposable("Ayu syntax settings session")
    private val runtime =
        SyntaxServiceRuntime(
            service = service,
            onRelinquished = onRelinquished,
            onForeignScheme = onForeignScheme,
        )
    private val editing =
        SyntaxEditingSession(
            initialCheckpoint = initialCheckpoint,
            runtime = runtime,
            persist = persist,
            onRuntimeApplied = { config ->
                if (isAyuActive()) onRuntimeApplied(config) else onForeignScheme()
            },
            onRuntimeFailed = onRuntimeFailed,
        )

    init {
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(lifetime)
            .subscribe(
                EditorColorsManager.TOPIC,
                EditorColorsListener { onSchemeChanged() },
            )
    }

    fun editDiscrete(config: SyntaxPresetConfig) {
        editing.editDiscrete(config)
    }

    fun editSlider(config: SyntaxPresetConfig) {
        editing.editSlider(config)
    }

    fun sliderReleased() {
        editing.sliderReleased()
    }

    fun apply(config: SyntaxPresetConfig): SyntaxCommitResult = editing.apply(config)

    fun reset(): SyntaxRestoreResult = editing.reset()

    fun cancel(): SyntaxRestoreResult = editing.cancel()

    override fun dispose() {
        Disposer.dispose(lifetime)
        editing.dispose()
    }

    private fun onSchemeChanged() {
        if (runtime.isWriting) return
        if (isAyuActive()) {
            editing.activeAyuSchemeChanged()
        } else {
            editing.foreignSchemeActivated()
        }
    }
}

private fun ayuIsActive(): Boolean =
    AyuEditorSchemeBinder.isAyuScheme(EditorColorsManager.getInstance().globalScheme.name)
