#!/usr/bin/env python3
"""Writes the Room schema export of the made-up "Music" demo app used for RoomEr's screenshots.

The app does not exist; the schema is shaped like a real Room export (formatVersion 1) and shows every
feature the diagram draws: one-to-many and one-to-one keys, a many-to-many junction table with a composite
primary key, unique and composite indices, a view and an external-content FTS4 table.

Run from anywhere:  python3 samples/demo-music/generate_schema.py
"""
import hashlib
import json
import os

DB = "com.example.music.data.MusicDatabase"
VERSION = 7


def col(name, affinity, not_null=True, default=None, field=None):
    c = {"fieldPath": field or camel(name), "columnName": name, "affinity": affinity, "notNull": not_null}
    if default is not None:
        c["defaultValue"] = default
    return c


def camel(name):
    head, *rest = name.split("_")
    return head + "".join(p.capitalize() for p in rest)


def fk(table, columns, refs=("id",), on_delete="CASCADE"):
    return {"table": table, "onDelete": on_delete, "onUpdate": "NO ACTION",
            "columns": list(columns), "referencedColumns": list(refs)}


def index(table, columns, unique=False):
    name = "index_" + table + "_" + "_".join(columns)
    kind = "UNIQUE INDEX" if unique else "INDEX"
    cols = ", ".join(f"`{c}`" for c in columns)
    return {"name": name, "unique": unique, "columnNames": list(columns), "orders": [],
            "createSql": f"CREATE {kind} IF NOT EXISTS `{name}` ON `${{TABLE_NAME}}` ({cols})"}


def sql_type(c):
    s = f"`{c['columnName']}` {c['affinity']}"
    if c["notNull"]:
        s += " NOT NULL"
    if "defaultValue" in c:
        s += f" DEFAULT {c['defaultValue']}"
    return s


def entity(table, fields, pk, auto=False, fks=(), indices=()):
    defs = []
    for c in fields:
        d = sql_type(c)
        if auto and pk == [c["columnName"]]:
            d = f"`{c['columnName']}` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"
        defs.append(d)
    if not auto:
        defs.append("PRIMARY KEY(" + ", ".join(f"`{p}`" for p in pk) + ")")
    for f in fks:
        defs.append("FOREIGN KEY(" + ", ".join(f"`{c}`" for c in f["columns"]) + f") REFERENCES `{f['table']}`("
                    + ", ".join(f"`{c}`" for c in f["referencedColumns"]) + f") ON UPDATE {f['onUpdate']} ON DELETE {f['onDelete']}")
    e = {"tableName": table,
         "createSql": "CREATE TABLE IF NOT EXISTS `${TABLE_NAME}` (" + ", ".join(defs) + ")",
         "fields": list(fields),
         "primaryKey": {"autoGenerate": auto, "columnNames": list(pk)}}
    if indices:
        e["indices"] = list(indices)
    if fks:
        e["foreignKeys"] = list(fks)
    return e


