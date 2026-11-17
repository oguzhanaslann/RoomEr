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
- ~~AC-19.1 fixture reconciliation~~ **Done (2026-11-17).** The export tests now parse the parser's JSON fixtures end to end: `fx-basic.expected.mmd` and `fx-views-fts.expected.mmd` come from `parser/src/test/resources/fixtures/{fx-basic,fx-views-fts}.json` (shared via `sourceSets.test.resources.srcDir` and a test-scope dependency on `:parser`). The old in-code fixture's golden is now `model-basic.expected.mmd`. Expected files were verified by hand against the JSON.
- ~~UK vs diagram marker~~ **Done (2026-11-17).** One rule in the model: `Table.isUnique` = sole column of a unique index (UK / UQ); `Table.isIndexed` = part of any other index, including a multi-column unique index (IDX). `MermaidGenerator` calls `table.isUnique`; the canvas uses the same rule in `buildTable`.
- Verified on a real production schema (2026-11-17, a 6-table app database with composite PKs, a composite FK and a nullable self-reference): every token and key marker was correct. FK actions and non-unique indexes do not appear in the output because Mermaid has no syntax for them (by design).
- Entities named like Mermaid keywords (e.g. `erDiagram`) are not quoted.
