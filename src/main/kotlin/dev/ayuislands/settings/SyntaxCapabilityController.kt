package dev.ayuislands.settings

import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.ayuislands.syntax.PluginRequirement
import dev.ayuislands.syntax.SyntaxLanguageRegistry

/** Owns transient capability work for one settings session. */
internal class SyntaxCapabilityController(
    private val project: Project?,
    private val rendered: (SyntaxCapabilityState?) -> Unit,
) : Disposable {
    private var probe: SyntaxCapabilityProbe? = project?.let(::NativeCapabilityProbe)
    private var activeProbe: Disposable? = null
    private val session = Disposer.newDisposable("Ayu syntax capability session")
    private var model = SyntaxCapabilityModel()
    private var isDisposed = false

    fun selectLanguage(languageId: String) {
        if (probe == null) return
        handle(SyntaxCapabilityEvent.SelectLanguage(languageId))
    }

    fun performRecoveryAction() {
        when (model.state) {
            is SyntaxCapabilityState.PluginUnavailable -> handle(SyntaxCapabilityEvent.OpenPluginSettings)
            is SyntaxCapabilityState.TemporarilyUnavailable,
            is SyntaxCapabilityState.Incompatible,
            -> handle(SyntaxCapabilityEvent.Retry)
            is SyntaxCapabilityState.Confirmed -> handle(SyntaxCapabilityEvent.OpenHighlightingSettings)
            is SyntaxCapabilityState.Checking,
            null,
            -> Unit
        }
    }

    fun replaceProbeForTest(probe: SyntaxCapabilityProbe) {
        this.probe = probe
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        handle(SyntaxCapabilityEvent.CloseSettings)
        Disposer.dispose(session)
    }

    private fun handle(event: SyntaxCapabilityEvent) {
        val transition = SyntaxCapabilityReducer.reduce(model, event)
        model = transition.model
        transition.effects.forEach(::execute)
    }

    private fun execute(effect: SyntaxCapabilityEffect) {
        when (effect) {
            SyntaxCapabilityEffect.CancelProbe -> cancelProbe()
            is SyntaxCapabilityEffect.StartProbe -> startProbe(effect.languageId, effect.generation)
            SyntaxCapabilityEffect.Render -> rendered(model.state)
            is SyntaxCapabilityEffect.OpenPluginSettings -> openPluginSettings(effect.requirement)
            SyntaxCapabilityEffect.OpenHighlightingSettings -> openHighlightingSettings()
            SyntaxCapabilityEffect.ClearRenderer -> rendered(null)
        }
    }

    private fun startProbe(
        languageId: String,
        generation: Long,
    ) {
        val currentProbe = probe ?: return
        val specification =
            checkNotNull(SyntaxLanguageRegistry.findByStorageId(languageId)) {
                "Unknown syntax capability language '$languageId'"
            }
        val task = Disposer.newDisposable("Ayu syntax capability probe: $languageId")
        Disposer.register(session, task)
        activeProbe = task
        currentProbe.start(specification, generation, task) { result ->
            if (activeProbe === task) activeProbe = null
            handle(result.toEvent())
        }
    }

    private fun cancelProbe() {
        val task = activeProbe ?: return
        activeProbe = null
        Disposer.dispose(task)
    }

    private fun openPluginSettings(requirement: PluginRequirement?) {
        val currentProject = project ?: return
        if (requirement == null) {
            ShowSettingsUtil.getInstance().showSettingsDialog(currentProject, PluginManagerConfigurable.ID)
        } else {
            PluginManagerConfigurable.showPluginConfigurable(
                currentProject,
                listOf(PluginId.getId(requirement.pluginId)),
            )
        }
    }

    private fun openHighlightingSettings() {
        val currentProject = project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(currentProject, HIGHLIGHTING_SETTINGS_ID)
        handle(SyntaxCapabilityEvent.RecheckHighlighting)
    }

    private companion object {
        private const val HIGHLIGHTING_SETTINGS_ID = "preferences.editor.colorScheme"
    }
}
