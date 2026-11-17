package com.oguzhanaslann.roomer.navigation

import com.oguzhanaslann.roomer.TestSchemas
import com.oguzhanaslann.roomer.model.EntityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonEntityLocatorTest {
    @Test
    fun `finds the tableName property of the entity`() {
        val json = TestSchemas.schema(1, "users", "users_archive", "orders")
        val offset = JsonEntityLocator.offsetOf(json, "users_archive", EntityKind.TABLE)
        assertTrue(json.substring(offset).startsWith("\"tableName\": \"users_archive\""))
        assertTrue(json.substring(JsonEntityLocator.offsetOf(json, "users", EntityKind.TABLE)).startsWith("\"tableName\": \"users\""))
    }

    @Test
    fun `views use viewName and missing names give -1`() {
        val json = """{"views":[{"viewName":"v.active (1)","createSql":""}],"entities":[{"tableName":"t"}]}"""
        assertEquals(json.indexOf("\"viewName\""), JsonEntityLocator.offsetOf(json, "v.active (1)", EntityKind.VIEW))
        assertEquals(-1, JsonEntityLocator.offsetOf(json, "t", EntityKind.VIEW))
        assertEquals(-1, JsonEntityLocator.offsetOf(json, "missing", EntityKind.TABLE))
    }
}
