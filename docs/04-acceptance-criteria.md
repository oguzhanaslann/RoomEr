# RoomEr – Acceptance Criteria (v1)

Format: Given / When / Then. Fixtures referenced (`fx-*`) live in the test resources and are defined in the spec's testing strategy.

## Epic 1 – Discovery and loading

**US-01 Auto-discovery**
- AC-01.1 Given a project with `app/schemas/com.acme.AppDb/1.json` and `2.json`, when I open the tool window, then the diagram shows version 2 of `AppDb` with no configuration.
- AC-01.2 Given schema files in two modules, when I open the tool window, then both databases are discovered.
- AC-01.3 Given a file named `schemas/x/notanumber.json`, then it is ignored.

**US-02 Manual folder**
- AC-02.1 Given no schemas are discovered, when I choose "Choose schema folder…" and pick a valid folder, then the diagram loads and the folder is remembered after IDE restart.
- AC-02.2 Given I pick a folder without valid schema files, then I see the message from AC-06.3 and nothing is loaded.

**US-03 Database dropdown**
- AC-03.1 Given three databases, then the dropdown lists them by simple class name, with the full name as tooltip.
- AC-03.2 When I select another database, then only its diagram is shown and the choice is remembered for the next session.
- AC-03.3 Given exactly one database, it is selected automatically.

**US-04 Header**
- AC-04.1 The header always shows the database name and schema version of the diagram on screen.

**US-05 Auto-reload**
- AC-05.1 Given a diagram is open, when the current schema file changes on disk, then the diagram updates within 2 s without user action.
- AC-05.2 When a new higher-numbered version file appears, then the header version updates to it.
- AC-05.3 After a reload, existing tables keep their positions, new tables are placed automatically, and removed tables disappear. Zoom and viewport are unchanged.
- AC-05.4 When I press "Refresh", the same reload happens.
- AC-05.5 The IDE stays responsive during reload (see AC-21.3).

**US-06 Errors and empty states**
- AC-06.1 Given no schemas anywhere, then the tool window shows text explaining `exportSchema = true` and `room.schemaLocation`, a documentation link and the folder picker.
- AC-06.2 Given a file with invalid JSON, then I see its path and the parse reason, and no exception dialog appears.
- AC-06.3 Given a schema folder that contains no valid files, then the message names that folder.
- AC-06.4 Given an unsupported `formatVersion`, then the message names the value found and the supported one.
- AC-06.5 Given a previously valid diagram and a file that then becomes invalid, then the last good diagram stays visible and is marked as stale.

## Epic 2 – Diagram

**US-07 Table content**
- AC-07.1 Each table node shows its name and every column with its type.
- AC-07.2 PK columns carry a PK marker. Composite PKs mark each column.
- AC-07.3 FK columns carry an FK marker. Unique or indexed columns carry the matching marker.
- AC-07.4 Not-null is distinguishable from nullable without relying on colour alone.
- AC-07.5 Given `fx-basic`, the node contents match the JSON exactly (names, types, order).

**US-08 Crow's-foot**
- AC-08.1 Given a nullable FK column, the parent end shows *zero or one*. Given a not-null FK, it shows *exactly one*.
- AC-08.2 Given an FK whose columns equal the child's PK, the child end shows *one* (one-to-one). The same applies if a unique index covers the FK columns.
- AC-08.3 Given any other FK, the child end shows *many*.
- AC-08.4 Given a composite FK, a single edge is drawn.
- AC-08.5 Given a self-referencing FK, the edge is drawn and legible, not hidden.
- AC-08.6 When I hover an edge, I see the FK columns, referenced columns, `ON DELETE` and `ON UPDATE`, and the FK columns are highlighted in the child table.

**US-09 Views and FTS**
- AC-09.1 Views and FTS tables render with a style distinct from tables, and the legend explains all three.

**US-10 Auto-layout**
- AC-10.1 On first open, with no saved positions, tables are placed by the layered layout with no overlapping nodes.
- AC-10.2 The layout engine is invoked through the `LayoutEngine` interface, and a test double can replace it.

**US-11 Pan and zoom**
- AC-11.1 Mouse wheel zooms around the cursor, within a defined min/max range.
- AC-11.2 Dragging the background pans. "Fit to window" shows all nodes.

**US-12 Dragging and persistence**
- AC-12.1 I can drag a table, and connected edges follow.
- AC-12.2 After restarting the IDE, dragged positions are restored for that database.
- AC-12.3 Positions are stored in workspace state, and no tracked project file changes (verified in a test).
- AC-12.4 "Reset layout" discards stored positions and re-runs auto-layout.

