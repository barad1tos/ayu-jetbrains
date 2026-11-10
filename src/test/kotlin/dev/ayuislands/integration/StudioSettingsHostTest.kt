package dev.ayuislands.integration

import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AccentControls
import dev.ayuislands.settings.AccentPanelGroup
import dev.ayuislands.settings.FontControls
import dev.ayuislands.settings.GlowControls
import dev.ayuislands.settings.GlowGroupPanel
import dev.ayuislands.settings.PluginsControls
import dev.ayuislands.settings.SyntaxControls
import dev.ayuislands.settings.VcsControls
import dev.ayuislands.settings.WorkspaceControls
import dev.ayuislands.settings.mappings.LanguageMapping
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.ProjectMapping
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontJobStatus
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioProfileChange
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileRow
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.persistence.StudioMetadataStore
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.studio.session.StudioFonts
import dev.ayuislands.studio.session.StudioIdeThemeHost
import dev.ayuislands.studio.session.StudioMappingsEditing
import dev.ayuislands.studio.session.StudioSessionIo
import dev.ayuislands.studio.session.StudioSettingsProjection
import dev.ayuislands.studio.ui.StudioProfileControls
import dev.ayuislands.studio.ui.StudioUiMetadata
import dev.ayuislands.theme.AyuEditorSchemeBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSettingsHostTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `one native picker binds seven retained sections and changes preview before Apply`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val root = fixture.open()
                val native = EditorColorsManager.getInstance().globalScheme
                val nativeSize = native.editorFontSize
                val accepted = fixture.document.currents
                val children = hostDescendants(root)
                assertEquals(1, children.filterIsInstance<StudioProfileControls>().size)
                assertEquals(StudioSettingsSection.entries.map { it.title }, fixture.tabTitles())
                for (type in listOf(
                    AccentControls::class.java,
                    FontControls::class.java,
                    GlowControls::class.java,
                    SyntaxControls::class.java,
                    VcsControls::class.java,
                    WorkspaceControls::class.java,
                    PluginsControls::class.java,
                )) {
                    assertEquals(1, children.count(type::isInstance), type.simpleName)
                }
                val glow = children.filterIsInstance<GlowGroupPanel>().single()
                val originalSample = listOf(glow.glowIntensity, glow.glowWidth, glow.glowVisible)
                val profile =
                    fixture.document.catalog
                        .filterIsInstance<ProfileDefinition.Factory>()
                        .last()
                fixture.select(profile.id)

                assertEquals(profile.id, fixture.selectedDraft().source?.id)
                assertEquals(PreviewMode.DRAFT, fixture.binding.controller.state.view.preview)
                assertNotEquals(originalSample, listOf(glow.glowIntensity, glow.glowWidth, glow.glowVisible))
                assertEquals(accepted, fixture.document.currents)
                assertSame(native, EditorColorsManager.getInstance().globalScheme)
                assertEquals(nativeSize, native.editorFontSize)
                assertTrue(fixture.apply.effects.all { it.writes == 0 })

                fixture.click("Enable glow")
                val changed = fixture.selectedDraft()
                val disclosure =
                    hostDescendants(
                        root,
                    ).filterIsInstance<TitledSeparator>().first { it.text == "Targets" }
                fixture.toggle(disclosure)
                val collapsedHeight = disclosure.parent.preferredSize.height
                fixture.select(
                    fixture.document.catalog
                        .first()
                        .id,
                )
                fixture.select(profile.id)
                assertEquals(changed.appearance, fixture.selectedDraft().appearance)
                assertSame(glow, hostDescendants(root).filterIsInstance<GlowGroupPanel>().single())
                assertEquals(collapsedHeight, disclosure.parent.preferredSize.height)
            }
        }

    @Test
    fun `Apply awaits real commit and distinguishes restored failure from accepted cleanup`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                fixture.open()
                fixture.click("Enable glow")
                val requested = fixture.selectedDraft().appearance
                fixture.host.apply()
                fixture.readStoredDocument()
                assertEquals(requested, fixture.document.currents.global.appearance)
                assertFalse(fixture.host.isModified())
                assertIs<AcceptanceResult.Accepted>(fixture.binding.lastOutcome)
                assertNull(fixture.coordinators.single().outcome(fixture.updates.last().operationId))
                assertTrue(fixture.apply.events.indexOf("flush") < fixture.apply.events.indexOf("apply-GLOW"))
                assertTrue(fixture.publicationThreads.isNotEmpty())
                assertTrue(fixture.publicationThreads.all { it })
                assertTrue(fixture.ioThreads.isNotEmpty())
                assertTrue(fixture.ioThreads.all { !it })
                val accepted = fixture.document.currents

                fixture.click("Enable glow")
                fixture.apply.effect(EffectOwner.GLOW).failAfter = true
                assertFailsWith<ConfigurationException> { fixture.host.apply() }
                assertEquals(accepted, fixture.document.currents)
                assertTrue(fixture.host.isModified())
                fixture.apply.effect(EffectOwner.GLOW).failAfter = false
                fixture.apply.effect(EffectOwner.GLOW).failAccept = true
                assertFailsWith<ConfigurationException> { fixture.host.apply() }
                val committed = fixture.document.currents
                assertNotEquals(accepted, committed)
                assertTrue(fixture.host.isModified())
                val pending = assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.binding.lastOutcome)
                assertEquals(pending, fixture.coordinators.single().outcome(pending.operationId))
                fixture.host.cancel()
                fixture.readStoredDocument()
                assertEquals(committed, fixture.document.currents)
            }
        }

    @Test
    fun `Apply retries interrupted recovery in the same view without accepting the retained draft`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val root = fixture.open()
                val accepted = fixture.document.currents
                fixture.click("Enable glow")
                val draft = fixture.selectedDraft()
                val glow = fixture.apply.effect(EffectOwner.GLOW)
                glow.failAfter = true
                glow.failRestore = true
                val failure = assertFailsWith<ConfigurationException> { fixture.host.apply() }
                val pending = assertIs<AcceptanceResult.RecoveryRequired>(fixture.binding.lastOutcome)
                val phase = assertIs<SessionPhase.Processing>(fixture.binding.controller.state.phase)
                assertEquals(OperationStage.RECOVERY_REQUIRED, phase.stage)
                assertEquals(pending.operationId, phase.operation.id)
                assertTrue(fixture.host.isModified())
                val flushes = fixture.apply.flushes
                val updates = fixture.updates.size

                glow.failAfter = false
                glow.failRestore = false
                assertFailsWith<ConfigurationException> { fixture.host.apply() }
                fixture.readStoredDocument()

                assertIs<AcceptanceResult.NotAccepted>(fixture.binding.lastOutcome)
                assertEquals(SessionPhase.Editing, fixture.binding.controller.state.phase)
                assertSame(root, fixture.host.createComponent())
                assertEquals(accepted, fixture.document.currents)
                assertEquals(draft, fixture.selectedDraft())
                assertEquals(0, glow.value)
                assertEquals(flushes, fixture.apply.flushes)
                assertEquals(
                    setOf(pending.operationId),
                    fixture.updates
                        .drop(updates)
                        .map { it.operationId }
                        .toSet(),
                )
                assertTrue(fixture.host.isModified())
                assertTrue(failure.messageHtml.toString().contains("Apply"))
                assertTrue(failure.messageHtml.toString().contains("retry recovery"))
            }
        }

    @Test
    fun `dispose reattaches the same work while Cancel and ambiguous close retain distinct durable outcomes`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val original = fixture.open()
                fixture.click("Enable glow")
                fixture.saveDraft()
                val work = requireNotNull(fixture.binding.controller.state.record).work
                val glow = hostDescendants(original).filterIsInstance<GlowGroupPanel>().single()
                fixture.host.disposeUIResources()
                fixture.readStoredDocument()
                assertEquals(work, requireNotNull(fixture.binding.controller.state.record).work)
                assertFalse(glow.isPreviewAnimating)
                val recreated = fixture.open()
                assertTrue(recreated !== original)
                assertEquals(work, requireNotNull(fixture.binding.controller.state.record).work)
                fixture.host.cancel()
                fixture.readStoredDocument()
                assertEquals(SessionPhase.Retired, fixture.binding.controller.state.phase)
                assertEquals(
                    SessionAvailability.RETIRED,
                    fixture.document.sessions
                        .single()
                        .availability,
                )
                fixture.scheduler.fireCancelled()
                fixture.readStoredDocument()
                assertEquals(
                    SessionAvailability.RETIRED,
                    fixture.document.sessions
                        .single()
                        .availability,
                )
            }
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                fixture.open()
                fixture.select(
                    fixture.document.catalog
                        .filterIsInstance<ProfileDefinition.User>()
                        .first()
                        .id,
                )
                fixture.click("Enable glow")
                fixture.saveDraft()
                val accepted = fixture.document.currents
                fixture.binding.controller.dispatch(SessionEvent.HostClose(CloseRequest.AMBIGUOUS))
                fixture.readStoredDocument()
                assertEquals(
                    SessionAvailability.RECOVERED,
                    fixture.document.sessions
                        .single()
                        .availability,
                )
                assertEquals(accepted, fixture.document.currents)
                val menu = fixture.profileMenu()
                val userActions = setOf("Update profile", "Rename…", "Delete profile…")
                userActions.forEach { title ->
                    val action = menu.components.filterIsInstance<javax.swing.JMenuItem>().single { it.text == title }
                    assertFalse(action.isEnabled, title)
                }
            }
        }

    @Test
    fun `native catalog menu stages literal names and Review cancellation leaves hidden work untouched`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                fixture.open()
                val selected =
                    fixture.document.catalog
                        .filterIsInstance<ProfileDefinition.User>()
                        .first()
                fixture.select(selected.id)
                val acceptedCatalog = fixture.document.catalog
                fixture.names.add("<html>Особистий профіль</html>")
                fixture.menu("Create profile…")
                assertEquals(acceptedCatalog, fixture.document.catalog)
                assertEquals(StudioProfileChoice.Profile(selected.id), fixture.pickerSelection().choice)
                val staged = fixture.pickerRows().single { StudioProfileChange.CREATE in it.pending }
                assertEquals("<html>Особистий профіль</html>", staged.label.name)
                fixture.names.add("Renamed locally")
                fixture.menu("Rename…")
                assertEquals(
                    selected.name,
                    fixture.document.catalog
                        .single { it.id == selected.id }
                        .name,
                )
                assertEquals("Renamed locally", fixture.pickerSelection().label.pendingName)
                fixture.click("Enable glow")
                fixture.select(
                    fixture.document.catalog
                        .filterIsInstance<ProfileDefinition.Factory>()
                        .last()
                        .id,
                )
                val work = requireNotNull(fixture.binding.controller.state.record).work
                fixture.review = { dialog ->
                    showReviewControls(dialog) { shown -> reviewButton(shown, "Cancel").doClick() }
                }
                assertFailsWith<ConfigurationException> { fixture.host.apply() }
                assertEquals(work, requireNotNull(fixture.binding.controller.state.record).work)
                assertEquals(acceptedCatalog, fixture.document.catalog)
                assertTrue(fixture.apply.effects.all { it.writes == 0 })
                assertEquals(1, fixture.reviewCount)
            }
        }

    @Test
    fun `explicit font completion after Cancel records inventory without applying the retired draft`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                fixture.fontAvailability = FontAvailability.MISSING_CURATED
                fixture.open()
                val native = EditorColorsManager.getInstance().globalScheme
                val originalFamily = native.editorFontName
                val originalSize = native.editorFontSize
                val accepted = fixture.document.currents
                fixture.click("Apply font preset")
                fixture.click("Install automatically")
                val job = fixture.resources.single()
                fixture.host.cancel()
                fixture.host.disposeUIResources()
                fixture.readStoredDocument()
                fixture.completeResource(job, FontJobStatus.INSTALLED)
                assertEquals(SessionPhase.Retired, fixture.binding.controller.state.phase)
                assertEquals(accepted, fixture.document.currents)
                assertTrue(fixture.installedFonts().contains(job.fontId))
                assertSame(native, EditorColorsManager.getInstance().globalScheme)
                assertEquals(originalFamily, native.editorFontName)
                assertEquals(originalSize, native.editorFontSize)
                assertTrue(fixture.apply.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `capture native settings panes for release documentation`() {
        val images = linkedMapOf<String, BufferedImage>()
        onHostEdt {
            val theme = StudioIdeThemeHost()
            val original = theme.observe()
            val previous = requireNotNull(original.theme)
            try {
                theme.switchTo(theme.installed().single { it.name == "Ayu Mirage" })
                val scheme = requireNotNull(theme.scheme(AyuEditorSchemeBinder.targetSchemeName(AyuVariant.MIRAGE)))
                theme.selectScheme(scheme)
                StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                    captureSections(fixture, images)
                }
                StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                    expandCaptureGroup(fixture, AccentPanelGroup.OVERRIDES)
                    fixture.open()
                    selectCaptureProfile(fixture)
                    addCaptureMappings(fixture)
                    images["feature-project-overrides.png"] =
                        capturePane(fixture.root, StudioSettingsSection.ACCENT, sectionOnly = true)
                    fixture.click("Languages")
                    images["feature-language-overrides.png"] =
                        capturePane(fixture.root, StudioSettingsSection.ACCENT, sectionOnly = true)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                }
                StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                    expandCaptureGroup(fixture, AccentPanelGroup.ELEMENTS)
                    fixture.open()
                    selectCaptureProfile(fixture)
                    images["feature-accent-elements.png"] =
                        capturePane(fixture.root, StudioSettingsSection.ACCENT, sectionOnly = true)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                }
            } finally {
                theme.switchTo(previous, original.lookAndFeel)
                theme.selectScheme(original.scheme)
            }
            assertSame(original.scheme, theme.observe().scheme)
        }
        val output = Path.of("build", "reports", "ayu-settings-preview")
        Files.createDirectories(output)
        assertEquals(14, images.size)
        for ((name, image) in images) {
            assertTrue(ImageIO.write(image, "png", output.resolve(name).toFile()), name)
        }
    }

    @Test
    fun `measure fifty native transitions and one hundred host open close cycles`() {
        val directory = temporary.newFolder().toPath()
        lateinit var fixture: StudioHostFixture
        var originalEditors: Set<Editor> = emptySet()
        onHostEdt {
            fixture = StudioHostFixture(directory)
            originalEditors = EditorFactory.getInstance().allEditors.toSet()
            fixture.open()
        }
        try {
            val timings = measureTransitions(fixture)
            onHostEdt {
                fixture.saveDraft()
                val glow = hostDescendants(fixture.root).filterIsInstance<GlowGroupPanel>().single()
                fixture.host.disposeUIResources()
                assertFalse(glow.isPreviewAnimating)
            }
            val cycles = measureHostCycles(fixture, originalEditors)
            onHostEdt {
                assertTrue(fixture.ioThreads.isNotEmpty())
                assertTrue(fixture.ioThreads.all { !it })
                assertTrue(fixture.resources.isEmpty())
                assertTrue(fixture.apply.effects.all { it.writes == 0 })
            }
            val output = Path.of("build", "reports", "ayu-settings-performance")
            Files.createDirectories(output)
            Files.writeString(output.resolve("native-host.txt"), performanceReport(timings, cycles))
        } finally {
            onHostEdt { fixture.close() }
        }
    }

    private fun measureTransitions(fixture: StudioHostFixture): Map<String, List<Long>> {
        val profiles = mutableListOf<Long>()
        val sections = mutableListOf<Long>()
        val validation = mutableListOf<Long>()
        val factories = fixture.document.catalog.filterIsInstance<ProfileDefinition.Factory>()
        repeat(25) { index ->
            onHostEdt {
                val target = factories[index % factories.size].id
                val row = fixture.pickerRows().single { it.choice == StudioProfileChoice.Profile(target) }
                val picker =
                    hostDescendants(fixture.root).filterIsInstance<JComboBox<*>>().single {
                        it.itemCount > 0 && it.getItemAt(0) is StudioProfileRow
                    }
                profiles.add(measureNanoTime { picker.selectedItem = row })
                assertEquals(target, fixture.selectedDraft().source?.id)
                val window = requireNotNull(SwingUtilities.getWindowAncestor(fixture.root))
                validation.add(measureNanoTime { window.validate() })
            }
            onHostEdt {
                val tabs = hostDescendants(fixture.root).filterIsInstance<JTabbedPane>().single()
                val next = (tabs.selectedIndex + 1) % tabs.tabCount
                sections.add(measureNanoTime { tabs.selectedIndex = next })
                assertEquals(next, tabs.selectedIndex)
                val window = requireNotNull(SwingUtilities.getWindowAncestor(fixture.root))
                validation.add(measureNanoTime { window.validate() })
            }
        }
        return linkedMapOf("profile" to profiles, "section" to sections, "separate-layout" to validation)
    }

    private fun measureHostCycles(
        fixture: StudioHostFixture,
        originalEditors: Set<Editor>,
    ): List<String> {
        val observations = mutableListOf<String>()
        val retired = mutableListOf<GlowGroupPanel>()
        var initialListeners: List<Int>? = null
        repeat(100) { cycle ->
            var opened = ""
            onHostEdt {
                val root = fixture.open()
                val children = hostDescendants(root)
                val glow = children.filterIsInstance<GlowGroupPanel>().single()
                val tabs = children.filterIsInstance<JTabbedPane>().single()
                val picker =
                    children.filterIsInstance<JComboBox<*>>().single {
                        it.itemCount > 0 && it.getItemAt(0) is StudioProfileRow
                    }
                val listeners =
                    listOf(
                        picker.actionListeners.size,
                        picker.itemListeners.size,
                        tabs.changeListeners.size,
                        glow.hierarchyListeners.size,
                    )
                initialListeners?.let { assertEquals(it, listeners) }
                initialListeners = listeners
                opened = "editors=${EditorFactory.getInstance().allEditors.size}; " +
                    "glowAnimating=${glow.isPreviewAnimating}; swingListeners=$listeners"
                retired.add(glow)
                fixture.host.disposeUIResources()
                assertFalse(glow.isPreviewAnimating)
            }
            onHostEdt {
                val editors = EditorFactory.getInstance().allEditors.toSet()
                assertEquals(originalEditors, editors)
                assertTrue(retired.none { it.isPreviewAnimating })
                observations.add("cycle=${cycle + 1}; $opened; closedEditors=${editors.size}; retiredActiveTimers=0")
            }
        }
        return observations
    }

    private fun performanceReport(
        timings: Map<String, List<Long>>,
        cycles: List<String>,
    ): String =
        buildString {
            appendLine("Native Studio host performance measurement")
            appendLine("Transitions: 25 profile + 25 section = 50; open/close cycles: ${cycles.size}")
            appendLine(
                "Handlers: synchronous native setters on EDT, including any rendering inside their callbacks.",
            )
            appendLine("Initial construction and explicit layout are separate; asynchronous repaint is not timed.")
            appendLine(
                "No measured sample discarded. Historical baseline and comparative threshold: unavailable.",
            )
            appendLine(
                "Listener census: picker actions/items, tabs changes, Glow hierarchy; no global bus/heap census.",
            )
            appendLine("Timer census: existing Glow isPreviewAnimating; hidden tick count is not instrumented.")
            appendLine(
                "IO evidence: existing writer/flush thread observations; no font resource request or native apply.",
            )
            appendLine("ManualSessionScheduler is controlled test storage, not a native timer census.")
            for ((handler, samples) in timings) {
                val ordered = samples.sorted()
                val percentile = ordered[(ordered.size * 95 + 99) / 100 - 1]
                appendLine("$handler count=${samples.size} p95_ns=$percentile raw_ns=${samples.joinToString(",")}")
            }
            cycles.forEach(::appendLine)
        }

    private fun captureSections(
        fixture: StudioHostFixture,
        images: MutableMap<String, BufferedImage>,
    ) {
        val root = fixture.open()
        val accepted = fixture.document.currents
        selectCaptureProfile(fixture)
        prepareCaptureControls(fixture)
        val titles = setOf("Customize", "Targets")
        for (separator in hostDescendants(root).filterIsInstance<TitledSeparator>().filter { it.text in titles }) {
            fixture.toggle(separator)
        }
        for (section in StudioSettingsSection.entries) {
            images["settings-${section.name.lowercase()}.png"] = capturePane(root, section)
        }
        val features =
            mapOf(
                "glow" to StudioSettingsSection.GLOW,
                "font" to StudioSettingsSection.FONT,
                "workspace" to StudioSettingsSection.WORKSPACE,
                "integrations" to StudioSettingsSection.PLUGINS,
            )
        for ((name, section) in features) {
            images["feature-$name.png"] = capturePane(root, section, sectionOnly = true)
        }
        assertEquals(accepted, fixture.document.currents)
        assertTrue(fixture.apply.effects.all { it.writes == 0 })
    }

    private fun prepareCaptureControls(fixture: StudioHostFixture) {
        val resources = fixture.document.auxiliary.resources
        val facts = hostModal { StudioFonts.capture(resources) }
        fixture.installedFontFamilies = facts.families
        fixture.fontAvailability =
            StudioFonts.availability(StudioFontProjection.request(fixture.selectedDraft().appearance), resources, facts)
        fixture.host.refresh()
        val tabs = hostDescendants(fixture.root).filterIsInstance<JTabbedPane>().single()
        tabs.selectedIndex = StudioSettingsSection.GLOW.ordinal
        val glow = hostDescendants(fixture.root).filterIsInstance<GlowControls>().single()
        val custom = hostDescendants(glow).filterIsInstance<ActionButton>().single { it.presentation.text == "Custom" }
        custom.update()
        assertTrue(custom.isEnabled)
        assertTrue(custom.isShowing)
        custom.click()
    }

    private fun selectCaptureProfile(fixture: StudioHostFixture) {
        val profile =
            fixture.document.catalog.filterIsInstance<ProfileDefinition.Factory>().single {
                it.name == "Ambient"
            }
        fixture.select(profile.id)
    }

    private fun expandCaptureGroup(
        fixture: StudioHostFixture,
        group: AccentPanelGroup,
        ioContext: CoroutineContext = Dispatchers.IO,
    ) {
        val name = StudioUiMetadata.accentFields.getValue(group)
        val field =
            fixture.document.auxiliary.ui.records
                .single { it.key.name == name }
        val command =
            StudioMetadataCommand.UiChoice(FieldAddress(field.key), FieldValue.Present(RawValue.BooleanValue(true)))
        val session =
            object : StudioSessionIo {
                override suspend fun publish(update: StudioApplyUpdate) = fixture.apply.publish(update)

                override suspend fun flush(record: StudioSessionRecord) = fixture.apply.flush(record)

                override suspend fun observe(
                    request: AcceptanceRequest,
                    document: StudioDocument,
                ) = fixture.apply.observe(request, document)
            }
        val result =
            hostModal {
                withContext(ioContext) {
                    StudioMetadataStore(fixture.apply.store, session, fixture.apply.mutex)
                        .submit(command, "release-capture-${group.name.lowercase()}")
                }
            }
        assertIs<AcceptanceResult.Accepted>(result)
        fixture.readStoredDocument()
    }

    private fun addCaptureMappings(fixture: StudioHostFixture) {
        val examples =
            listOf(
                MappingsEdit.AddProject(ProjectMapping("/projects/ayu-islands", "Ayu Islands", "#FFCD66")),
                MappingsEdit.AddProject(ProjectMapping("/projects/harbor", "Harbor", "#5CCFE6")),
                MappingsEdit.AddLanguage(LanguageMapping("kotlin", "Kotlin", "#C2D94C")),
                MappingsEdit.AddLanguage(LanguageMapping("python", "Python", "#FFD580")),
            )
        for (edit in examples) {
            val work = requireNotNull(fixture.binding.controller.state.record).work
            val shared = StudioSettingsProjection.shared(fixture.document.shared, work.shared)
            StudioMappingsEditing.edits(shared.mappings, edit).forEach { intent ->
                fixture.binding.controller.dispatch(SessionEvent.Intent(intent))
            }
        }
    }

    private fun capturePane(
        root: JComponent,
        section: StudioSettingsSection,
        sectionOnly: Boolean = false,
    ): BufferedImage {
        val tabs = hostDescendants(root).filterIsInstance<JTabbedPane>().single()
        tabs.selectedIndex = section.ordinal
        val window = requireNotNull(SwingUtilities.getWindowAncestor(root))
        window.setSize(1_040, 900)
        window.validate()
        val viewport = assertIs<JScrollPane>(tabs.selectedComponent).viewport
        val height = window.height - viewport.height + viewport.view.preferredSize.height
        window.setSize(1_040, maxOf(640, height))
        window.validate()
        val component = if (sectionOnly) viewport.view else root
        val image = BufferedImage(component.width * 2, component.height * 2, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(2.0, 2.0)
            component.printAll(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }
}