entities = [
    entity("artists", [
        col("id", "INTEGER"), col("name", "TEXT"), col("country", "TEXT", False),
        col("bio", "TEXT", False), col("image_url", "TEXT", False),
    ], ["id"], auto=True, indices=[index("artists", ["name"], unique=True)]),
    entity("albums", [
        col("id", "INTEGER"), col("artist_id", "INTEGER"), col("title", "TEXT"),
        col("release_year", "INTEGER", False), col("cover_url", "TEXT", False),
    ], ["id"], auto=True, fks=[fk("artists", ["artist_id"])], indices=[index("albums", ["artist_id"])]),
    entity("tracks", [
        col("id", "INTEGER"), col("album_id", "INTEGER"), col("title", "TEXT"),
        col("track_number", "INTEGER"), col("duration_ms", "INTEGER"),
        col("explicit", "INTEGER", default="0"),
    ], ["id"], auto=True, fks=[fk("albums", ["album_id"])], indices=[index("tracks", ["album_id"])]),
    entity("genres", [col("id", "INTEGER"), col("name", "TEXT")], ["id"], auto=True,
           indices=[index("genres", ["name"], unique=True)]),
    entity("track_genres", [col("track_id", "INTEGER"), col("genre_id", "INTEGER")], ["track_id", "genre_id"],
           fks=[fk("tracks", ["track_id"]), fk("genres", ["genre_id"])],
           indices=[index("track_genres", ["genre_id"])]),
    entity("users", [
        col("id", "INTEGER"), col("email", "TEXT"), col("display_name", "TEXT"),
        col("is_premium", "INTEGER", default="0"), col("created_at", "INTEGER"),
    ], ["id"], auto=True, indices=[index("users", ["email"], unique=True)]),
    entity("user_settings", [
        col("user_id", "INTEGER"), col("theme", "TEXT", default="'system'"),
        col("audio_quality", "TEXT", default="'high'"), col("offline_mode", "INTEGER", default="0"),
    ], ["user_id"], fks=[fk("users", ["user_id"])]),
    entity("playlists", [
        col("id", "INTEGER"), col("owner_id", "INTEGER"), col("name", "TEXT"),
        col("description", "TEXT", False), col("is_public", "INTEGER", default="1"), col("updated_at", "INTEGER"),
    ], ["id"], auto=True, fks=[fk("users", ["owner_id"])], indices=[index("playlists", ["owner_id"])]),
    entity("playlist_tracks", [
        col("playlist_id", "INTEGER"), col("track_id", "INTEGER"), col("position", "INTEGER"),
        col("added_at", "INTEGER"),
    ], ["playlist_id", "track_id"], fks=[fk("playlists", ["playlist_id"]), fk("tracks", ["track_id"])],
        indices=[index("playlist_tracks", ["track_id"])]),
    entity("play_history", [
        col("id", "INTEGER"), col("user_id", "INTEGER"), col("track_id", "INTEGER"),
        col("played_at", "INTEGER"), col("completed", "INTEGER", default="0"),
    ], ["id"], auto=True, fks=[fk("users", ["user_id"]), fk("tracks", ["track_id"])],
        indices=[index("play_history", ["user_id", "played_at"]), index("play_history", ["track_id"])]),
    entity("downloads", [
        col("track_id", "INTEGER"), col("file_path", "TEXT"), col("size_bytes", "INTEGER"),
        col("downloaded_at", "INTEGER"),
    ], ["track_id"], fks=[fk("tracks", ["track_id"])]),
]

fts_fields = [col("title", "TEXT")]
entities.append({
    "ftsVersion": "FTS4",
    "ftsOptions": {"tokenizer": "unicode61", "tokenizerArgs": [], "contentTable": "tracks",
                   "languageIdColumnName": "", "matchInfo": "FTS4", "notIndexedColumns": [], "prefixSizes": [],
                   "preferredOrder": "ASC"},
    "contentSyncTriggers": [
        f"CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_tracks_fts_{when} {when.replace('_', ' ')} ON `tracks` BEGIN "
        + ("DELETE FROM `tracks_fts` WHERE `docid`=OLD.`rowid`; END" if "DELETE" in when or when == "BEFORE_UPDATE"
           else "INSERT INTO `tracks_fts`(`docid`, `title`) VALUES (NEW.`rowid`, NEW.`title`); END")
        for when in ("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT")
    ],
    "tableName": "tracks_fts",
    "createSql": "CREATE VIRTUAL TABLE IF NOT EXISTS `${TABLE_NAME}` USING FTS4(`title` TEXT NOT NULL, "
                 "tokenize=unicode61, content=`tracks`)",
    "fields": fts_fields,
    "primaryKey": {"autoGenerate": False, "columnNames": ["rowid"]},
})

views = [{
    "viewName": "album_overview",
    "createSql": "CREATE VIEW `${VIEW_NAME}` AS SELECT albums.id AS album_id, albums.title AS title, "
                 "artists.name AS artist_name, COUNT(tracks.id) AS track_count, SUM(tracks.duration_ms) AS "
                 "total_duration_ms FROM albums INNER JOIN artists ON artists.id = albums.artist_id "
                 "LEFT JOIN tracks ON tracks.album_id = albums.id GROUP BY albums.id",
}]

identity = hashlib.md5(json.dumps([entities, views], sort_keys=True).encode()).hexdigest()
schema = {
    "formatVersion": 1,
    "database": {
        "version": VERSION,
        "identityHash": identity,
        "entities": entities,
        "views": views,
        "setupQueries": [
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            f"INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '{identity}')",
        ],
    },
}

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "schemas", DB, f"{VERSION}.json")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w") as f:
    json.dump(schema, f, indent=2)
    f.write("\n")
print(out)
