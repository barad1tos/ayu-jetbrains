package dev.ayuislands.studio

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPreset
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsTable
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.CapabilitySnapshot
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.QuickCommandPreparation
import dev.ayuislands.studio.session.SessionTransitions
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioFontEditing
import dev.ayuislands.studio.session.StudioGlowEditing
import dev.ayuislands.studio.session.StudioMappingsEditing
import dev.ayuislands.studio.session.StudioPluginsEditing
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.session.StudioQuickCommands
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioEntryPointParityTest {
    @Test
    fun `external popup color matches Plugins choice and preserves all unrelated shared preferences`() {
        val document = customDocument()
        val initial = sessionState(document)
        val inputs = sessionInputs(document, initial)
        val request = ready(StudioQuickCommand.ExternalAccent("#123456"), initial, inputs)
        val settings =
            settingsRequest(
                initial,
                inputs,
                StudioPluginsEditing.edits(document.shared.plugins, PluginsPanelEvent.Accent("#123456")),
            )
        assertEquals(settings.copy(origin = AcceptanceOrigin.QUICK_SWITCHER), request)
        val candidate = commit(request, inputs).candidate
        assertEquals(document.currents, candidate.currents)
        assertEquals(document.catalog, candidate.catalog)
        assertEquals(document.shared.copy(plugins = candidate.shared.plugins), candidate.shared)
        assertEquals(RawValue.Text("#123456"), candidate.shared.plugins.value("externalThemeAccent"))
        assertEquals(RawValue.Text("MANUAL"), candidate.shared.plugins.value("externalThemeAccentSource"))
        val changed = setOf("externalThemeAccent", "externalThemeAccentSource")
        assertEquals(
            document.shared.plugins.records
                .filter { it.key.name !in changed },
            candidate.shared.plugins.records.filter {
                it.key.name !in
                    changed
            },
        )
    }

    @Test
    fun `unpin removes only the captured project mapping without reordering siblings`() {
        val original = projectDocument()
        val pins =
            RawValue.OrderedMap(
                StudioList(
                    listOf(
                        RawValue.Entry("/work/B", RawValue.Text("#112233")),
                        RawValue.Entry("/work/A", RawValue.Text("#445566")),
                        RawValue.Entry("/closed/C", RawValue.Text("#778899")),
                    ),
                ),
            )
        val document =
            original.copy(
                shared = original.shared.copy(mappings = pinFields(original.shared.mappings, pins)),
            )
        val initial = projectState(document)
        val baseInputs = sessionInputs(document, initial)
        val inputs =
            baseInputs.copy(
                context = baseInputs.context.copy(target = baseInputs.context.target.copy(generation = 17)),
            )
        val request = ready(StudioQuickCommand.Unpin, initial, inputs)
        val settings =
            settingsRequest(
                initial,
                inputs,
                StudioMappingsEditing.edits(
                    document.shared.mappings,
                    MappingsEdit.Remove(MappingsTable.PROJECTS, "/work/A"),
                ),
            )

        assertEquals(settings.copy(origin = AcceptanceOrigin.QUICK_SWITCHER), request)
        val plan = commit(request, inputs)
        assertEquals(
            listOf(
                "/work/B",
                "/closed/C",
            ),
            assertIs<RawValue.OrderedMap>(
                plan.candidate.shared.mappings
                    .value("projectAccents"),
            ).entries.map {
                it.key
            },
        )
        assertEquals(document.currents, plan.candidate.currents)
        assertEquals(document.catalog, plan.candidate.catalog)
        assertEquals(document.shared.copy(mappings = plan.candidate.shared.mappings), plan.candidate.shared)
    }

    @Test
    fun `onboarding accepts font and glow choices together while preserving all dormant preferences`() {
        val document = customDocument()
        val initial = sessionState(document)
        val inputs = sessionInputs(document, initial)
        val draft = requireNotNull(initial.record).work.drafts.single()
        for (glow in listOf(null, GlowPreset.NEON)) {
            val request = ready(StudioQuickCommand.Onboarding(FontPreset.WHISPER, glow), initial, inputs)
            val edits =
                buildList {
                    addAll(StudioFontEditing.edits(draft, FontPanelEvent.Enable(true)))
                    addAll(StudioFontEditing.edits(draft, FontPanelEvent.Preset(FontPreset.WHISPER)))
                    if (glow != null) {
                        addAll(StudioGlowEditing.edits(draft, GlowPanelEvent.Enable(true)))
                        addAll(StudioGlowEditing.edits(draft, GlowPanelEvent.Preset(glow)))
                    }
                }
            val settings = settingsRequest(initial, inputs, edits)

            assertEquals(settings.copy(origin = AcceptanceOrigin.QUICK_SWITCHER), request)
            val selected =
                request.session.work.drafts
                    .single()
                    .appearance
            assertEquals(RawValue.Text(FontPreset.WHISPER.name), selected.font.value("fontPresetName"))
            assertEquals(
                draft.appearance.font.value("fontPresetCustomizations"),
                selected.font.value("fontPresetCustomizations"),
            )
            assertEquals(draft.appearance.accent, selected.accent)
            assertEquals(draft.appearance.syntax, selected.syntax)
            assertEquals(draft.appearance.vcs, selected.vcs)
            if (glow == null) assertEquals(draft.appearance.glow, selected.glow)
        }
    }

    @Test
    fun `popup accent gestures match Settings requests without rewriting sibling preferences`() {
        val document = customDocument()
        val initial = sessionState(document)
        val inputs = sessionInputs(document, initial)
        val draft = requireNotNull(initial.record).work.drafts.single()
        val gestures =
            listOf(
                AccentPanelEvent.Color(AyuVariant.DARK, "#AABBCC") to
                    ("darkAccent" to RawValue.Text("#AABBCC")),
                AccentPanelEvent.ChromeEnabled(true) to
                    ("chromeTintingEnabled" to RawValue.BooleanValue(true)),
                AccentPanelEvent.FollowSystem(false) to
                    ("followSystemAccent" to RawValue.BooleanValue(false)),
                AccentPanelEvent.RotationEnabled(true) to
                    ("accentRotationEnabled" to RawValue.BooleanValue(true)),
            )

        for ((event, change) in gestures) {
            val request = ready(StudioQuickCommand.Accent(event), initial, inputs)
            val settings = settingsRequest(initial, inputs, StudioAccentEditing.edits(draft, document.shared, event))

            assertEquals(settings.copy(origin = AcceptanceOrigin.QUICK_SWITCHER), request)
            val after =
                request.session.work.drafts
                    .single()
                    .appearance
            assertEquals(change.second, after.accent.value(change.first))
            assertEquals(
                StudioAppearanceFields.fields(draft.appearance).filterNot { it.key.name == change.first },
                StudioAppearanceFields.fields(after).filterNot { it.key.name == change.first },
            )
            assertTrue(
                request.session.work.catalog
                    .isEmpty(),
            )
            assertTrue(
                request.session.work.shared
                    .isEmpty(),
            )
            assertEquals(document, inputs.latest)
            assertEquals(sessionState(document), initial)
        }
    }

    @Test
    fun `Glow master command preserves every retained style waveform and island choice`() {
        val document = customDocument()
        val initial = sessionState(document)
        val inputs = sessionInputs(document, initial)
        val draft = requireNotNull(initial.record).work.drafts.single()

        val request = ready(StudioQuickCommand.GlowEnabled(true), initial, inputs)
        val settings = settingsRequest(initial, inputs, StudioGlowEditing.edits(draft, GlowPanelEvent.Enable(true)))

        assertEquals(settings.copy(origin = AcceptanceOrigin.QUICK_SWITCHER), request)
        val after =
            request.session.work.drafts
                .single()
                .appearance
        assertEquals(RawValue.BooleanValue(true), after.glow.value("glowEnabled"))
        assertEquals(
            StudioAppearanceFields.fields(draft.appearance).filterNot { it.key.name == "glowEnabled" },
            StudioAppearanceFields.fields(after).filterNot { it.key.name == "glowEnabled" },
        )
        assertEquals(document.shared, commit(request, inputs).candidate.shared)
    }

    @Test
    fun `explicit rotation enable retains its origin under forced system accent`() {
        val original = customDocument()
        val document = original.copy(shared = original.shared.copy(systemAccentPolicy = SystemAccentPolicy.FORCE_ON))
        val initial = sessionState(document)
        val inputs = sessionInputs(document, initial)

        val request = ready(StudioQuickCommand.Accent(AccentPanelEvent.RotationEnabled(true)), initial, inputs)

        val draft =
            request.session.work.drafts
                .single()
        assertEquals(RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE, draft.tracking.rotationOrigin)
        assertEquals(RawValue.BooleanValue(true), draft.appearance.accent.value("followSystemAccent"))
        assertEquals(RawValue.Text("RANDOM"), draft.appearance.accent.value("accentRotationMode"))
        assertEquals(RawValue.Integer(11), draft.appearance.accent.value("accentRotationIntervalHours"))
        assertEquals(SystemAccentPolicy.FORCE_ON, commit(request, inputs).candidate.shared.systemAccentPolicy)
    }

    @Test
    fun `manual factory and ordered custom profile activation stays available without Pro`() {
        val original = sessionDocument()
        val appearance = original.currents.global.appearance
        val first = ProfileDefinition.User(ProfileId("custom-z"), "Робота", appearance, 3)
        val second = first.copy(id = ProfileId("custom-a"), revision = 7)
        val document = original.copy(catalog = StudioList(original.catalog + listOf(first, second)))
        val initial = sessionState(document)
        val baseInputs = sessionInputs(document, initial)
        val limitations = StudioList(EffectOwner.entries.map { EffectLimitation(it, LimitationReason.PRO_REQUIRED) })
        val inputs =
            baseInputs.copy(context = baseInputs.context.copy(capabilities = CapabilitySnapshot(4, limitations)))

        for (profile in document.catalog) {
            val draftId = DraftId("activate-${profile.id.value}")
            val request = ready(StudioQuickCommand.Profile(profile.id, draftId), initial, inputs)
            val settings = settingsRequest(initial, inputs, listOf(SessionIntent.Browse(profile.id, draftId)))

            assertEquals(settings.copy(origin = AcceptanceOrigin.QUICK_SWITCHER), request)
            val selected =
                request.session.work.drafts
                    .single { it.id == request.session.work.selected }
            assertEquals(profile.appearance, selected.appearance)
            assertEquals(DraftIntent.FULL_PROFILE, selected.tracking.intent)
            assertEquals(RotationAcceptanceOrigin.PROFILE_ACTIVATION, selected.tracking.rotationOrigin)
            val plan = commit(request, inputs)
            assertAcceptedProfile(appearance, profile.appearance, plan.candidate.currents.global.appearance)
            assertEquals(
                profile.id,
                plan.candidate.currents.global.source
                    ?.id,
            )
            assertEquals(document.catalog, plan.candidate.catalog)
            assertEquals(document.shared, plan.candidate.shared)
            assertTrue(plan.effects.isEmpty())
        }
        assertEquals(listOf(first, second), document.catalog.takeLast(2))
    }

    @Test
    fun `an inactive captured project receives appearance intent without changing the active project`() {
        val document = projectDocument()
        val initial = projectState(document)
        val baseInputs = sessionInputs(document, initial)
        val inputs =
            baseInputs.copy(
                context =
                    baseInputs.context.copy(
                        target = baseInputs.context.target.copy(generation = 17, presence = TargetPresence.INACTIVE),
                    ),
            )

        val request = ready(StudioQuickCommand.GlowEnabled(true), initial, inputs)

        assertEquals(initial.identity.target, request.session.target)
        assertEquals(17L, request.targetGeneration)
        assertEquals("quick-operation", request.transactionId)
        val plan = commit(request, inputs)
        assertTrue(plan.effects.isEmpty())
        assertEquals(document.currents.global, plan.candidate.currents.global)
        assertEquals(document.currents.projects[1], plan.candidate.currents.projects[1])
        assertEquals(
            RawValue.BooleanValue(true),
            plan.candidate.currents.projects[0]
                .current.appearance.glow
                .value("glowEnabled"),
        )
    }

    @Test
    fun `a replaced or unavailable captured target never becomes a command for the new focus`() {
        val document = projectDocument()
        val initial = projectState(document)
        val baseInputs = sessionInputs(document, initial)
        val target = baseInputs.context.target.copy(generation = 17)
        val observations =
            listOf(
                target.copy(generation = 18) to AcceptanceBlock.TARGET_REPLACED,
                target.copy(target = StudioTarget.Project(StudioProjectKey("/work/B"))) to
                    AcceptanceBlock.TARGET_REPLACED,
                target.copy(presence = TargetPresence.UNAVAILABLE) to AcceptanceBlock.TARGET_UNAVAILABLE,
            )
        for ((observation, reason) in observations) {
            val inputs = baseInputs.copy(context = baseInputs.context.copy(target = observation))
            val result =
                StudioQuickCommands.prepare(
                    StudioQuickCommand.GlowEnabled(true),
                    initial,
                    inputs,
                    "quick-operation",
                )
            assertEquals(listOf(reason), assertIs<QuickCommandPreparation.Blocked>(result).reasons)
        }
        assertEquals(projectState(document), initial)
    }

    @Test
    fun `pinning changes only the captured project entry and preserves map order`() {
        val original = projectDocument()
        val pins =
            RawValue.OrderedMap(
                StudioList(
                    listOf(
                        RawValue.Entry("/work/B", RawValue.Text("#112233")),
                        RawValue.Entry("/work/A", RawValue.Text("#445566")),
                        RawValue.Entry("/closed/C", RawValue.Text("#778899")),
                    ),
                ),
            )
        val document =
            original.copy(
                shared = original.shared.copy(mappings = pinFields(original.shared.mappings, pins)),
            )
        val initial = projectState(document)
        val baseInputs = sessionInputs(document, initial)
        val inputs =
            baseInputs.copy(context = baseInputs.context.copy(target = baseInputs.context.target.copy(generation = 17)))

        val request = ready(StudioQuickCommand.Pin("#AABBCC"), initial, inputs)

        val settings =
            settingsRequest(
                initial,
                inputs,
                StudioMappingsEditing.edits(
                    document.shared.mappings,
                    MappingsEdit.Color(MappingsTable.PROJECTS, "/work/A", "#AABBCC"),
                ),
            )
        assertEquals(settings.copy(origin = AcceptanceOrigin.QUICK_SWITCHER), request)
        val plan = commit(request, inputs)
        val after =
            assertIs<RawValue.OrderedMap>(
                plan.candidate.shared.mappings
                    .value("projectAccents"),
            )
        assertEquals(listOf("/work/B", "/work/A", "/closed/C"), after.entries.map { it.key })
        assertEquals(
            listOf("#112233", "#AABBCC", "#778899"),
            after.entries.map { assertIs<RawValue.Text>(it.value).value },
        )
        assertEquals(document.currents, plan.candidate.currents)
        assertEquals(document.catalog, plan.candidate.catalog)
        assertEquals(
            document.shared.mappings.records
                .filterNot { it.key.name == "projectAccents" },
            plan.candidate.shared.mappings.records
                .filterNot { it.key.name == "projectAccents" },
        )
        assertEquals(document.shared.copy(mappings = plan.candidate.shared.mappings), plan.candidate.shared)
    }

    @Test
    fun `a shared pin requires the captured project to remain active`() {
        val document = projectDocument()
        val initial = projectState(document)
        val baseInputs = sessionInputs(document, initial)
        val inputs =
            baseInputs.copy(
                context =
                    baseInputs.context.copy(
                        target = baseInputs.context.target.copy(generation = 17, presence = TargetPresence.INACTIVE),
                    ),
            )

        val result = StudioQuickCommands.prepare(StudioQuickCommand.Pin("#AABBCC"), initial, inputs, "quick-operation")

        assertEquals(listOf(AcceptanceBlock.ACTIVATE_TARGET), assertIs<QuickCommandPreparation.Blocked>(result).reasons)
        assertEquals(document, inputs.latest)
    }

    @Test
    fun `unflushed Settings work reaches the shared Review guard before a popup command`() {
        val document = customDocument()
        val initial = sessionState(document)
        val dirty = requireNotNull(editedSession(document).record).copy(id = "open-settings")
        val baseInputs = sessionInputs(document, initial)
        val inputs = baseInputs.copy(context = baseInputs.context.copy(liveSessions = StudioList(listOf(dirty))))

        val result =
            StudioQuickCommands.prepare(StudioQuickCommand.GlowEnabled(true), initial, inputs, "quick-operation")

        assertEquals(
            listOf<AcceptanceReview>(AcceptanceReview.LiveSession("open-settings")),
            assertIs<QuickCommandPreparation.ReviewRequired>(result).items,
        )
        assertEquals(document, inputs.latest)
        assertEquals(dirty, inputs.context.liveSessions.single())
    }

    @Test
    fun `retired live observations override stale durable dirty work at the common planner`() {
        val original = customDocument()
        val dirty = requireNotNull(editedSession(original).record).copy(id = "closed-settings")
        val document = original.copy(sessions = StudioList(listOf(dirty)))
        val initial = sessionState(document)
        val baseInputs = sessionInputs(document, initial)
        val retired = dirty.copy(generation = dirty.generation + 1, availability = SessionAvailability.RETIRED)
        val inputs = baseInputs.copy(context = baseInputs.context.copy(liveSessions = StudioList(listOf(retired))))

        val request = ready(StudioQuickCommand.GlowEnabled(true), initial, inputs)

        assertEquals(AcceptanceOrigin.QUICK_SWITCHER, request.origin)
        assertEquals(document, inputs.latest)
        assertEquals(retired, inputs.context.liveSessions.single())
    }

    @Test
    fun `a retired command record cannot be revived by a popup action`() {
        val document = customDocument()
        val clean = sessionState(document)
        val record = requireNotNull(clean.record)
        val initial =
            clean.copy(phase = SessionPhase.Retired, record = record.copy(availability = SessionAvailability.RETIRED))

        val result =
            StudioQuickCommands.prepare(
                StudioQuickCommand.GlowEnabled(true),
                initial,
                sessionInputs(document, initial),
                "quick-operation",
            )

        assertEquals(listOf(AcceptanceBlock.INVALID_REQUEST), assertIs<QuickCommandPreparation.Blocked>(result).reasons)
        assertEquals(SessionPhase.Retired, initial.phase)
    }

    @Test
    fun `a dirty canonical record cannot accept existing Settings edits through a popup action`() {
        val document = customDocument()
        val initial = editedSession(document)
        val inputs = sessionInputs(document, initial)

        val result =
            StudioQuickCommands.prepare(
                StudioQuickCommand.Accent(AccentPanelEvent.ChromeEnabled(true)),
                initial,
                inputs,
                "quick-operation",
            )

        assertEquals(listOf(AcceptanceBlock.INVALID_REQUEST), assertIs<QuickCommandPreparation.Blocked>(result).reasons)
        assertEquals(editedSession(document), initial)
        assertEquals(document, inputs.latest)
    }

    @Test
    fun `a removed profile is rejected without substituting Current or another profile`() {
        val document = customDocument()
        val initial = sessionState(document)

        val result =
            StudioQuickCommands.prepare(
                StudioQuickCommand.Profile(ProfileId("removed-profile"), DraftId("removed-selection")),
                initial,
                sessionInputs(document, initial),
                "quick-operation",
            )

        assertEquals(listOf(AcceptanceBlock.INVALID_REQUEST), assertIs<QuickCommandPreparation.Blocked>(result).reasons)
        assertEquals(sessionState(document), initial)
    }

    @Test
    fun `repeating an already accepted master choice carries no appearance work`() {
        val document = customDocument()
        val initial = sessionState(document)
        val inputs = sessionInputs(document, initial)

        val request = ready(StudioQuickCommand.GlowEnabled(false), initial, inputs)

        assertEquals(initial.record, request.session)
        assertEquals(
            AcceptancePlan.Unchanged,
            assertIs<AcceptancePlanResult.Ready>(
                StudioAcceptancePlanner.plan(request, document, inputs.context),
            ).plan,
        )
    }

    private fun ready(
        command: StudioQuickCommand,
        state: StudioSessionState,
        inputs: SessionInputs,
    ): AcceptanceRequest =
        assertIs<QuickCommandPreparation.Ready>(
            StudioQuickCommands.prepare(command, state, inputs, "quick-operation"),
        ).request

    private fun commit(
        request: AcceptanceRequest,
        inputs: SessionInputs,
    ): AcceptancePlan.Commit =
        assertIs<AcceptancePlan.Commit>(
            assertIs<AcceptancePlanResult.Ready>(
                StudioAcceptancePlanner.plan(request, inputs.latest, inputs.context),
            ).plan,
        )

    private fun settingsRequest(
        initial: StudioSessionState,
        inputs: SessionInputs,
        intents: List<SessionIntent>,
    ): AcceptanceRequest {
        val edited =
            intents.fold(initial) { state, intent ->
                StudioSessionReducer.reduce(state, SessionEvent.Intent(intent), inputs).state
            }
        return SessionTransitions.request(edited, "quick-operation")
    }

    private fun assertAcceptedProfile(
        before: AppearanceSnapshot,
        requested: AppearanceSnapshot,
        actual: AppearanceSnapshot,
    ) {
        val previous = StudioAppearanceFields.fields(before).associateBy { it.key }
        val expected =
            StudioAppearanceFields.fields(requested).map { field ->
                val origin =
                    if (field.value == previous.getValue(field.key).value) {
                        previous.getValue(field.key).origin
                    } else {
                        FieldOrigin(if (field.value == RawValue.Null) SourcePresence.NULL else SourcePresence.VALUE)
                    }
                field.copy(origin = origin)
            }
        assertEquals(expected, StudioAppearanceFields.fields(actual))
        assertEquals(requested.accent.extensions, actual.accent.extensions)
        assertEquals(requested.font.extensions, actual.font.extensions)
        assertEquals(requested.glow.extensions, actual.glow.extensions)
        assertEquals(requested.syntax.extensions, actual.syntax.extensions)
        assertEquals(requested.vcs.extensions, actual.vcs.extensions)
    }

    private fun customDocument(): StudioDocument {
        val settings =
            AyuIslandsState().apply {
                chromeTintingEnabled = false
                chromeStatusBar = false
                chromeMainToolbar = true
                chromeToolWindowStripe = false
                chromeNavBar = true
                chromePanelBorder = false
                chromeTintIntensity = 63
                glowEnabled = false
                waveformAmplitude = 23
                waveformLoopSeconds = 9.25f
                accentRotationEnabled = false
                accentRotationMode = "RANDOM"
                accentRotationIntervalHours = 11
                followSystemAccent = true
            }
        return assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(settings))).document
    }

    private fun projectDocument(): StudioDocument {
        val document = customDocument()
        val current = document.currents.global
        val projects =
            StudioList(
                listOf(
                    StudioProjectRecord(StudioProjectKey("/work/A"), "Project A", current),
                    StudioProjectRecord(StudioProjectKey("/work/B"), "Project B", current),
                ),
            )
        return document.copy(currents = document.currents.copy(projects = projects))
    }

    private fun projectState(document: StudioDocument): StudioSessionState {
        val initial = sessionState(document)
        val target = StudioTarget.Project(StudioProjectKey("/work/A"))
        return initial.copy(
            identity = initial.identity.copy(target = target, targetGeneration = 17),
            record = requireNotNull(initial.record).copy(target = target),
        )
    }

    private fun pinFields(
        before: StudioFields,
        value: RawValue,
    ): StudioFields =
        before.copy(
            records =
                StudioList(
                    before.records.map { field ->
                        if (field.key.name == "projectAccents") {
                            field.copy(value = value, origin = FieldOrigin(SourcePresence.VALUE))
                        } else {
                            field
                        }
                    },
                ),
        )
}
