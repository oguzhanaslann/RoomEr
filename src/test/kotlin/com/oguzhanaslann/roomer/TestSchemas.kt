package com.oguzhanaslann.roomer

import com.oguzhanaslann.roomer.layout.LayoutEngine
import com.oguzhanaslann.roomer.layout.LayoutRequest
import com.oguzhanaslann.roomer.layout.LayoutResult
import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.layout.Size
import com.oguzhanaslann.roomer.toolwindow.NodeMeasurer
import java.util.concurrent.CopyOnWriteArrayList

/** Minimal Room schema JSON (formatVersion 1) in the shape Room writes it. */
object TestSchemas {
    fun schema(version: Int, vararg tables: String, formatVersion: Int = 1): String {
        val entities = tables.joinToString(",\n") { table ->
            """
            {
              "tableName": "$table",
              "createSql": "CREATE TABLE IF NOT EXISTS `${'$'}{TABLE_NAME}` (`id` INTEGER NOT NULL, `name` TEXT, PRIMARY KEY(`id`))",
              "fields": [
                {"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
                {"fieldPath": "name", "columnName": "name", "affinity": "TEXT", "notNull": false}
              ],
              "primaryKey": {"autoGenerate": false, "columnNames": ["id"]},
              "indices": [],
              "foreignKeys": []
            }
            """.trimIndent()
        }
        return """
        {
          "formatVersion": $formatVersion,
          "database": {
            "version": $version,
            "identityHash": "0123456789abcdef",
            "entities": [
        $entities
            ],
            "views": [],
            "setupQueries": []
          }
        }
        """.trimIndent()
    }
}

/** Test double for the layout engine (AC-10.2): records requests, places free nodes on a diagonal. */
class RecordingLayoutEngine : LayoutEngine {
    val requests = CopyOnWriteArrayList<LayoutRequest>()

    override fun layout(request: LayoutRequest): LayoutResult {
        requests += request
        return LayoutResult(request.nodes.mapIndexed { index, node ->
            node.id to (request.fixedPositions[node.id] ?: Point(1000.0 + 100 * index, 500.0 + 100 * index))
        }.toMap())
    }
}

val FixedSizeMeasurer = NodeMeasurer { database, _ -> database.entities.associate { it.name to Size(120.0, 60.0) } }
