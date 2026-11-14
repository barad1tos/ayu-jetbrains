package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.ApplyCandidate
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioApplyCandidate
import dev.ayuislands.studio.session.StudioEffectPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioEffectRoutingTest {
    @Test
    fun `Ignore colors select Syntax and only a new integration enable rearms Accent`() {
        val document = catalogTestDocument().withPlugin("ignorePluginSyntaxColorsEnabled", false)
        val before = document.withPlugin("cgpIntegrationEnabled", false)
        val edits =
            listOf(
                enabled(before.shared.plugins, "ignorePluginSyntaxColorsEnabled"),
                enabled(before.shared.plugins, "cgpIntegrationEnabled"),
            )
        val session = withShared(before, edits)
        val together = plan(before, session)
        assertEquals(listOf(EffectOwner.ACCENT, EffectOwner.SYNTAX), together.owners.toList())
        assertEquals(StudioIntegrationSelection(enablesCodeGlance = true), together.integrations)

        val alreadyEnabled = before.withPlugin("cgpIntegrationEnabled", true)
        val remaining = plan(alreadyEnabled, session)
        assertEquals(listOf(EffectOwner.SYNTAX), remaining.owners.toList())
        assertEquals(StudioIntegrationSelection(), remaining.integrations)
    }

    @Test
    fun `an external integration gate never rearms the main integration`() {
        val document = catalogTestDocument().withPlugin("irIntegrationEnabled", true)
        val before = document.withPlugin("externalThemeIndentRainbowEnabled", false)
        val change = enabled(before.shared.plugins, "externalThemeIndentRainbowEnabled")
        val route = plan(before, withShared(before, listOf(change)))
        assertEquals(listOf(EffectOwner.ACCENT), route.owners.toList())
        assertEquals(StudioIntegrationSelection(), route.integrations)
    }

    @Test
    fun `workspace writes only changed values and skips a field already accepted elsewhere`() {
        val document = catalogTestDocument()
        val fields = document.shared.workspace
        val horizontal = toggled(fields, "hideEditorHScrollbar")
        val vertical = toggled(fields, "hideEditorVScrollbar")
        val latestFields =
            fields.copy(
                records =
                    StudioList(
                        fields.records.map {
                            if (it.key ==
                                vertical.before.key
                            ) {
                                vertical.after
                            } else {
                                it
                            }
                        },
                    ),
            )
        val latest = document.copy(shared = document.shared.copy(workspace = latestFields))
        val route = plan(latest, withShared(document, listOf(horizontal, vertical)))

        assertEquals(listOf(EffectOwner.WORKSPACE), route.owners.toList())
        assertEquals(listOf(FieldAddress(horizontal.before.key)), route.workspace.toList())
    }

    @Test
    fun `inactive project preference can be accepted without running any effects`() {
        val initial = catalogTestDocument()
        val target = StudioTarget.Project(StudioProjectKey("background"))
        val project = StudioProjectRecord(target.key, "Background", initial.currents.global)
        val document = initial.copy(currents = initial.currents.copy(projects = StudioList(listOf(project))))
        val session = editedJournalRecord(document).copy(target = target)
        val active = plan(document, session)
        assertEquals(listOf(EffectOwner.GLOW), active.owners.toList())
        assertTrue(active.glow.fields.isNotEmpty())

        val inactive = plan(document, session, TargetPresence.INACTIVE)
        assertTrue(inactive.owners.isEmpty())
        assertTrue(inactive.glow.fields.isEmpty())
    }

    private fun plan(
        document: StudioDocument,
        session: StudioSessionRecord,
        presence: TargetPresence = TargetPresence.ACTIVE,
    ) = run {
        val target = TargetObservation(session.target, 1, presence)
        val context = ApplyContext(AcceptanceObservation(AuthorityStatus.READY, target), StudioList(emptyList()))
        val candidate =
            assertIs<ApplyCandidate.Ready>(StudioApplyCandidate.plan(ApplyRequest(session, 1), document, context))
        StudioEffectPolicy.route(document, candidate, target)
    }

    private fun withShared(
        document: StudioDocument,
        edits: List<SharedIntent>,
    ): StudioSessionRecord {
        val session = journalRecord(document)
        return session.copy(work = session.work.copy(shared = StudioList(edits)))
    }

    private fun enabled(
        fields: StudioFields,
        name: String,
    ): SharedIntent.Field =
        fields.records.single { it.key.name == name }.let {
            SharedIntent.Field(
                it,
                it.copy(value = RawValue.BooleanValue(true), origin = FieldOrigin(SourcePresence.VALUE)),
            )
        }

    private fun toggled(
        fields: StudioFields,
        name: String,
    ): SharedIntent.Field =
        fields.records.single { it.key.name == name }.let {
            val value = it.value as RawValue.BooleanValue
            SharedIntent.Field(it, it.copy(value = RawValue.BooleanValue(!value.value)))
        }

    private fun StudioDocument.withPlugin(
        name: String,
        value: Boolean,
    ): StudioDocument {
        val fields = shared.plugins
        val changed =
            fields.records.map {
                if (it.key.name ==
                    name
                ) {
                    it.copy(value = RawValue.BooleanValue(value))
                } else {
                    it
                }
            }
        return copy(shared = shared.copy(plugins = fields.copy(records = StudioList(changed))))
    }
}
