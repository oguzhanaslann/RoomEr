package com.oguzhanaslann.roomer.render

import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.layout.Size
import com.oguzhanaslann.roomer.model.Database
import com.oguzhanaslann.roomer.render.demo.DemoData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource
import javax.swing.plaf.FontUIResource
import kotlin.math.abs

class DiagramCanvasTest {

    private class Recorder : DiagramCanvasListener {
        val events = ArrayList<String>()
        var lastPositions: Map<String, Point>? = null
        override fun goToSourceRequested(entityName: String) { events += "goto:$entityName" }
        override fun positionsChanged(positions: Map<String, Point>) { events += "positions"; lastPositions = positions }
        override fun collapsedChanged(collapsed: Set<String>) { events += "collapsed:${collapsed.sorted().joinToString(",")}" }
        override fun selectionChanged(entityName: String?) { events += "selection:$entityName" }
        override fun focusModeChanged(enabled: Boolean) { events += "focus:$enabled" }
        override fun searchRequested() { events += "search" }
        override fun escapePressed() { events += "escape" }
    }

    private val db = DemoData.database()
    private lateinit var canvas: DiagramCanvas
    private lateinit var rec: Recorder

    @Before
    fun setUp() {
        installTheme(LIGHT)
        canvas = DiagramCanvas()
        canvas.setSize(1400, 800)
        rec = Recorder()
        canvas.addCanvasListener(rec)
        canvas.setDiagram(db, DemoData.positions())
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private fun node(name: String) = canvas.sceneOrNull!!.byName[name]!!
    private fun sx(dx: Double) = canvas.viewportState.toScreenX(dx).toInt()
    private fun sy(dy: Double) = canvas.viewportState.toScreenY(dy).toInt()

    private fun mouse(id: Int, x: Int, y: Int, button: Int = MouseEvent.BUTTON1, clicks: Int = 1, popup: Boolean = false) {
        val mask = when (button) {
            MouseEvent.BUTTON1 -> InputEvent.BUTTON1_DOWN_MASK
            MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK
            MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK
            else -> 0
        }
        val modifiers = if (id == MouseEvent.MOUSE_RELEASED || id == MouseEvent.MOUSE_CLICKED || id == MouseEvent.MOUSE_MOVED) 0 else mask
        val b = if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED) MouseEvent.NOBUTTON else button
        canvas.dispatchEvent(MouseEvent(canvas, id, System.currentTimeMillis(), modifiers, x, y, clicks, popup, b))
    }

    private fun click(x: Int, y: Int, clicks: Int = 1) {
        mouse(MouseEvent.MOUSE_PRESSED, x, y, clicks = clicks)
        mouse(MouseEvent.MOUSE_RELEASED, x, y, clicks = clicks)
        mouse(MouseEvent.MOUSE_CLICKED, x, y, clicks = clicks)
    }

    private fun drag(x0: Int, y0: Int, x1: Int, y1: Int, button: Int = MouseEvent.BUTTON1) {
        mouse(MouseEvent.MOUSE_PRESSED, x0, y0, button)
        mouse(MouseEvent.MOUSE_DRAGGED, (x0 + x1) / 2, (y0 + y1) / 2, button)
        mouse(MouseEvent.MOUSE_DRAGGED, x1, y1, button)
        mouse(MouseEvent.MOUSE_RELEASED, x1, y1, button)
    }

