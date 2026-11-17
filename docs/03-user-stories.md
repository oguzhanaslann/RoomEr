# RoomEr – User Stories (v1)

Priority: **P0** = must-have for v1 · **P1** = should-have · P2 = nice-to-have.
Personas: **Dev** (any Android developer using Room), **Newcomer** (new to the codebase), **Reviewer** (tech lead/PR reviewer).

## Epic 1 – Discovery and loading

| ID | Story | Pri | Spec |
|----|-------|-----|------|
| US-01 | As a **Dev**, I want the plugin to find my Room schema files automatically so that I can open the diagram without configuration. | P0 | FR-01, FR-02 |
| US-02 | As a **Dev**, I want to pick a schema folder manually when auto-discovery finds nothing, so that unusual project setups still work. | P0 | FR-03 |
| US-03 | As a **Dev** with several databases or modules, I want to choose which database to view, so that each diagram stays readable. | P0 | FR-04 |
| US-04 | As a **Newcomer**, I want to see the database name and schema version, so that I know which schema I'm looking at. | P0 | FR-02, FR-05 |
| US-05 | As a **Dev**, I want the diagram to update when the schema JSON changes, so that it never shows stale data after a build. | P0 | FR-06, FR-07 |
| US-06 | As a **Newcomer**, I want clear guidance when no schema is found or a file is broken, so that I can fix the setup myself. | P0 | FR-10, FR-12, FR-40–42 |

## Epic 2 – Diagram

| ID | Story | Pri | Spec |
|----|-------|-----|------|
| US-07 | As a **Newcomer**, I want each table shown with its columns, types, PK, FK and unique/index markers, so that I understand a table without opening its class. | P0 | FR-11, FR-20 |
| US-08 | As a **Newcomer**, I want relationships drawn in crow's-foot notation, so that I can see one-to-one vs one-to-many and optionality at a glance. | P0 | FR-21 |
| US-09 | As a **Dev**, I want views and FTS tables styled differently, so that I don't mistake them for plain tables. | P1 | FR-22 |
| US-10 | As a **Newcomer**, I want an automatic, readable layout, so that I get a useful picture without arranging anything. | P0 | FR-23 |
| US-11 | As a **Dev**, I want to pan and zoom, so that I can explore large diagrams. | P0 | FR-24 |
| US-12 | As a **Reviewer**, I want to drag tables into a meaningful arrangement that is remembered, so that I don't redo it every session. | P1 | FR-25 |
| US-13 | As a **Dev**, I want the diagram to match my IDE theme, so that it's comfortable in light, dark or high-contrast mode. | P0 | FR-26, NFR-05 |

## Epic 3 – Navigation and large schemas

| ID | Story | Pri | Spec |
|----|-------|-----|------|
| US-14 | As a **Newcomer**, I want to search tables and columns, so that I can jump straight to what I'm looking for. | P0 | FR-30 |
| US-15 | As a **Newcomer**, I want a focus mode showing one table and its neighbours, so that I can understand a part of a large schema. | P0 | FR-31 |
| US-16 | As a **Dev**, I want to collapse column lists, so that big schemas fit on screen. | P1 | FR-32 |
| US-17 | As a **Dev**, I want to jump from a table to its `@Entity` class, so that I can move from diagram to code. | P0 | FR-33 |
| US-18 | As a **Dev**, I want to operate the diagram from the keyboard, so that I don't have to leave the home row. | P1 | FR-34, NFR-05 |

## Epic 4 – Export

| ID | Story | Pri | Spec |
|----|-------|-----|------|
| US-19 | As a **Reviewer**, I want to copy the schema as Mermaid text, so that I can paste it into a PR, wiki or docs. | P1 | FR-35, FR-36 |
| US-20 | As a **Reviewer**, I want to save the schema as a `.mmd` file, so that I can keep it with documentation. | P1 | FR-35, FR-36 |

## Epic 5 – Quality attributes

| ID | Story | Pri | Spec |
|----|-------|-----|------|
| US-21 | As a **Dev** with a large schema, I want the diagram to load quickly and stay smooth, so that the tool is faster than reading code. | P0 | NFR-01 |
| US-22 | As a **Dev**, I want the plugin to work on current Android Studio releases and fail clearly on unsupported schema formats. | P0 | NFR-02, FR-10 |
| US-23 | As a **Dev** in a security-conscious company, I want the plugin to be read-only and offline, so that I can approve it for use. | P0 | NFR-03, NFR-04 |
| US-24 | As a **maintainer**, I want logic separated from the UI and covered by fixture tests, so that I can extend it safely. | P1 | NFR-06, NFR-07, §5 |

## MVP cut
All **P0** stories form the minimum shippable release. P1 stories (US-09, 12, 16, 18, 19, 20, 24) are in v1 scope but may slip to 1.1 if the schedule requires. Version diffing, migration planning and image export remain out of scope.
