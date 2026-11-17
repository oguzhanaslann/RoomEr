# Handover notes – `:parser`

Status: **finished**. `./gradlew :parser:test` passes (66 tests: 50 original + 14 real-fixture + 2 sweep). Written from the parser agent's final report.

## What exists
- `parser/src/main/kotlin/com/oguzhanaslann/roomer/parser/SchemaParser.kt` – `SchemaParser.parse(json, databaseName, sourcePath): ParseResult`. Gson tree parsing (strict), manual extraction, never throws.
- `parser/src/test/kotlin/.../SchemaParserTest.kt` – 50 JUnit4 tests (AC-24.2).
- `parser/src/test/kotlin/.../SyntheticSchema.kt` – generator for the 100-table / 1000-column schema (chain of 99 FKs). Cold parse 4 ms, warm 10 ms.
- `parser/src/test/resources/fixtures/` – `fx-basic.json`, `fx-views-fts.json`, `fx-old-room.json`, and `err-*` files (malformed, unsupported format, missing fields, unknown references). These are hand-written.
- `parser/src/test/resources/fixtures/real/` – 12 real Room exports copied unmodified from Apache-2.0 repositories; `README.md` there lists source URL, pinned commit, licence and shape of each.
- `parser/src/test/kotlin/.../RealSchemaFixturesTest.kt` – precise assertions per shape on the real fixtures.
- `parser/src/test/kotlin/.../RealSchemaSweepTest.kt` + `RealSchemaConsistency.kt` – parses every real file and compares the model with an independent Gson read of the raw JSON (table/column/index/FK/view names and counts, affinity, notNull, defaultValue, PK, FTS version and contentTable, view SQL placeholder substitution). Runs over the bundled fixtures always, and over any directory given in the env var `ROOMER_REAL_SCHEMAS_DIR` (skipped when unset).

## Behaviour
- `formatVersion` is checked first. Absent/null → `MissingField("formatVersion")`. Anything but the number 1 → `UnsupportedFormat(found = raw text, max 40 chars)`.
- Required (→ `MissingField` with a path like `database.entities[1].fields[1].columnName`): `database`, `database.version` (integer), `database.entities`, entity `tableName`/`fields`, field `columnName`/`affinity`, index `name`/`columnNames`, FK `table`/`columns`/`referencedColumns`, view `viewName`.
- Optional with defaults: `identityHash`, `views`, `defaultValue`, `notNull` (false), `primaryKey` (+ `columnNames`, `autoGenerate`), `indices`, `foreignKeys`, index `unique` (false), `onDelete`/`onUpdate` ("NO ACTION"), `ftsVersion`, `ftsOptions`, view `createSql` ("").
- A wrong JSON type on an optional field is an error (`MissingField` with a "should be a boolean but is a string" reason); only absent/null uses the default.
- FTS: entity is FTS if it has `ftsVersion` or `ftsOptions` (version defaults to "FTS4"). Empty `contentTable` → null. `contentTable` is not validated.
- Views: `${VIEW_NAME}` / `${TABLE_NAME}` replaced in `View.sql`.
- `UnknownReference`: FK to a table not in the file (views do not count), FK child column, FK referenced column, index column, primary-key column, and FK `columns`/`referencedColumns` length mismatch. A `rowid` primary key is skipped only on FTS tables.
- Unknown JSON members are ignored.

## Things consumers must know
- On FTS tables `PrimaryKey(["rowid"])` can name a column that is not in `Table.columns`; FTS tables can also have an empty primary key (real files, see below).
- `Column.defaultValue` is a raw SQL expression, quotes included (`'Unknown'`, `''`, `NULL`, `(0)`).
- `FtsInfo.version` can be `FTS5` (Room 3).
- Duplicate entity names in one file are not detected (Room cannot generate them).

