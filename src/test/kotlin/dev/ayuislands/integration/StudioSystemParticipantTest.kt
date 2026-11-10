package dev.ayuislands.integration

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioEffectParticipant
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioGlowHolds
import dev.ayuislands.studio.session.StudioIdeThemeHost
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioNativeFacts
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioSystemParticipant
import dev.ayuislands.studio.session.StudioThemeHost
import dev.ayuislands.studio.session.StudioThemeSnapshot
import dev.ayuislands.studio.session.StudioThemeTransition
import dev.ayuislands.theme.AyuEditorSchemeBinder
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import javax.swing.LookAndFeel
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSystemParticipantTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `explicit variant is independent of Follow and uses exact installed identity with retained rollback`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val before = fixture.host.observe()
                val original = participantInput(AyuIslandsState().apply { syncEditorScheme = false })
                val input = original.copy(themeChange = StudioThemeChange.Explicit(fixture.foreign.name))
                val runtime = fixture.runtime()
                val operation = fixture.participant.prepare(input, runtime)
                fixture.assertSnapshot(before)
                assertEquals(0, fixture.host.switches)
                try {
                    operation.apply()
                    assertSame(fixture.foreign, fixture.host.observe().theme)
                    assertSame(before.scheme, fixture.host.observe().scheme)
                    assertEquals(1, runtime.transitions.size)
                    assertEquals(original.candidate, input.candidate)
                } finally {
                    settleParticipant(operation)
                }
                fixture.assertSnapshot(before)
            }
        }

    @Test
    fun `stale system appearance observation cannot switch native theme after Follow was disabled`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val before = fixture.host.observe()
                val input = participantInput(AyuIslandsState()).copy(themeChange = StudioThemeChange.FollowSystem)
                val operation = fixture.participant.prepare(input, fixture.runtime())
                operation.apply()
                operation.accept()
                assertTrue(operation.receipts.isEmpty())
                assertEquals(0, fixture.host.switches)
                fixture.assertSnapshot(before)
            }
        }

    @Test
    fun `missing or ambiguous explicit installed target fails before native writes without changing preferences`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val before = fixture.host.observe()
                val original = participantInput(AyuIslandsState())
                for (name in listOf("Missing Ayu theme", fixture.foreign.name)) {
                    fixture.host.duplicateInstalled = name == fixture.foreign.name
                    assertFailsWith<IllegalArgumentException> {
                        fixture.participant.prepare(
                            original.copy(themeChange = StudioThemeChange.Explicit(name)),
                            fixture.runtime(),
                        )
                    }
                    fixture.assertSnapshot(before)
                    assertEquals(0, fixture.host.switches)
                }
            }
        }

    @Test
    fun `prepare is read only and verified native switch preserves exact editor scheme before acceptance`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val before = fixture.host.observe()
                val content = ParticipantSnapshot(assertIs<EditorColorsSchemeImpl>(before.scheme))
                val authority = StudioService.getInstance()
                val raw = authority.capture(null)?.let(authority::document)
                val input = fixture.input()
                val runtime = fixture.runtime()
                val operation = fixture.participant.prepare(input, runtime)
                try {
                    fixture.assertSnapshot(before)
                    content.assertUnchanged()
                    assertEquals(0, fixture.host.switches)
                    assertEquals(1, operation.receipts.size)
                    assertEquals(RestoreStatus.PENDING, operation.receipts.single().status)
                    assertTrue(runtime.transitions.isEmpty())

                    operation.apply()

                    assertSame(fixture.target, fixture.host.observe().theme)
                    assertSame(before.scheme, fixture.host.observe().scheme)
                    content.assertValues()
                    assertEquals(RestoreStatus.OWNED, operation.receipts.single().status)
                    assertEquals(1, runtime.transitions.size)
                    assertSame(
                        before.lookAndFeel,
                        runtime.transitions
                            .single()
                            .before.lookAndFeel,
                    )
                    assertSame(
                        fixture.host.observe().lookAndFeel,
                        runtime.transitions
                            .single()
                            .after.lookAndFeel,
                    )
                    assertEquals(raw, authority.capture(null)?.let(authority::document))
                    assertEquals(fixture.input().candidate, input.candidate)
                } finally {
                    settleParticipant(operation)
                }
                assertSame(before.theme, fixture.host.observe().theme)
                assertSame(before.scheme, fixture.host.observe().scheme)
                content.assertValues()
                assertEquals(RestoreStatus.RESTORED, operation.receipts.single().status)
                assertEquals(1, runtime.transitions.size)
            }
        }

    @Test
    fun `unchanged Follow unsupported appearance and already selected theme perform no native writes`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val before = fixture.host.observe()
                val unchanged =
                    fixture.participant.prepare(fixture.input().copy(themeChange = null), fixture.runtime())
                val unsupported =
                    StudioSystemParticipant(fixture.host, { null }, EmptyCoroutineContext)
                        .prepare(fixture.input(), fixture.runtime())
                val alreadySelected =
                    fixture.participant.prepare(fixture.input(fixture.initial.name), fixture.runtime())
                for (operation in listOf(unchanged, unsupported, alreadySelected)) {
                    operation.apply()
                    operation.accept()
                    assertTrue(operation.receipts.isEmpty())
                }
                fixture.assertSnapshot(before)
                assertEquals(0, fixture.host.switches)
            }
        }

    @Test
    fun `mutate then throw is rolled back without a successful native lease handoff`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val before = fixture.host.observe()
                val runtime = fixture.runtime()
                val operation = fixture.participant.prepare(fixture.input(), runtime)
                fixture.host.failAfterSwitch = true

                assertFailsWith<IllegalStateException> { operation.apply() }

                assertSame(fixture.target, fixture.host.observe().theme)
                assertTrue(runtime.transitions.isEmpty())
                settleParticipant(operation)
                assertSame(before.theme, fixture.host.observe().theme)
                assertSame(before.scheme, fixture.host.observe().scheme)
                assertEquals(RestoreStatus.RESTORED, operation.receipts.single().status)
            }
        }

    @Test
    fun `later owner abort restores actual theme through the durable outer coordinator`() =
        onThemeEdt {
            ThemeFixture().use { native ->
                val before = native.host.observe()
                StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                    val system = native.coordinatedParticipant()
                    val participants = fixture.effects.map { if (it.owner == EffectOwner.SYSTEM) system else it }
                    fixture.effect(EffectOwner.ACCENT).apply {
                        beforePrepare = {
                            assertSame(native.target, native.host.observe().theme)
                            val journal = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                            assertTrue(journal.receipts.any { it.owner == EffectOwner.SYSTEM })
                        }
                        failAfter = true
                    }
                    val coordinator =
                        StudioApplyCoordinator(
                            fixture.store,
                            fixture,
                            participants,
                            fixture.mutex,
                            EmptyCoroutineContext,
                        )
                    val request = fixture.allOwnersRequest()

                    val result = coordinator.submit(request)

                    assertIs<AcceptanceResult.NotAccepted>(result)
                    assertEquals(1, fixture.effect(EffectOwner.ACCENT).writes)
                    assertSame(before.theme, native.host.observe().theme)
                    assertSame(before.scheme, native.host.observe().scheme)
                    assertTrue(fixture.effects.all { it.accepts == 0 })
                }
            }
        }

    @Test
    fun `retained rollback preserves foreign theme and independently replaced editor scheme`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val first = fixture.participant.prepare(fixture.input(), fixture.runtime())
                first.apply()
                fixture.native.switchTo(fixture.foreign)
                val foreignSnapshot = fixture.host.observe()
                settleParticipant(first)
                fixture.assertSnapshot(foreignSnapshot)
                assertEquals(RestoreStatus.FOREIGN, first.receipts.single().status)

                fixture.native.switchTo(fixture.initial)
                val second = fixture.participant.prepare(fixture.input(), fixture.runtime())
                second.apply()
                val foreignScheme = fixture.colors.globalScheme.clone() as EditorColorsScheme
                fixture.colors.setGlobalScheme(foreignScheme)
                settleParticipant(second)
                assertSame(fixture.initial, fixture.host.observe().theme)
                assertSame(foreignScheme, fixture.host.observe().scheme)
                assertEquals(RestoreStatus.RESTORED, second.receipts.single().status)
            }
        }

    @Test
    fun `lost same or unknown process handles cannot restore or replay forward application`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val original = fixture.participant.prepare(fixture.input(), fixture.runtime())
                original.apply()
                val beforeRecovery = fixture.host.observe()
                val switches = fixture.host.switches
                for (process in listOf(fixture.process, null)) {
                    val recovery =
                        fixture.participant.recover(fixture.input(), original.receipts, fixture.runtime(process))
                    assertFailsWith<IllegalStateException> { recovery.apply() }
                    settleParticipant(recovery)
                    assertEquals(RestoreStatus.UNAVAILABLE, recovery.receipts.single().status)
                }
                fixture.assertSnapshot(beforeRecovery)
                assertEquals(switches, fixture.host.switches)
                settleParticipant(original)
            }
        }

    @Test
    fun `accepted theme receipt cleanup after lost handles never restores or repeats the native switch`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val original = fixture.participant.prepare(fixture.input(), fixture.runtime())
                original.apply()
                original.accept()
                val accepted = fixture.host.observe()
                val content = ParticipantSnapshot(assertIs<EditorColorsSchemeImpl>(accepted.scheme))
                val switches = fixture.host.switches
                assertEquals(RestoreStatus.OWNED, original.receipts.single().status)

                for (process in listOf(fixture.process, StudioProcess(2, Instant.EPOCH), null)) {
                    val runtime = fixture.runtime(process)
                    val recovered = fixture.participant.recover(fixture.input(), original.receipts, runtime)

                    recovered.accept()
                    recovered.accept()

                    fixture.assertSnapshot(accepted)
                    content.assertUnchanged()
                    assertEquals(switches, fixture.host.switches)
                    assertTrue(runtime.transitions.isEmpty())
                }
            }
        }

    @Test
    fun `restore failure after theme mutation retries refresh before acknowledging retained or recovered cleanup`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                for (loseHandles in listOf(false, true)) {
                    val operation = fixture.participant.prepare(fixture.input(), fixture.runtime())
                    operation.apply()
                    fixture.host.failAfterSwitch = true

                    assertFailsWith<IllegalStateException> { settleParticipant(operation) }

                    assertSame(fixture.initial, fixture.host.observe().theme)
                    assertFalse(StudioNativeEvidence.isTerminal(operation.receipts.single().status))
                    val switches = fixture.host.switches
                    val retry =
                        if (loseHandles) {
                            fixture.participant.recover(
                                fixture.input(),
                                operation.receipts,
                                fixture.runtime(StudioProcess(2, Instant.EPOCH)),
                            )
                        } else {
                            operation
                        }

                    settleParticipant(retry)

                    assertSame(fixture.initial, fixture.host.observe().theme)
                    assertEquals(switches + 1, fixture.host.switches)
                    assertEquals(RestoreStatus.RESTORED, retry.receipts.single().status)
                }
            }
        }

    @Test
    fun `different process recovery binds unique logical theme ids and preserves foreign selections`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val original = fixture.participant.prepare(fixture.input(), fixture.runtime())
                original.apply()
                val receipts = original.receipts
                val restarted = fixture.runtime(StudioProcess(2, Instant.EPOCH))
                val recovery = fixture.participant.recover(fixture.input(), receipts, restarted)
                assertFailsWith<IllegalStateException> { recovery.apply() }
                settleParticipant(recovery)
                assertSame(fixture.initial, fixture.host.observe().theme)
                assertEquals(RestoreStatus.RESTORED, recovery.receipts.single().status)
                assertTrue(restarted.transitions.isEmpty())

                fixture.native.switchTo(fixture.foreign)
                val foreign = fixture.host.observe()
                val foreignRecovery = fixture.participant.recover(fixture.input(), receipts, restarted)
                settleParticipant(foreignRecovery)
                fixture.assertSnapshot(foreign)
                assertEquals(RestoreStatus.FOREIGN, foreignRecovery.receipts.single().status)

                fixture.native.switchTo(fixture.target)
                fixture.host.duplicateInstalled = true
                val ambiguous = fixture.participant.recover(fixture.input(), receipts, restarted)
                val switches = fixture.host.switches
                settleParticipant(ambiguous)
                assertSame(fixture.target, fixture.host.observe().theme)
                assertEquals(switches, fixture.host.switches)
                assertEquals(RestoreStatus.UNAVAILABLE, ambiguous.receipts.single().status)
            }
        }
}

class StudioSystemSchemeTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `enabled synchronization captures then selects the registered variant scheme and restores exact identities`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                fixture.selectVariantScheme(AyuVariant.MIRAGE)
                val before = fixture.host.observe()
                val content = ParticipantSnapshot(assertIs<EditorColorsSchemeImpl>(before.scheme))
                val runtime = fixture.runtime()
                val input = fixture.syncInput()
                val operation = fixture.participant.prepare(input, runtime)
                fixture.assertSnapshot(before)
                content.assertUnchanged()
                assertEquals(0, fixture.host.schemeSelections)
                assertEquals(RestoreStatus.PENDING, operation.selectionReceipt().status)
                try {
                    operation.apply()
                    val applied = fixture.host.observe()
                    assertSame(fixture.target, applied.theme)
                    assertTrue(AyuEditorSchemeBinder.matchesVariant(applied.scheme.name, AyuVariant.LIGHT))
                    assertSame(
                        applied.scheme,
                        runtime.transitions
                            .single()
                            .after.scheme,
                    )
                    assertEquals(1, fixture.host.schemeSelections)
                    assertEquals(RestoreStatus.OWNED, operation.selectionReceipt().status)
                    assertEquals(input.candidate, fixture.syncInput().candidate)
                } finally {
                    settleParticipant(operation)
                }
                assertSame(before.theme, fixture.host.observe().theme)
                assertSame(before.scheme, fixture.host.observe().scheme)
                content.assertValues()
                assertEquals(RestoreStatus.RESTORED, operation.selectionReceipt().status)

                val accepted = fixture.participant.prepare(fixture.syncInput(), fixture.runtime())
                accepted.apply()
                val selected = fixture.host.observe()
                val writes = fixture.host.schemeSelections
                accepted.accept()
                accepted.accept()
                fixture.assertSnapshot(selected)
                assertEquals(writes, fixture.host.schemeSelections)
            }
        }

    @Test
    fun `disabled sync custom schemes and matching editable copies preserve the exact user selection`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                for (mode in listOf("disabled", "custom", "matching")) {
                    fixture.native.switchTo(fixture.initial)
                    fixture.selectVariantScheme(if (mode == "matching") AyuVariant.LIGHT else AyuVariant.MIRAGE)
                    if (mode == "custom") {
                        val custom =
                            (fixture.colors.globalScheme.clone() as EditorColorsScheme).apply {
                                name = "User preserved selection"
                            }
                        fixture.colors.setGlobalScheme(custom)
                    }
                    val before = fixture.host.observe()
                    val writes = fixture.host.schemeSelections
                    val operation =
                        fixture.participant.prepare(
                            fixture.syncInput(mode != "disabled"),
                            fixture.runtime(),
                        )
                    try {
                        operation.apply()
                        assertSame(before.scheme, fixture.host.observe().scheme)
                        assertEquals(writes, fixture.host.schemeSelections)
                        assertTrue(operation.receipts.none { it.identity.resource == "ide/editor-scheme" })
                    } finally {
                        settleParticipant(operation)
                    }
                    assertSame(before.scheme, fixture.host.observe().scheme)
                }
            }
        }

    @Test
    fun `selection mutation then failure restores theme and scheme without a successful lease handoff`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                fixture.selectVariantScheme(AyuVariant.MIRAGE)
                val before = fixture.host.observe()
                val runtime = fixture.runtime()
                val operation = fixture.participant.prepare(fixture.syncInput(), runtime)
                fixture.host.failAfterSelection = true
                try {
                    assertFailsWith<IllegalStateException> { operation.apply() }
                    assertSame(fixture.target, fixture.host.observe().theme)
                    assertTrue(
                        AyuEditorSchemeBinder.matchesVariant(
                            fixture.host
                                .observe()
                                .scheme.name,
                            AyuVariant.LIGHT,
                        ),
                    )
                    assertTrue(runtime.transitions.isEmpty())
                    fixture.host.failAfterSelection = true
                    assertFailsWith<IllegalStateException> { settleParticipant(operation) }
                    assertSame(before.scheme, fixture.host.observe().scheme)
                    assertFalse(StudioNativeEvidence.isTerminal(operation.selectionReceipt().status))
                } finally {
                    settleParticipant(operation)
                }
                assertSame(before.theme, fixture.host.observe().theme)
                assertSame(before.scheme, fixture.host.observe().scheme)
                assertEquals(RestoreStatus.RESTORED, operation.selectionReceipt().status)
                assertTrue(runtime.transitions.isEmpty())
            }
        }

    @Test
    fun `foreign selection before apply rejects capture and foreign selection after apply survives rollback`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                fixture.selectVariantScheme(AyuVariant.MIRAGE)
                val first = fixture.participant.prepare(fixture.syncInput(), fixture.runtime())
                fixture.selectVariantScheme(AyuVariant.DARK)
                val intervening = fixture.host.observe()
                assertFailsWith<IllegalStateException> { first.apply() }
                settleParticipant(first)
                fixture.assertSnapshot(intervening)

                fixture.selectVariantScheme(AyuVariant.MIRAGE)
                val before = fixture.host.observe()
                val second = fixture.participant.prepare(fixture.syncInput(), fixture.runtime())
                second.apply()
                fixture.selectVariantScheme(AyuVariant.DARK)
                val foreign = fixture.host.observe().scheme
                val writes = fixture.host.schemeSelections
                settleParticipant(second)
                assertSame(before.theme, fixture.host.observe().theme)
                assertSame(foreign, fixture.host.observe().scheme)
                assertEquals(writes, fixture.host.schemeSelections)
                assertEquals(RestoreStatus.FOREIGN, second.selectionReceipt().status)
            }
        }

    @Test
    fun `different process restores established unique scheme identities and preserves a later duplicate`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val selected =
                    AyuVariant.entries.associateWith { variant ->
                        fixture.selectVariantScheme(variant)
                        fixture.host.observe().scheme
                    }
                val before = selected.getValue(AyuVariant.MIRAGE)
                val planned = selected.getValue(AyuVariant.LIGHT)
                val other = selected.getValue(AyuVariant.DARK)
                val key = FontIdentityRules.METADATA_KEY
                val originalIds = selected.values.associateWith { it.metaProperties[key] }
                val beforeId = "21f90a3d-9873-4573-b24c-a21c0e91e318"
                val plannedId = "939c385f-0e91-4264-85cc-f354db6b9b4e"
                try {
                    before.metaProperties[key] = beforeId
                    planned.metaProperties[key] = plannedId
                    for (duplicate in listOf(false, true)) {
                        fixture.colors.setGlobalScheme(before)
                        val operation = fixture.participant.prepare(fixture.syncInput(), fixture.runtime())
                        operation.apply()
                        assertSame(planned, fixture.host.observe().scheme)
                        if (duplicate) other.metaProperties[key] = beforeId
                        val writes = fixture.host.schemeSelections
                        val recovered =
                            fixture.participant.recover(
                                fixture.syncInput(),
                                operation.receipts,
                                fixture.runtime(StudioProcess(2, Instant.EPOCH)),
                            )
                        settleParticipant(recovered)
                        if (duplicate) {
                            assertSame(planned, fixture.host.observe().scheme)
                            assertEquals(writes, fixture.host.schemeSelections)
                            assertEquals(RestoreStatus.UNAVAILABLE, recovered.selectionReceipt().status)
                        } else {
                            assertSame(before, fixture.host.observe().scheme)
                            assertEquals(writes + 1, fixture.host.schemeSelections)
                            assertEquals(RestoreStatus.RESTORED, recovered.selectionReceipt().status)
                        }
                        assertEquals(beforeId, before.metaProperties[key])
                        assertEquals(plannedId, planned.metaProperties[key])
                    }
                } finally {
                    originalIds.forEach { (scheme, raw) ->
                        if (raw == null) scheme.metaProperties.remove(key) else scheme.metaProperties[key] = raw
                    }
                }
            }
        }

    @Test
    fun `lost scheme handles never recover by matching names and accepted cleanup never replays selection`() =
        onThemeEdt {
            ThemeFixture().use { fixture ->
                val key = FontIdentityRules.METADATA_KEY
                val schemes = fixture.host.schemes().filterIsInstance<EditorColorsSchemeImpl>()
                val identities = schemes.associateWith { it.metaProperties[key] }
                schemes.forEach { it.metaProperties.remove(key) }
                try {
                    fixture.selectVariantScheme(AyuVariant.MIRAGE)
                    val input = fixture.syncInput()
                    val original = fixture.participant.prepare(input, fixture.runtime())
                    original.apply()
                    val written = fixture.host.observe().scheme
                    val writes = fixture.host.schemeSelections
                    try {
                        for (process in listOf(fixture.process, null, StudioProcess(2, Instant.EPOCH))) {
                            val recovered =
                                fixture.participant.recover(input, original.receipts, fixture.runtime(process))
                            settleParticipant(recovered)
                            assertSame(written, fixture.host.observe().scheme)
                            assertEquals(writes, fixture.host.schemeSelections)
                            assertEquals(RestoreStatus.UNAVAILABLE, recovered.selectionReceipt().status)
                            val committed =
                                fixture.participant.recover(input, original.receipts, fixture.runtime(process))
                            committed.accept()
                            committed.accept()
                            assertSame(written, fixture.host.observe().scheme)
                            assertEquals(writes, fixture.host.schemeSelections)
                        }
                    } finally {
                        settleParticipant(original)
                    }
                } finally {
                    identities.forEach { (scheme, raw) ->
                        if (raw == null) scheme.metaProperties.remove(key) else scheme.metaProperties[key] = raw
                    }
                }
            }
        }
}

