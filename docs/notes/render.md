# `:render` module – handover notes (checkpoint)

State at checkpoint (pass 2): **feature-complete against the brief, compiles without warnings, `./gradlew :render:test` =
60 tests, 0 failures** (DiagramCanvasTest 35, SceneTest 15, NavigationTest 6, ViewportTest 4). `:render:renderDemo` writes
21 PNGs, all inspected in pass 2. No public member of the original contract is a stub; no `TODO` remains in `render/`.
Only files under `render/` were touched (plus this note).

Pass 2 changed: edge router (symbol ordering at shared anchors, collapsed self-loops on the top border), batched edge
painting + cheap "interactive" frames while panning/zooming, keyboard focus ring, focus-mode click no longer moves
the viewport, fit-to-window avoids the legend, UQ/IDX legend text + tooltips for the lead's badge rule, `reshape`
override replaced by `setBounds`, listener KDoc describes the actual event semantics.

Build:
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/univenn/Desktop/RoomEr
./gradlew :render:test          # headless unit tests, prints "[render perf]" lines
./gradlew :render:renderDemo    # PNGs -> render/build/demo/
./gradlew :render:runDemo       # JFrame for humans (add --args=large for 100 tables)
```

## 1. Status

### Implemented and verified (unit tests and/or inspected PNGs)
- Nodes: header + one row per column, badges `PK` (filled), `FK`, `UQ`, `IDX` (outlined). Badge rule (same as
  `Table.isUnique`/`isIndexed`, Mermaid `UK`): `UQ` = sole column of a unique index; `IDX` = member of any other
  index, INCLUDING a multi-column unique index. Legend says "Unique column" / "Indexed / composite unique"; column
  tooltips spell it out per index ("UQ: unique (unique index x)", "IDX: part of composite unique index x (a, b) –
  unique only in combination", "IDX: indexed (index x)", "IDX: part of index x (a, b)"). Nullable = type suffixed `?`
  (no suffix = NOT NULL); model order/names preserved; ellipsis truncation with caps; views = «view» stereotype +
  one italic truncated SQL line; FTS = «FTS4»/«FTS3» stereotype.
- Three node styles beyond colour: table = square solid; view = rounded + dashed border; FTS = chamfered top corners.
- Legend overlay (bottom-right, `legendVisible`), stale banner (top-left pill, warning triangle glyph + text).
  Fit-to-window (and the focus-mode fit) re-fits beside/above the legend if a table would end up under it.
- Keyboard focus ring: 2 px accent frame along the canvas border while the canvas has keyboard focus (also with no
  diagram). Selection indicator (thick outline + corner handles) is separate.
- Edges: one per FK (composite = one), crow's-foot ends from `CardinalityInference`, attach left/right at FK row /
  referenced row, header when collapsed; self-loop on the right side (expanded: FK row -> referenced row; collapsed:
  child end on the right side, parent end on the TOP border, loop around the top-right corner, `EdgeModel.bUp`);
  parallel edges are spread; different end symbols at one anchor are spread 15 units apart (clamped to the node
  border) and ORDERED by where their edges head (steep up = top, steep down = bottom, same-side loops nest outermost
  first) so edges leaving one anchor do not cross; edges follow dragged nodes.
- Notation (`CrowsFoot`, next to the node = maximum, outer = minimum): exactly one = two bars; zero or one = bar +
  circle; zero or many = crow's foot touching the border + circle. Parent end: exactly one / zero or one. Child end:
  zero or many, or zero or one (circle + bar) for one-to-one, matching Mermaid's `o|` child token (spec FR-36).
  NOTE: the lead's pass-2 brief wrote "child end … one = bar"; kept circle + bar so the diagram matches the Mermaid
  export (AC-19.3). If a bare bar is wanted, map `ChildCardinality.ONE` to a new `EndKind` in `EdgeModel.childEnd`.
- Hover edge: highlight + FK rows (child) and referenced rows (parent) highlighted + tooltip (FK cols → table(cols),
  ON DELETE, ON UPDATE, cardinality). Column / table / view-SQL tooltips.
- Wheel zoom around cursor (0.1–3.0), background / middle-button pan, click select, node drag (`positionsChanged`
  once on release), double-click → go to source, chevron collapse, `fitToWindow/zoomIn/zoomOut`.
- Focus mode: clicking a (dimmed) table re-focuses on it WITHOUT moving the viewport (table stays under the cursor,
  drag works at once); keyboard / programmatic `select` still brings the new focus set into view.
- `select(name, column, center)`, focus mode with viewport restore, re-focus on selection change.
- Keyboard map (below), arrow navigation with fallback, selection kept visible.
- Theme: `DiagramTheme` / `DefaultDiagramTheme` resolved from `UIManager` at paint time; font change re-measures.
- `setDiagram` preserve semantics, default placement for entities missing from `positions`, deferred fit.
- `DiagramMetrics.measure` = same code path as painting (`NodeModel.measure`), thread-safe (test with 4 threads).
- Context menus built (`nodeMenu`, `backgroundMenu`) and their actions tested via `doClick`.
- Accessible name/description, role CANVAS.

### Implemented but NOT verified
- Nothing was run in a real window: `DemoApp`/`runDemo` was never launched. Real popup showing (`JPopupMenu.show`),
  Swing tooltip display, cursor changes, focus traversal and real key dispatch (tests call the KeyListener directly;
  mouse events go through `dispatchEvent`) are untested in a live UI.
- Not run inside the IDE: the `UIManager` keys are assumed from IntelliJ LaFs; demo themes only imitate them.
  HiDPI not checked.
- PNGs: all 21 were looked at in pass 2 (light/dark: overview, selection, focus, hover-stale, zoom, zoom-interactive,
  collapsed, keyboard-focus; high-contrast: overview, keyboard-focus; light-large-fit, -fit-interactive, -zoomed),
  the ones affected by later changes again after the last change.
- The interaction settle timer (`javax.swing.Timer`, 150 ms) is tested headless, not felt in a real window.
- 60 fps (AC-21.2) only measured as software rendering into a BufferedImage (numbers below), not on screen.

### Not started
- Nothing from the brief is unstarted. Optional ideas not done: orthogonal / obstacle-avoiding routing, an edge-layer
  image cache (would make static repaints such as hover changes cheap too).

## 2. Files

`render/src/main/kotlin/com/oguzhanaslann/roomer/render/`
- `DiagramCanvas.kt` – public API (`DiagramCanvas`, `DiagramCanvasListener`, `DiagramMetrics`), input handling, menus, `FontSets` cache.
- `DiagramTheme.kt` – `DiagramTheme`, `DefaultDiagramTheme` (UIManager lookups), internal `Palette`/`Tones` (per-paint snapshot, dim variants).
- `RenderModel.kt` – `FontSet`, `RowModel`, `NodeModel` (measure + cached shapes), `EdgeModel`, `Hit`, `DiagramScene` (precomputed render model, positions, bounds, hit-testing).
- `EdgeRouter.kt` – side choice, anchor spreading, path building/flattening; also `Tooltips` (HTML strings).
- `ScenePainter.kt` – `CrowsFoot` (symbols appended to batch paths, any direction), `PaintState`, `ScenePainter`
  (batched edges, nodes, LOD, legend + `legendBounds`, stale banner, focus ring).
- `Viewport.kt` – zoom/pan maths (`zoomAt`, `fit`, `centerOn`, `ensureVisible`).
- `DiagramNavigation.kt` – focus set, arrow-key target, nearest node.

`render/src/test/kotlin/com/oguzhanaslann/roomer/render/`
- `LogicTest.kt` – `ViewportTest`, `NavigationTest`, `SceneTest` (rows, edges, hit-testing, routing, tooltips, placement).
- `DiagramCanvasTest.kt` – metrics = painted pixels, setDiagram semantics, events, mouse, keyboard, menus, themes, perf.
- `demo/DemoData.kt` – sample blog DB + hand-picked positions; `large()` synthetic 100×10 + `gridPositions()`.
- `demo/DemoApp.kt` – JFrame demo (`main`). `demo/DemoRenderer.kt` – headless PNG renderer (`main`); also renders
  "interactive" frames (`*-zoom-interactive`, `light-large-fit-interactive`) and the focus ring (`*-keyboard-focus`).

`render/build.gradle.kts` – added: `tasks.test` headless + stdout, `renderDemo`, `runDemo`. stdlib stays `compileOnly`.

## 3. Public members ADDED beyond the original contract

```kotlin
// DiagramCanvas
var theme: DiagramTheme                      // default DefaultDiagramTheme()
var legendVisible: Boolean                   // default true
val zoom: Double
companion object { const val MIN_ZOOM: Double = 0.1; const val MAX_ZOOM: Double = 3.0 }
// overrides: paintComponent, updateUI, setBounds (replaced the deprecated reshape in pass 2; same deferred-fit
//            behaviour), getPreferredSize (800x600 unless set), getToolTipText(MouseEvent), getAccessibleContext

