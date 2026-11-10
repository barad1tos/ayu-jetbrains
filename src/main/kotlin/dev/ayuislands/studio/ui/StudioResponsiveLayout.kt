package dev.ayuislands.studio.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager2

/** Lay out consecutive label/control pairs on the EDT without changing their models or child order. */
internal class StudioResponsiveLayout(
    private val spacing: StudioSpacing = StudioUiTokens.spacing(),
) : LayoutManager2 {
    // AWT declares constraints as Object; this layout uses the existing child order instead.
    override fun addLayoutComponent(
        component: Component,
        constraints: Any?,
    ) {
        require(constraints == null) { "Settings layout accepts consecutive label/control pairs without constraints" }
    }

    override fun addLayoutComponent(
        name: String?,
        component: Component,
    ) {
        require(name == null) { "Settings layout does not use named constraints" }
    }

    override fun removeLayoutComponent(component: Component) = Unit

    override fun invalidateLayout(target: Container) = Unit

    override fun getLayoutAlignmentX(target: Container): Float = 0f

    override fun getLayoutAlignmentY(target: Container): Float = 0f

    override fun maximumLayoutSize(target: Container): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

    override fun minimumLayoutSize(parent: Container): Dimension {
        val fields = measureFields(parent)
        val insets = parent.insets
        val width = if (parent.width > 0) (parent.width - insets.left - insets.right).coerceAtLeast(0) else null
        val plan = StudioLayoutPolicy.arrange(fields.map { it.metrics }, width, spacing)
        return Dimension(plan.minimumWidth + insets.left + insets.right, plan.height + insets.top + insets.bottom)
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        val fields = measureFields(parent)
        val insets = parent.insets
        val width = if (parent.width > 0) (parent.width - insets.left - insets.right).coerceAtLeast(0) else null
        val plan = StudioLayoutPolicy.arrange(fields.map { it.metrics }, width, spacing)
        return Dimension(plan.preferredWidth + insets.left + insets.right, plan.height + insets.top + insets.bottom)
    }

    override fun layoutContainer(parent: Container) {
        val fields = measureFields(parent)
        val insets = parent.insets
        val width = (parent.width - insets.left - insets.right).coerceAtLeast(0)
        val plan = StudioLayoutPolicy.arrange(fields.map { it.metrics }, width, spacing)
        for ((field, placement) in fields.zip(plan.fields)) {
            place(field.label, placement.label, insets.left, insets.top)
            place(field.control, placement.control, insets.left, insets.top)
        }
    }

    private fun measureFields(parent: Container): List<NativeField> {
        val children = parent.components
        require(children.size % 2 == 0) { "Every setting needs a label followed by its control" }
        return children.toList().chunked(2).mapNotNull { pair ->
            val label = pair[0]
            val control = pair[1]
            if (!label.isVisible && !control.isVisible) {
                null
            } else {
                val labelSize = label.preferredSize
                val controlSize = control.preferredSize
                val controlMinimum = control.minimumSize
                NativeField(
                    label,
                    control,
                    FieldMetrics(
                        LayoutSize(labelSize.width, labelSize.height),
                        LayoutSize(
                            maxOf(controlSize.width, controlMinimum.width),
                            maxOf(controlSize.height, controlMinimum.height),
                        ),
                        controlMinimum.width,
                    ),
                )
            }
        }
    }

    private fun place(
        component: Component,
        bounds: LayoutBounds,
        left: Int,
        top: Int,
    ) {
        component.setBounds(left + bounds.x, top + bounds.y, bounds.width, bounds.height)
    }
}

private data class NativeField(
    val label: Component,
    val control: Component,
    val metrics: FieldMetrics,
)

private data class LayoutSize(
    val width: Int,
    val height: Int,
)

private data class FieldMetrics(
    val label: LayoutSize,
    val control: LayoutSize,
    val minimumControlWidth: Int,
)

private data class LayoutBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

private data class FieldPlacement(
    val label: LayoutBounds,
    val control: LayoutBounds,
)

private data class LayoutPlan(
    val minimumWidth: Int,
    val preferredWidth: Int,
    val height: Int,
    val fields: List<FieldPlacement>,
)

