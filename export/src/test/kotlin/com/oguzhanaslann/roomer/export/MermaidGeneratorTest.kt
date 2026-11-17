package com.oguzhanaslann.roomer.export

import com.oguzhanaslann.roomer.export.Fixtures.col
import com.oguzhanaslann.roomer.export.Fixtures.database
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ForeignKey
import com.oguzhanaslann.roomer.model.FtsInfo
import com.oguzhanaslann.roomer.model.Index
import com.oguzhanaslann.roomer.model.ParseResult
import com.oguzhanaslann.roomer.model.PrimaryKey
import com.oguzhanaslann.roomer.model.Table
import com.oguzhanaslann.roomer.model.View
import com.oguzhanaslann.roomer.parser.SchemaParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class MermaidGeneratorTest {

    // ---- golden files (AC-19.1) -------------------------------------------------------------

    @Test
    fun `in-code basic model matches golden file byte for byte`() = assertGolden("model-basic", Fixtures.basic)

    @Test
    fun `fx-edge matches golden file byte for byte`() = assertGolden("fx-edge", Fixtures.edge)

    @Test
    fun `empty database is just the header`() {
        assertGolden("empty", database(emptyList()))
        assertEquals("erDiagram\n", MermaidGenerator.generate(database(emptyList())))
    }

    // ---- shape (AC-19.2) --------------------------------------------------------------------

    @Test
    fun `output starts with erDiagram and ends with a single newline`() {
        for (db in listOf(Fixtures.basic, Fixtures.edge, database(emptyList()))) {
            val text = MermaidGenerator.generate(db)
            assertTrue(text.startsWith("erDiagram\n"))
            assertTrue(text.endsWith("\n"))
            assertFalse(text.endsWith("\n\n"))
            assertFalse(text.contains('\r'))
        }
    }

    @Test
    fun `attributes are type name keys in column order`() {
        val lines = lines(Fixtures.basic)
        val start = lines.indexOf("    users {")
        assertEquals(
            listOf("        INTEGER id PK", "        TEXT email UK", "        TEXT display_name", "    }"),
            lines.subList(start + 1, start + 5),
        )
    }

    @Test
    fun `a column with several keys lists them comma separated in PK FK UK order`() {
        val table = Table(
            name = "child",
            columns = listOf(col("parent_id")),
            primaryKey = PrimaryKey(listOf("parent_id")),
            foreignKeys = listOf(ForeignKey("parent", listOf("parent_id"), listOf("id"))),
            indices = listOf(Index("ix", unique = true, columns = listOf("parent_id"))),
        )
        assertTrue("        INTEGER parent_id PK, FK, UK" in lines(database(listOf(table, simple("parent")))))
    }

    @Test
    fun `UK only marks the sole column of a unique index`() {
        val table = Table(
            name = "t",
            columns = listOf(col("a"), col("b"), col("c"), col("d")),
            primaryKey = PrimaryKey(emptyList()),
            indices = listOf(
                Index("ix_ab", unique = true, columns = listOf("a", "b")),
                Index("ix_c", unique = true, columns = listOf("c")),
                Index("ix_d", unique = false, columns = listOf("d")),
            ),
        )
        val lines = lines(database(listOf(table)))
        assertTrue("        INTEGER a" in lines)
        assertTrue("        INTEGER b" in lines)
        assertTrue("        INTEGER c UK" in lines)
        assertTrue("        INTEGER d" in lines)
    }

    // ---- cardinality tokens (AC-19.3) -------------------------------------------------------

    @Test
    fun `not-null FK is exactly one to many`() =
        assertRelationship("parent ||--o{ child : \"parent_id\"", notNull = true, unique = false)

    @Test
    fun `nullable FK is zero or one to many`() =
        assertRelationship("parent |o--o{ child : \"parent_id\"", notNull = false, unique = false)

    @Test
    fun `not-null unique FK is exactly one to one`() =
        assertRelationship("parent ||--o| child : \"parent_id\"", notNull = true, unique = true)

    @Test
    fun `nullable unique FK is zero or one to one`() =
        assertRelationship("parent |o--o| child : \"parent_id\"", notNull = false, unique = true)

    @Test
    fun `FK that is the primary key is one to one`() {
        assertTrue("    users ||--o| profiles : \"user_id\"" in lines(Fixtures.basic))
    }

    @Test
    fun `composite FK is a single line labelled with all columns`() {
        val lines = lines(Fixtures.edge)
        assertTrue("    alpha ||--o| \"2024 archive\" : \"tenant id, order#\"" in lines)
        assertEquals(1, lines.count { it.contains("\"2024 archive\" :") })
    }

    @Test
    fun `self reference is emitted`() {
        assertTrue("    categories |o--o{ categories : \"parent_id\"" in lines(Fixtures.basic))
    }

    @Test
    fun `FK to a table missing from the database is skipped`() {
        val text = MermaidGenerator.generate(Fixtures.edge)
        assertFalse(text.contains("ghost "))
        assertTrue(text.contains("        INTEGER ghost_id FK\n"))
        assertEquals(3, relationshipLines(Fixtures.edge).size)
    }

    @Test
    fun `one relationship line per foreign key`() {
        assertEquals(6, relationshipLines(Fixtures.basic).size)
    }

    // ---- quoting and sanitising (AC-19.4) ---------------------------------------------------

    @Test
    fun `entity names with non-word characters are quoted everywhere`() {
        val lines = lines(Fixtures.basic)
        assertTrue("    \"order-items\" {" in lines)
        assertTrue("    posts |o--o| \"order-items\" : \"post_id\"" in lines)
        assertFalse(lines.any { it.trim().startsWith("order-items") })
    }

    @Test
    fun `plain entity names are not quoted`() {
        assertTrue("    users {" in lines(Fixtures.basic))
        assertTrue("    _private1 {" in lines(database(listOf(simple("_private1")))))
    }

    @Test
    fun `entity name starting with a digit or containing quotes is quoted safely`() {
        val lines = lines(Fixtures.edge)
        assertTrue("    \"2024 archive\" {" in lines)
        assertTrue("    \"say 'hi'\"" in lines)
    }

    @Test
    fun `attribute names and types are sanitised and the original name kept as comment`() {
        val lines = lines(Fixtures.edge)
        assertTrue("        INTEGER tenant_id PK, FK \"tenant id\"" in lines)
        assertTrue("        INTEGER order_ PK, FK \"order#\"" in lines)
        assertTrue("        VARCHAR_20_ note" in lines)
        assertTrue("        TEXT _1st UK \"1st\"" in lines)
    }

    @Test
    fun `every attribute line is valid mermaid attribute syntax`() {
        val attribute = Regex("""        [A-Za-z_][A-Za-z0-9_]* [A-Za-z_][A-Za-z0-9_]*( (PK|FK|UK)(, (PK|FK|UK))*)?( "[^"]*")?""")
        for (db in listOf(Fixtures.basic, Fixtures.edge)) {
            val attributes = lines(db).filter { it.startsWith("        ") }
            assertTrue(attributes.isNotEmpty())
            attributes.forEach { assertTrue(it, attribute.matches(it)) }
        }
    }

    @Test
    fun `line breaks in names cannot break the line structure`() {
        val table = Table(
            name = "a\nb",
            columns = listOf(col("x\ny")),
            primaryKey = PrimaryKey(emptyList()),
            fts = FtsInfo("FTS4", contentTable = "c\nd"),
        )
        assertEquals(
            "erDiagram\n" +
                "    %% FTS4 virtual table (content table: c d)\n" +
                "    \"a b\" {\n" +
                "        INTEGER x_y \"x y\"\n" +
                "    }\n",
            MermaidGenerator.generate(database(listOf(table))),
        )
    }

    // ---- views and FTS (AC-19.5) ------------------------------------------------------------

    @Test
    fun `view is a bare entity preceded by a kind comment`() {
        val lines = lines(Fixtures.basic)
        val i = lines.indexOf("    active_users")
        assertTrue(i > 0)
        assertEquals("    %% view", lines[i - 1])
    }

    @Test
    fun `FTS table is preceded by a comment with version and content table`() {
        val basic = lines(Fixtures.basic)
        assertEquals("    %% FTS4 virtual table (content table: posts)", basic[basic.indexOf("    posts_fts {") - 1])
        val edge = lines(Fixtures.edge)
        assertEquals("    %% FTS3 virtual table", edge[edge.indexOf("    docs_fts {") - 1])
    }

    @Test
    fun `plain tables have no comment`() {
        assertEquals(2, lines(Fixtures.basic).count { it.trim().startsWith("%%") })
    }

    // ---- determinism (AC-19.6) --------------------------------------------------------------

    @Test
    fun `generating twice gives identical output`() {
        assertEquals(MermaidGenerator.generate(Fixtures.basic), MermaidGenerator.generate(Fixtures.basic))
    }

    @Test
    fun `table, view and foreign key order in the model does not change the output`() {
        for (db in listOf(Fixtures.basic, Fixtures.edge)) {
            val expected = MermaidGenerator.generate(db)
            val random = Random(42)
            repeat(20) {
                val shuffled = db.copy(
                    tables = db.tables.shuffled(random).map { it.copy(foreignKeys = it.foreignKeys.shuffled(random)) },
                    views = db.views.shuffled(random),
                )
                assertEquals(expected, MermaidGenerator.generate(shuffled))
            }
        }
    }

    @Test
    fun `database metadata does not leak into the output`() {
        val other = Fixtures.basic.copy(name = "other.Db", version = 99, identityHash = null, sourcePath = "/tmp/9.json")
        assertEquals(MermaidGenerator.generate(Fixtures.basic), MermaidGenerator.generate(other))
    }

    @Test
    fun `entities are sorted by name with tables and views interleaved`() {
        val db = database(
            tables = listOf(simple("m"), simple("b"), simple("Z")),
            views = listOf(View("c", "SELECT 1"), View("a", "SELECT 1")),
        )
        val names = lines(db).filter { it.startsWith("    ") && !it.startsWith("     ") }
            .map { it.trim().removeSuffix(" {") }
            .filter { it != "}" && !it.startsWith("%%") }
        assertEquals(listOf("Z", "a", "b", "c", "m"), names)
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun simple(name: String) = Table(name, listOf(col("id")), PrimaryKey(listOf("id")))

    private fun lines(db: Database): List<String> = MermaidGenerator.generate(db).removeSuffix("\n").split("\n")

    private fun relationshipLines(db: Database): List<String> = lines(db).filter { it.contains("--") && it.contains(" : ") }

    private fun assertRelationship(expected: String, notNull: Boolean, unique: Boolean) {
        val child = Table(
            name = "child",
            columns = listOf(col("id"), col("parent_id", notNull = notNull)),
            primaryKey = PrimaryKey(listOf("id")),
            foreignKeys = listOf(ForeignKey("parent", listOf("parent_id"), listOf("id"))),
            indices = listOf(Index("ix", unique = unique, columns = listOf("parent_id"))),
        )
        assertEquals(listOf("    $expected"), relationshipLines(database(listOf(child, simple("parent")))))
    }

    // End to end over the parser's JSON fixtures, the same input the plugin's "Copy as Mermaid" uses (AC-19.1).
    @Test
    fun `fx-basic json fixture matches golden file byte for byte`() = assertGolden("fx-basic", parseFixture("fx-basic"))

    @Test
    fun `fx-views-fts json fixture matches golden file byte for byte`() =
        assertGolden("fx-views-fts", parseFixture("fx-views-fts"))

    private fun parseFixture(name: String): Database {
        val resource = "/fixtures/$name.json"
        val json = checkNotNull(javaClass.getResourceAsStream(resource)) { "missing fixture $resource" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return when (val result = SchemaParser.parse(json, "com.acme.AppDb", resource)) {
            is ParseResult.Success -> result.database
            is ParseResult.Failure -> throw AssertionError("fixture $name does not parse: ${result.error}")
        }
    }

    private fun assertGolden(name: String, db: Database) {
        val resource = "/golden/$name.expected.mmd"
        val stream = checkNotNull(javaClass.getResourceAsStream(resource)) { "missing golden file $resource" }
        val expected = stream.use { it.readBytes() }
        val actual = MermaidGenerator.generate(db).toByteArray(Charsets.UTF_8)
        // String comparison first for a readable diff, then the byte-for-byte check.
        assertEquals(String(expected, Charsets.UTF_8), String(actual, Charsets.UTF_8))
        assertTrue("golden $name differs at byte level", expected.contentEquals(actual))
    }
}
