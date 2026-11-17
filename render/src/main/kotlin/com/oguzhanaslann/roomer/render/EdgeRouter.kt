package com.oguzhanaslann.roomer.render

import com.oguzhanaslann.roomer.inference.ChildCardinality
import com.oguzhanaslann.roomer.inference.ParentCardinality
import com.oguzhanaslann.roomer.inference.Relationship
import com.oguzhanaslann.roomer.model.Column
import com.oguzhanaslann.roomer.model.Table
import com.oguzhanaslann.roomer.model.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Routes foreign-key edges: a straight horizontal stub at each end (where the crow's-foot symbol sits)
 * joined by a smooth curve. Edges attach to the left/right side of a node, at the FK column's row on the
 * child and the referenced column's row on the parent (at the header when the node is collapsed).
 */
internal object EdgeRouter {
    /** Length of the straight part at each end; the cardinality symbols fit inside it. */
    const val STUB = 26.0
    /** Vertical distance between edges that would otherwise coincide. */
    const val SPREAD = 8.0
    private const val SIDE_GAP = 2 * STUB + 8.0

    /** Vertical distance between different cardinality symbols that end at the same row and side of a node. */
    const val KIND_SPREAD = 15.0

    /** Max distance (diagram units) between the cubic path and its hit-testing polyline [EdgeModel.points]. */
    const val FLATNESS = 0.75

    /** Distance of a collapsed node's self-loop parent end (on the top border) from the node's right edge. */
    const val TOP_INSET = 22.0

    fun routeAll(edges: List<EdgeModel>) {
        if (edges.isEmpty()) return
        // 1. Sides and attachment slots.
        for (e in edges) chooseSides(e)
        // 2. Edges with the same child, parent and slots would coincide: spread them.
        //    Ends of different cardinality meeting at the same slot would overprint their symbols: spread those too,
        //    ordered by the direction the edges head off in (steeply upwards = top, steeply downwards = bottom), so
        //    edges leaving one anchor do not cross each other.
        val pairTotals = HashMap<Long, Int>()
        val ends = HashMap<Long, EndGroup>()
        for (e in edges) {
            pairTotals.merge(pairKey(e), 1, Int::plus)
            ends.getOrPut(childEndKey(e)) { EndGroup() }.add(e.childEnd, heading(e, childEnd = true))
            ends.getOrPut(parentEndKey(e)) { EndGroup() }.add(e.parentEnd, heading(e, childEnd = false))
        }
        val seen = HashMap<Long, Int>()
        for (e in edges) {
            val key = pairKey(e)
            val count = pairTotals[key] ?: 1
            val index = if (count > 1) (seen.merge(key, 1, Int::plus)!! - 1) else 0
            val aOff = ends[childEndKey(e)]?.offset(e.childEnd, e.child.height) ?: 0.0
            val bOff = if (e.bUp) 0.0 else ends[parentEndKey(e)]?.offset(e.parentEnd, e.parent.height) ?: 0.0
            build(e, index, count, aOff, bOff)
        }
    }

    /**
     * Sort key for the direction an end's edge heads off in: an angle from -π/2 (straight up) to π/2 (straight down),
     * measured from the side it leaves; loops (back to the same side) sort beyond ±π/2.
     */
    private fun heading(e: EdgeModel, childEnd: Boolean): Double {
        val n = if (childEnd) e.child else e.parent
        val dir = if (childEnd) e.aDir else e.bDir
        val y0 = anchorY(n, if (childEnd) e.childRows else e.parentRows)
        val x0 = if (dir > 0) n.x + n.width else n.x
        val o = if (childEnd) e.parent else e.child
        val (tx, ty) = when {
            !childEnd || !e.bUp -> {
                val oDir = if (childEnd) e.bDir else e.aDir
                (if (oDir > 0) o.x + o.width else o.x) to anchorY(o, if (childEnd) e.parentRows else e.childRows)
            }
            else -> (o.x + o.width - TOP_INSET) to o.y
        }
        val dy = ty - y0
        if (e.isSelf || e.aDir == e.bDir) {
            // Loops (both ends on the same side) turn back: steeper than any edge that crosses over, and they nest,
            // so the one reaching further down (up) must start higher (lower) to enclose the shorter one.
            val nest = 1.0 / (1.0 + abs(dy))
            return if (dy >= 0) PI / 2 + nest else -(PI / 2 + nest)
        }
        return atan2(dy, max(STUB, (tx - x0) * dir))
    }

    /** The different end symbols meeting at one anchor (node, side, slot), with the mean heading of their edges. */
    private class EndGroup {
        val sumY = DoubleArray(EndKind.entries.size)
        val count = IntArray(EndKind.entries.size)

        fun add(kind: EndKind, heading: Double) {
            sumY[kind.ordinal] += heading
            count[kind.ordinal]++
        }

        /** Offset of [kind]'s symbol from the anchor; 0 if it is the only kind here. Kept inside a node of [nodeHeight]. */
        fun offset(kind: EndKind, nodeHeight: Double): Double {
            var kinds = 0
            for (c in count) if (c > 0) kinds++
            if (kinds <= 1) return 0.0
            val my = sumY[kind.ordinal] / count[kind.ordinal]
            var position = 0
            for (k in count.indices) {
                if (count[k] == 0 || k == kind.ordinal) continue
                val y = sumY[k] / count[k]
                if (y < my || (y == my && k < kind.ordinal)) position++
            }
            val step = min(KIND_SPREAD, max(2 * CrowsFoot.HALF, (nodeHeight - 2 * CrowsFoot.HALF - 2) / (kinds - 1)))
            return (position - (kinds - 1) / 2.0) * step
        }
    }

    /** Routes a single edge on its own (no spreading against other edges). */
    fun route(e: EdgeModel) {
        chooseSides(e)
        build(e, 0, 1, 0.0, 0.0)
    }

    private fun slot(n: NodeModel, rows: IntArray): Int = if (!n.showsRows || rows.isEmpty()) -1 else rows[0]

    private fun pairKey(e: EdgeModel): Long =
        ((e.child.index.toLong() * 100_003L + e.parent.index) shl 24) or
            ((slot(e.child, e.childRows) + 1).toLong() shl 12) or (slot(e.parent, e.parentRows) + 1).toLong()

    private fun childEndKey(e: EdgeModel): Long = endKey(e.child, if (e.aDir > 0) 1 else 0, slot(e.child, e.childRows))
    private fun parentEndKey(e: EdgeModel): Long =
        endKey(e.parent, if (e.bUp) 2 else if (e.bDir > 0) 1 else 0, slot(e.parent, e.parentRows))

    private fun endKey(n: NodeModel, side: Int, slot: Int): Long =
        (n.index.toLong() shl 22) or ((slot + 1).toLong() shl 2) or side.toLong()

    private fun anchorY(n: NodeModel, rows: IntArray): Double {
        val s = slot(n, rows)
        return if (s < 0) n.y + n.headerH / 2 else n.rowCenterY(s)
    }

    private fun chooseSides(e: EdgeModel) {
        val c = e.child
        val p = e.parent
        e.bUp = false
        if (e.isSelf) {
            e.aDir = 1; e.bDir = 1
            // A collapsed node has no room for two different symbols plus a loop on one side: the parent end
            // moves to the top border, the loop goes around the top-right corner.
            e.bUp = slot(c, e.childRows) < 0 && slot(p, e.parentRows) < 0
        } else if (c.x + c.width + SIDE_GAP <= p.x) {
            e.aDir = 1; e.bDir = -1
        } else if (p.x + p.width + SIDE_GAP <= c.x) {
            e.aDir = -1; e.bDir = 1
        } else {
            // Horizontally overlapping nodes: leave both on the side where their borders are closest.
            val right = abs((c.x + c.width) - (p.x + p.width)) <= abs(c.x - p.x)
            e.aDir = if (right) 1 else -1
            e.bDir = e.aDir
        }
    }

    private fun clampY(n: NodeModel, y: Double): Double {
        val lo = n.y + CrowsFoot.HALF + 1
        val hi = n.y + n.height - CrowsFoot.HALF - 1
        return if (lo > hi) n.y + n.height / 2 else y.coerceIn(lo, hi)
    }

    private fun build(e: EdgeModel, indexInGroup: Int, groupSize: Int, aOffset: Double, bOffset: Double) {
        val c = e.child
        val p = e.parent
        var ay = anchorY(c, e.childRows) + aOffset
        var by = anchorY(p, e.parentRows) + bOffset
        val path = e.path
        path.reset()
        if (e.bUp) {
            // Collapsed self-loop: out of the right side, around the corner, into the top border.
            e.ax = c.x + c.width
            e.ay = clampY(c, ay)
            e.bx = p.x + p.width - TOP_INSET - indexInGroup * 14.0
            e.by = p.y
            val reach = 26.0 + indexInGroup * 10.0
            val sx0 = e.ax + STUB
            val sy1 = e.by - STUB
            path.moveTo(e.ax, e.ay)
            path.lineTo(sx0, e.ay)
            path.curveTo(sx0 + reach, e.ay, e.bx, sy1 - reach, e.bx, sy1)
            path.lineTo(e.bx, e.by)
            flatten(e)
            return
        }
        val sameSide = e.aDir == e.bDir
        if (e.isSelf) {
            if (abs(ay - by) < 13.0) {
                val mid = (ay + by) / 2
                ay = mid + 6.5; by = mid - 6.5
            }
        } else {
            val spread = (indexInGroup - (groupSize - 1) / 2.0) * SPREAD
            ay += spread; by += spread
        }
        e.ax = if (e.aDir > 0) c.x + c.width else c.x
        e.bx = if (e.bDir > 0) p.x + p.width else p.x
        e.ay = clampY(c, ay)
        e.by = clampY(p, by)
        ay = e.ay
        by = e.by

        val sx0 = e.ax + e.aDir * STUB
        val sx1 = e.bx + e.bDir * STUB
        path.moveTo(e.ax, ay)
        path.lineTo(sx0, ay)
        if (sameSide) {
            val outer = if (e.aDir > 0) max(sx0, sx1) else min(sx0, sx1)
            val reach = if (e.isSelf) 30.0 + indexInGroup * 14.0 else 30.0 + min(70.0, abs(by - ay) * 0.25) + indexInGroup * 12.0
            val cx = outer + e.aDir * reach
            path.curveTo(cx, ay, cx, by, sx1, by)
        } else {
            val d = max(24.0, abs(sx1 - sx0) / 2)
            path.curveTo(sx0 + e.aDir * d, ay, sx1 + e.bDir * d, by, sx1, by)
        }
        path.lineTo(e.bx, by)
        flatten(e)
    }

    private val coords = DoubleArray(6)

    private fun flatten(e: EdgeModel) {
        var n = 0
        var pts = e.points
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        val it = e.path.getPathIterator(null, FLATNESS)
        while (!it.isDone) {
            it.currentSegment(coords)
            if (n * 2 + 2 > pts.size) pts = pts.copyOf(max(32, pts.size * 2))
            val x = coords[0]
            val y = coords[1]
            pts[n * 2] = x; pts[n * 2 + 1] = y
            n++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            it.next()
        }
        e.points = pts
        e.pointCount = n
        // Room for the cardinality symbols, which extend sideways from the line at the ends.
        val m = CrowsFoot.HALF + 2
        e.minX = minX - m; e.maxX = maxX + m; e.minY = minY - m; e.maxY = maxY + m
    }
}

/** Tooltip texts (HTML). Built lazily / once, never during painting. */
internal object Tooltips {
    fun esc(s: String): String = buildString(s.length + 8) {
        for (ch in s) when (ch) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); else -> append(ch)
        }
    }

    fun column(t: Table, c: Column): String = buildString {
        append("<html><b>").append(esc(c.name)).append("</b> &nbsp;").append(esc(c.type))
        append(if (c.notNull) " NOT NULL" else " (nullable)")
        if (c.defaultValue != null) append("<br>DEFAULT ").append(esc(c.defaultValue!!))
        if (c.name in t.primaryKey.columns) {
            append("<br>Primary key")
            if (t.primaryKey.columns.size > 1) append(" (composite: ").append(esc(t.primaryKey.columns.joinToString(", "))).append(")")
            if (t.primaryKey.autoGenerate) append(", auto-generated")
        }
        for (fk in t.foreignKeys) {
            val i = fk.columns.indexOf(c.name)
            if (i < 0) continue
            append("<br>Foreign key → ").append(esc(fk.referencedTable))
            fk.referencedColumns.getOrNull(i)?.let { append('(').append(esc(it)).append(')') }
        }
        // Mirrors the badges: UQ = sole column of a unique index; IDX = any other index membership, including a
        // multi-column unique index (that column alone is not unique).
        for (ix in t.indices) {
            if (c.name !in ix.columns) continue
            val cols = esc(ix.columns.joinToString(", "))
            when {
                ix.unique && ix.columns.size == 1 -> append("<br>UQ: unique (unique index ").append(esc(ix.name)).append(')')
                ix.unique -> append("<br>IDX: part of composite unique index ").append(esc(ix.name)).append(" (").append(cols)
                    .append(") – unique only in combination")
                ix.columns.size == 1 -> append("<br>IDX: indexed (index ").append(esc(ix.name)).append(')')
                else -> append("<br>IDX: part of index ").append(esc(ix.name)).append(" (").append(cols).append(')')
            }
        }
        append("</html>")
    }

    fun table(t: Table): String = buildString {
        append("<html><b>").append(esc(t.name)).append("</b>")
        val fts = t.fts
        if (fts != null) {
            append(" &nbsp;").append(esc(fts.version)).append(" virtual table")
            if (fts.contentTable != null) append("<br>Content table: ").append(esc(fts.contentTable!!))
        }
        append("<br>").append(t.columns.size).append(if (t.columns.size == 1) " column" else " columns")
        if (t.primaryKey.columns.isNotEmpty()) append("<br>Primary key: ").append(esc(t.primaryKey.columns.joinToString(", ")))
        append("</html>")
    }

    fun view(v: View): String = buildString {
        append("<html><b>").append(esc(v.name)).append("</b> &nbsp;view<br><pre>")
        val sql = v.sql.trim()
        append(esc(if (sql.length > 3000) sql.substring(0, 3000) + FontSet.ELLIPSIS else sql))
        append("</pre></html>")
    }

    fun edge(r: Relationship): String = buildString {
        val fk = r.foreignKey
        append("<html><b>").append(esc(r.childTable)).append("</b>(").append(esc(fk.columns.joinToString(", ")))
        append(") → <b>").append(esc(r.parentTable)).append("</b>(").append(esc(fk.referencedColumns.joinToString(", "))).append(")")
        append("<br>ON DELETE ").append(esc(fk.onDelete))
        append("<br>ON UPDATE ").append(esc(fk.onUpdate))
        append("<br>").append(esc(r.childTable)).append(": ")
        append(if (r.childEnd == ChildCardinality.MANY) "zero or many" else "zero or one")
        append(" — ").append(esc(r.parentTable)).append(": ")
        append(if (r.parentEnd == ParentCardinality.EXACTLY_ONE) "exactly one" else "zero or one")
        append("</html>")
    }
}
