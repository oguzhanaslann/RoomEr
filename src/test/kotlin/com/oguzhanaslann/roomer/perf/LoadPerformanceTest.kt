package com.oguzhanaslann.roomer.perf

import com.oguzhanaslann.roomer.layout.ElkLayoutEngine
import com.oguzhanaslann.roomer.layout.LayoutEdge
import com.oguzhanaslann.roomer.layout.LayoutNode
import com.oguzhanaslann.roomer.layout.LayoutRequest
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ParseResult
import com.oguzhanaslann.roomer.parser.SchemaParser
import com.oguzhanaslann.roomer.render.DiagramMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * NFR-01 / AC-21.1: a 100-table / 1000-column schema is parsed and laid out in under 2 s.
 *
 * Runs the same three stages as the tool window's loading pipeline, end to end and with the real
 * implementations: JSON → [SchemaParser] → [DiagramMetrics] node sizes → [ElkLayoutEngine].
 * The first iteration includes class loading of Gson, ELK, EMF and Guava (cold start), unless another
 * test in the same JVM loaded them already, so it is an upper bound only when this test runs first.
 */
class LoadPerformanceTest {

    @Test
    fun `100 tables and 1000 columns are parsed and laid out in under 2 seconds`() {
        val json = syntheticSchema(tables = 100, columnsPerTable = 10, seed = 7)
        val engine = ElkLayoutEngine()

        val timings = (1..5).map { iteration ->
            val start = System.nanoTime()
            val database = parse(json)
            val sizes = DiagramMetrics.measure(database)
            val result = engine.layout(
                LayoutRequest(
                    nodes = database.entities.map { LayoutNode(it.name, sizes.getValue(it.name)) },
                    edges = database.tables.flatMap { t -> t.foreignKeys.map { LayoutEdge(t.name, it.referencedTable) } },
                ),
            )
            val millis = (System.nanoTime() - start) / 1_000_000
            if (iteration == 1) {
                assertEquals(100, database.tables.size)
                assertEquals(1000, database.tables.sumOf { it.columns.size })
                assertEquals(100, result.positions.size)
                assertEquals("ELK must not have fallen back to the grid", null, engine.lastError)
            }
            millis
        }

        println("[perf] parse + measure + layout, 100 tables / 1000 columns: first ${timings.first()} ms, " +
            "then ${timings.drop(1).joinToString(", ")} ms")
        assertTrue("first load took ${timings.first()} ms (budget 2000 ms)", timings.first() < 2000)
        assertTrue("warm load took ${timings.drop(1).max()} ms (budget 2000 ms)", timings.drop(1).max() < 2000)
    }

    private fun parse(json: String): Database =
        when (val result = SchemaParser.parse(json, "com.acme.BigDb", "synthetic.json")) {
            is ParseResult.Success -> result.database
            is ParseResult.Failure -> throw AssertionError("synthetic schema does not parse: ${result.error}")
        }

    /**
     * Room-shaped JSON: every table has an `id` primary key and [columnsPerTable] columns in total; most tables
     * have one or two foreign keys to earlier tables (indexed, as Room recommends), giving about 150 edges.
     */
    private fun syntheticSchema(tables: Int, columnsPerTable: Int, seed: Long): String {
        val random = Random(seed)
        val entities = (0 until tables).joinToString(",\n") { t ->
            val parents = if (t == 0) emptyList() else List(if (random.nextInt(3) == 0) 1 else 2) { random.nextInt(t) }.distinct()
            val fkColumns = parents.mapIndexed { i, p -> "table_${p}_id_$i" to p }
            val plainCount = columnsPerTable - 1 - fkColumns.size
            val fields = buildList {
                add("""{"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true}""")
                fkColumns.forEach { (c, _) -> add("""{"fieldPath": "$c", "columnName": "$c", "affinity": "INTEGER", "notNull": ${random.nextBoolean()}}""") }
                repeat(plainCount) { c ->
                    val affinity = listOf("TEXT", "INTEGER", "REAL", "BLOB")[c % 4]
                    add("""{"fieldPath": "col_$c", "columnName": "col_$c", "affinity": "$affinity", "notNull": ${c % 2 == 0}}""")
                }
            }
            val indices = fkColumns.map { (c, _) ->
                """{"name": "index_table_${t}_$c", "unique": false, "columnNames": ["$c"], "orders": [], "createSql": ""}"""
            }
            val foreignKeys = fkColumns.map { (c, p) ->
                """{"table": "table_$p", "onDelete": "CASCADE", "onUpdate": "NO ACTION", "columns": ["$c"], "referencedColumns": ["id"]}"""
            }
            """
            {
              "tableName": "table_$t",
              "createSql": "CREATE TABLE IF NOT EXISTS `${'$'}{TABLE_NAME}` (...)",
              "fields": [${fields.joinToString(", ")}],
              "primaryKey": {"autoGenerate": true, "columnNames": ["id"]},
              "indices": [${indices.joinToString(", ")}],
              "foreignKeys": [${foreignKeys.joinToString(", ")}]
            }
            """.trimIndent()
        }
        return """{"formatVersion": 1, "database": {"version": 1, "identityHash": "x", "entities": [$entities], "views": [], "setupQueries": []}}"""
    }
}
