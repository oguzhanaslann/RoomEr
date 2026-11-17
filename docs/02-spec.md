# RoomEr – Specification (v1)

Status: draft for implementation · Derived from `01-discovery-notes.md`

## 1. Overview
RoomEr is an Android Studio plugin (Kotlin, Apache-2.0). It discovers Room exported schema JSON files in the open project and renders the latest version of a chosen database as an interactive ER diagram in a "Room ER" tool window. It is read-only and offline.

## 2. Architecture

```
Room schema JSON ──► Discovery ──► Parser ──► Schema Model ─┬─► Cardinality inference ─► Layout (ELK) ─► Swing Canvas
                                                            ├─► Mermaid Generator ─► clipboard / .mmd
                                                            └─► Entity Resolver (PSI) ─► navigation
```

| Module | Responsibility | UI-free |
|--------|----------------|:------:|
| `discovery` | Find schema folders/files, pick latest version per database, watch for changes | ✔ (VFS API only) |
| `parser` | JSON → `SchemaModel`, with errors typed | ✔ |
| `model` | `Database`, `Table`, `Column`, `PrimaryKey`, `ForeignKey`, `Index`, `View`, `FtsTable`, `Relationship` | ✔ |
| `inference` | Relationship cardinality from the model | ✔ |
| `layout` | `LayoutEngine` interface + ELK implementation | ✔ |
| `render` | Swing canvas, hit-testing, zoom/pan, theme, keyboard | – |
| `export` | Mermaid `erDiagram` text generation | ✔ |
| `navigation` | Table → `@Entity` PSI lookup, fallback to JSON | – |
| `toolwindow` | Toolbar, dropdown, search box, empty/error states, state persistence | – |

Constraints: parsing, layout and PSI lookup run off the EDT (background tasks). The model is immutable. The renderer only reads the model and layout results.

## 3. Functional requirements

### 3.1 Discovery and loading
- **FR-01** Scan all project modules for files matching `**/schemas/<databaseName>/<n>.json`, where `<n>` is an integer.
- **FR-02** Group files by database (`<databaseName>`, normally the fully qualified `@Database` class). Choose the highest `<n>` as the current version.
- **FR-03** If nothing is found, show the empty state (FR-40) and a "Choose schema folder…" action. A chosen folder is remembered per project.
- **FR-04** If several databases are found, show a dropdown labelled with the simple class name (full name in tooltip). One database is rendered at a time. The last selection is remembered.
- **FR-05** Show the database name and schema version in the header.
- **FR-06** Watch discovered schema files. On create/modify/delete, re-parse and re-layout in the background, then update the diagram. A toolbar "Refresh" action does the same on demand.
- **FR-07** On reload, preserve zoom, viewport and positions of tables that still exist. New tables are auto-placed. Removed tables are dropped.

### 3.2 Parsing
- **FR-10** Support `formatVersion` 1. For other values, show an "unsupported format" error (FR-41).
- **FR-11** Extract per entity: table name, columns (name, affinity/type, notNull, default value), primary key (columns, autoGenerate), indices (name, unique, columns), foreign keys (referenced table, columns, referenced columns, onDelete, onUpdate), and FTS markers. Extract per view: name and SQL.
- **FR-12** Malformed JSON, missing required fields and unknown references are reported with the file path and a human-readable reason. They never crash the IDE.
- **FR-13** Field extraction is validated against real fixtures from several Room versions before release (see risk R5).

### 3.3 Diagram
- **FR-20** Each table is a node with a header (table name), a column list (name, type), and markers for PK, FK, unique/indexed, and not-null.
- **FR-21** Each foreign key is a crow's-foot edge from the child to the parent.
  - Parent end: *exactly one* if all FK columns are not-null, otherwise *zero or one*.
  - Child end: *one* if the FK columns exactly match the child's primary key or are covered by a unique index (one-to-one), otherwise *many*.
  - Hovering an edge shows the FK columns, the referenced columns and the `ON DELETE` / `ON UPDATE` actions. The FK columns are highlighted in the child table.
