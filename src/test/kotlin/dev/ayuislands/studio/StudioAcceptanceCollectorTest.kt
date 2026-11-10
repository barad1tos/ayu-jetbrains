package dev.ayuislands.studio

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontOwnership
import dev.ayuislands.font.FontOwnershipCodec
import dev.ayuislands.font.FontOwnershipPolicy
import dev.ayuislands.font.FontOwnershipRecord
import dev.ayuislands.font.FontOwnershipStatus
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldConflict
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.runtime.FocusSnapshot
import dev.ayuislands.studio.session.FontSchemeView
import dev.ayuislands.studio.session.StudioAcceptanceCollector
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioFontHost
import dev.ayuislands.studio.session.StudioFontPolicy
import dev.ayuislands.studio.session.StudioNativeFocus
import dev.ayuislands.studio.session.StudioNativeHost
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioSessionDispatchers
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment
import java.util.Properties
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioAcceptanceCollectorTest {
    @Test
    fun `view observes real authority and target without doing font IO or inventing availability`() {
        val fixture = Fixture()
        fixture.authority = AuthorityStatus.RECOVERY
        fixture.target = fixture.target.copy(generation = 7, presence = TargetPresence.INACTIVE)

        val context = fixture.collector.view(sessionState(fixture.document), fixture.document)

        assertEquals(AuthorityStatus.RECOVERY, context.authority)
        assertEquals(fixture.target, context.target)
        assertEquals(FontAvailability.UNCONFIRMED, context.font.availability)
        assertEquals(0, fixture.fonts.resolutions.size)
        assertEquals(0, fixture.fonts.censuses)
    }

    @Test
    fun `admission merges untouched stale font fields from latest accepted appearance`() =
        runBlocking {
            val fixture = Fixture()
            val old = fixture.document
            val latest = document(settings(17f))

            val context = fixture.collector.observe(request(old), latest)

            assertEquals(latest.currents.global.appearance.font, context.font.requested)
            assertEquals(FontAvailability.AVAILABLE, context.font.availability)
            assertEquals(
                17f,
                fixture.fonts.resolutions
                    .single()
                    .fontSize,
            )
        }

    @Test
    fun `admission honors exact reviewed font conflict choices instead of the view snapshot`() =
        runBlocking {
            val fixture = Fixture()
            val baseline = fixture.document
            val current = document(settings(17f))
            val original = sessionRecord(baseline)
            val mine = settings(19f).encode()
            val address =
                FieldAddress(
                    LegacyFieldKey(LegacyStore.SETTINGS, "fontPresetCustomizations"),
                    FieldPart.MapEntry("CUSTOM"),
                )
            val draft =
                StudioDraftPolicy.edit(
                    original.work.drafts.single(),
                    address,
                    FieldValue.Present(RawValue.Text(mine)),
                )
            val conflict =
                FieldConflict(
                    address,
                    FieldValue.Present(RawValue.Text(settings(13f).encode())),
                    FieldValue.Present(RawValue.Text(settings(17f).encode())),
                    FieldValue.Present(RawValue.Text(mine)),
                )
            val acceptance =
                request(baseline).copy(
                    session = original.copy(work = original.work.copy(drafts = StudioList(listOf(draft)))),
                    choices =
                        AcceptanceChoices(
                            fields = StudioList(listOf(FieldResolution(conflict, ConflictChoice.USE_MINE))),
                        ),
                )

            val context = fixture.collector.observe(acceptance, current)

            assertEquals(draft.appearance.font, context.font.requested)
            assertEquals(
                19f,
                fixture.fonts.resolutions
                    .single()
                    .fontSize,
            )
            assertEquals(FontAvailability.AVAILABLE, context.font.availability)
        }

    @Test
    fun `fresh JVM evidence distinguishes an absent custom family without fallback`() =
        runBlocking {
            val fixture = Fixture()
            val unavailable = document(settings(13f).copy(fontFamily = "Ayu Missing Test Family 93e26cc9"))

            val context = fixture.collector.observe(request(unavailable), unavailable)

            assertEquals(FontAvailability.MISSING_CUSTOM, context.font.availability)
            assertEquals(unavailable.currents.global.appearance.font, context.font.requested)
            assertTrue(context.native.isEmpty())
            assertEquals(0, fixture.fonts.censuses)
        }

    @Test
    fun `Free keeps Font and Syntax owners while reporting restorable paid features`() =
        runBlocking {
            val fixture = Fixture()
            fixture.entitlement = LicenseEntitlement.UNLICENSED

            val context = fixture.collector.observe(request(fixture.document), fixture.document)

            val paid = context.capabilities.limitations.filter { it.reason == LimitationReason.PRO_REQUIRED }
            assertEquals(setOf(EffectOwner.GLOW, EffectOwner.VCS, EffectOwner.WORKSPACE), paid.map { it.owner }.toSet())
            assertTrue(paid.none { it.blocksEffect })
            assertFalse(
                context.capabilities.limitations.any {
                    it.owner == EffectOwner.FONT ||
                        it.owner == EffectOwner.SYNTAX
                },
            )
            assertEquals(FontAvailability.AVAILABLE, context.font.availability)
            fixture.entitlement = LicenseEntitlement.LICENSED
            assertTrue(
                fixture.collector
                    .observe(request(fixture.document), fixture.document)
                    .capabilities.limitations
                    .isEmpty(),
            )
        }

    @Test
    fun `unique foreign native font produces the existing exact review without adopting or writing`() =
        runBlocking {
            val fixture = Fixture()
            fixture.properties[FontIdentityRules.METADATA_KEY] = ID
            val stored =
                FontOwnershipRecord(FontOwnershipStatus.OWNED, snapshot("Before", 12f), snapshot("Applied", 15f))
            val captured = document(settings(13f), mapOf(FontIdentityRules.key(ID, FontSurface.EDITOR) to stored))
            val before = fixture.properties.toMap()
            val actual = FontSurface.EDITOR.captureExact(fixture.scheme)
            val target =
                FontOwnershipPolicy.target(
                    FontSurface.EDITOR,
                    false,
                    FontData.captureExact(fixture.scheme.fontPreferences),
                    settings(13f),
                    FAMILY,
                )
            val expected =
                StudioFontPolicy.change(
                    FontIdentityRules.key(ID, FontSurface.EDITOR),
                    stored,
                    actual,
                    target,
                )

            val context = fixture.collector.observe(request(captured), captured)

            assertEquals(listOf(expected), context.native)
            assertEquals(before, fixture.properties.toMap())
            assertEquals(actual, FontSurface.EDITOR.captureExact(fixture.scheme))
        }

    @Test
    fun `disabled font restores without requesting apply consent from a suspended record`() =
        runBlocking {
            val fixture = Fixture()
            fixture.properties[FontIdentityRules.METADATA_KEY] = ID
            val stored =
                FontOwnershipRecord(FontOwnershipStatus.SUSPENDED, snapshot("Before", 12f), snapshot("Applied", 15f))
            val captured =
                document(settings(13f), mapOf(FontIdentityRules.key(ID, FontSurface.EDITOR) to stored), false)

            val context = fixture.collector.observe(request(captured), captured)

            assertTrue(context.native.isEmpty())
            assertTrue(fixture.fonts.resolutions.isEmpty())
            assertEquals(0, fixture.fonts.censuses)
        }

    @Test
    fun `unconfirmed entitlement is never labelled as confirmed Free`() =
        runBlocking {
            val fixture = Fixture()
            fixture.entitlement = LicenseEntitlement.UNKNOWN

            val context = fixture.collector.observe(request(fixture.document), fixture.document)

            assertTrue(context.capabilities.limitations.none { it.reason == LimitationReason.PRO_REQUIRED })
        }

    private class Fixture {
        val document = document(settings(13f))
        val properties = Properties()
        val preferences = assertIs<FontSnapshot.Explicit>(snapshot("Personal", 18f)).preferences.toPreferences()
        val scheme =
            mockk<EditorColorsScheme> {
                every { metaProperties } returns properties
                every { fontPreferences } returns preferences
                every { isUseAppFontPreferencesInEditor } returns false
                every { isUseEditorFontPreferencesInConsole } returns false
            }
        var authority = AuthorityStatus.READY
        var target = TargetObservation(StudioTarget.Global, 1, TargetPresence.ACTIVE)
        var entitlement = LicenseEntitlement.LICENSED
        val fonts = Fonts(scheme)
        private val native =
            mockk<StudioNativeHost> {
                every { observe() } answers {
                    StudioNativeObservation(
                        mockk(),
                        scheme,
                        StudioNativeFocus(FocusSnapshot(StudioTarget.Global, 1), null),
                        entitlement,
                        AyuVariant.MIRAGE,
                        null,
                        StudioList(emptyList()),
                        null,
                    )
                }
            }
        val collector =
            StudioAcceptanceCollector(
                native,
                { target },
                { authority },
                fonts,
                StudioSessionDispatchers(EmptyCoroutineContext, EmptyCoroutineContext),
            )
    }

    private class Fonts(
        private val scheme: EditorColorsScheme,
    ) : StudioFontHost {
        val resolutions = mutableListOf<FontSettings>()
        var censuses = 0

        override suspend fun resolveFamily(settings: FontSettings): String {
            resolutions.add(settings)
            return settings.fontFamily
        }

        override fun observe(): FontSchemeView {
            censuses++
            return FontSchemeView(listOf(scheme), scheme, AyuVariant.MIRAGE)
        }

        override fun newIdentity(): String = error("Observation must not allocate a native identity")

        override fun refresh(): Unit = error("Observation must not refresh native state")
    }

    private companion object {
        const val ID = "30000000-0000-0000-0000-000000000005"
        val FAMILY: String = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.first()

        fun settings(size: Float): FontSettings =
            FontSettings.fromPreset(FontPreset.CUSTOM).copy(fontFamily = FAMILY, fontSize = size)

        fun request(document: StudioDocument) =
            AcceptanceRequest(sessionRecord(document), "observe", 1, AcceptanceOrigin.SETTINGS)

        fun document(
            font: FontSettings,
            ownership: Map<String, FontOwnershipRecord> = emptyMap(),
            enabled: Boolean = true,
        ): StudioDocument {
            val state =
                freshSettingsState().apply {
                    fontPresetEnabled = enabled
                    fontPresetName = FontPreset.CUSTOM.name
                    fontPresetCustomizations[FontPreset.CUSTOM.name] = font.encode()
                    fontOwnershipVersion = FontOwnership.VERSION
                    fontOwnershipSnapshots.putAll(ownership.mapValues { FontOwnershipCodec.encode(it.value) })
                }
            return assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(state))).document
        }

        fun snapshot(
            family: String,
            size: Float,
        ): FontSnapshot =
            FontSnapshot.Explicit(
                FontData(listOf(family), listOf(FontFamily(family, size)), size, 1f, false, null, null),
            )
    }
}
