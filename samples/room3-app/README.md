# Room 3 sample

A small Android app on Room 3 (`androidx.room3` 3.0.3, KSP), for checking RoomEr against a real Room 3 project.

The schema covers every relationship RoomEr draws: one-to-many (`authors` → `books`), one-to-one
(`readers` → `reader_profiles`), many-to-many through a junction table (`book_tags`), an FTS4 table
(`books_fts`), a view (`book_ratings`) and a table without `tableName` (`Tag`, matched by class name).

The exported schema is committed in `app/schemas/`. To regenerate it:

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # path to your Android SDK
./gradlew :app:kspDebugKotlin
```

To try RoomEr on it, open this folder in Android Studio with the plugin installed (or `./gradlew runIde` from the
repository root and open it there), let the Gradle sync finish, then open **View | Tool Windows | Room ER**.
Go to source (`Enter` or double-click on a table) jumps to the class annotated with `androidx.room3.Entity`.
