package com.oguzhanaslann.roomer.toolwindow

import com.oguzhanaslann.roomer.model.Database
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Writing the Mermaid export to disk — the plugin's only write to user files (spec NFR-04, FR-35). */
object MermaidExport {
    const val EXTENSION: String = "mmd"

    /** `<SimpleDbName>.mmd`, with characters that are not valid in file names replaced. */
    fun defaultFileName(database: Database): String {
        val stem = database.simpleName.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
        return "${stem.ifEmpty { "schema" }}.$EXTENSION"
    }

    /** Writes [text] as UTF-8 to [target], replacing an existing file. Touches nothing else. */
    @Throws(IOException::class)
    fun write(target: Path, text: String) {
        target.parent?.let(Files::createDirectories)
        Files.writeString(target, text)
    }
}
