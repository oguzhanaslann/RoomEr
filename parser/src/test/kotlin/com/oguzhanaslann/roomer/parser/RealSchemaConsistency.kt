package com.oguzhanaslann.roomer.parser

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ParseResult
import java.io.File

/**
 * Cross-checks a parsed [Database] against the raw JSON it came from, independently of the parser's own
 * extraction code. Used for every real Room export (bundled fixtures and, optionally, an external directory).
 *
 * Returns a list of human-readable problems; empty means the file parsed and the model matches the JSON.
 */
object RealSchemaConsistency {

    fun check(file: File): List<String> {
        val json = file.readText(Charsets.UTF_8)
        val databaseName = file.parentFile.name
        val result = SchemaParser.parse(json, databaseName, file.path)
        if (result !is ParseResult.Success) return listOf("did not parse: ${(result as ParseResult.Failure).error}")
        return compare(JsonParser.parseString(json).asJsonObject, result.database, databaseName)
    }

    private fun compare(root: JsonObject, db: Database, databaseName: String): List<String> {
        val problems = mutableListOf<String>()
        fun expect(ok: Boolean, what: () -> String) { if (!ok) problems += what() }

        val raw = root.getAsJsonObject("database")
        expect(db.name == databaseName) { "database name ${db.name}" }
        expect(db.version == raw.get("version").asInt) { "version ${db.version}" }
        expect(db.identityHash == raw.get("identityHash")?.asString) { "identityHash ${db.identityHash}" }

        val entities = raw.getAsJsonArray("entities").map { it.asJsonObject }
        expect(db.tables.map { it.name } == entities.map { it.get("tableName").asString }) { "table names ${db.tables.map { it.name }}" }

        for ((table, e) in db.tables.zip(entities)) {
            val t = table.name
            val fields = e.getAsJsonArray("fields").map { it.asJsonObject }
            expect(table.columns.map { it.name } == fields.map { it.get("columnName").asString }) { "$t: column names" }
            for ((column, f) in table.columns.zip(fields)) {
                expect(column.type == f.get("affinity").asString) { "$t.${column.name}: affinity ${column.type}" }
                expect(column.notNull == (f.get("notNull")?.asBoolean ?: false)) { "$t.${column.name}: notNull ${column.notNull}" }
                expect(column.defaultValue == f.get("defaultValue")?.asString) { "$t.${column.name}: defaultValue ${column.defaultValue}" }
            }

            val pk = e.getAsJsonObject("primaryKey")
            expect(table.primaryKey.columns == pk?.strings("columnNames").orEmpty()) { "$t: primary key ${table.primaryKey}" }
            expect(table.primaryKey.autoGenerate == (pk?.get("autoGenerate")?.asBoolean ?: false)) { "$t: autoGenerate" }

            val indices = e.getAsJsonArray("indices")?.map { it.asJsonObject }.orEmpty()
            expect(table.indices.size == indices.size) { "$t: ${table.indices.size} indices, JSON has ${indices.size}" }
            for ((index, i) in table.indices.zip(indices)) {
                expect(index.name == i.get("name").asString && index.columns == i.strings("columnNames") &&
                    index.unique == (i.get("unique")?.asBoolean ?: false)) { "$t: index $index" }
            }

            val fks = e.getAsJsonArray("foreignKeys")?.map { it.asJsonObject }.orEmpty()
            expect(table.foreignKeys.size == fks.size) { "$t: ${table.foreignKeys.size} FKs, JSON has ${fks.size}" }
            for ((fk, k) in table.foreignKeys.zip(fks)) {
                expect(
                    fk.referencedTable == k.get("table").asString && fk.columns == k.strings("columns") &&
                        fk.referencedColumns == k.strings("referencedColumns") &&
                        fk.onDelete == k.get("onDelete").asString && fk.onUpdate == k.get("onUpdate").asString,
                ) { "$t: FK $fk" }
            }

            val ftsVersion = e.get("ftsVersion")?.asString
            val isFts = ftsVersion != null || e.has("ftsOptions")
            expect((table.fts != null) == isFts) { "$t: fts=${table.fts}, JSON ftsVersion=$ftsVersion" }
            if (table.fts != null) {
                expect(ftsVersion == null || table.fts!!.version == ftsVersion) { "$t: fts version ${table.fts}" }
                val content = e.getAsJsonObject("ftsOptions")?.get("contentTable")?.asString?.takeIf { it.isNotEmpty() }
                expect(table.fts!!.contentTable == content) { "$t: contentTable ${table.fts}" }
            }
        }

        val views = raw.getAsJsonArray("views")?.map { it.asJsonObject }.orEmpty()
        expect(db.views.map { it.name } == views.map { it.get("viewName").asString }) { "view names ${db.views.map { it.name }}" }
        for (view in db.views) {
            expect("\${" !in view.sql) { "view ${view.name}: placeholder left in SQL: ${view.sql}" }
            expect(view.sql.startsWith("CREATE VIEW `${view.name}` AS ")) { "view ${view.name}: SQL ${view.sql}" }
        }
        return problems
    }

    private fun JsonObject.strings(key: String): List<String> =
        (get(key) as? JsonArray)?.map { it.asString }.orEmpty()
}
