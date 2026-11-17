package com.oguzhanaslann.roomer.render

import com.oguzhanaslann.roomer.inference.CardinalityInference
import com.oguzhanaslann.roomer.inference.ChildCardinality
import com.oguzhanaslann.roomer.inference.ParentCardinality
import com.oguzhanaslann.roomer.inference.Relationship
import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.EntityKind
import com.oguzhanaslann.roomer.model.Table
import com.oguzhanaslann.roomer.model.View
import java.awt.Font
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Fonts derived from one base font plus their metrics. Measuring uses a fixed [FontRenderContext], so it needs no component. */
internal class FontSet(val base: Font) {
    val regular: Font = base.deriveFont(Font.PLAIN)
    val bold: Font = base.deriveFont(Font.BOLD)
    val italic: Font = base.deriveFont(Font.ITALIC)
    val small: Font = base.deriveFont(Font.PLAIN, max(8f, base.size2D - 2.5f))
    val smallBold: Font = small.deriveFont(Font.BOLD)

    val ascent: Float
    val textH: Float
    val rowH: Double
    val smallAscent: Float
    val smallH: Float

    init {
        val lm = regular.getLineMetrics("Ag", FRC)
        ascent = lm.ascent
        textH = lm.ascent + lm.descent
        rowH = ceil(textH.toDouble()) + 6.0
        val sm = smallBold.getLineMetrics("Ag", FRC)
        smallAscent = sm.ascent
        smallH = sm.ascent + sm.descent
    }

    fun width(font: Font, s: String): Float =
        if (s.isEmpty()) 0f else synchronized(LOCK) { font.getStringBounds(s, FRC).width.toFloat() }

    /** [s] if it fits [maxWidth], otherwise the longest prefix that fits followed by an ellipsis. */
    fun truncate(font: Font, s: String, maxWidth: Float): String {
        if (width(font, s) <= maxWidth) return s
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(font, s.substring(0, mid) + ELLIPSIS) <= maxWidth) lo = mid else hi = mid - 1
        }
        return s.substring(0, lo).trimEnd() + ELLIPSIS
    }

    companion object {
        /** Anti-aliased, fractional metrics: the same settings the canvas paints with, so measured = painted. */
        val FRC = FontRenderContext(null, true, true)
        private val LOCK = Any()
        const val ELLIPSIS = "…"
    }
}

internal class RowModel(
    /** Column name as in the model (empty for the SQL line of a view). */
    val column: String,
    val fullName: String,
    val fullType: String,
    val badges: Array<String>,
    val isSql: Boolean,
    tooltipBuilder: () -> String,
) {
    val tooltip: String by lazy(LazyThreadSafetyMode.NONE, tooltipBuilder)
    var name: String = fullName
    var type: String = fullType
    val badgeW = FloatArray(badges.size)
    var badgesTotalW = 0f
}

