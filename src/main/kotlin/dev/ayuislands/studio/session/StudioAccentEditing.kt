package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.settings.AccentChromeSettings
import dev.ayuislands.settings.AccentElementSettings
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.settings.AccentPanelSettings
import dev.ayuislands.settings.AccentSharedToggle
import dev.ayuislands.settings.AccentSystemSettings
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioFields

/** Projects raw choices and translates one explicit gesture without reapplying decoded sibling values. */
internal object StudioAccentEditing {
    fun settings(
        appearance: AppearanceSnapshot,
        shared: SharedPreferences,
    ): AccentPanelSettings {
        val accent = appearance.accent
        return AccentPanelSettings(
            AyuVariant.entries.associateWith { accent.text(accentField(it)) },
            accent.boolean("followSystemAccent"),
            RotationIntent(
                accent.boolean("accentRotationEnabled"),
                accent.text("accentRotationMode"),
                accent.integer("accentRotationIntervalHours"),
            ),
            AccentChromeSettings(
                accent.boolean("chromeTintingEnabled"),
                AccentElementId.entries.filter { it.group == AccentGroup.CHROME }.associateWith {
                    accent.boolean(
                        it.settingName,
                    )
                },
                accent.integer("chromeTintIntensity"),
            ),
            AccentElementSettings(
                AccentElementId.entries.filter { it.group != AccentGroup.CHROME }.associateWith {
                    accent.boolean(
                        it.settingName,
                    )
                },
                (accent.value("forceOverrides") as RawValue.OrderedSet).values.toList(),
                accent.text("glowTabMode"),
                accent.integer("tabUnderlineHeight"),
                accent.boolean("tabUnderlineGlowSync"),
                accent.boolean("bracketScopeEnabled"),
            ),
            AccentSystemSettings(
                AccentSharedToggle.entries.associateWith { shared.system.boolean(it.field) },
                shared.system.text("lastDarkAppearanceTheme"),
            ),
            shared.systemAccentPolicy,
            StudioAccentPolicy.followsSystem(appearance, shared.systemAccentPolicy),
        )
    }

    fun edits(
        draft: DraftRecord,
        shared: SharedPreferences,
        event: AccentPanelEvent,
    ): List<SessionIntent> =
        when (event) {
            is AccentPanelEvent.SystemPolicy -> {
                if (event.value == shared.systemAccentPolicy) {
                    emptyList()
                } else {
                    listOf(
                        SessionIntent.Shared(SharedIntent.SystemPolicy(shared.systemAccentPolicy, event.value)),
                    )
                }
            }

            is AccentPanelEvent.SharedToggle -> {
                sharedEdit(shared.system, event.target.field, RawValue.BooleanValue(event.value))
            }

            is AccentPanelEvent.NightTheme -> {
                sharedEdit(shared.system, "lastDarkAppearanceTheme", RawValue.Text(event.value))
            }

            else -> {
                appearanceEdits(draft, event)
            }
        }

    private fun appearanceEdits(
        draft: DraftRecord,
        event: AccentPanelEvent,
    ): List<SessionIntent> {
        val origin =
            when (event) {
                is AccentPanelEvent.RotationEnabled -> {
                    if (event.value) {
                        RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE
                    } else {
                        RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT
                    }
                }

                is AccentPanelEvent.RotationMode, is AccentPanelEvent.RotationInterval -> {
                    RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT
                }

                else -> {
                    RotationAcceptanceOrigin.LOCAL_APPEARANCE_EDIT
                }
            }
        return changes(event).mapNotNull { (name, requested) ->
            val before =
                draft.appearance.accent.records
                    .single { it.key.name == name }
            if (before.value == requested) {
                null
            } else {
                SessionIntent.EditField(
                    draft.id,
                    FieldAddress(before.key),
                    FieldValue.Present(requested),
                    origin,
                )
            }
        }
    }

    private fun changes(event: AccentPanelEvent): Map<String, RawValue> =
        when (event) {
            is AccentPanelEvent.Color -> {
                mapOf(accentField(event.variant) to RawValue.Text(event.hex))
            }

            is AccentPanelEvent.ResetColor -> {
                mapOf(
                    accentField(event.variant) to RawValue.Text(""),
                    "followSystemAccent" to RawValue.BooleanValue(false),
                )
            }

            is AccentPanelEvent.FollowSystem -> {
                boolean("followSystemAccent", event.value)
            }

            is AccentPanelEvent.RotationEnabled -> {
                boolean("accentRotationEnabled", event.value)
            }

            is AccentPanelEvent.RotationMode -> {
                mapOf("accentRotationMode" to RawValue.Text(event.value.name))
            }

            is AccentPanelEvent.RotationInterval -> {
                mapOf("accentRotationIntervalHours" to RawValue.Integer(event.value))
            }

            is AccentPanelEvent.ChromeEnabled -> {
                boolean("chromeTintingEnabled", event.value)
            }

            is AccentPanelEvent.ChromeIntensity -> {
                mapOf("chromeTintIntensity" to RawValue.Integer(event.value))
            }

            is AccentPanelEvent.ChromeSurface -> {
                require(event.id.group == AccentGroup.CHROME)
                boolean(event.id.settingName, event.value)
            }

            else -> {
                elementChanges(event)
            }
        }

    private fun elementChanges(event: AccentPanelEvent): Map<String, RawValue> =
        when (event) {
            is AccentPanelEvent.Element -> {
                require(event.id.group != AccentGroup.CHROME)
                boolean(event.id.settingName, event.value)
            }

            is AccentPanelEvent.AllElements -> {
                AccentElementId.entries
                    .filter { it.group != AccentGroup.CHROME }
                    .associate { it.settingName to RawValue.BooleanValue(event.value) }
            }

            is AccentPanelEvent.TabMode -> {
                mapOf("glowTabMode" to RawValue.Text(event.value.name))
            }

            is AccentPanelEvent.UnderlineHeight -> {
                mapOf("tabUnderlineHeight" to RawValue.Integer(event.value))
            }

            is AccentPanelEvent.UnderlineSync -> {
                boolean("tabUnderlineGlowSync", event.value)
            }

            is AccentPanelEvent.BracketScope -> {
                boolean("bracketScopeEnabled", event.value)
            }

            else -> {
                emptyMap()
            }
        }

    private fun sharedEdit(
        fields: StudioFields,
        name: String,
        value: RawValue,
    ): List<SessionIntent> {
        val before = fields.records.single { it.key.name == name }
        val after = StudioFieldEdits.edit(before, FieldPart.Value, FieldValue.Present(value))
        return if (before == after) emptyList() else listOf(SessionIntent.Shared(SharedIntent.Field(before, after)))
    }

    private fun boolean(
        name: String,
        value: Boolean,
    ): Map<String, RawValue> = mapOf(name to RawValue.BooleanValue(value))

    private fun accentField(variant: AyuVariant): String =
        when (variant) {
            AyuVariant.MIRAGE -> "mirageAccent"
            AyuVariant.DARK -> "darkAccent"
            AyuVariant.LIGHT -> "lightAccent"
        }

    private fun StudioFields.value(name: String): RawValue = records.single { it.key.name == name }.value

    private fun StudioFields.text(name: String): String? = (value(name) as? RawValue.Text)?.value

    private fun StudioFields.boolean(name: String): Boolean = (value(name) as RawValue.BooleanValue).value

    private fun StudioFields.integer(name: String): Int = (value(name) as RawValue.Integer).value
}
