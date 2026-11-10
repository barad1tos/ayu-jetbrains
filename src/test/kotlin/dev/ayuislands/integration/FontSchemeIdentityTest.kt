package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontOwnershipCodec
import dev.ayuislands.font.FontOwnershipStatus
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.runtime.FocusSnapshot
import dev.ayuislands.studio.runtime.RuntimeDispatch
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator
import dev.ayuislands.studio.runtime.StudioRuntimeSource
import dev.ayuislands.studio.session.StudioApplyCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontSchemeIdentityTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    private lateinit var native: FontNativeFixture
    private var activeScheme: EditorColorsSchemeImpl
        get() = native.scheme as EditorColorsSchemeImpl
        set(value) {
            native.scheme = value
        }
    private val schemes get() = native.schemes

    @Before
    fun setup() {
        native = FontNativeFixture()
        activeScheme.fontPreferences = FontPreferencesImpl().apply { register("Dialog", 17f) }
    }

    @Test
    fun sameObjectRenameRestoresBaseline() =
        onEdt {
            val original = fonts()
            native.apply(customFonts())
            activeScheme.name = "Renamed personal scheme"

            native.disable()

            assertEquals(original, fonts())
            assertTrue(native.state.fontOwnershipSnapshots.isEmpty())
        }

    @Test
    fun reusedNameCannotAuthorizeRestore() =
        onEdt {
            native.apply(customFonts())
            val backup = native.state.fontOwnershipSnapshots.toMap()
            activeScheme = activeScheme.clone() as EditorColorsSchemeImpl
            assertNull(activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            schemes.clear()
            schemes.add(activeScheme)
            val unrelated = fonts()

            native.disable()

            assertEquals(unrelated, fonts())
            assertEquals(backup, native.state.fontOwnershipSnapshots)
        }

    @Test
    fun nativeRenameRequiresExplicitApply() =
        onEdt {
            native.apply(customFonts())
            val backup = native.state.fontOwnershipSnapshots.toMap()
            activeScheme = (activeScheme.clone() as EditorColorsSchemeImpl).apply { name = "Native rename" }
            schemes.clear()
            schemes.add(activeScheme)
            val renamedFonts = fonts()
            native.state.fontPresetEnabled = true
            native.state.fontPresetName = FontPreset.CUSTOM.name

            refreshAutomatically()

            assertEquals(renamedFonts, fonts())
            assertEquals(backup, native.state.fontOwnershipSnapshots)
            native.disable()

            assertEquals(renamedFonts, fonts())
            assertEquals(backup, native.state.fontOwnershipSnapshots)
            native.apply(customFonts().copy(fontFamily = "Monospaced"))
            native.disable()
            assertEquals(renamedFonts, fonts())
            assertEquals(backup, native.state.fontOwnershipSnapshots)
        }

    @Test
    fun nativeCopyCapturesItsOwnBaseline() =
        onEdt {
            val original = activeScheme
            native.apply(customFonts())
            activeScheme = (original.clone() as EditorColorsSchemeImpl).apply { name = "Writing copy" }
            schemes.add(activeScheme)
            val copyBaseline = fonts()
            native.apply(customFonts().copy(fontFamily = "Monospaced"))

            native.disable()

            assertEquals(copyBaseline, fonts())
            assertEquals("Dialog", original.editorFontName)
            assertTrue(native.state.fontOwnershipSnapshots.isEmpty())
        }

    @Test
    fun xmlReloadRetainsIdentity() =
        onEdt {
            native.apply(customFonts())
            val identity = assertNotNull(activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            val keys =
                native.state.fontOwnershipSnapshots.keys
                    .toSet()
            assertEquals(2, native.state.fontOwnershipVersion)
            assertTrue(keys.all { it == "v2:EDITOR:$identity" })
            activeScheme = xmlCopy()
            schemes[0] = activeScheme
            reloadSettings()

            assertEquals(identity, activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            native.apply(customFonts())
            assertEquals(keys, native.state.fontOwnershipSnapshots.keys)
        }

    @Test fun duplicateIdentityRemainsSuspended() = onEdt { checkCollision(oneShot = false) }

    @Test fun duplicateOneShotRemainsSuspended() = onEdt { checkCollision(oneShot = true) }

    @Test
    fun legacyBackupsStayOpaque() =
        onEdt {
            native.apply(customFonts())
            val legacy =
                native.state.fontOwnershipSnapshots.values
                    .single()
                    .replace("version=\"2\"", "version=\"1\"")
            native.state.fontOwnershipSnapshots.clear()
            native.state.fontOwnershipSnapshots["EDITOR:${activeScheme.name}"] = legacy
            native.state.fontOwnershipVersion = 1
            activeScheme.metaProperties.remove(IDENTITY_KEY)
            val baseline = fonts()
            val opaque = native.state.fontOwnershipSnapshots.toMap()

            native.disable()
            assertEquals(baseline, fonts())
            native.apply(customFonts().copy(fontFamily = "Monospaced"))
            reloadSettings()
            native.disable()

            assertEquals(baseline, fonts())
            assertEquals(opaque, native.state.fontOwnershipSnapshots)
            assertEquals(2, native.state.fontOwnershipVersion)
        }

    @Test
    fun invalidIdentityBlocksExplicitApply() =
        onEdt {
            activeScheme.metaProperties.setProperty(IDENTITY_KEY, "future:identity")
            val baseline = fonts()

            assertFailsWith<IllegalStateException> { native.apply(customFonts()) }

            assertEquals(baseline, fonts())
            assertEquals("future:identity", activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            assertTrue(native.state.fontOwnershipSnapshots.isEmpty())
            assertEquals(0, native.state.fontOwnershipVersion)
        }

    @Test
    fun futureStateBlocksMetadataAdoption() =
        onEdt {
            native.state.fontOwnershipVersion = 99
            native.state.fontOwnershipSnapshots["future:record"] = " exact opaque data "
            val stateXml = JDOMUtil.writeElement(XmlSerializer.serialize(native.state))
            val baseline = fonts()

            assertFailsWith<IllegalStateException> { native.apply(customFonts()) }
            native.disable()

            assertEquals(baseline, fonts())
            assertNull(activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            assertEquals(stateXml, JDOMUtil.writeElement(XmlSerializer.serialize(native.state)))
        }

    @Test
    fun invalidSettingsCannotAdoptIdentity() =
        onEdt {
            assertFailsWith<IllegalArgumentException> {
                native.apply(customFonts().copy(fontSize = Float.NaN))
            }
            assertNull(activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            assertEquals(0, native.state.fontOwnershipVersion)
            assertTrue(native.state.fontOwnershipSnapshots.isEmpty())
        }

    @Test
    fun automaticApplyCannotAdoptConsole() =
        onEdt {
            activeScheme.consoleFontPreferences =
                FontPreferencesImpl().apply { register("Monospaced", 15f) }
            native.apply(customFonts())
            val console = FontData.capture(activeScheme.consoleFontPreferences)
            native.state.fontPresetEnabled = true
            native.state.fontPresetName = FontPreset.CUSTOM.name
            native.state.fontApplyToConsole = true

            refreshAutomatically()

            assertEquals(console, FontData.capture(activeScheme.consoleFontPreferences))
            assertEquals(1, native.state.fontOwnershipSnapshots.size)
        }

    private fun refreshAutomatically() =
        runBlocking {
            native.state.fontPresetEnabled = true
            native.state.fontPresetName = FontPreset.CUSTOM.name
            native.state.fontPresetCustomizations[FontPreset.CUSTOM.name] = customFonts().encode()
            StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                val latest = fixture.document()
                val captured = StudioFixtures.legacyDocument(native.state)
                val current = latest.currents.global.copy(appearance = captured.currents.global.appearance)
                val checkpoint = RotationRuntimeCheckpoint(RuntimeOwner(StudioTarget.Global, current.revision), 0, 0)
                val accepted =
                    latest.copy(
                        header =
                            latest.header.copy(
                                revision = latest.header.revision + 1,
                                transactionId = "seed-font-native",
                            ),
                        currents = latest.currents.copy(global = current),
                        runtime = captured.runtime.copy(rotation = checkpoint),
                    )
                seedDocument(fixture, accepted)
                val focus = FocusSnapshot(StudioTarget.Global, 1)
                val snapshot =
                    RotationSnapshot(
                        checkpoint,
                        RuntimeInputs(
                            checkpoint.owner,
                            current.appearance,
                            RuntimeAvailability(
                                lastDefinitivePro = true,
                                effectiveFollowSystem = false,
                                variant = AyuVariant.MIRAGE,
                                targetValid = true,
                                recoveryPending = false,
                            ),
                        ),
                    )
                val runtime = StudioRuntimeCoordinator(RotationRuntimeState.Off(snapshot), focus)
                val participants = fixture.effects.filterNot { it.owner == EffectOwner.FONT } + native.participant
                val coordinator = StudioApplyCoordinator(fixture.store, fixture, participants, fixture.mutex)
                val sources =
                    object : StudioRuntimeSource {
                        override fun clock() = ClockSample(100, 100_000_000)

                        override fun operationId(): UUID = UUID(0, 1)

                        override fun transactionId(): String = "automatic-font-refresh"

                        override fun randomColor(variant: AyuVariant): String =
                            error("Appearance refresh cannot draw a color")
                    }

                coordinator.executeRuntime(
                    runtime,
                    RuntimeDispatch.NativeChange(focus, StudioNativeChange.Appearance),
                    sources,
                )

                val document = fixture.document()
                assertEquals(accepted.currents, document.currents)
                assertEquals(accepted.shared, document.shared)
                assertEquals(1, fixture.effect(EffectOwner.ACCENT).writes)
                val projected = requireNotNull(StudioLegacyProjection.project(document, StudioTarget.Global)).settings
                native.state.fontOwnershipVersion = projected.fontOwnershipVersion
                native.state.fontOwnershipSnapshots.clear()
                native.state.fontOwnershipSnapshots.putAll(projected.fontOwnershipSnapshots)
            }
        }

    private fun seedDocument(
        fixture: StudioApplyFixture,
        candidate: StudioDocument,
    ) {
        val token =
            assertIs<PrepareResult.Prepared>(
                fixture.store.prepare(
                    fixture.document().header.revision,
                    candidate,
                    RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                ),
            ).token
        assertIs<CommitResult.Committed>(fixture.store.commit(token, candidate.runtime))
        assertEquals(JournalResult.Acknowledged, fixture.store.finishAccepted(token))
    }

    private fun checkCollision(oneShot: Boolean) {
        native.apply(customFonts())
        if (oneShot) {
            val key =
                native.state.fontOwnershipSnapshots.keys
                    .single()
            val record = requireNotNull(FontOwnershipCodec.decode(native.state.fontOwnershipSnapshots.getValue(key)))
            native.state.fontOwnershipSnapshots[key] =
                FontOwnershipCodec.encode(
                    record.copy(status = FontOwnershipStatus.ONE_SHOT),
                )
        }
        val originalFonts = fonts()
        val copied = xmlCopy().apply { name = "Imported copy" }
        schemes.add(copied)
        val before = native.state.fontOwnershipSnapshots.toMap()

        assertFailsWith<IllegalStateException> { native.apply(customFonts().copy(fontFamily = "Monospaced")) }
        assertEquals(originalFonts, fonts())
        assertEquals(before, native.state.fontOwnershipSnapshots)
        native.disable()
        assertTrue(
            native.state.fontOwnershipSnapshots.values
                .all { "status=\"SUSPENDED\"" in it },
        )
        schemes.remove(copied)
        reloadSettings()
        native.disable()
        assertEquals(originalFonts, fonts())
        assertTrue(native.state.fontOwnershipSnapshots.isNotEmpty())
    }

    private fun xmlCopy(): EditorColorsSchemeImpl =
        EditorColorsSchemeImpl(activeScheme.parentScheme).apply {
            readExternal(JDOMUtil.load(JDOMUtil.writeElement(activeScheme.writeScheme())))
        }

    private fun reloadSettings() {
        native.reload()
    }

    private fun fonts(): FontData = FontData.capture(activeScheme.fontPreferences)

    private fun customFonts(): FontSettings =
        FontSettings.fromPreset(FontPreset.CUSTOM).copy(fontFamily = "Serif", fontSize = 19f)

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private companion object {
        const val IDENTITY_KEY = "dev.ayuislands.fontOwnershipId"
    }
}
