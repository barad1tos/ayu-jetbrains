package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.options.Scheme
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.FontSchemeView
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioEffectParticipant
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioFontHost
import dev.ayuislands.studio.session.StudioFontParticipant
import dev.ayuislands.studio.session.StudioGlowHolds
import dev.ayuislands.studio.session.StudioNativeFacts
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioRuntimeLease
import dev.ayuislands.studio.session.StudioSchemeIdentityReceipt
import dev.ayuislands.studio.session.StudioSyntaxParticipant
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.studio.session.StudioThemeTransition
import dev.ayuislands.studio.session.StudioVcsParticipant
import dev.ayuislands.syntax.SyntaxCellId
import dev.ayuislands.syntax.SyntaxFontState
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.theme.EditorSchemeOverrides
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jdom.Element
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.UUID
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSyntaxParticipantTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @After
    fun clearOwnership() {
        SwingUtilities.invokeAndWait { EditorSchemeOverrides.reset() }
    }

    @Test
    fun `actual Syntax participant captures registered editable targets before changing native or semantic state`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val fixture = ParticipantSyntax(detached = false)
                val identityKey = FontIdentityRules.METADATA_KEY
                val identities = fixture.targets.associateWith { it.metaProperties[identityKey] }
                fixture.targets.forEach { it.metaProperties.remove(identityKey) }
                try {
                    val expected =
                        fixture.service.prepare(
                            fixture.config,
                            isLicensed = true,
                            ignorePluginSyntaxColorsEnabled = true,
                        )
                    val before = fixture.targets.associateWith(::ParticipantSnapshot)
                    val canonical =
                        fixture.targets.mapNotNull { it.original }.associateWith { scheme ->
                            scheme.directlyDefinedAttributes.mapValues { it.value.clone() }
                        }
                    val active = EditorColorsManager.getInstance().globalScheme
                    val semantic = fixture.fonts.current
                    val candidate = fixture.input.candidate
                    val operation = fixture.participant().prepare(fixture.input, participantRuntime())
                    try {
                        before.values.forEach { it.assertUnchanged() }
                        assertSame(semantic, fixture.fonts.current)
                        assertTrue(operation.receipts.isNotEmpty())
                        assertTrue(operation.receipts.all { it.owner == EffectOwner.SYNTAX })
                        assertTrue(operation.receipts.any { it.identity.field == StudioSchemeIdentityReceipt.FIELD })

                        operation.apply()

                        for ((scheme, _, attributes) in expected.changes) {
                            for ((key, expectedAttributes) in attributes) {
                                assertEquals(expectedAttributes, scheme.getAttributes(key), key.externalName)
                            }
                        }
                        assertEquals(expected.replacementFonts, fixture.fonts.current.styles)
                        assertEquals(candidate, fixture.input.candidate)
                        assertSame(active, EditorColorsManager.getInstance().globalScheme)
                        canonical.forEach { (scheme, attributes) ->
                            assertEquals(attributes, scheme.directlyDefinedAttributes)
                        }
                    } finally {
                        settleParticipant(operation)
                    }
                    before.values.forEach { it.assertValues() }
                    assertSame(semantic, fixture.fonts.current)
                } finally {
                    identities.forEach { (scheme, raw) ->
                        if (raw == null) {
                            scheme.metaProperties.remove(identityKey)
                        } else {
                            scheme.metaProperties[identityKey] = raw
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `later owner failure restores Font Syntax and VCS through one adopted scheme identity`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val syntax = ParticipantSyntax()
                val before = syntax.targets.associateWith(::ParticipantSnapshot)
                val semantic = syntax.fonts.current
                StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                    val participants =
                        fixture.effects.map { effect ->
                            when (effect.owner) {
                                EffectOwner.FONT -> StudioFontParticipant(syntax.host, EmptyCoroutineContext)
                                EffectOwner.SYNTAX -> syntax.participant()
                                EffectOwner.VCS -> StudioVcsParticipant(syntax.host, EmptyCoroutineContext)
                                else -> effect
                            }
                        }
                    fixture.effect(EffectOwner.GLOW).apply {
                        beforePrepare = {
                            val ids = syntax.targets.map { it.metaProperties[FontIdentityRules.METADATA_KEY] }
                            assertTrue(ids.all { it is String })
                            assertEquals(ids.size, ids.distinct().size)
                            assertEquals(syntax.targets.size, syntax.host.identities)
                        }
                        failAfter = true
                    }
                    val coordinator =
                        StudioApplyCoordinator(
                            fixture.store,
                            ParticipantEnvironment(fixture),
                            participants,
                            fixture.mutex,
                            EmptyCoroutineContext,
                        )

                    val request = fixture.allOwnersRequest()
                    val durableDraft =
                        fixture.initial.copy(
                            header =
                                fixture.initial.header.copy(
                                    revision = fixture.initial.header.revision + 1,
                                    transactionId = "draft-1",
                                ),
                            sessions = StudioList(listOf(request.session)),
                        )
                    assertIs<AcceptanceResult.NotAccepted>(coordinator.submit(request))

                    before.values.forEach { it.assertValues() }
                    assertSame(semantic, syntax.fonts.current)
                    assertEquals(durableDraft, fixture.document())
                    assertTrue(fixture.effects.all { it.accepts == 0 })
                }
            }
        }
    }

    @Test
    fun `Syntax restart routes persistent receipts separately from lost semantic handles and unknown process facts`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val fixture = ParticipantSyntax()
                val before = fixture.targets.map(::participantReload)
                val operation = fixture.participant().prepare(fixture.input, participantRuntime())
                try {
                    operation.apply()
                    val acknowledged = operation.receipts
                    val loaded = fixture.targets.map(::participantReload)
                    val recoveredHost = ParticipantSchemeHost(loaded)
                    val recoveredService = SyntaxIntensityService()
                    val recoveredFonts = recoveredService.fontState.current
                    val participant =
                        StudioSyntaxParticipant(recoveredHost, recoveredService, EmptyCoroutineContext)
                    val unknown = participant.recover(fixture.input, acknowledged, participantRuntime(process = null))
                    val applied = loaded.map(::ParticipantSnapshot)

                    settleParticipant(unknown)

                    applied.forEach { it.assertUnchanged() }
                    assertTrue(unknown.receipts.any { it.status == RestoreStatus.UNAVAILABLE })
                    val recovery =
                        participant.recover(
                            fixture.input,
                            acknowledged,
                            participantRuntime(process = restartedProcess),
                        )
                    assertFailsWith<IllegalStateException> { recovery.apply() }
                    settleParticipant(recovery)

                    loaded.zip(before).forEach { (actual, original) ->
                        assertEquals(
                            original.directlyDefinedAttributes,
                            participantReload(actual).directlyDefinedAttributes,
                        )
                        assertEquals(original.metaProperties.toMap(), actual.metaProperties.toMap())
                    }
                    assertSame(recoveredFonts, recoveredService.fontState.current)
                    assertTrue(recovery.receipts.all { it.status == RestoreStatus.RESTORED })
                } finally {
                    settleParticipant(operation)
                }
            }
        }
    }
}

