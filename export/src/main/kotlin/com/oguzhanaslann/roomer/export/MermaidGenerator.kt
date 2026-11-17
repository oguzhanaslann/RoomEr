package com.oguzhanaslann.roomer.export

import com.oguzhanaslann.roomer.inference.CardinalityInference
import com.oguzhanaslann.roomer.inference.ChildCardinality
import com.oguzhanaslann.roomer.inference.ParentCardinality
import com.oguzhanaslann.roomer.inference.Relationship
import com.oguzhanaslann.roomer.model.Column
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.SchemaEntity
import com.oguzhanaslann.roomer.model.Table
import com.oguzhanaslann.roomer.model.View

/**
 * Generates Mermaid `erDiagram` text from the model (spec FR-35/FR-36). Pure and deterministic.
 *
 * ## Layout
 * ```
 * erDiagram
 *     <entity blocks, sorted by entity name>
 *                                              <- one blank line, only if there are relationships
 *     <relationship lines, sorted>
 * ```
 * Lines end with `\n`; the output ends with exactly one `\n`; indentation is 4 spaces per level.
 * Nothing but the tables, views and foreign keys of the database influences the output (no
 * timestamps, no database name, no source path), so equal schemas give byte-identical text.
 *
 * ## Ordering
 * - Entities (tables, FTS tables and views together) are sorted by their raw name, compared by
 *   UTF-16 code unit ([String.compareTo]) — locale independent, upper case before lower case.
 * - Columns keep their declared order.
 * - Relationships are sorted by raw parent name, then raw child name, then the FK columns joined
 *   with `,`, then the referenced columns joined with `,`; remaining ties keep model order.
 *
 * ## Entities
 * - A table with columns is a `name { … }` block with one attribute `type name [keys] ["comment"]`
 *   per column.
 * - A view, or a table without columns, is a bare name line (an attribute-less entity; an empty
 *   `{ }` block is rejected by some Mermaid versions).
 * - A view is preceded by `%% view`; an FTS table by `%% FTS4 virtual table` (version taken from
 *   the model), followed by ` (content table: <name>)` when it has an external content table.
 * - **Entity name**: written as is when it matches `[A-Za-z_][A-Za-z0-9_]*`, otherwise wrapped in
 *   double quotes. Inside quotes, `"` becomes `'` and control characters become a space (Mermaid
 *   has no escape for either).
 *
 * ## Attributes
 * - **Keys**, comma-separated in the fixed order `PK, FK, UK`:
 *   - `PK`: the column is part of the primary key.
 *   - `FK`: the column is part of at least one foreign key (even one whose parent table is absent).
 *   - `UK`: the column is the *only* column of at least one unique index. A column that is merely a
 *     member of a multi-column unique index is not marked, because it is not unique on its own.
 *     Primary key columns are not marked `UK` unless they also have such an index.
 * - **Type and name** must be Mermaid identifiers, which cannot be quoted. Every character outside
 *   `[A-Za-z0-9_]` is replaced by `_`, and `_` is prefixed if the result is empty or starts with a
 *   digit (`first name` → `first_name`, `VARCHAR(20)` → `VARCHAR_20_`, `2fa` → `_2fa`).
 *   When a *name* was changed, the original is appended as an attribute comment so no information
 *   is lost: `TEXT first_name "first name"`. Distinct columns can sanitise to the same identifier;
 *   Mermaid accepts duplicate attribute names and the comments keep them apart.
 *
 * ## Relationships
 * `PARENT <parent-token>--<child-token> CHILD : "<fk columns joined with ', '>"` — one line per
 * foreign key (a composite key is one line; self-references are emitted). Tokens come from
 * [CardinalityInference]: parent end `||` (exactly one) or `|o` (zero or one); child end `o{`
 * (many) or `o|` (one). Foreign keys whose parent table is not in the database are skipped.
 * Entity names follow the entity-name rule above; the label holds the raw column names with the
 * same in-quote replacements.
 */
object MermaidGenerator {

    private const val INDENT = "    "
    private val PLAIN_ENTITY_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val NON_WORD = Regex("[^A-Za-z0-9_]")

    fun generate(database: Database): String {
        val out = StringBuilder("erDiagram\n")

        database.entities.sortedBy { it.name }.forEach { appendEntity(out, it) }

        val relationships = CardinalityInference.infer(database)
            .filter { database.table(it.parentTable) != null }
            .sortedWith(
                compareBy<Relationship> { it.parentTable }
                    .thenBy { it.childTable }
                    .thenBy { it.foreignKey.columns.joinToString(",") }
                    .thenBy { it.foreignKey.referencedColumns.joinToString(",") },
            )
        if (relationships.isNotEmpty()) {
            out.append('\n')
            relationships.forEach { appendRelationship(out, it) }
        }
        return out.toString()
    }

    private fun appendEntity(out: StringBuilder, entity: SchemaEntity) {
        val name = entityName(entity.name)
        when (entity) {
            is View -> {
                out.append(INDENT).append("%% view\n")
                out.append(INDENT).append(name).append('\n')
            }
            is Table -> {
                entity.fts?.let { fts ->
                    out.append(INDENT).append("%% ").append(singleLine(fts.version)).append(" virtual table")
                    fts.contentTable?.let { out.append(" (content table: ").append(singleLine(it)).append(')') }
                    out.append('\n')
                }
                if (entity.columns.isEmpty()) {
                    out.append(INDENT).append(name).append('\n')
                } else {
                    out.append(INDENT).append(name).append(" {\n")
                    entity.columns.forEach { appendAttribute(out, entity, it) }
                    out.append(INDENT).append("}\n")
                }
            }
        }
    }

    private fun appendAttribute(out: StringBuilder, table: Table, column: Column) {
        val name = identifier(column.name)
        out.append(INDENT).append(INDENT).append(identifier(column.type)).append(' ').append(name)

        val keys = buildList {
            if (table.isPrimaryKey(column.name)) add("PK")
            if (table.isForeignKey(column.name)) add("FK")
            if (table.isUnique(column.name)) add("UK")
        }
        if (keys.isNotEmpty()) out.append(' ').append(keys.joinToString(", "))
        if (name != column.name) out.append(' ').append(quoted(column.name))
        out.append('\n')
    }

    private fun appendRelationship(out: StringBuilder, relationship: Relationship) {
        val parentToken = when (relationship.parentEnd) {
            ParentCardinality.EXACTLY_ONE -> "||"
            ParentCardinality.ZERO_OR_ONE -> "|o"
        }
        val childToken = when (relationship.childEnd) {
            ChildCardinality.MANY -> "o{"
            ChildCardinality.ONE -> "o|"
        }
        out.append(INDENT)
            .append(entityName(relationship.parentTable))
            .append(' ').append(parentToken).append("--").append(childToken).append(' ')
            .append(entityName(relationship.childTable))
            .append(" : ")
            .append(quoted(relationship.foreignKey.columns.joinToString(", ")))
            .append('\n')
    }


    private fun entityName(raw: String): String = if (PLAIN_ENTITY_NAME.matches(raw)) raw else quoted(raw)

    private fun identifier(raw: String): String {
        val replaced = NON_WORD.replace(raw, "_")
        return if (replaced.isEmpty() || replaced[0] in '0'..'9') "_$replaced" else replaced
    }

    private fun quoted(raw: String): String = '"' + singleLine(raw).replace('"', '\'') + '"'

    private fun singleLine(raw: String): String =
        buildString(raw.length) { raw.forEach { append(if (it.isISOControl()) ' ' else it) } }
}
