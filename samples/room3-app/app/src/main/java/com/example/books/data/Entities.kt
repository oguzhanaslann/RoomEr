package com.example.books.data

import androidx.room3.ColumnInfo
import androidx.room3.DatabaseView
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts4
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "authors")
data class Author(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val country: String?,
)

@Entity(
    tableName = "books",
    foreignKeys = [ForeignKey(entity = Author::class, parentColumns = ["id"], childColumns = ["author_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("author_id"), Index(value = ["isbn"], unique = true)],
)
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "author_id") val authorId: Long,
    val title: String,
    val isbn: String,
    @ColumnInfo(name = "published_year") val publishedYear: Int?,
)

// No tableName: Room uses the class name, so go to source must match "Tag" by class name.
@Entity
data class Tag(
    @PrimaryKey val name: String,
)

@Entity(
    tableName = "book_tags",
    primaryKeys = ["book_id", "tag_name"],
    foreignKeys = [
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["book_id"]),
        ForeignKey(entity = Tag::class, parentColumns = ["name"], childColumns = ["tag_name"]),
    ],
    indices = [Index("tag_name")],
)
data class BookTag(
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "tag_name") val tagName: String,
)

@Entity(tableName = "readers")
data class Reader(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
)

@Entity(
    tableName = "reader_profiles",
    foreignKeys = [ForeignKey(entity = Reader::class, parentColumns = ["id"], childColumns = ["reader_id"])],
)
data class ReaderProfile(
    @PrimaryKey @ColumnInfo(name = "reader_id") val readerId: Long,
    val bio: String?,
)

@Entity(
    tableName = "reviews",
    foreignKeys = [
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["book_id"]),
        ForeignKey(entity = Reader::class, parentColumns = ["id"], childColumns = ["reader_id"]),
    ],
    indices = [Index("book_id"), Index("reader_id")],
)
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "reader_id") val readerId: Long?,
    val rating: Int,
    val body: String?,
)

@Fts4(contentEntity = Book::class)
@Entity(tableName = "books_fts")
data class BookFts(
    val title: String,
)

@DatabaseView(
    viewName = "book_ratings",
    value = "SELECT books.id AS bookId, books.title AS title, AVG(reviews.rating) AS rating " +
        "FROM books LEFT JOIN reviews ON reviews.book_id = books.id GROUP BY books.id",
)
data class BookRating(
    val bookId: Long,
    val title: String,
    val rating: Double?,
)
