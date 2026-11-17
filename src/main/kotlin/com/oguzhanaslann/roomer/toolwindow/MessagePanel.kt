package com.oguzhanaslann.roomer.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.oguzhanaslann.roomer.RoomErBundle
import com.oguzhanaslann.roomer.model.SchemaError
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/** Empty and error states of the tool window (spec FR-40–42, AC-06.x). EDT only. */
internal class MessagePanel {
    private val content = WidthTrackingPanel()
    val component: JComponent = ScrollPaneFactory.createScrollPane(
        content,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply { border = JBUI.Borders.empty() }

    /** FR-40: nothing found anywhere. */
    fun showNoSchemas(onChooseFolder: () -> Unit) = rebuild {
        title(RoomErBundle.message("state.empty.title"))
        text(RoomErBundle.message("state.empty.body"))
        code(RoomErBundle.message("state.empty.snippet"))
        text(RoomErBundle.message("state.empty.after"))
        // The only external link of the plugin; the browser opens only on click (NFR-03, AC-23.3).
        link(RoomErBundle.message("link.room.docs"), external = true) { BrowserUtil.browse(ROOM_DOCS_URL) }
        link(RoomErBundle.message("link.choose.folder"), onClick = onChooseFolder)
    }

    /** FR-42: the chosen folder has no valid schema files. */
    fun showEmptyFolder(folderPath: String, onChooseFolder: () -> Unit, onAutomatic: () -> Unit) = rebuild {
        title(RoomErBundle.message("state.folder.empty.title", folderPath))
        text(RoomErBundle.message("state.folder.empty.body"))
        link(RoomErBundle.message("link.choose.folder"), onClick = onChooseFolder)
        link(RoomErBundle.message("link.automatic.discovery"), onClick = onAutomatic)
    }

    /** FR-41: the schema file cannot be parsed and there is no earlier diagram to keep. */
    fun showError(error: SchemaError, onOpenFile: () -> Unit) = rebuild {
        title(
            if (error is SchemaError.UnsupportedFormat) RoomErBundle.message("state.error.unsupported.title")
            else RoomErBundle.message("state.error.title")
        )
        link(error.filePath, onClick = onOpenFile)
        text(describe(error))
    }

    private fun rebuild(build: Builder.() -> Unit) {
        content.removeAll()
        Builder().build()
        content.revalidate()
        content.repaint()
    }

    private inner class Builder {
        fun title(text: String) {
            content.add(wrapping(text).apply { font = JBFont.label().asBold() })
        }

        fun text(text: String) {
            content.add(wrapping(text))
        }

        fun code(text: String) {
            content.add(JBTextArea(text).apply {
                isEditable = false
                font = JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBFont.label().size))
                background = UIUtil.getTextFieldBackground()
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.empty(8)
            })
        }

        fun link(text: String, external: Boolean = false, onClick: () -> Unit) {
            val link = ActionLink(text) { onClick() }
            if (external) link.setExternalLinkIcon()
            link.horizontalAlignment = SwingConstants.LEFT
            // Keep the link at its natural width so only the text itself is clickable.
            content.add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(link)
            })
        }

        private fun wrapping(text: String): JBTextArea = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty()
            font = JBFont.label()
            foreground = UIUtil.getLabelForeground()
        }
    }

    /** Fills the viewport width so wrapped text re-flows when the tool window is resized. */
    private class WidthTrackingPanel : JPanel(VerticalLayout(JBUI.scale(10))), Scrollable {
        init {
            border = JBUI.Borders.empty(20, 24)
            background = UIUtil.getPanelBackground()
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    companion object {
        const val ROOM_DOCS_URL: String =
            "https://developer.android.com/training/data-storage/room/migrating-db-versions#export-schemas"

        /** Human-readable reason; for an unsupported format it names the value found and the supported one (AC-06.4). */
        fun describe(error: SchemaError): String = when (error) {
            is SchemaError.UnsupportedFormat ->
                RoomErBundle.message("error.unsupported.format", error.found, error.supported)
            else -> error.reason
        }
    }
}
