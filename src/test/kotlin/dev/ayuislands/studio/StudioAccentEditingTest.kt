package dev.ayuislands.studio

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.settings.AccentSharedToggle
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioDraftPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioAccentEditingTest {
    @Test
    fun `raw rotation choices and masked profile Follow remain distinct from effective policy`() {
        val draft = draft()
        val shared = shared().copy(systemAccentPolicy = SystemAccentPolicy.FORCE_ON)
        val before = draft.copy()
        val view = StudioAccentEditing.settings(draft.appearance, shared)

        assertEquals(RotationIntent(true, "FUTURE_MODE", -7), view.rotation)
        assertEquals(false, view.followSystemAccent)
        assertEquals(SystemAccentPolicy.FORCE_ON, view.systemAccentPolicy)
        assertTrue(view.effectiveFollowSystem)
        assertEquals(before, draft)
        assertEquals(
            false,
            StudioAccentEditing
                .settings(
                    draft.appearance,
                    shared.copy(systemAccentPolicy = SystemAccentPolicy.BY_PROFILE),
                ).effectiveFollowSystem,
        )
    }

    @Test
    fun `Follow and explicit rotation enable edit their own field without clearing the other choice`() {
        val initial = draft()
        val followed = edit(initial, AccentPanelEvent.FollowSystem(true))
        assertAppearance(initial, followed, mapOf("followSystemAccent" to RawValue.BooleanValue(true)))
        val disabled = edit(followed, AccentPanelEvent.RotationEnabled(false))
        assertAppearance(followed, disabled, mapOf("accentRotationEnabled" to RawValue.BooleanValue(false)))
        val enabled = edits(disabled, AccentPanelEvent.RotationEnabled(true)).single()
        val intent = assertIs<SessionIntent.EditField>(enabled)
        assertEquals(RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE, intent.rotationOrigin)
        val restored = apply(disabled, listOf(intent))
        assertAppearance(disabled, restored, mapOf("accentRotationEnabled" to RawValue.BooleanValue(true)))
    }

    @Test
    fun `explicit mode and interval edits preserve unrelated raw values and carry local rotation provenance`() {
        val initial = draft()
        for ((event, name, value) in listOf(
            Triple(
                AccentPanelEvent.RotationMode(AccentRotationMode.RANDOM),
                "accentRotationMode",
                RawValue.Text("RANDOM"),
            ),
            Triple(AccentPanelEvent.RotationInterval(3), "accentRotationIntervalHours", RawValue.Integer(3)),
        )) {
            val intents = edits(initial, event)
            assertEquals(1, intents.size)
            assertEquals(
                RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT,
                assertIs<SessionIntent.EditField>(intents.single()).rotationOrigin,
            )
            assertAppearance(initial, apply(initial, intents), mapOf(name to value))
        }
    }

    @Test
    fun `shared Follow policy stages only the working policy conflict baseline`() {
        val initial = draft()
        val before = initial.copy()
        val shared = shared().copy(systemAccentPolicy = SystemAccentPolicy.FORCE_ON)
        val intents =
            StudioAccentEditing.edits(
                initial,
                shared,
                AccentPanelEvent.SystemPolicy(SystemAccentPolicy.FORCE_OFF),
            )

        assertEquals(
            listOf(
                SessionIntent.Shared(
                    SharedIntent.SystemPolicy(SystemAccentPolicy.FORCE_ON, SystemAccentPolicy.FORCE_OFF),
                ),
            ),
            intents,
        )
        assertEquals(before, initial)
        assertEquals(SystemAccentPolicy.FORCE_ON, shared.systemAccentPolicy)
    }

    @Test
    fun `color reset stays on its variant and element mass toggle cannot touch Chrome or dormant choices`() {
        val initial = draft()
        val colored = edit(initial, AccentPanelEvent.Color(AyuVariant.DARK, "#aAbBcC"))
        assertAppearance(initial, colored, mapOf("darkAccent" to RawValue.Text("#aAbBcC")))
        val followed = edit(colored, AccentPanelEvent.FollowSystem(true))
        val reset = edit(followed, AccentPanelEvent.ResetColor(AyuVariant.DARK))
        assertAppearance(
            followed,
            reset,
            mapOf("darkAccent" to RawValue.Text(""), "followSystemAccent" to RawValue.BooleanValue(false)),
        )
        val disabled = edit(initial, AccentPanelEvent.AllElements(false))
        assertAppearance(
            initial,
            disabled,
            listOf(
                "inlayHints",
                "caretRow",
                "progressBar",
                "scrollbar",
                "links",
                "bracketMatch",
                "searchResults",
                "matchingTag",
            ).associateWith { RawValue.BooleanValue(false) },
        )
    }

    @Test
    fun `shared widget and Chrome master commands retain all granular preferences`() {
        val initial = draft()
        val shared = shared()
        val before = shared.system.records.single { it.key.name == "quickSwitcherWidgetEnabled" }
        val requested = before.value != RawValue.BooleanValue(true)
        val intents =
            StudioAccentEditing.edits(
                initial,
                shared,
                AccentPanelEvent.SharedToggle(AccentSharedToggle.QUICK_SWITCHER_WIDGET, requested),
            )
        val change = assertIs<SharedIntent.Field>(assertIs<SessionIntent.Shared>(intents.single()).intent)
        assertEquals(before, change.before)
        assertEquals(before.key, change.after.key)
        assertEquals(RawValue.BooleanValue(requested), change.after.value)
        val disabled = edit(initial, AccentPanelEvent.ChromeEnabled(false))
        assertAppearance(initial, disabled, mapOf("chromeTintingEnabled" to RawValue.BooleanValue(false)))
    }

    private fun shared(): SharedPreferences = sessionDocument().shared

    private fun draft(): DraftRecord {
        val current = sessionRecord(sessionDocument()).work.drafts.single()
        val raw =
            mapOf(
                "mirageAccent" to RawValue.Text("future-color"),
                "darkAccent" to RawValue.Text(" #112233 "),
                "followSystemAccent" to RawValue.BooleanValue(false),
                "accentRotationEnabled" to RawValue.BooleanValue(true),
                "accentRotationMode" to RawValue.Text("FUTURE_MODE"),
                "accentRotationIntervalHours" to RawValue.Integer(-7),
                "chromeTintingEnabled" to RawValue.BooleanValue(true),
                "chromeTintIntensity" to RawValue.Integer(137),
                "glowTabMode" to RawValue.Text("FUTURE_TAB"),
                "tabUnderlineHeight" to RawValue.Integer(91),
            )
        return current.copy(
            appearance =
                current.appearance.copy(
                    accent =
                        current.appearance.accent.copy(
                            records =
                                StudioList(
                                    current.appearance.accent.records
                                        .map { it.copy(value = raw[it.key.name] ?: it.value) },
                                ),
                        ),
                ),
        )
    }

    private fun edits(
        draft: DraftRecord,
        event: AccentPanelEvent,
    ): List<SessionIntent> = StudioAccentEditing.edits(draft, shared(), event)

    private fun edit(
        draft: DraftRecord,
        event: AccentPanelEvent,
    ): DraftRecord = apply(draft, edits(draft, event))

    private fun apply(
        draft: DraftRecord,
        intents: List<SessionIntent>,
    ): DraftRecord =
        intents.fold(draft) { current, event ->
            val intent = assertIs<SessionIntent.EditField>(event)
            assertEquals(draft.id, intent.draft)
            StudioDraftPolicy.edit(current, intent.address, intent.value, intent.rotationOrigin)
        }

    private fun assertAppearance(
        before: DraftRecord,
        after: DraftRecord,
        changes: Map<String, RawValue>,
    ) {
        val expected =
            before.appearance.accent.copy(
                records =
                    StudioList(
                        before.appearance.accent.records.map { field ->
                            val value = changes[field.key.name]
                            val original =
                                before.tracking.initial.accent.records
                                    .single { it.key == field.key }
                            when (value) {
                                null, field.value -> field
                                original.value -> original
                                else -> field.copy(value = value, origin = FieldOrigin(SourcePresence.VALUE))
                            }
                        },
                    ),
            )
        assertEquals(before.appearance.copy(accent = expected), after.appearance)
    }
}