private data class LayoutColumn(
    val labelWidth: Int,
    val minimumWidth: Int,
    val preferredWidth: Int,
)

/** Calculate geometry only; native measurement and component mutation remain in the layout adapter. */
private object StudioLayoutPolicy {
    fun arrange(
        fields: List<FieldMetrics>,
        availableWidth: Int?,
        spacing: StudioSpacing,
    ): LayoutPlan {
        if (fields.isEmpty()) return LayoutPlan(0, 0, 0, emptyList())
        val minimumWidth = fields.maxOf { maxOf(it.label.width, it.minimumControlWidth) }
        val left = measureColumn(fields.filterIndexed { index, _ -> index % 2 == 0 }, spacing.fieldGap)
        val right = measureColumn(fields.filterIndexed { index, _ -> index % 2 != 0 }, spacing.fieldGap)
        val preferredWidth =
            if (fields.size > 1) left.preferredWidth + spacing.pairedGap + right.preferredWidth else left.preferredWidth
        val width = (availableWidth ?: preferredWidth).coerceAtLeast(minimumWidth)
        val isPaired = fields.size > 1 && left.minimumWidth + spacing.pairedGap + right.minimumWidth <= width
        val columns = if (isPaired) listOf(left, right) else listOf(measureColumn(fields, spacing.fieldGap))
        val widths = columnWidths(columns, width, spacing.pairedGap)
        val placements = mutableListOf<FieldPlacement>()
        var top = 0
        for (row in fields.chunked(columns.size)) {
            val height =
                row
                    .mapIndexed { columnIndex, field ->
                        fieldHeight(field, widths[columnIndex] >= columns[columnIndex].minimumWidth, spacing.fieldGap)
                    }.max()
            var x = 0
            for ((columnIndex, field) in row.withIndex()) {
                val column = columns[columnIndex]
                val columnWidth = widths[columnIndex]
                placements += placeField(field, column, LayoutBounds(x, top, columnWidth, height), spacing.fieldGap)
                x += columnWidth + spacing.pairedGap
            }
            top += height + spacing.groupGap
        }
        return LayoutPlan(minimumWidth, preferredWidth, top - spacing.groupGap, placements)
    }

    private fun measureColumn(
        fields: List<FieldMetrics>,
        gap: Int,
    ): LayoutColumn {
        if (fields.isEmpty()) return LayoutColumn(0, 0, 0)
        val labelWidth = fields.maxOf { it.label.width }
        return LayoutColumn(
            labelWidth,
            labelWidth + gap + fields.maxOf { it.minimumControlWidth },
            labelWidth + gap + fields.maxOf { it.control.width },
        )
    }

    private fun columnWidths(
        columns: List<LayoutColumn>,
        width: Int,
        gap: Int,
    ): List<Int> {
        if (columns.size == 1) return listOf(width)
        val extra = width - gap - columns.sumOf { it.minimumWidth }
        val leftWidth = columns[0].minimumWidth + extra / 2
        return listOf(leftWidth, width - gap - leftWidth)
    }

    private fun fieldHeight(
        field: FieldMetrics,
        isInline: Boolean,
        gap: Int,
    ): Int =
        if (isInline) {
            maxOf(field.label.height, field.control.height)
        } else {
            field.label.height + gap +
                field.control.height
        }

    private fun placeField(
        field: FieldMetrics,
        column: LayoutColumn,
        area: LayoutBounds,
        gap: Int,
    ): FieldPlacement =
        if (area.width >= column.minimumWidth) {
            val controlX = column.labelWidth + gap
            FieldPlacement(
                LayoutBounds(
                    area.x,
                    area.y + (area.height - field.label.height) / 2,
                    field.label.width,
                    field.label.height,
                ),
                LayoutBounds(
                    area.x + controlX,
                    area.y + (area.height - field.control.height) / 2,
                    minOf(field.control.width, area.width - controlX),
                    field.control.height,
                ),
            )
        } else {
            FieldPlacement(
                LayoutBounds(area.x, area.y, field.label.width, field.label.height),
                LayoutBounds(
                    area.x,
                    area.y + field.label.height + gap,
                    minOf(field.control.width, area.width),
                    field.control.height,
                ),
            )
        }
}
