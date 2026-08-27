package dev.ayuislands.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicReference

internal class FocusedEditorCapture {
    private val focusedEditor = AtomicReference<SettingsOpenContext>()

    fun record(context: SettingsOpenContext): Boolean {
        if (context.project == null || context.activeFile == null) return false
        val previous = focusedEditor.getAndSet(context)
        return previous?.project !== context.project || previous.activeFile != context.activeFile
    }

    fun get(project: Project): SettingsOpenContext? =
        focusedEditor.get()?.takeIf { context -> context.project === project }
}

@Service(Service.Level.APP)
internal class FocusedEditorContext(
    subscribeFocus: (PropertyChangeListener) -> (() -> Unit) = { listener ->
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addPropertyChangeListener(FOCUS_OWNER_PROPERTY, listener)
        val unsubscribe: () -> Unit = {
            focusManager.removePropertyChangeListener(FOCUS_OWNER_PROPERTY, listener)
        }
        unsubscribe
    },
    focusedEditorContext: (Component) -> SettingsOpenContext? = ::readFocusedEditorContext,
) : Disposable {
    private val capture = FocusedEditorCapture()
    private val focusListener =
        PropertyChangeListener { event ->
            listOfNotNull(event.newValue as? Component, event.oldValue as? Component)
                .firstNotNullOfOrNull(focusedEditorContext)
                ?.let { context ->
                    if (capture.record(context)) {
                        LOG.info(
                            "[SYNTAX-CONTEXT] focused-editor project=${context.project?.name}, " +
                                "fileType=${context.activeFile?.fileTypeName}",
                        )
                    }
                }
        }
    private val unsubscribeFocus = subscribeFocus(focusListener)

    fun get(project: Project): SettingsOpenContext? = capture.get(project)

    override fun dispose() = unsubscribeFocus()

    companion object {
        private const val FOCUS_OWNER_PROPERTY = "permanentFocusOwner"
        private val LOG = logger<FocusedEditorContext>()

        fun getInstance(): FocusedEditorContext = service()

        private fun readFocusedEditorContext(component: Component): SettingsOpenContext? {
            val dataContext = DataManager.getInstance().getDataContext(component)
            val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return null
            val project = editor.project ?: dataContext.getData(CommonDataKeys.PROJECT) ?: return null
            val activeFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
            return SettingsOpenContext(project, ActiveFileContext.capture(activeFile.name, activeFile.fileType))
        }
    }
}
