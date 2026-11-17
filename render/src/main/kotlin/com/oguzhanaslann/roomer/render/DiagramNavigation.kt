package com.oguzhanaslann.roomer.render

import com.oguzhanaslann.roomer.inference.Relationship
import kotlin.math.abs

/** Pure selection/navigation logic (focus set, arrow-key target). No Swing; unit-tested. */
internal object DiagramNavigation {

    enum class Direction(val dx: Int, val dy: Int) { LEFT(-1, 0), RIGHT(1, 0), UP(0, -1), DOWN(0, 1) }

    /** Centre of a node in diagram space. */
    class Spot(val name: String, val cx: Double, val cy: Double)

    /** [selected] plus every table connected to it by a foreign key in either direction (FR-31). */
    fun focusSet(selected: String, relationships: List<Relationship>): Set<String> {
        val set = LinkedHashSet<String>()
        set += selected
        for (r in relationships) {
            if (r.childTable == selected) set += r.parentTable
            if (r.parentTable == selected) set += r.childTable
        }
        return set
    }

    /**
     * Arrow-key target: the FK neighbour of [current] that lies best in [direction] (inside a cone of about
     * ±63° around it); if there is none, the geometrically nearest node in that direction, so every node
     * stays reachable. Null if nothing lies in that direction.
     */
    fun next(current: Spot, direction: Direction, all: List<Spot>, neighbours: Set<String>): Spot? {
        var best: Spot? = null
        var bestScore = Double.MAX_VALUE
        for (s in all) {
            if (s.name == current.name || s.name !in neighbours) continue
            val score = score(current, s, direction, cone = 2.0)
            if (score < bestScore) { bestScore = score; best = s }
        }
        if (best != null) return best
        for (s in all) {
            if (s.name == current.name) continue
            val score = score(current, s, direction, cone = Double.MAX_VALUE)
            if (score < bestScore) { bestScore = score; best = s }
        }
        return best
    }

    /** Lower is better; MAX_VALUE = not in that direction. Sideways offset counts double. */
    private fun score(from: Spot, to: Spot, d: Direction, cone: Double): Double {
        val vx = to.cx - from.cx
        val vy = to.cy - from.cy
        val along = vx * d.dx + vy * d.dy
        val across = abs(vx * d.dy + vy * d.dx)
        if (along <= 0.0) return Double.MAX_VALUE
        if (cone != Double.MAX_VALUE && across > along * cone) return Double.MAX_VALUE
        return along + 2.0 * across
    }

    /** Node whose centre is closest to ([px], [py]). */
    fun nearest(all: List<Spot>, px: Double, py: Double): Spot? {
        var best: Spot? = null
        var bestD = Double.MAX_VALUE
        for (s in all) {
            val d = (s.cx - px) * (s.cx - px) + (s.cy - py) * (s.cy - py)
            if (d < bestD) { bestD = d; best = s }
        }
        return best
    }
}
