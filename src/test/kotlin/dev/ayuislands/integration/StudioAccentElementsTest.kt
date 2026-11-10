package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.elements.BracketFadeManager
import dev.ayuislands.accent.elements.BracketFadeState
import dev.ayuislands.accent.elements.BracketMatchElement
import dev.ayuislands.accent.elements.CaretRowElement
import dev.ayuislands.accent.elements.InlayHintsElement
import dev.ayuislands.accent.elements.LinksElement
import dev.ayuislands.accent.elements.MatchingTagElement
import dev.ayuislands.accent.elements.ProgressBarElement
import dev.ayuislands.accent.elements.ScrollbarElement
import dev.ayuislands.accent.elements.SearchResultsElement
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.font.FontData
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AccentElementSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioAccentElements
import dev.ayuislands.studio.session.StudioAccentReferences
import dev.ayuislands.studio.session.StudioAccentWrite
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioEffectParticipant
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioSchemeIdentityReceipt
import dev.ayuislands.studio.session.StudioSchemeOperation
import dev.ayuislands.studio.session.StudioTargets
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.Font
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioAccentElementsTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @After
    fun clearElementOwnership() {
        SwingUtilities.invokeAndWait {
            BracketFadeManager.deactivate()
            EditorSchemeOverrides.reset()
        }
    }

    @Test
    fun `eight elements prepare without writes and apply actual keys before outer acceptance`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                ElementSurfaces().use { fixture ->
                    val before = ParticipantSnapshot(fixture.scheme)
                    val input = participantInput()
                    val document = input.candidate
                    val operation = fixture.prepare(input)
                    try {
                        before.assertUnchanged()
                        fixture.assertDefaults(Color.BLUE)
                        assertEquals(0, fixture.host.refreshes)
                        assertEquals(
                            1,
                            operation.receipts.count {
                                it.identity.field ==
                                    StudioSchemeIdentityReceipt.FIELD
                            },
                        )
                        assertEquals(
                            operation.receipts.size,
                            operation.receipts
                                .map { it.id }
                                .distinct()
                                .size,
                        )
                        assertTrue(operation.receipts.all { it.status == RestoreStatus.PENDING })

                        operation.apply()

                        fixture.assertApplied()
                        assertEquals(document, input.candidate)
                        assertEquals(1, fixture.host.identities)
                        assertTrue(operation.receipts.any { it.status == RestoreStatus.OWNED })
                    } finally {
                        settleParticipant(operation)
                    }
                    before.assertValues()
                    fixture.assertDefaults(Color.BLUE)
                    assertTrue(operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
                }
            }
        }
    }

    @Test
    fun `later owner failure restores actual element surfaces through the outer durable coordinator`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                ElementSurfaces().use { native ->
                    val before = ParticipantSnapshot(native.scheme)
                    StudioApplyFixture(temporary.newFolder().toPath()).use { fixture ->
                        val participants =
                            fixture.effects.map { effect ->
                                if (effect.owner == EffectOwner.ACCENT) native.participant() else effect
                            }
                        fixture.effect(EffectOwner.GLOW).apply {
                            beforePrepare = {
                                native.assertApplied()
                                val journal = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                                assertTrue(journal.receipts.any { it.owner == EffectOwner.ACCENT })
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

                        val result = coordinator.submit(fixture.allOwnersRequest())

                        assertIs<AcceptanceResult.NotAccepted>(result)
                        assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
                        before.assertValues()
                        native.assertDefaults(Color.BLUE)
                        assertTrue(fixture.effects.all { it.accepts == 0 })
                    }
                }
            }
        }
    }

    @Test
    fun `foreign editor and default replacements survive while independent element fields roll back`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                ElementSurfaces().use { fixture ->
                    val originalLink = fixture.scheme.getAttributes(TextAttributesKey.find("HYPERLINK_ATTRIBUTES"))
                    val operation = fixture.prepare(participantInput())
                    operation.apply()
                    fixture.scheme.setColor(ColorKey.find("HYPERLINK_COLOR"), Color.GREEN)
                    UIManager.put("Link.activeForeground", Color.GREEN)

                    settleParticipant(operation)

                    assertSame(Color.GREEN, fixture.scheme.directlyDefinedColors[ColorKey.find("HYPERLINK_COLOR")])
                    assertSame(Color.GREEN, readRawUiDefault(UIManager.getDefaults(), "Link.activeForeground"))
                    assertSame(Color.BLUE, fixture.scheme.directlyDefinedColors[ColorKey.find("CARET_COLOR")])
                    assertSame(Color.BLUE, readRawUiDefault(UIManager.getDefaults(), "ProgressBar.foreground"))
                    assertEquals(
                        originalLink,
                        fixture.scheme.getAttributes(TextAttributesKey.find("HYPERLINK_ATTRIBUTES")),
                    )
                    assertTrue(operation.receipts.any { it.status == RestoreStatus.FOREIGN })
                    assertTrue(operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
                }
            }
        }
    }

    @Test
    fun `accepted disable and reenable preserve granular choices and user editor baseline`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                val input = participantInput(AyuIslandsState().apply { glowTabMode = "FUTURE_TAB" })
                ElementSurfaces().use { legacy ->
                    legacy.prepare(input).also {
                        it.apply()
                        it.accept()
                    }
                    legacy.restoreLegacy()
                    assertDisableMatchesLegacy(input, legacy)
                }
            }
        }
    }

    @Test
    fun `inactive cleanup preserves remembered choices through prepare abort and acceptance`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                ElementSurfaces().use { fixture ->
                    val input = participantInput()
                    val settings = fixture.settings(input)
                    val syntaxKey = TextAttributesKey.find("DEFAULT_KEYWORD")
                    val syntax = TextAttributes(Color.MAGENTA, null, null, null, Font.ITALIC)
                    val (legacyColors, legacyAttributes) =
                        ElementSurfaces().use { legacy ->
                            EditorSchemeOverrides.writeAttributes(
                                legacy.scheme,
                                EditorSchemeOwner.Syntax,
                                syntaxKey,
                                syntax,
                            )
                            legacy.prepare(input).also {
                                it.apply()
                                it.accept()
                            }
                            legacy.restoreLegacy()
                            legacy.scheme.directlyDefinedColors.toMap() to
                                legacy.scheme.directlyDefinedAttributes.mapValues { it.value.clone() }
                        }
                    EditorSchemeOverrides.writeAttributes(fixture.scheme, EditorSchemeOwner.Syntax, syntaxKey, syntax)
                    val metadata = fixture.scheme.metaProperties.toMap()
                    val editorFont = FontData.captureExact(fixture.scheme.fontPreferences)
                    val consoleFont = FontData.captureExact(fixture.scheme.consoleFontPreferences)
                    fixture.prepare(input).also {
                        it.apply()
                        it.accept()
                    }
                    EditorSchemeOverrides.observeElementEnabled(
                        AccentElementId.CARET_ROW,
                        isEnabled = false,
                    ) { fixture.host.schemes }
                    val remembered = fixture.enabledMemory()
                    val accepted = ParticipantSnapshot(fixture.scheme)
                    val defaults = fixture.keys.associateWith { readRawUiDefault(UIManager.getDefaults(), it) }
                    val bracket = bracketState()
                    assertNotNull(bracket.parent)
                    val inactive = fixture.prepare(input, context = null)

                    try {
                        accepted.assertUnchanged()
                        defaults.forEach { (key, value) ->
                            assertSame(value, readRawUiDefault(UIManager.getDefaults(), key))
                        }
                        assertEquals(bracket, bracketState())
                        assertEquals(remembered, fixture.enabledMemory())
                        inactive.apply()
                        assertEquals(legacyColors, fixture.scheme.directlyDefinedColors)
                        assertEquals(legacyAttributes, fixture.scheme.directlyDefinedAttributes)
                        fixture.keys.forEach { assertNull(readRawUiDefault(UIManager.getDefaults(), it), it) }
                        assertEquals(remembered, fixture.enabledMemory())
                        assertEquals(editorFont, FontData.captureExact(fixture.scheme.fontPreferences))
                        assertEquals(consoleFont, FontData.captureExact(fixture.scheme.consoleFontPreferences))
                        metadata.forEach { (key, value) -> assertEquals(value, fixture.scheme.metaProperties[key]) }
                    } finally {
                        settleParticipant(inactive)
                    }

                    accepted.assertValues()
                    defaults.forEach { (key, value) ->
                        assertSame(value, readRawUiDefault(UIManager.getDefaults(), key))
                    }
                    assertEquals(bracket, bracketState())
                    assertEquals(remembered, fixture.enabledMemory())
                    assertEquals(settings, fixture.settings(input))

                    fixture.prepare(input, context = null).also {
                        it.apply()
                        it.accept()
                    }
                    assertEquals(legacyColors, fixture.scheme.directlyDefinedColors)
                    assertEquals(legacyAttributes, fixture.scheme.directlyDefinedAttributes)
                    assertNull(bracketState().parent)
                    assertNull(bracketState().color)
                    assertNull(bracketState().appearance)
                    assertEquals(remembered, fixture.enabledMemory())
                    assertEquals(settings, fixture.settings(input))
                    assertEquals(editorFont, FontData.captureExact(fixture.scheme.fontPreferences))
                    assertEquals(consoleFont, FontData.captureExact(fixture.scheme.consoleFontPreferences))
                    metadata.forEach { (key, value) -> assertEquals(value, fixture.scheme.metaProperties[key]) }
                }
            }
        }
    }

    @Test
    fun `inactive cleanup reaches every observed scheme and preserves foreign and Syntax fields`() {
        SwingUtilities.invokeAndWait {
            runBlocking {
                ElementSurfaces().use { fixture ->
                    val input = participantInput()
                    fixture.prepare(input).also {
                        it.apply()
                        it.accept()
                    }
                    val named = participantScheme(fixture.scheme).apply { name = "Other user scheme" }
                    val progressKey = ColorKey.find("PROGRESS_BAR_TRACK")
                    named.setColor(progressKey, Color.BLUE)
                    EditorSchemeOverrides.writeColor(
                        named,
                        EditorSchemeOwner.Element(AccentElementId.PROGRESS_BAR),
                        progressKey,
                        Color.RED,
                    )
                    val syntaxKey = TextAttributesKey.find("DEFAULT_KEYWORD")
                    val syntax = TextAttributes(Color.MAGENTA, null, null, null, Font.ITALIC)
                    EditorSchemeOverrides.writeAttributes(named, EditorSchemeOwner.Syntax, syntaxKey, syntax)
                    val font = FontData.captureExact(named.fontPreferences)
                    fixture.scheme.setColor(ColorKey.find("HYPERLINK_COLOR"), Color.GREEN)
                    val observedHost = ParticipantSchemeHost(listOf(fixture.scheme, named))
                    val inactive = fixture.prepare(input, context = null, observedHost = observedHost)
                    assertSame(Color.RED, named.getColor(progressKey))

                    inactive.apply()
                    inactive.accept()

                    assertSame(Color.BLUE, named.getColor(progressKey))
                    assertSame(Color.BLUE, fixture.scheme.getColor(ColorKey.find("CARET_COLOR")))
                    assertSame(Color.GREEN, fixture.scheme.getColor(ColorKey.find("HYPERLINK_COLOR")))
                    assertEquals(syntax, named.getAttributes(syntaxKey))
                    assertEquals(font, FontData.captureExact(named.fontPreferences))
                    fixture.keys.forEach { assertNull(readRawUiDefault(UIManager.getDefaults(), it), it) }
                }
            }
        }
    }

    private fun bracketState(): BracketFadeState {
        val checkpoint = BracketFadeManager.prepare(null)
        val state = checkpoint.before
        checkpoint.restore()
        return state
    }

    private suspend fun assertDisableMatchesLegacy(
        input: StudioEffectInput,
        legacy: ElementSurfaces,
    ) {
        ElementSurfaces().use { fixture ->
            val explicitColors = fixture.scheme.directlyDefinedColors.toMap()
            val explicitAttributes = fixture.scheme.directlyDefinedAttributes.mapValues { it.value.clone() }
            val selected = fixture.settings(input)
            fixture.prepare(input).also {
                it.apply()
                it.accept()
            }
            val disabled = selected.copy(toggles = selected.toggles.mapValues { false })
            val operation = fixture.prepare(input, disabled)
            operation.apply()
            operation.accept()

            assertEquals(legacy.scheme.directlyDefinedColors, fixture.scheme.directlyDefinedColors)
            assertEquals(legacy.scheme.directlyDefinedAttributes, fixture.scheme.directlyDefinedAttributes)
            explicitColors.forEach { (key, value) ->
                assertEquals(value, fixture.scheme.directlyDefinedColors[key], key.externalName)
            }
            explicitAttributes.forEach { (key, value) ->
                assertEquals(value, fixture.scheme.directlyDefinedAttributes[key], key)
            }
            fixture.keys.forEach { assertNull(readRawUiDefault(UIManager.getDefaults(), it), it) }
            assertEquals("FUTURE_TAB", fixture.settings(input).tabMode)
            assertEquals(selected, fixture.settings(input))
            val acceptedDisabled = ParticipantSnapshot(fixture.scheme)

            val reenabled = fixture.prepare(input, selected)
            try {
                reenabled.apply()
                fixture.assertApplied()
            } finally {
                settleParticipant(reenabled)
            }
            acceptedDisabled.assertCells()
            fixture.keys.forEach { assertNull(readRawUiDefault(UIManager.getDefaults(), it), it) }
            assertEquals(selected, fixture.settings(input))
        }
    }
}

