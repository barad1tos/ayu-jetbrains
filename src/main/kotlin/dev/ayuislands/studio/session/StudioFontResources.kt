package dev.ayuislands.studio.session

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontInstallConsent
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontUninstaller
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.FontJobStatus
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioMetadataCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Retains completed file operations until their exact metadata is durably recorded. */
internal class StudioFontResources(
    private val scope: CoroutineScope,
    private val document: () -> StudioDocument,
    private val submitMetadata: suspend (StudioMetadataCommand) -> AcceptanceResult,
    private val entitlement: () -> LicenseEntitlement,
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) {
    private val pending = linkedMapOf<OperationStamp, ResourceWork>()
    private val settlement = Mutex()

    /** Called on EDT; the app-owned operation retains the originating Settings modality after detachment. */
    fun start(
        project: Project?,
        binding: StudioSessionBinding,
        job: FontJob,
    ) {
        start(project, job, compactConsent = true) { status ->
            binding.controller.dispatch(
                SessionEvent.EffectCompleted(
                    CallbackStamp(job.operation, "resource-complete"),
                    SessionEffect.ResourceFinished(status),
                ),
            )
        }
    }

    /**
     * Resource inventory is committed before notifying the originating view;
     * appearance remains an explicit command.
     */
    fun start(
        project: Project?,
        job: FontJob,
        compactConsent: Boolean,
        completed: (FontJobStatus) -> Unit,
    ) {
        val modality = ModalityState.current().asContextElement()
        scope.launch(nativeContext + modality) {
            if (job.operation in pending) return@launch
            val work = ResourceWork(job, modality, compactConsent, completed)
            pending[job.operation] = work
            val entry =
                FontPreset.entries
                    .mapNotNull(
                        FontCatalog::forPreset,
                    ).firstOrNull { it.familyName == job.fontId }
            if (entry == null) {
                finish(work, FontJobStatus.FAILED)
            } else if (job.action == FontAction.UNINSTALL) {
                remove(project, work, entry)
            } else {
                install(project, work, entry)
            }
        }
    }

    /** Replays only metadata submission, never download, registration, or file deletion. */
    suspend fun retryPending(): List<AcceptanceResult> =
        withContext(nativeContext) {
            settlement.withLock {
                pending.values
                    .toList()
                    .filter { it.completion != null }
                    .map { settle(it) }
            }
        }

    private fun install(
        project: Project?,
        work: ResourceWork,
        entry: FontCatalog.Entry,
    ) {
        val confirmed = entitlement()
        if (confirmed == LicenseEntitlement.UNKNOWN) {
            finish(work, FontJobStatus.FAILED)
            return
        }
        val consent = FontInstallConsent.confirmInstall(entry, project, work.compactConsent)
        if (consent == null) {
            finish(work, FontJobStatus.DECLINED)
            return
        }
        LicenseChecker.withConfirmedEntitlement(confirmed) {
            FontInstaller.installResource(entry, consent, project) { result ->
                scope.launch(nativeContext + work.modality) {
                    when (result) {
                        is FontInstaller.InstallResult.Success -> complete(work, Completion.Installed(result))
                        is FontInstaller.InstallResult.Failure -> finish(work, FontJobStatus.FAILED)
                        FontInstaller.InstallResult.Cancelled -> finish(work, FontJobStatus.CANCELLED)
                    }
                }
            }
        }
    }

    private fun remove(
        project: Project?,
        work: ResourceWork,
        entry: FontCatalog.Entry,
    ) {
        val resources = document().auxiliary.resources
        val installed = resources.records.single { it.key.name == "installedFonts" }.value as RawValue.OrderedSet
        val family = entry.preset.fontAliases.firstOrNull { it in installed.values }
        if (family == null) {
            finish(work, FontJobStatus.FAILED)
            return
        }
        val files = resources.records.single { it.key.name == "installedFontFiles" }.value as RawValue.OrderedMap
        val encoded = files.entries.singleOrNull { it.key == family }?.value as? RawValue.Text
        val paths = AyuIslandsState.decodeFontPaths(encoded?.value)
        if (!FontInstallConsent.confirmUninstall(entry, project, FontInstaller.platformFontDir().absolutePath)) {
            finish(work, FontJobStatus.DECLINED)
            return
        }
        FontUninstaller.uninstallResource(entry, family, paths, project) { result ->
            scope.launch(nativeContext + work.modality) {
                if (result is FontUninstaller.UninstallResult.Failure) {
                    finish(work, FontJobStatus.FAILED)
                } else {
                    complete(work, Completion.Removed(result))
                }
            }
        }
    }

    private suspend fun complete(
        work: ResourceWork,
        completion: Completion,
    ) {
        work.completion = completion
        settlement.withLock { settle(work) }
    }

    private suspend fun settle(work: ResourceWork): AcceptanceResult {
        val completion = requireNotNull(work.completion)
        val result = submitMetadata(completion.command(work.job))
        if (result is AcceptanceResult.Accepted || result == AcceptanceResult.NoChanges) {
            finish(work, completion.status)
        } else if (work.notification == null) {
            val notification =
                Notification(
                    "Ayu Islands",
                    "Font change needs attention",
                    "The font files have changed, but the installation record could not be saved. Retry to save it.",
                    NotificationType.WARNING,
                )
            notification.addAction(
                NotificationAction.createSimpleExpiring("Retry") {
                    work.notification = null
                    scope.launch(nativeContext + ModalityState.current().asContextElement()) { retryPending() }
                },
            )
            work.notification = notification
            Notifications.Bus.notify(notification)
        }
        return result
    }

    private fun finish(
        work: ResourceWork,
        status: FontJobStatus,
    ) {
        pending.remove(work.job.operation)
        work.notification?.expire()
        work.completed(status)
    }

    private class ResourceWork(
        val job: FontJob,
        val modality: CoroutineContext,
        val compactConsent: Boolean,
        val completed: (FontJobStatus) -> Unit,
    ) {
        var completion: Completion? = null
        var notification: Notification? = null
    }

    private sealed interface Completion {
        val status: FontJobStatus

        fun command(job: FontJob): StudioMetadataCommand

        data class Installed(
            val result: FontInstaller.InstallResult.Success,
        ) : Completion {
            override val status = if (result.availableInJvm) FontJobStatus.INSTALLED else FontJobStatus.RESTART_REQUIRED

            override fun command(job: FontJob) =
                StudioMetadataCommand.FontInstalled(
                    job,
                    result.familyName,
                    AyuIslandsState.encodeFontPaths(result.installedPaths.map(::File)),
                )
        }

        data class Removed(
            val result: FontUninstaller.UninstallResult,
        ) : Completion {
            override val status =
                if (result is FontUninstaller.UninstallResult.Partial) FontJobStatus.PARTIAL else FontJobStatus.REMOVED

            override fun command(job: FontJob) = StudioMetadataCommand.FontRemoved(job, result.familyName)
        }
    }
}
