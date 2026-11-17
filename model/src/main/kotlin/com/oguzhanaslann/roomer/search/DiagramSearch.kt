package com.oguzhanaslann.roomer.search

import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.Table

/** A table/view ([column] == null) or a column of a table that matched a search query. */
data class SearchResult(val entity: String, val column: String? = null)

object DiagramSearch {
    /**
     * Case-insensitive substring search over entity names and column names (spec FR-30).
     * Entity matches come first, then column matches, each in model order. Blank query → no results.
     */
    fun search(database: Database, query: String): List<SearchResult> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val entityMatches = database.entities
            .filter { it.name.contains(needle, ignoreCase = true) }
            .map { SearchResult(it.name) }
        val columnMatches = database.entities.filterIsInstance<Table>().flatMap { table ->
            table.columns
                .filter { it.name.contains(needle, ignoreCase = true) }
                .map { SearchResult(table.name, it.name) }
        }
        return entityMatches + columnMatches
    }
}