private fun StudioEffectOperation.selectionReceipt() = receipts.single { it.identity.resource == "ide/editor-scheme" }

private class ThemeFixture : AutoCloseable {
    val native = StudioIdeThemeHost()
    val colors: EditorColorsManager = EditorColorsManager.getInstance()
    private val original = native.observe()
    val initial: UIThemeLookAndFeelInfo = installed("Ayu Mirage (Islands UI)")
    val target: UIThemeLookAndFeelInfo = installed("Ayu Light (Islands UI)")
    val foreign: UIThemeLookAndFeelInfo = installed("Ayu Dark (Islands UI)")
    val host = RecordingThemeHost(native)
    val process = StudioProcess(1, Instant.EPOCH)
    val participant = StudioSystemParticipant(host, { Appearance.LIGHT }, EmptyCoroutineContext)

    init {
        native.switchTo(initial)
        colors.setGlobalScheme(original.scheme.clone() as EditorColorsScheme)
    }

    fun input(lightTheme: String = target.name): StudioEffectInput =
        participantInput(
            AyuIslandsState().apply {
                followSystemAppearance = true
                syncEditorScheme = false
                lastLightAppearanceTheme = lightTheme
            },
        ).copy(themeChange = StudioThemeChange.FollowSystem)

    fun syncInput(enabled: Boolean = true): StudioEffectInput =
        participantInput(AyuIslandsState().apply { syncEditorScheme = enabled })
            .copy(themeChange = StudioThemeChange.Explicit(target.name))

