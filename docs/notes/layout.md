# Handover notes – `:layout`

Status: **finished**. `./gradlew :layout:test` passes (25 tests). Written from the layout agent's final report.

## What exists
- `layout/src/main/kotlin/com/oguzhanaslann/roomer/layout/LayoutEngine.kt` – the contract (`Point`, `Size`, `LayoutNode`, `LayoutEdge`, `LayoutRequest`, `LayoutResult`, `LayoutEngine`). Written by the lead; unchanged.
- `ElkLayoutEngine.kt` – ELK layered layout. Never throws; on ELK failure falls back to the grid and exposes the cause as `engine.lastError` (null after success).
- `GridLayoutEngine.kt` – `GridLayoutEngine(spacing = 80.0)`, stateless row grid aiming for a 1.6 aspect ratio. Fallback and example test double.
- `Placement.kt` – shared skeleton `layoutWith`: sanitise, keep fixed nodes, arrange free nodes, place, final overlap pass.
- Tests: `ElkLayoutEngineTest`, `GridLayoutEngineTest`, `LayoutEngineContractTest`, `ElkLayoutPerformanceTest`, `TestGraphs`.

## Behaviour
- Odd input: duplicate node ids → first wins; negative/NaN/infinite sizes → 0; self-loops, unknown endpoints, duplicate edges dropped; fixed positions with non-finite coordinates or unknown ids ignored.
- No fixed positions: bounding box starts at exactly (0,0).
- With fixed positions: fixed nodes returned exactly; all-fixed returns without calling ELK. ELK lays out only the free nodes; each connected group of free nodes with fixed FK neighbours moves as a block to the nearest free spot near those neighbours (80 px gap); the rest go right of or below the bounding box. A last pass guarantees no overlap.
- Deterministic (fixed ELK seed, request-order result map).
- Edges are passed to ELK reversed (parent → child), so referenced tables are in the left layers.

## ELK options
algorithm layered · direction RIGHT · edgeRouting POLYLINE · spacing.nodeNode 70 · nodeNodeBetweenLayers 110 · edgeNode 30 · edgeEdge 15 · separateConnectedComponents true · componentComponent 90 · aspectRatio 1.6 · padding 0 · randomSeed 1. Only node x/y are read back (the canvas routes edges).

## Classloader / threading
- All ELK calls run inside one process-wide `synchronized` block; the thread context classloader is swapped to the engine's classloader and restored in `finally`.
- `LayeredMetaDataProvider()` is registered explicitly with `LayoutMetaDataService` on first use.
- Tested with a context classloader that cannot see ELK, and with 48 concurrent layouts on 6 threads.

## Timing (100 nodes ≈220×260, 150 edges)
Cold first call ≈ 220 ms · warm median 8.6 ms · incremental (90 fixed + 10 new) ≈ 4 ms.

## Dependencies (`layout/build.gradle.kts`)
- ELK core / graph / alg.layered 0.12.1 (+ alg.common transitive), EMF common + ecore 2.12.0, Guava 33.8.0-jre (+ failureaccess 1.0.3), Xtext xbase.lib 2.36.0. About 8.5 MB. Licences are recorded in the root `NOTICE` (verified from jar manifests / POMs).
- `xbase.lib` is added by hand, non-transitive: ELK's POMs do not declare it but `LayeredMetaDataProvider` needs it.
- Guava is pinned with `strictly("33.8.0-jre")` because ELK asks for the open range `[15.0,)`.
- Excluded: `emf.ecore.xmi`, `jspecify`, `error_prone_annotations`, `j2objc-annotations`, `listenablefuture`. Confirmed absent from the root plugin's `runtimeClasspath`.

Real production schema (6 tables, 6 FKs, 2026-11-17): ELK cold 152 ms, whole parse + measure + layout ≈245 ms cold, 2.5–4 ms warm; no overlaps.

## Open
- Not yet run under a real IDE plugin classloader (only the unit-test JVM). Check on first `runIde`.
- The plugin ships its own Guava next to the IDE's; untested in an IDE.
- Dense graphs get large: the random 100-node / 150-edge graph is about 12400 × 10100 px. Layered layout has no aspect-ratio control within one connected component.
- NFR-08: EPL-2.0 / EPL-1.0 libraries are bundled unmodified as separate jars under an Apache-2.0 plugin. This is the usual arrangement, but the compatibility check in the Definition of Done has not been signed off by a person.