private class ElementSurfaces : AutoCloseable {
    private val progress = ProgressBarElement()
    private val scrollbar = ScrollbarElement()
    private val links = LinksElement()
    private val search = SearchResultsElement()
    private val elements: List<AccentElement> =
        listOf(
            InlayHintsElement(),
            CaretRowElement(),
            progress,
            scrollbar,
            links,
            BracketMatchElement(),
            search,
            MatchingTagElement(),
        )
    val keys =
        buildList {
            addAll(progress.uiKeys)
            addAll(scrollbar.uiKeys)
            addAll(links.uiKeys)
            addAll(search.uiKeys)
        }
    private val backgrounds = listOf("List.background", "Tree.background", "Table.background", "Panel.background")
    private val previous = (keys + backgrounds).associateWith { readRawUiDefault(UIManager.getDefaults(), it) }
    val scheme: EditorColorsSchemeImpl =
        participantScheme(
            assertIs<EditorColorsSchemeImpl>(
                EditorColorsManager.getInstance().getScheme(AyuVariant.MIRAGE.editorSchemeResource),
            ),
        )
    val host = ParticipantSchemeHost(listOf(scheme))

    init {
        keys.forEach { UIManager.put(it, Color.BLUE) }
        backgrounds.forEach { UIManager.put(it, BACKGROUND) }
        scheme.setColor(ColorKey.find("CARET_COLOR"), Color.BLUE)
        scheme.setColor(ColorKey.find("HYPERLINK_COLOR"), Color.BLUE)
        scheme.setColor(ColorKey.find("PROGRESS_BAR_TRACK"), Color.BLUE)
        scheme.setColor(ColorKey.find("ScrollBar.thumbColor"), Color.BLUE)
        listOf("INLAY_TEXT_WITHOUT_BACKGROUND", "HYPERLINK_ATTRIBUTES", "MATCHED_BRACE_ATTRIBUTES", "MATCHED_TAG_NAME")
            .forEach { key ->
                scheme.setAttributes(
                    TextAttributesKey.find(key),
                    TextAttributes(Color.BLUE, BACKGROUND, Color.CYAN, EffectType.WAVE_UNDERSCORE, Font.ITALIC),
                )
            }
        scheme.setAttributes(
            TextAttributesKey.find("TEXT"),
            TextAttributes(Color.WHITE, BACKGROUND, null, null, Font.PLAIN),
        )
    }

