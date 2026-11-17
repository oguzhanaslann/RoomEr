package com.oguzhanaslann.roomer

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowEP
import com.oguzhanaslann.roomer.layout.LayoutEngine
import com.oguzhanaslann.roomer.layout.LayoutResult
import com.oguzhanaslann.roomer.toolwindow.RoomErPanel
import com.oguzhanaslann.roomer.toolwindow.RoomErProjectService
import com.oguzhanaslann.roomer.toolwindow.RoomErToolWindowFactory

/** plugin.xml: tool window, actions, notification group, services. */
class PluginRegistrationTest : RoomErPlatformTestCase() {

    fun testToolWindowIsRegistered() {
        val extension = ToolWindowEP.EP_NAME.extensionList.single { it.id == "Room ER" }
        assertEquals(RoomErToolWindowFactory::class.java.name, extension.factoryClass)
        assertNotNull(RoomErIcons.ToolWindow)
        assertTrue(DumbAware::class.java.isAssignableFrom(RoomErToolWindowFactory::class.java))
    }

    fun testToolbarActionsAreRegisteredWithSpecTexts() {
        val actionManager = ActionManager.getInstance()
        val group = actionManager.getAction(RoomErPanel.TOOLBAR_GROUP_ID) as ActionGroup
        val ids = listOf(
            "Refresh", "FitToWindow", "ZoomIn", "ZoomOut", "CollapseAll", "ExpandAll", "FocusMode",
            "ResetLayout", "ToggleLegend", "CopyAsMermaid", "SaveAsMermaid", "ChooseSchemaFolder",
        ).map { "RoomEr.$it" }
        val texts = ids.associateWith { actionManager.getAction(it).templatePresentation.text }

        assertEquals("Refresh", texts["RoomEr.Refresh"])
        assertEquals("Reset layout", texts["RoomEr.ResetLayout"])
        assertEquals("Copy as Mermaid", texts["RoomEr.CopyAsMermaid"])
        assertEquals("Save as .mmd…", texts["RoomEr.SaveAsMermaid"])
        assertEquals("Choose schema folder…", texts["RoomEr.ChooseSchemaFolder"])
        assertEquals("Show Legend", texts["RoomEr.ToggleLegend"])
        for (id in listOf("RoomEr.FitToWindow", "RoomEr.ZoomIn", "RoomEr.ZoomOut", "RoomEr.CollapseAll", "RoomEr.ExpandAll", "RoomEr.FocusMode")) {
            assertFalse(id, texts[id].isNullOrBlank())
        }
        for (id in ids) {
            val action = actionManager.getAction(id)
            assertTrue("$id must be DumbAware", action is DumbAware)
            assertNotNull("$id needs an icon for the toolbar", action.templatePresentation.icon)
            assertFalse("$id needs a description", action.templatePresentation.description.isNullOrBlank())
            // Every action reads Swing state of the panel, so it must declare its update thread (no OLD_EDT default).
            assertEquals("$id update thread", com.intellij.openapi.actionSystem.ActionUpdateThread.EDT, action.actionUpdateThread)
        }
        val children = (group as com.intellij.openapi.actionSystem.DefaultActionGroup).childActionsOrStubs
        assertEquals("every action is on the toolbar", ids.size, children.count { it !is com.intellij.openapi.actionSystem.Separator })
    }

    fun testNotificationGroupIsRegistered() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(RoomErNotifications.GROUP_ID))
    }

    fun testLayoutEngineOfTheProjectServiceCanBeReplaced() {
        val service = project.service<RoomErProjectService>()
        val original = service.layoutEngine
        try {
            val double = LayoutEngine { LayoutResult(emptyMap()) }
            service.layoutEngine = double
            assertSame(double, project.service<RoomErProjectService>().layoutEngine)
        } finally {
            service.layoutEngine = original
        }
    }
}
