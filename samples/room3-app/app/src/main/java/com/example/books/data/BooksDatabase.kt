package com.example.books.data

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [Author::class, Book::class, Tag::class, BookTag::class, Reader::class, ReaderProfile::class, Review::class, BookFts::class],
    views = [BookRating::class],
    version = 1,
    exportSchema = true,
)
abstract class BooksDatabase : RoomDatabase()
