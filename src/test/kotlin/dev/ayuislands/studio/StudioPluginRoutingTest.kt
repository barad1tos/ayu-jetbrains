package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioPluginRoutingTest {
    @Test
    fun `ignore colors select Syntax without replaying Accent`() {
        val document = plugins("ignorePluginSyntaxColorsEnabled" to false)
        val plan = plan(document, edit(document, "ignorePluginSyntaxColorsEnabled" to true))

        assertEquals(listOf(EffectOwner.SYNTAX), plan.effects)
        assertEquals(StudioIntegrationSelection(), plan.integrationSelection)
        assertEquals(document.currents, plan.candidate.currents)
    }

    @Test
    fun `combined integrations and ignore colors select their existing owners`() {
        val document = plugins("ignorePluginSyntaxColorsEnabled" to false, "cgpIntegrationEnabled" to false)
        val session = edit(document, "ignorePluginSyntaxColorsEnabled" to true, "cgpIntegrationEnabled" to true)
        val plan = plan(document, session)

        assertTrue(EffectOwner.ACCENT in plan.effects)
        assertTrue(EffectOwner.SYNTAX in plan.effects)
        assertTrue(EffectOwner.PLUGINS !in plan.effects)
        assertEquals(StudioIntegrationSelection(enablesCodeGlance = true), plan.integrationSelection)
    }

    @Test
    fun `only a main integration false to true transition requests rearming`() {
        for (name in listOf("cgpIntegrationEnabled", "irIntegrationEnabled")) {
            for (wasEnabled in listOf(false, true)) {
                val document =
                    plugins(
                        "cgpIntegrationEnabled" to false,
                        "irIntegrationEnabled" to false,
                        name to wasEnabled,
                    )
                val plan = plan(document, edit(document, name to !wasEnabled))

                assertEquals(
                    StudioIntegrationSelection(
                        enablesCodeGlance = name == "cgpIntegrationEnabled" && !wasEnabled,
                        enablesIndentRainbow = name == "irIntegrationEnabled" && !wasEnabled,
                    ),
                    plan.integrationSelection,
                )
                assertTrue(EffectOwner.ACCENT in plan.effects)
                assertTrue(EffectOwner.PLUGINS !in plan.effects)
            }
        }
    }

    @Test
    fun `external gates never rearm suspended main integrations`() {
        for (name in listOf(
            "externalThemeEnhancementsEnabled",
            "externalThemeCodeGlanceProEnabled",
            "externalThemeIndentRainbowEnabled",
        )) {
            val document = plugins("cgpIntegrationEnabled" to true, "irIntegrationEnabled" to true, name to false)
            val plan = plan(document, edit(document, name to true))

            assertEquals(StudioIntegrationSelection(), plan.integrationSelection)
            assertTrue(EffectOwner.ACCENT in plan.effects)
            assertTrue(EffectOwner.PLUGINS !in plan.effects)
        }
    }

    @Test
    fun `an already accepted enable is not repeated from a stale session`() {
        val before = plugins("cgpIntegrationEnabled" to false, "ignorePluginSyntaxColorsEnabled" to false)
        val session = edit(before, "cgpIntegrationEnabled" to true, "ignorePluginSyntaxColorsEnabled" to true)
        val latest = plugins("cgpIntegrationEnabled" to true, "ignorePluginSyntaxColorsEnabled" to false)
        val plan = plan(latest, session)

        assertEquals(StudioIntegrationSelection(), plan.integrationSelection)
        assertEquals(listOf(EffectOwner.SYNTAX), plan.effects)
    }

    private fun plugins(vararg values: Pair<String, Boolean>): StudioDocument {
        val document = sessionDocument()
        val replacements = values.toMap()
        val fields = document.shared.plugins
        return document.copy(
            shared =
                document.shared.copy(
                    plugins =
                        fields.copy(
                            records =
                                StudioList(
                                    fields.records.map { field ->
                                        replacements[field.key.name]?.let {
                                            field.copy(
                                                value = RawValue.BooleanValue(it),
                                            )
                                        }
                                            ?: field
                                    },
                                ),
                        ),
                ),
        )
    }

    private fun edit(
        document: StudioDocument,
        vararg values: Pair<String, Boolean>,
    ): StudioSessionRecord {
        val session = sessionRecord(document)
        val edits =
            values.map { (name, value) ->
                val before =
                    document.shared.plugins.records
                        .single { it.key.name == name }
                SharedIntent.Field(
                    before,
                    before.copy(value = RawValue.BooleanValue(value), origin = FieldOrigin(SourcePresence.VALUE)),
                )
            }
        return session.copy(work = session.work.copy(shared = StudioList(edits)))
    }

    private fun plan(
        document: StudioDocument,
        session: StudioSessionRecord,
    ): AcceptancePlan.Commit {
        val request = AcceptanceRequest(session, "plugins", 1, AcceptanceOrigin.SETTINGS)
        val context = sessionInputs(document, sessionState(document)).context
        return assertIs<AcceptancePlan.Commit>(
            assertIs<AcceptancePlanResult.Ready>(StudioAcceptancePlanner.plan(request, document, context)).plan,
        )
    }
}
