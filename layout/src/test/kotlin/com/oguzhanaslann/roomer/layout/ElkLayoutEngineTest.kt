package com.oguzhanaslann.roomer.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLClassLoader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.hypot

class ElkLayoutEngineTest {
    private val engine = ElkLayoutEngine()

    // ---- no overlap (AC-10.1) ----

    @Test fun `chain has no overlaps and is layered parent-first`() {
        val request = chain(8)
        val result = engine.layoutChecked(request)
        assertNoOverlap(request, result, minGap = 60.0)
        // t0 is the root parent, every child sits in a later (more right) layer.
        for (i in 1 until 8) assertTrue(result.positions.getValue("t$i").x > result.positions.getValue("t${i - 1}").x)
    }

    @Test fun `star has no overlaps`() {
        val request = star(25)
        assertNoOverlap(request, engine.layoutChecked(request), minGap = 60.0)
    }

    @Test fun `FK-heavy random graphs have no overlaps`() {
        for (seed in 1L..8L) {
            val request = randomGraph(40, 90, seed)
            assertNoOverlap(request, engine.layoutChecked(request), minGap = 60.0)
        }
    }

    @Test fun `disconnected components are packed, not put in one endless row or column`() {
        val ns = nodes(48)
        val edges = (0 until 12).map { LayoutEdge("t${it * 2 + 1}", "t${it * 2}") } // 12 pairs + 24 isolated tables
        val request = LayoutRequest(ns, edges)
        val result = engine.layoutChecked(request)
        assertNoOverlap(request, result, minGap = 60.0)
        val b = bounds(request, result)
        val aspect = (b[2] - b[0]) / (b[3] - b[1])
        assertTrue("aspect ratio $aspect", aspect in 0.5..4.0)
    }

    @Test fun `only isolated nodes are packed sensibly`() {
        val request = LayoutRequest(nodes(30), emptyList())
        val result = engine.layoutChecked(request)
        assertNoOverlap(request, result, minGap = 60.0)
        val b = bounds(request, result)
        val aspect = (b[2] - b[0]) / (b[3] - b[1])
        assertTrue("aspect ratio $aspect", aspect in 0.5..4.0)
    }

    @Test fun `self-loops, duplicate edges, cycles and unknown endpoints are tolerated`() {
        val request = LayoutRequest(
            nodes(5),
            listOf(
                LayoutEdge("t0", "t0"), LayoutEdge("t1", "t1"),
                LayoutEdge("t1", "t0"), LayoutEdge("t1", "t0"), LayoutEdge("t0", "t1"),
                LayoutEdge("t2", "t1"), LayoutEdge("t1", "t2"),
                LayoutEdge("t3", "nope"), LayoutEdge("nope", "t3"), LayoutEdge("x", "y"),
            ),
        )
        assertNoOverlap(request, engine.layoutChecked(request), minGap = 60.0)
    }

    // ---- trivial input ----

    @Test fun `empty request gives empty result`() {
        assertEquals(emptyMap<String, Point>(), engine.layoutChecked(LayoutRequest(emptyList(), emptyList())).positions)
        assertEquals(emptyMap<String, Point>(), engine.layoutChecked(LayoutRequest(emptyList(), listOf(LayoutEdge("a", "b")), mapOf("a" to Point(1.0, 2.0)))).positions)
    }

    @Test fun `single node is placed at the origin`() {
        val result = engine.layoutChecked(LayoutRequest(nodes(1), listOf(LayoutEdge("t0", "t0"))))
        assertEquals(mapOf("t0" to Point(0.0, 0.0)), result.positions)
    }

    @Test fun `result is normalised to the origin`() {
        for (request in listOf(chain(6), star(9), randomGraph(30, 50, 3), LayoutRequest(nodes(7), emptyList()))) {
            val b = bounds(request, engine.layoutChecked(request))
            assertEquals(0.0, b[0], 1e-9)
            assertEquals(0.0, b[1], 1e-9)
        }
    }

    @Test fun `odd input never throws`() {
        val request = LayoutRequest(
            listOf(
                LayoutNode("a", Size(0.0, 0.0)), LayoutNode("b", Size(0.0, 0.0)),
                LayoutNode("c", Size(-5.0, Double.NaN)), LayoutNode("d", Size(Double.POSITIVE_INFINITY, 10.0)),
                LayoutNode("a", Size(100.0, 100.0)), // duplicate id: first wins
                LayoutNode("e", Size(100.0, 100.0)), LayoutNode("", Size(50.0, 50.0)),
            ),
            listOf(LayoutEdge("a", "b"), LayoutEdge("e", "a"), LayoutEdge("", "e")),
            mapOf("e" to Point(Double.NaN, 0.0), "ghost" to Point(0.0, 0.0)),
        )
        val result = engine.layout(request)
        assertEquals(setOf("a", "b", "c", "d", "e", ""), result.positions.keys)
        assertTrue(result.positions.values.all { it.x.isFinite() && it.y.isFinite() })
        assertEquals(6, result.positions.values.toSet().size) // even zero-sized nodes get distinct spots
    }

    // ---- fixed positions (FR-07) ----

