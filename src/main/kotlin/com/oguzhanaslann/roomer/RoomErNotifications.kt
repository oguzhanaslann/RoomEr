package com.oguzhanaslann.roomer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object RoomErNotifications {
    /** Must match the `notificationGroup` id in plugin.xml. */
    const val GROUP_ID: String = "RoomEr"

    fun notify(project: Project, content: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
            .notify(project)
    }
}
