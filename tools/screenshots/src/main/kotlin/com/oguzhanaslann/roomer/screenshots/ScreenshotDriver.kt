package com.oguzhanaslann.roomer.screenshots

import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.SearchTextField
import com.intellij.util.ui.UIUtil
import com.oguzhanaslann.roomer.export.MermaidGenerator
import com.oguzhanaslann.roomer.render.DiagramCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JFrame

/**
 * Captures RoomEr's Marketplace screenshots. Runs only when the sandbox IDE is started with
 * `-Droomer.screenshots.out=<dir>` (the root project's `runIdeScreenshots` task), then exits the IDE.
 *
 * The frame is painted into an image at 2x scale, so no screen-recording permission is needed and the result
 * does not depend on the monitor. Each shot is a real IDE state: theme, tool window, canvas selection, search.
 */
class ScreenshotDriver : ProjectActivity {

    override suspend fun execute(project: Project) {
        val out = System.getProperty("roomer.screenshots.out") ?: return
        val dir = Path.of(out)
        Files.createDirectories(dir)
        val log = StringBuilder()
        fun note(message: String) {
            log.appendLine(message)
            Files.writeString(dir.resolve("driver.log"), log)
        }
        try {
            smartReadAction(project) { }
            delay(4_000)
            Shots(project, dir, ::note).run()
            note("done")
        } catch (t: Throwable) {
            note("FAILED: ${t.stackTraceToString()}")
        } finally {
            withContext(Dispatchers.EDT) { ApplicationManager.getApplication().exit(true, true, false) }
        }
    }
}

private class Shots(private val project: Project, private val dir: Path, private val note: (String) -> Unit) {

    private val toolWindows get() = ToolWindowManager.getInstance(project)

    suspend fun run() {
        val toolWindow = withContext(Dispatchers.EDT) {
            // Code vision ("N usages") leaves empty placeholder lines in a file outside a source root.
            CodeVisionSettings.getInstance().codeVisionEnabled = false
            prepareFrame()
            hideOtherToolWindows()
            val tw = toolWindows.getToolWindow(TOOL_WINDOW) ?: error("tool window '$TOOL_WINDOW' not registered")
            tw.show()
            tw
        }
        val canvas = waitForDiagram(toolWindow)
        note("diagram loaded: ${canvas.database?.tables?.size} tables, ${canvas.database?.views?.size} views")
        note("themes: " + LafManager.getInstance().installedThemes.joinToString { "${it.name}(dark=${it.isDark})" })

        // 1. Overview next to the entity source, light theme.
        withContext(Dispatchers.EDT) {
            setTheme(dark = false)
            openInEditor("app/src/main/java/com/example/music/data/Entities.kt")
            setWidth(toolWindow, 0.62)
        }
        settle()
        withContext(Dispatchers.EDT) {
            canvas.legendVisible = false
            canvas.select(null)
            canvas.fitToWindow()
        }
        capture("01-overview-light")

        // 2. Focus mode on one table, dark theme, diagram maximised.
        withContext(Dispatchers.EDT) {
            setTheme(dark = true)
            showBlankEditor()
            toolWindows.setMaximized(toolWindow, true)
        }
        settle()
        withContext(Dispatchers.EDT) {
            canvas.legendVisible = false
            canvas.fitToWindow()
            canvas.select("tracks", center = false)
            canvas.focusMode = true
        }
        capture("02-focus-mode-dark")

        // 3. Search, light theme.
        withContext(Dispatchers.EDT) {
            canvas.focusMode = false
            canvas.select(null)
            setTheme(dark = false)
        }
        settle()
        withContext(Dispatchers.EDT) {
            canvas.fitToWindow()
            val search = UIUtil.findComponentOfType(toolWindow.component, SearchTextField::class.java)
                ?: error("search field not found")
            search.text = "play"
        }
        settle()
        withContext(Dispatchers.EDT) {
            canvas.select("playlists", center = false)
        }
        capture("03-search-light")

        // 4. Close-up: column badges and crow's-foot ends at 100 %, dark theme.
        withContext(Dispatchers.EDT) {
            UIUtil.findComponentOfType(toolWindow.component, SearchTextField::class.java)?.text = ""
            setTheme(dark = true)
        }
        settle()
        withContext(Dispatchers.EDT) {
            canvas.fitToWindow()
            canvas.select("albums", center = true)
            canvas.zoomIn(); canvas.zoomIn(); canvas.zoomIn()
            canvas.select("albums", center = true)
        }
        capture("04-close-up-dark")

        // 5. Mermaid export next to the diagram, light theme.
        val mermaid = withContext(Dispatchers.EDT) { MermaidGenerator.generate(canvas.database!!) }
        val mmd = Path.of(project.basePath!!).resolve("music-database.mmd")
        Files.writeString(mmd, mermaid)
        withContext(Dispatchers.EDT) {
            toolWindows.setMaximized(toolWindow, false)
            setLineNumbers(true)
            setTheme(dark = false)
            canvas.select(null)
            openInEditor("music-database.mmd")
            setWidth(toolWindow, 0.6)
        }
        settle()
        withContext(Dispatchers.EDT) {
            canvas.legendVisible = true
            canvas.fitToWindow()
        }
        capture("05-mermaid-export-light")
    }

