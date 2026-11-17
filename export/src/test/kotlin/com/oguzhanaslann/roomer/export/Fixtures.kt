package com.oguzhanaslann.roomer.export

import com.oguzhanaslann.roomer.model.Column
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ForeignKey
import com.oguzhanaslann.roomer.model.FtsInfo
import com.oguzhanaslann.roomer.model.Index
import com.oguzhanaslann.roomer.model.PrimaryKey
import com.oguzhanaslann.roomer.model.Table
import com.oguzhanaslann.roomer.model.View

/** Model fixtures built by hand (the parser is a separate module). */
object Fixtures {

    fun col(name: String, type: String = "INTEGER", notNull: Boolean = true) = Column(name, type, notNull)

    fun database(tables: List<Table>, views: List<View> = emptyList()) = Database(
        name = "com.example.AppDatabase",
        version = 1,
        identityHash = "hash",
        tables = tables,
        views = views,
    )

    /**
     * Equivalent of the `fx-basic` schema: users; posts (not-null FK); profiles (PK is the FK, 1:1);
     * categories (nullable self-reference, multi-column unique index); post_categories (composite-PK
     * join table); "order-items" (needs quoting, nullable FK with single-column unique index);
     * a view; an FTS4 table with a content table. Tables are deliberately not in name order.
     */
    val basic: Database = database(
        tables = listOf(
            Table(
                name = "users",
                columns = listOf(col("id"), col("email", "TEXT"), col("display_name", "TEXT", notNull = false)),
                primaryKey = PrimaryKey(listOf("id"), autoGenerate = true),
                indices = listOf(Index("index_users_email", unique = true, columns = listOf("email"))),
            ),
            Table(
                name = "posts",
                columns = listOf(col("id"), col("author_id"), col("title", "TEXT"), col("body", "TEXT", notNull = false)),
                primaryKey = PrimaryKey(listOf("id"), autoGenerate = true),
                foreignKeys = listOf(ForeignKey("users", listOf("author_id"), listOf("id"), onDelete = "CASCADE")),
                indices = listOf(Index("index_posts_author_id", unique = false, columns = listOf("author_id"))),
            ),
            Table(
                name = "profiles",
                columns = listOf(col("user_id"), col("bio", "TEXT", notNull = false)),
                primaryKey = PrimaryKey(listOf("user_id")),
                foreignKeys = listOf(ForeignKey("users", listOf("user_id"), listOf("id"), onDelete = "CASCADE")),
            ),
            Table(
                name = "categories",
                columns = listOf(
                    col("id"),
                    col("parent_id", notNull = false),
                    col("name", "TEXT"),
                    col("slug", "TEXT", notNull = false),
                ),
                primaryKey = PrimaryKey(listOf("id"), autoGenerate = true),
                foreignKeys = listOf(ForeignKey("categories", listOf("parent_id"), listOf("id"), onDelete = "SET NULL")),
                indices = listOf(
                    Index("index_categories_parent_id_name", unique = true, columns = listOf("parent_id", "name")),
                    Index("index_categories_slug", unique = false, columns = listOf("slug")),
                ),
            ),
            Table(
                name = "post_categories",
                columns = listOf(col("post_id"), col("category_id")),
                primaryKey = PrimaryKey(listOf("post_id", "category_id")),
                foreignKeys = listOf(
                    ForeignKey("posts", listOf("post_id"), listOf("id"), onDelete = "CASCADE"),
                    ForeignKey("categories", listOf("category_id"), listOf("id"), onDelete = "CASCADE"),
                ),
                indices = listOf(Index("index_post_categories_category_id", unique = false, columns = listOf("category_id"))),
            ),
            Table(
                name = "order-items",
                columns = listOf(col("id"), col("post_id", notNull = false), col("unit price", "REAL")),
                primaryKey = PrimaryKey(listOf("id"), autoGenerate = true),
                foreignKeys = listOf(ForeignKey("posts", listOf("post_id"), listOf("id"))),
                indices = listOf(Index("index_order-items_post_id", unique = true, columns = listOf("post_id"))),
            ),
            Table(
                name = "posts_fts",
                columns = listOf(col("title", "TEXT"), col("body", "TEXT", notNull = false)),
                primaryKey = PrimaryKey(emptyList()),
                fts = FtsInfo("FTS4", contentTable = "posts"),
            ),
        ),
        views = listOf(View("active_users", "CREATE VIEW `active_users` AS SELECT * FROM users")),
    )

    /**
     * Edge cases: names that need quoting or sanitising, composite FK, FK to a missing table,
     * two FKs between the same pair (declared in reverse of sorted order), column-less table,
     * FTS3 without content table, upper/lower case ordering.
     */
    val edge: Database = database(
        tables = listOf(
            Table(
                name = "alpha",
                columns = listOf(
                    col("tenant_id"),
                    col("order_no"),
                    col("updated_by", notNull = false),
                    col("created_by"),
                    col("ghost_id"),
                ),
                primaryKey = PrimaryKey(listOf("tenant_id", "order_no")),
                foreignKeys = listOf(
                    ForeignKey("Zeta", listOf("updated_by"), listOf("id")),
                    ForeignKey("ghost", listOf("ghost_id"), listOf("id")),
                    ForeignKey("Zeta", listOf("created_by"), listOf("id")),
                ),
            ),
            Table(
                name = "say \"hi\"",
                columns = emptyList(),
                primaryKey = PrimaryKey(emptyList()),
            ),
            Table(
                name = "docs_fts",
                columns = listOf(col("content", "TEXT")),
                primaryKey = PrimaryKey(emptyList()),
                fts = FtsInfo("FTS3"),
            ),
            Table(
                name = "2024 archive",
                columns = listOf(
                    col("tenant id"),
                    col("order#"),
                    col("note", "VARCHAR(20)", notNull = false),
                    col("1st", "TEXT", notNull = false),
                ),
                primaryKey = PrimaryKey(listOf("tenant id", "order#")),
                foreignKeys = listOf(
                    ForeignKey("alpha", listOf("tenant id", "order#"), listOf("tenant_id", "order_no")),
                ),
                indices = listOf(Index("index_archive_1st", unique = true, columns = listOf("1st"))),
            ),
            Table(
                name = "Zeta",
                columns = listOf(col("id")),
                primaryKey = PrimaryKey(listOf("id")),
            ),
        ),
        views = listOf(View("v weird", "CREATE VIEW `v weird` AS SELECT 1")),
    )
}