internal class NodeModel(
    val index: Int,
    val name: String,
    val kind: EntityKind,
    val stereotype: String?,
    val rows: Array<RowModel>,
    val tooltip: String,
) {
    var x = 0.0
    var y = 0.0
    var width = 0.0
        private set
    var headerH = 0.0
        private set
    var rowH = 0.0
        private set
    var collapsed = false
        set(value) {
            if (field != value) {
                field = value
                shapesValid = false
            }
        }
    var title: String = name
        private set
    var titleW = 0f
        private set
    var nameX = 0f
        private set
    var typeX = 0f
        private set

    /** Direct FK neighbours in either direction (never contains this node). */
    val neighbours = ArrayList<NodeModel>(4)

    private val rowIndex: Map<String, Int> = HashMap<String, Int>(rows.size * 2).also { m ->
        rows.forEachIndexed { i, r -> if (!r.isSql) m[r.column] = i }
    }

    val showsRows: Boolean get() = !collapsed && rows.isNotEmpty()
    val height: Double get() = if (showsRows) headerH + rows.size * rowH + BOTTOM_PAD else headerH
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2

    fun rowOf(column: String): Int = rowIndex[column] ?: -1
    fun rowTop(i: Int): Double = y + headerH + i * rowH
    fun rowCenterY(i: Int): Double = rowTop(i) + rowH / 2

    fun contains(dx: Double, dy: Double): Boolean = dx >= x && dx <= x + width && dy >= y && dy <= y + height

    /** Computes display strings and the node size for [fonts]. The one code path for layout sizes and painted sizes. */
    fun measure(fonts: FontSet) {
        rowH = fonts.rowH
        title = fonts.truncate(fonts.bold, name, MAX_TITLE_W)
        titleW = fonts.width(fonts.bold, title)
        val stereoW = if (stereotype != null) fonts.width(fonts.small, stereotype) else 0f
        headerH = if (stereotype != null) ceil(5.0 + fonts.smallH + fonts.textH + 5.0) else rowH + 8.0

        var markerW = 0f
        var nameW = 0f
        var typeW = 0f
        for (r in rows) {
            var total = 0f
            for (i in r.badges.indices) {
                val w = fonts.width(fonts.smallBold, r.badges[i]) + 2 * BADGE_PAD_X
                r.badgeW[i] = w
                total += w + if (i > 0) BADGE_GAP else 0f
            }
            r.badgesTotalW = total
            markerW = max(markerW, total)
            if (r.isSql) {
                r.name = fonts.truncate(fonts.italic, r.fullName, MAX_SQL_W)
                nameW = max(nameW, fonts.width(fonts.italic, r.name))
            } else {
                r.name = fonts.truncate(fonts.regular, r.fullName, MAX_NAME_W)
                r.type = fonts.truncate(fonts.regular, r.fullType, MAX_TYPE_W)
                nameW = max(nameW, fonts.width(fonts.regular, r.name))
                typeW = max(typeW, fonts.width(fonts.regular, r.type))
            }
        }
        nameX = PAD + if (markerW > 0f) markerW + 7f else 0f
        typeX = nameX + nameW + if (typeW > 0f) 16f else 0f
        val rowsW = if (rows.isEmpty()) 0f else typeX + typeW + PAD
        val headerW = TITLE_X + max(titleW, stereoW) + PAD
        width = ceil(max(MIN_W, max(headerW, rowsW)).toDouble())
        shapesValid = false
    }

    // ---- cached shapes in node-local coordinates -------------------------------------------------------

    private var shapesValid = false
    private var outlineShape: Shape = Rectangle2D.Double()
    private var headerShapeCache: Shape = Rectangle2D.Double()

    val outline: Shape get() { ensureShapes(); return outlineShape }
    val headerShape: Shape get() { ensureShapes(); return headerShapeCache }

    private fun ensureShapes() {
        if (shapesValid) return
        val w = width
        val h = height
        val hh = headerH
        when (kind) {
            EntityKind.TABLE -> {
                outlineShape = Rectangle2D.Double(0.0, 0.0, w, h)
                headerShapeCache = Rectangle2D.Double(0.0, 0.0, w, hh)
            }
            EntityKind.VIEW -> {
                val r = VIEW_RADIUS
                outlineShape = RoundRectangle2D.Double(0.0, 0.0, w, h, 2 * r, 2 * r)
                headerShapeCache = if (!showsRows) outlineShape else Path2D.Double().apply {
                    moveTo(0.0, hh); lineTo(0.0, r); quadTo(0.0, 0.0, r, 0.0); lineTo(w - r, 0.0)
                    quadTo(w, 0.0, w, r); lineTo(w, hh); closePath()
                }
            }
            EntityKind.FTS_TABLE -> {
                val c = FTS_CHAMFER
                outlineShape = Path2D.Double().apply {
                    moveTo(c, 0.0); lineTo(w - c, 0.0); lineTo(w, c); lineTo(w, h); lineTo(0.0, h); lineTo(0.0, c); closePath()
                }
                headerShapeCache = Path2D.Double().apply {
                    moveTo(c, 0.0); lineTo(w - c, 0.0); lineTo(w, c); lineTo(w, hh); lineTo(0.0, hh); lineTo(0.0, c); closePath()
                }
            }
        }
        shapesValid = true
    }

    companion object {
        const val PAD = 9f
        const val CHEVRON = 8f
        const val TITLE_X = PAD + CHEVRON + 7f
        const val BOTTOM_PAD = 4.0
        const val MIN_W = 110f
        const val MAX_TITLE_W = 300f
        const val MAX_NAME_W = 220f
        const val MAX_TYPE_W = 130f
        const val MAX_SQL_W = 250f
        const val BADGE_PAD_X = 4f
        const val BADGE_GAP = 3f
        const val VIEW_RADIUS = 11.0
        const val FTS_CHAMFER = 9.0
    }
}

