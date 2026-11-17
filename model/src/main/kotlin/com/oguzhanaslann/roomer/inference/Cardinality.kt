package com.oguzhanaslann.roomer.inference

import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ForeignKey
import com.oguzhanaslann.roomer.model.Table

/** Parent (referenced) end of a foreign key edge. */
enum class ParentCardinality { EXACTLY_ONE, ZERO_OR_ONE }

/** Child (declaring) end of a foreign key edge. */
enum class ChildCardinality { ONE, MANY }

/** One foreign key, with the cardinality inferred for both ends (spec FR-21). */
data class Relationship(
    val childTable: String,
    val parentTable: String,
    val foreignKey: ForeignKey,
    val parentEnd: ParentCardinality,
    val childEnd: ChildCardinality,
) {
    val isSelfReference: Boolean get() = childTable == parentTable

    /** Stable id, unique within a database: child table name + index of the FK in that table. */
    fun id(indexInChild: Int): String = "$childTable#$indexInChild"
}

object CardinalityInference {

    /**
     * One [Relationship] per foreign key, in table order then FK order (deterministic).
     * Foreign keys whose parent table is not in the database are still returned.
     */
    fun infer(database: Database): List<Relationship> =
        database.tables.flatMap { table -> table.foreignKeys.map { fk -> infer(table, fk) } }

    fun infer(child: Table, foreignKey: ForeignKey): Relationship = Relationship(
        childTable = child.name,
        parentTable = foreignKey.referencedTable,
        foreignKey = foreignKey,
        parentEnd = parentEnd(child, foreignKey),
        childEnd = childEnd(child, foreignKey),
    )

    /** Exactly one if every FK column is not-null, otherwise zero or one. */
    private fun parentEnd(child: Table, fk: ForeignKey): ParentCardinality {
        val allNotNull = fk.columns.isNotEmpty() && fk.columns.all { child.column(it)?.notNull == true }
        return if (allNotNull) ParentCardinality.EXACTLY_ONE else ParentCardinality.ZERO_OR_ONE
    }

    /**
     * One if the FK columns are exactly the child's primary key, or a unique index covers them
     * (every column of the unique index is an FK column, so the FK value identifies at most one row).
     */
    private fun childEnd(child: Table, fk: ForeignKey): ChildCardinality {
        val fkColumns = fk.columns.toSet()
        if (fkColumns.isEmpty()) return ChildCardinality.MANY
        val matchesPrimaryKey = child.primaryKey.columns.isNotEmpty() && child.primaryKey.columns.toSet() == fkColumns
        val coveredByUniqueIndex = child.indices.any { index ->
            index.unique && index.columns.isNotEmpty() && fkColumns.containsAll(index.columns)
        }
        return if (matchesPrimaryKey || coveredByUniqueIndex) ChildCardinality.ONE else ChildCardinality.MANY
    }
}
