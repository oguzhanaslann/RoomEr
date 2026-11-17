package com.oguzhanaslann.roomer.parser

/**
 * Generates a large Room-shaped schema JSON for performance tests (NFR-01).
 *
 * Every table has an auto-generated `id` primary key; every table but the first has a not-null foreign key
 * (with an index) to the previous table, so the result is one connected chain.
 */
object SyntheticSchema {

    fun generate(tables: Int = 100, columnsPerTable: Int = 10, seedName: String = "table"): String {
        require(tables >= 1 && columnsPerTable >= 2)
        val affinities = listOf("TEXT", "INTEGER", "REAL", "BLOB")
        val sb = StringBuilder(tables * columnsPerTable * 160)
        sb.append("{\n  \"formatVersion\": 1,\n  \"database\": {\n    \"version\": 1,\n")
        sb.append("    \"identityHash\": \"ffeeddccbbaa99887766554433221100\",\n    \"entities\": [\n")
        for (t in 0 until tables) {
            val name = tableName(seedName, t)
            val hasParent = t > 0
            sb.append("      {\n        \"tableName\": \"").append(name).append("\",\n")
            sb.append("        \"createSql\": \"CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)\",\n")
            sb.append("        \"fields\": [\n")
            for (c in 0 until columnsPerTable) {
                val column = when {
                    c == 0 -> "id"
                    c == 1 && hasParent -> "parent_id"
                    else -> "col_%02d".format(c)
                }
                val affinity = if (c <= 1) "INTEGER" else affinities[(t + c) % affinities.size]
                val notNull = c <= 1 || (t + c) % 3 != 0
                sb.append("          {\"fieldPath\": \"").append(column).append("\", \"columnName\": \"").append(column)
                    .append("\", \"affinity\": \"").append(affinity).append("\", \"notNull\": ").append(notNull)
                if (c > 1 && c % 5 == 0) sb.append(", \"defaultValue\": \"0\"")
                sb.append(if (c == columnsPerTable - 1) "}\n" else "},\n")
            }
            sb.append("        ],\n")
            sb.append("        \"primaryKey\": {\"autoGenerate\": true, \"columnNames\": [\"id\"]},\n")
            if (hasParent) {
                val parent = tableName(seedName, t - 1)
                sb.append("        \"indices\": [{\"name\": \"index_").append(name)
                    .append("_parent_id\", \"unique\": false, \"columnNames\": [\"parent_id\"], \"orders\": [], ")
                    .append("\"createSql\": \"CREATE INDEX IF NOT EXISTS `index_").append(name)
                    .append("_parent_id` ON `\${TABLE_NAME}` (`parent_id`)\"}],\n")
                sb.append("        \"foreignKeys\": [{\"table\": \"").append(parent)
                    .append("\", \"onDelete\": \"CASCADE\", \"onUpdate\": \"NO ACTION\", ")
                    .append("\"columns\": [\"parent_id\"], \"referencedColumns\": [\"id\"]}]\n")
            } else {
                sb.append("        \"indices\": [],\n        \"foreignKeys\": []\n")
            }
            sb.append(if (t == tables - 1) "      }\n" else "      },\n")
        }
        sb.append("    ],\n    \"views\": [],\n    \"setupQueries\": []\n  }\n}\n")
        return sb.toString()
    }

    private fun tableName(seedName: String, index: Int): String = "%s_%03d".format(seedName, index)
}
