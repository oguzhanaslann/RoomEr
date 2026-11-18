# Demo project: Music

A made-up app used for RoomEr's screenshots and manual testing. Only the parts RoomEr reads exist: the Room
schema export in `app/schemas/` and a few `@Entity` classes for go-to-source. It is not a buildable Android project.

- `generate_schema.py` writes `app/schemas/com.example.music.data.MusicDatabase/7.json`.
- `./gradlew runIdeScreenshots` (from the repository root) opens this project in a sandbox Android Studio and saves
  the Marketplace screenshots to `build/screenshots/`.
