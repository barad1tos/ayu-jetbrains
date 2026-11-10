package dev.ayuislands.studio

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import dev.ayuislands.AyuLaf
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.CgpAccessProvider
import dev.ayuislands.accent.SystemAccentProvider
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.indent.IrAccessProvider
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.licensing.LicenseRecheckScheduler
import dev.ayuislands.licensing.resolveEntitlement
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.ContrastAwareColorGenerator
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.ProInitialization
import dev.ayuislands.studio.model.SessionIdentity
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.persistence.StudioMetadataStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.runtime.FocusSnapshot
import dev.ayuislands.studio.runtime.StudioRuntimeBinding
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator
import dev.ayuislands.studio.runtime.StudioRuntimeObservation
import dev.ayuislands.studio.runtime.StudioRuntimeSource
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.QuickCommandPreparation
import dev.ayuislands.studio.session.ScheduledCall
import dev.ayuislands.studio.session.SessionScheduler
import dev.ayuislands.studio.session.StudioAccentParticipant
import dev.ayuislands.studio.session.StudioAccentPolicy
import dev.ayuislands.studio.session.StudioAccentResolver
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioAcceptanceCollector
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioFontParticipant
import dev.ayuislands.studio.session.StudioFontResources
import dev.ayuislands.studio.session.StudioGlowParticipant
import dev.ayuislands.studio.session.StudioImmediateExecution
import dev.ayuislands.studio.session.StudioIntegrationCapture
import dev.ayuislands.studio.session.StudioNativeEnvironment
import dev.ayuislands.studio.session.StudioNativeHost
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.session.StudioQuickCommands
import dev.ayuislands.studio.session.StudioSessionIo
import dev.ayuislands.studio.session.StudioSessionRecords
import dev.ayuislands.studio.session.StudioSessions
import dev.ayuislands.studio.session.StudioSyntaxParticipant
import dev.ayuislands.studio.session.StudioSystemParticipant
import dev.ayuislands.studio.session.StudioTargets
import dev.ayuislands.studio.session.StudioVcsParticipant
import dev.ayuislands.studio.session.StudioWorkspaceParticipant
import dev.ayuislands.studio.session.observeStudioProcess
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/** The production composition root. All producers share Sessions, one store, and one transaction lane. */
internal class StudioApplicationGraph(
    private val store: StudioAppearanceStore,
    capture: StudioLegacyCapture,
    parentScope: CoroutineScope,
    val projects: StudioProjects,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.EDT)
    private val transactionMutex = Mutex()
    private val entitlementUpdates = Mutex()
    private val source = StudioIdeRuntimeSource
    private val runtime = StudioRuntimeCoordinator(FocusSnapshot(StudioTarget.Global, 0))
    private lateinit var retainedSessions: StudioSessions
    private lateinit var metadata: StudioMetadataStore
    private lateinit var collector: StudioAcceptanceCollector
    lateinit var immediate: StudioImmediateExecution
        private set
    lateinit var resources: StudioFontResources
        private set
    lateinit var runtimeBinding: StudioRuntimeBinding
        private set
    private val native =
        StudioNativeHost(
            focus = { requireNotNull(projects.focus()) { "Studio project context is unavailable" } },
            entitlement = ::entitlement,
            accent = ::acceptedAccent,
            managers = { projects.openProjects().map(GlowOverlayManager::getInstance) },
            process = observeStudioProcess().getOrNull(),
        )
    val application =
        StudioApplication(
            store,
            StudioBootstrap(store, capture::capture, transactionMutex),
            transactionMutex,
            ::createSessions,
            startRuntime = { initializeRuntime() },
            recoverMetadata = { metadata.recoverPending() },
        )
    val sessions: StudioSessions? get() = application.sessions

    private fun acceptedAccent(): StudioAccentResult? {
        val focus = projects.focus() ?: return null
        val confirmed = entitlement()
        if (confirmed == LicenseEntitlement.UNKNOWN) return null
        return StudioAccentResolver(
            StudioEffectInput(retainedSessions.document, focus.runtime.target, StudioList(emptyList())),
            AyuVariant.fromThemeName(AyuLaf.currentThemeName()),
            SystemAccentProvider.resolve(),
            confirmed,
        ).resolve(focus.project, projects.openProjects())
    }

    /** Reads raw Marketplace evidence without mutating the legacy settings service. */
    fun entitlement(): LicenseEntitlement {
        val settings =
            requireNotNull(
                StudioLegacyProjection.project(retainedSessions.document, StudioTarget.Global),
            ).settings
        return resolveEntitlement(LicenseChecker.isLicensed(), settings.lastKnownLicensedMs, source.clock().epochMillis)
            .entitlement
    }

    suspend fun submitMetadata(command: StudioMetadataCommand): AcceptanceResult {
        val result = metadata.submit(command, source.transactionId())
        if (result is AcceptanceResult.Accepted || result == AcceptanceResult.NoChanges) {
            withContext(Dispatchers.EDT) { runtimeBinding.resumePending() }
        }
        return result
    }

    /** Returns the next licensing observation delay; null means no recheck is due. */
    suspend fun refreshEntitlement(): Long? =
        withContext(Dispatchers.EDT) {
            entitlementUpdates.withLock { refreshObservedEntitlement() }
        }

    private suspend fun refreshObservedEntitlement(): Long? {
        if (immediate.retryPending() is ImmediateExecutionResult.Pending) return LicenseRecheckScheduler.RETRY_DELAY_MS
        val rawLicense = LicenseChecker.isLicensed()
        val observedAt = source.clock().epochMillis
        val recorded = submitMetadata(StudioMetadataCommand.Entitlement(rawLicense, observedAt))
        if (recorded !is AcceptanceResult.Accepted && recorded != AcceptanceResult.NoChanges) {
            return LicenseRecheckScheduler.RETRY_DELAY_MS
        }
        // UNKNOWN is not license loss and cannot admit a new native transition.
        if (rawLicense == null) return LicenseRecheckScheduler.RETRY_DELAY_MS
        val settings =
            requireNotNull(
                StudioLegacyProjection.project(retainedSessions.document, StudioTarget.Global),
            ).settings
        val confirmed = resolveEntitlement(rawLicense, settings.lastKnownLicensedMs, observedAt).entitlement
        val needsDefaults = !settings.proDefaultsApplied || !settings.workspaceDefaultsApplied
        if (confirmed == LicenseEntitlement.LICENSED &&
            needsDefaults &&
            !initializePro()
        ) {
            return LicenseRecheckScheduler.RETRY_DELAY_MS
        }
        runtimeBinding.appearanceChanged().join()
        val accepted =
            requireNotNull(
                StudioLegacyProjection.project(retainedSessions.document, StudioTarget.Global),
            ).settings
        return LicenseChecker.nextRecheckDelayMs(
            accepted.lastConfirmedEntitlement,
            accepted.lastKnownLicensedMs,
            source.clock().epochMillis,
        )
    }

    private suspend fun initializePro(): Boolean {
        val owner = sessions ?: return false
        val focus = projects.focus() ?: return false
        val target = projects.observe(focus.runtime.target)
        if (target.presence != TargetPresence.ACTIVE) return false
        val baseline = owner.document
        val record =
            StudioSessionRecords.current(
                baseline,
                target.target,
                source.transactionId(),
                DraftId(source.transactionId()),
            ) ?: return false
        val request =
            AcceptanceRequest(
                session = record,
                transactionId = source.transactionId(),
                targetGeneration = target.generation,
                origin = AcceptanceOrigin.INITIALIZATION,
                initialization =
                    ProInitialization(
                        baseline,
                        StudioLegacyFields.capture(
                            freshSettingsState(),
                            SyntaxIntensityBaseState(),
                            AccentMappingsState(),
                        ),
                    ),
            )
        return when (val initialized = immediate.submit(request)) {
            is ImmediateExecutionResult.Pending -> false
            is ImmediateExecutionResult.Complete ->
                initialized.acceptance is AcceptanceResult.Accepted ||
                    initialized.acceptance == AcceptanceResult.NoChanges
        }
    }

    /** Immediate gestures capture one target incarnation and never register a second Settings session. */
    suspend fun submitQuick(
        target: TargetObservation,
        command: StudioQuickCommand,
        baseline: StudioDocument? = null,
    ): ImmediateExecutionResult =
        withContext(Dispatchers.EDT) {
            val owner = sessions ?: return@withContext blockedCommand(AcceptanceBlock.READ_ONLY)
            val observed = projects.observe(target.target)
            if (observed.generation !=
                target.generation
            ) {
                return@withContext blockedCommand(AcceptanceBlock.TARGET_REPLACED)
            }
            if (observed.presence ==
                TargetPresence.UNAVAILABLE
            ) {
                return@withContext blockedCommand(AcceptanceBlock.TARGET_UNAVAILABLE)
            }
            val document = owner.document
            val record =
                StudioSessionRecords.current(
                    baseline ?: document,
                    target.target,
                    source.transactionId(),
                    DraftId(source.transactionId()),
                ) ?: return@withContext blockedCommand(AcceptanceBlock.TARGET_UNAVAILABLE)
            val state =
                StudioSessionState(
                    SessionIdentity(record.id, record.target, target.generation),
                    record,
                    SessionPhase.Editing,
                )
            val context = collector.view(state, document).copy(liveSessions = owner.liveSessions)
            when (
                val prepared =
                    StudioQuickCommands.prepare(
                        command,
                        state,
                        SessionInputs(document, context),
                        source.transactionId(),
                    )
            ) {
                is QuickCommandPreparation.Ready -> immediate.submit(prepared.request)
                is QuickCommandPreparation.Blocked ->
                    ImmediateExecutionResult.Complete(
                        AcceptanceResult.Blocked(prepared.reasons),
                    )
                is QuickCommandPreparation.ReviewRequired ->
                    ImmediateExecutionResult.Complete(
                        AcceptanceResult.ReviewRequired(prepared.items),
                    )
            }
        }

    private fun blockedCommand(reason: AcceptanceBlock): ImmediateExecutionResult =
        ImmediateExecutionResult.Complete(AcceptanceResult.Blocked(StudioList(listOf(reason))))

    private fun createSessions(initial: StudioDocument): StudioSessions {
        collector = StudioAcceptanceCollector(native, projects::observe, ::authority)
        val platform = ApplicationManager.getApplication()
        val integrations =
            StudioIntegrationCapture(
                platform.getService(CgpAccessProvider::class.java),
                platform.getService(IrAccessProvider::class.java),
            )
        val participants =
            listOf(
                StudioSystemParticipant(),
                StudioAccentParticipant(integrations = integrations),
                StudioFontParticipant(),
                StudioSyntaxParticipant(),
                StudioVcsParticipant(),
                StudioGlowParticipant(),
                StudioWorkspaceParticipant(),
            )
        val owner =
            StudioSessions(
                initial,
                store,
                scope,
                transactionMutex,
                collector::view,
                coordinator = { io ->
                    val observed =
                        object : StudioSessionIo by io {
                            override suspend fun publish(update: StudioApplyUpdate) {
                                io.publish(update)
                                if (update is StudioApplyUpdate.Committed) {
                                    withContext(Dispatchers.EDT) { StudioAccentPublication.publish(acceptedAccent()) }
                                }
                            }

                            override suspend fun observe(
                                request: AcceptanceRequest,
                                document: StudioDocument,
                            ): AcceptanceContext {
                                val collected = collector.observe(request, document)
                                return withContext(Dispatchers.EDT) {
                                    collected.copy(liveSessions = retainedSessions.liveSessions)
                                }
                            }
                        }
                    val environment =
                        StudioNativeEnvironment(
                            runtime,
                            source,
                            observed,
                            native,
                            { runtimeBinding.consume(it) },
                        )
                    StudioApplyCoordinator(store, environment, participants, transactionMutex)
                },
                ids = source::transactionId,
            )
        retainedSessions = owner
        immediate = StudioImmediateExecution(owner.coordinator, owner::finishRecord)
        metadata = StudioMetadataStore(store, owner, transactionMutex)
        resources = StudioFontResources(scope, { owner.document }, ::submitMetadata, ::entitlement)
        val scheduler =
            SessionScheduler { millis, callback ->
                val job =
                    scope.launch(Dispatchers.EDT) {
                        delay(millis.milliseconds)
                        callback()
                    }
                ScheduledCall { job.cancel() }
            }
        runtimeBinding =
            StudioRuntimeBinding(
                runtime,
                owner.coordinator,
                source,
                observe = {
                    if (projects.focus() ==
                        null
                    ) {
                        null
                    } else {
                        native.observe().let { StudioRuntimeObservation(it, availability(it)) }
                    }
                },
                accepted = { owner.document },
                scope = scope,
                scheduler = scheduler,
                onFault = { failure ->
                    StudioNotifications.runtime(failure) { scope.launch { runtimeBinding.retry().join() } }
                },
            )
        return owner
    }

    /** A later project open reuses the same idempotent current-copy command as application startup. */
    suspend fun initializeProject(project: Project): AcceptanceResult =
        withContext(Dispatchers.EDT) {
            val target =
                projects.capture(project)?.target as? StudioTarget.Project
                    ?: return@withContext AcceptanceResult.Blocked(
                        StudioList(listOf(AcceptanceBlock.TARGET_UNAVAILABLE)),
                    )
            val origin =
                (runtime.state as? RotationRuntimeState.Loaded)
                    ?.snapshot
                    ?.inputs
                    ?.owner
                    ?.target
                    ?: StudioTarget.Global
            submitMetadata(StudioMetadataCommand.InitializeProject(target.key, project.name, origin))
        }

    private suspend fun initializeRuntime(): AcceptanceResult {
        if (withContext(ioContext) { store.load() } !is StoreLoad.Recovering) {
            val initialization = initializeProjects()
            if (initialization != AcceptanceResult.NoChanges) return initialization
        }
        if (projects.focus() == null) return AcceptanceResult.NoChanges
        if (runtime.state is RotationRuntimeState.Loading) {
            runtimeBinding.start().join()
        } else {
            runtimeBinding
                .retry()
                .join()
        }
        return AcceptanceResult.NoChanges
    }

    private suspend fun initializeProjects(): AcceptanceResult {
        for (project in projects.openProjects()) {
            val result = initializeProject(project)
            if (result is AcceptanceResult.Accepted || result == AcceptanceResult.NoChanges) continue
            if (project.isDisposed || project !in projects.openProjects()) continue
            return result
        }
        return AcceptanceResult.NoChanges
    }

    private fun authority(): AuthorityStatus =
        when (application.state) {
            is StudioApplicationState.Ready -> AuthorityStatus.READY
            is StudioApplicationState.Unavailable, is StudioApplicationState.InitializationBlocked ->
                AuthorityStatus.RECOVERY
            StudioApplicationState.Loading, StudioApplicationState.Closed -> AuthorityStatus.READ_ONLY
        }

    private fun availability(observed: StudioNativeObservation): RuntimeAvailability {
        val document = retainedSessions.document
        val target = observed.focus.runtime.target
        val appearance = StudioTargets.current(document, target)?.appearance
        val settings = requireNotNull(StudioLegacyProjection.project(document, StudioTarget.Global)).settings
        val definitive =
            when (observed.entitlement) {
                LicenseEntitlement.LICENSED -> true
                LicenseEntitlement.UNLICENSED -> false
                LicenseEntitlement.UNKNOWN ->
                    when (LicenseEntitlement.fromName(settings.lastConfirmedEntitlement)) {
                        LicenseEntitlement.LICENSED -> true
                        LicenseEntitlement.UNLICENSED -> false
                        LicenseEntitlement.UNKNOWN -> null
                    }
            }
        return RuntimeAvailability(
            definitive,
            appearance?.let { StudioAccentPolicy.followsSystem(it, document.shared.systemAccentPolicy) } == true,
            observed.variant,
            projects.observe(target).presence == TargetPresence.ACTIVE && appearance != null,
            recoveryPending =
                runtime.state is RotationRuntimeState.Applying ||
                    (runtime.state as? RotationRuntimeState.Faulted.Loaded)?.pendingOperation != null,
        )
    }

    suspend fun close() {
        withContext(NonCancellable) {
            withContext(Dispatchers.EDT) {
                if (::runtimeBinding.isInitialized) runtimeBinding.close()
            }
            lifetime.cancelAndJoin()
            application.close()
        }
    }

    companion object {
        suspend fun open(
            directory: Path,
            options: Path,
            scope: CoroutineScope,
            projects: StudioProjects = StudioProjects(),
        ): StudioApplicationGraph =
            withContext(NonCancellable + Dispatchers.IO) {
                // Ownership must reach the caller even if its request is cancelled after the file lock opens.
                val openedStore = StudioAppearanceStore(openStudioFiles(directory))
                var transferred = false
                AutoCloseable { if (!transferred) openedStore.close() }.use {
                    val graph =
                        withContext(Dispatchers.EDT) {
                            StudioApplicationGraph(
                                openedStore,
                                StudioLegacyCapture(
                                    options,
                                    openedStore,
                                    StudioIdeRuntimeSource::transactionId,
                                    System.getProperty("user.home"),
                                ),
                                scope,
                                projects,
                            )
                        }
                    transferred = true
                    graph
                }
            }
    }
}

private object StudioIdeRuntimeSource : StudioRuntimeSource {
    override fun clock() = ClockSample(System.currentTimeMillis(), System.nanoTime())

    override fun operationId(): UUID = UUID.randomUUID()

    override fun transactionId(): String = UUID.randomUUID().toString()

    override fun randomColor(variant: AyuVariant): String = ContrastAwareColorGenerator.generate(variant)
}
