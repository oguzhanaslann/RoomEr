package com.oguzhanaslann.roomer.layout

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Minimum free space kept between auto-placed blocks and everything else. */
internal const val BLOCK_GAP = 80.0

/** Width / height the packing steps aim for (roughly a landscape screen). */
internal const val TARGET_ASPECT_RATIO = 1.6

internal data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right get() = x + w
    val bottom get() = y + h
    val centerX get() = x + w / 2
    val centerY get() = y + h / 2

    /** True if the interiors intersect; touching borders do not count. */
    fun overlaps(o: Rect): Boolean = x < o.right && right > o.x && y < o.bottom && bottom > o.y

    fun inflate(d: Double) = Rect(x - d, y - d, w + 2 * d, h + 2 * d)

    fun union(o: Rect): Rect {
        val nx = min(x, o.x)
        val ny = min(y, o.y)
        return Rect(nx, ny, max(right, o.right) - nx, max(bottom, o.bottom) - ny)
    }
}

internal fun Iterable<Rect>.bounds(): Rect? = reduceOrNull(Rect::union)

/** A request with odd input removed: unique node ids, finite non-negative sizes, only usable edges. */
internal class CleanRequest(request: LayoutRequest) {
    /** Unique nodes in request order. For duplicate ids the FIRST occurrence wins. */
    val nodes: List<LayoutNode>

    /** Edges between two distinct known nodes, de-duplicated (direction-sensitive), in request order. */
    val edges: List<LayoutEdge>

    /** Fixed positions for known nodes with finite coordinates. */
    val fixed: Map<String, Point>

    init {
        val byId = LinkedHashMap<String, LayoutNode>()
        for (n in request.nodes) {
            byId.putIfAbsent(n.id, LayoutNode(n.id, Size(sane(n.size.width), sane(n.size.height))))
        }
        nodes = byId.values.toList()
        edges = request.edges
            .filter { it.sourceId != it.targetId && it.sourceId in byId && it.targetId in byId }
            .distinct()
        val f = LinkedHashMap<String, Point>()
        for (n in nodes) {
            val p = request.fixedPositions[n.id] ?: continue
            if (p.x.isFinite() && p.y.isFinite()) f[n.id] = p
        }
        fixed = f
    }

    private fun sane(v: Double) = if (v.isFinite() && v > 0.0) v else 0.0
}

/**
 * Shared skeleton of both engines.
 *
 * 1. Fixed nodes keep their positions exactly.
 * 2. [arrange] lays out the remaining ("free") nodes relative to each other.
 * 3. Without fixed nodes the arrangement is normalised to start at (0,0). Otherwise each connected group of
 *    free nodes that has fixed FK neighbours is moved, as a rigid block, to the free spot closest to those
 *    neighbours; all other free nodes are moved together as one block beside the bounding box.
 * 4. A final pass relocates any node that still overlaps something, so "no overlap" holds unconditionally
 *    (for nodes with a non-zero area).
 */
internal fun layoutWith(
    request: LayoutRequest,
    arrange: (nodes: List<LayoutNode>, edges: List<LayoutEdge>) -> Map<String, Point>,
): LayoutResult {
    val clean = CleanRequest(request)
    val free = clean.nodes.filter { it.id !in clean.fixed }
    val placed = HashMap<String, Point>(clean.fixed)

    if (free.isNotEmpty()) {
        val freeIds = free.mapTo(HashSet()) { it.id }
        val freeEdges = clean.edges.filter { it.sourceId in freeIds && it.targetId in freeIds }
        val raw = arrange(free, freeEdges)
        val relative = normalise(free, raw)
        if (clean.fixed.isEmpty()) {
            placed.putAll(relative)
        } else {
            placeAroundFixed(clean, free, freeEdges, relative, placed)
        }
        resolveOverlaps(clean, free, placed)
    }

    val out = LinkedHashMap<String, Point>(clean.nodes.size * 2)
    for (n in clean.nodes) out[n.id] = placed.getValue(n.id)
    return LayoutResult(out)
}

