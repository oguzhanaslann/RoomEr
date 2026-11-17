package com.oguzhanaslann.roomer.toolwindow

import com.intellij.testFramework.common.timeoutRunBlocking
import com.oguzhanaslann.roomer.RoomErPlatformTestCase
import com.oguzhanaslann.roomer.TestSchemas
import com.oguzhanaslann.roomer.layout.LayoutEngine
import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.model.SchemaError

/** Loading pipeline driven synchronously with a layout test double: AC-01.1, AC-03.x, AC-05.x, AC-06.x, AC-10.2, AC-12.4. */
class DiagramControllerTest : RoomErPlatformTestCase() {

    private fun DiagramController.reloadNow(force: Boolean = true) = timeoutRunBlocking { reload(force) }

    fun testNoSchemasGivesEmptyState() {
        val snapshot = newController().reloadNow()
        assertEquals(DiagramState.NoSchemas, snapshot.state)
        assertTrue(snapshot.databases.isEmpty())
    }

    fun testLatestVersionIsShownWithoutConfiguration() {
        addFile("app/schemas/com.acme.AppDb/1.json", TestSchemas.schema(1, "users"))
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users", "orders"))

        val controller = newController()
        val diagram = controller.reloadNow().state.asDiagram()

        assertEquals("AppDb", diagram.database.simpleName)
        assertEquals(2, diagram.database.version)
        assertEquals(setOf("users", "orders"), diagram.positions.keys)
        assertFalse(diagram.preserveViewport)
        assertFalse(diagram.stale)
        // AC-10.2: layout went through the injected LayoutEngine.
        val request = layoutEngine.requests.single()
        assertEquals(listOf("users", "orders"), request.nodes.map { it.id })
        assertTrue(request.fixedPositions.isEmpty())
        assertFalse(controller.loading.value)
    }

    fun testReloadPicksUpChangedFileAndKeepsPositions() {
        val file = addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users", "orders"))
        val controller = newController()
        val first = controller.reloadNow().state.asDiagram()

        setText(file, TestSchemas.schema(2, "users", "invoices"))
        val second = controller.reloadNow(force = false).state.asDiagram()

        assertEquals(setOf("users", "invoices"), second.database.entities.map { it.name }.toSet())
        assertTrue("reload keeps zoom and viewport", second.preserveViewport)
        assertTrue(second.modelRevision != first.modelRevision)
        // AC-05.3: existing tables keep their place, new ones are placed by the engine, removed ones are gone.
        assertEquals(first.positions["users"], second.positions["users"])
        assertEquals(setOf("users", "invoices"), second.positions.keys)
        assertEquals(mapOf("users" to first.positions.getValue("users")), layoutEngine.requests.last().fixedPositions)
    }

    fun testUnchangedFileIsNotReloadedAutomatically() {
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        val controller = newController()
        val first = controller.reloadNow().state.asDiagram()

        val automatic = controller.reloadNow(force = false).state.asDiagram()
        assertEquals(first.modelRevision, automatic.modelRevision)
        assertEquals(1, layoutEngine.requests.size)

        val manual = controller.reloadNow(force = true).state.asDiagram() // AC-05.4 "Refresh"
        assertTrue(manual.modelRevision != first.modelRevision)
        assertEquals(2, layoutEngine.requests.size)
    }

    fun testNewHigherVersionReplacesTheDiagram() {
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        val controller = newController()
        assertEquals(2, controller.reloadNow().state.asDiagram().database.version)

        addFile("app/schemas/com.acme.AppDb/3.json", TestSchemas.schema(3, "users", "orders"))
        val snapshot = controller.reloadNow(force = false)
        val diagram = snapshot.state.asDiagram()

        assertEquals(3, diagram.database.version) // AC-05.2
        assertEquals(3, snapshot.selected!!.latestVersion)
        assertTrue(diagram.preserveViewport)
    }

