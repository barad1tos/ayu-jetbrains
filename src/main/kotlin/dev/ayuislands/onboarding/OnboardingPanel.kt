package dev.ayuislands.onboarding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Welcome wizard panel showing preset cards for quick onboarding.
 *
 * Applies glow + font presets in one click and closes via the [onClose] callback.
 */
internal class OnboardingPanel(
    private val project: Project,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(OUTER_INSET)
        add(createHeader(), BorderLayout.NORTH)
        add(createPresetCards(), BorderLayout.CENTER)
        add(createFooter(), BorderLayout.SOUTH)
    }

    private fun createHeader(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.emptyBottom(SECTION_GAP)

        val title = JBLabel("Welcome to Ayu Islands Premium")
        title.font = title.font?.deriveFont(Font.BOLD, TITLE_FONT_SIZE)
            ?: Font(Font.DIALOG, Font.BOLD, TITLE_FONT_SIZE.toInt())

        val subtitle = JBLabel("Your 30-day trial is active. Pick a preset to set glow and font in one click.")

        panel.add(title, BorderLayout.NORTH)
        panel.add(subtitle, BorderLayout.SOUTH)
        return panel
    }

    private fun createPresetCards(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.emptyBottom(SECTION_GAP)

        for ((glowPreset, fontPreset) in PRESETS) {
            val card = createPresetCard(glowPreset, fontPreset)
            panel.add(card)
        }
        return panel
    }

    private fun createPresetCard(
        glowPreset: GlowPreset,
        fontPreset: FontPreset,
    ): JPanel {
        val card = JPanel(BorderLayout())
        card.border = JBUI.Borders.empty(CARD_INSET)

        val styleName = glowPreset.style?.name?.lowercase()
        val label = "${glowPreset.displayName} \u2014 ${fontPreset.fontFamily}, glow: $styleName"
        val description = JBLabel(label)

        val applyButton = JButton(glowPreset.displayName)
        applyButton.addActionListener {
            applyPreset(glowPreset, fontPreset)
            onClose()
        }

        card.add(description, BorderLayout.CENTER)
        card.add(applyButton, BorderLayout.EAST)
        return card
    }

    private fun createFooter(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT))

        val keepDefaultsButton = JButton("Keep Defaults")
        keepDefaultsButton.addActionListener { onClose() }

        val openSettingsButton = JButton("Open Settings")
        openSettingsButton.addActionListener {
            onClose()
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
        }

        panel.add(keepDefaultsButton)
        panel.add(openSettingsButton)
        return panel
    }

    private fun applyPreset(
        glowPreset: GlowPreset,
        fontPreset: FontPreset,
    ) {
        val style = glowPreset.style ?: return
        val intensity = glowPreset.intensity ?: return
        val width = glowPreset.width ?: return
        val animation = glowPreset.animation ?: return
        val state = AyuIslandsSettings.getInstance().state

        state.glowEnabled = true
        state.glowStyle = style.name
        state.glowPreset = glowPreset.name
        state.setIntensityForStyle(style, intensity)
        state.setWidthForStyle(style, width)
        state.glowAnimation = animation.name

        state.fontPresetEnabled = true
        state.fontPresetName = fontPreset.name

        FontPresetApplicator.apply(
            FontSettings.decode(null, fontPreset).copy(applyToConsole = state.fontApplyToConsole),
        )

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                GlowOverlayManager.getInstance(project).initialize()
                GlowOverlayManager.syncGlowForAllProjects()
            }
        }

        LOG.info("Onboarding preset applied: ${glowPreset.name}")
    }

    companion object {
        private val LOG = logger<OnboardingPanel>()
        private const val OUTER_INSET = 16
        private const val SECTION_GAP = 12
        private const val CARD_INSET = 8
        private const val TITLE_FONT_SIZE = 18f

        private val PRESETS =
            listOf(
                GlowPreset.WHISPER to FontPreset.WHISPER,
                GlowPreset.AMBIENT to FontPreset.AMBIENT,
                GlowPreset.NEON to FontPreset.NEON,
                GlowPreset.CYBERPUNK to FontPreset.CYBERPUNK,
            )
    }
}
