package dev.ayuislands.integration

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontInstallConsent
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontStatus
import dev.ayuislands.font.FontUninstaller
import dev.ayuislands.font.FontWeight
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.FontPresetPanel
import io.mockk.EqMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseEvent
import java.io.File
import javax.accessibility.AccessibleState
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontPresetPanelInteractionTest {
    @get:org.junit.Rule val applicationRule = ApplicationRule()

    private lateinit var settings: AyuIslandsSettings
    private lateinit var participant: FontPresetPanel
    private lateinit var component: JPanel
    private val applied = mutableListOf<FontSettings>()
    private val events = mutableListOf<String>()
    private val statuses = mutableMapOf<FontPreset, FontStatus>()
    private var installCompletion: ((FontInstaller.InstallResult) -> Unit)? = null
    private var deleteCompletion: ((FontUninstaller.UninstallResult) -> Unit)? = null
    private val clipboard = mockk<CopyPasteManager>()

    @org.junit.Before
    fun setup() {
        settings = AyuIslandsSettings()
        settings.state.fontPresetEnabled = true
        mockkObject(AyuIslandsSettings.Companion, FontDetector, FontPresetApplicator)
        every { AyuIslandsSettings.getInstance() } returns settings
        // Host font discovery and scheme mutation are effect boundaries; the panel and its working copy
        // stay real.
        every { FontDetector.invalidateCache() } returns Unit
        every { FontDetector.detectAll() } answers
            {
                FontPreset.entries.associateWith { preset -> statuses[preset] == FontStatus.HEALTHY }
            }
        every { FontDetector.status(any()) } answers
            {
                statuses[firstArg()] ?: FontStatus.NOT_INSTALLED
            }
        every { FontDetector.listMonospaceFonts() } returns
            listOf("JetBrains Mono", "Monospaced", "Personal Mono")
        every { FontDetector.isFamilyInstalled(any()) } returns false
        every { FontDetector.resolveFamily(any()) } answers { firstArg<FontPreset>().fontFamily }
        every { FontPresetApplicator.apply(any()) } answers { applied += firstArg<FontSettings>() }
        every { FontPresetApplicator.revert() } answers { events += "revert" }
        mockkObject(
            LicenseChecker,
            AccentApplicator,
            FontInstallConsent,
            FontInstaller,
            FontUninstaller,
        )
        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { LicenseChecker.requestLicense(any()) } answers { events += "license" }
        every { AccentApplicator.resolveFocusedProject() } returns null
        // Never permit a native dialog, download, font deletion, process launch or clipboard write in
        // this fixture.
        every { FontInstallConsent.confirmInstall(any(), any(), any()) } answers
            {
                events += "consent:${firstArg<FontCatalog.Entry>().preset}"
                null
            }
        every { FontInstallConsent.confirmUninstall(any(), any(), any()) } answers
            {
                events += "delete-consent:${firstArg<FontCatalog.Entry>().preset}"
                false
            }
        every { FontInstaller.platformFontDir() } returns File("/tmp/ayu-font-panel-fixture")
        every { FontInstaller.install(any(), any(), any(), any()) } answers
            {
                events += "install:${firstArg<FontCatalog.Entry>().preset}"
                installCompletion = arg(3)
            }
        every { FontUninstaller.uninstall(any(), any(), any()) } answers
            {
                events += "delete:${firstArg<FontPreset>()}"
                deleteCompletion = arg(2)
            }
        mockkStatic(CopyPasteManager::class)
        every { CopyPasteManager.getInstance() } returns clipboard
        every { (clipboard::setContents)(any()) } returns Unit
        mockkConstructor(ProcessBuilder::class)
        every { anyConstructed<ProcessBuilder>().start() } returns mockk()
    }

    @org.junit.After
    fun cleanup() {
        SwingUtilities.invokeAndWait {}
        unmockkAll()
    }

    @org.junit.Test
    fun toggleRoundTripPreservesSparsePreferences() {
        SwingUtilities.invokeAndWait {
            val original = mapOf("UNAVAILABLE" to "opaque|future|value", "WHISPER" to "16|1.4|true|LIGHT")
            settings.state.fontPresetCustomizations.putAll(original)
            openPanel()
            expandCustomize()
            assertFalse(participant.isModified())
            assertTrue(applied.isEmpty())
            assertTrue(button("Reapply preset").isVisible)
            button("Apply font preset").doClick()
            assertFalse(button("Reapply preset").isVisible)
            assertTrue(participant.isModified())
            assertTrue(settings.state.fontPresetEnabled)
            participant.apply()
            assertFalse(settings.state.fontPresetEnabled)
            assertEquals(listOf("revert"), events)
            assertEquals(original, settings.state.fontPresetCustomizations)
            reloadSettings()
            openPanel()
            assertFalse(button("Apply font preset").isSelected)
            assertFalse(button("Reapply preset").isVisible)
            assertFalse(participant.isModified())
            button("Apply font preset").doClick()
            participant.apply()
            assertTrue(settings.state.fontPresetEnabled)
            assertEquals(original, settings.state.fontPresetCustomizations)
            assertEquals(FontPreset.AMBIENT, applied.single().preset)
        }
    }

    @org.junit.Test
    fun nativeRenameCanReapplyUnchangedPreset() {
        SwingUtilities.invokeAndWait {
            var activeScheme =
                EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme).apply {
                    name = "Personal font scheme"
                    fontPreferences = FontPreferencesImpl().apply { register("Dialog", 17.5f) }
                    consoleFontPreferences = FontPreferencesImpl().apply { register("Monospaced", 15.5f) }
                }
            val manager = mockk<EditorColorsManager>()
            every { manager.globalScheme } answers { activeScheme }
            every { manager.allSchemes } answers { arrayOf(activeScheme) }
            mockkStatic(EditorColorsManager::class, Notifications.Bus::class)
            every { EditorColorsManager.getInstance() } returns manager
            every { Notifications.Bus.notify(any<Notification>(), isNull<Project>()) } returns Unit
            every { FontPresetApplicator.apply(any()) } answers { callOriginal() }
            every { FontPresetApplicator.revert() } answers { callOriginal() }
            settings.state.fontPresetName = FontPreset.AMBIENT.name
            settings.state.fontApplyToConsole = true
            val customizations = mapOf("AMBIENT" to "18.00|1.40|true|REGULAR", "FUTURE" to "opaque|preserved")
            settings.state.fontPresetCustomizations.putAll(customizations)
            FontPresetApplicator.apply(
                FontSettings.decode(customizations["AMBIENT"], FontPreset.AMBIENT).copy(applyToConsole = true),
            )
            val backup = settings.state.fontOwnershipSnapshots.toMap()
            assertTrue(
                backup.isNotEmpty(),
                "Initial native Apply must establish ownership; recorded panel calls=${applied.size}",
            )
            assertEquals(FontPreset.AMBIENT.fontFamily, activeScheme.editorFontName)
            assertNotNull(activeScheme.metaProperties.getProperty("dev.ayuislands.fontOwnershipId"))
            activeScheme = (activeScheme.clone() as EditorColorsSchemeImpl).apply { name = "Renamed font scheme" }
            assertNull(activeScheme.metaProperties.getProperty("dev.ayuislands.fontOwnershipId"))
            val editor = FontData.capture(activeScheme.fontPreferences)
            val console = FontData.capture(activeScheme.consoleFontPreferences)
            val originalXml = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))

            openPanel()
            assertFalse(participant.isModified(), "Opening Settings must not queue font writes")
            participant.apply()
            assertEquals(originalXml, JDOMUtil.writeElement(XmlSerializer.serialize(settings.state)))
            expandCustomize()
            assertFalse(participant.isModified(), "Expanding Customize must not queue font writes")
            val reapply =
                assertNotNull(
                    descendants(component)
                        .filterIsInstance<AbstractButton>()
                        .singleOrNull { it.text == "Reapply preset" },
                    "The unchanged preset needs an explicit reapply action after native Rename",
                )
            assertTrue(generateSequence<Component>(reapply) { it.parent }.all { it.isVisible })
            reapply.doClick()
            assertTrue(participant.isModified())
            assertEquals(originalXml, JDOMUtil.writeElement(XmlSerializer.serialize(settings.state)))
            participant.reset()
            assertFalse(participant.isModified())
            participant.apply()
            assertEquals(originalXml, JDOMUtil.writeElement(XmlSerializer.serialize(settings.state)))

            reapply.doClick()
            assertTrue(participant.isModified(), "The second Reapply click must queue a new explicit request")
            participant.apply()
            assertFalse(participant.isModified())
            val freshKeys = settings.state.fontOwnershipSnapshots.keys - backup.keys
            assertTrue(
                freshKeys.isNotEmpty(),
                "Reapply must establish renamed ownership; recorded panel calls=${applied.size}",
            )
            assertEquals(customizations, settings.state.fontPresetCustomizations)
            assertTrue(settings.state.fontPresetEnabled)
            assertTrue(settings.state.fontApplyToConsole)
            assertEquals(FontPreset.AMBIENT.name, settings.state.fontPresetName)
            FontPresetApplicator.revert()
            assertEquals(editor, FontData.capture(activeScheme.fontPreferences))
            assertEquals(console, FontData.capture(activeScheme.consoleFontPreferences))
            assertEquals(backup, settings.state.fontOwnershipSnapshots)
        }
    }

    @org.junit.Test
    fun unavailablePresetSurvivesPanelLifecycle() {
        SwingUtilities.invokeAndWait {
            val unavailable = "FUTURE_PRESET"
            val original = mapOf(unavailable to "21.00|1.37|true|FUTURE_WEIGHT|Unavailable Font|extension=42")
            settings.state.fontPresetName = unavailable
            settings.state.fontPresetCustomizations.putAll(original)
            val originalXml = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))

            openPanel()

            assertEquals(originalXml, JDOMUtil.writeElement(XmlSerializer.serialize(settings.state)))
            assertTrue(selectedPresets().isEmpty(), "An unavailable preset must not select a known fallback")
            assertTrue(labels().any { unavailable in it && "unavailable" in it.lowercase() })
            assertRows(null)
            assertFalse(button("Reapply preset").isVisible)
            assertFalse(participant.isModified())
            selectPreset(FontPreset.WHISPER)
            participant.reset()
            assertTrue(selectedPresets().isEmpty())
            participant.apply()
            assertEquals(originalXml, JDOMUtil.writeElement(XmlSerializer.serialize(settings.state)))
            assertTrue(applied.isEmpty())

            button("Apply font preset").doClick()
            participant.apply()
            reloadSettings()
            openPanel()
            assertFalse(settings.state.fontPresetEnabled)
            assertEquals(unavailable, settings.state.fontPresetName)
            button("Apply font preset").doClick()
            participant.apply()
            reloadSettings()
            openPanel()
            assertTrue(settings.state.fontPresetEnabled)
            assertEquals(unavailable, settings.state.fontPresetName)
            assertEquals(original, settings.state.fontPresetCustomizations)
            assertTrue(selectedPresets().isEmpty())
            assertTrue(applied.isEmpty())

            selectPreset(FontPreset.WHISPER)
            participant.apply()
            assertEquals("WHISPER", settings.state.fontPresetName)
            assertEquals(listOf("Whisper"), selectedPresets())
            assertEquals(original, settings.state.fontPresetCustomizations)
            assertEquals(FontPreset.WHISPER, applied.single().preset)
        }
    }

    @org.junit.Test
    fun unavailablePresetRejectsStaleTypographyEdits() {
        SwingUtilities.invokeAndWait {
            val unavailable = "FUTURE_PRESET"
            val encoded = "opaque|future|customization"
            settings.state.fontPresetName = unavailable
            settings.state.fontPresetCustomizations[unavailable] = encoded
            openPanel()

            size().value = 22
            spacing().value = 1.8
            weight().selectedItem = FontWeight.MEDIUM
            button("Ligatures").doClick()
            button("Install automatically").doClick()
            button("Reapply preset").doClick()
            participant.apply()

            assertEquals(unavailable, settings.state.fontPresetName)
            assertEquals(mapOf(unavailable to encoded), settings.state.fontPresetCustomizations)
            assertFalse(participant.isModified())
            assertTrue(applied.isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    @org.junit.Test
    fun typographyStaysPendingUntilApply() {
        SwingUtilities.invokeAndWait {
            val original = mapOf("WHISPER" to "16|1.4|true|LIGHT", "FUTURE" to "opaque")
            settings.state.fontPresetCustomizations.putAll(original)
            openPanel()
            size().value = 19
            spacing().value = 1.6
            weight().selectedItem = FontWeight.MEDIUM
            button("Ligatures").doClick()
            button("Also apply to console").doClick()
            assertTrue(participant.isModified())
            assertEquals(original, settings.state.fontPresetCustomizations)
            assertFalse(settings.state.fontApplyToConsole)
            assertTrue(applied.isEmpty())
            assertTrue(labels().any { "19pt" in it && "no ligatures" in it })
            participant.apply()
            val expected =
                FontSettings
                    .fromPreset(FontPreset.AMBIENT)
                    .copy(
                        fontSize = 19f,
                        lineSpacing = 1.6f,
                        weight = FontWeight.MEDIUM,
                        enableLigatures = false,
                        applyToConsole = true,
                    )
            assertEquals(listOf(expected), applied)
            assertEquals(
                original + ("AMBIENT" to "19.0|1.6|false|MEDIUM"),
                settings.state.fontPresetCustomizations,
            )
            assertTrue(settings.state.fontApplyToConsole)
            assertFalse(participant.isModified())
            participant.apply()
            assertEquals(1, applied.size)
            reloadSettings()
            openPanel()
            assertEquals(19, size().value)
            assertEquals(1.6f.toDouble(), spacing().value)
            assertEquals(FontWeight.MEDIUM, weight().selectedItem)
            assertFalse(button("Ligatures").isSelected)
            assertTrue(button("Also apply to console").isSelected)
        }
    }

    @org.junit.Test
    fun switchingPresetsRetainsSeparateEdits() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetCustomizations["CUSTOM"] = "17|1.5|false|LIGHT|Unavailable Mono"
            openPanel()
            val order = presetNames()
            size().value = 21
            selectPreset(FontPreset.CUSTOM)
            assertEquals(17, size().value)
            assertTrue(labels().any { "Unavailable Mono" in it })
            assertFalse(button("Install automatically").isVisible)
            family().selectedItem = "Personal Mono"
            size().value = 18
            selectPreset(FontPreset.AMBIENT)
            assertEquals(21, size().value)
            selectPreset(FontPreset.CUSTOM)
            assertEquals(18, size().value)
            assertEquals("Personal Mono", family().selectedItem)
            assertEquals(order, presetNames())
            participant.apply()
            assertEquals("CUSTOM", settings.state.fontPresetName)
            assertEquals(
                "18.0|1.5|false|LIGHT|Personal Mono",
                settings.state.fontPresetCustomizations["CUSTOM"],
            )
            assertEquals("21.0|1.3|true|REGULAR", settings.state.fontPresetCustomizations["AMBIENT"])
            assertEquals("Personal Mono", applied.single().fontFamily)
        }
    }

    @org.junit.Test
    fun resetDiscardsAllPendingEdits() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetName = "CUSTOM"
            val original = mapOf("CUSTOM" to "17|1.5|false|LIGHT|Personal Mono", "FUTURE" to "opaque")
            settings.state.fontPresetCustomizations.putAll(original)
            openPanel()
            family().selectedItem = "Monospaced"
            size().value = 23
            spacing().value = 1.8
            weight().selectedItem = FontWeight.MEDIUM
            button("Ligatures").doClick()
            button("Also apply to console").doClick()
            selectPreset(FontPreset.WHISPER)
            button("Apply font preset").doClick()
            participant.reset()
            assertTrue(button("Apply font preset").isSelected)
            assertEquals("Personal Mono", family().selectedItem)
            assertEquals(17, size().value)
            assertEquals(1.5, spacing().value)
            assertEquals(FontWeight.LIGHT, weight().selectedItem)
            assertFalse(button("Ligatures").isSelected)
            assertFalse(button("Also apply to console").isSelected)
            assertFalse(participant.isModified())
            participant.apply()
            assertTrue(applied.isEmpty())
            assertTrue(events.isEmpty())
            assertEquals(original, settings.state.fontPresetCustomizations)
            reloadSettings()
            openPanel()
            assertEquals(17, size().value)
            assertEquals("CUSTOM", settings.state.fontPresetName)
        }
    }

    @org.junit.Test
    fun explicitDefaultsPreserveUnknownPreferences() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetName = "WHISPER"
            settings.state.fontApplyToConsole = true
            settings.state.fontInstallTerminal = "SYSTEM"
            settings.state.fontPresetCustomizations.putAll(
                mapOf(
                    "WHISPER" to "21|1.8|false|MEDIUM",
                    "CUSTOM" to "18|1.5|false|LIGHT|Personal Mono",
                    "FUTURE" to "opaque",
                ),
            )
            openPanel()
            button("Reset defaults").doClick()
            assertEquals(13, size().value)
            assertFalse(button("Also apply to console").isSelected)
            assertTrue(button("Apply font preset").isSelected)
            assertTrue(participant.isModified())
            participant.apply()
            assertEquals("AMBIENT", settings.state.fontPresetName)
            assertEquals("14.0|1.4|true|LIGHT", settings.state.fontPresetCustomizations["WHISPER"])
            assertEquals(
                "13.0|1.2|true|REGULAR|JetBrains Mono",
                settings.state.fontPresetCustomizations["CUSTOM"],
            )
            assertEquals("opaque", settings.state.fontPresetCustomizations["FUTURE"])
            assertEquals("SYSTEM", settings.state.fontInstallTerminal)
            assertEquals(FontPreset.AMBIENT, applied.single().preset)
        }
    }

    @org.junit.Test
    fun availabilityRowsAreMutuallyExclusive() {
        SwingUtilities.invokeAndWait {
            for (status in FontStatus.entries) {
                statuses[FontPreset.AMBIENT] = status
                openPanel()
                assertRows(status)
                button("Apply font preset").doClick()
                assertRows(null)
                button("Apply font preset").doClick()
                assertRows(status)
                selectPreset(FontPreset.CUSTOM)
                assertRows(null)
                assertTrue(settings.state.fontPresetCustomizations.isEmpty())
                assertTrue(applied.isEmpty())
            }
        }
    }

    @org.junit.Test
    fun deniedConsentDoesNotInstallOrDelete() {
        SwingUtilities.invokeAndWait {
            openPanel()
            button("Install automatically").doClick()
            assertEquals(listOf("consent:AMBIENT"), events)
            assertRows(FontStatus.NOT_INSTALLED)
            statuses[FontPreset.AMBIENT] = FontStatus.HEALTHY
            openPanel()
            button("Delete").doClick()
            assertEquals(listOf("consent:AMBIENT", "delete-consent:AMBIENT"), events)
            assertRows(FontStatus.HEALTHY)
            assertFalse(participant.isModified())
        }
    }

    @org.junit.Test
    fun acceptedInstallRefreshesAvailability() {
        SwingUtilities.invokeAndWait {
            grantInstall()
            openPanel()
            button("Install automatically").doClick()
            assertEquals(listOf("consent:AMBIENT", "install:AMBIENT"), events)
            assertRows(FontStatus.NOT_INSTALLED)
            statuses[FontPreset.AMBIENT] = FontStatus.HEALTHY
            requireNotNull(installCompletion)(FontInstaller.InstallResult.Success("Maple Mono"))
        }
        SwingUtilities.invokeAndWait { assertRows(FontStatus.HEALTHY) }
    }

    @org.junit.Test
    fun reinstallRefreshesBothInstalledStates() {
        for (initial in listOf(FontStatus.HEALTHY, FontStatus.CORRUPTED)) {
            SwingUtilities.invokeAndWait {
                events.clear()
                grantInstall()
                statuses[FontPreset.AMBIENT] = initial
                openPanel()
                button("Reinstall").doClick()
                assertEquals(listOf("consent:AMBIENT", "install:AMBIENT"), events)
                statuses[FontPreset.AMBIENT] = FontStatus.HEALTHY
                requireNotNull(installCompletion)(FontInstaller.InstallResult.Success("Maple Mono"))
            }
            SwingUtilities.invokeAndWait { assertRows(FontStatus.HEALTHY) }
        }
    }

    @org.junit.Test
    fun acceptedDeleteRefreshesMissingRow() {
        SwingUtilities.invokeAndWait {
            statuses[FontPreset.AMBIENT] = FontStatus.HEALTHY
            every { FontInstallConsent.confirmUninstall(any(), any(), any()) } answers
                {
                    assertEquals("/tmp/ayu-font-panel-fixture", thirdArg<String>())
                    events += "delete-consent:${firstArg<FontCatalog.Entry>().preset}"
                    true
                }
            openPanel()
            button("Delete").doClick()
            assertEquals(listOf("delete-consent:AMBIENT", "delete:AMBIENT"), events)
            statuses[FontPreset.AMBIENT] = FontStatus.NOT_INSTALLED
            requireNotNull(deleteCompletion)(FontUninstaller.UninstallResult.Success("Maple Mono", 2))
        }
        SwingUtilities.invokeAndWait { assertRows(FontStatus.NOT_INSTALLED) }
    }

    @org.junit.Test
    fun unlicensedInstallOnlyRequestsLicense() {
        SwingUtilities.invokeAndWait {
            every { LicenseChecker.isLicensedOrGrace() } returns false
            openPanel()
            button("Install automatically").doClick()
            assertEquals(listOf("license"), events)
            assertRows(FontStatus.NOT_INSTALLED)
            verify(exactly = 1) { LicenseChecker.requestLicense("Unlock font installation") }
        }
    }

    @org.junit.Test
    fun lifecycleExceptionLeavesNextActionUsable() {
        SwingUtilities.invokeAndWait {
            every { FontInstallConsent.confirmInstall(any(), any(), any()) } throws
                IllegalStateException("Dialog unavailable")
            openPanel()
            button("Install automatically").doClick()
            assertRows(FontStatus.NOT_INSTALLED)
            grantInstall()
            button("Install automatically").doClick()
            assertEquals(listOf("consent:AMBIENT", "install:AMBIENT"), events)
            assertFalse(participant.isModified())
        }
    }

    @org.junit.Test
    fun customAvailabilityRefreshPreservesTypography() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetName = "CUSTOM"
            val encoded = "17|1.5|false|LIGHT|Personal Mono"
            settings.state.fontPresetCustomizations["CUSTOM"] = encoded
            openPanel()
            assertRows(null)
            every { FontDetector.isFamilyInstalled("Personal Mono") } returns true
            participant.reset()
            assertEquals("Personal Mono", family().selectedItem)
            assertEquals(17, size().value)
            assertRows(null)
            assertFalse(participant.isModified())
            participant.apply()
            assertEquals(encoded, settings.state.fontPresetCustomizations["CUSTOM"])
            assertTrue(applied.isEmpty())
            // A stale/programmatic click must still be rejected even when it bypasses the hidden row.
            button("Install automatically").doClick()
            assertTrue(events.isEmpty())
        }
    }

    @org.junit.Test
    fun brewCopyUsesSelectedPreset() {
        SwingUtilities.invokeAndWait {
            openPanel()
            if (!System.getProperty("os.name").lowercase().contains("mac")) {
                assertTrue(
                    descendants(component).filterIsInstance<AbstractButton>().none { it.text == "Copy" },
                )
                return@invokeAndWait
            }
            selectPreset(FontPreset.NEON)
            button("Copy").doClick()
            val command =
                "brew install --cask ${requireNotNull(FontCatalog.forPreset(FontPreset.NEON)).brewCaskSlug}"
            verify(exactly = 1) {
                (clipboard::setContents)(match { it.getTransferData(DataFlavor.stringFlavor) == command })
            }
            assertTrue(events.isEmpty())
        }
    }

    @org.junit.Test
    fun terminalChoicePersistsImmediately() {
        SwingUtilities.invokeAndWait {
            openPanel()
            if (!System.getProperty("os.name").lowercase().contains("mac")) return@invokeAndWait
            terminal().selectedItem = "System Terminal"
            val terminalCommandMatcher = EqMatcher(arrayOf("open", "-a", "Terminal"))
            every { constructedWith<ProcessBuilder>(terminalCommandMatcher).start() } returns mockk()
            assertEquals("SYSTEM", settings.state.fontInstallTerminal)
            assertFalse(participant.isModified())
            participant.reset()
            assertEquals("SYSTEM", settings.state.fontInstallTerminal)
            button("Run in Terminal").doClick()
            verify(exactly = 1) { constructedWith<ProcessBuilder>(terminalCommandMatcher).start() }
            val command = "brew install --cask font-maple-mono"
            verify(exactly = 1) {
                (clipboard::setContents)(match { it.getTransferData(DataFlavor.stringFlavor) == command })
            }
        }
    }

    @org.junit.Test
    fun builtinTerminalReceivesActivation() {
        SwingUtilities.invokeAndWait {
            openPanel()
            if (!System.getProperty("os.name").lowercase().contains("mac")) return@invokeAndWait
            val project = mockk<Project>()
            val manager = mockk<ToolWindowManager>()
            val terminalWindow = mockk<ToolWindow>()
            every { AccentApplicator.resolveFocusedProject() } returns project
            mockkStatic(ToolWindowManager::class)
            every { ToolWindowManager.getInstance(project) } returns manager
            every { manager.getToolWindow("Terminal") } returns terminalWindow
            every { terminalWindow.activate(null) } returns Unit
            button("Run in Terminal").doClick()
            verify(exactly = 1) { terminalWindow.activate(null) }
            val command = "brew install --cask font-maple-mono"
            verify(exactly = 1) {
                (clipboard::setContents)(match { it.getTransferData(DataFlavor.stringFlavor) == command })
            }
            verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
        }
    }

    @org.junit.Test
    fun legacyPresetMigratesWithoutReencodingPreferences() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetName = "CLEAN"
            settings.state.fontPresetCustomizations.putAll(
                mapOf("CLEAN" to "17|1.6|false|LIGHT", "FUTURE" to "opaque"),
            )
            openPanel()
            assertEquals("AMBIENT", settings.state.fontPresetName)
            assertEquals(
                mapOf("AMBIENT" to "17|1.6|false|LIGHT", "FUTURE" to "opaque"),
                settings.state.fontPresetCustomizations,
            )
            assertEquals(17, size().value)
            assertEquals(FontWeight.LIGHT, weight().selectedItem)
            assertFalse(button("Ligatures").isSelected)
            assertFalse(participant.isModified())
            participant.apply()
            assertTrue(applied.isEmpty())
            reloadSettings()
            openPanel()
            assertEquals(17, size().value)
            assertFalse(participant.isModified())
        }
    }

    @org.junit.Test
    fun unavailableTerminalKeepsCopiedCommand() {
        SwingUtilities.invokeAndWait {
            openPanel()
            if (!System.getProperty("os.name").lowercase().contains("mac")) return@invokeAndWait
            button("Run in Terminal").doClick()
            val command = "brew install --cask font-maple-mono"
            verify(exactly = 1) {
                (clipboard::setContents)(match { it.getTransferData(DataFlavor.stringFlavor) == command })
            }
            val project = mockk<Project>()
            val manager = mockk<ToolWindowManager>()
            every { AccentApplicator.resolveFocusedProject() } returns project
            mockkStatic(ToolWindowManager::class)
            every { ToolWindowManager.getInstance(project) } returns manager
            every { manager.getToolWindow("Terminal") } returns null
            button("Run in Terminal").doClick()
            verify(exactly = 2) {
                (clipboard::setContents)(match { it.getTransferData(DataFlavor.stringFlavor) == command })
            }
            verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
            assertFalse(participant.isModified())
        }
    }

    @org.junit.Test
    fun customRejectsStaleBrewActions() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetName = "CUSTOM"
            val encoded = "17|1.5|false|LIGHT|Personal Mono"
            settings.state.fontPresetCustomizations["CUSTOM"] = encoded
            openPanel()
            if (!System.getProperty("os.name").lowercase().contains("mac")) return@invokeAndWait
            assertFalse(button("Copy").isVisible)
            assertFalse(button("Run in Terminal").isVisible)
            button("Copy").doClick()
            button("Run in Terminal").doClick()
            verify(exactly = 0) { (clipboard::setContents)(any()) }
            verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
            assertEquals(encoded, settings.state.fontPresetCustomizations["CUSTOM"])
            assertEquals("Personal Mono", family().selectedItem)
            assertFalse(participant.isModified())
        }
    }

    @org.junit.Test
    fun runtimeFailureKeepsSavedEditsUsable() {
        SwingUtilities.invokeAndWait {
            every { FontPresetApplicator.apply(any()) } throws IllegalStateException("Scheme unavailable")
            openPanel()
            size().value = 19
            participant.apply()
            assertEquals("19.0|1.3|true|REGULAR", settings.state.fontPresetCustomizations["AMBIENT"])
            assertEquals(19, size().value)
            assertFalse(participant.isModified())
            every { FontPresetApplicator.apply(any()) } answers { applied += firstArg<FontSettings>() }
            size().value = 20
            assertTrue(participant.isModified())
            participant.apply()
            assertEquals(20f, applied.single().fontSize)
            assertEquals("20.0|1.3|true|REGULAR", settings.state.fontPresetCustomizations["AMBIENT"])
            assertFalse(participant.isModified())
        }
    }

    @org.junit.Test
    fun previewFailureLeavesSettingsUsable() {
        SwingUtilities.invokeAndWait {
            every { FontDetector.resolveFamily(any()) } throws IllegalStateException("Font lookup unavailable")
            openPanel()
            assertTrue(button("Apply font preset").isSelected)
            assertEquals(13, size().value)
            assertFalse(participant.isModified())
            size().value = 19
            participant.apply()
            assertEquals(19f, applied.single().fontSize)
            assertEquals("19.0|1.3|true|REGULAR", settings.state.fontPresetCustomizations["AMBIENT"])
            every { FontDetector.resolveFamily(any()) } answers { firstArg<FontPreset>().fontFamily }
            selectPreset(FontPreset.WHISPER)
            assertEquals(14, size().value)
            participant.apply()
            assertEquals(FontPreset.WHISPER, applied.last().preset)
            assertFalse(participant.isModified())
        }
    }

    private fun openPanel() {
        participant = FontPresetPanel()
        component = panel { participant.buildPanel(this) }
    }

    private fun reloadSettings() {
        val encoded = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))
        settings.loadState(
            XmlSerializer.deserialize(JDOMUtil.load(encoded), AyuIslandsState::class.java),
        )
    }

    private fun grantInstall() {
        val consent = mockk<FontInstallConsent.InstallConsent>()
        every { FontInstallConsent.confirmInstall(any(), any(), true) } answers
            {
                events += "consent:${firstArg<FontCatalog.Entry>().preset}"
                consent
            }
    }

    private fun button(text: String): AbstractButton {
        val matches =
            descendants(component)
                .filterIsInstance<AbstractButton>()
                .filter { it.text == text }
                .toList()
        return matches.singleOrNull() ?: matches.single { it.isVisible }
    }

    private fun expandCustomize() {
        val heading = descendants(component).filterIsInstance<TitledSeparator>().single { it.text == "Customize" }
        heading.dispatchEvent(MouseEvent(heading, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1))
    }

    private fun presetNames(): List<String> =
        descendants(component)
            .mapNotNull { it.accessibleContext?.accessibleName }
            .filter { name -> FontPreset.entries.any { it.displayName == name } }
            .toList()

    private fun selectedPresets(): List<String> =
        descendants(component)
            .mapNotNull { it.accessibleContext }
            .filter { it.accessibleStateSet.contains(AccessibleState.CHECKED) }
            .mapNotNull { it.accessibleName }
            .filter { name -> FontPreset.entries.any { it.displayName == name } }
            .toList()

    private fun selectPreset(preset: FontPreset) {
        // SDK segments expose ActionButton's standard AccessibleAction click.
        // No dependency on its internal implementation is needed.
        val segment = descendants(component).single { it.accessibleContext?.accessibleName == preset.displayName }
        assertTrue(segment.accessibleContext.accessibleAction.doAccessibleAction(0))
    }

    private fun size(): JSpinner =
        descendants(component).filterIsInstance<JSpinner>().single {
            (it.model as SpinnerNumberModel).stepSize == 1
        }

    private fun spacing(): JSpinner =
        descendants(component).filterIsInstance<JSpinner>().single {
            (it.model as SpinnerNumberModel).stepSize == 0.1
        }

    private fun weight(): JComboBox<*> =
        descendants(component).filterIsInstance<JComboBox<*>>().single {
            it.getItemAt(0) is FontWeight
        }

    private fun family(): JComboBox<*> =
        descendants(component).filterIsInstance<JComboBox<*>>().single {
            it.getItemAt(0) == "JetBrains Mono"
        }

    private fun terminal(): JComboBox<*> =
        descendants(component).filterIsInstance<JComboBox<*>>().single {
            it.getItemAt(0) == "Built-in Terminal"
        }

    private fun labels(): List<String> =
        descendants(component).filterIsInstance<JLabel>().map { it.text.orEmpty() }.toList()

    private fun assertRows(status: FontStatus?) {
        assertEquals(status == FontStatus.NOT_INSTALLED, button("Install automatically").isVisible)
        assertEquals(status == FontStatus.HEALTHY, button("Delete").isVisible)
        val visibleReinstall =
            descendants(component).filterIsInstance<AbstractButton>().count {
                it.text == "Reinstall" && it.isVisible
            }
        assertEquals(
            if (status == FontStatus.HEALTHY || status == FontStatus.CORRUPTED) 1 else 0,
            visibleReinstall,
        )
    }
}

private fun descendants(root: Component): Sequence<Component> =
    sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
    }