    fun settings(input: StudioEffectInput): AccentElementSettings =
        StudioAccentEditing
            .settings(
                assertNotNull(StudioTargets.current(input.candidate, input.target)).appearance,
                input.candidate.shared,
            ).elements

    fun prepare(
        input: StudioEffectInput,
        settings: AccentElementSettings = settings(input),
        runtime: StudioEffectRuntime = participantRuntime(),
        context: AccentContext? = AccentContext.Ayu(AyuVariant.MIRAGE),
        observedHost: ParticipantSchemeHost = host,
    ): StudioEffectOperation {
        val schemes =
            StudioSchemeOperation.prepare(
                observedHost,
                EmptyCoroutineContext,
                EffectOwner.ACCENT,
                input,
                runtime.native,
            )
        val references =
            StudioAccentReferences(input.candidate.header.transactionId, assertNotNull(runtime.native.currentProcess))
        val surface =
            StudioAccentElements(references, schemes, elements).capture(
                settings,
                context,
                ACCENT.takeIf { context != null },
                observedHost.observe(),
                if (runtime.native.isLicensed) LicenseEntitlement.LICENSED else LicenseEntitlement.UNLICENSED,
            )
        return ElementOperation(surface, schemes)
    }

    fun enabledMemory(): Map<AccentElementId, Boolean?> =
        elements.associate { element ->
            val checkpoint = EditorSchemeOverrides.checkpoints.prepareEnabled(element.id, enabled = false)
            val before = checkpoint.before
            checkpoint.restore()
            element.id to before
        }