internal val participantProcess = StudioProcess(51, Instant.EPOCH)
private val restartedProcess = StudioProcess(52, Instant.EPOCH.plusSeconds(1))

internal fun participantRuntime(
    licensed: Boolean = true,
    process: StudioProcess? = participantProcess,
): StudioEffectRuntime =
    object : StudioEffectRuntime {
        override val glow: StudioGlowHolds? = null
        override val accent: StudioAccentResult? = null
        override val observation: StudioNativeObservation? = null

        override suspend fun confirmAccent(result: StudioAccentResult): Unit =
            error("This fixture does not hold native Accent admission")

        override suspend fun confirmTheme(transition: StudioThemeTransition): Unit =
            error("This fixture does not hold native theme admission")

        override val native = StudioNativeFacts(licensed, process)
    }

internal suspend fun settleParticipant(operation: StudioEffectOperation) {
    repeat(3) {
        operation.prepareRestore(StudioList(emptyList()))
        if (operation.restore(StudioList(emptyList())) == StudioRestoreContinuation.NONE) return
    }
    error("Native participant rollback did not settle")
}

internal fun participantInput(
    settings: AyuIslandsState = AyuIslandsState(),
    syntax: SyntaxIntensityBaseState = SyntaxIntensityBaseState(),
): StudioEffectInput {
    val legacy = StudioFixtures.capture(settings).copy(fields = StudioFixtures.fields(settings, syntax))
    val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(legacy)).document
    return StudioEffectInput(document, StudioTarget.Global, StudioList(emptyList()))
}

internal fun participantReload(source: EditorColorsSchemeImpl): EditorColorsSchemeImpl {
    val detached =
        (source.clone() as EditorColorsSchemeImpl).apply {
            metaProperties.clear()
            metaProperties.putAll(source.metaProperties)
        }
    val element = Element("scheme")
    detached.writeExternal(element)
    return EditorColorsSchemeImpl(source.parentScheme).apply {
        readExternal(JDOMUtil.load(JDOMUtil.writeElement(element)))
    }
}

internal class ParticipantSchemeHost(
    val schemes: List<EditorColorsScheme>,
) : StudioFontHost {
    var active: EditorColorsScheme = schemes.first()
    var identities = 0
    var refreshes = 0

    override fun observe(): FontSchemeView = FontSchemeView(schemes, active, AyuVariant.MIRAGE)

    override fun newIdentity(): String = UUID(0, (++identities).toLong()).toString()

    override suspend fun resolveFamily(settings: FontSettings): String = "Serif"

    override fun refresh() {
        check(SwingUtilities.isEventDispatchThread())
        refreshes++
    }
}

