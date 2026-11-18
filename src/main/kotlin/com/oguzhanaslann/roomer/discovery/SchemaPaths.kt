package com.oguzhanaslann.roomer.discovery

/** One file matching `<folder>/<n>.json`, where `<folder>` is the directory of one database. */
data class SchemaFileMatch(
    /** Directory holding the version files of one database (`…/schemas/<databaseName>`). */
    val folderPath: String,
    val databaseName: String,
    val version: Int,
    val filePath: String,
    /** Build variant for `schemas/<variant>/<databaseName>/<n>.json` (Room's per-variant schemaDirectory), else null. */
    val variant: String? = null,
)

/** One database found on disk: a schema folder plus the version files inside it (spec FR-02). */
data class DiscoveredDatabase(
    /** Unique id: the schema folder path. Two modules may export a database with the same name. */
    val id: String,
    /** Folder name, normally the fully qualified `@Database` class name. */
    val name: String,
    val folderPath: String,
    /** Highest version number found; this is the file that is rendered. */
    val latestVersion: Int,
    val latestFilePath: String,
    /** All versions found, ascending. */
    val versions: List<Int>,
    /** Text for the dropdown: the simple class name, disambiguated when several databases share it. */
    val label: String,
    /** Key for persisted layout state: the database name, disambiguated only when the name is not unique. */
    val stateKey: String,
) {
    val simpleName: String get() = name.substringAfterLast('.')
}

/**
 * Pure path logic for schema discovery (spec FR-01, FR-02; AC-01.1–01.3). No IDE dependencies.
 * All paths use `/` separators.
 */
object SchemaPathMatcher {
    const val SCHEMAS_DIR: String = "schemas"
    private const val EXTENSION = ".json"

    /** `"12.json"` → 12; anything else (`notanumber.json`, `1.json.bak`, out-of-range numbers) → null. */
    fun versionOf(fileName: String): Int? {
        if (!fileName.endsWith(EXTENSION, ignoreCase = true)) return null
        val stem = fileName.dropLast(EXTENSION.length)
        if (stem.isEmpty() || !stem.all { it in '0'..'9' }) return null
        return stem.toIntOrNull()
    }

    /**
     * Matches `schemas/<databaseName>/<n>.json` at any depth, and the per-variant layout
     * `schemas/<variant>/<databaseName>/<n>.json` written by `schemaDirectory("<variant>", …)` in the Room 2.6+
     * and Room 3 Gradle plugins. The variant form needs a qualified database name (it contains a dot, as
     * Room's `<package>.<DatabaseClass>` folders do) and a variant without one, so that an arbitrary
     * nested folder under `schemas` is not mistaken for a database.
     */
    fun match(path: String): SchemaFileMatch? {
        val segments = segments(path)
        if (segments.size >= 3 && segments[segments.size - 3] == SCHEMAS_DIR) return matchDatabaseFile(path)
        if (segments.size >= 4 && segments[segments.size - 4] == SCHEMAS_DIR) {
            val variant = segments[segments.size - 3]
            val databaseName = segments[segments.size - 2]
            if ('.' in variant || '.' !in databaseName) return null
            return matchDatabaseFile(path)?.copy(variant = variant)
        }
        return null
    }

    /**
     * Matching for a folder the user picked by hand (spec FR-03). [folder] may be a database folder
     * (`<n>.json` directly inside), a schemas folder (`<databaseName>/<n>.json` inside) — in both cases
     * regardless of what the folders are called — or any ancestor, below which the normal
     * `schemas/<databaseName>/<n>.json` rule applies.
     */
    fun matchInFolder(folder: String, path: String): SchemaFileMatch? {
        val base = normalize(folder).trimEnd('/')
        val normalized = normalize(path)
        if (!normalized.startsWith("$base/")) return null
        val depth = segments(normalized.substring(base.length + 1)).size
        return if (depth <= 2) matchDatabaseFile(normalized) else match(normalized)
    }

    private fun matchDatabaseFile(path: String): SchemaFileMatch? {
        val normalized = normalize(path)
        val segments = segments(normalized)
        if (segments.size < 2) return null
        val version = versionOf(segments.last()) ?: return null
        val databaseName = segments[segments.size - 2]
        return SchemaFileMatch(
            folderPath = normalized.substringBeforeLast('/'),
            databaseName = databaseName,
            version = version,
            filePath = normalized,
        )
    }

    /**
     * Groups files by schema folder and picks the highest version of each. The result is sorted by label,
     * then folder path, so it is stable. [basePath] (the project directory) is only used to build short
     * labels when several databases share a name.
     */
    fun group(matches: Collection<SchemaFileMatch>, basePath: String? = null): List<DiscoveredDatabase> {
        data class Raw(val folder: String, val name: String, val variant: String?, val files: List<SchemaFileMatch>)

        val raw = matches.groupBy { it.folderPath }
            .map { (folder, files) ->
                val first = files.first()
                Raw(folder, first.databaseName, first.variant, files.sortedBy { it.version })
            }
        val bySimpleName = raw.groupBy { it.name.substringAfterLast('.') }
        val byName = raw.groupBy { it.name }

        val base = basePath?.let { normalize(it).trimEnd('/') }
        fun relative(path: String): String =
            if (base != null && path.startsWith("$base/")) path.substring(base.length + 1) else path

        /**
         * Where the database lives: the directory containing `schemas`, relative to the project, followed by
         * the build variant for per-variant folders.
         */
        fun location(db: Raw): String {
            var owner = db.folder.substringBeforeLast('/', "").substringBeforeLast('/', "")
            if (db.variant != null) owner = owner.substringBeforeLast('/', "")
            val module = when {
                base != null && owner == base -> "."
                owner.isEmpty() -> "/"
                else -> relative(owner)
            }
            return if (db.variant == null) module else if (module == ".") db.variant else "$module, ${db.variant}"
        }

        return raw.map { db ->
            val simpleName = db.name.substringAfterLast('.')
            val sameSimple = bySimpleName.getValue(simpleName)
            val sameName = byName.getValue(db.name)
            val label = when {
                sameSimple.size == 1 -> simpleName
                sameName.size == 1 -> db.name
                sameName.map { location(it) }.distinct().size == sameName.size ->
                    "$simpleName (${location(db)})"
                else -> "$simpleName (${relative(db.folder)})"
            }
            val stateKey = when {
                sameName.size == 1 -> db.name
                sameName.map { location(it) }.distinct().size == sameName.size ->
                    "${db.name}@${location(db)}"
                else -> "${db.name}@${relative(db.folder)}"
            }
            val latest = db.files.last()
            DiscoveredDatabase(
                id = db.folder,
                name = db.name,
                folderPath = db.folder,
                latestVersion = latest.version,
                latestFilePath = latest.filePath,
                versions = db.files.map { it.version }.distinct(),
                label = label,
                stateKey = stateKey,
            )
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.folderPath }))
    }

    /** True if [path] is a `schemas` directory or lies inside one — a cheap pre-filter for file events. */
    fun isUnderSchemasDir(path: String): Boolean {
        val normalized = normalize(path)
        return normalized.endsWith("/$SCHEMAS_DIR") || normalized.contains("/$SCHEMAS_DIR/")
    }

    /** True if [ancestor] is [path] itself or one of its parent directories. */
    fun isSameOrAncestor(ancestor: String, path: String): Boolean {
        val a = normalize(ancestor).trimEnd('/')
        val p = normalize(path)
        return p == a || p.startsWith("$a/")
    }

    private fun normalize(path: String): String = path.replace('\\', '/')
    private fun segments(path: String): List<String> = normalize(path).split('/').filter { it.isNotEmpty() }
}