    fun participant(): StudioEffectParticipant =
        object : StudioEffectParticipant {
            override val owner = EffectOwner.ACCENT

            override suspend fun prepare(
                input: StudioEffectInput,
                runtime: StudioEffectRuntime,
            ): StudioEffectOperation = this@ElementSurfaces.prepare(input, runtime = runtime)

            override suspend fun recover(
                input: StudioEffectInput,
                receipts: StudioList<PortableReceipt>,
                runtime: StudioEffectRuntime,
            ): StudioEffectOperation = error("This failure is restored using the retained native operation")
        }

    fun restoreLegacy() {
        elements.forEach { EditorSchemeOverrides.restore(scheme, EditorSchemeOwner.Element(it.id)) }
    }

    fun assertDefaults(expected: Color) {
        keys.forEach { assertSame(expected, readRawUiDefault(UIManager.getDefaults(), it), it) }
    }

    fun assertApplied() {
        assertEquals(
            140,
            scheme.getAttributes(TextAttributesKey.find("INLAY_TEXT_WITHOUT_BACKGROUND")).foregroundColor.alpha,
        )
        assertEquals(ACCENT.rgb, scheme.getColor(ColorKey.find("CARET_COLOR"))?.rgb)
        assertEquals(0x1A, scheme.getColor(ColorKey.find("CARET_ROW_COLOR"))?.alpha)
        assertEquals(ACCENT.rgb, scheme.getColor(ColorKey.find("PROGRESS_BAR_TRACK"))?.rgb)
        assertEquals(ACCENT.rgb, UIManager.getColor("ProgressBar.foreground").rgb)
        assertEquals(0x59, scheme.getColor(ColorKey.find("ScrollBar.thumbColor"))?.alpha)
        assertEquals(0x8C, UIManager.getColor("ScrollBar.hoverThumbColor").alpha)
        val link = scheme.getAttributes(TextAttributesKey.find("HYPERLINK_ATTRIBUTES"))
        assertEquals(ACCENT.rgb, link.foregroundColor.rgb)
        assertEquals(ACCENT.rgb, link.effectColor.rgb)
        assertEquals(BACKGROUND, link.backgroundColor)
        assertEquals(Font.ITALIC, link.fontType)
        assertEquals(Font.BOLD, scheme.getAttributes(TextAttributesKey.find("MATCHED_BRACE_ATTRIBUTES")).fontType)
        assertEquals(Color(64, 61, 56).rgb, UIManager.getColor("List.selectionBackground").rgb)
        val tag = scheme.getAttributes(TextAttributesKey.find("MATCHED_TAG_NAME"))
        assertNull(tag.foregroundColor)
        assertEquals(Color(73, 68, 58).rgb, tag.backgroundColor.rgb)
    }

    override fun close() {
        previous.forEach { (key, value) -> UIManager.put(key, value) }
    }

    private companion object {
        val ACCENT = Color(255, 204, 102)
        val BACKGROUND = Color(31, 36, 48)
    }
}

private class ElementOperation(
    private val surface: StudioAccentWrite,
    private val schemes: StudioSchemeOperation,
) : StudioEffectOperation {
    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(surface.receipts + schemes.receipts)
    override val runtimeChanges: StudioList<RuntimeFieldChange> = StudioList(emptyList())

    override suspend fun apply() {
        surface.apply()
        schemes.apply()
    }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = schemes.prepareRestore(blockedBy)

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation {
        val result = schemes.restore(blockedBy)
        if (result == StudioRestoreContinuation.NONE) surface.restore(blockedBy)
        return result
    }

    override suspend fun accept() {
        schemes.accept()
        surface.accept()
    }
}
