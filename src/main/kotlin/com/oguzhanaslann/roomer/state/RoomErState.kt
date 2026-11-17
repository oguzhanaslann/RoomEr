package com.oguzhanaslann.roomer.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap
import com.oguzhanaslann.roomer.layout.Point

/**
 * Per-project UI state of the Room ER tool window. Stored ONLY in the workspace file
 * (`.idea/workspace.xml`, never under version control): spec FR-25, AC-12.3, NFR-04.
 */
@Service(Service.Level.PROJECT)
@State(name = "RoomErState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RoomErState : PersistentStateComponent<RoomErState.Data> {

    class Data {
        /** VFS url of the folder chosen with "Choose schema folder…" (FR-03). */
        var schemaFolderUrl: String? = null
        /** [com.oguzhanaslann.roomer.discovery.DiscoveredDatabase.id] of the database shown last (FR-04). */
        var selectedDatabaseId: String? = null
        var legendVisible: Boolean = true
        /** Layout state per database, keyed by database name (FR-25, FR-32). */
        @get:XMap(entryTagName = "database", keyAttributeName = "name")
        var databases: MutableMap<String, DatabaseLayout> = LinkedHashMap()
    }

    @Tag("layout")
    class DatabaseLayout {
        /** Node positions keyed by table/view name. */
        @get:XMap(entryTagName = "node", keyAttributeName = "name")
        var positions: MutableMap<String, NodePosition> = LinkedHashMap()
        @get:XCollection(elementName = "collapsed", valueAttributeName = "name")
        var collapsed: MutableList<String> = ArrayList()
    }

    @Tag("position")
    class NodePosition {
        @get:Attribute var x: Double = 0.0
        @get:Attribute var y: Double = 0.0
    }

    private val lock = Any()
    private var data = Data()

    override fun getState(): Data = synchronized(lock) { copy(data) }

    override fun loadState(state: Data) {
        synchronized(lock) { data = copy(state) }
    }

    var schemaFolderUrl: String?
        get() = synchronized(lock) { data.schemaFolderUrl }
        set(value) = synchronized(lock) { data.schemaFolderUrl = value?.takeIf { it.isNotBlank() } }

    var selectedDatabaseId: String?
        get() = synchronized(lock) { data.selectedDatabaseId }
        set(value) = synchronized(lock) { data.selectedDatabaseId = value }

    var legendVisible: Boolean
        get() = synchronized(lock) { data.legendVisible }
        set(value) = synchronized(lock) { data.legendVisible = value }

    fun positions(databaseKey: String): Map<String, Point> = synchronized(lock) {
        data.databases[databaseKey]?.positions?.mapValues { Point(it.value.x, it.value.y) }.orEmpty()
    }

    /** Replaces all stored positions of the database, so tables that no longer exist are dropped. */
    fun setPositions(databaseKey: String, positions: Map<String, Point>) = synchronized(lock) {
        val layout = data.databases.getOrPut(databaseKey) { DatabaseLayout() }
        layout.positions = positions.entries
            .filter { it.value.x.isFinite() && it.value.y.isFinite() }
            .associateTo(LinkedHashMap()) { (name, point) ->
                name to NodePosition().apply { x = point.x; y = point.y }
            }
        removeIfEmpty(databaseKey)
    }

    fun clearPositions(databaseKey: String) = setPositions(databaseKey, emptyMap())

    fun collapsed(databaseKey: String): Set<String> = synchronized(lock) {
        data.databases[databaseKey]?.collapsed?.toSet().orEmpty()
    }

    fun setCollapsed(databaseKey: String, collapsed: Set<String>) = synchronized(lock) {
        val layout = data.databases.getOrPut(databaseKey) { DatabaseLayout() }
        layout.collapsed = collapsed.sorted().toMutableList()
        removeIfEmpty(databaseKey)
    }

    private fun removeIfEmpty(databaseKey: String) {
        val layout = data.databases[databaseKey] ?: return
        if (layout.positions.isEmpty() && layout.collapsed.isEmpty()) data.databases.remove(databaseKey)
    }

    private fun copy(source: Data): Data = Data().also { target ->
        target.schemaFolderUrl = source.schemaFolderUrl
        target.selectedDatabaseId = source.selectedDatabaseId
        target.legendVisible = source.legendVisible
        source.databases.forEach { (key, layout) ->
            target.databases[key] = DatabaseLayout().also { copy ->
                layout.positions.forEach { (name, p) ->
                    copy.positions[name] = NodePosition().apply { x = p.x; y = p.y }
                }
                copy.collapsed = ArrayList(layout.collapsed)
            }
        }
    }

    companion object {
        fun getInstance(project: Project): RoomErState = project.service()
    }
}
