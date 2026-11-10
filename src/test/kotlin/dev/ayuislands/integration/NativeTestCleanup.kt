package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.TestApplicationManager
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.util.concurrent.ConcurrentLinkedQueue

/** Closes the native fixture before JVM shutdown closes IDE logging; cleanup errors fail the task. */
class NativeTestCleanup : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        if (!java.lang.Boolean.getBoolean("ayu.native.cleanup")) return
        closeApplication()
    }

    companion object {
        fun closeApplication() {
            val application = ApplicationManager.getApplication() ?: return
            if (application.isDisposed) return
            val failures = ConcurrentLinkedQueue<Throwable>()
            val processor =
                object : LoggedErrorProcessor() {
                    override fun processError(
                        category: String,
                        message: String,
                        details: Array<String>,
                        failure: Throwable?,
                    ): Set<Action> {
                        failures.add(failure ?: AssertionError("$category: $message"))
                        return super.processError(category, message, details, failure)
                    }
                }
            runCatching {
                LoggedErrorProcessor.executeWith<Throwable>(processor) {
                    TestApplicationManager.disposeApplicationAndCheckForLeaks()
                }
            }.exceptionOrNull()?.let(failures::add)
            if (failures.isNotEmpty()) {
                throw AssertionError("Native fixture cleanup failed").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        }
    }
}