internal enum class EndKind { EXACTLY_ONE, ZERO_OR_ONE, ZERO_OR_MANY }

internal class EdgeModel(
    val id: String,
    val relationship: Relationship,
    val child: NodeModel,
    val parent: NodeModel,
    /** Row indices of the FK columns in [child]. */
    val childRows: IntArray,
    /** Row indices of the referenced columns in [parent]. */
    val parentRows: IntArray,
    val tooltip: String,
) {
    val isSelf: Boolean get() = child === parent
    val childEnd: EndKind =
        if (relationship.childEnd == ChildCardinality.MANY) EndKind.ZERO_OR_MANY else EndKind.ZERO_OR_ONE
    val parentEnd: EndKind =
        if (relationship.parentEnd == ParentCardinality.EXACTLY_ONE) EndKind.EXACTLY_ONE else EndKind.ZERO_OR_ONE

    // Geometry, filled by EdgeRouter. (ax, ay) is the child anchor on the node border, (bx, by) the parent anchor;
    // aDir / bDir is +1 if the edge leaves towards the right, -1 towards the left.
    var ax = 0.0; var ay = 0.0; var aDir = 1
    var bx = 0.0; var by = 0.0; var bDir = 1
    /** True if the parent end leaves the parent's TOP border upwards (self-loop on a collapsed node); [bDir] is then unused. */
    var bUp = false
    val path = Path2D.Double()
    /**
     * Flattened path as x0, y0, x1, y1, … (flatness [EdgeRouter.FLATNESS]) for hit-testing. Painting strokes [path]:
     * measured, Marlin strokes the 3-segment cubic faster than this many-segment polyline.
     */
    var points = DoubleArray(0)
    var pointCount = 0
    var minX = 0.0; var minY = 0.0; var maxX = 0.0; var maxY = 0.0

    /** Distance from a diagram-space point to the edge polyline. */
    fun distanceTo(px: Double, py: Double): Double {
        var best = Double.MAX_VALUE
        var i = 0
        while (i + 3 < pointCount * 2) {
            val d = segmentDistanceSq(px, py, points[i], points[i + 1], points[i + 2], points[i + 3])
            if (d < best) best = d
            i += 2
        }
        return if (best == Double.MAX_VALUE) best else kotlin.math.sqrt(best)
    }

    private fun segmentDistanceSq(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val vx = x2 - x1
        val vy = y2 - y1
        val len = vx * vx + vy * vy
        val t = if (len == 0.0) 0.0 else (((px - x1) * vx + (py - y1) * vy) / len).coerceIn(0.0, 1.0)
        val cx = x1 + t * vx - px
        val cy = y1 + t * vy - py
        return cx * cx + cy * cy
    }
}

internal enum class HitKind { NONE, NODE_HEADER, CHEVRON, ROW, EDGE }

internal class Hit(val kind: HitKind, val node: NodeModel? = null, val row: Int = -1, val edge: EdgeModel? = null) {
    val isNode: Boolean get() = node != null
    companion object { val NONE = Hit(HitKind.NONE) }
}

/**
 * Everything the canvas needs to paint and hit-test one database, precomputed once when the diagram is set:
 * no model traversal happens during painting.
 */
internal class DiagramScene(val database: Database) {
    val nodes: List<NodeModel>
    val byName: Map<String, NodeModel>
    val edges: List<EdgeModel>

    /** Paint order (last = top-most). The selected node is moved to the end. */
    val zOrder: ArrayList<NodeModel>

