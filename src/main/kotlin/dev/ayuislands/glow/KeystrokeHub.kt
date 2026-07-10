package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings

@Service(Service.Level.APP)
class KeystrokeHub internal constructor(
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Disposable {
    private val log = logger<KeystrokeHub>()
    private var initialized = false
    private var isLicenseAllowed: Boolean? = null
    private var licenseCheckedAtMs = Long.MIN_VALUE
    private var routeFailureLogged = false

    internal val actionListener: AnActionListener =
        object : AnActionListener {
            override fun beforeEditorTyping(
                character: Char,
                dataContext: DataContext,
            ) {
                routeSafely(CommonDataKeys.PROJECT.getData(dataContext))
            }

            override fun beforeActionPerformed(
                action: AnAction,
                event: AnActionEvent,
            ) {
                try {
                    val actionId = ActionManager.getInstance().getId(action)
                    if (actionId in TYPING_ACTION_IDS) route(event.project)
                } catch (exception: RuntimeException) {
                    reportRouteFailure("Glow editor action routing failed", exception)
                }
            }
        }

    fun initialize() {
        if (initialized) return
        val application = ApplicationManager.getApplication()
        val connection = application.messageBus.connect(this)
        connection.subscribe(
            PowerSaveMode.TOPIC,
            PowerSaveMode.Listener {
                GlowOverlayManager.broadcastPowerSave(PowerSaveMode.isEnabled())
            },
        )
        connection.subscribe(AnActionListener.TOPIC, actionListener)
        initialized = true
    }

    private fun route(project: Project?) {
        if (project == null) return
        if (!licenseAllowsInput()) return
        if (!AyuIslandsSettings.getInstance().state.glowEnabled) return
        GlowOverlayManager.getInstance(project).input.onKeystroke()
    }

    private fun licenseAllowsInput(): Boolean {
        val currentTimeMs = nowMs()
        val cacheAgeMs = currentTimeMs - licenseCheckedAtMs
        val cached = isLicenseAllowed
        if (cached != null && cacheAgeMs in 0 until LICENSE_CACHE_MS) return cached

        return LicenseChecker.isLicensedOrGrace().also { allowed ->
            isLicenseAllowed = allowed
            licenseCheckedAtMs = currentTimeMs
        }
    }

    private fun routeSafely(project: Project?) {
        try {
            route(project)
        } catch (exception: RuntimeException) {
            reportRouteFailure("Glow editor typing routing failed", exception)
        }
    }

    private fun reportRouteFailure(
        message: String,
        exception: RuntimeException,
    ) {
        if (routeFailureLogged) return
        routeFailureLogged = true
        log.warn(message, exception)
    }

    internal fun invalidateLicenseGate() {
        isLicenseAllowed = null
        licenseCheckedAtMs = Long.MIN_VALUE
        routeFailureLogged = false
    }

    override fun dispose() = Unit

    companion object {
        private const val LICENSE_CACHE_MS = 30_000L
        private val TYPING_ACTION_IDS =
            setOf(
                IdeActions.ACTION_EDITOR_BACKSPACE,
                IdeActions.ACTION_EDITOR_ENTER,
                IdeActions.ACTION_EDITOR_DELETE,
            )

        fun getInstance(): KeystrokeHub = ApplicationManager.getApplication().getService(KeystrokeHub::class.java)
    }
}
