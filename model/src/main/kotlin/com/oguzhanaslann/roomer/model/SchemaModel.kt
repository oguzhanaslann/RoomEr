package com.oguzhanaslann.roomer.model

/**
 * Immutable model of one version of one Room database, as read from an exported schema JSON file.
 *
 * Entity names ([Table.name], [View.name]) are unique within a database (SQLite shares one namespace
 * for tables and views) and are used as node ids everywhere (layout, canvas, persistence).
 */
data class Database(
    /** Folder name under `schemas/`, normally the fully qualified `@Database` class name. */
    val name: String,
    val version: Int,
    val identityHash: String?,
    val tables: List<Table>,
    val views: List<View>,
    /** Path of the JSON file this model was parsed from. */
    val sourcePath: String? = null,
) {
    /** Simple class name: the part of [name] after the last dot. */
    val simpleName: String get() = name.substringAfterLast('.')

    /** Tables and FTS tables first (in file order), then views. */
    val entities: List<SchemaEntity> get() = tables + views

    fun entity(name: String): SchemaEntity? = entities.firstOrNull { it.name == name }
    fun table(name: String): Table? = tables.firstOrNull { it.name == name }
}

enum class EntityKind { TABLE, FTS_TABLE, VIEW }

sealed interface SchemaEntity {
    val name: String
    val kind: EntityKind
}

data class Table(
    override val name: String,
    val columns: List<Column>,
    val primaryKey: PrimaryKey,
    val foreignKeys: List<ForeignKey> = emptyList(),
    val indices: List<Index> = emptyList(),
    /** Non-null for FTS virtual tables (`@Fts3` / `@Fts4`). */
    val fts: FtsInfo? = null,
) : SchemaEntity {
    override val kind: EntityKind get() = if (fts != null) EntityKind.FTS_TABLE else EntityKind.TABLE

    fun column(name: String): Column? = columns.firstOrNull { it.name == name }

    fun isPrimaryKey(column: String): Boolean = column in primaryKey.columns
    fun isForeignKey(column: String): Boolean = foreignKeys.any { column in it.columns }
    /**
     * True if the column is unique on its own: it is the sole column of at least one unique index.
     * A column that is only part of a multi-column unique index is not unique by itself; it counts as
     * [isIndexed]. This one rule drives both the diagram's `UQ` badge and Mermaid's `UK` key.
     */
    fun isUnique(column: String): Boolean =
        indices.any { it.unique && it.columns.size == 1 && it.columns[0] == column }

    /**
     * True if the column is part of an index other than a single-column unique index on it
     * (a non-unique index, or a multi-column unique index). Drives the diagram's `IDX` badge.
     */
    fun isIndexed(column: String): Boolean =
        indices.any { column in it.columns && !(it.unique && it.columns.size == 1) }
}

data class Column(
    val name: String,
    /** SQLite affinity as written by Room: `INTEGER`, `TEXT`, `REAL`, `BLOB`, `NUMERIC`, … */
    val type: String,
    val notNull: Boolean,
    val defaultValue: String? = null,
)

data class PrimaryKey(
    val columns: List<String>,
    val autoGenerate: Boolean = false,
)

data class ForeignKey(
    /** Referenced (parent) table. */
    val referencedTable: String,
    /** Columns in the child table (the table that declares the key). */
    val columns: List<String>,
    val referencedColumns: List<String>,
    val onDelete: String = "NO ACTION",
    val onUpdate: String = "NO ACTION",
)

data class Index(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

data class FtsInfo(
    /** As written by Room: `FTS3`, `FTS4`, or `FTS5` (Room 3). */
    val version: String,
    /** External content table, if any. */
    val contentTable: String? = null,
)

data class View(
    override val name: String,
    val sql: String,
) : SchemaEntity {
    override val kind: EntityKind get() = EntityKind.VIEW
}