    @Test fun `all nodes fixed are returned unchanged`() {
        val fixed = mapOf("t0" to Point(13.5, -7.25), "t1" to Point(13.5, -7.25), "t2" to Point(1e6, 3.0))
        val request = LayoutRequest(nodes(3), listOf(LayoutEdge("t1", "t0")), fixed + ("unknown" to Point(0.0, 0.0)))
        assertEquals(fixed, engine.layoutChecked(request).positions)
    }

    @Test fun `fixed positions are preserved exactly and new nodes do not overlap them`() {
        val base = randomGraph(30, 45, 11)
        val first = engine.layoutChecked(base)
        // Keep 20 of the laid-out nodes where they are (shifted to odd coordinates), re-place the rest plus 6 new tables.
        val fixed = first.positions.filterKeys { it.removePrefix("t").toInt() < 20 }.mapValues { Point(it.value.x - 1234.5, it.value.y + 77.125) }
        val extra = List(6) { LayoutNode("new$it", TABLE) }
        val request = LayoutRequest(
            base.nodes + extra,
            base.edges + listOf(LayoutEdge("new0", "t3"), LayoutEdge("new1", "new0"), LayoutEdge("t5", "new2"), LayoutEdge("new3", "new3")),
            fixed,
        )
        val result = engine.layoutChecked(request)
        for ((id, p) in fixed) assertEquals(id, p, result.positions[id])
        assertNoOverlap(request, result, minGap = 60.0)
    }

    @Test fun `a new table lands near its fixed FK neighbour`() {
        val grid = GridLayoutEngine().layout(LayoutRequest(nodes(25), emptyList())).positions
        val request = LayoutRequest(nodes(25) + LayoutNode("new", TABLE), listOf(LayoutEdge("new", "t24")), grid)
        val result = engine.layoutChecked(request)
        assertNoOverlap(request, result, minGap = 60.0)
        val n = result.positions.getValue("new"); val anchor = grid.getValue("t24"); val far = grid.getValue("t0")
        assertTrue(hypot(n.x - anchor.x, n.y - anchor.y) < hypot(n.x - far.x, n.y - far.y))
        assertTrue("too far: $n vs $anchor", hypot(n.x - anchor.x, n.y - anchor.y) < 3 * TABLE.height)
    }

    @Test fun `unrelated new tables go beside the fixed bounding box`() {
        val fixed = mapOf("t0" to Point(100.0, 100.0), "t1" to Point(500.0, 100.0))
        val request = LayoutRequest(nodes(6), listOf(LayoutEdge("t3", "t2")), fixed)
        val result = engine.layoutChecked(request)
        for ((id, p) in fixed) assertEquals(p, result.positions[id])
        assertNoOverlap(request, result, minGap = 60.0)
        for (i in 2 until 6) {
            val p = result.positions.getValue("t$i")
            assertTrue("t$i at $p is inside the fixed bounding box", p.x >= 720.0 || p.y >= 360.0)
        }
    }

    @Test fun `overlapping fixed nodes and huge coordinates do not break placement`() {
        val fixed = mapOf("t0" to Point(0.0, 0.0), "t1" to Point(10.0, 10.0), "t2" to Point(1e7, -1e7))
        val request = LayoutRequest(nodes(8), listOf(LayoutEdge("t3", "t0"), LayoutEdge("t4", "t2"), LayoutEdge("t5", "t4")), fixed)
        val result = engine.layoutChecked(request)
        for ((id, p) in fixed) assertEquals(p, result.positions[id])
        assertNoOverlap(request, result)
    }

    // ---- determinism / thread-safety ----

    @Test fun `layout is deterministic`() {
        val base = randomGraph(60, 100, 5)
        val withFixed = base.copy(fixedPositions = mapOf("t1" to Point(40.0, 40.0), "t7" to Point(900.0, 300.0), "t20" to Point(-300.0, 800.0)))
        for (request in listOf(base, withFixed)) {
            val expected = engine.layoutChecked(request)
            repeat(3) { assertEquals(expected, ElkLayoutEngine().layoutChecked(request)) }
            assertEquals(expected.positions.keys.toList(), request.nodes.map { it.id }) // request order
        }
    }

    @Test fun `concurrent layouts give the same results as sequential ones`() {
        val requests = (1L..12L).map { randomGraph(30, 50, it) }
        val expected = requests.map { engine.layoutChecked(it) }
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 48).map { i -> pool.submit(Callable { ElkLayoutEngine().layoutChecked(requests[i % requests.size]) }) }
            futures.forEachIndexed { i, f -> assertEquals(expected[i % requests.size], f.get()) }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- class loader handling ----

    @Test fun `works when the thread context class loader cannot see ELK, and restores it`() {
        val blind = URLClassLoader(emptyArray(), null)
        var failure: Throwable? = null
        var result: LayoutResult? = null
        val t = Thread {
            try {
                val e = ElkLayoutEngine()
                result = e.layout(chain(5))
                failure = e.lastError
                assertSame(blind, Thread.currentThread().contextClassLoader)
            } catch (x: Throwable) { failure = x }
        }
        t.contextClassLoader = blind
        t.start(); t.join()
        assertNull(failure)
        assertNotNull(result)
        assertNoOverlap(chain(5), result!!)
    }
}
