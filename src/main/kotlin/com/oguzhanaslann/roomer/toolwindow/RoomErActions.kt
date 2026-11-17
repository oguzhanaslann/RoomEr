package com.oguzhanaslann.roomer.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction

/**
 * Toolbar actions of the Room ER tool window. Registered in plugin.xml (group `RoomEr.Toolbar`); texts
 * and descriptions come from `messages/RoomErBundle.properties`. They read Swing state of the panel, so
 * they update on the EDT.
 */
abstract class RoomErAction : DumbAwareAction() {
    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    final override fun update(e: AnActionEvent) {
        val panel = e.getData(RoomErPanel.KEY)
        e.presentation.isEnabled = panel != null && isEnabled(panel)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        e.getData(RoomErPanel.KEY)?.let(::perform)
    }

    /** Most actions need a diagram on screen. */
    protected open fun isEnabled(panel: RoomErPanel): Boolean = panel.hasDiagram
    protected abstract fun perform(panel: RoomErPanel)
}

class RefreshAction : RoomErAction() {
    override fun isEnabled(panel: RoomErPanel) = true
    override fun perform(panel: RoomErPanel) = panel.refresh()
}

class FitToWindowAction : RoomErAction() {
    override fun perform(panel: RoomErPanel) = panel.canvas.fitToWindow()
}

class ZoomInAction : RoomErAction() {
    override fun perform(panel: RoomErPanel) = panel.canvas.zoomIn()
}

class ZoomOutAction : RoomErAction() {
    override fun perform(panel: RoomErPanel) = panel.canvas.zoomOut()
}

class CollapseAllAction : RoomErAction() {
    override fun perform(panel: RoomErPanel) = panel.collapseAll()
}

class ExpandAllAction : RoomErAction() {
    override fun perform(panel: RoomErPanel) = panel.expandAll()
}

class ResetLayoutAction : RoomErAction() {
    override fun perform(panel: RoomErPanel) = panel.resetLayout()
}

class CopyAsMermaidAction : RoomErAction() {
    override fun perform(panel: RoomErPanel) = panel.copyAsMermaid()
}

class SaveAsMermaidAction : RoomErAction() {
    override fun perform(panel: RoomErPanel) = panel.saveAsMermaid()
}

class ChooseSchemaFolderAction : RoomErAction() {
    override fun isEnabled(panel: RoomErPanel) = true
    override fun perform(panel: RoomErPanel) = panel.chooseSchemaFolder()
}

/** Focus mode (spec FR-31): available when a table is selected, or to switch it off again. */
class FocusModeAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean = e.getData(RoomErPanel.KEY)?.focusMode == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.getData(RoomErPanel.KEY)?.focusMode = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val panel = e.getData(RoomErPanel.KEY)
        e.presentation.isEnabled = panel != null && (panel.hasSelection || panel.focusMode)
    }
}

/** Shows or hides the legend overlay (spec FR-22); the choice is remembered per project. */
class ToggleLegendAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean = e.getData(RoomErPanel.KEY)?.legendVisible == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.getData(RoomErPanel.KEY)?.legendVisible = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.getData(RoomErPanel.KEY)?.hasDiagram == true
    }
}
