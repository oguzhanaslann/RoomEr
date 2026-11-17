package com.oguzhanaslann.roomer.state

import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.oguzhanaslann.roomer.RoomErPlatformTestCase
import com.oguzhanaslann.roomer.layout.Point

/** FR-25, FR-32, FR-03, FR-04; AC-12.2, AC-12.3. */
class RoomErStateTest : RoomErPlatformTestCase() {

    fun testStateIsStoredOnlyInTheWorkspaceFile() {
        val annotation = RoomErState::class.java.getAnnotation(State::class.java)
        assertEquals(listOf(StoragePathMacros.WORKSPACE_FILE), annotation.storages.map { it.value })
        assertTrue(annotation.storages.none { it.deprecated })
    }

    fun testRoundTripThroughXml() {
        state.schemaFolderUrl = "file:///work/app/schemas"
        state.selectedDatabaseId = "/work/app/schemas/com.acme.AppDb"
        state.legendVisible = false
        state.setPositions("com.acme.AppDb", mapOf("users" to Point(10.5, -20.0), "odd \"name\" <x>" to Point(0.0, 3.25)))
        state.setCollapsed("com.acme.AppDb", setOf("users", "orders"))
        state.setPositions("com.acme.OtherDb", mapOf("users" to Point(1.0, 2.0)))

        val xml = JDOMUtil.write(XmlSerializer.serialize(state.state))
        val restored = RoomErState().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), RoomErState.Data::class.java))
        }

        assertEquals("file:///work/app/schemas", restored.schemaFolderUrl)
        assertEquals("/work/app/schemas/com.acme.AppDb", restored.selectedDatabaseId)
        assertFalse(restored.legendVisible)
        assertEquals(mapOf("users" to Point(10.5, -20.0), "odd \"name\" <x>" to Point(0.0, 3.25)), restored.positions("com.acme.AppDb"))
        assertEquals(setOf("users", "orders"), restored.collapsed("com.acme.AppDb"))
        assertEquals(mapOf("users" to Point(1.0, 2.0)), restored.positions("com.acme.OtherDb"))
        assertTrue(restored.positions("unknown").isEmpty())
    }

    fun testReplacingPositionsDropsRemovedTables() {
        state.setPositions("db", mapOf("a" to Point(1.0, 1.0), "b" to Point(2.0, 2.0)))
        state.setPositions("db", mapOf("b" to Point(5.0, 5.0)))
        assertEquals(mapOf("b" to Point(5.0, 5.0)), state.positions("db"))

        state.clearPositions("db")
        assertTrue(state.positions("db").isEmpty())
        assertTrue("empty entries are not persisted", state.state.databases.isEmpty())
    }

    fun testGetStateReturnsACopy() {
        state.setPositions("db", mapOf("a" to Point(1.0, 1.0)))
        val snapshot = state.state
        state.setPositions("db", mapOf("a" to Point(9.0, 9.0)))
        assertEquals(1.0, snapshot.databases.getValue("db").positions.getValue("a").x)
    }
}
