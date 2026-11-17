package com.oguzhanaslann.roomer.layout

/** Diagram-space coordinates (unscaled, y grows downwards). */
data class Point(val x: Double, val y: Double)

data class Size(val width: Double, val height: Double)

data class LayoutNode(val id: String, val size: Size)

/** Directed edge; for foreign keys [sourceId] is the child table and [targetId] the parent. May be a self-loop. */
data class LayoutEdge(val sourceId: String, val targetId: String)

data class LayoutRequest(
    val nodes: List<LayoutNode>,
    val edges: List<LayoutEdge>,
    /**
     * Top-left positions that must be kept exactly (user-dragged or previously laid-out nodes).
     * Nodes not listed here are placed automatically without overlapping each other or the fixed nodes.
     * Entries for ids that are not in [nodes] are ignored.
     */
    val fixedPositions: Map<String, Point> = emptyMap(),
)

/** Top-left position of every node in the request, keyed by node id. */
data class LayoutResult(val positions: Map<String, Point>)

/**
 * Computes node positions. Implementations are pure and thread-safe, are called off the EDT, and must not
 * depend on Swing or the IDE (spec FR-23, NFR-07). Edges whose endpoints are unknown are ignored.
 */
fun interface LayoutEngine {
    fun layout(request: LayoutRequest): LayoutResult
}
