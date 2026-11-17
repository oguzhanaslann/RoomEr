package com.oguzhanaslann.roomer.toolwindow

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.oguzhanaslann.roomer.RoomErBundle
import com.oguzhanaslann.roomer.discovery.DiscoveredDatabase
import com.oguzhanaslann.roomer.discovery.SchemaDiscovery
import com.oguzhanaslann.roomer.discovery.SchemaPathMatcher
import com.oguzhanaslann.roomer.export.MermaidGenerator
import com.oguzhanaslann.roomer.layout.LayoutEdge
import com.oguzhanaslann.roomer.layout.LayoutEngine
import com.oguzhanaslann.roomer.layout.LayoutNode
import com.oguzhanaslann.roomer.layout.LayoutRequest
import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.layout.Size
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.model.ParseResult
import com.oguzhanaslann.roomer.model.SchemaError
import com.oguzhanaslann.roomer.parser.SchemaParser
import com.oguzhanaslann.roomer.render.DiagramMetrics
import com.oguzhanaslann.roomer.state.RoomErState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.sqrt

/** What the tool window shows. Immutable; produced by [DiagramController]. */
sealed interface DiagramState {
    /** Nothing has been loaded yet. */
    data object Loading : DiagramState

    /** No schema files anywhere (spec FR-40). */
    data object NoSchemas : DiagramState

    /** The folder chosen by hand contains no valid schema files, and nothing else was found (FR-42). */
    data class FolderWithoutSchemas(val folderPath: String) : DiagramState

    /** The schema file could not be read or parsed and there is no earlier diagram to keep (FR-41). */
    data class Failed(val source: DiscoveredDatabase, val error: SchemaError) : DiagramState

    data class Diagram(
        val source: DiscoveredDatabase,
        val database: Database,
        /** Top-left position of every entity. */
        val positions: Map<String, Point>,
        val collapsed: Set<String>,
        /** True on reload of the database already on screen: zoom and viewport must not change (FR-07). */
        val preserveViewport: Boolean,
        /** Changes whenever [database] or [positions] were produced by a new load. */
        val modelRevision: Long,
        /** True if a newer file failed to load and [database] is the last good model (FR-41, AC-06.5). */
        val stale: Boolean = false,
        /** Why the newer file failed; non-null exactly when [stale]. */
        val error: SchemaError? = null,
    ) : DiagramState
}

/** Everything the UI needs to render, published atomically. */
data class DiagramSnapshot(
    val databases: List<DiscoveredDatabase> = emptyList(),
    val selected: DiscoveredDatabase? = null,
    val state: DiagramState = DiagramState.Loading,
    /**
     * Set when a manually chosen folder holds no schema files but other databases were still found
     * (otherwise the state is [DiagramState.FolderWithoutSchemas]).
     */
    val emptyManualFolder: String? = null,
    /** Monotonic counter, so two loads never produce equal snapshots. */
    val revision: Long = 0,
)

/** Measures node sizes for layout; [DiagramMetrics.measure] in production. */
fun interface NodeMeasurer {
    fun measure(database: Database, collapsed: Set<String>): Map<String, Size>
}

/**
 * UI-free loading pipeline (spec FR-01–07, FR-40–42): discovery → read → parse → measure → layout.
 * Every suspend function may be called from any thread and never touches the EDT; results are published
 * through [snapshots]. Loads are serialised; callers cancel superseded loads by cancelling their job.
 *
 * @param layoutEngine replaceable, see AC-10.2
 *
 * Open only so tests can observe the persistence calls ([collapsedChanged], [positionsChanged]).
 */