- **FR-22** Views and FTS tables use visually distinct node styles from tables, with a legend.
- **FR-23** Initial placement uses ELK layered layout behind a `LayoutEngine` interface.
- **FR-24** Pan (drag on background, or middle mouse), zoom (wheel, +/- keys, fit-to-window) and select.
- **FR-25** Tables can be dragged. Positions are stored per database (key: database name + table name) in project workspace state, never in version-controlled files. A "Reset layout" action discards them and re-runs auto-layout.
- **FR-26** Colours and fonts come from the IDE theme (light, dark, high-contrast). No hard-coded colours outside theme-aware lookups.

### 3.4 Navigation and large schemas
- **FR-30** A search box finds tables and columns by substring, case-insensitively. Selecting a result centres and highlights it.
- **FR-31** Focus mode shows one selected table plus its direct FK neighbours (both directions), dims or hides the rest, and can be toggled off to restore the full view.
- **FR-32** The column list of each table can be collapsed. Collapse-all and expand-all are available. Collapsed state is remembered.
- **FR-33** "Go to source" (double-click, context menu, Enter) opens the `@Entity` class, resolved by (1) `@Entity(tableName = …)` match, (2) class simple name match, in the order given, searching the project scope. If there is no match it opens the schema JSON at that entity. Views resolve via `@DatabaseView` in the same way.
- **FR-34** Keyboard: arrow keys move selection between connected tables, Enter = go to source, `/` or Ctrl/Cmd+F = search, `+`/`-` = zoom, `0` = fit, Esc = clear search/focus.

### 3.5 Export
- **FR-35** Toolbar actions "Copy as Mermaid" and "Save as .mmd…" produce Mermaid `erDiagram` text from the model.
- **FR-36** Mermaid format:
  - One entity per table, quoted if the name has non-word characters, with attributes `type name [PK|FK|UK]`.
  - Relationships as `PARENT <parent-token>--<child-token> CHILD : "<fk column(s)>"` using crow's-foot tokens from FR-21 (`||` / `|o` for the parent end, `o{` / `o|` for the child end).
  - Views and FTS tables are emitted as plain entities, preceded by a `%%` comment stating their kind.
  - Output is deterministic (stable ordering) so it diffs cleanly.

### 3.6 Error and empty states
- **FR-40** No schemas found: explain `exportSchema = true` and `room.schemaLocation`, link to the Room documentation, and offer the folder picker.
- **FR-41** Parse or format error: show the file path and reason. If a previous diagram was shown, keep it and mark it stale.
- **FR-42** Schema folder exists but contains no valid files: show a distinct message naming the folder.

## 4. Non-functional requirements
- **NFR-01 Performance** A 100-table / 1000-column schema is parsed and laid out in under 2 s (reference hardware: 2020+ laptop). Pan and zoom hold 60 fps at that size. The EDT is never blocked by parsing, layout or PSI search. Show a progress indicator if loading exceeds 300 ms.
- **NFR-02 Compatibility** Latest two stable Android Studio releases. `until-build` is left open. Room JSON `formatVersion` 1.
- **NFR-03 Privacy** No network calls, telemetry or analytics. Project data is never transmitted.
- **NFR-04 Read-only** The plugin never modifies project files. The only writes are the user-initiated `.mmd` save and IDE workspace state.
- **NFR-05 Accessibility** Full keyboard operation (FR-34), theme-aware colours, and selection is never conveyed by colour alone.
- **NFR-06 Robustness** Invalid input produces typed errors, never uncaught exceptions. The plugin passes the IDE's plugin verifier for the supported builds.
- **NFR-07 Maintainability** Logic modules marked UI-free have no dependency on Swing or the IDE UI. The layout engine is swappable.
- **NFR-08 Licensing** Apache-2.0. Third-party dependencies (notably ELK, EPL-2.0) are checked for compatibility.

## 5. Testing strategy
| Layer | Approach |
|-------|----------|
| Parser/model | Unit tests over real JSON fixtures from several Room versions: FKs, composite PKs, views, FTS, malformed input |
| Mermaid generator | Golden-file tests (fixture → expected `.mmd`) |
| Cardinality, layout interface | Unit tests without UI |
| Discovery, reload, navigation | IntelliJ Platform test framework |
| Rendering | Manual checklist (no pixel tests in v1) |
| Performance | Synthetic 100-table fixture checked against NFR-01 |

## 6. Out of scope (v1)
See `01-discovery-notes.md` §3.

## 7. Requirement traceability
Every FR/NFR is covered by at least one user story in `03-user-stories.md`, and every story has acceptance criteria in `04-acceptance-criteria.md`.