## Verified against real exports (FR-13 / risk R5 – closed)
287 real files from 6 public Apache-2.0 repositories were run through `SchemaParser.parse` with the sweep test
(`ROOMER_REAL_SCHEMAS_DIR=<dir> ./gradlew :parser:test`): androidx/androidx `room/integration-tests` at
`dc01cda8` (69 files – last commit before the Room 3 move, includes `testapp`, `kotlintestapp` javac + KSP,
`multiplatformtestapp` = KMP), androidx/androidx `room3/integration-tests` at `015c35dd` (38, Room 3),
android/nowinandroid (14), android/architecture-components-samples (6), home-assistant/android (33),
duckduckgo/Android (127). **All 287 parse to `Success` and match the raw JSON.** android/sunflower has no
exported schemas. Corpus spans Room 1.x-era files (no `views` key) to Room 3 / KMP.

Confirmed by the corpus:
- `formatVersion` is `1` in every file, including Room 3 and KMP exports.
- Top-level `database` keys: `version`, `identityHash`, `entities`, `setupQueries` always; `views` only from Room 2.1 on (absent in 86 files) and sometimes `[]`. `setupQueries` is ignored.
- Entities always carry `tableName`, `createSql`, `fields`, `primaryKey`; `indices`/`foreignKeys` are absent in older files. FK objects always have all five members (`table`, `onDelete`, `onUpdate`, `columns`, `referencedColumns`); actions seen: `NO ACTION`, `CASCADE`, `SET NULL`.
- **`notNull` is omitted for nullable columns** by newer Room (2.6+/KSP, Room 3, KMP; 467 fields). Default `false` is correct – checked against the column definitions in `createSql`.
- `defaultValue` is the raw SQL expression: `0`, `(0)`, `'1'`, `''`, `'Unknown'`, `NULL`, `false`, `12.0`, `'[]'`… The parser stores it verbatim (quotes included); renderers must not assume it is unquoted.
- Index `orders` (Room 2.5+) is present on 171 of 300 indices, usually `[]`; ignored (not in the model).
- Key order inside `primaryKey` varies (`columnNames` first vs `autoGenerate` first) – irrelevant to Gson.
- FTS: every FTS entity (105) has both `ftsVersion` and `ftsOptions` (plus `contentSyncTriggers`). Versions seen: `FTS3`, `FTS4`, and **`FTS5` (Room 3)**. Empty `contentTable` (`""`) means none. FTS5 `ftsOptions` adds `contentRowId`, `columnSize`, `detail`.
- FTS primary key is either `["rowid"]` with `autoGenerate: true` (rowid is not a field) or **`[]`** (older Room, and Room 3 FTS5 with external content). Both handled; no non-FTS table ever names `rowid`.
- Views: all 24 real views use `${VIEW_NAME}` (never `${TABLE_NAME}`); after substitution every `View.sql` starts with ``CREATE VIEW `<name>` AS ``.
- FKs may reference a table declared later in the file (androidx AutoMigrationDb) and may be self-referencing (DuckDuckGo `tabs`); both validated fine.
- Unknown members (`fieldPath`, `createSql`, `setupQueries`, `contentSyncTriggers`, `orders`, tokenizer options …) are ignored.

### Parser changes
None were needed: no real file failed, and the model matched the raw JSON for every file. The FR-13 work added
only tests, fixtures and this note.

### Still unverified / caveats
- No real file was found where `ftsVersion` is absent but `ftsOptions` present, so the `FTS4` default is untested against reality (harmless).
- A real file with a wrong JSON type on an optional field was never seen; the strict "wrong type → `MissingField`" rule therefore has no real counter-example, but also no real-file evidence that it is needed to be lenient.
- Room versions are inferred from file shape (and repo history position), not from each file's generating Room version. Pre-1.0 alpha exports and exports with `@Embedded`/`@Relation` beyond what the androidx test apps cover were not specifically sought.
- Not in the model (reported, not changed): FTS5 `ftsOptions.contentRowId` (the external-content column that maps to the FTS rowid, e.g. `SongFts` → `Song.songId`), index `orders`, other `ftsOptions`, and `FtsInfo`'s KDoc still says "`FTS3` or `FTS4`" although Room 3 writes `FTS5`.

## Open
- Build file: Gson's `error_prone_annotations` is excluded in `parser/build.gradle.kts`; `:parser:test` has passed with it since.