    private fun press(keyCode: Int, modifiers: Int = 0) {
        val e = KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)
        canvas.keyListeners.forEach { it.keyPressed(e) }
    }

    private fun type(ch: Char) {
        val e = KeyEvent(canvas, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch)
        canvas.keyListeners.forEach { it.keyTyped(e) }
    }

    private fun focus(gained: Boolean) {
        val e = java.awt.event.FocusEvent(canvas, if (gained) java.awt.event.FocusEvent.FOCUS_GAINED else java.awt.event.FocusEvent.FOCUS_LOST)
        canvas.focusListeners.forEach { if (gained) it.focusGained(e) else it.focusLost(e) }
    }

    private fun paint(c: DiagramCanvas = canvas): BufferedImage {
        val image = BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        c.paint(g)
        g.dispose()
        return image
    }

    // ---- metrics ----------------------------------------------------------------------------------------

    @Test
    fun metricsEqualTheSizesTheCanvasUses() {
        val collapsed = setOf("posts", "user_post_counts")
        canvas.setDiagram(db, DemoData.positions(), collapsed)
        val sizes = DiagramMetrics.measure(db, collapsed)
        assertEquals(db.entities.map { it.name }.toSet(), sizes.keys)
        for (n in canvas.sceneOrNull!!.nodes) {
            assertEquals(n.name, Size(n.width, n.height), sizes[n.name])
        }
        val expanded = DiagramMetrics.measure(db)
        assertTrue(expanded["posts"]!!.height > sizes["posts"]!!.height)
        assertEquals(expanded["posts"]!!.width, sizes["posts"]!!.width, 0.0)
        assertEquals(expanded["users"], sizes["users"])
    }

    @Test
    fun metricsEqualThePaintedPixels() {
        val single = db.copy(tables = listOf(db.table("users")!!.copy(foreignKeys = emptyList())), views = emptyList())
        val size = DiagramMetrics.measure(single)["users"]!!
        val c = DiagramCanvas()
        c.legendVisible = false
        c.setSize(600, 400)
        c.setDiagram(single, mapOf("users" to Point(0.0, 0.0)))
        assertEquals(1.0, c.zoom, 0.0)
        val image = paint(c)
        val bg = image.getRGB(1, 1)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) != bg) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        assertEquals(size.width, (maxX - minX + 1).toDouble(), 2.5)
        assertEquals(size.height, (maxY - minY + 1).toDouble(), 2.5)
        // every row's text stays inside the node: the right padding column is node background
        val right = image.getRGB(maxX - 3, (minY + maxY) / 2)
        assertEquals(UIManager.getColor("TextField.background").rgb, right)
    }

    @Test
    fun measureIsThreadSafe() {
        val big = DemoData.large(40, 10)
        val expected = DiagramMetrics.measure(big)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..8).map { Callable { DiagramMetrics.measure(big) } })
            for (r in results) assertEquals(expected, r.get())
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- setDiagram -------------------------------------------------------------------------------------

    @Test
    fun firstShowFitsTheDiagram() {
        val b = canvas.sceneOrNull!!.bounds()!!
        val v = canvas.viewportState
        assertTrue(v.toScreenX(b.x) >= 0 && v.toScreenX(b.x + b.width) <= canvas.width)
        assertTrue(v.toScreenY(b.y) >= 0 && v.toScreenY(b.y + b.height) <= canvas.height)
        assertEquals(db, canvas.database)
    }

    @Test
    fun fitKeepsTablesOutOfTheLegend() {
        canvas.collapseAll()
        for (legend in listOf(true, false)) {
            canvas.legendVisible = legend
            canvas.fitToWindow()
            val img = paint()
            val lb = ScenePainter().legendBounds(canvas.width, canvas.height, FontSet(Font(Font.SANS_SERIF, Font.PLAIN, 13)))!!
            val covered = canvas.sceneOrNull!!.nodes.any { n ->
                sx(n.x) < lb.x + lb.width && sx(n.x + n.width) > lb.x && sy(n.y) < lb.y + lb.height && sy(n.y + n.height) > lb.y
            }
            if (legend) assertFalse("a table is under the legend", covered)
            // every table is still fully on screen
            for (n in canvas.sceneOrNull!!.nodes) {
                assertTrue(n.name, sx(n.x) >= 0 && sx(n.x + n.width) <= img.width && sy(n.y) >= 0 && sy(n.y + n.height) <= img.height)
            }
        }
    }

    @Test
    fun fitIsDeferredUntilTheCanvasHasASize() {
        val c = DiagramCanvas()
        c.setDiagram(db, DemoData.positions())
        c.setSize(500, 300)
        val b = c.sceneOrNull!!.bounds()!!
        val v = c.viewportState
        assertTrue(c.zoom < 1.0)
        assertTrue(v.toScreenX(b.x) >= 0 && v.toScreenX(b.x + b.width) <= 500.0 + 1e-6)
        assertTrue(v.toScreenY(b.y) >= 0 && v.toScreenY(b.y + b.height) <= 300.0 + 1e-6)
    }

    @Test
    fun reloadPreservesViewportSelectionAndFocus() {
        canvas.select("posts", null, center = false)
        canvas.zoomIn()
        canvas.viewportState.panBy(33.0, -17.0)
        val zoom = canvas.zoom
        val panX = canvas.viewportState.panX
        val panY = canvas.viewportState.panY
        canvas.stale = true
        rec.events.clear()

        val moved = DemoData.positions() + ("tags" to Point(5.0, 5.0))
        canvas.setDiagram(db.copy(version = 8), moved, setOf("tags"), preserveViewport = true)
        assertEquals(zoom, canvas.zoom, 0.0)
        assertEquals(panX, canvas.viewportState.panX, 0.0)
        assertEquals(panY, canvas.viewportState.panY, 0.0)
        assertEquals("posts", canvas.selectedEntity)
        assertFalse(canvas.stale)
        assertEquals(Point(5.0, 5.0), canvas.positions()["tags"])
        assertEquals(setOf("tags"), canvas.collapsed())
        assertEquals(emptyList<String>(), rec.events)

        // focus survives while the table exists
        canvas.focusMode = true
        canvas.setDiagram(db, DemoData.positions(), preserveViewport = true)
        assertTrue(canvas.focusMode)
        assertEquals("posts", canvas.selectedEntity)

        // selected table removed: selection and focus are dropped, with events
        rec.events.clear()
        val without = db.copy(tables = db.tables.filter { it.name != "posts" && it.name != "comments" && it.name != "post_tags" && it.name != "posts_fts" })
        canvas.setDiagram(without, DemoData.positions(), preserveViewport = true)
        assertNull(canvas.selectedEntity)
        assertFalse(canvas.focusMode)
        assertEquals(listOf("selection:null", "focus:false"), rec.events)
        assertFalse("posts" in canvas.positions())
    }

    @Test
    fun nonPreservingSetDiagramResetsSelectionAndFits() {
        canvas.select("posts")
        canvas.zoomIn(); canvas.zoomIn()
        val z = canvas.zoom
        canvas.setDiagram(db, DemoData.positions())
        assertNull(canvas.selectedEntity)
        assertNotEquals(z, canvas.zoom, 1e-9)
    }

    @Test
    fun entitiesWithoutPositionGetADefaultPlace() {
        canvas.setDiagram(db, mapOf("users" to Point(0.0, 0.0)), preserveViewport = true)
        val positions = canvas.positions()
        assertEquals(db.entities.size, positions.size)
        assertEquals(Point(0.0, 0.0), positions["users"])
        assertTrue(positions.values.all { !it.x.isNaN() && !it.y.isNaN() })
        assertEquals(db.entities.size, positions.values.toSet().size)
        paint()
    }

    @Test
    fun collapsedSetRoundTrips() {
        val set = setOf("users", "posts", "user_post_counts")
        canvas.setDiagram(db, DemoData.positions(), set + "not_in_database")
        assertEquals(set, canvas.collapsed())
        rec.events.clear()
        canvas.setCollapsed("users", false)
        canvas.setCollapsed("users", false)
        assertEquals(setOf("posts", "user_post_counts"), canvas.collapsed())
        canvas.collapseAll()
        assertEquals(db.entities.map { it.name }.toSet(), canvas.collapsed())
        canvas.collapseAll()
        canvas.expandAll()
        assertEquals(emptySet<String>(), canvas.collapsed())
        assertEquals(3, rec.events.count { it.startsWith("collapsed:") })
        assertEquals("collapsed:", rec.events.last())
    }

    @Test
    fun clearDiagram() {
        canvas.select("users")
        canvas.clearDiagram()
        assertNull(canvas.database)
        assertNull(canvas.selectedEntity)
        assertEquals(emptyMap<String, Point>(), canvas.positions())
        assertEquals(emptySet<String>(), canvas.collapsed())
        canvas.fitToWindow(); canvas.zoomIn(); canvas.select("users"); canvas.focusMode = true
        assertFalse(canvas.focusMode)
        paint()
    }

    // ---- selection / focus ------------------------------------------------------------------------------

    @Test
    fun selectCentresAndHighlightsColumn() {
        canvas.zoomIn(); canvas.zoomIn()
        canvas.setCollapsed("tags", true)
        rec.events.clear()
        canvas.select("tags", "name")
        val n = node("tags")
        assertEquals("tags", canvas.selectedEntity)
        assertEquals("name", canvas.highlightedColumn)
        assertFalse("select(column) expands a collapsed table", n.collapsed)
        assertEquals(canvas.width / 2.0, canvas.viewportState.toScreenX(n.centerX), 1.0)
        assertEquals(canvas.height / 2.0, canvas.viewportState.toScreenY(n.centerY), 1.0)
        assertEquals(listOf("collapsed:", "selection:tags"), rec.events)

        rec.events.clear()
        canvas.select("tags", "name")
        canvas.select("no_such_table")
        assertEquals("tags", canvas.selectedEntity)
        assertEquals(emptyList<String>(), rec.events)
        canvas.select(null)
        assertNull(canvas.selectedEntity)
        assertNull(canvas.highlightedColumn)
        assertEquals(listOf("selection:null"), rec.events)
    }

    @Test
    fun focusModeRestoresPreviousViewport() {
        canvas.focusMode = true
        assertFalse("no-op without selection", canvas.focusMode)

        canvas.select("tags", null, center = false)
        canvas.viewportState.set(2.5, -900.0, 300.0)
        rec.events.clear()
        canvas.focusMode = true
        assertTrue(canvas.focusMode)
        // focus set brought into view
        val n = node("post_tags")
        assertTrue(sx(n.x) >= 0 && sx(n.x + n.width) <= canvas.width)
        // selection change re-focuses
        canvas.select("users", null, center = false)
        assertTrue(canvas.focusMode)
        val p = node("profiles")
        assertTrue(sx(p.x) >= 0 && sx(p.x + p.width) <= canvas.width)

        canvas.focusMode = false
        assertEquals(2.5, canvas.zoom, 0.0)
        assertEquals(-900.0, canvas.viewportState.panX, 0.0)
        assertEquals(300.0, canvas.viewportState.panY, 0.0)
        assertEquals(listOf("focus:true", "selection:users", "focus:false"), rec.events)
    }

    @Test
    fun clickingADimmedTableInFocusModeRefocusesWithoutMovingTheViewport() {
        canvas.select("post_tags", null, center = false)
        canvas.focusMode = true
        val v = canvas.viewportState
        val before = Triple(v.zoom, v.panX, v.panY)
        val profiles = node("profiles")
        assertFalse(profiles in canvas.sceneOrNull!!.nodes.filter { it.name in setOf("post_tags", "posts", "tags") })
        val x = sx(profiles.x + 60)
        val y = sy(profiles.y + 8)
        mouse(MouseEvent.MOUSE_PRESSED, x, y)
        assertEquals("profiles", canvas.selectedEntity)
        assertTrue(canvas.focusMode)
        assertEquals(before, Triple(v.zoom, v.panX, v.panY))
        // still the same table under the cursor, and a drag moves it
        mouse(MouseEvent.MOUSE_DRAGGED, x + 30, y)
        mouse(MouseEvent.MOUSE_RELEASED, x + 30, y)
        assertTrue("positions" in rec.events)
        // keyboard / programmatic selection still brings the new focus set into view
        canvas.viewportState.set(2.5, -3000.0, 300.0)
        canvas.select("tags", null, center = false)
        val t = node("tags")
        assertTrue(sx(t.x) >= 0 && sx(t.x + t.width) <= canvas.width)
    }

    @Test
    fun keyboardFocusIsVisible() {
        canvas.legendVisible = false
        val accent = Color(0x3574F0).rgb
        assertNotEquals(accent, paint().getRGB(1, canvas.height / 2))
        focus(true)
        assertTrue(canvas.hasKeyboardFocusForTest)
        val img = paint()
        for ((x, y) in listOf(1 to canvas.height / 2, canvas.width - 2 to 40, 300 to 1, 300 to canvas.height - 2)) {
            assertEquals("($x, $y)", accent, img.getRGB(x, y))
        }
        focus(false)
        assertNotEquals(accent, paint().getRGB(1, canvas.height / 2))
        // also without a diagram
        canvas.clearDiagram()
        focus(true)
        assertEquals(accent, paint().getRGB(1, canvas.height / 2))
    }

    @Test
    fun escapeLeavesFocusModeAndClearsHighlightThenNotifies() {
        canvas.select("users", "email", center = false)
        canvas.focusMode = true
        rec.events.clear()
        press(KeyEvent.VK_ESCAPE)
        assertFalse(canvas.focusMode)
        assertNull(canvas.highlightedColumn)
        assertEquals("users", canvas.selectedEntity)
        assertEquals(listOf("focus:false", "escape"), rec.events)
    }

    // ---- mouse ------------------------------------------------------------------------------------------

    @Test
    fun clickSelectsAndBackgroundClickClears() {
        val users = node("users")
        click(sx(users.x + 100), sy(users.y + 10))
        assertEquals("users", canvas.selectedEntity)
        click(5, 5)
        assertNull(canvas.selectedEntity)
        assertEquals(listOf("selection:users", "selection:null"), rec.events)
    }

    @Test
    fun doubleClickRequestsGoToSource() {
        val users = node("users")
        val x = sx(users.x + 100)
        val y = sy(users.rowCenterY(1))
        click(x, y)
        click(x, y, clicks = 2)
        assertEquals(listOf("selection:users", "goto:users"), rec.events)
    }

    @Test
    fun chevronTogglesCollapse() {
        val users = node("users")
        click(sx(users.x + 12), sy(users.y + users.headerH / 2))
        assertEquals(setOf("users"), canvas.collapsed())
        click(sx(users.x + 12), sy(users.y + users.headerH / 2))
        assertEquals(emptySet<String>(), canvas.collapsed())
        assertEquals(listOf("collapsed:users", "collapsed:"), rec.events)
    }

    @Test
    fun draggingANodeMovesItAndFiresOnce() {
        val posts = node("posts")
        val before = Point(posts.x, posts.y)
        val edge = canvas.sceneOrNull!!.edges.first { it.id == "posts#0" }
        canvas.sceneOrNull!!.ensureRoutes()
        val oldAx = edge.ax
        val z = canvas.zoom
        drag(sx(posts.x + 100), sy(posts.y + 10), sx(posts.x + 100) + 60, sy(posts.y + 10) + 40)
        assertEquals(before.x + 60 / z, posts.x, 0.01)
        assertEquals(before.y + 40 / z, posts.y, 0.01)
        assertEquals(1, rec.events.count { it == "positions" })
        assertEquals(Point(posts.x, posts.y), rec.lastPositions!!["posts"])
        assertEquals(db.entities.size, rec.lastPositions!!.size)
        // the edge follows the node
        canvas.sceneOrNull!!.ensureRoutes()
        assertEquals(oldAx + 60 / z, edge.ax, 0.01)
    }

    @Test
    fun draggingBackgroundOrMiddleButtonPans() {
        val v = canvas.viewportState
        val x = v.panX
        val y = v.panY
        drag(5, 5, 45, 25)
        assertEquals(x + 40, v.panX, 0.0)
        assertEquals(y + 20, v.panY, 0.0)
        val users = node("users")
        drag(sx(users.x + 100), sy(users.y + 10), sx(users.x + 100) - 30, sy(users.y + 10), MouseEvent.BUTTON2)
        assertEquals(x + 10, v.panX, 0.0)
        assertFalse("positions" in rec.events)
        assertNull(canvas.selectedEntity)
    }

    @Test
    fun wheelZoomsAroundCursorWithinLimits() {
        val v = canvas.viewportState
        val dx = v.toDiagramX(700.0)
        val dy = v.toDiagramY(300.0)
        fun wheel(rotation: Int) = canvas.dispatchEvent(
            MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, 0L, 0, 700, 300, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation),
        )
        val z = canvas.zoom
        wheel(-1)
        assertTrue(canvas.zoom > z)
        assertEquals(dx, v.toDiagramX(700.0), 1e-6)
        assertEquals(dy, v.toDiagramY(300.0), 1e-6)
        repeat(200) { wheel(-3) }
        assertEquals(DiagramCanvas.MAX_ZOOM, canvas.zoom, 0.0)
        repeat(200) { wheel(3) }
        assertEquals(DiagramCanvas.MIN_ZOOM, canvas.zoom, 0.0)
        assertEquals(dx, v.toDiagramX(700.0), 1e-6)
    }

    @Test
    fun panAndWheelPaintInteractiveFramesUntilTheySettle() {
        assertFalse(canvas.interactiveForTest)
        mouse(MouseEvent.MOUSE_PRESSED, 5, 5)
        mouse(MouseEvent.MOUSE_DRAGGED, 25, 15)
        assertTrue("dragging the background is an interaction", canvas.interactiveForTest)
        mouse(MouseEvent.MOUSE_RELEASED, 25, 15)
        assertFalse("release repaints at full quality", canvas.interactiveForTest)
        canvas.dispatchEvent(
            MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, 0L, 0, 700, 300, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1),
        )
        assertTrue(canvas.interactiveForTest)
        // the settle timer ends it (on the EDT)
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            javax.swing.SwingUtilities.invokeAndWait { done = !canvas.interactiveForTest }
            if (done) break
            Thread.sleep(20)
        }
        javax.swing.SwingUtilities.invokeAndWait { assertFalse(canvas.interactiveForTest) }
    }

    @Test
    fun interactiveFramesStillShowEdgesAndSymbols() {
        canvas.legendVisible = false
        canvas.viewportState.set(0.45, 40.0, 40.0)
        val scene = canvas.sceneOrNull!!
        scene.ensureRoutes()
        val e = scene.edges.first { it.id == "post_tags#1" }
        val bg = paint().getRGB(0, 0)
        canvas.interactiveForTest = true
        val img = paint()
        fun inked(dx: Double, dy: Double): Boolean {
            val x = sx(dx); val y = sy(dy)
            for (ox in -1..1) for (oy in -1..1) if (img.getRGB(x + ox, y + oy) != bg) return true
            return false
        }
        val mid = e.pointCount / 2
        assertTrue("edge line", inked(e.points[mid * 2], e.points[mid * 2 + 1]))
        // the bar of the parent's exactly-one symbol, above the line
        assertTrue("end symbol", inked(e.bx + e.bDir * CrowsFoot.BAR_1, e.by - CrowsFoot.HALF + 1.5))
    }

    @Test
    fun hoveringAnEdgeHighlightsItAndShowsFkTooltip() {
        val scene = canvas.sceneOrNull!!
        scene.ensureRoutes()
        val e = scene.edges.first { it.id == "post_tags#1" }
        val mid = e.pointCount / 2
        val x = sx(e.points[mid * 2])
        val y = sy(e.points[mid * 2 + 1]) + 3
        mouse(MouseEvent.MOUSE_MOVED, x, y)
        assertEquals("post_tags#1", canvas.hoveredEdgeId)
        val tip = canvas.getToolTipText(MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, x, y, 0, false))
        assertNotNull(tip)
        assertTrue(tip!!.contains("tag_id") && tip.contains("tags") && tip.contains("ON DELETE CASCADE") && tip.contains("ON UPDATE NO ACTION"))
        paint()
        mouse(MouseEvent.MOUSE_MOVED, 3, 3)
        assertNull(canvas.hoveredEdgeId)
        assertNull(canvas.getToolTipText(MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, 3, 3, 0, false)))

        val users = node("users")
        val colTip = canvas.getToolTipText(MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, sx(users.x + 90), sy(users.rowCenterY(0)), 0, false))
        assertTrue(colTip!!.contains("Primary key"))
    }

    @Test
    fun contextMenus() {
        val users = node("users")
        val menu = canvas.nodeMenu(users)
        val items = menu.components.filterIsInstance<JMenuItem>()
        assertEquals(listOf("Go to Source", "Collapse Columns", "Focus on Table"), items.map { it.text })
        items[0].doClick(0)
        items[1].doClick(0)
        assertEquals(setOf("users"), canvas.collapsed())
        assertEquals("Expand Columns", canvas.nodeMenu(users).components.filterIsInstance<JMenuItem>()[1].text)
        items[2].doClick(0)
        assertTrue(canvas.focusMode)
        assertEquals("users", canvas.selectedEntity)
        val again = canvas.nodeMenu(users).components.filterIsInstance<JMenuItem>()[2] as JCheckBoxMenuItem
        assertTrue(again.isSelected)
        again.doClick(0)
        assertFalse(canvas.focusMode)
        assertEquals(listOf("goto:users", "collapsed:users", "selection:users", "focus:true", "focus:false"), rec.events)

        val bg = canvas.backgroundMenu().components.filterIsInstance<JMenuItem>()
        assertEquals(listOf("Fit to Window", "Collapse All", "Expand All", "Show Legend"), bg.map { it.text })
        bg[1].doClick(0)
        assertEquals(db.entities.size, canvas.collapsed().size)
        bg[2].doClick(0)
        assertEquals(0, canvas.collapsed().size)
        bg[3].doClick(0)
        assertFalse(canvas.legendVisible)
        canvas.zoomIn()
        bg[0].doClick(0)
        val b = canvas.sceneOrNull!!.bounds()!!
        assertTrue(sx(b.x) >= 0 && sx(b.x + b.width) <= canvas.width)
    }

    // ---- keyboard ---------------------------------------------------------------------------------------

    @Test
    fun arrowKeysMoveBetweenConnectedTables() {
        press(KeyEvent.VK_RIGHT)
        val first = canvas.selectedEntity
        assertNotNull("without selection an arrow selects the node nearest the centre", first)
        canvas.select("posts", null, center = false)
        press(KeyEvent.VK_RIGHT)
        assertEquals("post_tags", canvas.selectedEntity)
        press(KeyEvent.VK_RIGHT)
        assertEquals("tags", canvas.selectedEntity)
        press(KeyEvent.VK_LEFT)
        assertEquals("post_tags", canvas.selectedEntity)
        press(KeyEvent.VK_LEFT)
        assertEquals("posts", canvas.selectedEntity)
        press(KeyEvent.VK_UP)
        assertEquals("users", canvas.selectedEntity)
        press(KeyEvent.VK_LEFT)
        assertEquals("profiles", canvas.selectedEntity)
        // no FK neighbour below posts: falls back to the nearest node in that direction
        canvas.select("posts", null, center = false)
        press(KeyEvent.VK_DOWN)
        assertEquals("posts_fts", canvas.selectedEntity)
    }

    @Test
    fun keyboardSelectionStaysVisible() {
        canvas.select("profiles", null, center = true)
        repeat(6) { canvas.zoomIn() }
        press(KeyEvent.VK_RIGHT)
        assertEquals("users", canvas.selectedEntity)
        val n = node("users")
        assertTrue(sx(n.x) >= 0 && sx(n.x) < canvas.width)
        assertTrue(sy(n.y) >= 0 && sy(n.y) < canvas.height)
    }

    @Test
    fun keyboardShortcuts() {
        canvas.select("users", null, center = false)
        rec.events.clear()
        press(KeyEvent.VK_ENTER)
        type('/')
        press(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK)
        press(KeyEvent.VK_F, InputEvent.META_DOWN_MASK)
        assertEquals(listOf("goto:users", "search", "search", "search"), rec.events)

        val z = canvas.zoom
        type('+')
        assertEquals(z * 1.25, canvas.zoom, 1e-9)
        type('=')
        type('-'); type('-')
        assertEquals(z, canvas.zoom, 1e-9)
        type('+'); type('0')
        assertEquals(z, canvas.zoom, 1e-9)

        rec.events.clear()
        press(KeyEvent.VK_SPACE)
        assertEquals(setOf("users"), canvas.collapsed())
        press(KeyEvent.VK_SPACE)
        type('f')
        assertTrue(canvas.focusMode)
        type('f')
        assertFalse(canvas.focusMode)
        press(KeyEvent.VK_ESCAPE)
        assertEquals(listOf("collapsed:users", "collapsed:", "focus:true", "focus:false", "escape"), rec.events)
        assertTrue(canvas.isFocusable)
    }

    @Test
    fun removedListenerGetsNothing() {
        canvas.removeCanvasListener(rec)
        canvas.select("users")
        assertEquals(emptyList<String>(), rec.events)
    }

    // ---- painting ---------------------------------------------------------------------------------------

    @Test
    fun paintsWithoutDiagramAndWhenStale() {
        val empty = DiagramCanvas()
        empty.setSize(300, 200)
        val image = paint(empty)
        assertEquals(UIManager.getColor("Panel.background").rgb, image.getRGB(150, 100))
        empty.stale = true
        paint(empty)
        DiagramCanvas().paint(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).createGraphics())

        val clean = paint()
        canvas.stale = true
        val stale = paint()
        var differs = false
        for (y in 8 until 45) for (x in 8 until 260) if (clean.getRGB(x, y) != stale.getRGB(x, y)) differs = true
        assertTrue("stale banner is painted in the top-left corner", differs)
        // the diagram is still visible
        val users = node("users")
        assertEquals(clean.getRGB(sx(users.x + 100), sy(users.y + 10)), stale.getRGB(sx(users.x + 100), sy(users.y + 10)))
        assertTrue(canvas.accessibleContext.accessibleDescription.contains("Stale"))
        assertEquals("Room ER diagram", canvas.accessibleContext.accessibleName)
    }

    @Test
    fun paintsEveryStateInEveryTheme() {
        for (theme in listOf(LIGHT, DARK)) {
            installTheme(theme)
            canvas.updateUI()
            canvas.setDiagram(db, DemoData.positions(), setOf("comments"))
            canvas.select("users", "email", center = false)
            canvas.hoveredEdgeId = "users#0"
            paint()
            canvas.focusMode = true
            paint()
            for (z in 0 until 20) { canvas.zoomOut(); paint() }
            for (z in 0 until 30) { canvas.zoomIn(); paint() }
            canvas.focusMode = false
        }
    }

    @Test
    fun themeIsResolvedAtPaintTime() {
        val users = node("users")
        val x = 2
        val y = 2
        assertEquals(Color(LIGHT["Panel.background"]!!).rgb, paint().getRGB(x, y))
        installTheme(DARK)
        val dark = paint()
        assertEquals(Color(DARK["Panel.background"]!!).rgb, dark.getRGB(x, y))
        assertEquals(Color(DARK["TextField.background"]!!).rgb, dark.getRGB(sx(users.x + users.width) - 4, sy(users.rowCenterY(2))))

        // font change (theme switch) re-measures the nodes
        val w = users.width
        UIManager.put("Label.font", FontUIResource(Font(Font.SANS_SERIF, Font.PLAIN, 18)))
        canvas.updateUI()
        paint()
        assertTrue(node("users").width > w)
        assertEquals(Size(node("users").width, node("users").height), DiagramMetrics.measure(db)["users"])

        // custom theme
        canvas.theme = object : DefaultDiagramTheme() {
            override val canvasBackground: Color get() = Color.MAGENTA
        }
        assertEquals(Color.MAGENTA.rgb, paint().getRGB(x, y))
    }

    @Test
    fun selectionIndicatorIsNotColourOnly() {
        // Corner handles are painted outside the plain outline: pixels just outside the corner change.
        canvas.legendVisible = false
        val users = node("users")
        val before = paint()
        canvas.select("users", null, center = false)
        val after = paint()
        val cx = sx(users.x) - 2
        val cy = sy(users.y) - 2
        assertNotEquals(before.getRGB(cx, cy), after.getRGB(cx, cy))
    }

    @Test
    fun largeDiagramPaintsFastEnough() {
        val big: Database = DemoData.large(100, 10)
        assertEquals(1000, big.tables.sumOf { it.columns.size })
        val c = DiagramCanvas()
        c.setSize(1600, 1000)
        val t0 = System.nanoTime()
        c.setDiagram(big, DemoData.gridPositions(big))
        val setMs = (System.nanoTime() - t0) / 1e6
        val image = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        fun measure(label: String, frames: Int = 40, step: (Int) -> Unit): Double {
            repeat(15) { step(it); c.paint(g) }
            val start = System.nanoTime()
            repeat(frames) { step(it); c.paint(g) }
            val ms = (System.nanoTime() - start) / 1e6 / frames
            println("[render perf] 100 tables / 1000 columns, 1600x1000, $label: ${"%.2f".format(ms)} ms/frame")
            return ms
        }

        println("[render perf] setDiagram (build render model + measure + route): ${"%.1f".format(setMs)} ms")
        val results = ArrayList<Double>()
        // Static = full-quality frame (selection, hover, settle after an interaction).
        // Interactive = frames painted while the user pans / wheel-zooms / drags (cheaper edges, see ScenePainter).
        val interactive = ArrayList<Double>()
        for (mode in listOf("static", "interactive")) {
            c.interactiveForTest = mode == "interactive"
            val r = if (mode == "interactive") interactive else results
            c.fitToWindow()
            r += measure("$mode, fit to window (zoom ${"%.2f".format(c.zoom)}), panning") { c.viewportState.panBy(if (it % 2 == 0) 7.0 else -7.0, 3.0) }
            c.viewportState.set(0.36, 20.0, 20.0)
            r += measure("$mode, zoom 0.36 (smallest zoom with full column text), panning") { c.viewportState.panBy(if (it % 2 == 0) 9.0 else -9.0, 0.0) }
            c.viewportState.set(1.0, -900.0, -700.0)
            r += measure("$mode, zoom 1.0, panning") { c.viewportState.panBy(if (it % 2 == 0) 11.0 else -11.0, 0.0) }
            c.viewportState.set(2.0, -1900.0, -1500.0)
            r += measure("$mode, zoom 2.0, panning") { c.viewportState.panBy(if (it % 2 == 0) 11.0 else -11.0, 0.0) }
            c.select("table_42", null, center = true)
            c.focusMode = true
            r += measure("$mode, focus mode") { c.viewportState.panBy(if (it % 2 == 0) 5.0 else -5.0, 0.0) }
            c.focusMode = false
            c.select(null)
            c.fitToWindow()
            r += measure("$mode, wheel zoom in/out") { c.viewportState.zoomAt(800.0, 500.0, if (it % 2 == 0) 1.12 else 1 / 1.12) }
        }
        c.interactiveForTest = false
        g.dispose()
        // 60 fps = 16.7 ms. Software rendering into a BufferedImage: allow generous slack. Shared CI runners
        // (GitHub Actions sets CI=true) are 3-6x slower and vary run to run, so there the timings are only printed.
        if (System.getenv("CI") == "true") return
        assertTrue("paint too slow: $results", results.all { it < 60.0 })
        assertTrue("interactive paint too slow: $interactive", interactive.all { it < 30.0 })
    }

    private companion object {
        val LIGHT = mapOf(
            "Panel.background" to 0xF7F8FA, "TextField.background" to 0xFFFFFF, "Label.foreground" to 0x000000,
            "Component.borderColor" to 0xC9CCD6, "Component.focusColor" to 0x3574F0, "List.selectionBackground" to 0x3574F0,
        )
        val DARK = mapOf(
            "Panel.background" to 0x2B2D30, "TextField.background" to 0x1E1F22, "Label.foreground" to 0xDFE1E5,
            "Component.borderColor" to 0x4E5157, "Component.focusColor" to 0x3574F0, "List.selectionBackground" to 0x2E436E,
        )

        fun installTheme(values: Map<String, Int>) {
            for ((k, v) in values) UIManager.put(k, ColorUIResource(Color(v)))
            UIManager.put("Label.font", FontUIResource(Font(Font.SANS_SERIF, Font.PLAIN, 13)))
        }
    }
}
