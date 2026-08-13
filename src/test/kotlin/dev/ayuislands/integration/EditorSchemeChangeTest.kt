package dev.ayuislands.integration

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.theme.EditorSchemeChange
import org.junit.Rule
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorSchemeChangeTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun publishesWithWriteIntent() {
        val application = ApplicationManager.getApplication()
        val listenerLifetime = Disposer.newDisposable("EditorSchemeChangeTest.listener")
        val listener = LockCheckingListener(application)

        try {
            application.messageBus.connect(listenerLifetime).subscribe(EditorColorsManager.TOPIC, listener)
            val action = PublishAction(application)

            SwingUtilities.invokeAndWait(action)

            assertTrue(action.wasCalled, "Swing callback must execute")
            assertFalse(
                action.enteredWithWriteIntent,
                "Swing callbacks must enter this regression test without a write-intent lock",
            )
            assertTrue(listener.wasCalled, "Editor scheme listeners must receive the published change")
        } finally {
            Disposer.dispose(listenerLifetime)
        }
    }

    private class PublishAction(
        private val application: Application,
    ) : Runnable {
        var wasCalled = false
            private set
        var enteredWithWriteIntent = false
            private set

        override fun run() {
            wasCalled = true
            enteredWithWriteIntent = application.isWriteIntentLockAcquired
            EditorSchemeChange.publish()
        }
    }

    private class LockCheckingListener(
        private val application: Application,
    ) : EditorColorsListener {
        var wasCalled = false
            private set

        override fun globalSchemeChange(scheme: EditorColorsScheme?) {
            application.assertWriteIntentLockAcquired()
            wasCalled = true
        }
    }
}
