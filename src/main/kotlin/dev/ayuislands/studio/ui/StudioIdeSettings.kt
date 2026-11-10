package dev.ayuislands.studio.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.settings.SettingsOpenContextSource
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.session.StudioSessionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Application capabilities remain alive when the Settings view is detached. */
internal data class StudioSettingsBinding(
    val binding: StudioSessionBinding,
    val metadata: (StudioMetadataCommand) -> Unit,
    val scope: CoroutineScope,
    val entitlement: () -> LicenseEntitlement,
)

internal sealed interface StudioSettingsAccess {
    data class Ready(
        val opening: StudioSettingsBinding,
    ) : StudioSettingsAccess

    data class Unavailable(
        val reason: String,
    ) : StudioSettingsAccess
}

/** Composes the native host only after admission; Retry keeps the original captured context. */
internal class StudioIdeSettings(
    private val provider: suspend (SettingsOpenContext) -> StudioSettingsAccess,
    private val capture: () -> SettingsOpenContext = SettingsOpenContextSource()::capture,
) : UnnamedConfigurable {
    private var captured: SettingsOpenContext? = null
    private var opening: StudioSettingsBinding? = null
    private var presentation: StudioSessionPresentation? = null
    private var host: StudioSettingsHost? = null
    private var view: JPanel? = null

    override fun createComponent(): JComponent {
        view?.let { return it }
        val container = JPanel(BorderLayout())
        view = container
        try {
            load(container)
        } catch (failure: RuntimeException) {
            disposeUIResources()
            throw failure
        }
        return container
    }

    override fun reset() {
        host?.reset()
    }

    override fun isModified(): Boolean = host?.isModified() == true

    override fun apply() {
        host?.apply()
    }

    override fun cancel() {
        host?.cancel()
    }

    override fun disposeUIResources() {
        try {
            host?.disposeUIResources()
        } finally {
            val disposed = presentation
            presentation = null
            view = null
            if (disposed != null) Disposer.dispose(disposed)
        }
    }

    private fun load(container: JPanel) {
        val context = captured ?: capture().also { captured = it }
        runWithModalProgressBlocking(ModalTaskOwner.component(container), "Ayu Islands") {
            when (val access = opening?.let(StudioSettingsAccess::Ready) ?: provider(context)) {
                is StudioSettingsAccess.Ready -> {
                    withContext(Dispatchers.EDT) { opening = access.opening }
                    val prepared = StudioSessionPresentation.create(context, access.opening) { host?.refresh() }
                    withContext(Dispatchers.EDT) { showHost(container, access.opening, prepared) }
                }
                is StudioSettingsAccess.Unavailable ->
                    withContext(
                        Dispatchers.EDT,
                    ) { showUnavailable(container, access.reason) }
            }
        }
    }

    private fun showHost(
        container: JPanel,
        admitted: StudioSettingsBinding,
        prepared: StudioSessionPresentation,
    ) {
        presentation = prepared
        val native =
            StudioSettingsHost(
                admitted.binding,
                prepared::context,
                StudioNativeDialogs,
                prepared,
                prepared::selectSection,
                admitted.metadata,
            )
        host = native
        container.removeAll()
        container.add(native.createComponent(), BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
    }

    private fun showUnavailable(
        container: JPanel,
        reason: String,
    ) {
        val unavailable =
            panel {
                row { label(reason) }
                row { button("Retry") { if (view === container) load(container) } }
            }
        container.removeAll()
        container.add(unavailable, BorderLayout.NORTH)
        container.revalidate()
        container.repaint()
    }
}

private object StudioNativeDialogs : StudioHostDialogs {
    override fun profileName(currentName: String?): String? =
        Messages.showInputDialog("Profile name:", "Ayu Islands", null, currentName.orEmpty(), null)

    override fun review(dialog: StudioReviewDialog): Boolean = dialog.showAndGet()
}
