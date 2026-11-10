package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import dev.ayuislands.gitpanel.GitProportionCheckpoint
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.settings.WorkspaceSurface
import dev.ayuislands.settings.WorkspaceToggle
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.PortableReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Captures selected native Workspace inputs across the projects retained by the current runtime lease. */
internal class StudioWorkspaceParticipant(
    private val host: StudioWorkspaceHost = IdeWorkspaceHost(),
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) : StudioEffectParticipant {
    override val owner: EffectOwner = EffectOwner.WORKSPACE

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val observed = checkNotNull(runtime.observation) { "Workspace native observation is unavailable" }
            val references =
                WorkspaceReferences(
                    input.candidate.header.transactionId,
                    checkNotNull(runtime.native.currentProcess) { "Workspace process identity is unconfirmed" },
                )
            val selected =
                StudioWorkspaceProjection.resolve(
                    input.candidate.shared.workspace,
                    input.workspaceFields,
                    runtime.native.isLicensed,
                )
            val projects =
                observed.managers
                    .map { it.project }
                    .distinct()
                    .sortedBy { it.locationHash }
            check(projects.none { it.isDisposed }) { "Workspace project census changed during capture" }
            val root = captureRoot(input, references, selected, projects)
            var properties: StudioWorkspaceProperties? = null
            val resolveProperty: (String) -> GitProportionCheckpoint = { key ->
                val batch =
                    properties ?: StudioWorkspaceProperties(
                        references,
                        checkNotNull(host.properties()) { "The existing Git property owner is unavailable" },
                    ).also { properties = it }
                batch.capture(key)
            }
            val fields = projects.flatMap { captureProject(it, selected, references, resolveProperty) }
            StudioWorkspaceOperation(
                fields,
                nativeContext,
                publication = { root?.runtimeChanges ?: StudioList(emptyList()) },
                properties = properties,
                root = root,
            )
        }

    override suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            require(receipts.all { it.owner == owner })
            require(
                receipts.all {
                    it.identity.ownerToken.startsWith(
                        "workspace/${input.candidate.header.transactionId}/",
                    )
                },
            ) {
                "Workspace receipt belongs to another operation"
            }
            val rootReceipts = receipts.filter { it.identity.field == WorkspaceNativeField.ROOT_PATH.name }
            val propertyReceipts = receipts.filter { it.identity.field == WorkspaceNativeField.GIT_PROPERTY.name }
            val ephemeral = receipts.filter { it !in rootReceipts && it !in propertyReceipts }
            val root =
                rootReceipts.takeIf { it.isNotEmpty() }?.let {
                    StudioWorkspaceRoot.recover(it, runtime.native, host.rootLease(), host.rootRegistry())
                }
            val properties = recoverProperties(propertyReceipts, runtime.native)
            val unavailable = if (properties == null) propertyReceipts else emptyList()
            StudioWorkspaceOperation(
                emptyList(),
                nativeContext,
                publication = null,
                properties = properties,
                root = root,
                lostFields =
                    StudioWorkspaceReceipts.recoverLost(
                        ephemeral + unavailable,
                        runtime.native.currentProcess,
                    ),
            )
        }

    private fun recoverProperties(
        receipts: List<PortableReceipt>,
        facts: StudioNativeFacts,
    ): StudioWorkspaceProperties? {
        if (receipts.isEmpty()) return null
        val process = StudioNativeEvidence.readProcess(receipts.first().before)
        require(
            receipts.all {
                StudioNativeEvidence.readProcess(it.before) == process &&
                    StudioNativeEvidence.readProcess(it.lastWritten) == process
            },
        ) { "Git property receipt process provenance differs" }
        if (StudioProcessPolicy.compare(process, facts.currentProcess) != StudioProcessRelation.DIFFERENT) return null
        return host.properties()?.let { StudioWorkspaceProperties.recover(receipts, it) }
    }

    private fun captureRoot(
        input: StudioEffectInput,
        references: WorkspaceReferences,
        selected: StudioWorkspaceSelection,
        projects: List<Project>,
    ): StudioWorkspaceRoot? {
        if (WorkspaceToggle.PROJECT_ROOT_PATH !in selected.toggles) return null
        return StudioWorkspaceRoot.prepare(
            input,
            references,
            host,
            projects.toSet(),
            selected.settings.hideProjectRootPath,
        )
    }

    private fun captureProject(
        project: Project,
        selected: StudioWorkspaceSelection,
        references: WorkspaceReferences,
        proportions: (String) -> GitProportionCheckpoint,
    ): List<StudioWorkspaceFields> =
        buildList {
            captureEditor(project, selected, references)?.let(::add)
            addAll(captureProjectView(project, selected, references))
            if (WorkspaceSurface.COMMIT in selected.widths || selected.isCommitRendererSelected) {
                host.commit(project)?.let { manager ->
                    if (selected.isCommitRendererSelected) {
                        add(
                            StudioWorkspaceFields.commitRenderer(
                                manager.prepareRenderer(commitState(selected.settings)),
                                references,
                            ),
                        )
                    }
                    if (WorkspaceSurface.COMMIT in selected.widths) {
                        manager
                            .prepareWidth(
                                selected.settings.commit,
                            )?.let { add(StudioWorkspaceFields.width(it, references)) }
                    }
                }
            }
            if (WorkspaceSurface.GIT in selected.widths) {
                host
                    .git(
                        project,
                    )?.capture(selected.settings.git, proportions)
                    ?.let { add(StudioWorkspaceFields.git(it, references)) }
            }
        }

    private fun captureEditor(
        project: Project,
        selected: StudioWorkspaceSelection,
        references: WorkspaceReferences,
    ): StudioWorkspaceFields? {
        val vertical =
            selected.settings.hideEditorVertical.takeIf {
                WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR in
                    selected.toggles
            }
        val horizontal =
            selected.settings.hideEditorHorizontal.takeIf {
                WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR in
                    selected.toggles
            }
        if (vertical == null && horizontal == null) return null
        return host.editor(project)?.capture(vertical, horizontal)?.let { StudioWorkspaceFields.editor(it, references) }
    }

    private fun captureProjectView(
        project: Project,
        selected: StudioWorkspaceSelection,
        references: WorkspaceReferences,
    ): List<StudioWorkspaceFields> {
        val root = WorkspaceToggle.PROJECT_ROOT_PATH in selected.toggles
        val scrollbar = WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR in selected.toggles
        val width = WorkspaceSurface.PROJECT in selected.widths
        if (!root && !scrollbar && !width) return emptyList()
        val manager = host.projectView(project) ?: return emptyList()
        return buildList {
            if (root) {
                add(
                    StudioWorkspaceFields.projectRenderer(
                        manager.prepareRootRenderer(selected.settings.hideProjectRootPath),
                        references,
                    ),
                )
            }
            if (scrollbar) {
                manager.prepareScrollbar(selected.settings.hideProjectHorizontal)?.let {
                    add(StudioWorkspaceFields.projectScrollbar(it, references))
                }
            }
            if (width) {
                manager
                    .prepareWidth(
                        selected.settings.project,
                    )?.let { add(StudioWorkspaceFields.width(it, references)) }
            }
        }
    }

    private fun commitState(settings: WorkspaceSettings): AyuIslandsState =
        AyuIslandsState().apply {
            commitPanelWidthMode = settings.commit.mode
            commitPanelPathDisplayMode = settings.paths.mode
            commitPanelPathMinHiddenLevels = settings.paths.minimum
            commitPanelPathMaxHiddenLevels = settings.paths.maximum
        }
}
