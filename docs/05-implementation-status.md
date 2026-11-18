# RoomEr – Implementation Status and Handover

Updated: 2026-11-17 (real-schema check) · Status: **feature-complete, all automated checks green; not yet run in a live IDE** · Per-module detail: `docs/notes/*.md`

## 1. Where things stand

| Module | Status | Tests | Notes |
|---|---|---|---|
| `:model` (model, cardinality inference, search) | finished | 13 pass | this file §3 |
| `:parser` | finished; validated against 287 real Room exports (Room 1.x era to Room 3 and KMP) | 66 pass, 1 skipped (corpus sweep, runs with `ROOMER_REAL_SCHEMAS_DIR`) | `notes/parser.md` |
| `:export` (Mermaid) | finished | 31 pass | `notes/export.md` |
| `:layout` (ELK) | finished | 25 pass | `notes/layout.md` |
| `:render` (Swing canvas) | finished; demo images inspected in light, dark and high-contrast | 60 pass | `notes/render.md` |
| root plugin (discovery, state, tool window, navigation) | finished; panel exercised in platform tests | 71 pass | `notes/plugin.md` |

- `./gradlew check buildPlugin` passes from scratch (`--rerun-tasks`). Plugin zip: `build/distributions/RoomEr-0.0.2.zip`, 8.6 MB.
- `./gradlew verifyPlugin`: **Compatible** with Android Studio 2026.1.4 (Quail, AI-261.26222.65) and 2026.2.2 (Rabbit, AI-262.10968.63),
  with no deprecated, experimental or internal API usages.
- End-to-end load (parse + measure + ELK layout), 100 tables / 1000 columns: 406 ms first run, 17–30 ms warm (budget 2000 ms, AC-21.1).
- Canvas at that size, software rendering: at most 3.5 ms per frame while panning or zooming; up to about 11 ms for a one-off
  full-quality frame (within the 16.7 ms budget for 60 fps; on-screen fps not measured).
- **Real-world check (2026-11-17):** a production app's schema (6 tables, composite PKs, composite FK, self-reference)
  went through parse → Mermaid → layout → render → discovery path matching with no errors (≈245 ms cold). Three UX rough
  edges found, listed in §4 item 2.
- **Nothing has been run in a live IDE window.**
- Git: `main` on GitHub (private): https://github.com/oguzhanaslann/RoomEr. Releases: `v0.0.1` (pre-release), `v0.0.2` (icon and Marketplace listing; built by the `Release` workflow).
- Lessons learned while building this: `docs/06-learnings.md`.

## 2. Toolchain (this machine)

- No system Java or Gradle. Use the JDK bundled with Android Studio:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (JBR 25), then `./gradlew …` (wrapper, Gradle 9.9.1).
- Kotlin 2.4.21, IntelliJ Platform Gradle Plugin 2.20.1, JVM target 21, Kotlin `jvmDefault = NO_COMPATIBILITY` in the root project
  (otherwise the compiler emits bridges that the verifier reports as deprecated/experimental API use).
- Local builds compile against the installed Android Studio 2026.2.1 via `local.properties` (`platformLocalPath=…`). Without it
  (e.g. CI) the build downloads `androidStudioVersion=2026.2.2.9` from `gradle.properties`.
- `pluginSinceBuild=261`, no `until-build`. Verifier targets: `pluginVerifierIdes=2026.2.2.9,2026.1.4.8` (update when a new stable line ships).
- `kotlin.stdlib.default.dependency=false`: the stdlib is `compileOnly` in the logic modules, because the IDE provides it.

## 3. Architecture decisions made during implementation

- **Gradle modules enforce the UI-free rule (AC-24.1).** `model`, `parser`, `layout`, `export` are plain Kotlin/JVM modules;
  `checkUiFreeModules` (part of `check`) also fails on Swing/AWT/IntelliJ imports there. `render` is pure Swing with no IntelliJ
  dependency, so it is tested and demoed outside the IDE (`:render:runDemo`, `:render:renderDemo`).
- **No network (AC-23.2):** `checkNoNetworkAccess` (part of `check`) fails on bundled HTTP/telemetry libraries and on network calls
  in our sources. CI workflow: `.github/workflows/build.yml` runs `check buildPlugin verifyPlugin`.
- **Inference and search live in `:model`**, because export, render and the plugin all need them.
- **Unique/indexed rule, one definition** (`Table.isUnique` / `isIndexed`): `UQ`/`UK` = sole column of a unique index;
  `IDX` = part of any other index, including a multi-column unique index. Diagram badges and Mermaid keys use the same rule.
- **Crow's-foot ends match the Mermaid tokens** (AC-19.3): parent `||` exactly one / `|o` zero or one; child `o{` many / `o|` one,
  drawn as circle + bar.
- **Node ids are entity names** everywhere (layout, canvas, persisted positions).
- **Layout returns positions only.** The canvas routes edges itself, since nodes are draggable.
- **Node sizes come from the canvas** (`DiagramMetrics.measure`), then go into `LayoutRequest`.
- **Reload (FR-07)** passes current positions as `LayoutRequest.fixedPositions`; the engine places only new nodes.
- **Theme (FR-26)**: the canvas resolves colours from `UIManager` at paint time, which IntelliJ's look-and-feel populates.
- **Navigation** uses Java PSI / light classes only. No dependency on the Kotlin or Android plugins.
- **Shared fixtures:** the export golden tests parse the parser's JSON fixtures (`fx-basic`, `fx-views-fts`) end to end (AC-19.1).
- **Plugin name** is `RoomEr - Room ER Diagrams` with a hyphen: the verifier rejects the en dash from decision D24 in plugin names.

## 4. Remaining work, in order

1. **First live run** (`./gradlew runIde`), with a real Room project open. Walk through: first open and empty state (both links),
   folder chooser, database switch, auto-reload on rebuild, stale banner, search keys (Up/Down/Enter/Esc, `/`), focus mode,
   collapse, drag + restart (positions restored), reset layout, copy/save Mermaid (incl. cancel), go to source on a **Kotlin**
   `@Entity` and the multi-candidate chooser, theme switch (light/dark/high contrast), 300 ms loading indicator, tool window icon
   in the New UI, narrow tool-window widths. Also confirm ELK runs under the plugin classloader and the bundled Guava causes no conflict.
2. Canvas fixes found on the real schema (`notes/render.md` §7): move/hide the legend when it covers a table after
   zooming in; fan out parent-end symbols when several FKs end on the same row; optionally re-layout after collapse-all.
3. Manual rendering checklist and on-screen 60 fps measurement at 100 tables (AC-13.1, AC-21.2). Record results in the release checklist.
4. Optional canvas improvements (see `notes/render.md`): cache the edge layer as an image (full-quality frames ~11 ms → lower),
   obstacle-avoiding edge routing, draw FTS5 `contentRowId` links (not in the model yet).
5. Release items (icon, listing text, change notes from `CHANGELOG.md` and the tag-triggered `Release` workflow are done; first Marketplace upload is manual): Marketplace name availability (R4), licence headers in source files, sign-off of the EPL/Apache bundling check
   (R2, `NOTICE` lists everything bundled), git remote + CI run, Marketplace description/screenshots.

## 5. How to resume with agents

Give a fresh agent: this file, the module's note in `docs/notes/`, and `docs/02-spec.md` + `docs/04-acceptance-criteria.md`.
Keep one agent per Gradle module so builds do not collide, and tell each to run only its own module's tasks (`:render:test`,
root `:test`, …). Only one agent at a time should touch root build files.
