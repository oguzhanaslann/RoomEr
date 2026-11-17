package com.oguzhanaslann.roomer.navigation

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.oguzhanaslann.roomer.RoomErBundle
import com.oguzhanaslann.roomer.RoomErNotifications
import com.oguzhanaslann.roomer.model.EntityKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import java.io.IOException

/** Where "Go to source" leads. */
sealed interface NavigationTarget {
    /** The declaring class(es); several candidates require a chooser (AC-17.4). */
    data class Classes(val candidates: List<EntityCandidate>) : NavigationTarget

    /** No class found: the schema JSON at the entity, with the reason to tell the user (AC-17.3). */
    data class SchemaJson(val file: VirtualFile, val offset: Int, val reason: FallbackReason) : NavigationTarget

    /** Neither a class nor the schema file is available. */
    data class Nothing(val reason: FallbackReason) : NavigationTarget
}

enum class FallbackReason { INDEXING, ROOM_NOT_AVAILABLE, NO_MATCHING_CLASS }

/**
 * "Go to source" (spec FR-33, AC-17.x). Resolution runs in a background read action; only opening the
 * editor or the chooser happens on the EDT.
 */
class EntityNavigator(private val project: Project) {

    /** Resolves the target without touching the UI. Safe to call from any thread except under a write lock. */
    suspend fun resolveTarget(entityName: String, kind: EntityKind, schemaFile: VirtualFile?): NavigationTarget {
        val resolution: EntityResolution? =
            if (DumbService.isDumb(project)) null // do not make the user wait for indexing: fall back to the JSON
            else smartReadAction(project) { EntityResolver.resolve(project, entityName, kind) }
        val reason = when (resolution) {
            is EntityResolution.Found -> return NavigationTarget.Classes(resolution.candidates)
            null -> FallbackReason.INDEXING
            EntityResolution.RoomNotAvailable -> FallbackReason.ROOM_NOT_AVAILABLE
            EntityResolution.NoMatch -> FallbackReason.NO_MATCHING_CLASS
        }
        if (schemaFile == null || !schemaFile.isValid) return NavigationTarget.Nothing(reason)
        val offset = withContext(Dispatchers.IO) {
            try {
                JsonEntityLocator.offsetOf(VfsUtilCore.loadText(schemaFile), entityName, kind)
            } catch (_: IOException) {
                -1
            }
        }
        return NavigationTarget.SchemaJson(schemaFile, offset.coerceAtLeast(0), reason)
    }

    /** Resolves and navigates. [popupOwner] anchors the chooser shown for several candidates. */
    suspend fun goToSource(entityName: String, kind: EntityKind, schemaFile: VirtualFile?, popupOwner: Component?) {
        val target = resolveTarget(entityName, kind, schemaFile)
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            when (target) {
                is NavigationTarget.Classes ->
                    if (target.candidates.size == 1) open(target.candidates.single().file, target.candidates.single().offset)
                    else chooseCandidate(entityName, target.candidates, popupOwner)

                is NavigationTarget.SchemaJson -> {
                    open(target.file, target.offset)
                    notify(RoomErBundle.message("navigation.fallback.json", entityName, reasonText(target.reason)))
                }

                is NavigationTarget.Nothing ->
                    notify(RoomErBundle.message("navigation.fallback.none", entityName, reasonText(target.reason)))
            }
        }
    }

    private fun chooseCandidate(entityName: String, candidates: List<EntityCandidate>, popupOwner: Component?) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(candidates)
            .setTitle(RoomErBundle.message("navigation.chooser.title", entityName))
            .setRenderer(CandidateRenderer())
            .setNamerForFiltering { it.qualifiedName }
            .setItemChosenCallback { open(it.file, it.offset) }
            .createPopup()
        if (popupOwner != null && popupOwner.isShowing) popup.showInCenterOf(popupOwner)
        else popup.showCenteredInCurrentWindow(project)
    }

    private fun open(file: VirtualFile, offset: Int) {
        if (file.isValid) OpenFileDescriptor(project, file, offset).navigate(true)
    }

    private fun notify(text: String) {
        RoomErNotifications.notify(project, text, NotificationType.INFORMATION)
    }

    private fun reasonText(reason: FallbackReason): String = when (reason) {
        FallbackReason.INDEXING -> RoomErBundle.message("navigation.reason.indexing")
        FallbackReason.ROOM_NOT_AVAILABLE -> RoomErBundle.message("navigation.reason.no.room")
        FallbackReason.NO_MATCHING_CLASS -> RoomErBundle.message("navigation.reason.no.match")
    }

    private class CandidateRenderer : com.intellij.ui.SimpleListCellRenderer<EntityCandidate>() {
        override fun customize(
            list: javax.swing.JList<out EntityCandidate>,
            value: EntityCandidate?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            text = value?.qualifiedName.orEmpty()
            icon = com.intellij.icons.AllIcons.Nodes.Class
            toolTipText = value?.file?.presentableUrl
        }
    }
}
