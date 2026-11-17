package com.oguzhanaslann.roomer

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.oguzhanaslann.roomer.state.RoomErState
import com.oguzhanaslann.roomer.toolwindow.DiagramController
import com.oguzhanaslann.roomer.toolwindow.DiagramState

/**
 * Base for platform tests. Tests run on a background thread (like the production pipeline) so they can
 * drive suspend functions with `timeoutRunBlocking` while the EDT stays free.
 */
abstract class RoomErPlatformTestCase : BasePlatformTestCase() {
    protected val layoutEngine = RecordingLayoutEngine()
    protected lateinit var state: RoomErState

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        state = RoomErState.getInstance(project)
        state.loadState(RoomErState.Data()) // the light project is shared between tests
    }

    protected fun newController(): DiagramController = DiagramController(project, state, layoutEngine, FixedSizeMeasurer)

    protected fun addFile(relativePath: String, text: String): VirtualFile =
        myFixture.addFileToProject(relativePath, text).virtualFile

    protected fun setText(file: VirtualFile, text: String) {
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(file, text) }
    }

    protected fun delete(file: VirtualFile) {
        WriteAction.runAndWait<Throwable> { file.delete(this) }
    }

    protected fun DiagramState.asDiagram(): DiagramState.Diagram {
        assertTrue("expected a diagram but was $this", this is DiagramState.Diagram)
        return this as DiagramState.Diagram
    }
}
