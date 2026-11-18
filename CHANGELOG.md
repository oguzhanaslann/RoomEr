# Changelog

All notable changes to RoomEr. The section for the version being built becomes the plugin's change notes
in Android Studio and on JetBrains Marketplace, so write entries for users.

Format: one `## [version] - date` heading per release, `### Added` / `### Changed` / `### Fixed` subsections,
`- ` bullets. Inline `code` is allowed.

## [Unreleased]

## [0.0.3] - 2026-11-18

### Added

- Room 3 support: go to source finds classes annotated with `androidx.room3.Entity` and `androidx.room3.DatabaseView`,
  next to Room 2's `androidx.room` ones.
- Per-variant schema folders (`schemas/<variant>/<DatabaseClass>/<n>.json`, from `schemaDirectory("<variant>", …)`)
  are found automatically; the database switcher names the variant.

## [0.0.2] - 2026-11-18

### Added

- Plugin icon for the Plugins dialog and JetBrains Marketplace.
- Fuller plugin description with a getting-started guide, and change notes in the Plugins dialog.

## [0.0.1] - 2026-11-17

### Added

- First preview release.
- **Room ER** tool window showing the Room exported schema as an ER diagram: tables, columns, keys and indices,
  foreign keys in crow's-foot notation, views and FTS3/FTS4/FTS5 tables.
- Automatic layout, draggable tables with remembered positions, search, focus mode, collapsible tables.
- Go to the `@Entity` source from a table.
- Copy or save the diagram as a Mermaid `erDiagram`.
- Automatic reload when the schema files change, several databases per project.
