package com.oguzhanaslann.roomer.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridLayoutEngineTest {
    private val engine = GridLayoutEngine()

    @Test fun `grid has no overlaps, starts at the origin and is roughly screen shaped`() {
        val request = randomGraph(37, 60, 2)
        val result = engine.layout(request)
        assertNoOverlap(request, result, minGap = 79.9)
        val b = bounds(request, result)
        assertEquals(0.0, b[0], 0.0); assertEquals(0.0, b[1], 0.0)
        assertTrue((b[2] - b[0]) / (b[3] - b[1]) in 0.8..3.2)
    }

    @Test fun `fixed nodes are kept and avoided`() {
        val fixed = mapOf("t0" to Point(5.0, 5.0), "t4" to Point(400.0, 900.0))
        val request = LayoutRequest(nodes(12), listOf(LayoutEdge("t1", "t0"), LayoutEdge("t2", "t1")), fixed)
        val result = engine.layout(request)
        for ((id, p) in fixed) assertEquals(p, result.positions[id])
        assertNoOverlap(request, result, minGap = 79.9)
    }

    @Test fun `empty, single and odd input`() {
        assertEquals(emptyMap<String, Point>(), engine.layout(LayoutRequest(emptyList(), emptyList())).positions)
        assertEquals(mapOf("t0" to Point(0.0, 0.0)), engine.layout(LayoutRequest(nodes(1), emptyList())).positions)
        val odd = LayoutRequest(listOf(LayoutNode("a", Size(Double.NaN, -1.0)), LayoutNode("a", TABLE), LayoutNode("b", Size(0.0, 0.0))), listOf(LayoutEdge("a", "a")))
        assertEquals(setOf("a", "b"), GridLayoutEngine(Double.NaN).layout(odd).positions.keys)
    }
}
