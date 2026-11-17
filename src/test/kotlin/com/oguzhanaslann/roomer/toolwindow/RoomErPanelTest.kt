package com.oguzhanaslann.roomer.toolwindow

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.SimpleColoredComponent
import com.oguzhanaslann.roomer.RoomErBundle
import com.oguzhanaslann.roomer.RoomErPlatformTestCase
import com.oguzhanaslann.roomer.TestSchemas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.text.JTextComponent

/**
 * The Swing panel driven end to end on the EDT, with the real parser and the layout test double:
 * AC-03.x, AC-04.1, AC-06.x, AC-14.x, AC-20.2, legend and collapse persistence.
 */
class RoomErPanelTest : RoomErPlatformTestCase() {
    private lateinit var controller: CountingController
    private val notifications = CopyOnWriteArrayList<Notification>()

    /** Counts persistence calls so "persisted exactly once" can be asserted. */
    private inner class CountingController : DiagramController(project, state, layoutEngine, com.oguzhanaslann.roomer.FixedSizeMeasurer) {
        val collapsedCalls = CopyOnWriteArrayList<Set<String>>()
        override fun collapsedChanged(collapsed: Set<String>) {
            collapsedCalls += collapsed
            super.collapsedChanged(collapsed)
        }
    }

    override fun setUp() {
        super.setUp()
        controller = CountingController()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun <T> edt(block: () -> T): T = runInEdtAndGet { block() }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!edt(condition)) {
            if (System.currentTimeMillis() > deadline) fail("timed out: $message")
            Thread.sleep(20)
        }
    }

    private fun createPanel(): RoomErPanel = edt {
        RoomErPanel(project, controller, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also {
            Disposer.register(testRootDisposable, it)
        }
    }

    /** Waits until the panel has rendered the controller's latest snapshot and that snapshot is not "Loading". */
    private fun RoomErPanel.awaitRendered(condition: (DiagramSnapshot) -> Boolean = { true }): DiagramSnapshot {
        waitUntil("panel renders a loaded snapshot") {
            val snapshot = controller.snapshot
            shownSnapshot === snapshot && snapshot.state != DiagramState.Loading && !controller.loading.value && condition(snapshot)
        }
        return controller.snapshot
    }

    private fun texts(component: Component): List<String> {
        val result = ArrayList<String>()
        fun visit(c: Component) {
            when (c) {
                is JTextComponent -> result += c.text
                is AbstractButton -> result += c.text
                is JLabel -> result += c.text
                is SimpleColoredComponent -> result += c.getCharSequence(false).toString()
            }
            if (c is Container) c.components.forEach(::visit)
        }
        visit(component)
        return result.filter { it.isNotBlank() }
    }

    private fun appDb() {
        addFile("app/schemas/com.acme.AppDb/1.json", TestSchemas.schema(1, "users"))
        addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users", "orders"))
    }

    private fun event(action: AnAction, panel: RoomErPanel?): com.intellij.openapi.actionSystem.AnActionEvent {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        if (panel != null) builder.add(RoomErPanel.KEY, panel)
        val context: DataContext = builder.build()
        return TestActionEvent.createTestEvent(action, context)
    }

    // ---- header and database chooser --------------------------------------------------------------

    fun testHeaderShowsNameAndVersionOfASingleDatabase() {
        appDb()
        val panel = createPanel()
        val diagram = panel.awaitRendered().state.asDiagram()
        assertEquals(2, diagram.database.version)

        edt {
            assertEquals("AppDb", panel.titleLabel.text) // AC-04.1
            assertEquals("v2", panel.versionLabel.text)
            assertEquals("com.acme.AppDb", panel.titleLabel.toolTipText)
            assertTrue(panel.titleLabel.isVisible)
            assertFalse("one database needs no chooser (AC-03.3)", panel.databaseCombo.isVisible)
            assertEquals(RoomErPanel.CARD_DIAGRAM, panel.visibleCard)
            assertEquals(setOf("users", "orders"), panel.canvas.database?.entities?.map { it.name }?.toSet())
        }
    }

    fun testSeveralDatabasesAreListedBySimpleNameWithFullNameTooltip() {
        appDb()
        addFile("lib/schemas/com.acme.data.OtherDb/1.json", TestSchemas.schema(1, "notes"))
        val panel = createPanel()
        panel.awaitRendered()

        edt {
            val combo = panel.databaseCombo
            assertTrue(combo.isVisible) // AC-03.1
            assertFalse(panel.titleLabel.isVisible)
            assertEquals(2, combo.itemCount)
            val rendered = (0 until combo.itemCount).map { index ->
                val db = combo.getItemAt(index)
                @Suppress("UNCHECKED_CAST")
                val renderer = combo.renderer as javax.swing.ListCellRenderer<Any?>
                val component = renderer.getListCellRendererComponent(JList<Any?>(), db, index, false, false) as JComponent
                Triple(db.name, texts(component), component.toolTipText)
            }
            assertEquals(setOf("com.acme.AppDb", "com.acme.data.OtherDb"), rendered.map { it.first }.toSet())
            for ((name, labels, tooltip) in rendered) {
                assertTrue("$name is shown by its simple name, got $labels", labels.contains(name.substringAfterLast('.')))
                assertEquals(name, tooltip)
            }
            assertEquals(panel.shownSnapshot.selected?.name, combo.toolTipText)
        }
    }

    // ---- search -----------------------------------------------------------------------------------

    fun testSearchListsResultsSelectsInCanvasAndSaysNoResults() {
        appDb()
        val panel = createPanel()
        panel.awaitRendered()

        edt {
            assertFalse(panel.resultsPane.isVisible)
            panel.searchField.text = "ORD" // case-insensitive (FR-30)
            assertTrue(panel.resultsPane.isVisible)
            assertTrue(panel.resultsModel.items.any { it.entity == "orders" && it.column == null })
            assertEquals(0, panel.resultsList.selectedIndex)

            // Enter in the search field shows the selected result in the canvas (AC-14.2).
            val editor = panel.searchField.textEditor
            val enter = KeyEvent(editor, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n')
            editor.keyListeners.forEach { it.keyPressed(enter) }
            assertEquals("orders", panel.canvas.selectedEntity)

            panel.searchField.text = "zzz-nothing"
            assertTrue("the list stays visible to say there are no results", panel.resultsPane.isVisible)
            assertTrue(panel.resultsModel.isEmpty)
            assertEquals(RoomErBundle.message("search.no.results"), panel.resultsList.emptyText.text)

            val escape = KeyEvent(editor, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
            editor.keyListeners.forEach { it.keyPressed(escape) }
            assertEquals("", panel.searchField.text)
            assertFalse(panel.resultsPane.isVisible)
        }
    }

    // ---- stale diagram and message cards ----------------------------------------------------------

    fun testInvalidFileKeepsLastDiagramWithStaleBanner() {
        addFile("app/schemas/com.acme.AppDb/1.json", TestSchemas.schema(1, "users"))
        val latest = addFile("app/schemas/com.acme.AppDb/2.json", TestSchemas.schema(2, "users", "orders"))
        val panel = createPanel()
        val first = panel.awaitRendered().state.asDiagram()
        edt { assertFalse(panel.errorBanner.isVisible) }

        setText(latest, "{ \"formatVersion\": 1, \"database\": ")
        edt { panel.refresh() }
        val stale = panel.awaitRendered { (it.state as? DiagramState.Diagram)?.stale == true }.state.asDiagram()

        assertEquals("the last good model stays (AC-06.5)", first.modelRevision, stale.modelRevision)
        edt {
            assertEquals(RoomErPanel.CARD_DIAGRAM, panel.visibleCard)
            assertTrue(panel.canvas.stale)
            assertTrue(panel.errorBanner.isVisible)
            assertTrue(panel.errorBanner.text, panel.errorBanner.text.contains(latest.path))
            assertEquals("v2", panel.versionLabel.text)
            assertEquals(setOf("users", "orders"), panel.canvas.database?.entities?.map { it.name }?.toSet())
        }

        setText(latest, TestSchemas.schema(2, "users", "orders"))
        edt { panel.refresh() }
        panel.awaitRendered { (it.state as? DiagramState.Diagram)?.stale == false }
        edt {
            assertFalse(panel.errorBanner.isVisible)
            assertFalse(panel.canvas.stale)
        }
    }

    fun testNoSchemasCardExplainsHowToExport() {
        val panel = createPanel()
        assertEquals(DiagramState.NoSchemas, panel.awaitRendered().state)

        edt {
            assertEquals(RoomErPanel.CARD_MESSAGE, panel.visibleCard)
            val shown = texts(panel.messagePanel.component)
            val all = shown.joinToString("\n")
            assertTrue(all, all.contains("exportSchema = true")) // AC-06.1
            assertTrue(all, all.contains("room.schemaLocation"))
            assertTrue(shown.contains(RoomErBundle.message("link.room.docs")))
            assertTrue(shown.contains(RoomErBundle.message("link.choose.folder")))
            assertEquals("", panel.titleLabel.text)
            assertEquals("", panel.versionLabel.text)
        }
    }

    fun testParseErrorCardNamesFileAndReason() {
        val broken = addFile("app/schemas/com.acme.AppDb/3.json", "{ not json")
        val panel = createPanel()
        val failed = panel.awaitRendered().state as DiagramState.Failed

        edt {
            assertEquals(RoomErPanel.CARD_MESSAGE, panel.visibleCard)
            val shown = texts(panel.messagePanel.component)
            assertTrue("$shown", shown.contains(RoomErBundle.message("state.error.title"))) // AC-06.2
            assertTrue("$shown", shown.contains(broken.path))
            val reason = MessagePanel.describe(failed.error)
            assertTrue(reason.isNotBlank())
            assertTrue("$shown", shown.contains(reason))
            assertEquals("AppDb", panel.titleLabel.text)
        }
    }

    fun testFolderWithoutSchemasCardNamesTheFolder() {
        val folder = myFixture.tempDirFixture.findOrCreateDir("not-schemas")
        addFile("not-schemas/readme.txt", "nothing here")
        state.schemaFolderUrl = folder.url
        val panel = createPanel()
        val empty = panel.awaitRendered().state as DiagramState.FolderWithoutSchemas

        edt {
            assertEquals(RoomErPanel.CARD_MESSAGE, panel.visibleCard)
            val shown = texts(panel.messagePanel.component)
            assertTrue("$shown", shown.contains(RoomErBundle.message("state.folder.empty.title", empty.folderPath))) // FR-42
            assertTrue(empty.folderPath.endsWith("not-schemas"))
            assertTrue(shown.contains(RoomErBundle.message("link.automatic.discovery")))
        }
    }

    // ---- toolbar ----------------------------------------------------------------------------------

    fun testLegendToggleRoundTrip() {
        appDb()
        state.legendVisible = false
        val panel = createPanel()
        panel.awaitRendered()
        val action = ActionManager.getInstance().getAction("RoomEr.ToggleLegend") as ToggleAction

        edt {
            assertFalse("the stored choice is applied on creation", panel.canvas.legendVisible)
            val e = event(action, panel)
            action.update(e)
            assertTrue(e.presentation.isEnabled)
            assertFalse(action.isSelected(e))

            action.actionPerformed(event(action, panel))
            assertTrue(panel.canvas.legendVisible)
            assertTrue(state.legendVisible)

            action.actionPerformed(event(action, panel))
            assertFalse(panel.canvas.legendVisible)
            assertFalse(state.legendVisible)
        }
    }

    fun testCollapseAllAndExpandAllArePersistedExactlyOnce() {
        appDb()
        val panel = createPanel()
        val diagram = panel.awaitRendered().state.asDiagram()
        val key = diagram.source.stateKey
        val collapseAll = ActionManager.getInstance().getAction("RoomEr.CollapseAll")
        val expandAll = ActionManager.getInstance().getAction("RoomEr.ExpandAll")

        edt { collapseAll.actionPerformed(event(collapseAll, panel)) }
        assertEquals(listOf(setOf("users", "orders")), controller.collapsedCalls)
        assertEquals(setOf("users", "orders"), state.collapsed(key))
        panel.awaitRendered()
        assertEquals("render() must not echo the change back into the canvas", diagram.modelRevision, controller.snapshot.state.asDiagram().modelRevision)

        edt { panel.collapseAll() } // no-op: nothing fires
        assertEquals(1, controller.collapsedCalls.size)

        edt { expandAll.actionPerformed(event(expandAll, panel)) }
        assertEquals(listOf(setOf("users", "orders"), emptySet()), controller.collapsedCalls)
        assertEquals(emptySet<String>(), state.collapsed(key))
    }

    fun testEveryToolbarActionUpdatesWithAndWithoutPanel() {
        appDb()
        val panel = createPanel()
        panel.awaitRendered()
        val group = ActionManager.getInstance().getAction(RoomErPanel.TOOLBAR_GROUP_ID) as DefaultActionGroup
        val actions = group.childActionsOrStubs.filter { it !is Separator }
            .map { ActionManager.getInstance().getId(it)!! }
            .map { ActionManager.getInstance().getAction(it) }
        assertTrue(actions.size >= 12)

        edt {
            for (action in actions) {
                val without = event(action, null)
                action.update(without)
                assertFalse("${action.javaClass.simpleName} is disabled without a panel", without.presentation.isEnabled)

                val with = event(action, panel)
                action.update(with)
                val expectEnabled = action !is FocusModeAction // focus mode needs a selection
                assertEquals(action.javaClass.simpleName, expectEnabled, with.presentation.isEnabled)
            }
            panel.canvas.select("users")
            val focus = actions.single { it is FocusModeAction }
            val e = event(focus, panel)
            focus.update(e)
            assertTrue("focus mode is available with a selection", e.presentation.isEnabled)
        }
    }

    // ---- Mermaid ----------------------------------------------------------------------------------

    fun testCancelledSaveWritesNothing() {
        appDb()
        val panel = createPanel()
        panel.awaitRendered()
        val dir = Files.createTempDirectory("roomer-save")
        val asked = CopyOnWriteArrayList<String>()

        edt {
            panel.mermaidTargetChooser = { name -> asked += name; null }
            val action = ActionManager.getInstance().getAction("RoomEr.SaveAsMermaid")
            action.actionPerformed(event(action, panel))
        }
        Thread.sleep(200) // a write would be launched on Dispatchers.IO

        assertEquals(listOf("AppDb.mmd"), asked)
        assertEquals("AC-20.2: nothing written", 0L, Files.list(dir).use { it.count() })
        assertTrue("no notification on cancel", notifications.isEmpty())
        dir.toFile().deleteRecursively()
    }

    fun testSaveWritesTheChosenFile() {
        appDb()
        val panel = createPanel()
        panel.awaitRendered()
        val dir = Files.createTempDirectory("roomer-save")
        val target: Path = dir.resolve("AppDb.mmd")

        edt {
            panel.mermaidTargetChooser = { target }
            panel.saveAsMermaid()
        }
        waitUntil("file written and notified") { Files.exists(target) && notifications.isNotEmpty() }
        assertTrue(Files.readString(target).startsWith("erDiagram"))
        dir.toFile().deleteRecursively()
    }

    fun testCopyAsMermaidPutsTextOnTheClipboard() {
        appDb()
        val panel = createPanel()
        panel.awaitRendered()

        edt { panel.copyAsMermaid() }

        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        assertNotNull(text)
        assertTrue(text!!, text.startsWith("erDiagram"))
        assertTrue(text, text.contains("orders"))
    }
}