    fun selectVariantScheme(variant: AyuVariant) {
        colors.setGlobalScheme(requireNotNull(colors.getScheme(AyuEditorSchemeBinder.targetSchemeName(variant))))
        assertTrue(AyuEditorSchemeBinder.matchesVariant(colors.globalScheme.name, variant))
    }

    fun runtime(currentProcess: StudioProcess? = process): ThemeRuntime = ThemeRuntime(host, currentProcess)

    fun coordinatedParticipant(): StudioEffectParticipant =
        object : StudioEffectParticipant by participant {
            override suspend fun prepare(
                input: StudioEffectInput,
                runtime: StudioEffectRuntime,
            ): StudioEffectOperation = participant.prepare(input, ThemeRuntime(host, runtime.native.currentProcess))
        }

    fun assertSnapshot(expected: StudioThemeSnapshot) {
        val actual = host.observe()
        assertSame(expected.theme, actual.theme)
        assertSame(expected.lookAndFeel, actual.lookAndFeel)
        assertSame(expected.scheme, actual.scheme)
    }

    override fun close() {
        try {
            native.switchTo(requireNotNull(original.theme))
        } finally {
            colors.setGlobalScheme(original.scheme)
        }
    }

    private fun installed(name: String): UIThemeLookAndFeelInfo =
        LafManager.getInstance().installedThemes.single { it.name == name }
}

