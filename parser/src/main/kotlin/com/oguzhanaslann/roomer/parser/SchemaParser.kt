package com.oguzhanaslann.roomer.parser

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.oguzhanaslann.roomer.model.Column
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ForeignKey
import com.oguzhanaslann.roomer.model.FtsInfo
import com.oguzhanaslann.roomer.model.Index
import com.oguzhanaslann.roomer.model.ParseResult
import com.oguzhanaslann.roomer.model.PrimaryKey
import com.oguzhanaslann.roomer.model.SchemaError
import com.oguzhanaslann.roomer.model.Table
import com.oguzhanaslann.roomer.model.View
import java.io.StringReader

/** Parses Room exported schema JSON (`formatVersion` 1) into the immutable model. Pure, thread-safe. */
object SchemaParser {
    /**
     * @param json content of `<n>.json`
     * @param databaseName name of the folder containing the file (normally the FQN of the `@Database` class);
     *   the JSON itself does not carry the database name
     * @param sourcePath path used in errors and stored in `Database.sourcePath`
     * Never throws for bad input: all problems are returned as [ParseResult.Failure].
     */
    fun parse(json: String, databaseName: String, sourcePath: String): ParseResult =
        try {
            ParseResult.Success(Extractor(sourcePath).database(readTree(json, sourcePath), databaseName))
        } catch (e: SchemaFailure) {
            ParseResult.Failure(e.error)
        } catch (e: Exception) {
            ParseResult.Failure(SchemaError.MalformedJson(sourcePath, "Unexpected error while reading the schema: ${describe(e)}"))
        } catch (e: StackOverflowError) {
            ParseResult.Failure(SchemaError.MalformedJson(sourcePath, "JSON is nested too deeply."))
        }

    // ---------------------------------------------------------------------------------------------
    // JSON syntax
    // ---------------------------------------------------------------------------------------------

