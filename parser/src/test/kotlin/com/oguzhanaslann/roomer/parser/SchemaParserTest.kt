package com.oguzhanaslann.roomer.parser

import com.oguzhanaslann.roomer.model.Column
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.EntityKind
import com.oguzhanaslann.roomer.model.ForeignKey
import com.oguzhanaslann.roomer.model.FtsInfo
import com.oguzhanaslann.roomer.model.Index
import com.oguzhanaslann.roomer.model.ParseResult
import com.oguzhanaslann.roomer.model.PrimaryKey
import com.oguzhanaslann.roomer.model.SchemaError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SchemaParserTest {

    // --- helpers ---------------------------------------------------------------------------------

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) { "missing fixture $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun parse(json: String): ParseResult = SchemaParser.parse(json, DB_NAME, PATH)

    private fun success(json: String): Database = when (val r = parse(json)) {
        is ParseResult.Success -> r.database
        is ParseResult.Failure -> throw AssertionError("expected success but got ${r.error}")
    }

    private inline fun <reified E : SchemaError> failure(json: String): E {
        val r = parse(json)
        if (r !is ParseResult.Failure) throw AssertionError("expected ${E::class.simpleName} but parsing succeeded")
        val error = r.error
        if (error !is E) throw AssertionError("expected ${E::class.simpleName} but got $error")
        assertEquals(PATH, error.filePath)
        assertTrue("reason must not be blank", error.reason.isNotBlank())
        assertFalse("reason must not repeat the file path: ${error.reason}", error.reason.contains(PATH))
        return error
    }

    private fun missingField(fixtureName: String, expectedPath: String) {
        val error = failure<SchemaError.MissingField>(fixture(fixtureName))
        assertEquals(expectedPath, error.fieldPath)
        assertTrue(error.reason, error.reason.contains(expectedPath))
    }

    /** Minimal valid document; [entities] and [extra] are raw JSON snippets. */
    private fun doc(entities: String, extra: String = ""): String =
        """{"formatVersion":1,"database":{"version":1,"entities":[$entities]$extra}}"""

    private val simpleTable =
        """{"tableName":"t","fields":[{"columnName":"id","affinity":"INTEGER","notNull":true}],"primaryKey":{"columnNames":["id"],"autoGenerate":false}}"""

    // --- fx-basic --------------------------------------------------------------------------------

    @Test
    fun `basic - database metadata`() {
        val db = success(fixture("fx-basic"))
        assertEquals(DB_NAME, db.name)
        assertEquals("AppDatabase", db.simpleName)
        assertEquals(PATH, db.sourcePath)
        assertEquals(3, db.version)
        assertEquals("5c1f8a0b6e2d4f7a9b3c0d1e2f3a4b5c", db.identityHash)
        assertEquals(listOf("users", "posts", "profiles", "categories", "post_categories"), db.tables.map { it.name })
        assertTrue(db.views.isEmpty())
        assertTrue(db.tables.all { it.kind == EntityKind.TABLE && it.fts == null })
    }

    @Test
    fun `basic - columns keep file order, affinity, nullability and default`() {
        val users = success(fixture("fx-basic")).table("users")!!
        assertEquals(
            listOf(
                Column("id", "INTEGER", true, null),
                Column("email", "TEXT", true, null),
                Column("display_name", "TEXT", false, null),
                Column("is_active", "INTEGER", true, "1"),
                Column("created_at", "INTEGER", true, "CURRENT_TIMESTAMP"),
                Column("rating", "REAL", false, null),
                Column("avatar", "BLOB", false, null),
            ),
            users.columns,
        )
        assertEquals(PrimaryKey(listOf("id"), autoGenerate = true), users.primaryKey)
    }

    @Test
    fun `basic - unique index`() {
        val users = success(fixture("fx-basic")).table("users")!!
        assertEquals(listOf(Index("index_users_email", true, listOf("email"))), users.indices)
        assertTrue(users.isUnique("email"))
        assertFalse(users.isUnique("display_name"))
    }

    @Test
    fun `basic - not-null foreign key with index`() {
        val posts = success(fixture("fx-basic")).table("posts")!!
        assertEquals(
            listOf(ForeignKey("users", listOf("author_id"), listOf("id"), onDelete = "CASCADE", onUpdate = "NO ACTION")),
            posts.foreignKeys,
        )
        assertTrue(posts.column("author_id")!!.notNull)
        assertEquals(listOf(Index("index_posts_author_id", false, listOf("author_id"))), posts.indices)
        assertTrue(posts.isIndexed("author_id"))
        assertFalse(posts.isUnique("author_id"))
    }

    @Test
    fun `basic - one-to-one table whose primary key is a foreign key`() {
        val profiles = success(fixture("fx-basic")).table("profiles")!!
        assertEquals(PrimaryKey(listOf("user_id"), autoGenerate = false), profiles.primaryKey)
        val fk = profiles.foreignKeys.single()
        assertEquals(profiles.primaryKey.columns, fk.columns)
        assertEquals("users", fk.referencedTable)
        assertEquals("CASCADE", fk.onUpdate)
        assertTrue(profiles.isPrimaryKey("user_id") && profiles.isForeignKey("user_id"))
    }

    @Test
    fun `basic - nullable self-referencing foreign key`() {
        val categories = success(fixture("fx-basic")).table("categories")!!
        val fk = categories.foreignKeys.single()
        assertEquals("categories", fk.referencedTable)
        assertEquals(listOf("parent_id"), fk.columns)
        assertEquals(listOf("id"), fk.referencedColumns)
        assertEquals("SET NULL", fk.onDelete)
        assertFalse(categories.column("parent_id")!!.notNull)
    }

    @Test
    fun `basic - composite primary key join table with two foreign keys`() {
        val join = success(fixture("fx-basic")).table("post_categories")!!
        assertEquals(PrimaryKey(listOf("post_id", "category_id"), autoGenerate = false), join.primaryKey)
        assertEquals(listOf("posts", "categories"), join.foreignKeys.map { it.referencedTable })
        assertEquals(listOf("CASCADE", "RESTRICT"), join.foreignKeys.map { it.onDelete })
        assertEquals("0", join.column("sort_order")!!.defaultValue)
    }

    // --- fx-views-fts ----------------------------------------------------------------------------

    @Test
    fun `views - name and sql with placeholder substituted`() {
        val db = success(fixture("fx-views-fts"))
        assertEquals(listOf("article_summaries", "author_stats"), db.views.map { it.name })
        val view = db.views[0]
        assertEquals(EntityKind.VIEW, view.kind)
        assertTrue(view.sql, view.sql.startsWith("CREATE VIEW `article_summaries` AS SELECT articles.id AS article_id"))
        assertTrue(db.views.none { it.sql.contains("\${") })
        assertEquals(
            listOf("authors", "articles", "articles_fts", "notes_fts", "article_summaries", "author_stats"),
            db.entities.map { it.name },
        )
    }

    @Test
    fun `fts - table with external content table and rowid primary key`() {
        val fts = success(fixture("fx-views-fts")).table("articles_fts")!!
        assertEquals(EntityKind.FTS_TABLE, fts.kind)
        assertEquals(FtsInfo("FTS4", "articles"), fts.fts)
        assertEquals(listOf("title", "body"), fts.columns.map { it.name })
        assertEquals(PrimaryKey(listOf("rowid"), autoGenerate = true), fts.primaryKey)
        assertTrue(fts.indices.isEmpty() && fts.foreignKeys.isEmpty())
    }

    @Test
    fun `fts - table without content table, primary key, indices or foreign keys`() {
        val fts = success(fixture("fx-views-fts")).table("notes_fts")!!
        assertEquals(EntityKind.FTS_TABLE, fts.kind)
        assertEquals(FtsInfo("FTS3", null), fts.fts)
        assertEquals(PrimaryKey(emptyList(), autoGenerate = false), fts.primaryKey)
        assertTrue(fts.indices.isEmpty() && fts.foreignKeys.isEmpty())
    }

    @Test
    fun `fts - regular tables in the same file are not fts`() {
        val db = success(fixture("fx-views-fts"))
        assertEquals(EntityKind.TABLE, db.table("articles")!!.kind)
        assertEquals("authors", db.table("articles")!!.foreignKeys.single().referencedTable)
    }

    // --- tolerance across Room versions ----------------------------------------------------------

    @Test
    fun `old room file without views, defaultValue, orders or autoGenerate`() {
        val db = success(fixture("fx-old-room"))
        assertTrue(db.views.isEmpty())
        assertTrue(db.tables.flatMap { it.columns }.all { it.defaultValue == null })
        val album = db.table("Album")!!
        assertEquals(PrimaryKey(listOf("albumId"), autoGenerate = false), album.primaryKey)
        assertEquals(listOf(Index("index_Album_artistId_title", true, listOf("artistId", "title"))), album.indices)
        assertEquals(ForeignKey("Artist", listOf("artistId"), listOf("artistId")), album.foreignKeys.single())
        assertTrue(db.table("Artist")!!.primaryKey.autoGenerate)
    }

    @Test
    fun `optional members may be absent or null`() {
        val db = success(
            doc(
                """{"tableName":"t","fields":[{"columnName":"id","affinity":"INTEGER"},
                   {"columnName":"n","affinity":"TEXT","notNull":null,"defaultValue":null}],
                   "primaryKey":null,"indices":null,"foreignKeys":null}""",
                ""","views":null,"identityHash":null""",
            ),
        )
        val t = db.tables.single()
        assertNull(db.identityHash)
        assertEquals(listOf(Column("id", "INTEGER", false), Column("n", "TEXT", false)), t.columns)
        assertEquals(PrimaryKey(emptyList(), false), t.primaryKey)
        assertTrue(t.indices.isEmpty() && t.foreignKeys.isEmpty() && db.views.isEmpty())
    }

    @Test
    fun `foreign key actions default to NO ACTION and index unique defaults to false`() {
        val db = success(
            doc(
                """$simpleTable,{"tableName":"c","fields":[{"columnName":"t_id","affinity":"INTEGER","notNull":true}],
                   "indices":[{"name":"i","columnNames":["t_id"]}],
                   "foreignKeys":[{"table":"t","columns":["t_id"],"referencedColumns":["id"]}]}""",
            ),
        )
        val c = db.table("c")!!
        assertEquals(ForeignKey("t", listOf("t_id"), listOf("id"), "NO ACTION", "NO ACTION"), c.foreignKeys.single())
        assertFalse(c.indices.single().unique)
    }

    @Test
    fun `unknown members from newer room versions are ignored`() {
        val db = success(
            """{"formatVersion":1,"generator":"room-99","database":{"version":4,"identityHash":"h","futureFlag":true,
               "entities":[{"tableName":"t","withoutRowId":true,"shadow":{"a":[1,2]},
                 "fields":[{"fieldPath":"id","columnName":"id","affinity":"INTEGER","notNull":true,"collate":"NOCASE","generated":{"x":1}}],
                 "primaryKey":{"columnNames":["id"],"autoGenerate":false,"conflict":"ABORT"},
                 "indices":[{"name":"i","unique":true,"columnNames":["id"],"orders":["DESC"],"where":"id > 0"}],
                 "foreignKeys":[{"table":"t","columns":["id"],"referencedColumns":["id"],"deferred":true}]}],
               "views":[{"viewName":"v","createSql":"CREATE VIEW `${'$'}{VIEW_NAME}` AS SELECT 1","materialized":false}],
               "triggers":[{"name":"x"}],"setupQueries":[]},"extra":[]}""",
        )
        assertEquals(4, db.version)
        assertEquals("CREATE VIEW `v` AS SELECT 1", db.views.single().sql)
        assertEquals(1, db.tables.single().foreignKeys.size)
    }

    @Test
    fun `view without createSql and TABLE_NAME placeholder in view sql`() {
        val db = success(
            doc(simpleTable, ""","views":[{"viewName":"a"},{"viewName":"b","createSql":"CREATE VIEW `${'$'}{TABLE_NAME}` AS SELECT 1"}]"""),
        )
        assertEquals("", db.views[0].sql)
        assertEquals("CREATE VIEW `b` AS SELECT 1", db.views[1].sql)
    }

    @Test
    fun `fts marker without options, and fts table declaring rowid column`() {
        val db = success(
            doc(
                """{"tableName":"f1","ftsVersion":"FTS4","fields":[{"columnName":"body","affinity":"TEXT","notNull":true}],
                    "primaryKey":{"columnNames":["rowid"],"autoGenerate":true}},
                   {"tableName":"f2","ftsVersion":"FTS4","ftsOptions":{"contentTable":""},
                    "fields":[{"columnName":"rowid","affinity":"INTEGER","notNull":true},{"columnName":"body","affinity":"TEXT","notNull":true}],
                    "primaryKey":{"columnNames":["rowid"],"autoGenerate":true}}""",
            ),
        )
        assertEquals(listOf(FtsInfo("FTS4", null), FtsInfo("FTS4", null)), db.tables.map { it.fts })
        assertTrue(db.tables.all { it.kind == EntityKind.FTS_TABLE })
    }

    @Test
    fun `empty entities list is a valid database`() {
        val db = success(doc(""))
        assertTrue(db.tables.isEmpty() && db.views.isEmpty() && db.entities.isEmpty())
    }

    // --- malformed JSON --------------------------------------------------------------------------

    @Test
    fun `empty file is malformed json`() {
        assertTrue(failure<SchemaError.MalformedJson>(fixture("err-empty")).reason.contains("empty", ignoreCase = true))
        failure<SchemaError.MalformedJson>("   \n\t ")
    }

    @Test
    fun `truncated file is malformed json`() {
        failure<SchemaError.MalformedJson>(fixture("err-malformed-truncated"))
    }

    @Test
    fun `syntax error reports line and column without gson noise`() {
        val reason = failure<SchemaError.MalformedJson>(fixture("err-malformed-missing-comma")).reason
        assertTrue(reason, Regex("line 6 column \\d+").containsMatchIn(reason))
        assertFalse(reason, reason.contains("com.google.gson"))
        assertFalse(reason, reason.contains("https://"))
    }

    @Test
    fun `non-object root is malformed json`() {
        assertTrue(failure<SchemaError.MalformedJson>(fixture("err-root-array")).reason.contains("array"))
        failure<SchemaError.MalformedJson>("[]")
        failure<SchemaError.MalformedJson>("null")
        failure<SchemaError.MalformedJson>("42")
        failure<SchemaError.MalformedJson>("\"formatVersion\"")
    }

    @Test
    fun `trailing content after the document is malformed json`() {
        failure<SchemaError.MalformedJson>(fixture("fx-basic") + "{}")
        failure<SchemaError.MalformedJson>(fixture("fx-basic") + " garbage")
    }

    // --- formatVersion ---------------------------------------------------------------------------

    @Test
    fun `unsupported formatVersion names found and supported values`() {
        val error = failure<SchemaError.UnsupportedFormat>(fixture("err-unsupported-format"))
        assertEquals("2", error.found)
        assertEquals(1, error.supported)
        assertTrue(error.reason.contains("2") && error.reason.contains("1"))
    }

    @Test
    fun `non-numeric formatVersion is unsupported with raw value as text`() {
        assertEquals("1", failure<SchemaError.UnsupportedFormat>("""{"formatVersion":"1","database":{}}""").found)
        assertEquals("two", failure<SchemaError.UnsupportedFormat>("""{"formatVersion":"two"}""").found)
        assertEquals("true", failure<SchemaError.UnsupportedFormat>("""{"formatVersion":true}""").found)
        assertEquals("1.5", failure<SchemaError.UnsupportedFormat>("""{"formatVersion":1.5}""").found)
        assertEquals("""{"v":1}""", failure<SchemaError.UnsupportedFormat>("""{"formatVersion":{"v":1}}""").found)
        assertEquals("0", failure<SchemaError.UnsupportedFormat>("""{"formatVersion":0}""").found)
    }

    @Test
    fun `unsupported formatVersion wins over an unknown body shape`() {
        failure<SchemaError.UnsupportedFormat>("""{"formatVersion":2,"schema":{"tables":[]}}""")
    }

    @Test
    fun `missing formatVersion is a missing field`() {
        missingField("err-missing-format-version", "formatVersion")
        assertEquals("formatVersion", failure<SchemaError.MissingField>("{}").fieldPath)
        assertEquals("formatVersion", failure<SchemaError.MissingField>("""{"formatVersion":null}""").fieldPath)
    }

    // --- missing fields --------------------------------------------------------------------------

    @Test
    fun `missing database`() = missingField("err-missing-database", "database")

    @Test
    fun `missing version`() = missingField("err-missing-version", "database.version")

    @Test
    fun `missing entities`() = missingField("err-missing-entities", "database.entities")

    @Test
    fun `missing tableName`() = missingField("err-missing-table-name", "database.entities[1].tableName")

    @Test
    fun `missing fields array`() = missingField("err-missing-fields", "database.entities[0].fields")

    @Test
    fun `missing columnName`() = missingField("err-missing-column-name", "database.entities[1].fields[1].columnName")

    @Test
    fun `missing affinity`() = missingField("err-missing-affinity", "database.entities[0].fields[1].affinity")

    @Test
    fun `missing foreign key table`() = missingField("err-missing-fk-table", "database.entities[1].foreignKeys[0].table")

    @Test
    fun `wrong type for entities`() {
        val error = failure<SchemaError.MissingField>(fixture("err-wrong-type-entities"))
        assertEquals("database.entities", error.fieldPath)
        assertTrue(error.reason, error.reason.contains("array") && error.reason.contains("object"))
    }

    @Test
    fun `wrong types and missing nested members give precise paths`() {
        val cases = mapOf(
            """{"formatVersion":1,"database":[]}""" to "database",
            """{"formatVersion":1,"database":{"version":"3","entities":[]}}""" to "database.version",
            """{"formatVersion":1,"database":{"version":1.5,"entities":[]}}""" to "database.version",
            """{"formatVersion":1,"database":{"version":99999999999,"entities":[]}}""" to "database.version",
            """{"formatVersion":1,"database":{"version":1,"identityHash":5,"entities":[]}}""" to "database.identityHash",
            doc("\"users\"") to "database.entities[0]",
            doc("$simpleTable,null") to "database.entities[1]",
            doc("""{"tableName":7,"fields":[]}""") to "database.entities[0].tableName",
            doc("""{"tableName":"t","fields":{}}""") to "database.entities[0].fields",
            doc("""{"tableName":"t","fields":[[]]}""") to "database.entities[0].fields[0]",
            doc("""{"tableName":"t","fields":[{"columnName":"a","affinity":1}]}""") to "database.entities[0].fields[0].affinity",
            doc("""{"tableName":"t","fields":[{"columnName":"a","affinity":"TEXT","notNull":"yes"}]}""") to
                "database.entities[0].fields[0].notNull",
            doc("""{"tableName":"t","fields":[{"columnName":"a","affinity":"TEXT","defaultValue":0}]}""") to
                "database.entities[0].fields[0].defaultValue",
            doc("""{"tableName":"t","fields":[],"primaryKey":[]}""") to "database.entities[0].primaryKey",
            doc("""{"tableName":"t","fields":[],"primaryKey":{"columnNames":"id"}}""") to "database.entities[0].primaryKey.columnNames",
            doc("""{"tableName":"t","fields":[],"primaryKey":{"columnNames":[1]}}""") to "database.entities[0].primaryKey.columnNames[0]",
            doc("""{"tableName":"t","fields":[],"primaryKey":{"columnNames":[],"autoGenerate":1}}""") to
                "database.entities[0].primaryKey.autoGenerate",
            doc("""{"tableName":"t","fields":[],"indices":{}}""") to "database.entities[0].indices",
            doc("""{"tableName":"t","fields":[],"indices":[{"unique":true,"columnNames":[]}]}""") to "database.entities[0].indices[0].name",
            doc("""{"tableName":"t","fields":[],"indices":[{"name":"i"}]}""") to "database.entities[0].indices[0].columnNames",
            doc("""{"tableName":"t","fields":[],"foreignKeys":[{"table":"t","referencedColumns":[]}]}""") to
                "database.entities[0].foreignKeys[0].columns",
            doc("""{"tableName":"t","fields":[],"foreignKeys":[{"table":"t","columns":[]}]}""") to
                "database.entities[0].foreignKeys[0].referencedColumns",
            doc("""{"tableName":"t","fields":[],"foreignKeys":[{"table":"t","columns":[],"referencedColumns":[],"onDelete":3}]}""") to
                "database.entities[0].foreignKeys[0].onDelete",
            doc("""{"tableName":"t","fields":[],"ftsVersion":4}""") to "database.entities[0].ftsVersion",
            doc("""{"tableName":"t","fields":[],"ftsOptions":"x"}""") to "database.entities[0].ftsOptions",
            doc(simpleTable, ""","views":{}""") to "database.views",
            doc(simpleTable, ""","views":[{"createSql":"x"}]""") to "database.views[0].viewName",
            doc(simpleTable, ""","views":[{"viewName":"v","createSql":[]}]""") to "database.views[0].createSql",
        )
        for ((json, path) in cases) {
            assertEquals(json, path, failure<SchemaError.MissingField>(json).fieldPath)
        }
    }

    // --- unknown references ----------------------------------------------------------------------

    @Test
    fun `foreign key to unknown table`() {
        val reason = failure<SchemaError.UnknownReference>(fixture("err-unknown-fk-table")).reason
        assertTrue(reason, reason.contains("'child'") && reason.contains("'ghost'"))
    }

    @Test
    fun `foreign key with unknown child column`() {
        val reason = failure<SchemaError.UnknownReference>(fixture("err-unknown-fk-column")).reason
        assertTrue(reason, reason.contains("'child'") && reason.contains("'owner_id'"))
    }

    @Test
    fun `foreign key with unknown referenced column`() {
        val reason = failure<SchemaError.UnknownReference>(fixture("err-unknown-fk-referenced-column")).reason
        assertTrue(reason, reason.contains("'uuid'") && reason.contains("'parent'"))
    }

    @Test
    fun `index with unknown column`() {
        val reason = failure<SchemaError.UnknownReference>(fixture("err-unknown-index-column")).reason
        assertTrue(reason, reason.contains("'index_child_parent_id'") && reason.contains("'missing_col'"))
    }

    @Test
    fun `primary key with unknown column`() {
        val reason = failure<SchemaError.UnknownReference>(fixture("err-unknown-pk-column")).reason
        assertTrue(reason, reason.contains("'parent'") && reason.contains("'pk_that_is_gone'"))
    }

    @Test
    fun `rowid primary key is only tolerated on fts tables`() {
        val fields = """"fields":[{"columnName":"body","affinity":"TEXT","notNull":true}]"""
        val pk = """"primaryKey":{"columnNames":["rowid"],"autoGenerate":true}"""
        failure<SchemaError.UnknownReference>(doc("""{"tableName":"t",$fields,$pk}"""))
        success(doc("""{"tableName":"t","ftsVersion":"FTS4",$fields,$pk}"""))
        failure<SchemaError.UnknownReference>(
            doc("""{"tableName":"t","ftsVersion":"FTS4",$fields,"primaryKey":{"columnNames":["docid_x"]}}"""),
        )
    }

    @Test
    fun `foreign key to a view is an unknown reference`() {
        failure<SchemaError.UnknownReference>(
            doc(
                """{"tableName":"c","fields":[{"columnName":"v_id","affinity":"INTEGER"}],
                    "foreignKeys":[{"table":"v","columns":["v_id"],"referencedColumns":["id"]}]}""",
                ""","views":[{"viewName":"v","createSql":"CREATE VIEW v AS SELECT 1 AS id"}]""",
            ),
        )
    }

    @Test
    fun `foreign key with mismatched column counts`() {
        failure<SchemaError.UnknownReference>(
            doc(
                """{"tableName":"p","fields":[{"columnName":"a","affinity":"INTEGER"},{"columnName":"b","affinity":"INTEGER"}]},
                   {"tableName":"c","fields":[{"columnName":"a","affinity":"INTEGER"}],
                    "foreignKeys":[{"table":"p","columns":["a"],"referencedColumns":["a","b"]}]}""",
            ),
        )
    }

    @Test
    fun `forward reference to a table declared later is fine`() {
        val db = success(
            doc(
                """{"tableName":"c","fields":[{"columnName":"p_id","affinity":"INTEGER"}],
                    "foreignKeys":[{"table":"p","columns":["p_id"],"referencedColumns":["id"]}]},
                   {"tableName":"p","fields":[{"columnName":"id","affinity":"INTEGER"}]}""",
            ),
        )
        assertEquals("p", db.table("c")!!.foreignKeys.single().referencedTable)
    }

    // --- never throws ----------------------------------------------------------------------------

    @Test
    fun `never throws on garbage input`() {
        val basic = fixture("fx-basic")
        val inputs = mutableListOf(
            "", " ", "\n", "\u0000", "﻿", "[]", "{}", "null", "true", "0", "-", "\"", "'", "{", "}", "[", "]", ":", ",",
            "{\"formatVersion\":", "{\"formatVersion\":1", "{\"formatVersion\":1,", "{\"formatVersion\":1}",
            "{\"formatVersion\":1,\"database\":null}", "{\"formatVersion\":1,\"database\":\"x\"}",
            "{\"formatVersion\":1e999,\"database\":{}}", "{\"formatVersion\":-1}", "{\"formatVersion\":[1]}",
            "{\"formatVersion\":1,\"database\":{\"version\":1e400,\"entities\":[]}}",
            "{\"formatVersion\":1,\"database\":{\"version\":1,\"entities\":[1,\"a\",null,[],{}]}}",
            "<?xml version=\"1.0\"?><schema/>", "formatVersion: 1", "not json at all", "{\"a\":1}{\"b\":2}",
            "{\"formatVersion\":1,\"formatVersion\":1}", "😀", "{\"formatVersion\":\"" + "9".repeat(5000) + "\"}",
            "[".repeat(100_000), "{\"a\":".repeat(50_000), "[".repeat(300) + "]".repeat(300),
        )
        // Every prefix of a valid file (truncation at any point), sampled.
        for (i in 0 until basic.length step 97) inputs += basic.substring(0, i)
        // Type-confused variants of a valid file.
        inputs += basic.replace("\"INTEGER\"", "12")
        inputs += basic.replace("true", "\"true\"")
        inputs += basic.replace("[", "{").replace("]", "}")
        inputs += basic.replace("\"users\"", "null")
        inputs += basic.replace(":", "=")
        inputs += basic.reversed()

        for (input in inputs) {
            val result = try {
                parse(input)
            } catch (t: Throwable) {
                fail("parse threw ${t.javaClass.name} for input starting with '${input.take(60)}'")
                throw t
            }
            if (result is ParseResult.Failure) {
                assertEquals(PATH, result.error.filePath)
                assertTrue(result.error.reason.isNotBlank())
            }
        }
        val long = failure<SchemaError.UnsupportedFormat>("{\"formatVersion\":\"" + "9".repeat(5000) + "\"}")
        assertTrue(long.found.length < 100)
    }

    @Test
    fun `truncated valid file never parses successfully`() {
        val basic = fixture("fx-basic").trimEnd()
        for (i in 1 until basic.length step 131) {
            assertTrue("prefix of length $i must fail", parse(basic.substring(0, i)) is ParseResult.Failure)
        }
    }

    // --- performance (NFR-01) --------------------------------------------------------------------

    @Test
    fun `synthetic 100 table 1000 column schema parses well under two seconds`() {
        val json = SyntheticSchema.generate(tables = 100, columnsPerTable = 10)
        success(json) // warm-up: class loading and JIT must not be what we measure, nor hide a slow parser
        val start = System.nanoTime()
        val db = success(json)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(100, db.tables.size)
        assertEquals(1000, db.tables.sumOf { it.columns.size })
        assertEquals(99, db.tables.sumOf { it.foreignKeys.size })
        assertNotNull(db.table("table_099"))
        assertTrue("parsing took $elapsedMs ms", elapsedMs < 500)
    }

    @Test
    fun `cold parse of the synthetic schema is under two seconds`() {
        val json = SyntheticSchema.generate(tables = 100, columnsPerTable = 10, seedName = "cold")
        val start = System.nanoTime()
        val result = parse(json)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(result is ParseResult.Success)
        assertTrue("parsing took $elapsedMs ms", elapsedMs < 2000)
    }

    private companion object {
        const val DB_NAME = "com.example.app.data.AppDatabase"
        const val PATH = "/project/app/schemas/com.example.app.data.AppDatabase/3.json"
    }
}
