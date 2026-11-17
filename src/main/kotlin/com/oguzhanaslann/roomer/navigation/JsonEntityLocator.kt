package com.oguzhanaslann.roomer.navigation

import com.oguzhanaslann.roomer.model.EntityKind

/** Finds where an entity is declared inside Room's schema JSON text. Pure. */
object JsonEntityLocator {
    /**
     * Offset of the `"tableName": "<name>"` (or `"viewName"` for views) property in [json], or -1.
     * The name is compared as Room writes it: a plain JSON string.
     */
    fun offsetOf(json: String, entityName: String, kind: EntityKind): Int {
        val property = if (kind == EntityKind.VIEW) "viewName" else "tableName"
        val escapedName = Regex.escape(jsonEscape(entityName))
        return Regex("\"$property\"\\s*:\\s*\"$escapedName\"").find(json)?.range?.first ?: -1
    }

    private fun jsonEscape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
