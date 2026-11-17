package com.oguzhanaslann.roomer.render.demo

import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.render.DiagramCanvas
import com.oguzhanaslann.roomer.render.DiagramCanvasListener
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JToolBar
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/** Opens the demo diagram in a window: `./gradlew :render:runDemo` (pass `large` to see 100 tables). */
object DemoApp {
    @JvmStatic
    fun main(args: Array<String>) {
        SwingUtilities.invokeLater {
            val large = args.firstOrNull() == "large"
            val db = if (large) DemoData.large() else DemoData.database()
            val positions = if (large) DemoData.gridPositions(db) else DemoData.positions()
            val canvas = DiagramCanvas()
            val status = JLabel(" ")
            canvas.addCanvasListener(object : DiagramCanvasListener {
                override fun goToSourceRequested(entityName: String) { status.text = "Go to source: $entityName" }
                override fun positionsChanged(positions: Map<String, Point>) { status.text = "Positions changed" }
                override fun collapsedChanged(collapsed: Set<String>) { status.text = "Collapsed: $collapsed" }
                override fun selectionChanged(entityName: String?) { status.text = "Selected: $entityName" }
                override fun focusModeChanged(enabled: Boolean) { status.text = "Focus mode: $enabled" }
                override fun searchRequested() { status.text = "Search requested" }
                override fun escapePressed() { status.text = "Escape" }
            })
            canvas.setDiagram(db, positions)
            val toolbar = JToolBar().apply {
                add(JButton("Fit").apply { addActionListener { canvas.fitToWindow() } })
                add(JButton("+").apply { addActionListener { canvas.zoomIn() } })
                add(JButton("-").apply { addActionListener { canvas.zoomOut() } })
                add(JButton("Collapse all").apply { addActionListener { canvas.collapseAll() } })
                add(JButton("Expand all").apply { addActionListener { canvas.expandAll() } })
                add(JButton("Focus").apply { addActionListener { canvas.focusMode = !canvas.focusMode } })
                add(JButton("Stale").apply { addActionListener { canvas.stale = !canvas.stale } })
                add(JButton("Legend").apply { addActionListener { canvas.legendVisible = !canvas.legendVisible } })
            }
            JFrame("RoomEr canvas demo").apply {
                defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
                layout = BorderLayout()
                add(toolbar, BorderLayout.NORTH)
                add(canvas, BorderLayout.CENTER)
                add(status, BorderLayout.SOUTH)
                setSize(1400, 860)
                setLocationRelativeTo(null)
                isVisible = true
            }
            canvas.requestFocusInWindow()
        }
    }
}