    fun testInvalidFileKeepsLastGoodDiagramAsStale() {
        val file = addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        val controller = newController()
        val good = controller.reloadNow().state.asDiagram()

        setText(file, "{ this is not json")
        val stale = controller.reloadNow(force = false).state.asDiagram()

        assertTrue(stale.stale) // AC-06.5
        assertSame(good.database, stale.database)
        assertEquals(good.positions, stale.positions)
        assertEquals(good.modelRevision, stale.modelRevision)
        assertEquals(file.path, stale.error!!.filePath) // AC-06.2
        assertTrue(stale.error is SchemaError.MalformedJson)
        assertTrue(stale.error.reason.isNotBlank())

        // Still broken: an automatic reload changes nothing.
        assertTrue(controller.reloadNow(force = false).state.asDiagram().stale)

        setText(file, TestSchemas.schema(2, "users", "orders"))
        val fixed = controller.reloadNow(force = false).state.asDiagram()
        assertFalse(fixed.stale)
        assertNull(fixed.error)
        assertEquals(good.positions["users"], fixed.positions["users"])
    }

    fun testBrokenNewVersionKeepsOlderDiagramAsStale() {
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        val controller = newController()
        controller.reloadNow()

        val broken = addFile("app/schemas/com.acme.AppDb/3.json", "[]")
        val stale = controller.reloadNow(force = false).state.asDiagram()
        assertTrue(stale.stale)
        assertEquals(2, stale.database.version)
        assertEquals(broken.path, stale.error!!.filePath)
    }

    fun testInvalidFileWithoutPreviousDiagramIsAnErrorState() {
        val file = addFile("app/schemas/com.acme.AppDb/2.json", "not json at all")
        val failed = newController().reloadNow().state
        assertTrue("was $failed", failed is DiagramState.Failed)
        failed as DiagramState.Failed
        assertEquals(file.path, failed.error.filePath)
        assertTrue(failed.error.reason.isNotBlank())
    }

    fun testUnsupportedFormatVersionNamesFoundAndSupportedValue() {
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users", formatVersion = 7))
        val failed = newController().reloadNow().state as DiagramState.Failed
        val error = failed.error as SchemaError.UnsupportedFormat // AC-06.4
        assertEquals("7", error.found)
        assertEquals(1, error.supported)
        val text = MessagePanel.describe(error)
        assertTrue(text, text.contains("7") && text.contains("1"))
    }

    fun testDeletedSchemasReturnToEmptyState() {
        val file = addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        val controller = newController()
        controller.reloadNow()
        delete(file)
        assertEquals(DiagramState.NoSchemas, controller.reloadNow(force = false).state)
    }

    fun testSavedPositionsAndCollapsedStateAreUsedOnFirstLoad() {
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users", "orders"))
        state.setPositions("com.acme.AppDb", mapOf("users" to Point(11.0, 22.0), "dropped_table" to Point(1.0, 1.0)))
        state.setCollapsed("com.acme.AppDb", setOf("orders", "dropped_table"))

        val diagram = newController().reloadNow().state.asDiagram()

        assertEquals(Point(11.0, 22.0), diagram.positions["users"]) // AC-12.2
        assertEquals(setOf("orders"), diagram.collapsed) // AC-16.2
        assertEquals(mapOf("users" to Point(11.0, 22.0)), layoutEngine.requests.single().fixedPositions)
    }

    fun testDraggedPositionsArePersistedAndSurviveReload() {
        val file = addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users", "orders"))
        val controller = newController()
        val first = controller.reloadNow().state.asDiagram()

        val dragged = first.positions + ("users" to Point(-5.0, -6.0))
        controller.positionsChanged(dragged)
        controller.collapsedChanged(setOf("users"))
        assertEquals(dragged, state.positions("com.acme.AppDb"))
        assertEquals(setOf("users"), state.collapsed("com.acme.AppDb"))
        assertEquals("canvas already shows the drag", first.modelRevision, controller.snapshot.state.asDiagram().modelRevision)

        setText(file, TestSchemas.schema(2, "users", "invoices"))
        val reloaded = controller.reloadNow(force = false).state.asDiagram()
        assertEquals(Point(-5.0, -6.0), reloaded.positions["users"])
        assertEquals(setOf("users"), reloaded.collapsed)

        // A new session (new controller) restores them from the workspace state.
        val restored = newController().reloadNow().state.asDiagram()
        assertEquals(Point(-5.0, -6.0), restored.positions["users"])
    }

