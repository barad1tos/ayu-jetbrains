package dev.ayuislands.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.InplaceButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.PrimitiveGroup
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants

/** Builds and owns the presentation components for the Custom primitive grid. */
internal class SyntaxControlGrid(
    private val identityValue: Int,
    private val createStyleControl: (PrimitiveCategory) -> SyntaxStyleControl,
    private val sliderChanged: (PrimitiveCategory, Int) -> Unit,
    private val sliderReleased: (PrimitiveCategory) -> Unit,
    private val reset: (PrimitiveCategory) -> Unit,
    private val updateReadout: (JLabel, Int) -> Unit,
) {
    val categoryLabels: MutableMap<PrimitiveCategory, JLabel> = mutableMapOf()
    val sliders: MutableMap<PrimitiveCategory, JSlider> = mutableMapOf()
    val sliderLabels: MutableMap<PrimitiveCategory, JLabel> = mutableMapOf()
    val resetButtons: MutableMap<PrimitiveCategory, InplaceButton> = mutableMapOf()
    val styleControls: MutableMap<PrimitiveCategory, SyntaxStyleControl> = mutableMapOf()

    private val categoryRows: MutableMap<PrimitiveCategory, Row> = mutableMapOf()
    private val groupRows: MutableMap<PrimitiveGroup, Row> = mutableMapOf()
    private val labelColumnWidth: Int by lazy(::computeLabelColumnWidth)

    fun build(panel: Panel): Row =
        with(panel) {
            row {
                panel {
                    COLUMN_GROUPS.first().forEach { buildGroup(it) }
                }.align(AlignX.FILL)
                panel {
                    COLUMN_GROUPS.last().forEach { buildGroup(it) }
                }.align(AlignX.FILL)
            }
        }

    fun show(categories: Set<PrimitiveCategory>) {
        categoryRows.forEach { (category, row) -> row.visible(category in categories) }
        groupRows.forEach { (group, row) ->
            row.visible(PrimitiveCategory.entries.any { it.specification.group == group && it in categories })
        }
    }

    private fun Panel.buildGroup(categoryGroup: CategoryGroup) {
        groupRows[categoryGroup.group] =
            group(categoryGroup.title) {
                categoryGroup.categories.forEach { buildCategory(it) }
            }
    }

    private fun Panel.buildCategory(category: PrimitiveCategory) {
        categoryRows[category] =
            row {
                val categoryLabel =
                    JLabel(category.displayName).apply {
                        preferredSize = Dimension(labelColumnWidth, preferredSize.height)
                        minimumSize = Dimension(labelColumnWidth, preferredSize.height)
                    }
                categoryLabels[category] = categoryLabel
                cell(categoryLabel).gap(RightGap.SMALL)

                val styleControl = createStyleControl(category)
                styleControls[category] = styleControl
                val sliderAndStyle = sliderStylePair(styleControl)
                val intensitySlider = sliderAndStyle.slider
                cell(sliderAndStyle.component)

                val valueLabel =
                    JLabel("", SwingConstants.RIGHT).apply {
                        val width = JBUI.scale(READOUT_WIDTH)
                        preferredSize = Dimension(width, preferredSize.height)
                    }
                updateReadout(valueLabel, identityValue)
                intensitySlider.addChangeListener {
                    sliderChanged(category, intensitySlider.value)
                    if (!intensitySlider.valueIsAdjusting) sliderReleased(category)
                }
                cell(valueLabel).gap(RightGap.SMALL)

                val resetButton =
                    InplaceButton("Reset ${category.displayName} to default", AllIcons.Actions.Rollback) {
                        reset(category)
                    }.apply {
                        isVisible = false
                        isFocusable = true
                        accessibleContext.accessibleName = "Reset ${category.displayName} to default"
                    }
                resetButtons[category] = resetButton
                cell(resetSlot(resetButton))

                sliders[category] = intensitySlider
                sliderLabels[category] = valueLabel
            }
    }

    private fun resetSlot(resetButton: InplaceButton): JPanel =
        JPanel(GridLayout(1, TRAILING_SLOT_COUNT, 0, 0)).apply {
            isOpaque = false
            val zoneWidth = JBUI.scale(TRAILING_ZONE_WIDTH)
            val zoneHeight = JBUI.scale(TRAILING_SLOT_SIDE)
            preferredSize = Dimension(zoneWidth, zoneHeight)
            minimumSize = Dimension(zoneWidth, zoneHeight)
            add(resetButton)
        }

    private fun sliderStylePair(styleControl: SyntaxStyleControl): SliderStylePair {
        lateinit var intensitySlider: JSlider
        val component =
            panel {
                row {
                    intensitySlider =
                        slider(SLIDER_MIN, SLIDER_MAX, 0, 0)
                            .applyToComponent {
                                paintTicks = false
                                paintLabels = false
                                snapToTicks = false
                                val width = JBUI.scale(SLIDER_TRACK_WIDTH)
                                preferredSize = Dimension(width, preferredSize.height)
                                maximumSize = Dimension(width, preferredSize.height)
                            }.customize(UnscaledGaps(right = STYLE_CONTROL_GAP))
                            .align(AlignY.CENTER)
                            .component
                    styleControl.component.preferredSize =
                        Dimension(
                            styleControl.component.preferredSize.width,
                            intensitySlider.preferredSize.height,
                        )
                    cell(styleControl.component).align(AlignY.CENTER)
                }
            }.apply {
                isOpaque = false
            }
        return SliderStylePair(component, intensitySlider)
    }

    private fun computeLabelColumnWidth(): Int {
        val font = UIUtil.getLabelFont()
        val metrics = JLabel().getFontMetrics(font)
        val widest = PrimitiveCategory.entries.maxOf { metrics.stringWidth(it.displayName) }
        return if (widest <= 0) JBUI.scale(LABEL_FALLBACK_WIDTH) else widest + JBUI.scale(LABEL_PADDING)
    }

    private data class CategoryGroup(
        val group: PrimitiveGroup,
        val title: String,
        val categories: List<PrimitiveCategory>,
    )

    private data class SliderStylePair(
        val component: JComponent,
        val slider: JSlider,
    )

    private companion object {
        private const val SLIDER_MIN = 0
        private const val SLIDER_MAX = 100
        private const val SLIDER_TRACK_WIDTH = 140
        private const val STYLE_CONTROL_GAP = 8
        private const val READOUT_WIDTH = 28
        private const val TRAILING_SLOT_COUNT = 1
        private const val TRAILING_SLOT_SIDE = 20
        private const val TRAILING_ZONE_WIDTH = 20
        private const val LABEL_PADDING = 8
        private const val LABEL_FALLBACK_WIDTH = 170

        private val CATEGORY_GROUPS: List<CategoryGroup> =
            PrimitiveGroup.entries.map { group ->
                CategoryGroup(
                    group = group,
                    title = group.displayName,
                    categories =
                        PrimitiveCategory.entries
                            .filter { it.specification.group == group }
                            .sortedBy { it.specification.order },
                )
            }

        private val COLUMN_GROUPS: List<List<CategoryGroup>> =
            PrimitiveGroup.entries
                .groupBy(PrimitiveGroup::columnIndex)
                .toSortedMap()
                .values
                .map { groups ->
                    groups.sortedBy(PrimitiveGroup::columnOrder).map { group ->
                        CATEGORY_GROUPS[group.ordinal]
                    }
                }
    }
}

/** Formats the intensity delta and its matching accessible name. */
internal object SyntaxIntensityReadout {
    fun apply(
        label: JLabel,
        value: Int,
        identity: Int,
    ) {
        val isIdentity = value == identity
        label.text = if (isIdentity) "" else signed(value, identity)
        label.foreground =
            if (isIdentity) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
    }

    fun accessibleName(
        category: PrimitiveCategory,
        value: Int,
        identity: Int,
    ): String = "${category.displayName} intensity, ${signed(value, identity)} from default"

    fun signed(
        value: Int,
        identity: Int,
    ): String =
        when {
            value > identity -> "+${value - identity}"
            value < identity -> "−${identity - value}"
            else -> "0"
        }
}