private class RecordingThemeHost(
    private val delegate: StudioThemeHost,
) : StudioThemeHost by delegate {
    var switches = 0
        private set
    var failAfterSwitch = false
    var duplicateInstalled = false
    var schemeSelections = 0
        private set
    var failAfterSelection = false

    override fun selectScheme(scheme: EditorColorsScheme) {
        schemeSelections++
        delegate.selectScheme(scheme)
        if (failAfterSelection) {
            failAfterSelection = false
            error("Editor scheme changed before selection completion failed")
        }
    }

    override fun installed(): List<UIThemeLookAndFeelInfo> =
        delegate.installed().let { if (duplicateInstalled) it + it else it }

    override fun switchTo(
        target: UIThemeLookAndFeelInfo,
        restoredLaf: LookAndFeel?,
    ) {
        switches++
        delegate.switchTo(target, restoredLaf)
        if (failAfterSwitch) {
            failAfterSwitch = false
            error("Native theme changed before refresh completion failed")
        }
    }
}

private class ThemeRuntime(
    private val host: StudioThemeHost,
    process: StudioProcess?,
) : StudioEffectRuntime {
    override val native = StudioNativeFacts(true, process)
    override val glow: StudioGlowHolds? = null
    override val accent: StudioAccentResult? = null
    override val observation: StudioNativeObservation? = null
    val transitions = mutableListOf<StudioThemeTransition>()

    override suspend fun confirmAccent(result: StudioAccentResult): Unit = error("SYSTEM does not publish Accent")

    override suspend fun confirmTheme(transition: StudioThemeTransition) {
        val observed = host.observe()
        assertSame(observed.theme, transition.after.theme)
        assertSame(observed.lookAndFeel, transition.after.lookAndFeel)
        assertSame(observed.scheme, transition.after.scheme)
        transitions.add(transition)
    }
}

private fun onThemeEdt(action: suspend () -> Unit) {
    SwingUtilities.invokeAndWait { runBlocking { action() } }
}