**US-13 Theme**
- AC-13.1 In light, dark and high-contrast themes all text meets contrast needs, and switching theme at runtime repaints the diagram correctly.

## Epic 3 – Navigation and large schemas

**US-14 Search**
- AC-14.1 Typing `user` finds tables and columns containing it, case-insensitively.
- AC-14.2 Selecting a result centres the viewport and highlights the table (and column, if applicable).
- AC-14.3 No matches shows an explicit "no results" indication.

**US-15 Focus mode**
- AC-15.1 Given a selected table, when I toggle focus, then only it and tables connected by FKs in either direction remain emphasised, and the others are dimmed or hidden.
- AC-15.2 Toggling again or pressing Esc restores the full diagram with the previous viewport.

**US-16 Collapse columns**
- AC-16.1 I can collapse and expand one table. "Collapse all" and "Expand all" work.
- AC-16.2 Edges stay attached to the correct table, and collapsed state survives a reload.

**US-17 Go to source**
- AC-17.1 Given `@Entity(tableName = "users") class UserEntity`, when I double-click the `users` node, then `UserEntity` opens in the editor.
- AC-17.2 Given an entity with no `tableName` attribute, then it resolves by class name match.
- AC-17.3 Given no match, then the schema JSON opens at that entity, with a notification saying why.
- AC-17.4 Given multiple candidate classes, then the user is offered a chooser.
- AC-17.5 Lookup never blocks the UI thread.

**US-18 Keyboard**
- AC-18.1 Arrow keys move selection to connected tables, Enter triggers go to source, `/` or Ctrl/Cmd+F focuses search, `+`/`-` zoom, `0` fits, Esc clears search or focus.
- AC-18.2 The selected node has a visible focus indicator that does not depend on colour alone.

## Epic 4 – Export

**US-19 Copy as Mermaid**
- AC-19.1 Given `fx-basic`, "Copy as Mermaid" puts text on the clipboard that equals `fx-basic.expected.mmd` (golden test).
- AC-19.2 The output begins with `erDiagram`, and attributes appear as `type name [PK|FK|UK]`.
- AC-19.3 Relationships use the same cardinality tokens as the diagram (AC-08.1–08.3) and are labelled with the FK column(s).
- AC-19.4 Table names with special characters are quoted so the output is valid Mermaid.
- AC-19.5 Views and FTS tables are emitted as entities with a preceding `%%` comment naming their kind.
- AC-19.6 Running the export twice on the same input yields byte-identical output.

**US-20 Save as .mmd**
- AC-20.1 "Save as .mmd…" opens a save dialog, and the written file's content equals the clipboard output.
- AC-20.2 Cancelling the dialog writes nothing.

## Epic 5 – Quality attributes

**US-21 Performance**
- AC-21.1 Given a 100-table / 1000-column fixture, parse plus layout completes in under 2 s on reference hardware.
- AC-21.2 At that size, pan and zoom hold 60 fps (manual measurement recorded in the release checklist).
- AC-21.3 Parsing, layout and PSI lookup run off the EDT. No EDT freeze above 100 ms is observed during load.
- AC-21.4 If loading exceeds 300 ms, a progress indicator is shown.

**US-22 Compatibility**
- AC-22.1 The plugin installs and runs on the latest two stable Android Studio releases, and the plugin verifier reports no errors for them.
- AC-22.2 `until-build` is not set to a fixed upper bound.
- AC-22.3 An unknown `formatVersion` produces AC-06.4.

**US-23 Read-only and offline**
- AC-23.1 A test run confirms no project file is modified by any action except the user-initiated `.mmd` save.
- AC-23.2 The codebase contains no HTTP client or telemetry dependency (checked in CI by a dependency check).
- AC-23.3 The only external link is the documentation link, which opens the browser only on user click.

**US-24 Maintainability**
- AC-24.1 The `parser`, `model`, `inference`, `layout` and `export` modules compile without Swing or IDE-UI dependencies (enforced by a dependency rule in the build).
- AC-24.2 Parser tests cover: FKs, composite PK, views, FTS, malformed JSON, unknown `formatVersion`, and missing fields.

## Definition of Done (v1 release)
1. All P0 acceptance criteria pass, and P1 criteria pass or are explicitly deferred in the release notes.
2. Automated tests pass in CI, and the manual rendering checklist is complete on light, dark and high-contrast themes.
3. Plugin verifier is clean on the supported builds.
4. Marketplace name availability confirmed and licence headers and third-party notices in place (ELK/EPL-2.0 check done).
5. README documents `exportSchema = true` setup.
