package dev.ayuislands.studio

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.glow.GlowIslandIds
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.persistence.StudioLegacyView
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.vcs.VcsColorCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioLegacyProjectionTest {
    @Test
    fun `all legacy fields retain their exact values across existing migration fixtures`() {
        val fixtures =
            listOf("fresh-absent", "legacy-omitted", "mixed-user-values", "unknown-and-ordered", "existing-ownership")
        for (name in fixtures) {
            val input = StudioFixtures.load(name)
            val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(input)).document
            val projection = assertNotNull(StudioLegacyProjection.project(document, StudioTarget.Global))
            val views = stores(projection)
            assertEquals(StudioFieldCatalog.fields.size, input.fields.records.size)
            for ((key, value) in input.fields.records) {
                val view = views.getValue(key.source)
                assertRaw(value, getter(view, key.name), "$name $key")
            }
            for (view in views.values) {
                assertFalse(view.javaClass.methods.any { it.name.startsWith("set") && it.parameterCount > 0 })
            }
        }
    }

    @Test
    fun `project current is explicit and never falls back to global or profile definition`() {
        val global = migrate(StudioFixtures.fields(AyuIslandsState().apply { glowEnabled = false }))
        val project = migrate(StudioFixtures.fields(AyuIslandsState().apply { glowEnabled = true }))
        val target = StudioTarget.Project(StudioProjectKey("project-a"))
        val document =
            global.copy(
                currents =
                    global.currents.copy(
                        projects =
                            StudioList(
                                listOf(StudioProjectRecord(target.key, "Project", project.currents.global)),
                            ),
                    ),
            )

        assertFalse(assertNotNull(StudioLegacyProjection.project(document, StudioTarget.Global)).settings.glowEnabled)
        assertTrue(assertNotNull(StudioLegacyProjection.project(document, target)).settings.glowEnabled)
        assertNull(StudioLegacyProjection.project(document, StudioTarget.Project(StudioProjectKey("missing"))))
    }

    @Test
    fun `retained read view stays on its accepted generation while a draft and newer current differ`() {
        val document = sessionDocument()
        val before = assertNotNull(StudioLegacyProjection.project(document, StudioTarget.Global))
        val draft = assertNotNull(editedSession(document).record)
        val drafting = document.copy(sessions = StudioList(listOf(draft)))
        val next =
            drafting.copy(
                header = drafting.header.copy(revision = drafting.header.revision + 1),
                currents =
                    drafting.currents.copy(
                        global =
                            drafting.currents.global.copy(
                                appearance =
                                    draft.work.drafts
                                        .single()
                                        .appearance,
                            ),
                    ),
            )

        assertFalse(before.settings.glowEnabled)
        assertFalse(assertNotNull(StudioLegacyProjection.project(drafting, StudioTarget.Global)).settings.glowEnabled)
        assertTrue(assertNotNull(StudioLegacyProjection.project(next, StudioTarget.Global)).settings.glowEnabled)
        assertFalse(before.settings.glowEnabled)
    }

    @Test
    fun `ordered raw collections are detached immutable views and preserve unavailable values`() {
        val base = StudioFixtures.load("unknown-and-ordered")
        val values =
            mapOf(
                LegacyFieldKey(LegacyStore.SETTINGS, "fontPresetCustomizations") to
                    RawValue.OrderedMap(
                        StudioList(
                            listOf(
                                RawValue.Entry("z-future", RawValue.Text("bad;raw;")),
                                RawValue.Entry("a-first", RawValue.Text("  ")),
                            ),
                        ),
                    ),
                LegacyFieldKey(LegacyStore.SETTINGS, "installedFonts") to
                    RawValue.OrderedSet(StudioList(listOf("Z Font", "A Font"))),
                LegacyFieldKey(LegacyStore.MAPPINGS, "projectAccents") to
                    RawValue.OrderedMap(
                        StudioList(
                            listOf(
                                RawValue.Entry("/z", RawValue.Text("not-a-color")),
                                RawValue.Entry("/a", RawValue.Text("#AABBCC")),
                            ),
                        ),
                    ),
                LegacyFieldKey(LegacyStore.SETTINGS, "fontPresetName") to RawValue.Null,
                LegacyFieldKey(LegacyStore.SETTINGS, "waveformLoopSeconds") to RawValue.FloatBits(0x80000000.toInt()),
            )
        val fields =
            base.fields.copy(
                records =
                    StudioList(
                        base.fields.records.map {
                            it.copy(
                                value =
                                    values[it.key] ?: it.value,
                            )
                        },
                    ),
            )
        val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(base.copy(fields = fields))).document
        val projection = assertNotNull(StudioLegacyProjection.project(document, StudioTarget.Global))

        assertEquals(
            listOf("z-future", "a-first"),
            projection.settings.fontPresetCustomizations.keys
                .toList(),
        )
        assertEquals(
            listOf("bad;raw;", "  "),
            projection.settings.fontPresetCustomizations.values
                .toList(),
        )
        assertEquals(listOf("Z Font", "A Font"), projection.settings.installedFonts.toList())
        assertEquals(
            listOf("/z", "/a"),
            projection.mappings.projectAccents.keys
                .toList(),
        )
        assertEquals("not-a-color", projection.mappings.projectAccents["/z"])
        assertNull(projection.settings.fontPresetName)
        assertEquals(0x80000000.toInt(), projection.settings.waveformLoopSeconds.toRawBits())
        assertFails {
            (projection.settings.fontPresetCustomizations as MutableMap<String, String>)["z-future"] =
                "changed"
        }
        assertFails { (projection.settings.installedFonts as MutableSet<String>).clear() }
        assertFails { (projection.mappings.projectAccents as MutableMap<String, String>).clear() }
        assertEquals("bad;raw;", projection.settings.fontPresetCustomizations["z-future"])
        assertEquals(listOf("Z Font", "A Font"), projection.settings.installedFonts.toList())
        assertEquals(
            listOf("/z", "/a"),
            projection.mappings.projectAccents.keys
                .toList(),
        )
        assertEquals(fields.extensions, document.extensions)
    }

    @Test
    fun `global system policy gates effective follow without rewriting raw profile choice`() {
        for (raw in listOf(false, true)) {
            val document = migrate(StudioFixtures.fields(AyuIslandsState().apply { followSystemAccent = raw }))
            for (policy in SystemAccentPolicy.entries) {
                val selected = document.copy(shared = document.shared.copy(systemAccentPolicy = policy))
                val settings = assertNotNull(StudioLegacyProjection.project(selected, StudioTarget.Global)).settings
                val expected =
                    when (policy) {
                        SystemAccentPolicy.BY_PROFILE -> raw
                        SystemAccentPolicy.FORCE_ON -> true
                        SystemAccentPolicy.FORCE_OFF -> false
                    }
                assertEquals(raw, settings.followSystemAccent)
                assertEquals(expected, settings.effectiveFollowSystemAccent())
                assertEquals(raw, settings.followSystemAccent)
            }
        }
    }

    @Test
    fun `effective helpers keep legacy clamps surface gates dormant values and island aliases`() {
        val legacy =
            AyuIslandsState().apply {
                chromeTintIntensity = 999
                chromeTintingEnabled = false
                chromeStatusBar = true
                chromePanelBorder = true
                links = false
                softIntensity = -13
                sharpNeonWidth = 71
                glowProject = false
                glowGit = false
                vcsColorEnabled = true
                vcsDiffPreset = "CUSTOM"
                vcsMergePreset = "NEON"
                vcsBlamePreset = "FUTURE"
                vcsDiffIntensity = 181
                vcsProjectViewIntensity = -7
                lastAppliedAccentHex = "#5CCFE6"
                lastApplyOk = false
                externalThemeEnhancementsEnabled = false
                externalThemeGlowEnabled = true
            }
        for (enabled in listOf(false, true)) {
            legacy.chromeTintingEnabled = enabled
            legacy.externalThemeEnhancementsEnabled = enabled
            legacy.lastApplyOk = enabled
            legacy.vcsColorEnabled = enabled
            val settings =
                assertNotNull(
                    StudioLegacyProjection.project(migrate(StudioFixtures.fields(legacy)), StudioTarget.Global),
                ).settings
            assertEquals(legacy.effectiveChromeTintIntensity(), settings.effectiveChromeTintIntensity())
            assertEquals(legacy.effectiveLastAppliedAccentHex(), settings.effectiveLastAppliedAccentHex())
            assertEquals(legacy.trustedCachedAccent(), settings.trustedCachedAccent())
            assertEquals(legacy.hasChromeTintingSurfaceEnabled(), settings.hasChromeTintingSurfaceEnabled())
            for (id in AccentElementId.entries) {
                assertEquals(
                    legacy.isToggleEnabled(id),
                    settings.isToggleEnabled(id),
                    id.name,
                )
            }
            for (style in GlowStyle.entries) {
                assertEquals(legacy.getIntensityForStyle(style), settings.getIntensityForStyle(style))
                assertEquals(legacy.getWidthForStyle(style), settings.getWidthForStyle(style))
            }
            for (island in GlowIslandIds.all + listOf("Version Control", "unknown")) {
                assertEquals(legacy.isIslandEnabled(island), settings.isIslandEnabled(island), island)
            }
            assertEquals(legacy.effectiveVcsPerCategoryIntensities(), settings.effectiveVcsPerCategoryIntensities())
            for (category in VcsColorCategory.entries) {
                assertEquals(legacy.effectiveVcsPresetFor(category), settings.effectiveVcsPresetFor(category))
                assertEquals(legacy.effectiveVcsIntensityFor(category), settings.effectiveVcsIntensityFor(category))
            }
            assertEquals(legacy.isExternalQuickSwitcherAllowed(), settings.isExternalQuickSwitcherAllowed())
            assertEquals(legacy.isExternalGlowAllowed(), settings.isExternalGlowAllowed())
            assertEquals(legacy.isExternalCodeGlanceProAllowed(), settings.isExternalCodeGlanceProAllowed())
            assertEquals(legacy.isExternalIndentRainbowAllowed(), settings.isExternalIndentRainbowAllowed())
            assertEquals(legacy.isExternalChromeTintAllowed(), settings.isExternalChromeTintAllowed())
        }
    }

    @Test
    fun `syntax decoding retains invalid raw entries while exposing only valid effective cells`() {
        val legacy =
            SyntaxIntensityBaseState().apply {
                selectedPreset = null
                subordinatePreset = "FUTURE"
                customOverrides["Kotlin|KEYWORD"] = "71"
                customOverrides["Kotlin|STRING"] = "invalid"
                customOverrides["bad-key"] = "50"
                dimComments = true
            }
        val syntax =
            assertNotNull(
                StudioLegacyProjection.project(migrate(StudioFixtures.fields(syntax = legacy)), StudioTarget.Global),
            ).syntax
        val effective = syntax.toPresetConfig()

        assertNull(syntax.selectedPreset)
        assertEquals("FUTURE", syntax.subordinatePreset)
        assertEquals(legacy.customOverrides, syntax.customOverrides)
        assertEquals("AMBIENT", effective.selectedPreset)
        assertEquals("FUTURE", effective.subordinatePreset)
        assertEquals(mapOf("Kotlin" to mapOf("KEYWORD" to 71)), effective.customOverrides)
        assertTrue(effective.readabilityOptions.dimComments)
        assertFails { (syntax.customOverrides as MutableMap<String, String>).clear() }
        assertEquals(3, syntax.customOverrides.size)
    }

    @Test
    fun `mapping policy retains raw float bits while matching legacy effective normalization`() {
        val legacy =
            AccentMappingsState().apply {
                dominanceThreshold = 0.71f
                dominanceMarginRatio = -0.0f
                dominanceFloor = 0.23f
                tiebreakMinShare = Float.NaN
            }
        val mappings =
            assertNotNull(
                StudioLegacyProjection.project(migrate(StudioFixtures.fields(mappings = legacy)), StudioTarget.Global),
            ).mappings

        assertEquals(legacy.dominanceMarginRatio.toRawBits(), mappings.dominanceMarginRatio.toRawBits())
        assertEquals(legacy.tiebreakMinShare.toRawBits(), mappings.tiebreakMinShare.toRawBits())
        assertEquals(legacy.languageResolutionPolicy(), mappings.languageResolutionPolicy())
    }

    private fun migrate(fields: StudioFields): StudioDocument =
        assertIs<MigrationResult.Ready>(
            StudioLegacyMigration.plan(StudioFixtures.capture().copy(fields = fields)),
        ).document

    private fun stores(view: StudioLegacyView): Map<LegacyStore, Any> =
        mapOf(
            LegacyStore.SETTINGS to view.settings,
            LegacyStore.SYNTAX to view.syntax,
            LegacyStore.MAPPINGS to view.mappings,
        )

    // Public getters of our own classes only; validates the exhaustive legacy read boundary.
    private fun getter(
        view: Any,
        name: String,
    ): Any? {
        val method = if (name.startsWith("is")) name else "get" + name.replaceFirstChar { it.uppercaseChar() }
        return view.javaClass.getMethod(method).invoke(view)
    }

    private fun assertRaw(
        expected: RawValue,
        actual: Any?,
        message: String,
    ) {
        when (expected) {
            RawValue.Null -> assertNull(actual, message)
            is RawValue.Text -> assertEquals(expected.value, actual, message)
            is RawValue.BooleanValue -> assertEquals(expected.value, actual, message)
            is RawValue.Integer -> assertEquals(expected.value, actual, message)
            is RawValue.LongValue -> assertEquals(expected.value, actual, message)
            is RawValue.FloatBits -> assertEquals(expected.bits, assertIs<Float>(actual).toRawBits(), message)
            is RawValue.OrderedMap -> {
                val map = assertIs<Map<*, *>>(actual)
                assertEquals(expected.entries.map { it.key }, map.keys.toList(), message)
                assertEquals(
                    expected.entries.map { assertIs<RawValue.Text>(it.value).value },
                    map.values.toList(),
                    message,
                )
            }
            is RawValue.OrderedSet -> assertEquals(expected.values, assertIs<Set<*>>(actual).toList(), message)
            is RawValue.Bytes, is RawValue.OrderedList -> error("Unexpected legacy field value: $expected")
        }
    }
}
