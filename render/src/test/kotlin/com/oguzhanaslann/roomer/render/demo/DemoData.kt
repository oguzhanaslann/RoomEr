package com.oguzhanaslann.roomer.render.demo

import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.model.Column
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ForeignKey
import com.oguzhanaslann.roomer.model.FtsInfo
import com.oguzhanaslann.roomer.model.Index
import com.oguzhanaslann.roomer.model.PrimaryKey
import com.oguzhanaslann.roomer.model.Table
import com.oguzhanaslann.roomer.model.View

/** Sample schemas built in code, for the demo window, the PNG renderer and the tests. */
object DemoData {

    fun database(): Database {
        val users = Table(
            name = "users",
            columns = listOf(
                Column("id", "INTEGER", notNull = true),
                Column("email", "TEXT", notNull = true),
                Column("display_name", "TEXT", notNull = true),
                Column("created_at", "INTEGER", notNull = true, defaultValue = "CURRENT_TIMESTAMP"),
                Column("manager_id", "INTEGER", notNull = false),
            ),
            primaryKey = PrimaryKey(listOf("id"), autoGenerate = true),
            foreignKeys = listOf(ForeignKey("users", listOf("manager_id"), listOf("id"), onDelete = "SET NULL")),
            indices = listOf(
                Index("index_users_email", unique = true, columns = listOf("email")),
                Index("index_users_manager_id", unique = false, columns = listOf("manager_id")),
            ),
        )
        val profiles = Table(
            name = "profiles",
            columns = listOf(
                Column("user_id", "INTEGER", notNull = true),
                Column("bio", "TEXT", notNull = false),
                Column("avatar_url", "TEXT", notNull = false),
            ),
            primaryKey = PrimaryKey(listOf("user_id")),
            foreignKeys = listOf(ForeignKey("users", listOf("user_id"), listOf("id"), onDelete = "CASCADE")),
        )
        val posts = Table(
            name = "posts",
            columns = listOf(
                Column("id", "INTEGER", notNull = true),
                Column("author_id", "INTEGER", notNull = true),
                Column("title", "TEXT", notNull = true),
                Column("body", "TEXT", notNull = true),
                Column("published_at", "INTEGER", notNull = false),
            ),
            primaryKey = PrimaryKey(listOf("id"), autoGenerate = true),
            foreignKeys = listOf(ForeignKey("users", listOf("author_id"), listOf("id"), onDelete = "CASCADE", onUpdate = "CASCADE")),
            indices = listOf(Index("index_posts_author_id", unique = false, columns = listOf("author_id"))),
        )
        val comments = Table(
            name = "comments",
            columns = listOf(
                Column("id", "INTEGER", notNull = true),
                Column("post_id", "INTEGER", notNull = true),
                Column("author_id", "INTEGER", notNull = false),
                Column("text", "TEXT", notNull = true),
            ),
            primaryKey = PrimaryKey(listOf("id"), autoGenerate = true),
            foreignKeys = listOf(
                ForeignKey("posts", listOf("post_id"), listOf("id"), onDelete = "CASCADE"),
                ForeignKey("users", listOf("author_id"), listOf("id"), onDelete = "SET NULL"),
            ),
            indices = listOf(
                Index("index_comments_post_id", unique = false, columns = listOf("post_id")),
                Index("index_comments_author_id", unique = false, columns = listOf("author_id")),
            ),
        )
        val tags = Table(
            name = "tags",
            columns = listOf(
                Column("id", "INTEGER", notNull = true),
                Column("name", "TEXT", notNull = true),
            ),
            primaryKey = PrimaryKey(listOf("id"), autoGenerate = true),
            indices = listOf(Index("index_tags_name", unique = true, columns = listOf("name"))),
        )
        val postTags = Table(
            name = "post_tags",
            columns = listOf(
                Column("post_id", "INTEGER", notNull = true),
                Column("tag_id", "INTEGER", notNull = true),
            ),
            primaryKey = PrimaryKey(listOf("post_id", "tag_id")),
            foreignKeys = listOf(
                ForeignKey("posts", listOf("post_id"), listOf("id"), onDelete = "CASCADE"),
                ForeignKey("tags", listOf("tag_id"), listOf("id"), onDelete = "CASCADE"),
            ),
            indices = listOf(Index("index_post_tags_tag_id", unique = false, columns = listOf("tag_id"))),
        )
        val postsFts = Table(
            name = "posts_fts",
            columns = listOf(
                Column("title", "TEXT", notNull = true),
                Column("body", "TEXT", notNull = true),
            ),
            primaryKey = PrimaryKey(emptyList()),
            fts = FtsInfo("FTS4", contentTable = "posts"),
        )
        val view = View(
            name = "user_post_counts",
            sql = "CREATE VIEW `user_post_counts` AS SELECT u.id AS user_id, u.display_name, COUNT(p.id) AS post_count\n" +
                "FROM users u LEFT JOIN posts p ON p.author_id = u.id GROUP BY u.id",
        )
        return Database(
            name = "com.acme.blog.data.BlogDatabase",
            version = 7,
            identityHash = "0f1e2d3c",
            tables = listOf(users, profiles, posts, comments, tags, postTags, postsFts),
            views = listOf(view),
        )
    }

    /** Hand-picked positions for [database]. */
    fun positions(): Map<String, Point> = mapOf(
        "profiles" to Point(40.0, 60.0),
        "users" to Point(400.0, 40.0),
        "user_post_counts" to Point(800.0, 40.0),
        "comments" to Point(40.0, 300.0),
        "posts" to Point(420.0, 300.0),
        "post_tags" to Point(800.0, 300.0),
        "tags" to Point(1120.0, 320.0),
        "posts_fts" to Point(420.0, 520.0),
    )

    /** Synthetic schema: [tables] tables with [columnsPerTable] columns each; every table references up to two earlier ones. */
    fun large(tables: Int = 100, columnsPerTable: Int = 10): Database {
        val list = ArrayList<Table>()
        for (i in 0 until tables) {
            val columns = ArrayList<Column>()
            columns += Column("id", "INTEGER", notNull = true)
            val fks = ArrayList<ForeignKey>()
            val indices = ArrayList<Index>()
            if (i > 0) {
                val parent = (i * 7 + 3) % i
                columns += Column("table_${parent}_id", "INTEGER", notNull = i % 3 != 0)
                fks += ForeignKey("table_$parent", listOf("table_${parent}_id"), listOf("id"), onDelete = "CASCADE")
                indices += Index("index_table_${i}_a", unique = i % 5 == 0, columns = listOf("table_${parent}_id"))
            }
            if (i > 3) {
                val parent = i - 3
                columns += Column("other_${parent}_id", "INTEGER", notNull = false)
                fks += ForeignKey("table_$parent", listOf("other_${parent}_id"), listOf("id"))
            }
            while (columns.size < columnsPerTable) {
                val c = columns.size
                columns += Column("column_number_$c", if (c % 2 == 0) "TEXT" else "INTEGER", notNull = c % 3 == 0)
            }
            list += Table("table_$i", columns, PrimaryKey(listOf("id"), autoGenerate = true), fks, indices)
        }
        return Database("com.acme.big.BigDatabase", 1, null, list, emptyList())
    }

    /** Simple grid placement for [large]. */
    fun gridPositions(database: Database, perRow: Int = 10, dx: Double = 440.0, dy: Double = 330.0): Map<String, Point> =
        database.entities.mapIndexed { i, e -> e.name to Point((i % perRow) * dx, (i / perRow) * dy) }.toMap()
}
