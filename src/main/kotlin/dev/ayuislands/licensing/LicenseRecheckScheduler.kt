package dev.ayuislands.licensing

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.Alarm
import com.intellij.util.concurrency.annotations.RequiresEdt

@Service(Service.Level.APP)
internal class LicenseRecheckScheduler : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @RequiresEdt
    fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ) {
        alarm.cancelAllRequests()
        alarm.addRequest(action, delayMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): LicenseRecheckScheduler = service()
    }
}
