package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.ModifiableFontPreferences
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontOwnershipCodec
import dev.ayuislands.font.FontOwnershipRecord
import dev.ayuislands.font.FontOwnershipStatus
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.EmptyEffectRuntime
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.fields.StudioRuntimeFields
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioFontOwnership
import dev.ayuislands.studio.session.FontSchemeView
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioFontHost
import dev.ayuislands.studio.session.StudioFontParticipant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioFontParticipantTest {
    @get:Rule val applicationRule: ApplicationRule = ApplicationRule()

    @get:Rule val temporary: TemporaryFolder = TemporaryFolder()

    @Test
    fun `later owner failure rolls back native Font and never accepts its runtime ownership`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                    val scheme = ParticipantFontScheme()
                    val before = snapshot(scheme)
                    val host = ParticipantFonts(listOf(scheme))
                    fixture.effect(EffectOwner.GLOW).apply {
                        beforePrepare = { assertNotEquals(before, snapshot(scheme)) }
                        failAfter = true
                    }
                    assertIs<AcceptanceResult.NotAccepted>(
                        coordinator(fixture, host).submit(fixture.allOwnersRequest()),
                    )
                    assertEquals(before, snapshot(scheme))
                    assertNull(scheme.metaProperties[FontIdentityRules.METADATA_KEY])
                    assertEquals(fixture.initial.runtime, fixture.document().runtime)
                    assertTrue(fixture.effects.all { it.accepts == 0 })
                }
            }
        }
    }

    @Test
    fun `lost Font acknowledgement at either replacement boundary restores through the outer coordinator`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                for (boundary in IoBoundary.entries) {
                    StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                        val scheme = ParticipantFontScheme()
                        val before = snapshot(scheme)
                        val host = ParticipantFonts(listOf(scheme))
                        host.afterRefresh = { fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", boundary)) }
                        assertIs<AcceptanceResult.NotAccepted>(
                            coordinator(fixture, host).submit(fixture.allOwnersRequest()),
                        )
                        assertEquals(0, fixture.files.pendingFailures)
                        assertEquals(before, snapshot(scheme))
                        assertNull(scheme.metaProperties[FontIdentityRules.METADATA_KEY])
                        assertEquals(fixture.initial.runtime, fixture.document().runtime)
                    }
                }
            }
        }
    }

    @Test
    fun `accepted cleanup recovery never rolls back or reapplies committed native Font`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                    val scheme = ParticipantFontScheme()
                    val host = ParticipantFonts(listOf(scheme))
                    host.afterRefresh = { host.afterRefresh = { error("Refresh failed during accepted cleanup") } }
                    val result = coordinator(fixture, host).submit(fixture.allOwnersRequest())
                    assertIs<AcceptanceResult.AcceptedCleanupPending>(result)
                    val accepted = snapshot(scheme)
                    val writes = scheme.editorWrites
                    val runtime = fixture.document().runtime
                    assertEquals(2, StudioFontOwnership(runtime).version)
                    assertIs<AcceptanceResult.Accepted>(coordinator(fixture, host).retryRecovery("all"))
                    assertEquals(accepted, snapshot(scheme))
                    assertEquals(writes, scheme.editorWrites)
                    assertEquals(runtime, fixture.document().runtime)
                }
            }
        }
    }

    @Test
    fun `refresh listener changes are observed before native output becomes acceptable`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val scheme = ParticipantFontScheme()
                val before = snapshot(scheme)
                val host = ParticipantFonts(listOf(scheme))
                val participant = StudioFontParticipant(host, EmptyCoroutineContext)
                val operation = participant.prepare(input(enabled()), EmptyEffectRuntime)
                host.afterRefresh = { (scheme.fontPreferences as ModifiableFontPreferences).lineSpacing = 1.9f }
                assertFailsWith<IllegalStateException> { operation.apply() }
                assertTrue(operation.runtimeChanges.isEmpty())
                restore(operation)
                val expected = FontSnapshot.Explicit(before.preferences.copy(lineSpacing = 1.9f))
                assertEquals(expected, snapshot(scheme))
            }
        }
    }

    @Test
    fun `lost forward acknowledgement recovers native operation without replaying Apply`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val scheme = ParticipantFontScheme()
                val before = snapshot(scheme)
                val host = ParticipantFonts(listOf(scheme))
                val participant = StudioFontParticipant(host, EmptyCoroutineContext)
                val input = input(enabled())
                val operation = participant.prepare(input, EmptyEffectRuntime)
                val pending = operation.receipts
                operation.apply()
                val recovered = participant.recover(input, pending, EmptyEffectRuntime)
                assertFailsWith<IllegalStateException> { recovered.apply() }
                restore(recovered)
                assertEquals(before, snapshot(scheme))
                assertNull(scheme.metaProperties[FontIdentityRules.METADATA_KEY])
                assertTrue(recovered.receipts.all { it.status == RestoreStatus.RESTORED })
                val settled = participant.recover(input, recovered.receipts, EmptyEffectRuntime)
                restore(settled)
                assertEquals(recovered.receipts, settled.receipts)
            }
        }
    }

    @Test
    fun `candidate remains detached until Apply and rollback retains the original schema and native fonts`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val scheme = ParticipantFontScheme()
                val before = snapshot(scheme)
                val host = ParticipantFonts(listOf(scheme))
                val participant = StudioFontParticipant(host, EmptyCoroutineContext)
                val input = input(enabled())
                val operation = participant.prepare(input, EmptyEffectRuntime)
                assertEquals(before, snapshot(scheme))
                assertNull(scheme.metaProperties[FontIdentityRules.METADATA_KEY])
                assertTrue(operation.runtimeChanges.isEmpty())
                assertTrue(operation.receipts.isNotEmpty())
                operation.apply()
                assertNotEquals(before, snapshot(scheme))
                assertEquals("Serif", scheme.fontPreferences.realFontFamilies.single())
                val current = StudioRuntimeFields.merge(input.candidate.runtime, operation.runtimeChanges)
                val id = scheme.metaProperties.getProperty(FontIdentityRules.METADATA_KEY)
                val managed = StudioFontOwnership(current).read(FontIdentityRules.key(id, FontSurface.EDITOR))
                assertEquals(before, managed?.baseline)
                assertEquals(snapshot(scheme), managed?.applied)
                assertEquals(0, StudioFontOwnership(input.candidate.runtime).version)
                restore(operation)
                assertEquals(before, snapshot(scheme))
                assertNull(scheme.metaProperties[FontIdentityRules.METADATA_KEY])
                assertTrue(operation.receipts.all { it.status == RestoreStatus.RESTORED })
            }
        }
    }

    @Test
    fun `disabled unknown preset restores managed fonts across schemes but preserves one shot and unknown records`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val first = ParticipantFontScheme()
                val second = ParticipantFontScheme()
                val firstId = UUID(0, 1).toString()
                val secondId = UUID(0, 2).toString()
                first.metaProperties.setProperty(FontIdentityRules.METADATA_KEY, firstId)
                second.metaProperties.setProperty(FontIdentityRules.METADATA_KEY, secondId)
                val baseline = snapshot(second)
                second.fontPreferences = FontPreferencesImpl().apply { register("Serif", 19f) }
                val secondApplied = snapshot(second)
                val state =
                    AyuIslandsState().apply {
                        fontPresetEnabled = false
                        fontPresetName = "FUTURE"
                        fontOwnershipVersion = 2
                        fontOwnershipSnapshots["future"] = "<opaque/>"
                        fontOwnershipSnapshots[FontIdentityRules.key(firstId, FontSurface.EDITOR)] =
                            FontOwnershipCodec.encode(
                                FontOwnershipRecord(FontOwnershipStatus.ONE_SHOT, baseline, snapshot(first)),
                            )
                        fontOwnershipSnapshots[FontIdentityRules.key(secondId, FontSurface.EDITOR)] =
                            FontOwnershipCodec.encode(
                                FontOwnershipRecord(FontOwnershipStatus.OWNED, baseline, secondApplied),
                            )
                    }
                val host = ParticipantFonts(listOf(first, second))
                val input = input(state)
                val operation = StudioFontParticipant(host, EmptyCoroutineContext).prepare(input, EmptyEffectRuntime)
                operation.apply()
                assertEquals(baseline, snapshot(second))
                assertEquals(0, host.familyReads)
                val current = StudioRuntimeFields.merge(input.candidate.runtime, operation.runtimeChanges)
                val ownership = StudioFontOwnership(current)
                assertEquals(setOf("future", FontIdentityRules.key(firstId, FontSurface.EDITOR)), ownership.keys)
                restore(operation)
                assertEquals(secondApplied, snapshot(second))
                assertEquals(secondId, second.metaProperties[FontIdentityRules.METADATA_KEY])
            }
        }
    }

    @Test
    fun `console failure after editor write keeps runtime unaccepted and restores only owned native fields`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val scheme = ParticipantFontScheme()
                scheme.consoleFontPreferences = FontPreferencesImpl().apply { register("Dialog", 17f) }
                val editorBefore = snapshot(scheme)
                val consoleBefore = FontSurface.CONSOLE.captureExact(scheme)
                val host = ParticipantFonts(listOf(scheme))
                val operation =
                    StudioFontParticipant(
                        host,
                        EmptyCoroutineContext,
                    ).prepare(input(enabled(console = true)), EmptyEffectRuntime)
                scheme.failConsole = true
                assertFailsWith<IllegalStateException> { operation.apply() }
                assertTrue(operation.runtimeChanges.isEmpty())
                (scheme.fontPreferences as ModifiableFontPreferences).setUseLigatures(true)
                restore(operation)
                val expected = FontSnapshot.Explicit(editorBefore.preferences.copy(ligatures = true))
                assertEquals(expected, snapshot(scheme))
                assertEquals(consoleBefore, FontSurface.CONSOLE.captureExact(scheme))
                assertTrue(operation.receipts.any { it.status == RestoreStatus.FOREIGN })
                assertNull(scheme.metaProperties[FontIdentityRules.METADATA_KEY])
            }
        }
    }

    @Test
    fun `active scheme race and unrepresentable managed baseline cannot start a native apply`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val first = ParticipantFontScheme()
                val second = ParticipantFontScheme()
                val before = snapshot(first)
                val host = ParticipantFonts(listOf(first, second))
                val participant = StudioFontParticipant(host, EmptyCoroutineContext)
                val operation = participant.prepare(input(enabled()), EmptyEffectRuntime)
                host.active = second
                assertFailsWith<IllegalStateException> { operation.apply() }
                restore(operation)
                assertEquals(before, snapshot(first))
                assertNull(first.metaProperties[FontIdentityRules.METADATA_KEY])
                host.active = first
                first.fontPreferences = before.preferences.copy(templateSize = 0f).toPreferences()
                assertFailsWith<IllegalStateException> { participant.prepare(input(enabled()), EmptyEffectRuntime) }
                assertNull(first.metaProperties[FontIdentityRules.METADATA_KEY])
                assertEquals(0f, FontData.captureExact(first.fontPreferences).templateSize)
            }
        }
    }

    private fun enabled(console: Boolean = false): AyuIslandsState =
        AyuIslandsState().apply {
            fontPresetEnabled = true
            fontPresetName = "AMBIENT"
            fontPresetCustomizations["AMBIENT"] = "19|1.4|false|REGULAR"
            fontApplyToConsole = console
        }

    private fun input(settings: AyuIslandsState): StudioEffectInput {
        val document =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.capture(settings)),
            ).document
        return StudioEffectInput(document, StudioTarget.Global, StudioList(emptyList()))
    }

    private fun snapshot(scheme: EditorColorsScheme): FontSnapshot.Explicit =
        FontSnapshot.Explicit(FontData.captureExact(scheme.fontPreferences))

    private suspend fun restore(operation: StudioEffectOperation) {
        operation.prepareRestore(StudioList(emptyList()))
        operation.restore(StudioList(emptyList()))
    }

    private fun coordinator(
        fixture: StudioApplyFixture,
        host: StudioFontHost,
    ): StudioApplyCoordinator =
        StudioApplyCoordinator(
            fixture.store,
            fixture,
            fixture.effects.map {
                if (it.owner ==
                    EffectOwner.FONT
                ) {
                    StudioFontParticipant(host, EmptyCoroutineContext)
                } else {
                    it
                }
            },
            fixture.mutex,
            EmptyCoroutineContext,
        )
}

