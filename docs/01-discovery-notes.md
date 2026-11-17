# RoomEr – Discovery Notes

Date: 2026-11-10 · Format: structured interview (grill-me) · Status: agreed

## 1. Vision
An Android Studio plugin that reads the schema files Room exports and shows them as an interactive ER diagram, so a human can understand a database faster than by reading entity code.

## 2. Decisions and rationale

| # | Topic | Decision | Rationale / rejected options |
|---|-------|----------|------------------------------|
| D1 | Schema source | Room exported JSON (`exportSchema = true`) | Complete (FKs, indices, views), versioned, no code parsing. Rejected: PSI-only (fragile, replicates Room's naming rules), both-with-fallback (double effort), live SQLite file (needs running app, not what code declares). |
| D2 | Platform | Android Studio only, latest two stable releases | Smaller test matrix. IntelliJ IDEA and KMP Room are out of scope. |
| D3 | Users | Onboarding, schema designers and reviewers all matter, but **v1 = comprehension only** | "All equally" was challenged. Diffing, migration planning and export for sharing were deferred, except Mermaid export (D9). |
| D4 | UI surface | Dockable tool window, interactive | Editor-tab viewer rejected for v1. |
| D5 | Discovery | Auto-scan modules for `schemas/*/<n>.json`, manual folder picker as fallback | Reading Gradle `room.schemaLocation` rejected as brittle. |
| D6 | Multi-DB | One database per diagram, dropdown | A merged canvas would be unreadable. |
| D7 | Versions | Latest version only in v1 | Version picker comes with diffing (v2). |
| D8 | Content | Tables, columns, PK/FK/index, views, FTS (distinct styles). No DAOs | DAOs and queries are not in the JSON. |
| D9 | Mermaid | Generated from the internal model as an **export** (copy and save `.mmd`), not the renderer | Mermaid as renderer (via JCEF) would break click-to-navigate, focus mode, drag/saved positions and the performance targets, since `erDiagram` has no per-node interaction. A hybrid means building the renderer twice. |
| D10 | Rendering | Custom Swing/Java2D canvas, IDE theme | Native feel, full control of hit-testing and zoom. JCEF and JGraphX rejected. |
| D11 | Layout | ELK layered, behind an interface | Handles edge routing. Force-directed is messy on FK-heavy schemas. |
| D12 | Notation | Crow's-foot; cardinality inferred | See spec FR-21. |
| D13 | Layout persistence | Per-database positions in workspace state (`.idea/`, not committed) | Reset-layout button re-runs auto-layout. |
| D14 | Freshness | Auto-reload on file change, plus manual refresh | Positions preserved where possible. |
| D15 | Navigation | Best-effort jump to `@Entity` class, fallback to JSON | The JSON has no class names, so a code search is needed. |
| D16 | Large schemas | Search, focus mode (table + FK neighbours), collapsible columns | Needed for 50+ tables. |
| D17 | Errors | Explicit empty and error states with guidance | "No schemas exported" is the likely first-run failure. |
| D18 | Performance | 100 tables / 1000 columns laid out < 2 s, 60 fps pan/zoom, background threads | Becomes acceptance criteria. |
| D19 | Compatibility | JSON `formatVersion` 1, open-ended `until-build` | Unknown versions fail clearly. |
| D20 | Stack and distribution | Kotlin, IntelliJ Platform Gradle Plugin, JetBrains Marketplace, Apache-2.0 | |
| D21 | Posture | Read-only, offline, no telemetry | |
| D22 | Testing | Fixture-based parser tests, golden-file Mermaid tests, unit tests for layout and cardinality, IntelliJ platform tests for integration, manual rendering checklist | Logic kept UI-free. |
| D23 | Accessibility | Keyboard navigation, light/dark/high-contrast via IDE theme | Cheap if designed in early. |
| D24 | Name | RoomEr ("RoomEr – Room ER Diagrams"), tool window "Room ER", package `com.oguzhanaslann.roomer` | Check Marketplace for name clashes before publishing. |

## 3. Explicit non-goals (v1)
Schema version diffing or history · migration generation or planning · SQL execution · live DB inspection · DAO/query display · editing schemas or code · telemetry or network access · IntelliJ IDEA and KMP Room support · image export (PNG/SVG) · pixel-level rendering tests.

## 4. Open items / risks
- **R1** Entity-class lookup is heuristic (table name may differ from class name via `@Entity(tableName=...)`). Mitigation: match on annotation attribute first, then class name, then fall back to JSON.
- **R2** ELK integration size and licence (EPL-2.0) must be checked against Apache-2.0 distribution.
- **R3** Large-schema legibility depends on focus mode and search being good. Validate early with a 100-table fixture.
- **R4** Marketplace name availability for "RoomEr" is unverified.
- **R5** The JSON field layout must be validated against real fixtures from multiple Room versions (FTS and view shapes in particular) before the parser is finalised.
- **R6** Room KMP may relocate schema output. This is out of scope but should not block a later extension.

## 5. Possible v2 backlog
Version picker and diff view · migration impact view · PNG/SVG export · IntelliJ IDEA support · KMP Room.
