# Handover notes – `:export`

Status: **finished**. `./gradlew :export:test` passes (29 tests). Output was also accepted by Mermaid's own parser. Written from the export agent's final report.

## What exists
- `export/src/main/kotlin/com/oguzhanaslann/roomer/export/MermaidGenerator.kt` – `MermaidGenerator.generate(database): String`.
- `export/src/test/kotlin/.../Fixtures.kt` – model fixtures built in code (`fx-basic` equivalent, `fx-edge`, empty).
- `export/src/test/kotlin/.../MermaidGeneratorTest.kt`.
- `export/src/test/resources/golden/` – `fx-basic.expected.mmd`, `fx-edge.expected.mmd`, `empty.expected.mmd` (byte-for-byte).

## Format rules
- `erDiagram`, entity blocks, one blank line, relationship lines (blank line only if there are relationships). 4-space indent, `\n`, one trailing newline. Empty database → `erDiagram\n`. No database name/version/hash/path in the output.
- Entities (tables, FTS, views together) sorted by raw name with `String.compareTo` (upper case before lower case). Columns in declared order. Relationships sorted by parent, child, FK columns, referenced columns. Shuffling the model does not change the output.
- Table with columns → `name { … }`. View or column-less table → bare name line. `%% view` before views; `%% FTS4 virtual table` (+ ` (content table: posts)`) before FTS tables.
- Entity name bare if it matches `[A-Za-z_][A-Za-z0-9_]*`, otherwise double-quoted. Inside quotes `"` → `'`, control characters → space.
- Attribute: `type name [keys] ["comment"]`, keys in fixed order `PK, FK, UK`, comma-separated.
  - `PK` = part of the primary key. `FK` = part of any foreign key (even to a missing table).
  - `UK` = the **sole** column of at least one unique index. Members of multi-column unique indexes are not marked.
  - Type and name are sanitised: characters outside `[A-Za-z0-9_]` → `_`, `_` prefixed if empty or starting with a digit. If a name was changed, the original is appended as a comment.
- Relationship: `PARENT <tok>--<tok> CHILD : "col1, col2"`, from `CardinalityInference`. Parent `||` / `|o`, child `o{` / `o|`. One line per FK. Self-references emitted. FKs to tables not in the database are skipped.

## Open
- **AC-19.1 fixture reconciliation**: the golden `fx-basic` was built from the agent's own in-code model because `parser/.../fixtures/fx-basic.json` did not exist yet. The two `fx-basic` definitions differ (the export one has `order-items`, `active_users`, `posts_fts`). Add an end-to-end test in the root project: parse the parser's `fx-basic.json` → `MermaidGenerator.generate` → compare with a golden file; decide on one shared fixture.
- **UK vs diagram marker**: `Table.isUnique` in the model means "part of any unique index", broader than the Mermaid `UK` rule. Make the canvas marker and the export agree (suggestion: add `isSoleUnique`-style helper to the model and use it in both).
- Entities named like Mermaid keywords (e.g. `erDiagram`) are not quoted.
