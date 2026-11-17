package com.oguzhanaslann.roomer.parser

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs every real Room export through the parser and [RealSchemaConsistency].
 *
 * - Always: every file bundled under `src/test/resources/fixtures/real/`.
 * - Optionally: every `*.json` below the directory named by the environment variable `ROOMER_REAL_SCHEMAS_DIR`
 *   (used for the large one-off corpus from FR-13; skipped when the variable is not set).
 */
class RealSchemaSweepTest {

    @Test
    fun `every bundled real export parses and matches its JSON`() {
        val dir = File(checkNotNull(javaClass.getResource("/fixtures/real")) { "missing fixtures/real" }.toURI())
        val checked = sweep(dir)
        assertTrue("expected bundled real fixtures, found $checked", checked >= 8)
    }

    @Test
    fun `every export in ROOMER_REAL_SCHEMAS_DIR parses and matches its JSON`() {
        val path = System.getenv("ROOMER_REAL_SCHEMAS_DIR")
        assumeTrue("ROOMER_REAL_SCHEMAS_DIR not set", !path.isNullOrBlank())
        val checked = sweep(File(path!!))
        println("RealSchemaSweepTest: $checked files checked under $path")
    }

    private fun sweep(dir: File): Int {
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sorted().toList()
        val report = files.mapNotNull { file ->
            val problems = RealSchemaConsistency.check(file)
            if (problems.isEmpty()) null else "${file.relativeTo(dir)}:\n  " + problems.joinToString("\n  ")
        }
        assertTrue("${report.size} of ${files.size} files have problems:\n" + report.joinToString("\n"), report.isEmpty())
        return files.size
    }
}
