package com.oguzhanaslann.roomer.layout

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * NFR-01 / AC-21.1. Runs in its own JVM (forkEvery = 1 in build.gradle.kts), so the first call measures a real
 * cold start: ELK + EMF + Guava class loading, meta data registration and unJITted code.
 */
class ElkLayoutPerformanceTest {

    private fun schema(seed: Long) = randomGraph(100, 150, seed) { r: Random -> Size(200.0 + r.nextInt(40), 240.0 + r.nextInt(40)) }

    @Test fun `100 tables and 150 foreign keys are laid out within budget, cold and warm`() {
        val engine = ElkLayoutEngine()

        val coldRequest = schema(1)
        val t0 = System.nanoTime()
        val cold = engine.layoutChecked(coldRequest)
        val coldMs = (System.nanoTime() - t0) / 1e6
        assertNoOverlap(coldRequest, cold, minGap = 60.0)

        repeat(10) { engine.layoutChecked(schema(100L + it)) } // warm-up

        val warm = (0 until 10).map {
            val request = schema(200L + it)
            val t = System.nanoTime()
            val result = engine.layoutChecked(request)
            val ms = (System.nanoTime() - t) / 1e6
            assertNoOverlap(request, result, minGap = 60.0)
            ms
        }.sorted()

        // Incremental reload: 90 tables fixed, 10 new ones.
        val fixed = cold.positions.filterKeys { it.removePrefix("t").toInt() < 90 }
        val t1 = System.nanoTime()
        val incremental = engine.layoutChecked(coldRequest.copy(fixedPositions = fixed))
        val incrementalMs = (System.nanoTime() - t1) / 1e6
        assertNoOverlap(coldRequest, incremental, minGap = 60.0)

        val b = bounds(coldRequest, cold)
        println("ELK-PERF cold first call: %.0f ms; warm median: %.1f ms, max: %.1f ms; incremental (90 fixed + 10 new): %.1f ms; bounds %.0f x %.0f"
            .format(coldMs, warm[warm.size / 2], warm.last(), incrementalMs, b[2] - b[0], b[3] - b[1]))

        assertTrue("warm layout took ${warm.last()} ms", warm.last() < 1000.0)
        assertTrue("cold layout took $coldMs ms", coldMs < 2000.0)
    }
}