    private var routesValid = false

    init {
        val relationships = CardinalityInference.infer(database)
        val list = ArrayList<NodeModel>(database.tables.size + database.views.size)
        val names = HashMap<String, NodeModel>()
        for (t in database.tables) {
            if (t.name in names) continue
            val n = buildTable(list.size, t)
            list += n; names[t.name] = n
        }
        for (v in database.views) {
            if (v.name in names) continue
            val n = buildView(list.size, v)
            list += n; names[v.name] = n
        }
        nodes = list
        byName = names
        zOrder = ArrayList(list)

        val edgeList = ArrayList<EdgeModel>(relationships.size)
        val fkCounter = HashMap<String, Int>()
        for (rel in relationships) {
            val indexInChild = fkCounter.merge(rel.childTable, 1, Int::plus)!! - 1
            val child = names[rel.childTable] ?: continue
            val parent = names[rel.parentTable] ?: continue
            val fk = rel.foreignKey
            edgeList += EdgeModel(
                id = rel.id(indexInChild),
                relationship = rel,
                child = child,
                parent = parent,
                childRows = fk.columns.map { child.rowOf(it) }.filter { it >= 0 }.toIntArray(),
                parentRows = fk.referencedColumns.map { parent.rowOf(it) }.filter { it >= 0 }.toIntArray(),
                tooltip = Tooltips.edge(rel),
            )
            if (child !== parent) {
                if (parent !in child.neighbours) child.neighbours += parent
                if (child !in parent.neighbours) parent.neighbours += child
            }
        }
        edges = edgeList
    }

    private fun buildTable(index: Int, t: Table): NodeModel {
        // One pass over keys/indices instead of per-row lookups.
        val pk = t.primaryKey.columns.toHashSet()
        val fk = HashSet<String>()
        for (f in t.foreignKeys) fk.addAll(f.columns)
        // Same rule as Table.isUnique / isIndexed (and Mermaid's UK): UQ = sole column of a unique index,
        // IDX = part of any other index (non-unique, or a multi-column unique index).
        val unique = HashSet<String>()
        val indexed = HashSet<String>()
        for (ix in t.indices) if (ix.unique && ix.columns.size == 1) unique.add(ix.columns[0]) else indexed.addAll(ix.columns)
        val rows = Array(t.columns.size) { i ->
            val c = t.columns[i]
            val badges = ArrayList<String>(4)
            if (c.name in pk) badges += BADGE_PK
            if (c.name in fk) badges += BADGE_FK
            if (c.name in unique) badges += BADGE_UNIQUE
            if (c.name in indexed) badges += BADGE_INDEX
            RowModel(
                column = c.name,
                fullName = c.name,
                fullType = if (c.notNull) c.type else c.type + NULLABLE_SUFFIX,
                badges = badges.toTypedArray(),
                isSql = false,
            ) { Tooltips.column(t, c) }
        }
        val fts = t.fts
        return NodeModel(index, t.name, t.kind, fts?.let { "«${it.version}»" }, rows, Tooltips.table(t))
    }

    private fun buildView(index: Int, v: View): NodeModel {
        val oneLine = v.sql.trim().replace(WHITESPACE, " ")
        val rows = if (oneLine.isEmpty()) emptyArray() else arrayOf(
            RowModel("", oneLine, "", emptyArray(), isSql = true) { Tooltips.view(v) },
        )
        return NodeModel(index, v.name, EntityKind.VIEW, "«view»", rows, Tooltips.view(v))
    }

    fun measure(fonts: FontSet) {
        for (n in nodes) n.measure(fonts)
        invalidateRoutes()
    }

    fun invalidateRoutes() { routesValid = false }

    fun ensureRoutes() {
        if (!routesValid) {
            EdgeRouter.routeAll(edges)
            routesValid = true
        }
    }

