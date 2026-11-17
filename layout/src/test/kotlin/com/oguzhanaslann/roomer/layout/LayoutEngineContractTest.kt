package com.oguzhanaslann.roomer.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC-10.2: callers depend on [LayoutEngine] only, so a test double can replace the real engine. */
class LayoutEngineContractTest {

    /** Stand-in for production code that receives its engine by injection. */
    private class DiagramLoader(private val engine: LayoutEngine) {
        fun load(tables: List<String>): Map<String, Point> =
            engine.layout(LayoutRequest(tables.map { LayoutNode(it, TABLE) }, emptyList())).positions
    }

    @Test fun `a lambda test double can replace the engine`() {
        val seen = ArrayList<LayoutRequest>()
        val double = LayoutEngine { request ->
            seen += request
            LayoutResult(request.nodes.withIndex().associate { (i, n) -> n.id to Point(i * 1000.0, 0.0) })
        }
        val positions = DiagramLoader(double).load(listOf("user", "post"))
        assertEquals(mapOf("user" to Point(0.0, 0.0), "post" to Point(1000.0, 0.0)), positions)
        assertEquals(1, seen.size)
    }

    @Test fun `real engines are interchangeable behind the interface`() {
        for (engine in listOf<LayoutEngine>(ElkLayoutEngine(), GridLayoutEngine())) {
            val positions = DiagramLoader(engine).load(listOf("user", "post", "comment"))
            assertEquals(setOf("user", "post", "comment"), positions.keys)
        }
    }

    @Test fun `ELK failure falls back to the grid instead of throwing`() {
        // layoutWith is the shared skeleton; an arranger that throws nothing but returns garbage must still be repaired.
        val request = star(6)
        val garbage = layoutWith(request) { ns, _ -> ns.associate { it.id to Point(0.0, 0.0) } + ("t0" to Point(Double.NaN, 1.0)) - "t1" }
        assertNoOverlap(request, garbage)
        assertTrue(garbage.positions.values.all { it.x.isFinite() && it.y.isFinite() })
        assertNotNull(garbage.positions["t1"])
    }
}
