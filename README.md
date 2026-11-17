# RoomEr – Room ER Diagrams

An Android Studio plugin that reads the schema files exported by [Room](https://developer.android.com/training/data-storage/room)
and shows them as an interactive entity-relationship diagram in the **Room ER** tool window.

- Tables, columns and types with PK / FK / unique / index / not-null markers
- Foreign keys in crow's-foot notation, with inferred cardinality
- Views and FTS tables in their own styles, with a legend
- Automatic layered layout (ELK); drag tables, positions are remembered per database
- Search, focus mode (a table and its FK neighbours), collapsible column lists
- Go to source: jump from a table to its `@Entity` class
- Export as Mermaid `erDiagram` (copy or save `.mmd`)
- Reloads automatically when the schema files change
- Read-only and offline: no network access, no telemetry

## Setup: export your Room schema

RoomEr reads the JSON that Room writes at build time, so schema export must be on.

1. Keep `exportSchema` enabled (it is the default) on your database:

   ```kotlin
   @Database(entities = [User::class], version = 3, exportSchema = true)
   abstract class AppDatabase : RoomDatabase()
   ```

2. Tell Room where to write the files, in the module's `build.gradle.kts`:

   ```kotlin
   plugins {
       id("androidx.room")
   }

   room {
       schemaDirectory("$projectDir/schemas")
   }
   ```

   or, without the Room Gradle plugin:

   ```kotlin
   ksp {
       arg("room.schemaLocation", "$projectDir/schemas")
   }
   ```

3. Build the project. Room writes `schemas/<fully.qualified.DatabaseClass>/<version>.json`.

4. Open **View | Tool Windows | Room ER**.

RoomEr scans the project for `**/schemas/<databaseName>/<n>.json` and shows the highest version of each
database. If your files live somewhere unusual, use **Choose schema folder…** in the tool window.

## Using the diagram

| Action | Mouse | Keyboard |
|---|---|---|
| Pan | drag the background, or middle-button drag | |
| Zoom | mouse wheel | `+` / `-`, `0` = fit to window |
| Select | click a table | arrow keys move between connected tables |
| Go to source | double-click, or context menu | `Enter` |
| Search | search box | `/` or `Ctrl/Cmd+F` |
| Clear search / leave focus mode | | `Esc` |
| Move a table | drag it | |

Relationship ends: the parent end is *exactly one* when every foreign-key column is `NOT NULL`, otherwise
*zero or one*. The child end is *one* when the foreign-key columns are the child's primary key or are covered
by a unique index, otherwise *many*.

## Compatibility

- Android Studio: the latest two stable releases.
- Room schema JSON `formatVersion` 1. Other versions are reported as unsupported instead of being guessed at.

## Building

Requires JDK 21 or newer.

```sh
./gradlew buildPlugin      # plugin zip in build/distributions
./gradlew check            # all tests, plus the build-enforced rules below
./gradlew runIde           # sandbox IDE with the plugin
./gradlew verifyPlugin     # JetBrains plugin verifier against the IDEs in `pluginVerifierIdes`
./gradlew :render:runDemo  # the diagram canvas in a plain window, no IDE needed
```

`check` also runs two rules from the acceptance criteria:

- `checkUiFreeModules`: `model`, `parser`, `layout` and `export` must not import Swing, AWT or IntelliJ classes.
- `checkNoNetworkAccess`: no HTTP client or telemetry library is bundled, and the plugin's code makes no network calls.

By default the build downloads the Android Studio release named in `gradle.properties`. To build against
an installed IDE instead, create `local.properties` with:

```properties
platformLocalPath=/Applications/Android Studio.app
```

### Modules

| Module | Contents | Depends on |
|---|---|---|
| `model` | Immutable schema model, cardinality inference, search | – |
| `parser` | Room JSON → model, typed errors | `model` |
| `layout` | `LayoutEngine` interface, ELK implementation | – |
| `export` | Mermaid generator | `model` |
| `render` | Swing/Java2D canvas | `model`, `layout` |
| root | Discovery, tool window, navigation, persisted state | all of the above, IntelliJ Platform |

Only the root project depends on the IntelliJ Platform. The logic modules are plain Kotlin/JVM modules, so
they cannot reference Swing-in-IDE or platform UI classes.

## Licence

Apache-2.0, see [LICENSE](LICENSE). Third-party components are listed in [NOTICE](NOTICE).
