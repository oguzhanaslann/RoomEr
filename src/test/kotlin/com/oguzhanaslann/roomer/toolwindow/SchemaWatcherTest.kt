package com.oguzhanaslann.roomer.toolwindow

import com.intellij.testFramework.common.timeoutRunBlocking
import com.oguzhanaslann.roomer.RoomErPlatformTestCase
import com.oguzhanaslann.roomer.TestSchemas
import com.oguzhanaslann.roomer.discovery.SchemaWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** FR-06, AC-05.1: file changes trigger a debounced reload. */
class SchemaWatcherTest : RoomErPlatformTestCase() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun tearDown() {
        try {
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testSchemaChangesReloadTheDiagramAndOtherFilesDoNot() {
        val file = addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users"))
        val other = addFile("app/src/Main.java", "class Main {}")
        val controller = newController()
        timeoutRunBlocking { controller.reload() }

        val reloads = Channel<DiagramSnapshot>(Channel.UNLIMITED)
        SchemaWatcher(project, testRootDisposable, scope, debounceMillis = 50, isRelevant = controller::isWatchedPath) {
            reloads.send(controller.reload(force = false))
        }

        setText(other, "class Main { int x; }")
        assertNull("unrelated files are ignored", timeoutRunBlocking { withTimeoutOrNull(400) { reloads.receive() } })

        // A burst of changes is coalesced into one reload.
        setText(file, TestSchemas.schema(2, "users", "a"))
        setText(file, TestSchemas.schema(2, "users", "orders"))
        val changed = timeoutRunBlocking { reloads.receive() }
        assertEquals(setOf("users", "orders"), changed.state.asDiagram().positions.keys)
        assertNull(timeoutRunBlocking { withTimeoutOrNull(300) { reloads.receive() } })

        addFile("app/schemas/com.acme.AppDb/3.json", TestSchemas.schema(3, "users"))
        assertEquals(3, timeoutRunBlocking { reloads.receive() }.state.asDiagram().database.version)

        delete(file.parent)
        assertEquals(DiagramState.NoSchemas, timeoutRunBlocking { reloads.receive() }.state)
    }

    fun testNothingIsDeliveredAfterDisposal() {
        val calls = Channel<Unit>(Channel.UNLIMITED)
        val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watcher = SchemaWatcher(project, testRootDisposable, watcherScope, debounceMillis = 50, isRelevant = { false }) { calls.send(Unit) }
        watcher.schedule()
        watcherScope.cancel()
        timeoutRunBlocking { delay(200) }
        assertTrue(calls.tryReceive().isFailure)
    }
}