    /**
     * Applies [positions]; entities that have none are stacked in columns to the right of the placed ones
     * (or from the origin if nothing is placed). Requires [measure] to have run.
     */
    fun applyPositions(positions: Map<String, Point>) {
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val missing = ArrayList<NodeModel>()
        for (n in nodes) {
            val p = positions[n.name]
            if (p == null || p.x.isNaN() || p.y.isNaN()) { missing += n; continue }
            n.x = p.x; n.y = p.y
            minY = min(minY, n.y); maxX = max(maxX, n.x + n.width); maxY = max(maxY, n.y + n.height)
        }
        if (missing.isNotEmpty()) {
            val anyPlaced = missing.size < nodes.size
            val top = if (anyPlaced) minY else 0.0
            val columnHeight = max(if (anyPlaced) maxY - minY else 0.0, 900.0)
            var x = if (anyPlaced) maxX + 90.0 else 0.0
            var y = top
            var colW = 0.0
            for (n in missing) {
                if (y > top && y + n.height > top + columnHeight) {
                    x += colW + 90.0; y = top; colW = 0.0
                }
                n.x = x; n.y = y
                y += n.height + 40.0
                colW = max(colW, n.width)
            }
        }
        invalidateRoutes()
    }

    fun positions(): Map<String, Point> {
        val m = LinkedHashMap<String, Point>(nodes.size * 2)
        for (n in nodes) m[n.name] = Point(n.x, n.y)
        return m
    }

    fun collapsed(): Set<String> {
        val s = LinkedHashSet<String>()
        for (n in nodes) if (n.collapsed) s += n.name
        return s
    }

    fun bringToFront(n: NodeModel) {
        if (zOrder.isNotEmpty() && zOrder[zOrder.size - 1] === n) return
        zOrder.remove(n)
        zOrder.add(n)
    }

    /** Bounding box of [which] nodes and the edges between them, or null if empty. */
    fun bounds(which: Collection<NodeModel> = nodes): Rectangle2D.Double? {
        if (which.isEmpty()) return null
        ensureRoutes()
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (n in which) {
            x0 = min(x0, n.x); y0 = min(y0, n.y); x1 = max(x1, n.x + n.width); y1 = max(y1, n.y + n.height)
        }
        val all = which === nodes
        val set = if (all) null else which.toHashSet()
        for (e in edges) {
            if (set != null && (e.child !in set || e.parent !in set)) continue
            x0 = min(x0, e.minX); y0 = min(y0, e.minY); x1 = max(x1, e.maxX); y1 = max(y1, e.maxY)
        }
        return Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * What is at diagram point ([dx], [dy])? Nodes win over edges; the top-most node wins.
     * [edgeTolerance] is in diagram units (the canvas passes a fixed number of screen pixels / zoom).
     */
    fun hit(dx: Double, dy: Double, edgeTolerance: Double): Hit {
        for (i in zOrder.indices.reversed()) {
            val n = zOrder[i]
            if (!n.contains(dx, dy)) continue
            val ly = dy - n.y
            if (ly < n.headerH || !n.showsRows) {
                return if (n.rows.isNotEmpty() && dx - n.x < NodeModel.TITLE_X) Hit(HitKind.CHEVRON, n) else Hit(HitKind.NODE_HEADER, n)
            }
            val row = ((ly - n.headerH) / n.rowH).toInt()
            return if (row in n.rows.indices) Hit(HitKind.ROW, n, row) else Hit(HitKind.NODE_HEADER, n)
        }
        ensureRoutes()
        var best: EdgeModel? = null
        var bestD = edgeTolerance
        for (e in edges) {
            if (dx < e.minX - edgeTolerance || dx > e.maxX + edgeTolerance || dy < e.minY - edgeTolerance || dy > e.maxY + edgeTolerance) continue
            val d = e.distanceTo(dx, dy)
            if (d <= bestD) { bestD = d; best = e }
        }
        return if (best != null) Hit(HitKind.EDGE, edge = best) else Hit.NONE
    }

    companion object {
        const val BADGE_PK = "PK"
        const val BADGE_FK = "FK"
        const val BADGE_UNIQUE = "UQ"
        const val BADGE_INDEX = "IDX"
        const val NULLABLE_SUFFIX = "?"
        private val WHITESPACE = Regex("\\s+")
    }
}
