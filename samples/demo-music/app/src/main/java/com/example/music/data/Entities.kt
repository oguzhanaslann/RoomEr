package com.example.music.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "artists", indices = [Index(value = ["name"], unique = true)])
data class Artist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val country: String?,
    val bio: String?,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
)

@Entity(
    tableName = "albums",
    foreignKeys = [ForeignKey(entity = Artist::class, parentColumns = ["id"], childColumns = ["artist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("artist_id")],
)
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    val title: String,
    @ColumnInfo(name = "release_year") val releaseYear: Int?,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(entity = Album::class, parentColumns = ["id"], childColumns = ["album_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("album_id")],
)
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "album_id") val albumId: Long,
    val title: String,
    @ColumnInfo(name = "track_number") val trackNumber: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(defaultValue = "0") val explicit: Boolean = false,
)

@Fts4(contentEntity = Track::class)
@Entity(tableName = "tracks_fts")
data class TrackFts(val title: String)
