package com.oguzhanaslann.roomer.toolwindow

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.timeoutRunBlocking
import com.oguzhanaslann.roomer.RoomErPlatformTestCase
import com.oguzhanaslann.roomer.TestSchemas
import com.oguzhanaslann.roomer.export.MermaidGenerator
import com.oguzhanaslann.roomer.layout.Point
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

/** AC-20.1, AC-23.1: the .mmd save is the only write to the project directory. */
class MermaidExportTest : RoomErPlatformTestCase() {

    private fun contents(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.asSequence().filter { Files.isRegularFile(it) }
            .associate { it.relativeTo(root).toString().replace('\\', '/') to it.readText() }
    }

    fun testSaveWritesExactlyTheGeneratedTextAndNothingElse() {
        val root = FileUtil.createTempDirectory("roomer-project", null).toPath()
        val dbDir = Files.createDirectories(root.resolve("app/schemas/com.acme.AppDb"))
        Files.writeString(dbDir.resolve("1.json"), TestSchemas.schema(1, "users"))
        Files.writeString(dbDir.resolve("2.json"), TestSchemas.schema(2, "users", "orders"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "// untouched")
        val folder = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        val before = contents(root)

        // Every operation of the pipeline, on files in a real directory.
        val controller = newController()
        val diagram = timeoutRunBlocking { controller.chooseSchemaFolder(folder.url) }.state.asDiagram()
        controller.positionsChanged(diagram.positions + ("users" to Point(3.0, 4.0)))
        controller.collapsedChanged(setOf("orders"))
        timeoutRunBlocking {
            controller.reload(force = true)
            controller.resetLayout()
            controller.selectDatabase(diagram.source.id)
        }
        val text = controller.mermaidText()!!
        assertEquals("nothing is written before the user saves", before, contents(root))

        assertEquals("AppDb.mmd", MermaidExport.defaultFileName(diagram.database))
        val target = root.resolve("docs/AppDb.mmd")
        MermaidExport.write(target, text)

        val after = contents(root)
        assertEquals(MermaidGenerator.generate(diagram.database), target.readText())
        assertEquals(before + ("docs/AppDb.mmd" to text), after)
    }

    fun testNoDiagramMeansNoMermaidText() {
        val controller = newController()
        timeoutRunBlocking { controller.reload() }
        assertNull(controller.mermaidText())
    }
}
