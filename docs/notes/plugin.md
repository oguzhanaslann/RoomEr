# Handover notes – IDE integration (root project)

Checkpoint 2026-11-17 (second session; first checkpoint 2026-11-10). Scope: `src/main/kotlin/com/oguzhanaslann/roomer/**`, `src/main/resources/**`, `src/test/**`, root `build.gradle.kts`.

## Checkpoint state
- `./gradlew :compileKotlin :compileTestKotlin`: succeeds; no warnings from root sources (render has one: deprecated `reshape` override, render's business).
- `./gradlew :test`: **70 tests, 70 pass** (stable over 3 consecutive `--rerun`s, ~8 s warm) against the local Android Studio platform (AI-262.9437.185).
- The Swing panel, `MessagePanel`, toolbar actions, legend toggle, save/copy Mermaid and the chooser popup now **execute in platform tests** (`RoomErPanelTest`, `EntityNavigationTest`). Nothing has been run in a live IDE window (`runIde` never started).
- Nothing is stubbed in the root project.

## Status per spec item
| Item | Status |
|---|---|
| Discovery (FR-01–03, AC-01.x, AC-02.x) | Implemented, tested |
| File watching (FR-06) | Implemented, tested with VFS events in the light fixture. `ModuleRootListener` path untested |
| State (FR-25, FR-32, FR-03/04, AC-12.3) | Implemented, tested (XML round trip, workspace-only storage) |
| Loading pipeline (FR-06/07, AC-05.x, AC-10.2, AC-12.4) | Implemented, tested with a `LayoutEngine` double. The 300 ms progress indicator is unverified |
| Error/empty states (FR-40–42, AC-06.x) | Implemented, tested end to end in the panel: no-schemas card (`exportSchema = true`, `room.schemaLocation`, doc link, folder link), parse-error card (title, path, reason), folder-without-schemas card (names the folder), stale banner (AC-06.5) |
| Tool window, header, toolbar | Header tested (`AppDb`, `v2`, combo hidden for one database, simple names + full-name tooltip for several). Every `RoomEr.Toolbar` action's `update()` tested with and without a panel. Visual layout unverified |
| Search (FR-30) | Tested: case-insensitive results, Enter selects in the canvas, "No results" (list empty text), Esc clears |
| Navigation (FR-33, AC-17.x) | Tested incl. constant `tableName` and the multi-candidate chooser (popup intercepted, candidates listed). Choosing an entry in the popup and Kotlin `@Entity` classes untested |
| Mermaid actions (FR-35) | Tested: copy to clipboard, save writes the chosen file, cancelled save writes nothing and notifies nothing (AC-20.2, via the `mermaidTargetChooser` seam). The real `FileSaverDialog` is not exercised |
| Theme change | `LafManagerListener` calls `canvas.updateUI()`/repaint: unverified |
| Legend toggle | Done: `ToggleLegendAction` (`RoomEr.ToggleLegend`, icon `AllIcons.Actions.ToggleVisibility`), `RoomErPanel.legendVisible` writes canvas + `RoomErState.legendVisible`; stored value applied on panel creation. Tested (round trip) |
| Collapse persistence | Done: persisted only through the canvas listener; tested "exactly once" per change and none for a no-op |

## Files
Main (`src/main/kotlin/com/oguzhanaslann/roomer/`):
- `RoomErBundle.kt` – `DynamicBundle` accessor for `messages/RoomErBundle.properties`.
- `RoomErIcons.kt` – tool window icon holder (referenced from plugin.xml).
- `RoomErNotifications.kt` – notification helper, group id `RoomEr`.
- `discovery/SchemaPaths.kt` – pure logic: `SchemaPathMatcher` (match, matchInFolder, group, labels, event filters), `SchemaFileMatch`, `DiscoveredDatabase`.
- `discovery/SchemaDiscovery.kt` – VFS traversal of content roots + manual folder; `DiscoveryResult`. Call inside a read action.
- `discovery/SchemaWatcher.kt` – `BulkFileListener` + `ModuleRootListener`, 300 ms debounce by cancel-and-relaunch of a coroutine.
- `state/RoomErState.kt` – project `PersistentStateComponent`, `@Storage(StoragePathMacros.WORKSPACE_FILE)` only.
- `toolwindow/DiagramController.kt` – UI-free pipeline; `DiagramState`, `DiagramSnapshot`, `NodeMeasurer`. Publishes `StateFlow`s. `open` (with `open` `collapsedChanged`/`positionsChanged`) only so a test can count persistence calls.
- `toolwindow/RoomErPanel.kt` – Swing content: header, search, cards, banner, canvas wiring, action entry points. Components and `shownSnapshot`/`visibleCard` are `internal` for `RoomErPanelTest`; `mermaidTargetChooser` is the save-dialog seam.
- `toolwindow/MessagePanel.kt` – empty/error cards; holds the single external URL.
- `toolwindow/MermaidExport.kt` – default file name and `java.nio` write.
- `toolwindow/RoomErActions.kt` – toolbar actions (find the panel through `RoomErPanel.KEY`); all declare `ActionUpdateThread.EDT` (asserted by `PluginRegistrationTest`).
- `toolwindow/RoomErToolWindowFactory.kt` – factory + `RoomErProjectService` (coroutine scope, replaceable `layoutEngine`).
- `navigation/EntityResolver.kt` – PSI lookup (read action, smart mode).
- `navigation/JsonEntityLocator.kt` – pure offset search for `"tableName"`/`"viewName"`.
- `navigation/EntityNavigator.kt` – `resolveTarget` (no UI) and `goToSource` (opens editor / chooser / notification).

Resources: `META-INF/plugin.xml`, `messages/RoomErBundle.properties`, `icons/roomEr.svg`, `roomEr_dark.svg`, `roomEr@20x20.svg`, `roomEr@20x20_dark.svg`.

Tests (`src/test/kotlin/com/oguzhanaslann/roomer/`): `TestSchemas.kt` (JSON builder, `RecordingLayoutEngine`, `FixedSizeMeasurer`), `RoomErPlatformTestCase.kt` (base class), `PluginRegistrationTest`, `discovery/SchemaPathMatcherTest`, `discovery/SchemaDiscoveryTest`, `state/RoomErStateTest`, `toolwindow/DiagramControllerTest`, `toolwindow/MermaidExportTest`, `toolwindow/SchemaWatcherTest`, `toolwindow/RoomErPanelTest`, `navigation/JsonEntityLocatorTest`, `navigation/EntityNavigationTest`.

## Design decisions worth knowing
- Discovery uses VFS traversal, not an index, so it works in dumb mode. It skips directories named `build`, `out`, `node_modules`, dot-directories, and anything `ProjectFileIndex` reports as excluded/ignored. Nested content roots are visited once.
- A manual folder is **added** to automatic results, not a replacement. If it holds no schemas and nothing else was found, the state is `FolderWithoutSchemas`; if other databases exist, `DiagramSnapshot.emptyManualFolder` is set and the panel shows a warning notification.
- Database id = schema folder path. Layout state key = database name, or `name@location` only when two folders share a name.
- Automatic reloads (`reload(force = false)`) are skipped when the file's stamp/length is unchanged; "Refresh" forces.
- On parse failure with a diagram on screen, the old `Diagram` is republished with `stale = true`, `error` set and the same `modelRevision`, so the panel does not call `setDiagram` again.
- A layout engine that throws falls back to a grid (`completePositions`); it only catches `Exception`.
- In dumb mode "Go to source" does not wait for indexing: it falls back to the JSON with reason `INDEXING`.
- Listener semantics (render notes §3): the canvas fires `collapsedChanged`/`selectionChanged`/`focusModeChanged` for programmatic changes too, never for no-ops, and `setDiagram` never fires `collapsedChanged`. The panel therefore persists collapse state **only** from the listener (`collapseAll`/`expandAll` just call the canvas; `select()` expanding a collapsed table is persisted the same way). No loop: `controller.collapsedChanged` updates the snapshot without bumping `modelRevision`, so `render()` never calls back into the canvas. Selection/focus listeners are not used (the toolbar reads the canvas on update). The canvas KDoc still says "through the canvas UI"; the panel is coded against the actual behaviour.
- With no visible owner the chooser popup uses `showCenteredInCurrentWindow(project)` (was `showInFocusCenter`), which is also the path `UiInterceptors` sees in tests.
- EDT audit (2026-11-17): no `runBlocking`, `Future.get()`, `ReadAction.compute` or `java.nio`/`loadText` on the EDT in `src/main`. File reads run in `Dispatchers.IO`, PSI in `smartReadAction`, the Mermaid write in `Dispatchers.IO`. Still on the EDT but cheap: `MermaidGenerator.generate` for copy, `findFileByUrl`/`findFileByPath` lookups for "Open file" and the folder chooser's initial selection.

## plugin.xml registrations
- `<resource-bundle>messages.RoomErBundle</resource-bundle>`
- `toolWindow` id `Room ER`, anchor `right`, icon `com.oguzhanaslann.roomer.RoomErIcons.ToolWindow`, factory `…toolwindow.RoomErToolWindowFactory`.
- `notificationGroup` id `RoomEr`, `BALLOON`, key `notification.group.roomer`.
- `<actions resource-bundle="messages.RoomErBundle">`, group `RoomEr.Toolbar` with `RoomEr.Refresh`, `FitToWindow`, `ZoomIn`, `ZoomOut`, `CollapseAll`, `ExpandAll`, `FocusMode`, `ResetLayout`, `ToggleLegend`, `CopyAsMermaid`, `SaveAsMermaid`, `ChooseSchemaFolder` (texts/descriptions under `action.<id>.text|description`).
- Services are light services (`@Service(Service.Level.PROJECT)`): `RoomErState`, `RoomErProjectService`. No XML entry.
- `<depends>` unchanged: `com.intellij.modules.platform`, `com.intellij.modules.java`.

## Platform API research (verified with javap against the 262 jars)
Helper used: `javap -cp "<AS>/Contents/lib/*.jar:<AS>/Contents/plugins/java/lib/*.jar:…/modules/*.jar" <class>`.

Present and used, no deprecation warning from the compiler:
- `com.intellij.openapi.application.CoroutinesKt`: `readAction`, `smartReadAction(project)`, `Dispatchers.EDT`. Also present: `Dispatchers.UiWithModelAccess`, `UiImmediate`, `edtWriteAction`, `readActionBlocking`. `readAndWriteAction` is deprecated (renamed `readAndEdtWriteAction`). `readActionUndispatched` is `@ApiStatus.Internal`.
- Service constructor injection of `CoroutineScope` works (`RoomErProjectService`, exercised by a test).
- `ProjectFileIndex.isExcluded`, `isUnderIgnored`, `iterateContent(ContentIterator, VirtualFileFilter)`; `ProjectRootManager.contentRoots`.
- `VirtualFileManager.VFS_CHANGES` (`BulkFileListener`), also `VFS_CHANGES_BG` (`BulkFileListenerBackgroundable`); `findFileByUrl`, `findFileByNioPath`.
- `ModuleRootListener.TOPIC`, `LafManagerListener.TOPIC`.
- `UiDataProvider.uiDataSnapshot(DataSink)`, `sink[KEY] = value`.
- `FileChooserDescriptorFactory.singleDir()` (the newer name; `createSingleFolderDescriptor()` still exists), `FileChooser.chooseFile(descriptor, component, project, toSelect)`.
- `FileChooserFactory.createSaveFileDialog(FileSaverDescriptor, Project)`, `FileSaverDescriptor(title, description, vararg extensions)`, `FileSaverDialog.save(VirtualFile?, String)` returning `VirtualFileWrapper?`.
- `JBLoadingPanel(LayoutManager, Disposable, int startDelayMs)`, `EditorNotificationPanel(Status.Error)`, `InlineBanner` (exists, not used), `ActionLink`, `BrowserLink` (exists, not used), `SearchTextField(false)`, `components.panels.VerticalLayout`.
- `com.intellij.ui.dsl.listCellRenderer.listCellRenderer(nullText) { text(...); toolTipText = ... }` and `textListCellRenderer(nullText) { }`.
- `AnnotatedElementsSearch.searchPsiClasses` (in `intellij.java.indexing.jar`), `AnnotationUtil.getStringAttributeValue(PsiAnnotation, String)`, `JavaPsiFacade.findClass`.
- `JBPopupFactory.createPopupChooserBuilder(list)`, `OpenFileDescriptor(project, file, offset).navigate(true)`, `NotificationGroupManager`.
- `AllIcons`: `Actions.Refresh`, `General.FitContent`, `General.ZoomIn`, `General.ZoomOut`, `Actions.Collapseall`, `Actions.Expandall`, `General.Locate`, `Graph.Layout`, `Actions.Copy`, `Actions.MenuSaveall`, `Actions.MenuOpen` all exist (also `Actions.Show`, `Actions.ToggleVisibility`, `General.ShowInfos` — candidates for the legend toggle).
- Test framework: `testFramework.jar` is in the IDE's `lib`; `BasePlatformTestCase`, `com.intellij.testFramework.common.timeoutRunBlocking`, `IndexingTestUtil`, `PlatformTestUtil` exist.
- Added 2026-11-17: `TestActionEvent.createTestEvent(action, dataContext)` (testFramework.jar); `SimpleDataContext.builder().add(key, value).build()` (`com.intellij.openapi.actionSystem.impl`, in `intellij.platform.usageView.jar`); Kotlin `com.intellij.testFramework.runInEdtAndGet {}` (the Java `EdtTestUtil.runInEdtAndGet` is deprecated); `com.intellij.ui.UiInterceptors.register(UiInterceptor<JBPopup>)` / `clear()` (`intellij.platform.util.ui.jar`) — `AbstractPopup` consults it in `show(RelativePoint)`, `showCenteredInCurrentWindow`, `showInBestPositionFor(Editor)` and `showImpl`, **not** reliably via `showInCenterOf`/`showInFocusCenter` in headless tests; `clear()` throws if a registered interceptor was never hit. `TestDialogManager` exists (`intellij.platform.ide.jar`) but only covers `Messages` dialogs, not `FileSaverDialog`, hence the seam. `EditorNotificationPanel.clear()` empties the text label as well as the links. `JBPopup` has no `title` getter.

Deprecated or absent in 262 — do not use:
- `SimpleListCellRenderer.create(...)` (both overloads) is deprecated; subclassing `SimpleListCellRenderer` is not.
- `ReadAction.compute(ThrowableComputable)` is deprecated.
- `ActionManager.getChildActionIds` does not exist; use `DefaultActionGroup.childActionsOrStubs`.
- There is no `lib/modules` directory in this Android Studio build; platform jars are flat in `lib/`.

## Test task setup
- No change to dependencies was needed: `testFramework(TestFrameworkType.Platform)` resolves against the local IDE (`local.properties` `platformLocalPath`).
- One build change: `compileTestKotlin` ignored the extension-level `jvmTarget` and used 25 (the JBR running Gradle), failing JVM-target validation against `compileTestJava` (21). Fixed in root `build.gradle.kts` with `tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }`.
- Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :test` (about 10 s when warm). Results in `build/test-results/test/*.xml`.
- Platform tests extend `RoomErPlatformTestCase`: `runInDispatchThread() = false`, suspend code is driven with `timeoutRunBlocking`, and `RoomErState` is reset in `setUp` because the light project is shared between tests. Files live in the fixture's `temp://` file system, except `MermaidExportTest`, which uses a real temp directory.

## Test results (last run, 2026-11-17)
| Class | Tests | Failed |
|---|---|---|
| SchemaPathMatcherTest | 9 | 0 |
| SchemaDiscoveryTest | 7 | 0 |
| RoomErStateTest | 4 | 0 |
| DiagramControllerTest | 17 | 0 |
| SchemaWatcherTest | 2 | 0 |
| MermaidExportTest | 2 | 0 |
| PluginRegistrationTest | 4 | 0 |
| JsonEntityLocatorTest | 2 | 0 |
| EntityNavigationTest | 10 | 0 |
| RoomErPanelTest | 13 | 0 |

These ran against the real `:parser`, `:export` and `:render`; the controller is given the layout engine double and `FixedSizeMeasurer`.
`RoomErPanelTest` pattern: panel built on the EDT with `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, registered under `testRootDisposable`; the background test thread polls `runInEdtAndGet { panel.shownSnapshot === controller.snapshot && !loading }`. Logs contain harmless `StyleSheetUtil - Missing global CSS sheet` warnings (headless).

## Known bugs / open questions
1. ~~`testTableNameMayBeAConstant` fails~~ **Fixed — it was a test-fixture problem, not a resolver bug.** The light project of `BasePlatformTestCase` has no JDK, so `java.lang.String` does not resolve. Debug output: `findAttributeValue("tableName")` = `PsiReferenceExpressionImpl` `OrderEntity.TABLE`, and it *does* resolve to `PsiField:TABLE` (file is in the source root, annotation class resolvable); but the field type's canonical text is `String` (not `java.lang.String`), `field.computeConstantValue()` is null (even for a plain literal initializer), so `computeConstantExpression(ref)` is null — `PsiFieldImpl` only evaluates constants of primitive or `java.lang.String` type. The initializer alone evaluates to `"orders"`. The test now adds a one-class `java/lang/String.java` stand-in; the resolver is unchanged (real projects have a JDK). A mock JDK is not available from the local IDE install (`IdeaTestUtil.getMockJdk*` needs community sources).
2. Bug found and fixed by the panel test: the stale banner text was always empty, because `EditorNotificationPanel.clear()` was called after setting the text and clears it too.
3. `SchemaDiscovery.isScannableDirectory` is a heuristic for directory-creation events only; paths outside the project are not handled.
4. `Dispatchers.EDT` is used for UI hops and `OpenFileDescriptor.navigate`. Whether `Dispatchers.UiWithModelAccess` is the better choice in 262 was not investigated.
5. Snapshots are collected with the default modality, so the panel does not update while a modal dialog is open.
6. Choosing an entry in the multi-candidate popup is not tested (an intercepted popup cannot be clicked); only that the popup appears with all candidates and nothing opens beforehand.

## Next steps (in order)
1. Run `./gradlew runIde` and walk through: first open, empty state and its two links, folder chooser, database switch, auto-reload on file edit, stale banner (text + "Open file" link), search keys (Up/Down/Enter/Esc, `/`), focus mode enablement, legend toggle, reset layout, copy/save Mermaid including the real save dialog and its cancel, go-to-source with a Kotlin `@Entity` and choosing in the multi-candidate chooser, theme switch, 300 ms loading indicator.
2. Check the tool window icon in the New UI (`roomEr@20x20.svg`), the legend icon choice, and the header layout at narrow widths.
3. If the canvas KDoc of `DiagramCanvasListener.collapsedChanged` is updated by the render owner, nothing changes here; if render ever restricts it to UI changes again, `collapseAll`/`expandAll` must call `controller.collapsedChanged` again (the "exactly once" test will catch it).
4. Optional: a test for `ModuleRootListener` reloads and for the 300 ms loading indicator.