internal class ParticipantSnapshot(
    private val scheme: EditorColorsSchemeImpl,
) {
    private val colors = scheme.directlyDefinedColors.toMap()
    private val attributes = scheme.directlyDefinedAttributes.mapValues { it.value.clone() }
    private val references = scheme.directlyDefinedAttributes.toMap()
    private val metadata = scheme.metaProperties.toMap()
    private val saveState = scheme.schemeState
    private val editorFont = FontData.captureExact(scheme.fontPreferences)
    private val consoleFont = FontData.captureExact(scheme.consoleFontPreferences)

    fun assertUnchanged() {
        assertValues()
        assertEquals(saveState, scheme.schemeState)
        references.forEach { (key, value) -> assertSame(value, scheme.directlyDefinedAttributes[key], key) }
        colors.forEach { (key, value) -> assertSame(value, scheme.directlyDefinedColors[key], key.externalName) }
    }

    fun assertValues() {
        assertCells()
        assertEquals(metadata, scheme.metaProperties.toMap())
        assertEquals(editorFont, FontData.captureExact(scheme.fontPreferences))
        assertEquals(consoleFont, FontData.captureExact(scheme.consoleFontPreferences))
    }

    fun assertCells() {
        assertEquals(colors, scheme.directlyDefinedColors)
        assertEquals(attributes, scheme.directlyDefinedAttributes)
    }
}

private class ParticipantSyntax(
    detached: Boolean = true,
) {
    val input =
        participantInput(
            AyuIslandsState().apply { ignorePluginSyntaxColorsEnabled = true },
            SyntaxIntensityBaseState().apply {
                selectedPreset = "CUSTOM"
                customOverrides[SyntaxCellId("Swift", "OPERATOR").encode()] = "70"
                customStyles[SyntaxCellId("Kotlin", "KEYWORD").encode()] = "BOLD"
            },
        )
    val config = StudioSyntaxProjection.config(input.candidate.currents.global.appearance)
    private val provider = SyntaxIntensityService()
    private val nativeTargets =
        provider.prepare(config, isLicensed = true, ignorePluginSyntaxColorsEnabled = true).changes.map { change ->
            assertIs<EditorColorsSchemeImpl>(change.scheme)
        }
    val targets =
        nativeTargets.map { source ->
            if (detached) participantScheme(source) else source
        }
    val host =
        ParticipantSchemeHost(
            if (detached) targets else EditorColorsManager.getInstance().allSchemes.toList(),
        ).apply {
            active =
                if (detached) {
                    targets.first { AyuEditorSchemeBinder.matchesVariant(it.name, AyuVariant.MIRAGE) }
                } else {
                    EditorColorsManager.getInstance().globalScheme
                }
        }
    val fonts = if (detached) SyntaxFontState() else provider.fontState
    val service: SyntaxIntensityService =
        if (!detached) {
            provider
        } else {
            mockk {
                every { fontState } returns fonts
                every { prepare(any(), any(), any()) } answers {
                    val prepared = provider.prepare(firstArg<SyntaxPresetConfig>(), secondArg(), thirdArg())
                    prepared.copy(
                        changes =
                            prepared.changes.map { change ->
                                change.copy(scheme = targets[nativeTargets.indexOf(change.scheme)])
                            },
                    )
                }
            }
        }

    fun participant(): StudioEffectParticipant = StudioSyntaxParticipant(host, service, EmptyCoroutineContext)
}

internal fun participantScheme(source: EditorColorsSchemeImpl): EditorColorsSchemeImpl =
    EditorColorsSchemeImpl(source.parentScheme).apply {
        name = source.name.removePrefix(Scheme.EDITABLE_COPY_PREFIX)
        source.directlyDefinedColors.forEach { (key, color) -> setColor(key, color) }
        source.directlyDefinedAttributes.forEach { (key, attributes) ->
            val value =
                if (attributes ===
                    AbstractColorsScheme.INHERITED_ATTRS_MARKER
                ) {
                    attributes
                } else {
                    attributes.clone()
                }
            setAttributes(TextAttributesKey.find(key), value)
        }
        metaProperties["participant.unrelated"] = "untouched"
    }

private class ParticipantEnvironment(
    private val delegate: StudioApplyEnvironment,
) : StudioApplyEnvironment by delegate {
    override suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation =
        when (val prepared = delegate.prepareAcceptance(request, plan)) {
            is StudioAcceptancePreparation.Prepared -> prepared.copy(lease = withFacts(prepared.lease))
            is StudioAcceptancePreparation.Blocked -> prepared
        }

    override suspend fun resume(journal: PendingJournal): StudioRuntimeLease = withFacts(delegate.resume(journal))

    private fun withFacts(lease: StudioRuntimeLease): StudioRuntimeLease =
        object : StudioRuntimeLease by lease {
            override val native = StudioNativeFacts(true, participantProcess)
        }
}