/** Translates [raw] so its bounding box starts at (0,0); nodes missing or non-finite in [raw] are appended in a row. */
private fun normalise(nodes: List<LayoutNode>, raw: Map<String, Point>): Map<String, Point> {
    val ok = nodes.filter { n -> raw[n.id]?.let { it.x.isFinite() && it.y.isFinite() } == true }
    val minX = ok.minOfOrNull { raw.getValue(it.id).x } ?: 0.0
    val minY = ok.minOfOrNull { raw.getValue(it.id).y } ?: 0.0
    val out = HashMap<String, Point>()
    var maxRight = 0.0
    for (n in ok) {
        val p = raw.getValue(n.id)
        val q = Point(p.x - minX, p.y - minY)
        out[n.id] = q
        maxRight = max(maxRight, q.x + n.size.width)
    }
    var x = if (ok.isEmpty()) 0.0 else maxRight + BLOCK_GAP
    for (n in nodes) {
        if (n.id in out) continue
        out[n.id] = Point(x, 0.0)
        x += n.size.width + BLOCK_GAP
    }
    return out
}

private fun rectOf(n: LayoutNode, p: Point) = Rect(p.x, p.y, n.size.width, n.size.height)

private fun placeAroundFixed(
    clean: CleanRequest,
    free: List<LayoutNode>,
    freeEdges: List<LayoutEdge>,
    relative: Map<String, Point>,
    placed: MutableMap<String, Point>,
) {
    val nodeById = clean.nodes.associateBy { it.id }
    val obstacles = ArrayList<Rect>()
    for ((id, p) in clean.fixed) obstacles += rectOf(nodeById.getValue(id), p)

    // Connected groups of free nodes (union-find over free-free edges), in request order.
    val index = HashMap<String, Int>()
    free.forEachIndexed { i, n -> index[n.id] = i }
    val parent = IntArray(free.size) { it }
    fun find(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        var c = i
        while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
        return r
    }
    for (e in freeEdges) {
        val a = find(index.getValue(e.sourceId))
        val b = find(index.getValue(e.targetId))
        if (a != b) parent[max(a, b)] = min(a, b)
    }
    val groups = LinkedHashMap<Int, MutableList<LayoutNode>>()
    free.forEachIndexed { i, n -> groups.getOrPut(find(i)) { ArrayList() } += n }

    // Fixed FK neighbours of every free node.
    val fixedNeighbours = HashMap<String, MutableList<String>>()
    for (e in clean.edges) {
        val sFixed = e.sourceId in clean.fixed
        val tFixed = e.targetId in clean.fixed
        if (sFixed && !tFixed) fixedNeighbours.getOrPut(e.targetId) { ArrayList() } += e.sourceId
        if (tFixed && !sFixed) fixedNeighbours.getOrPut(e.sourceId) { ArrayList() } += e.targetId
    }

    fun commit(block: List<LayoutNode>, blockBounds: Rect, to: Point) {
        val dx = to.x - blockBounds.x
        val dy = to.y - blockBounds.y
        for (n in block) {
            val p = relative.getValue(n.id)
            placed[n.id] = Point(p.x + dx, p.y + dy)
        }
        obstacles += Rect(to.x, to.y, blockBounds.w, blockBounds.h)
    }

    val floating = ArrayList<LayoutNode>()
    for (group in groups.values) {
        val anchors = group.flatMap { fixedNeighbours[it.id].orEmpty() }
        if (anchors.isEmpty()) {
            floating += group
            continue
        }
        val blockBounds = group.map { rectOf(it, relative.getValue(it.id)) }.bounds()!!
        val cx = anchors.map { rectOf(nodeById.getValue(it), clean.fixed.getValue(it)).centerX }.average()
        val cy = anchors.map { rectOf(nodeById.getValue(it), clean.fixed.getValue(it)).centerY }.average()
        val spot = nearestFreeSpot(blockBounds.w, blockBounds.h, cx, cy, obstacles)
            ?: beside(obstacles, blockBounds.w, blockBounds.h)
        commit(group, blockBounds, spot)
    }
    if (floating.isNotEmpty()) {
        val blockBounds = floating.map { rectOf(it, relative.getValue(it.id)) }.bounds()!!
        commit(floating, blockBounds, beside(obstacles, blockBounds.w, blockBounds.h))
    }
}

