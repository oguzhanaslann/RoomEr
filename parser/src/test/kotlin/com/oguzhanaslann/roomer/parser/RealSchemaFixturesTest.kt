package com.oguzhanaslann.roomer.parser

import com.oguzhanaslann.roomer.model.Column
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.EntityKind
import com.oguzhanaslann.roomer.model.ForeignKey
import com.oguzhanaslann.roomer.model.FtsInfo
import com.oguzhanaslann.roomer.model.Index
import com.oguzhanaslann.roomer.model.ParseResult
import com.oguzhanaslann.roomer.model.PrimaryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Precise assertions over real Room exports copied unmodified from open-source repositories
 * (see `src/test/resources/fixtures/real/README.md` for sources and licences). FR-13 / risk R5.
 */
class RealSchemaFixturesTest {

    private fun real(path: String): Database {
        val json = checkNotNull(javaClass.getResourceAsStream("/fixtures/real/$path")) { "missing fixture $path" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val dbName = path.substringBeforeLast('/').substringAfterLast('/')
        return when (val r = SchemaParser.parse(json, dbName, path)) {
            is ParseResult.Success -> r.database
            is ParseResult.Failure -> throw AssertionError("expected success but got ${r.error}")
        }
    }

    // --- Room 1.x era (no `views`, no `orders`, no `defaultValue`) ---------------------------------

    @Test
    fun `room 1 era - minimal file without views key`() {
        val db = real("androidx-room/androidx.room.integration.testapp.migration.MigrationDb/1.json")
        assertEquals(1, db.version)
        assertEquals("2f3557e56d7f665363f3e20d14787a59", db.identityHash)
        assertEquals(emptyList<Any>(), db.views)
        val t = db.tables.single()
        assertEquals("Entity1", t.name)
        assertEquals(listOf(Column("id", "INTEGER", true), Column("name", "TEXT", false)), t.columns)
        assertEquals(PrimaryKey(listOf("id"), false), t.primaryKey)
    }

    @Test
    fun `room 1 era - architecture components sample`() {
        val db = real("architecture-components-samples/com.example.android.persistence.migrations.UsersDatabase/2.json")
        assertEquals("UsersDatabase", db.simpleName)
        val users = db.table("users")!!
        assertEquals(listOf("userid", "username"), users.columns.map { it.name })
        assertEquals(listOf("userid"), users.primaryKey.columns)
    }

    // --- views, defaults, FK ---------------------------------------------------------------------

    @Test
    fun `view SQL has VIEW_NAME substituted`() {
        val db = real("androidx-room/androidx.room.integration.testapp.migration.MigrationDb/13.json")
        val view = db.views.single()
        assertEquals("View1", view.name)
        assertEquals(EntityKind.VIEW, view.kind)
        assertEquals(
            "CREATE VIEW `View1` AS SELECT Entity4.id, Entity4.name, Entity1.id AS entity1Id " +
                "FROM Entity4 INNER JOIN Entity1 ON Entity4.name = Entity1.name",
            view.sql,
        )
    }

    @Test
    fun `defaultValue is the raw SQL literal including quotes and parentheses`() {
        val db = real("androidx-room/androidx.room.integration.testapp.migration.MigrationDb/13.json")
        val e1 = db.table("Entity1")!!
        assertEquals("0", e1.column("addedInV10")!!.defaultValue)
        assertEquals("(0)", e1.column("added1InV13")!!.defaultValue)
        assertEquals("'Unknown'", db.table("Entity2")!!.column("name")!!.defaultValue)
        assertNull(e1.column("name")!!.defaultValue)
        // indices written by an older Room, without `orders`
        assertEquals(
            listOf(Index("index_Entity1_name", true, listOf("name")), Index("index_Entity1_addedInV10", false, listOf("addedInV10"))),
            e1.indices,
        )
        assertEquals(
            listOf(ForeignKey("Entity1", listOf("name"), listOf("name"), "NO ACTION", "NO ACTION")),
            db.table("Entity4")!!.foreignKeys,
        )
    }

    @Test
    fun `nowinandroid - composite primary key and cascading foreign keys`() {
        val db = real("nowinandroid/com.google.samples.apps.nowinandroid.core.database.NiaDatabase/14.json")
        assertEquals(14, db.version)
        val crossRef = db.table("news_resources_topics")!!
        assertEquals(PrimaryKey(listOf("news_resource_id", "topic_id"), false), crossRef.primaryKey)
        assertEquals(
            listOf(
                ForeignKey("news_resources", listOf("news_resource_id"), listOf("id"), "CASCADE", "NO ACTION"),
                ForeignKey("topics", listOf("topic_id"), listOf("id"), "CASCADE", "NO ACTION"),
            ),
            crossRef.foreignKeys,
        )
        // indices written with `"orders": []` (Room 2.5+)
        assertEquals(listOf("index_news_resources_topics_news_resource_id", "index_news_resources_topics_topic_id"), crossRef.indices.map { it.name })
        assertEquals("''", db.table("topics")!!.column("longDescription")!!.defaultValue)
        assertEquals(false, db.table("news_resources")!!.column("header_image_url")!!.notNull)
    }

    @Test
    fun `nowinandroid - FTS4 tables without content table and without primary key`() {
        val db = real("nowinandroid/com.google.samples.apps.nowinandroid.core.database.NiaDatabase/14.json")
        assertEquals(listOf("newsResourcesFts", "topicsFts"), db.tables.filter { it.kind == EntityKind.FTS_TABLE }.map { it.name })
        val fts = db.table("topicsFts")!!
        assertEquals(FtsInfo("FTS4", null), fts.fts)
        assertEquals(emptyList<String>(), fts.primaryKey.columns)
        assertEquals(listOf("topicId", "name", "shortDescription", "longDescription"), fts.columns.map { it.name })
    }

    @Test
    fun `sample app - FTS4 external content table plus FK with CASCADE`() {
        val db = real("architecture-components-samples/com.example.android.persistence.db.AppDatabase/2.json")
        assertEquals(FtsInfo("FTS4", "products"), db.table("productsFts")!!.fts)
        assertEquals("CASCADE", db.table("comments")!!.foreignKeys.single().onDelete)
        assertEquals(PrimaryKey(listOf("id"), true), db.table("comments")!!.primaryKey)
        assertEquals(emptyList<Any>(), db.views) // `"views": []`
    }

    @Test
    fun `duckduckgo - self-referencing FK with SET NULL on delete and update`() {
        val db = real("duckduckgo/com.duckduckgo.app.fire.db.FireModeDatabase/1.json")
        assertEquals(
            ForeignKey("tabs", listOf("sourceTabId"), listOf("tabId"), "SET NULL", "SET NULL"),
            db.table("tabs")!!.foreignKeys.single(),
        )
        assertEquals(
            ForeignKey("tabs", listOf("tabId"), listOf("tabId"), "SET NULL", "NO ACTION"),
            db.table("tab_selection")!!.foreignKeys.single(),
        )
        // Room 2.6+ omits `notNull` for nullable columns
        assertEquals(false, db.table("tabs")!!.column("url")!!.notNull)
    }

    @Test
    fun `home assistant - large recent export with composite keys and NULL defaults`() {
        val db = real("home-assistant/io.homeassistant.companion.android.database.AppDatabase/56.json")
        assertEquals(56, db.version)
        assertEquals(21, db.tables.size)
        assertEquals(PrimaryKey(listOf("id", "server_id"), false), db.table("sensors")!!.primaryKey)
        assertEquals(PrimaryKey(listOf("server_id", "entity_id"), false), db.table("media_controls_entity_config")!!.primaryKey)
        assertEquals(Column("registered", "INTEGER", false, "NULL"), db.table("sensors")!!.column("registered"))
        assertEquals("'DAYNIGHT'", db.table("button_widgets")!!.column("background_type")!!.defaultValue)
        assertEquals("12.0", db.table("template_widgets")!!.column("text_size")!!.defaultValue)
    }

    // --- FTS3 / FTS4 / rowid -----------------------------------------------------------------------

    @Test
    fun `room 2_1 era FTS - empty primary key and contentTable`() {
        val db = real("androidx-room/androidx.room.integration.testapp.migration.FtsMigrationTest.FtsMigrationDb/3.json")
        assertEquals(FtsInfo("FTS4", null), db.table("Book")!!.fts)
        assertEquals(FtsInfo("FTS4", "Person"), db.table("AddressFts")!!.fts)
        assertEquals(EntityKind.TABLE, db.table("Person")!!.kind)
        assertEquals(PrimaryKey(emptyList(), false), db.table("AddressFts")!!.primaryKey)
    }

    @Test
    fun `auto-migration era - FTS3 and FTS4 with rowid primary key, view, forward FK reference`() {
        val db = real("androidx-room/androidx.room.integration.kotlintestapp.migration.AutoMigrationDb/5.json")
        assertEquals(26, db.tables.size)
        val e21 = db.table("Entity21")!!
        assertEquals(FtsInfo("FTS4", "Entity13_V2"), e21.fts)
        assertEquals(PrimaryKey(listOf("rowid"), true), e21.primaryKey)
        assertNull(e21.column("rowid")) // rowid is implicit, not a field
        assertEquals(FtsInfo("FTS3", null), db.table("Entity23")!!.fts)
        assertEquals(FtsInfo("FTS4", null), db.table("Entity22")!!.fts)
        // FK to a table declared later in the file
        assertEquals(ForeignKey("Entity27", listOf("id"), listOf("id27")), db.table("Entity9")!!.foreignKeys.single())
        assertEquals("'1'", db.table("Entity5")!!.column("addedInV1")!!.defaultValue)
        assertEquals(listOf("Entity25Detail"), db.views.map { it.name })
        assertTrue(db.views.single().sql.startsWith("CREATE VIEW `Entity25Detail` AS SELECT Entity25.id"))
    }

    // --- KMP / Room 2.7+ / Room 3 ------------------------------------------------------------------

    @Test
    fun `KMP export (Room 2_7+ multiplatform test app)`() {
        val db = real("androidx-room/androidx.room.integration.multiplatformtestapp.test.BaseAutoMigrationTest.AutoMigrationDatabase/2.json")
        assertEquals(2, db.version)
        val t = db.tables.single()
        assertEquals(listOf(Column("pk", "INTEGER", true), Column("data", "INTEGER", true, "0")), t.columns)
        assertEquals(PrimaryKey(listOf("pk"), false), t.primaryKey)
    }

    @Test
    fun `Room 3 - omitted notNull means nullable`() {
        val db = real("androidx-room3/androidx.room3.integration.kotlintestapp.migration.MigrationDb/10.json")
        assertEquals(Column("name", "TEXT", false), db.table("Entity1")!!.column("name"))
        assertEquals(Column("addedInV9", "TEXT", false, "'Unknown'"), db.table("Entity5")!!.column("addedInV9"))
        assertEquals(Column("addedInV10", "INTEGER", true, "(0)"), db.table("Entity5")!!.column("addedInV10"))
        assertTrue(db.table("Entity1")!!.isUnique("name"))
    }

    @Test
    fun `Room 3 - FTS5 tables`() {
        val db = real("androidx-room3/androidx.room3.integration.kotlintestapp.migration.Fts5MigrationDb/4.json")
        val songFts = db.table("SongFts")!!
        assertEquals(FtsInfo("FTS5", "Song"), songFts.fts)
        assertEquals(EntityKind.FTS_TABLE, songFts.kind)
        assertEquals(emptyList<String>(), songFts.primaryKey.columns)
        val mail = db.table("Mail")!!
        assertEquals(FtsInfo("FTS5", null), mail.fts)
        assertEquals(PrimaryKey(listOf("rowid"), true), mail.primaryKey)
        assertEquals(EntityKind.TABLE, db.table("Song")!!.kind)
    }
}
