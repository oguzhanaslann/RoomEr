# RoomEr – Learnings

Things we learned building v1 that are not obvious from the code and would cost time to rediscover.
Newest first within each section. Dates are when the lesson was learned.

## Toolchain and build

- **No system JDK is needed.** Android Studio ships JBR 25; point `JAVA_HOME` at
  `/Applications/Android Studio.app/Contents/jbr/Contents/Home` and use the Gradle wrapper. (2026-11-10)
- **Build against the installed IDE** with `local.properties` → `platformLocalPath`. No IDE download, and platform tests
  run against exactly what the user has. CI downloads `androidStudioVersion` instead. (2026-11-10)
- **Kotlin `compileTestKotlin` ignored the extension-level `jvmTarget`** and targeted the JDK running Gradle (25), failing
  JVM-target validation against Java (21). Pin it on every `KotlinCompile` task. (2026-11-10)
- **Kotlin stdlib is `compileOnly` in the logic modules** (`kotlin.stdlib.default.dependency=false`): the IDE provides it,
  and bundling a second copy causes classloader conflicts. (2026-11-10)
- **Pick Android Studio versions from the releases list**, not from memory:
  `https://jb.gg/android-studio-releases-list.xml` gives channel, version and platform build. Our first guess for the
  fallback version turned out to be a Canary build. (2026-11-17)

## Plugin verifier (AC-22.1)

- **Plugin names may only contain** letters, digits, spaces and `.,+_-/:()#'&[]|`. The en dash in "RoomEr – Room ER
  Diagrams" was rejected; the name is now "RoomEr - Room ER Diagrams". (2026-11-17)
- **Kotlin bridges count as API usage.** Implementing a platform interface that has JVM default methods
  (e.g. `ToolWindowFactory`) made the compiler emit delegating bridges for every default method, which the verifier
  reported as overriding/invoking deprecated and experimental API we never touched. Fix: `jvmDefault = NO_COMPATIBILITY`
  in the root project. Check with `javap -p` on our class before chasing "usages" that are not in the source. (2026-11-17)
- **APIs differ between the two supported lines.** The `ui.dsl.listCellRenderer` builders are public in 262 but
  `@ApiStatus.Internal` in 261; the verifier fails the build on internal API. Plain `SimpleListCellRenderer` subclasses
  work in both. Run the verifier against both lines, not just the one you compile against. (2026-11-17)
- **Verify platform APIs with `javap`** against the jars in the installed IDE; the API research is recorded in
  `notes/plugin.md` and is expensive to redo. (2026-11-10)

## IntelliJ platform tests

- **The `BasePlatformTestCase` light project has no JDK**, so `java.lang.String` does not resolve and
  `PsiField.computeConstantValue()` returns null even for literal initializers. A test for
  `@Entity(tableName = SOME_CONSTANT)` failed for this reason, not because of the resolver. A one-class `java/lang/String.java`
  stand-in in the test fixes it; mock JDKs are not available from a local IDE install. (2026-11-17)
- **Platform tests found real UI bugs that compile-checking never would**: the stale banner was always empty
  (`EditorNotificationPanel.clear()` also clears the text), and the chooser popup needed `showCenteredInCurrentWindow`.
  Build panel-level tests early instead of leaving Swing code compile-checked only. (2026-11-17)

## ELK (layout)

- **ELK's service discovery does not work in a plugin classloader.** Register `LayeredMetaDataProvider` explicitly and swap
  the thread context classloader around ELK calls. (2026-11-10)
- **ELK 0.12.1's POMs omit `org.eclipse.xtext.xbase.lib`**, which `LayeredMetaDataProvider` needs at runtime; add it by hand.
  ELK also asks for Guava `[15.0,)`, which floats to the newest release; pin it. (2026-11-10)
- **Layered layout gives no aspect-ratio control inside one connected component**; dense 100-table graphs get very large,
  so search and focus mode matter more than layout tuning. (2026-11-10)

## Room schema JSON

- **Validated against 287 real exports** (Room 1.x era to Room 3 and KMP) plus one private production schema; no parser
  change was needed. `formatVersion` is still 1 everywhere, including Room 3. (2026-11-17)
- **Shapes to expect:** newer Room omits `notNull` for nullable columns; `views` may be missing; FTS can be `FTS3`, `FTS4` or
  `FTS5` (Room 3); an FTS primary key is `["rowid"]` or empty; `defaultValue` is raw SQL with quotes kept. (2026-11-17)
- **Room 3 FTS5 has `ftsOptions.contentRowId`**, which the model does not carry yet; it could be drawn as a link to the
  content table. (2026-11-17)

## Diagram and export rules

- **One definition of "unique"** in the model: UQ/UK = sole column of a unique index; a member of a multi-column unique
  index is IDX. The diagram and Mermaid disagreed until this lived in one place. (2026-11-17)
- **Child "one" is drawn as circle + bar** because the spec's Mermaid token for it is `o|`, and AC-19.3 requires the diagram
  and the export to agree. (2026-11-17)
- **Rendering performance is dominated by anti-aliased edges** (85–90 % of a frame). Drawing aliased hairlines while the
  user pans or zooms, and full quality 150 ms after movement stops, brought interactive frames to ≤ 3.5 ms at 100 tables.
  Drawing cached flattened polylines was slower than drawing the cubic curves. (2026-11-17)
- **Real schemas expose layout rough edges that synthetic ones did not**: many FKs into one hub row stack their end symbols,
  and the legend overlay can cover tables after zooming in. Test new rendering work on a real hub-and-spoke schema. (2026-11-17)

## Working with agents

- **Fix the contracts first, then parallelise.** Writing the model, `LayoutEngine` and the `DiagramCanvas` API as stubs
  before spawning agents let five modules be built at once without conflicts. (2026-11-10)
- **One agent per Gradle module, each running only its own tasks** (`:render:test`, root `:test`). A build started while
  another agent was mid-edit failed with spurious compile errors; only one agent at a time should touch root build files. (2026-11-17)
- **Handover notes in `docs/notes/` made resuming cheap**: a fresh agent picked up each module from its note alone. Keep
  the notes updated in place rather than appending session logs. (2026-11-17)
- **Check agent claims that are cheap to check.** One agent reported the project was "not a git repository" after the first
  commit existed; `git status` settled it. (2026-11-17)
