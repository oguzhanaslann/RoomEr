package com.oguzhanaslann.roomer.layout

import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.util.NullElkProgressMonitor
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import kotlin.math.max

/**
 * [LayoutEngine] backed by the Eclipse Layout Kernel "layered" algorithm.
 *
 * Behaviour:
 * - Only node positions are taken from ELK; edge routes are discarded (the renderer routes edges itself).
 * - Layers run left to right with referenced (parent) tables left of the tables that reference them.
 * - Self-loops, edges with unknown endpoints and duplicate edges are ignored. For duplicate node ids the
 *   first occurrence wins. Negative or non-finite sizes are treated as 0.
 * - Without fixed positions the bounding box of the result starts at (0,0).
 * - Nodes in [LayoutRequest.fixedPositions] keep exactly those positions. The other nodes are laid out by ELK
 *   among themselves; each connected group that has fixed FK neighbours is then moved as a block to the
 *   nearest free spot around those neighbours, and the rest is put as one block right of / below the
 *   bounding box. Auto-placed nodes never overlap each other or a fixed node.
 * - Deterministic for identical input.
 * - Never throws: if ELK fails for any reason the free nodes are arranged by [GridLayoutEngine]'s grid and the
 *   failure is available from [lastError].
 *
 * Thread-safe. ELK calls are serialised process-wide because ELK's meta data service and EMF initialisation
 * are not documented as thread-safe; a layout takes milliseconds, so contention is irrelevant.
 */
class ElkLayoutEngine : LayoutEngine {

    /** The failure that made the most recent [layout] call on this instance fall back to the grid, or null if ELK succeeded. */
    @Volatile
    var lastError: Throwable? = null
        private set

    override fun layout(request: LayoutRequest): LayoutResult {
        var error: Throwable? = null
        val result = layoutWith(request) { nodes, edges ->
            try {
                runElk(nodes, edges)
            } catch (t: Throwable) {
                // Includes LinkageError etc.: a broken layout library must not take the IDE down.
                if (t is VirtualMachineError || t is InterruptedException) throw t
                error = t
                GridLayoutEngine.arrange(nodes, BLOCK_GAP)
            }
        }
        lastError = error
        return result
    }

    private companion object {
        const val NODE_SPACING = 70.0
        const val LAYER_SPACING = 110.0
        const val COMPONENT_SPACING = 90.0

        private val lock = Any()
        private var registered = false

        /**
         * ELK and EMF look services up through the thread context class loader, which inside the IDE is not the
         * plugin class loader. Run [block] with the class loader that actually holds the ELK classes.
         */
        private inline fun <T> withElkClassLoader(block: () -> T): T {
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            thread.contextClassLoader = ElkLayoutEngine::class.java.classLoader
            try {
                return block()
            } finally {
                thread.contextClassLoader = previous
            }
        }

        /** Must hold [lock]. Explicit registration, so nothing depends on ServiceLoader finding META-INF/services. */
        private fun ensureRegistered() {
            if (registered) return
            val service = LayoutMetaDataService.getInstance()
            if (service.getAlgorithmData(LayeredOptions.ALGORITHM_ID) == null) {
                service.registerLayoutMetaDataProviders(LayeredMetaDataProvider())
            }
            registered = true
        }

        fun runElk(nodes: List<LayoutNode>, edges: List<LayoutEdge>): Map<String, Point> = synchronized(lock) {
            withElkClassLoader {
                ensureRegistered()

                val root = ElkGraphUtil.createGraph()
                root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
                root.setProperty(LayeredOptions.DIRECTION, Direction.RIGHT)
                root.setProperty(LayeredOptions.EDGE_ROUTING, EdgeRouting.POLYLINE)
                root.setProperty(LayeredOptions.SPACING_NODE_NODE, NODE_SPACING)
                root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, LAYER_SPACING)
                root.setProperty(LayeredOptions.SPACING_EDGE_NODE, 30.0)
                root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, 30.0)
                root.setProperty(LayeredOptions.SPACING_EDGE_EDGE, 15.0)
                root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, 15.0)
                root.setProperty(LayeredOptions.SEPARATE_CONNECTED_COMPONENTS, true)
                root.setProperty(LayeredOptions.SPACING_COMPONENT_COMPONENT, COMPONENT_SPACING)
                root.setProperty(LayeredOptions.ASPECT_RATIO, TARGET_ASPECT_RATIO)
                root.setProperty(LayeredOptions.PADDING, ElkPadding(0.0))
                root.setProperty(LayeredOptions.RANDOM_SEED, 1)

                val elkNodes = LinkedHashMap<String, ElkNode>(nodes.size * 2)
                for (n in nodes) {
                    val elk = ElkGraphUtil.createNode(root)
                    // ELK needs a positive area to keep nodes apart.
                    elk.setDimensions(max(n.size.width, 1.0), max(n.size.height, 1.0))
                    elkNodes[n.id] = elk
                }
                for (e in edges) {
                    val child = elkNodes[e.sourceId] ?: continue
                    val parent = elkNodes[e.targetId] ?: continue
                    if (child === parent) continue
                    // Reversed on purpose: the referenced table goes into the earlier (left) layer.
                    ElkGraphUtil.createSimpleEdge(parent, child)
                }

                RecursiveGraphLayoutEngine().layout(root, NullElkProgressMonitor())

                val out = HashMap<String, Point>(nodes.size * 2)
                for ((id, elk) in elkNodes) out[id] = Point(elk.x, elk.y)
                out
            }
        }
    }
}
