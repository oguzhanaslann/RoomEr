package com.oguzhanaslann.roomer.discovery

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor

/** What [SchemaDiscovery.discover] found. */
class DiscoveryResult(
    val databases: List<DiscoveredDatabase>,
    /** Latest version file of every database, keyed by [DiscoveredDatabase.latestFilePath]. */
    val files: Map<String, VirtualFile>,
    /** Presentable path of the folder the user chose by hand, if one is configured. */
    val manualFolderPath: String?,
    /** False if a manual folder is configured but is missing or holds no schema files (spec FR-42). */
    val manualFolderHasSchemas: Boolean,
)

/**
 * Finds Room schema files (spec FR-01–FR-03). Uses plain VFS traversal of the content roots rather than
 * an index, so it also works while the IDE is indexing. Uses the VFS and project model only — no UI.
 */
class SchemaDiscovery(private val project: Project) {

    /**
     * Must be called inside a read action, off the EDT. Cancellable through the current progress.
     * @param manualFolderUrl VFS url of the folder chosen with "Choose schema folder…", or null
     */
    fun discover(manualFolderUrl: String?): DiscoveryResult {
        val candidates = LinkedHashMap<String, VirtualFile>()
        val matches = LinkedHashMap<String, SchemaFileMatch>()

        scanContentRoots { file ->
            SchemaPathMatcher.match(file.path)?.let {
                matches.putIfAbsent(it.filePath, it)
                candidates.putIfAbsent(it.filePath, file)
            }
        }

        var manualPath: String? = null
        var manualHasSchemas = true
        if (!manualFolderUrl.isNullOrBlank()) {
            val folder = VirtualFileManager.getInstance().findFileByUrl(manualFolderUrl)
            manualPath = folder?.presentableUrl ?: VfsUtilCore.urlToPath(manualFolderUrl)
            manualHasSchemas = false
            if (folder != null && folder.isValid && folder.isDirectory) {
                scanFolder(folder) { file ->
                    SchemaPathMatcher.matchInFolder(folder.path, file.path)?.let {
                        manualHasSchemas = true
                        matches.putIfAbsent(it.filePath, it)
                        candidates.putIfAbsent(it.filePath, file)
                    }
                }
            }
        }

        val databases = SchemaPathMatcher.group(matches.values, project.guessProjectDir()?.path)
        val files = databases.mapNotNull { db -> candidates[db.latestFilePath]?.let { db.latestFilePath to it } }.toMap()
        return DiscoveryResult(databases, files, manualPath, manualHasSchemas)
    }

    private fun scanContentRoots(onVersionFile: (VirtualFile) -> Unit) {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val roots = ProjectRootManager.getInstance(project).contentRoots.filter { it.isValid }
        // Gradle projects have nested content roots (project dir + every module dir): visit each tree once.
        val topRoots = roots.filter { root -> roots.none { it != root && VfsUtilCore.isAncestor(it, root, true) } }
        for (root in topRoots) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>(NO_FOLLOW_SYMLINKS) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory) {
                        if (SchemaPathMatcher.versionOf(file.name) != null) onVersionFile(file)
                        return false
                    }
                    ProgressManager.checkCanceled()
                    if (file == root) return true
                    if (file.name in SKIPPED_DIRECTORIES || file.name.startsWith(".")) return false
                    return !fileIndex.isExcluded(file) && !fileIndex.isUnderIgnored(file)
                }
            })
        }
    }

    private fun scanFolder(folder: VirtualFile, onVersionFile: (VirtualFile) -> Unit) {
        VfsUtilCore.visitChildrenRecursively(
            folder,
            object : VirtualFileVisitor<Unit>(NO_FOLLOW_SYMLINKS, limit(MANUAL_FOLDER_DEPTH)) {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory) {
                        if (SchemaPathMatcher.versionOf(file.name) != null) onVersionFile(file)
                        return false
                    }
                    ProgressManager.checkCanceled()
                    return file == folder || !file.name.startsWith(".")
                }
            },
        )
    }

    companion object {
        /**
         * Build output is skipped even before the build system has marked it excluded: the Android Gradle
         * plugin copies schema files into `build/` as test assets, which would otherwise show up as duplicates.
         */
        val SKIPPED_DIRECTORIES: Set<String> = setOf("build", "node_modules", "out")
        private const val MANUAL_FOLDER_DEPTH = 12

        /** True if a directory created at [path] could contain schema files worth a rescan. */
        fun isScannableDirectory(path: String): Boolean =
            path.split('/').none { it in SKIPPED_DIRECTORIES || (it.startsWith(".") && it.length > 1) }
    }
}
