package com.oguzhanaslann.roomer.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the virtual file system for changes that may affect the discovered schemas (spec FR-06) and
 * calls [onChange] once per burst of events. Saving a schema file edited in the IDE also arrives here,
 * as a content-change event. Stops when [parent] is disposed or [scope] is cancelled.
 *
 * @param isRelevant extra filter for paths the discovery already knows about (current database folders,
 *   the manually chosen folder); paths under a `schemas` directory are always relevant
 */
class SchemaWatcher(
    project: Project,
    parent: Disposable,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val isRelevant: (path: String) -> Boolean,
    private val onChange: suspend () -> Unit,
) {
    private val lock = Any()
    private var pending: Job? = null
    private val basePath: String? = project.basePath?.trimEnd('/')

    init {
        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::affectsSchemas)) schedule()
                }
            })
        // Content roots appear after a Gradle sync; the set of scanned directories changes with them.
        project.messageBus.connect(parent).subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = schedule()
        })
    }

    /** Requests a debounced [onChange]; a request already waiting is replaced. */
    fun schedule() {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(debounceMillis)
                onChange()
            }
        }
    }

    private fun affectsSchemas(event: VFileEvent): Boolean {
        val paths = when (event) {
            is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
            is VFilePropertyChangeEvent -> listOf(event.oldPath, event.newPath)
            is VFileCopyEvent -> listOf(event.path)
            else -> listOf(event.path)
        }
        if (paths.any { SchemaPathMatcher.isUnderSchemasDir(it) || isRelevant(it) }) return true
        // A whole directory tree that appears at once (checkout, unzip) is reported as one event for its root.
        val createdDirectory = (event is VFileCreateEvent && event.isDirectory) || event is VFileMoveEvent ||
            (event is VFileCopyEvent && event.file.isDirectory)
        return createdDirectory && SchemaDiscovery.isScannableDirectory(relativeToProject(paths.last()))
    }

    private fun relativeToProject(path: String): String {
        val base = basePath ?: return path
        return if (path.startsWith("$base/")) path.substring(base.length + 1) else path
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS: Long = 300
    }
}
