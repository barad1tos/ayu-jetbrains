package dev.ayuislands.licensing

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.Alarm
import com.intellij.util.concurrency.annotations.RequiresEdt

internal enum class LicenseRecheckSlot {
    STARTUP,
    TRANSITION,
}

@Service(Service.Level.APP)
internal class LicenseRecheckScheduler(
    private val alarmFactory: (LicenseRecheckSlot, Disposable) -> Alarm = { _, parent ->
        Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    },
) : Disposable {
    private val alarms by lazy(LazyThreadSafetyMode.NONE) {
        LicenseRecheckSlot.entries.associateWith { slot -> alarmFactory(slot, this) }
    }

    @RequiresEdt
    fun schedule(
        slot: LicenseRecheckSlot,
        delayMs: Long,
        action: () -> Unit,
    ) {
        val alarm = alarms.getValue(slot)
        alarm.cancelAllRequests()
        alarm.addRequest(action, delayMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): LicenseRecheckScheduler = service()
    }
}