    fun testResetLayoutDiscardsStoredPositions() {
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users", "orders"))
        val controller = newController()
        val first = controller.reloadNow().state.asDiagram()
        controller.positionsChanged(first.positions + ("users" to Point(-5.0, -6.0)))

        val reset = timeoutRunBlocking { controller.resetLayout() }.state.asDiagram()

        assertTrue(state.positions("com.acme.AppDb").isEmpty()) // AC-12.4
        assertTrue(layoutEngine.requests.last().fixedPositions.isEmpty())
        assertEquals(first.positions, reset.positions)
        assertFalse("reset fits the diagram again", reset.preserveViewport)
    }

    fun testDatabaseSelectionIsRemembered() {
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        addFile("data/schemas/com.acme.CacheDb/5.json", TestSchemas.schema(5, "cache"))
        addFile("sync/schemas/com.acme.sync.SyncDb/1.json", TestSchemas.schema(1, "queue"))
        val controller = newController()

        val initial = controller.reloadNow()
        assertEquals(listOf("AppDb", "CacheDb", "SyncDb"), initial.databases.map { it.label }) // AC-03.1
        assertEquals("com.acme.sync.SyncDb", initial.databases[2].name)
        assertEquals("AppDb", initial.selected!!.label)

        val cache = initial.databases[1]
        val switched = timeoutRunBlocking { controller.selectDatabase(cache.id) }
        val diagram = switched.state.asDiagram()
        assertEquals("CacheDb", diagram.database.simpleName) // AC-03.2
        assertEquals(setOf("cache"), diagram.positions.keys)
        assertFalse("another database is fitted to the window", diagram.preserveViewport)
        assertEquals(cache.id, state.selectedDatabaseId)

        assertEquals("CacheDb", newController().reloadNow().selected!!.label)
    }

    fun testManualFolderIsRememberedAndLoaded() {
        val folder = addFile("exported/room/com.acme.AppDb/4.json", TestSchemas.schema(4, "users")).parent.parent
        val controller = newController()
        assertEquals(DiagramState.NoSchemas, controller.reloadNow().state)

        val snapshot = timeoutRunBlocking { controller.chooseSchemaFolder(folder.url) }
        assertEquals(4, snapshot.state.asDiagram().database.version) // AC-02.1
        assertEquals(folder.url, state.schemaFolderUrl)
        assertTrue(controller.isWatchedPath(folder.path + "/com.acme.AppDb/5.json"))

        assertEquals(4, newController().reloadNow().state.asDiagram().database.version)
    }

    fun testManualFolderWithoutSchemasNamesTheFolder() {
        val folder = addFile("docs/readme.json", "{}").parent
        val controller = newController()

        val snapshot = timeoutRunBlocking { controller.chooseSchemaFolder(folder.url) }
        val empty = snapshot.state as DiagramState.FolderWithoutSchemas // AC-02.2, AC-06.3
        assertTrue(empty.folderPath, empty.folderPath.endsWith("docs"))

        val automatic = timeoutRunBlocking { controller.chooseSchemaFolder(null) }
        assertEquals(DiagramState.NoSchemas, automatic.state)
        assertNull(state.schemaFolderUrl)
    }

    fun testFailingLayoutEngineStillProducesADiagram() {
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "a", "b", "c"))
        val controller = DiagramController(project, state, LayoutEngine { error("boom") }, com.oguzhanaslann.roomer.FixedSizeMeasurer)
        val diagram = timeoutRunBlocking { controller.reload() }.state.asDiagram()
        assertEquals(setOf("a", "b", "c"), diagram.positions.keys)
        assertEquals("no two nodes share a position", 3, diagram.positions.values.toSet().size)
    }
}