// DiagramMetrics
fun measure(database: Database, collapsed: Set<String>, theme: DiagramTheme): Map<String, Size>

// DiagramTheme.kt
interface DiagramTheme {
    val font: Font; val canvasBackground: Color; val nodeBackground: Color; val foreground: Color
    val border: Color; val accent: Color; val selectionBackground: Color; val warning: Color
    // with default (derived) implementations:
    val secondaryForeground: Color; val tableHeaderBackground: Color; val viewHeaderBackground: Color
    val ftsHeaderBackground: Color; val edge: Color; val rowHighlight: Color; val overlayBackground: Color
    val dimAmount: Double
    companion object { @JvmStatic fun blend(a: Color, b: Color, t: Double): Color }
}
open class DefaultDiagramTheme : DiagramTheme { protected fun color(vararg keys: String): Color? }
```
Everything else is `internal`. Pass 2 added NO public API (only internal test hooks `interactiveForTest`,
`hasKeyboardFocusForTest`, `ScenePainter.legendBounds`). The `DiagramCanvasListener` KDoc now states the actual event
semantics (below) instead of "through the canvas UI".

### Behaviour the tool-window author must know
- `selectionChanged`, `focusModeChanged`, `collapsedChanged` fire on every ACTUAL state change, also for programmatic
  calls (`select`, `focusMode =`, `setCollapsed`, `collapseAll`, `expandAll`) and when `setDiagram`/`clearDiagram`
  drops selection/focus. Never for a no-op, so calling back from a listener cannot loop. `setDiagram` never fires
  `collapsedChanged`. The tool-window panel RELIES on this (it saves collapse state only from `collapsedChanged`, and
  a test asserts exactly-once saving) – do not change it. The KDoc says so since pass 2.
- `select(name, column)` expands a collapsed table (fires `collapsedChanged`); unknown name = ignored; `select(null)`
  also leaves focus mode. In focus mode `center` is ignored (the focus set is fitted instead).
- Background click clears the selection. Esc keeps the selection (leaves focus mode, clears column/edge highlight).
- `setDiagram(preserveViewport = true)` with no previous diagram fits to window.

## 4. Keyboard map (canvas focused)
| Key | Action |
|---|---|
| Arrows | selection → FK neighbour best in that direction (±63° cone); else nearest node in that direction; no selection → node nearest viewport centre |
| Enter | `goToSourceRequested(selected)` |
| Space | collapse/expand selected node |
| F | toggle focus mode (needs a selection) |
| `/`, Ctrl+F, Cmd+F | `searchRequested()` |
| `+`, `=` / `-`, `_` | zoom in / out (×1.25, viewport centre) |
| `0` | fit to window |
| Esc | leave focus mode, clear highlight, then `escapePressed()` |

## 5. Design decisions
- Precomputed `DiagramScene` on `setDiagram` (badges, tooltips lazy, neighbours, row indices) → no model traversal in paint.
- Sizes are zoom-independent diagram units measured with a fixed `FontRenderContext(null, true, true)`; painting uses
  the same hints (AA + fractional metrics) under a Graphics2D scale → measured = painted, no component needed.
- Font = `theme.font` forced to PLAIN (Metal's Label.font is bold); compared each paint, re-measure on change.
- Dimming (focus mode) uses colours pre-blended towards the background, not AlphaComposite (cheaper).
- Edge painting (pass 2): visible edges (bbox-culled) are appended into ONE `Path2D` per style (normal/dimmed, attached
  to the selection, hovered) plus one path of symbol strokes and one of "zero" circles -> 3–4 draw calls per style.
  Measured: AA rasterisation itself dominates (Marlin, ~5.5–7 ms for the dense 100-table views, mostly long
  near-vertical edges touching many coverage tiles); stroking the cached flattened polyline was SLOWER than the cubic
  (more segments), so painting strokes the cubic `EdgeModel.path`; `points` is only for hit-testing.
- Interactive frames: while panning (drag), node dragging or wheel zooming, `PaintState.interactive` is set; the
  settle timer (150 ms after the last event; immediately on mouse release) clears it and repaints at full quality.
  Interactive frames draw the bulk of the edges as non-AA hairlines (`BasicStroke(0f)`, ~0.2 ms) up to zoom 1.5
  (`ScenePainter.FAST_EDGES_MAX_ZOOM`) and with STROKE_NORMALIZE above; edges attached to the selection and the hovered
  edge stay anti-aliased. Static frames (hover, selection, settle) are always full quality.
- LOD: on-screen font < 4.6 px → simplified nodes (fill + enlarged centred title); zoom < 0.22 → no end symbols.
  Stroke widths never fall below ~1 screen px.
- Edges: horizontal 26-unit stub at each end (holds the symbol) + cubic curve; same-side loop when nodes overlap
  horizontally. Smooth curves chosen over orthogonal routing: simpler, fewer coincident segments.
- Child `ONE` and parent `ZERO_OR_ONE` share one glyph (circle + bar) as the brief specifies; legend shows three ends.
- Not-null marker: `?` suffix on nullable types (familiar to Kotlin users), documented in the legend.
- Selection = thick accent outline + four corner handles (non-colour). Row highlight = fill + outline.

## 6. Test / perf results (last run)
60/60 pass. Paint into a 1600×1000 BufferedImage, 100 tables / 1000 columns / ~200 edges, software pipeline
(`largeDiagramPaintsFastEnough`, prints `[render perf]`). ms/frame:

| view | before pass 2 | static (full quality) | interactive (pan / wheel / drag) |
|---|---|---|---|
| fit to window (zoom 0.29), panning | 9.2 | 7.7 | 1.1 |
| zoom 0.36 (densest with full text), panning | 13.0 | 11.2 | 3.5 |
| zoom 1.0, panning | 7.2 | 6.3 | 1.3 |
| zoom 2.0, panning | – | 3.4 | 3.4 |
| focus mode | 11.3 | 9.6 | 2.6 |
| wheel zoom in/out | 10.6 | 9.0 | 2.2 |

setDiagram ~3 ms. Nodes alone cost 0.5–1.3 ms at these views; the rest of a static frame is AA edge rasterisation.
"before" = old per-edge painting, every frame full quality. Every frame painted during pan/zoom/drag is now
< 3.5 ms; one-off full-quality frames (settle, hover change, selection) are <= 11.2 ms (< 16.7 ms).

## 7. Known bugs / limitations
- Static full-quality frames at 100 tables still cost up to ~11 ms (AA edges); hover changes repaint the whole canvas
  at full quality. Unmeasured on screen / HiDPI. Interactive frames show aliased hairline edges until 150 ms after
  the last event.
- Edges are not routed around nodes (they are painted underneath nodes); same-side loops can be long.
- 3 different symbols at one collapsed header anchor are compressed to 2*HALF spacing (touching, not overlapping);
  many parallel self-FKs on one node overlap their stubs. Long edges still pass UNDER intermediate tables (e.g. the
  `other_N_id` edges in the large demo run straight under neighbouring tables).
- FKs whose parent table is missing produce no edge (the column still shows `FK`).
- Legend is hidden when the canvas is smaller than legend + 60 px; it does not intercept clicks.
- `DiagramMetrics.measure(db, collapsed)` uses the default theme's font; a canvas with a custom-font theme needs the 3-arg overload.
- Arrow-key reachability of every node is tested only for the demo layout, not proven in general.

### Found on a real production schema (2026-11-17)
A 6-table app database (one hub table with 5 children, composite PKs, a composite FK, a nullable self-reference) rendered
correctly in light and dark: no overlaps, no edges through tables, no truncated names, correct ends and badges, focus
dimming correct. Three rough edges, none of them errors:
- **Legend over content when zoomed in**: the legend is a fixed-size overlay; fit-to-window avoids it, but after zooming
  in it can cover rows of a table. Default is on. Fix idea: hide or fade it while it intersects a node, or dock it
  outside the diagram area.
- **Stacked parent ends**: when several FKs reference the same row (4 children → the hub's PK row), their parent-end
  symbols draw on top of each other as one `||`, so the edges cannot be counted at that end. Fix idea: fan the anchors
  out vertically within the row (or across neighbouring rows) as is already done for differing symbol kinds.
- **Collapse all keeps the expanded layout**: collapsed nodes become thin bars with large gaps. Fix idea: offer
  re-layout after collapse-all (or re-run layout with the collapsed sizes when no positions were dragged).
- Composite FKs attach to the first FK column's row only (acceptable, but noted).

## 8. Next steps (in order)
0. Fix the legend overlap and the stacked parent ends above (likely on most real schemas).
1. Launch `./gradlew :render:runDemo` (human; `--args=large` for 100 tables) and check popup menus, tooltips, drag,
   wheel, keyboard with real focus, the focus ring, and how the hairline -> AA switch feels after panning.
2. Verify inside the IDE with the tool window: UIManager keys in light/dark/high-contrast, runtime theme switch
   (`updateUI`), Esc/Ctrl+F not swallowed by IDE actions, HiDPI, on-screen frame times (AC-21.2 manual check).
3. Ask the lead whether child-end "one" should stay circle + bar (matches Mermaid `o|`) or become a bare bar (§1).
4. Optional perf: cache the static edge layer (canvas background + unselected edges) in an image keyed on zoom,
   routes version, focus/selection and palette; blit it for hover/selection repaints and small pans.
5. Optional: orthogonal / obstacle-avoiding routing (edges currently pass under nodes).
