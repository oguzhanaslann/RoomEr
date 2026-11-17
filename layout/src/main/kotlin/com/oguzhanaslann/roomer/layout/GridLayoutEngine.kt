package com.oguzhanaslann.roomer.layout

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Dependency-free [LayoutEngine]: places nodes row by row in request order, ignoring edges. Used as the
 * fallback when ELK fails and as a simple reference implementation.
 *
 * Same contract as [ElkLayoutEngine]: fixed positions are kept exactly, other nodes never overlap each other
 * or fixed nodes, the result is deterministic, and odd input (duplicate ids: first wins; negative or
 * non-finite sizes: treated as 0) never throws. Stateless and thread-safe.
 */
class GridLayoutEngine(private val spacing: Double = BLOCK_GAP) : LayoutEngine {

    override fun layout(request: LayoutRequest): LayoutResult = layoutWith(request) { nodes, _ -> arrange(nodes, spacing) }

    internal companion object {
        /** Grid whose overall shape approximates [TARGET_ASPECT_RATIO]; column widths / row heights fit their largest node. */
        fun arrange(nodes: List<LayoutNode>, spacing: Double): Map<String, Point> {
            if (nodes.isEmpty()) return emptyMap()
            val gap = if (spacing.isFinite() && spacing >= 0.0) spacing else BLOCK_GAP
            val avgW = nodes.sumOf { it.size.width } / nodes.size + gap
            val avgH = nodes.sumOf { it.size.height } / nodes.size + gap
            val ideal = if (avgW > 0.0 && avgH > 0.0) sqrt(nodes.size * TARGET_ASPECT_RATIO * avgH / avgW) else sqrt(nodes.size.toDouble())
            val columns = (if (ideal.isFinite()) ceil(ideal).toInt() else 1).coerceIn(1, nodes.size)

            val colWidths = DoubleArray(columns)
            val rowHeights = DoubleArray((nodes.size + columns - 1) / columns)
            nodes.forEachIndexed { i, n ->
                colWidths[i % columns] = max(colWidths[i % columns], n.size.width)
                rowHeights[i / columns] = max(rowHeights[i / columns], n.size.height)
            }
            val colX = DoubleArray(columns)
            for (c in 1 until columns) colX[c] = colX[c - 1] + colWidths[c - 1] + gap
            val rowY = DoubleArray(rowHeights.size)
            for (r in 1 until rowHeights.size) rowY[r] = rowY[r - 1] + rowHeights[r - 1] + gap

            val out = HashMap<String, Point>(nodes.size * 2)
            nodes.forEachIndexed { i, n -> out[n.id] = Point(colX[i % columns], rowY[i / columns]) }
            return out
        }
    }
}