    private fun readTree(json: String, path: String): JsonObject {
        if (json.isBlank()) throw SchemaFailure(SchemaError.MalformedJson(path, "File is empty."))
        val root: JsonElement = try {
            val reader = JsonReader(StringReader(json))
            reader.setStrictness(Strictness.STRICT)
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw SchemaFailure(SchemaError.MalformedJson(path, "Unexpected content after the end of the JSON document."))
            }
            element
        } catch (e: SchemaFailure) {
            throw e
        } catch (e: Exception) {
            throw SchemaFailure(SchemaError.MalformedJson(path, "Invalid JSON: ${describe(e)}"))
        }
        if (!root.isJsonObject) {
            throw SchemaFailure(SchemaError.MalformedJson(path, "Expected a JSON object at the top level but found ${typeName(root)}."))
        }
        return root.asJsonObject
    }

    /** Gson message without exception class prefixes and without the trailing troubleshooting link. */
    private fun describe(e: Throwable): String {
        var message = generateSequence(e) { it.cause }.mapNotNull { it.message }.firstOrNull { it.isNotBlank() }
            ?: return e.javaClass.simpleName
        // "com.google.gson.stream.MalformedJsonException: Unterminated object at line 3 column 5 path $.a"
        message = message.replace(Regex("^(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:Exception|Error): "), "")
        message = message.substringBefore("\nSee https://").substringBefore(" See https://").trim()
        return if (message.endsWith('.')) message else "$message."
    }

    private fun typeName(element: JsonElement?): String = when {
        element == null -> "nothing"
        element.isJsonNull -> "null"
        element.isJsonObject -> "an object"
        element.isJsonArray -> "an array"
        element.asJsonPrimitive.isBoolean -> "a boolean"
        element.asJsonPrimitive.isNumber -> "a number"
        else -> "a string"
    }

    private class SchemaFailure(val error: SchemaError) : RuntimeException(null, null, false, false)

    // ---------------------------------------------------------------------------------------------
    // Extraction
    // ---------------------------------------------------------------------------------------------

    private class Extractor(private val path: String) {

        fun database(root: JsonObject, databaseName: String): Database {
            checkFormatVersion(root)
            val db = requiredObject(root, "database", "database")
            val version = requiredInt(db, "version", "database.version")
            val identityHash = optionalString(db, "identityHash", "database.identityHash")

            val tables = requiredArray(db, "entities", "database.entities").mapIndexed { i, element ->
                val entityPath = "database.entities[$i]"
                table(asObject(element, entityPath), entityPath)
            }
            val views = optionalArray(db, "views", "database.views").mapIndexed { i, element ->
                val viewPath = "database.views[$i]"
                view(asObject(element, viewPath), viewPath)
            }
            validateReferences(tables)
            return Database(
                name = databaseName,
                version = version,
                identityHash = identityHash,
                tables = tables,
                views = views,
                sourcePath = path,
            )
        }

        private fun checkFormatVersion(root: JsonObject) {
            val element = root.get("formatVersion")
            if (element == null || element.isJsonNull) throw missing("formatVersion", element, "a number")
            val supported = SchemaError.SUPPORTED_FORMAT_VERSION
            val ok = element.isJsonPrimitive && element.asJsonPrimitive.isNumber &&
                runCatching { element.asBigDecimal.compareTo(supported.toBigDecimal()) == 0 }.getOrDefault(false)
            if (!ok) {
                val raw = if (element.isJsonPrimitive) element.asString else element.toString()
                val found = if (raw.length > MAX_FOUND_LENGTH) raw.take(MAX_FOUND_LENGTH) + "…" else raw
                throw SchemaFailure(SchemaError.UnsupportedFormat(path, found))
            }
        }

        private fun table(obj: JsonObject, p: String): Table {
            val name = requiredString(obj, "tableName", "$p.tableName")
            val columns = requiredArray(obj, "fields", "$p.fields").mapIndexed { i, element ->
                val fp = "$p.fields[$i]"
                val field = asObject(element, fp)
                Column(
                    name = requiredString(field, "columnName", "$fp.columnName"),
                    type = requiredString(field, "affinity", "$fp.affinity"),
                    notNull = optionalBoolean(field, "notNull", "$fp.notNull", default = false),
                    defaultValue = optionalString(field, "defaultValue", "$fp.defaultValue"),
                )
            }

            val pkObj = optionalObject(obj, "primaryKey", "$p.primaryKey")
            val primaryKey = if (pkObj == null) {
                PrimaryKey(emptyList(), false)
            } else {
                PrimaryKey(
                    columns = optionalStringArray(pkObj, "columnNames", "$p.primaryKey.columnNames"),
                    autoGenerate = optionalBoolean(pkObj, "autoGenerate", "$p.primaryKey.autoGenerate", default = false),
                )
            }

            val indices = optionalArray(obj, "indices", "$p.indices").mapIndexed { i, element ->
                val ip = "$p.indices[$i]"
                val index = asObject(element, ip)
                Index(
                    name = requiredString(index, "name", "$ip.name"),
                    unique = optionalBoolean(index, "unique", "$ip.unique", default = false),
                    columns = requiredStringArray(index, "columnNames", "$ip.columnNames"),
                )
            }

            val foreignKeys = optionalArray(obj, "foreignKeys", "$p.foreignKeys").mapIndexed { i, element ->
                val kp = "$p.foreignKeys[$i]"
                val fk = asObject(element, kp)
                ForeignKey(
                    referencedTable = requiredString(fk, "table", "$kp.table"),
                    columns = requiredStringArray(fk, "columns", "$kp.columns"),
                    referencedColumns = requiredStringArray(fk, "referencedColumns", "$kp.referencedColumns"),
                    onDelete = optionalString(fk, "onDelete", "$kp.onDelete") ?: NO_ACTION,
                    onUpdate = optionalString(fk, "onUpdate", "$kp.onUpdate") ?: NO_ACTION,
                )
            }

            return Table(
                name = name,
                columns = columns,
                primaryKey = primaryKey,
                foreignKeys = foreignKeys,
                indices = indices,
                fts = fts(obj, p),
            )
        }

        private fun fts(obj: JsonObject, p: String): FtsInfo? {
            val version = optionalString(obj, "ftsVersion", "$p.ftsVersion")
            val options = optionalObject(obj, "ftsOptions", "$p.ftsOptions")
            if (version == null && options == null) return null
            val contentTable = options
                ?.let { optionalString(it, "contentTable", "$p.ftsOptions.contentTable") }
                ?.takeIf { it.isNotBlank() }
            return FtsInfo(version = version?.takeIf { it.isNotBlank() } ?: DEFAULT_FTS_VERSION, contentTable = contentTable)
        }

        private fun view(obj: JsonObject, p: String): View {
            val name = requiredString(obj, "viewName", "$p.viewName")
            val sql = (optionalString(obj, "createSql", "$p.createSql") ?: "")
                .replace("\${VIEW_NAME}", name)
                .replace("\${TABLE_NAME}", name)
            return View(name = name, sql = sql)
        }

        // --- reference validation ----------------------------------------------------------------

        private fun validateReferences(tables: List<Table>) {
            val columnsByTable = HashMap<String, Set<String>>(tables.size * 2)
            for (t in tables) columnsByTable.putIfAbsent(t.name, t.columns.mapTo(HashSet()) { it.name })

            for (t in tables) {
                val own = columnsByTable.getValue(t.name)
                for (c in t.primaryKey.columns) {
                    if (c in own) continue
                    if (t.fts != null && c.equals("rowid", ignoreCase = true)) continue
                    throw unknown("Primary key of table '${t.name}' refers to unknown column '$c'.")
                }
                for (index in t.indices) {
                    for (c in index.columns) {
                        if (c !in own) throw unknown("Index '${index.name}' on table '${t.name}' refers to unknown column '$c'.")
                    }
                }
                for (fk in t.foreignKeys) {
                    val parent = columnsByTable[fk.referencedTable]
                        ?: throw unknown("Foreign key in table '${t.name}' refers to unknown table '${fk.referencedTable}'.")
                    for (c in fk.columns) {
                        if (c !in own) {
                            throw unknown("Foreign key in table '${t.name}' (to '${fk.referencedTable}') refers to unknown column '$c'.")
                        }
                    }
                    for (c in fk.referencedColumns) {
                        if (c !in parent) {
                            throw unknown(
                                "Foreign key in table '${t.name}' refers to unknown column '$c' of table '${fk.referencedTable}'.",
                            )
                        }
                    }
                    if (fk.columns.size != fk.referencedColumns.size) {
                        throw unknown(
                            "Foreign key in table '${t.name}' (to '${fk.referencedTable}') has ${fk.columns.size} column(s) " +
                                "but ${fk.referencedColumns.size} referenced column(s).",
                        )
                    }
                }
            }
        }

        private fun unknown(reason: String) = SchemaFailure(SchemaError.UnknownReference(path, reason))

        // --- typed accessors ---------------------------------------------------------------------

        private fun missing(fieldPath: String, found: JsonElement?, expected: String): SchemaFailure {
            val reason = if (found == null || found.isJsonNull) {
                "Required field '$fieldPath' is missing."
            } else {
                "Field '$fieldPath' should be $expected but is ${typeName(found)}."
            }
            return SchemaFailure(SchemaError.MissingField(path, fieldPath, reason))
        }

        private fun present(obj: JsonObject, key: String): JsonElement? = obj.get(key)?.takeUnless { it.isJsonNull }

        private fun asObject(element: JsonElement?, p: String): JsonObject =
            if (element != null && element.isJsonObject) element.asJsonObject else throw missing(p, element, "an object")

        private fun requiredObject(obj: JsonObject, key: String, p: String): JsonObject = asObject(obj.get(key), p)

        private fun optionalObject(obj: JsonObject, key: String, p: String): JsonObject? =
            present(obj, key)?.let { asObject(it, p) }

        private fun requiredArray(obj: JsonObject, key: String, p: String): JsonArray {
            val e = obj.get(key)
            return if (e != null && e.isJsonArray) e.asJsonArray else throw missing(p, e, "an array")
        }

        private fun optionalArray(obj: JsonObject, key: String, p: String): Iterable<JsonElement> =
            if (present(obj, key) == null) emptyList() else requiredArray(obj, key, p)

        private fun string(e: JsonElement?, p: String): String =
            if (e != null && e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else throw missing(p, e, "a string")

        private fun requiredString(obj: JsonObject, key: String, p: String): String = string(obj.get(key), p)

        private fun optionalString(obj: JsonObject, key: String, p: String): String? =
            present(obj, key)?.let { string(it, p) }

        private fun requiredStringArray(obj: JsonObject, key: String, p: String): List<String> =
            requiredArray(obj, key, p).mapIndexed { i, e -> string(e, "$p[$i]") }

        private fun optionalStringArray(obj: JsonObject, key: String, p: String): List<String> =
            if (present(obj, key) == null) emptyList() else requiredStringArray(obj, key, p)

        private fun optionalBoolean(obj: JsonObject, key: String, p: String, default: Boolean): Boolean {
            val e = present(obj, key) ?: return default
            return if (e.isJsonPrimitive && e.asJsonPrimitive.isBoolean) e.asBoolean else throw missing(p, e, "a boolean")
        }

        private fun requiredInt(obj: JsonObject, key: String, p: String): Int {
            val e = obj.get(key)
            if (e != null && e.isJsonPrimitive && e.asJsonPrimitive.isNumber) {
                runCatching { e.asBigDecimal.intValueExact() }.getOrNull()?.let { return it }
            }
            throw missing(p, e, "an integer")
        }
    }

    private const val NO_ACTION = "NO ACTION"
    private const val DEFAULT_FTS_VERSION = "FTS4"
    private const val MAX_FOUND_LENGTH = 40
}
