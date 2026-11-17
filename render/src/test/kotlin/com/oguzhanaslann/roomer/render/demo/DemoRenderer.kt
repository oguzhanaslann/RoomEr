package com.oguzhanaslann.roomer.render.demo

import com.oguzhanaslann.roomer.render.DiagramCanvas
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource
import javax.swing.plaf.FontUIResource

/** Headless renderer: paints the demo canvas to PNG files (`./gradlew :render:renderDemo`). */
object DemoRenderer {

    /** UIManager values similar to what the IDE's light / dark / high-contrast look-and-feels provide. */
    enum class DemoTheme(val values: Map<String, Int>) {
        LIGHT(mapOf(
            "Panel.background" to 0xF7F8FA, "TextField.background" to 0xFFFFFF, "Label.foreground" to 0x000000,
            "Label.disabledForeground" to 0x8C8C8C, "Component.borderColor" to 0xC9CCD6, "Component.focusColor" to 0x3574F0,
            "List.selectionBackground" to 0x3574F0, "Label.errorForeground" to 0xDB3B4B,
        )),
        DARK(mapOf(
            "Panel.background" to 0x2B2D30, "TextField.background" to 0x1E1F22, "Label.foreground" to 0xDFE1E5,
            "Label.disabledForeground" to 0x6F737A, "Component.borderColor" to 0x4E5157, "Component.focusColor" to 0x3574F0,
            "List.selectionBackground" to 0x2E436E, "Label.errorForeground" to 0xF75464,
        )),
        HIGH_CONTRAST(mapOf(
            "Panel.background" to 0x000000, "TextField.background" to 0x000000, "Label.foreground" to 0xFFFFFF,
            "Label.disabledForeground" to 0xB0B0B0, "Component.borderColor" to 0xB3B3B3, "Component.focusColor" to 0x1AEBFF,
            "List.selectionBackground" to 0x3333FF, "Label.errorForeground" to 0xFF5A5A,
        ));

        fun install() {
            for ((key, rgb) in values) UIManager.put(key, ColorUIResource(Color(rgb)))
            UIManager.put("Label.font", FontUIResource(Font(Font.SANS_SERIF, Font.PLAIN, 13)))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "build/demo")
        dir.mkdirs()
        val written = ArrayList<File>()
        for (theme in DemoTheme.entries) {
            theme.install()
            val prefix = theme.name.lowercase().replace('_', '-')

            written += render(File(dir, "$prefix-overview.png"), 1400, 760) { }
            written += render(File(dir, "$prefix-keyboard-focus.png"), 900, 520) { c ->
                c.focusListeners.forEach { it.focusGained(java.awt.event.FocusEvent(c, java.awt.event.FocusEvent.FOCUS_GAINED)) }
                c.select("posts", null, center = false)
            }
            if (theme == DemoTheme.HIGH_CONTRAST) continue

            written += render(File(dir, "$prefix-selection.png"), 1400, 760) { c ->
                c.setCollapsed("comments", true)
                c.setCollapsed("user_post_counts", true)
                c.select("users", "email", center = false)
            }
            written += render(File(dir, "$prefix-focus.png"), 1400, 760) { c ->
                c.select("post_tags", null, center = false)
                c.focusMode = true
            }
            written += render(File(dir, "$prefix-hover-stale.png"), 1400, 760) { c ->
                c.hoveredEdgeId = "posts#0"
                c.stale = true
            }
            written += render(File(dir, "$prefix-zoom.png"), 900, 620) { c ->
                c.select("users", null, center = true)
                c.zoomIn(); c.zoomIn(); c.zoomIn()
            }
            written += render(File(dir, "$prefix-zoom-interactive.png"), 900, 620) { c ->
                c.select("users", null, center = true)
                c.zoomIn(); c.zoomIn(); c.zoomIn()
                c.interactiveForTest = true
            }
            written += render(File(dir, "$prefix-collapsed.png"), 1400, 620) { c ->
                c.collapseAll()
                c.fitToWindow()
                c.select("posts", null, center = false)
            }
        }
        DemoTheme.LIGHT.install()
        val big = DemoData.large()
        val canvas = DiagramCanvas()
        canvas.setSize(1600, 1000)
        canvas.setDiagram(big, DemoData.gridPositions(big))
        written += paint(canvas, File(dir, "light-large-fit.png"))
        canvas.select("table_42", null, center = true)
        repeat(6) { canvas.zoomIn() }
        written += paint(canvas, File(dir, "light-large-zoomed.png"))
        // What a frame looks like while the user pans/zooms (cheap hairline edges below 60 % zoom).
        canvas.select(null)
        canvas.fitToWindow()
        canvas.interactiveForTest = true
        written += paint(canvas, File(dir, "light-large-fit-interactive.png"))
        canvas.interactiveForTest = false
        written.forEach { println("wrote ${it.absolutePath}") }
    }

    private fun render(file: File, width: Int, height: Int, setup: (DiagramCanvas) -> Unit): File {
        val canvas = DiagramCanvas()
        canvas.setSize(width, height)
        canvas.setDiagram(DemoData.database(), DemoData.positions())
        setup(canvas)
        return paint(canvas, file)
    }

    private fun paint(canvas: DiagramCanvas, file: File): File {
        val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        canvas.paint(g)
        g.dispose()
        ImageIO.write(image, "png", file)
        return file
    }
}