open class DiagramController(
    private val project: Project,
    private val state: RoomErState,
    @Volatile var layoutEngine: LayoutEngine,
    private val measurer: NodeMeasurer = NodeMeasurer { db, collapsed -> DiagramMetrics.measure(db, collapsed) },
) {
    private val discovery = SchemaDiscovery(project)
    private val mutex = Mutex()
    private val activeLoads = AtomicInteger()

    private val _snapshots = MutableStateFlow(DiagramSnapshot())
    val snapshots: StateFlow<DiagramSnapshot> = _snapshots.asStateFlow()

    private val _loading = MutableStateFlow(false)
    /** True while a load is running or queued (used for the progress indicator, NFR-01). */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Guarded by [mutex] for writes; read by [isWatchedPath] from the VFS listener. */
    @Volatile private var knownFiles: Map<String, VirtualFile> = emptyMap()
    @Volatile private var manualFolderPath: String? = null

    /** Identity of the file behind the current diagram, to skip reloads when nothing changed. */
    private var loadedFingerprint: Fingerprint? = null
    private var revision = 0L

    private data class Fingerprint(
        val databaseIds: List<String>,
        val filePath: String,
        val modificationStamp: Long,
        val timeStamp: Long,
        val length: Long,
    )

    val snapshot: DiagramSnapshot get() = _snapshots.value

    /**
     * Runs discovery and (re)loads the selected database.
     * @param force false for automatic reloads: skipped when the file on screen has not changed
     */
    suspend fun reload(force: Boolean = true): DiagramSnapshot = load(force = force, resetLayout = false)

    /** Remembers [databaseId] as the selection (FR-04) and shows it. */
    suspend fun selectDatabase(databaseId: String): DiagramSnapshot {
        state.selectedDatabaseId = databaseId
        return load(force = true, resetLayout = false)
    }

    /** Remembers the manually chosen folder (FR-03); null returns to automatic discovery only. */
    suspend fun chooseSchemaFolder(folderUrl: String?): DiagramSnapshot {
        state.schemaFolderUrl = folderUrl
        return load(force = true, resetLayout = false)
    }

    /** Discards stored positions of the current database and lays it out from scratch (AC-12.4). */
    suspend fun resetLayout(): DiagramSnapshot = load(force = true, resetLayout = true)

    /** Called when the user dragged a node; [positions] holds every node of the diagram on screen. */
    open fun positionsChanged(positions: Map<String, Point>) {
        updateDiagram { diagram ->
            state.setPositions(diagram.source.stateKey, positions)
            diagram.copy(positions = positions)
        }
    }

    open fun collapsedChanged(collapsed: Set<String>) {
        updateDiagram { diagram ->
            state.setCollapsed(diagram.source.stateKey, collapsed)
            diagram.copy(collapsed = collapsed)
        }
    }

    /** Mermaid text of the diagram on screen (FR-35), or null if there is none. */
    fun mermaidText(): String? =
        (snapshot.state as? DiagramState.Diagram)?.let { MermaidGenerator.generate(it.database) }

    /** The virtual file for a path reported in a state or error, if it is known and still valid. */
    fun findFile(path: String): VirtualFile? = knownFiles[path]?.takeIf { it.isValid }

    /** True if a change at [path] can affect what is shown (used to filter file events). */
    fun isWatchedPath(path: String): Boolean {
        manualFolderPath?.let { if (SchemaPathMatcher.isSameOrAncestor(it, path)) return true }
        // Deleting or renaming a parent directory of a database folder is relevant too.
        return snapshot.databases.any { SchemaPathMatcher.isSameOrAncestor(path, it.folderPath) }
    }

    private fun updateDiagram(transform: (DiagramState.Diagram) -> DiagramState.Diagram) {
        // Does not bump modelRevision: the canvas already shows this change.
        while (true) {
            val current = _snapshots.value
            val diagram = current.state as? DiagramState.Diagram ?: return
            if (_snapshots.compareAndSet(current, current.copy(state = transform(diagram)))) return
        }
    }

    private suspend fun load(force: Boolean, resetLayout: Boolean): DiagramSnapshot {
        activeLoads.incrementAndGet()
        _loading.value = true
        try {
            return mutex.withLock { doLoad(force, resetLayout) }
        } finally {
            if (activeLoads.decrementAndGet() == 0) _loading.value = false
        }
    }

    private suspend fun doLoad(force: Boolean, resetLayout: Boolean): DiagramSnapshot {
        val manualUrl = state.schemaFolderUrl
        val found = readAction { discovery.discover(manualUrl) }
        knownFiles = found.files
        manualFolderPath = if (manualUrl != null) VfsUtilCore.urlToPath(manualUrl) else null

        val databases = found.databases
        val emptyManualFolder = found.manualFolderPath.takeIf { !found.manualFolderHasSchemas }
        if (databases.isEmpty()) {
            loadedFingerprint = null
            val empty = if (emptyManualFolder != null) DiagramState.FolderWithoutSchemas(emptyManualFolder) else DiagramState.NoSchemas
            return publish(databases, null, empty, null)
        }

        val selected = databases.firstOrNull { it.id == state.selectedDatabaseId } ?: databases.first()
        val file = found.files[selected.latestFilePath]
        val previous = (snapshot.state as? DiagramState.Diagram)?.takeIf { it.source.id == selected.id }

        val fingerprint = file?.let {
            Fingerprint(databases.map { db -> db.id }, selected.latestFilePath, it.modificationStamp, it.timeStamp, it.length)
        }
        if (!force && !resetLayout && previous != null && fingerprint != null && fingerprint == loadedFingerprint) {
            // Same file, same content: keep the model; only the database list or labels may have changed.
            return publish(databases, selected, previous.copy(source = selected), emptyManualFolder)
        }

        val parsed = if (file == null) {
            ParseResult.Failure(unreadable(selected.latestFilePath, null))
        } else {
            readAndParse(file, selected)
        }
        loadedFingerprint = fingerprint

        val newState: DiagramState = when (parsed) {
            is ParseResult.Failure ->
                if (previous != null) previous.copy(source = selected, stale = true, error = parsed.error, preserveViewport = true)
                else DiagramState.Failed(selected, parsed.error)

            is ParseResult.Success -> {
                val laidOut = layOut(selected, parsed.database, previous, resetLayout)
                // The user may have dragged or collapsed something while this load was running.
                val latest = (snapshot.state as? DiagramState.Diagram)?.takeIf { it.source.id == selected.id }
                if (previous == null || latest == null || resetLayout) laidOut
                else laidOut.copy(
                    positions = if (latest.positions === previous.positions) laidOut.positions
                    else laidOut.positions + latest.positions.filterKeys { it in laidOut.positions },
                    collapsed = if (latest.collapsed === previous.collapsed) laidOut.collapsed
                    else latest.collapsed.filterTo(LinkedHashSet()) { it in laidOut.positions },
                )
            }
        }
        return publish(databases, selected, newState, emptyManualFolder)
    }

    private suspend fun readAndParse(file: VirtualFile, source: DiscoveredDatabase): ParseResult {
        val text = try {
            withContext(Dispatchers.IO) { VfsUtilCore.loadText(file) }
        } catch (e: IOException) {
            return ParseResult.Failure(unreadable(source.latestFilePath, e))
        }
        return withContext(Dispatchers.Default) {
            try {
                SchemaParser.parse(text, source.name, source.latestFilePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                // The parser contract is "never throws"; if it does anyway, still show a typed error (NFR-06).
                LOG.warn("Schema parser failed on ${source.latestFilePath}", e)
                ParseResult.Failure(SchemaError.MalformedJson(source.latestFilePath, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun unreadable(path: String, cause: IOException?): SchemaError =
        SchemaError.MalformedJson(path, RoomErBundle.message("error.file.unreadable", cause?.message ?: ""))

    private suspend fun layOut(
        source: DiscoveredDatabase,
        database: Database,
        previous: DiagramState.Diagram?,
        resetLayout: Boolean,
    ): DiagramState.Diagram = withContext(Dispatchers.Default) {
        if (resetLayout) state.clearPositions(source.stateKey)
        val entityNames = database.entities.mapTo(LinkedHashSet()) { it.name }
        val collapsed = (previous?.collapsed ?: state.collapsed(source.stateKey)).filterTo(LinkedHashSet()) { it in entityNames }
        // Reload: everything already on screen stays where it is. First show: only what the user saved.
        val fixed = when {
            resetLayout -> emptyMap()
            previous != null -> previous.positions
            else -> state.positions(source.stateKey)
        }.filterKeys { it in entityNames }

        val sizes = measurer.measure(database, collapsed)
        val nodes = database.entities.map { LayoutNode(it.name, sizes[it.name] ?: DEFAULT_NODE_SIZE) }
        val edges = database.tables.flatMap { table ->
            table.foreignKeys
                .filter { it.referencedTable in entityNames }
                .map { LayoutEdge(sourceId = table.name, targetId = it.referencedTable) }
        }
        val request = LayoutRequest(nodes, edges, fixed)
        val computed = try {
            layoutEngine.layout(request).positions
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Layout engine failed, falling back to a grid", e)
            emptyMap()
        }
        val positions = completePositions(nodes, fixed, computed)
        DiagramState.Diagram(
            source = source,
            database = database,
            positions = positions,
            collapsed = collapsed,
            preserveViewport = previous != null && !resetLayout,
            modelRevision = ++revision,
        )
    }

    private fun publish(
        databases: List<DiscoveredDatabase>,
        selected: DiscoveredDatabase?,
        newState: DiagramState,
        emptyManualFolder: String?,
    ): DiagramSnapshot {
        val result = DiagramSnapshot(databases, selected, newState, emptyManualFolder, ++revision)
        _snapshots.value = result
        return result
    }

    companion object {
        private val LOG = logger<DiagramController>()
        private val DEFAULT_NODE_SIZE = Size(180.0, 120.0)
        private const val GRID_GAP = 48.0

        /**
         * Guarantees a position for every node: fixed positions win, then the engine's result; anything
         * still missing (engine failure or an incomplete result) is put on a grid below the placed nodes.
         */
        internal fun completePositions(
            nodes: List<LayoutNode>,
            fixed: Map<String, Point>,
            computed: Map<String, Point>,
        ): Map<String, Point> {
            val result = LinkedHashMap<String, Point>()
            val missing = ArrayList<LayoutNode>()
            for (node in nodes) {
                val point = fixed[node.id] ?: computed[node.id]?.takeIf { it.x.isFinite() && it.y.isFinite() }
                if (point != null) result[node.id] = point else missing += node
            }
            if (missing.isEmpty()) return result

            val sizes = nodes.associate { it.id to it.size }
            val top = result.maxOfOrNull { (id, p) -> p.y + (sizes[id]?.height ?: 0.0) }?.plus(GRID_GAP) ?: 0.0
            val left = result.minOfOrNull { it.value.x } ?: 0.0
            val columns = ceil(sqrt(missing.size.toDouble())).toInt().coerceAtLeast(1)
            val cellWidth = missing.maxOf { it.size.width } + GRID_GAP
            var y = top
            missing.chunked(columns).forEach { row ->
                row.forEachIndexed { index, node -> result[node.id] = Point(left + index * cellWidth, y) }
                y += row.maxOf { it.size.height } + GRID_GAP
            }
            return result
        }
    }
}
