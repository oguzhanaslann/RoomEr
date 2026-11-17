package com.oguzhanaslann.roomer.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.Random

internal val TABLE = Size(220.0, 260.0)

internal fun nodes(count: Int, size: (Int) -> Size = { TABLE }) = List(count) { LayoutNode("t$it", size(it)) }

internal fun chain(n: Int) = LayoutRequest(nodes(n), (1 until n).map { LayoutEdge("t$it", "t${it - 1}") })

internal fun star(n: Int) = LayoutRequest(nodes(n), (1 until n).map { LayoutEdge("t$it", "t0") })

/** Connected-ish FK-heavy graph with varied node sizes, cycles and parallel edges. */
internal fun randomGraph(nodeCount: Int, edgeCount: Int, seed: Long, size: (Random) -> Size = { r -> Size(160.0 + r.nextInt(120), 80.0 + r.nextInt(360)) }): LayoutRequest {
    val r = Random(seed)
    val ns = List(nodeCount) { LayoutNode("t$it", size(r)) }
    val es = ArrayList<LayoutEdge>()
    for (i in 1 until nodeCount) if (es.size < edgeCount) es += LayoutEdge("t$i", "t${r.nextInt(i)}")
    while (es.size < edgeCount) es += LayoutEdge("t${r.nextInt(nodeCount)}", "t${r.nextInt(nodeCount)}")
    return LayoutRequest(ns, es)
}

internal fun assertComplete(request: LayoutRequest, result: LayoutResult) {
    assertEquals(request.nodes.map { it.id }.toSet(), result.positions.keys)
    for ((id, p) in result.positions) assertTrue("non-finite position for $id: $p", p.x.isFinite() && p.y.isFinite())
}

internal fun assertNoOverlap(request: LayoutRequest, result: LayoutResult, minGap: Double = 0.0) {
    assertComplete(request, result)
    val ns = request.nodes.distinctBy { it.id }
    for (i in ns.indices) for (j in i + 1 until ns.size) {
        val a = ns[i]; val b = ns[j]
        if (a.id in request.fixedPositions && b.id in request.fixedPositions) continue // caller's business
        val pa = result.positions.getValue(a.id); val pb = result.positions.getValue(b.id)
        val apart = pa.x + a.size.width + minGap <= pb.x || pb.x + b.size.width + minGap <= pa.x ||
            pa.y + a.size.height + minGap <= pb.y || pb.y + b.size.height + minGap <= pa.y
        if (!apart) fail("${a.id} at $pa ${a.size} overlaps (gap<$minGap) ${b.id} at $pb ${b.size}")
    }
}

internal fun bounds(request: LayoutRequest, result: LayoutResult): DoubleArray {
    val size = request.nodes.associate { it.id to it.size }
    val minX = result.positions.values.minOf { it.x }
    val minY = result.positions.values.minOf { it.y }
    val maxX = result.positions.maxOf { (id, p) -> p.x + size.getValue(id).width }
    val maxY = result.positions.maxOf { (id, p) -> p.y + size.getValue(id).height }
    return doubleArrayOf(minX, minY, maxX, maxY)
}

internal fun ElkLayoutEngine.layoutChecked(request: LayoutRequest): LayoutResult {
    val result = layout(request)
    assertNull("ELK failed and the grid fallback was used: $lastError", lastError)
    return result
}