    private fun prepareFrame() {
        val frame = WindowManager.getInstance().getFrame(project) ?: error("no IDE frame")
        frame.extendedState = Frame.NORMAL
        frame.setBounds(40, 40, FRAME_WIDTH, FRAME_HEIGHT)
        frame.validate()
    }

    private fun hideOtherToolWindows() {
        for (id in toolWindows.toolWindowIds) {
            if (id != TOOL_WINDOW) toolWindows.getToolWindow(id)?.hide()
        }
    }

    private suspend fun waitForDiagram(toolWindow: ToolWindow): DiagramCanvas {
        repeat(120) {
            val canvas = withContext(Dispatchers.EDT) {
                UIUtil.findComponentOfType(toolWindow.component, DiagramCanvas::class.java)
            }
            if (canvas?.database != null) {
                delay(1_500)
                return canvas
            }
            delay(500)
        }
        error("the diagram did not load")
    }

    /**
     * A maximised tool window still leaves a narrow strip of the editor area visible. Keep a blank file open
     * there without line numbers, so the strip is plain editor background instead of the empty-editor hints.
     */
    private fun showBlankEditor() {
        Files.writeString(Path.of(project.basePath!!).resolve(BLANK_FILE), "")
        setLineNumbers(false)
        openInEditor(BLANK_FILE)
    }

    private fun setLineNumbers(shown: Boolean) {
        EditorSettingsExternalizable.getInstance().isLineNumbersShown = shown
        EditorFactory.getInstance().refreshAllEditors()
    }

    private fun closeEditors() {
        val editors = FileEditorManager.getInstance(project)
        editors.openFiles.forEach { editors.closeFile(it) }
    }

    private fun openInEditor(relativePath: String) {
        closeEditors()
        val editors = FileEditorManager.getInstance(project)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(project.basePath!!).resolve(relativePath))
            ?: error("missing $relativePath")
        editors.openFile(file, true)
    }

    private fun setWidth(toolWindow: ToolWindow, fraction: Double) {
        val frameWidth = WindowManager.getInstance().getFrame(project)!!.width
        val target = (frameWidth * fraction).toInt()
        (toolWindow as ToolWindowEx).stretchWidth(target - toolWindow.component.width)
    }

    private fun setTheme(dark: Boolean) {
        val manager = LafManager.getInstance()
        val themes = manager.installedThemes.toList()
        val preferred = if (dark) listOf("Islands Dark", "Dark") else listOf("Islands Light", "Light")
        val theme: UIThemeLookAndFeelInfo = preferred.firstNotNullOfOrNull { name -> themes.firstOrNull { it.name == name } }
            ?: themes.first { it.isDark == dark }
        if (manager.currentUIThemeLookAndFeel?.id == theme.id) return
        manager.setCurrentLookAndFeel(theme, false)
        manager.updateUI()
        note("theme -> ${theme.name}")
    }

    private suspend fun settle() = delay(1_500)

    private suspend fun capture(name: String) {
        settle()
        withContext(Dispatchers.EDT) {
            // Balloons such as "Project in Protected Folder" (the sandbox project lives under ~/Desktop) are noise.
            NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(Notification::class.java, project).forEach { it.expire() }
        }
        delay(500)
        withContext(Dispatchers.EDT) {
            val frame = WindowManager.getInstance().getFrame(project) as JFrame
            val root = frame.rootPane
            val image = BufferedImage(root.width * SCALE, root.height * SCALE, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try {
                g.scale(SCALE.toDouble(), SCALE.toDouble())
                root.paint(g)
            } finally {
                g.dispose()
            }
            ImageIO.write(image, "png", dir.resolve("$name.png").toFile())
            note("saved $name.png (${image.width}x${image.height})")
        }
    }

    private companion object {
        const val TOOL_WINDOW = "Room ER"
        const val BLANK_FILE = "notes.txt"
        const val FRAME_WIDTH = 1440
        const val FRAME_HEIGHT = 900
        const val SCALE = 2
    }
}