/**
 * Top-left for a w x h block as close as possible to being centred on (cx, cy) while keeping [BLOCK_GAP] to
 * every obstacle. Searches square rings of a coarse grid outwards; null if nothing is found in range.
 */
private fun nearestFreeSpot(w: Double, h: Double, cx: Double, cy: Double, obstacles: List<Rect>): Point? {
    val bounds = obstacles.bounds() ?: return Point(cx - w / 2, cy - h / 2)
    val reach = max(bounds.w, bounds.h) + max(w, h) + 2 * BLOCK_GAP
    if (!reach.isFinite()) return null
    val step = max(40.0, reach / 80)
    val rings = ceil(reach / step).toInt() + 2
    val x0 = cx - w / 2
    val y0 = cy - h / 2
    fun isFree(x: Double, y: Double): Boolean {
        val r = Rect(x, y, w, h).inflate(BLOCK_GAP)
        return obstacles.none { it.overlaps(r) }
    }
    for (ring in 0..rings) {
        var best: Point? = null
        var bestD = Double.MAX_VALUE
        fun consider(i: Int, j: Int) {
            val d = (i.toDouble() * i + j.toDouble() * j)
            if (d >= bestD) return // strict: first candidate in iteration order wins ties => deterministic
            val x = x0 + i * step
            val y = y0 + j * step
            if (isFree(x, y)) { best = Point(x, y); bestD = d }
        }
        if (ring == 0) {
            consider(0, 0)
        } else {
            for (i in -ring..ring) { consider(i, -ring); consider(i, ring) }
            for (j in -ring + 1 until ring) { consider(-ring, j); consider(ring, j) }
        }
        best?.let { return it }
    }
    return null
}

/** Top-left for a w x h block right of or below the obstacles' bounding box, whichever is closer to [TARGET_ASPECT_RATIO]. */
private fun beside(obstacles: List<Rect>, w: Double, h: Double): Point {
    val b = obstacles.bounds() ?: return Point(0.0, 0.0)
    fun badness(width: Double, height: Double): Double =
        if (width <= 0.0 || height <= 0.0) Double.MAX_VALUE else abs(ln(width / height / TARGET_ASPECT_RATIO))
    val right = badness(b.w + BLOCK_GAP + w, max(b.h, h))
    val below = badness(max(b.w, w), b.h + BLOCK_GAP + h)
    return if (right <= below) Point(b.right + BLOCK_GAP, b.y) else Point(b.x, b.bottom + BLOCK_GAP)
}

/**
 * Safety net: any free node that overlaps a fixed node or an earlier free node (should not happen) is moved
 * into a column right of everything.
 */
private fun resolveOverlaps(clean: CleanRequest, free: List<LayoutNode>, placed: MutableMap<String, Point>) {
    val nodeById = clean.nodes.associateBy { it.id }
    val accepted = ArrayList<Rect>(clean.nodes.size)
    for ((id, p) in clean.fixed) accepted += rectOf(nodeById.getValue(id), p)
    val offenders = ArrayList<LayoutNode>()
    for (n in free) {
        val r = rectOf(n, placed.getValue(n.id))
        val finite = r.x.isFinite() && r.y.isFinite()
        if (!finite || (r.w > 0 && r.h > 0 && accepted.any { it.w > 0 && it.h > 0 && it.overlaps(r) })) {
            offenders += n
        } else {
            accepted += r
        }
    }
    if (offenders.isEmpty()) return
    val all = (accepted + offenders.map { rectOf(it, placed[it.id]?.takeIf { p -> p.x.isFinite() && p.y.isFinite() } ?: Point(0.0, 0.0)) })
    val b = all.bounds()!!
    val x = b.right + BLOCK_GAP
    var y = b.y
    for (n in offenders) {
        placed[n.id] = Point(x, y)
        y += n.size.height + BLOCK_GAP
    }
}