private class ParticipantFontScheme : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
    var failConsole = false
    var editorWrites = 0

    init {
        name = "Participant"
        fontPreferences = FontPreferencesImpl().apply { register("Dialog", 13f) }
    }

    override fun setFontPreferences(preferences: FontPreferences) {
        editorWrites++
        super.setFontPreferences(preferences)
    }

    override fun setConsoleFontPreferences(preferences: FontPreferences) {
        super.setConsoleFontPreferences(preferences)
        if (failConsole) {
            failConsole = false
            error("Console failed after native mutation")
        }
    }
}

private class ParticipantFonts(
    private val available: List<EditorColorsScheme>,
) : StudioFontHost {
    var active: EditorColorsScheme = available.first()
    var familyReads = 0
    var afterRefresh: (() -> Unit)? = null
    private var sequence = 10L

    override fun observe(): FontSchemeView {
        check(SwingUtilities.isEventDispatchThread())
        return FontSchemeView(available, active)
    }

    override suspend fun resolveFamily(settings: FontSettings): String {
        familyReads++
        return "Serif"
    }

    override fun newIdentity(): String = UUID(0, sequence++).toString()

    override fun refresh() {
        check(SwingUtilities.isEventDispatchThread())
        val callback = afterRefresh
        afterRefresh = null
        callback?.invoke()
    }
}
