package com.oguzhanaslann.roomer.toolwindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.oguzhanaslann.roomer.layout.ElkLayoutEngine
import com.oguzhanaslann.roomer.layout.LayoutEngine
import com.oguzhanaslann.roomer.state.RoomErState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job

/**
 * Project-level home of the plugin's coroutine scope (cancelled by the platform when the project closes
 * or the plugin is unloaded) and of the replaceable [LayoutEngine] (spec NFR-07, AC-10.2).
 */
@Service(Service.Level.PROJECT)
class RoomErProjectService(private val scope: CoroutineScope) {
    @Volatile
    var layoutEngine: LayoutEngine = ElkLayoutEngine()

    /** A scope whose lifetime is bounded by the project and that can be cancelled on its own. */
    fun childScope(): CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
}

/** Creates the "Room ER" tool window content. Available during indexing: discovery does not need indices. */
class RoomErToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<RoomErProjectService>()
        val controller = DiagramController(project, RoomErState.getInstance(project), service.layoutEngine)
        val panel = RoomErPanel(project, controller, service.childScope())
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.canvas
        toolWindow.contentManager.addContent(content)
    }
}
