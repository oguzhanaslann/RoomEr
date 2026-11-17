package com.oguzhanaslann.roomer.inference

import com.oguzhanaslann.roomer.model.Column
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ForeignKey
import com.oguzhanaslann.roomer.model.Index
import com.oguzhanaslann.roomer.model.PrimaryKey
import com.oguzhanaslann.roomer.model.Table
import com.oguzhanaslann.roomer.search.DiagramSearch
import com.oguzhanaslann.roomer.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CardinalityInferenceTest {

    private val users = Table("users", listOf(Column("id", "INTEGER", true)), PrimaryKey(listOf("id")))

    private fun child(
        fkNotNull: Boolean = true,
        pk: List<String> = listOf("id"),
        indices: List<Index> = emptyList(),
        fkColumns: List<String> = listOf("user_id"),
    ) = Table(
        name = "child",
        columns = listOf(
            Column("id", "INTEGER", true),
            Column("user_id", "INTEGER", fkNotNull),
            Column("other_id", "INTEGER", true),
        ),
        primaryKey = PrimaryKey(pk),
        foreignKeys = listOf(ForeignKey("users", fkColumns, fkColumns.map { "id" })),
        indices = indices,
    )

    private fun infer(table: Table) = CardinalityInference.infer(table, table.foreignKeys.single())

    @Test fun `not-null FK gives exactly one parent`() =
        assertEquals(ParentCardinality.EXACTLY_ONE, infer(child(fkNotNull = true)).parentEnd)

    @Test fun `nullable FK gives zero or one parent`() =
        assertEquals(ParentCardinality.ZERO_OR_ONE, infer(child(fkNotNull = false)).parentEnd)

    @Test fun `composite FK with one nullable column gives zero or one parent`() =
        assertEquals(
            ParentCardinality.ZERO_OR_ONE,
            infer(child(fkNotNull = false, fkColumns = listOf("user_id", "other_id"))).parentEnd,
        )

    @Test fun `plain FK gives many children`() =
        assertEquals(ChildCardinality.MANY, infer(child()).childEnd)

    @Test fun `FK equal to primary key gives one child`() =
        assertEquals(ChildCardinality.ONE, infer(child(pk = listOf("user_id"))).childEnd)

    @Test fun `FK that is only part of a composite primary key gives many children`() =
        assertEquals(ChildCardinality.MANY, infer(child(pk = listOf("user_id", "other_id"))).childEnd)

    @Test fun `unique index on FK columns gives one child`() =
        assertEquals(
            ChildCardinality.ONE,
            infer(child(indices = listOf(Index("ux", true, listOf("user_id"))))).childEnd,
        )

    @Test fun `non-unique index gives many children`() =
        assertEquals(
            ChildCardinality.MANY,
            infer(child(indices = listOf(Index("ix", false, listOf("user_id"))))).childEnd,
        )

    @Test fun `unique index wider than the FK gives many children`() =
        assertEquals(
            ChildCardinality.MANY,
            infer(child(indices = listOf(Index("ux", true, listOf("user_id", "other_id"))))).childEnd,
        )

    @Test fun `infer returns one relationship per foreign key in order`() {
        val db = Database("com.acme.AppDb", 1, null, listOf(users, child()), emptyList())
        val relationships = CardinalityInference.infer(db)
        assertEquals(listOf("child" to "users"), relationships.map { it.childTable to it.parentTable })
        assertEquals("AppDb", db.simpleName)
    }

    @Test fun `search is case-insensitive over tables and columns`() {
        val db = Database("AppDb", 1, null, listOf(users, child()), emptyList())
        assertEquals(
            listOf(SearchResult("users"), SearchResult("child", "user_id")),
            DiagramSearch.search(db, "USER"),
        )
        assertEquals(emptyList<SearchResult>(), DiagramSearch.search(db, "  "))
    }
}

class UniqueMarkerRuleTest {
    private val table = Table(
        name = "t",
        columns = listOf(Column("a", "TEXT", true), Column("b", "TEXT", true), Column("c", "TEXT", true)),
        primaryKey = PrimaryKey(listOf("a")),
        indices = listOf(
            Index("ux_a", unique = true, columns = listOf("a")),
            Index("ux_bc", unique = true, columns = listOf("b", "c")),
            Index("ix_c", unique = false, columns = listOf("c")),
        ),
    )

    @Test fun `sole column of a unique index is unique and not merely indexed`() {
        assertEquals(true, table.isUnique("a"))
        assertEquals(false, table.isIndexed("a"))
    }

    @Test fun `member of a multi-column unique index is indexed, not unique`() {
        assertEquals(false, table.isUnique("b"))
        assertEquals(true, table.isIndexed("b"))
        assertEquals(false, table.isUnique("c"))
        assertEquals(true, table.isIndexed("c"))
    }
}
