package com.oguzhanaslann.roomer.render

import com.oguzhanaslann.roomer.inference.CardinalityInference
import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.render.DiagramNavigation.Direction
import com.oguzhanaslann.roomer.render.DiagramNavigation.Spot
import com.oguzhanaslann.roomer.render.demo.DemoData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Font
import kotlin.math.abs

class ViewportTest {
    @Test
    fun zoomKeepsPointUnderCursor() {
        val v = Viewport()
        v.set(1.0, 40.0, -20.0)
        val dx = v.toDiagramX(300.0)
        val dy = v.toDiagramY(200.0)
        v.zoomAt(300.0, 200.0, 1.7)
        assertEquals(1.7, v.zoom, 1e-9)
        assertEquals(dx, v.toDiagramX(300.0), 1e-9)
        assertEquals(dy, v.toDiagramY(200.0), 1e-9)
        v.zoomAt(300.0, 200.0, 0.3)
        assertEquals(dx, v.toDiagramX(300.0), 1e-9)
        assertEquals(dy, v.toDiagramY(200.0), 1e-9)
    }

    @Test
    fun zoomIsClamped() {
        val v = Viewport()
        repeat(100) { v.zoomAt(10.0, 10.0, 1.5) }
        assertEquals(Viewport.MAX_ZOOM, v.zoom, 0.0)
        val dx = v.toDiagramX(10.0)
        v.zoomAt(10.0, 10.0, 1.5)
        assertEquals("clamped zoom must not drift the pan", dx, v.toDiagramX(10.0), 1e-9)
        repeat(100) { v.zoomAt(10.0, 10.0, 0.5) }
        assertEquals(Viewport.MIN_ZOOM, v.zoom, 0.0)
        v.set(Double.NaN, 0.0, 0.0)
        assertEquals(1.0, v.zoom, 0.0)
    }

    @Test
    fun fitShowsEverythingCentredAndNeverZoomsIn() {
        val v = Viewport()
        v.fit(100.0, 50.0, 2000.0, 1000.0, 800.0, 600.0, 20.0)
        assertEquals(0.38, v.zoom, 1e-9)
        assertTrue(v.toScreenX(100.0) >= 20.0 - 1e-6)
        assertTrue(v.toScreenX(2100.0) <= 780.0 + 1e-6)
        assertTrue(v.toScreenY(50.0) >= 20.0 - 1e-6)
        assertTrue(v.toScreenY(1050.0) <= 580.0 + 1e-6)
        assertEquals(400.0, v.toScreenX(1100.0), 1e-6)
        assertEquals(300.0, v.toScreenY(550.0), 1e-6)
        v.fit(0.0, 0.0, 10.0, 10.0, 800.0, 600.0, 20.0)
        assertEquals(1.0, v.zoom, 0.0)
    }

    @Test
    fun ensureVisiblePansMinimally() {
        val v = Viewport()
        assertFalse(v.ensureVisible(100.0, 100.0, 50.0, 50.0, 800.0, 600.0, 10.0))
        assertTrue(v.ensureVisible(900.0, 100.0, 50.0, 50.0, 800.0, 600.0, 10.0))
        assertEquals(790.0, v.toScreenX(950.0), 1e-9)
        assertEquals(0.0, v.panY, 0.0)
        assertTrue(v.ensureVisible(-30.0, -40.0, 50.0, 50.0, 800.0, 600.0, 10.0))
        assertEquals(10.0, v.toScreenX(-30.0), 1e-9)
        assertEquals(10.0, v.toScreenY(-40.0), 1e-9)
    }
}

class NavigationTest {
    private val rels = CardinalityInference.infer(DemoData.database())

    @Test
    fun focusSetIsSelectedPlusNeighboursInBothDirections() {
        assertEquals(setOf("posts", "users", "comments", "post_tags"), DiagramNavigation.focusSet("posts", rels))
        assertEquals(setOf("users", "profiles", "posts", "comments"), DiagramNavigation.focusSet("users", rels))
        assertEquals(setOf("tags", "post_tags"), DiagramNavigation.focusSet("tags", rels))
        assertEquals(setOf("posts_fts"), DiagramNavigation.focusSet("posts_fts", rels))
    }

    @Test
    fun sceneNeighboursMatchFocusSet() {
        val scene = DiagramScene(DemoData.database())
        for (n in scene.nodes) {
            val expected = DiagramNavigation.focusSet(n.name, rels) - n.name
            assertEquals(n.name, expected, n.neighbours.map { it.name }.toSet())
        }
    }

    private val a = Spot("a", 0.0, 0.0)
    private val right = Spot("right", 300.0, 20.0)
    private val farRight = Spot("farRight", 900.0, 0.0)
    private val below = Spot("below", 10.0, 400.0)
    private val nearRightStranger = Spot("stranger", 150.0, 0.0)
    private val all = listOf(a, right, farRight, below, nearRightStranger)

    @Test
    fun arrowPrefersConnectedTableInThatDirection() {
        val neighbours = setOf("right", "farRight", "below")
        assertEquals("right", DiagramNavigation.next(a, Direction.RIGHT, all, neighbours)?.name)
        assertEquals("below", DiagramNavigation.next(a, Direction.DOWN, all, neighbours)?.name)
    }

    @Test
    fun arrowFallsBackToNearestNodeInDirection() {
        assertEquals("stranger", DiagramNavigation.next(a, Direction.RIGHT, all, emptySet())?.name)
        // "below" is a neighbour but not to the right: the fallback still finds something on the right.
        assertEquals("stranger", DiagramNavigation.next(a, Direction.RIGHT, all, setOf("below"))?.name)
        assertNull(DiagramNavigation.next(a, Direction.LEFT, all, setOf("right")))
        assertNull(DiagramNavigation.next(a, Direction.UP, all, emptySet()))
    }

    @Test
    fun everyNodeOfDemoIsReachableWithArrows() {
        val scene = DiagramScene(DemoData.database())
        scene.measure(FontSet(Font(Font.SANS_SERIF, Font.PLAIN, 13)))
        scene.applyPositions(DemoData.positions())
        val spots = scene.nodes.map { Spot(it.name, it.centerX, it.centerY) }
        val reached = HashSet<String>()
        val queue = ArrayDeque(listOf(spots[0]))
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            if (!reached.add(s.name)) continue
            val neighbours = scene.byName[s.name]!!.neighbours.map { it.name }.toSet()
            for (d in Direction.entries) DiagramNavigation.next(s, d, spots, neighbours)?.let { queue += it }
        }
        assertEquals(scene.nodes.map { it.name }.toSet(), reached)
    }

    @Test
    fun nearest() {
        assertEquals("right", DiagramNavigation.nearest(all, 280.0, 0.0)?.name)
        assertNull(DiagramNavigation.nearest(emptyList(), 0.0, 0.0))
    }
}

class SceneTest {
    private val fonts = FontSet(Font(Font.SANS_SERIF, Font.PLAIN, 13))

    private fun scene(collapsed: Set<String> = emptySet()): DiagramScene {
        val s = DiagramScene(DemoData.database())
        s.measure(fonts)
        for (n in s.nodes) n.collapsed = n.name in collapsed
        s.applyPositions(DemoData.positions())
        s.ensureRoutes()
        return s
    }

    @Test
    fun rowsMirrorTheModel() {
        val db = DemoData.database()
        val s = scene()
        for (t in db.tables) {
            val n = s.byName[t.name]!!
            assertEquals(t.columns.map { it.name }, n.rows.map { it.fullName })
            assertEquals(t.columns.map { if (it.notNull) it.type else it.type + "?" }, n.rows.map { it.fullType })
        }
        val users = s.byName["users"]!!
        assertEquals(listOf("PK"), users.rows[0].badges.toList())
        assertEquals(listOf("UQ"), users.rows[1].badges.toList())
        assertEquals(emptyList<String>(), users.rows[2].badges.toList())
        assertEquals(listOf("FK", "IDX"), users.rows[4].badges.toList())
        val postTags = s.byName["post_tags"]!!
        assertEquals(listOf("PK", "FK"), postTags.rows[0].badges.toList())
        assertEquals(listOf("PK", "FK", "IDX"), postTags.rows[1].badges.toList())
        assertEquals("«view»", s.byName["user_post_counts"]!!.stereotype)
        assertEquals("«FTS4»", s.byName["posts_fts"]!!.stereotype)
        assertNull(users.stereotype)
    }

    @Test
    fun oneEdgePerForeignKeyWithInferredEnds() {
        val s = scene()
        assertEquals(7, s.edges.size)
        fun edge(id: String) = s.edges.first { it.id == id }
        assertEquals(EndKind.EXACTLY_ONE, edge("posts#0").parentEnd)
        assertEquals(EndKind.ZERO_OR_MANY, edge("posts#0").childEnd)
        assertEquals(EndKind.ZERO_OR_ONE, edge("users#0").parentEnd)
        assertTrue(edge("users#0").isSelf)
        assertEquals(EndKind.ZERO_OR_ONE, edge("profiles#0").childEnd)
        assertEquals(EndKind.EXACTLY_ONE, edge("profiles#0").parentEnd)
        assertEquals(EndKind.ZERO_OR_ONE, edge("comments#1").parentEnd)
    }

    @Test
    fun hitTesting() {
        val s = scene()
        val users = s.byName["users"]!!
        assertEquals(HitKind.CHEVRON, s.hit(users.x + 10, users.y + 10, 5.0).kind)
        val header = s.hit(users.x + 80, users.y + 10, 5.0)
        assertEquals(HitKind.NODE_HEADER, header.kind)
        assertEquals("users", header.node!!.name)
        val row = s.hit(users.x + 80, users.rowCenterY(3), 5.0)
        assertEquals(HitKind.ROW, row.kind)
        assertEquals("created_at", row.node!!.rows[row.row].column)
        assertEquals(HitKind.NONE, s.hit(-500.0, -500.0, 5.0).kind)

        val e = s.edges.first { it.id == "post_tags#1" }
        val mid = e.pointCount / 2
        val hit = s.hit(e.points[mid * 2], e.points[mid * 2 + 1] + 4.0, 6.0)
        assertEquals(HitKind.EDGE, hit.kind)
        assertEquals("post_tags#1", hit.edge!!.id)
        assertEquals(HitKind.NONE, s.hit(e.points[mid * 2], e.points[mid * 2 + 1] + 40.0, 6.0).kind)
    }

    @Test
    fun collapsedNodeHasNoRowHits() {
        val s = scene(setOf("users"))
        val users = s.byName["users"]!!
        assertEquals(users.headerH, users.height, 0.0)
        assertEquals(HitKind.NONE, s.hit(users.x + 80, users.y + users.headerH + 30, 2.0).kind)
        assertEquals(HitKind.CHEVRON, s.hit(users.x + 10, users.y + 10, 5.0).kind)
    }

    @Test
    fun edgesAttachToNodeBordersAtTheFkRows() {
        val s = scene()
        for (e in s.edges) {
            val c = e.child
            val p = e.parent
            assertTrue(e.id, e.ax == c.x || e.ax == c.x + c.width)
            assertTrue(e.id, e.bx == p.x || e.bx == p.x + p.width)
            assertTrue(e.id, e.ay > c.y && e.ay < c.y + c.height)
            assertTrue(e.id, e.by > p.y && e.by < p.y + p.height)
            assertTrue(e.id, abs(e.ay - c.rowCenterY(e.childRows[0])) <= c.rowH / 2 + 1)
            assertTrue(e.id, abs(e.by - p.rowCenterY(e.parentRows[0])) <= p.rowH / 2 + 1)
            assertEquals(e.ax, e.points[0], 0.0)
            assertEquals(e.bx, e.points[(e.pointCount - 1) * 2], 0.0)
        }
    }

    @Test
    fun selfLoopIsVisibleOutsideTheNodeAlsoWhenCollapsed() {
        for (collapsed in listOf(emptySet(), setOf("users"))) {
            val s = scene(collapsed)
            val e = s.edges.first { it.isSelf }
            val n = e.child
            assertTrue(e.maxX > n.x + n.width + EdgeRouter.STUB + 10)
            assertTrue(e.ay in n.y..(n.y + n.height))
            if (collapsed.isEmpty()) {
                assertFalse(e.bUp)
                assertTrue(abs(e.ay - e.by) >= 12.0)
                assertTrue(e.by in n.y..(n.y + n.height))
            } else {
                // Collapsed: the parent end sits on the top border, the loop wraps the top-right corner.
                assertTrue(e.bUp)
                assertEquals(n.y, e.by, 0.0)
                assertTrue(e.bx > n.x && e.bx < n.x + n.width)
                assertTrue(e.minY < n.y - EdgeRouter.STUB)
            }
        }
    }

    @Test
    fun collapsedEdgesAttachAtHeader() {
        val s = scene(setOf("posts", "post_tags"))
        val e = s.edges.first { it.id == "post_tags#0" }
        // at the header (possibly offset within it, to keep different end symbols apart)
        assertTrue(e.ay > e.child.y && e.ay < e.child.y + e.child.headerH)
        assertTrue(e.by > e.parent.y && e.by < e.parent.y + e.parent.headerH)
        val tags = s.edges.first { it.id == "post_tags#1" }
        assertEquals(tags.child.y + tags.child.headerH / 2, tags.ay, 0.01)
    }

    @Test
    fun parallelEdgesBetweenTheSamePairDoNotCoincide() {
        val db = DemoData.large(tables = 2, columnsPerTable = 4).let { d ->
            // table_1 gets two FKs to table_0
            val t1 = d.tables[1]
            val fk = t1.foreignKeys[0]
            d.copy(tables = listOf(d.tables[0], t1.copy(foreignKeys = listOf(fk, fk.copy(columns = listOf("column_number_2"))))))
        }
        for (collapsed in listOf(false, true)) {
            val s = DiagramScene(db)
            s.measure(fonts)
            for (n in s.nodes) n.collapsed = collapsed
            s.applyPositions(mapOf("table_0" to Point(0.0, 0.0), "table_1" to Point(500.0, 100.0)))
            s.ensureRoutes()
            val (e1, e2) = s.edges
            assertTrue("collapsed=$collapsed", abs(e1.ay - e2.ay) >= 6.0)
            if (collapsed) assertTrue(abs(e1.by - e2.by) >= 6.0)
        }
    }

    @Test
    fun differentCardinalitySymbolsAtTheSameAnchorAreSeparated() {
        val s = scene()
        // comments.author_id (nullable -> zero or one) and profiles.user_id (-> exactly one) both end at users.id, left side
        val a = s.edges.first { it.id == "comments#1" }
        val b = s.edges.first { it.id == "profiles#0" }
        assertEquals(a.bx, b.bx, 0.0)
        assertTrue(abs(a.by - b.by) >= 2 * CrowsFoot.HALF)
    }

    @Test
    fun compositeUniqueIndexGivesIdxBadgeAndExplainingTooltip() {
        val db = DemoData.database().let { d ->
            d.copy(tables = d.tables.map { t ->
                if (t.name != "comments") t else t.copy(indices = t.indices + com.oguzhanaslann.roomer.model.Index(
                    "index_comments_post_text", unique = true, columns = listOf("post_id", "text"),
                ))
            })
        }
        val s = DiagramScene(db)
        s.measure(fonts)
        val comments = s.byName["comments"]!!
        val text = comments.rows[comments.rowOf("text")]
        assertEquals(listOf("IDX"), text.badges.toList())
        assertTrue(text.tooltip, text.tooltip.contains("IDX: part of composite unique index index_comments_post_text (post_id, text)"))
        assertTrue(text.tooltip, text.tooltip.contains("unique only in combination"))
        assertFalse(text.tooltip, text.tooltip.contains("UQ"))
        val postId = comments.rows[comments.rowOf("post_id")]
        assertEquals(listOf("FK", "IDX"), postId.badges.toList())
        assertEquals(listOf("UQ"), s.byName["tags"]!!.rows[1].badges.toList())
    }

    @Test
    fun symbolsSharingAnAnchorAreOrderedByWhereTheirEdgesGo() {
        val s = scene()
        // users.id, right side: the self-loop's parent end turns down at once (to manager_id) and must be BELOW the
        // end of posts.author_id, which goes further out first; otherwise the two edges cross.
        val self = s.edges.first { it.isSelf }
        val posts = s.edges.first { it.id == "posts#0" }
        assertEquals(self.bx, posts.bx, 0.0)
        assertTrue(self.by > posts.by + 2 * CrowsFoot.HALF)
        // both stay on the node border
        val users = s.byName["users"]!!
        for (y in listOf(self.by, posts.by)) assertTrue(y - CrowsFoot.HALF > users.y && y + CrowsFoot.HALF < users.y + users.height)
    }

    @Test
    fun collapsedNodeSymbolsStayOnTheBorderAndApart() {
        val s = scene(s0().nodes.map { it.name }.toSet())
        for (n in s.nodes) {
            val ends = ArrayList<Pair<Double, EndKind>>()
            for (e in s.edges) {
                if (e.child === n && e.aDir > 0) ends += e.ay to e.childEnd
                if (e.parent === n && !e.bUp && e.bDir > 0) ends += e.by to e.parentEnd
            }
            for ((y, _) in ends) assertTrue(n.name, y - CrowsFoot.HALF >= n.y && y + CrowsFoot.HALF <= n.y + n.height)
            for ((y1, k1) in ends) for ((y2, k2) in ends) {
                if (k1 != k2) assertTrue("${n.name}: $k1 / $k2", abs(y1 - y2) >= 2 * CrowsFoot.HALF)
            }
        }
    }

    private fun s0() = scene()

    @Test
    fun missingPositionsArePlacedWithoutOverlap() {
        val s = DiagramScene(DemoData.database())
        s.measure(fonts)
        s.applyPositions(mapOf("users" to Point(0.0, 0.0), "posts" to Point(0.0, 300.0), "ghost" to Point(1.0, 1.0)))
        val nodes = s.nodes
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]
            val b = nodes[j]
            val overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
            assertFalse("${a.name} overlaps ${b.name}", overlap)
        }
        val users = s.byName["users"]!!
        assertTrue(s.byName["tags"]!!.x > users.x + users.width)

        val none = DiagramScene(DemoData.database())
        none.measure(fonts)
        none.applyPositions(emptyMap())
        assertNotNull(none.bounds())
    }

    @Test
    fun longNamesAreTruncatedWithEllipsisAndWidthIsCapped() {
        val db = DemoData.large(1, 3).let { d ->
            val t = d.tables[0]
            d.copy(tables = listOf(t.copy(
                name = "a_very_long_table_name_".repeat(6),
                columns = t.columns + com.oguzhanaslann.roomer.model.Column("x".repeat(200), "TEXT", true),
            )))
        }
        val s = DiagramScene(db)
        s.measure(fonts)
        val n = s.nodes[0]
        assertTrue(n.title.endsWith(FontSet.ELLIPSIS))
        assertTrue(n.rows.last().name.endsWith(FontSet.ELLIPSIS))
        assertTrue(n.width <= 420.0)
        assertTrue(n.tooltip.contains("a_very_long_table_name_a_very_long_table_name_"))
        assertTrue(n.rows.last().tooltip.contains("x".repeat(200)))
    }

    @Test
    fun tooltips() {
        val s = scene()
        val edge = s.edges.first { it.id == "posts#0" }.tooltip
        assertTrue(edge, edge.contains("author_id") && edge.contains("users") && edge.contains("(id)"))
        assertTrue(edge, edge.contains("ON DELETE CASCADE") && edge.contains("ON UPDATE CASCADE"))
        val users = s.byName["users"]!!
        val id = users.rows[0].tooltip
        assertTrue(id, id.contains("INTEGER NOT NULL") && id.contains("Primary key") && id.contains("auto-generated"))
        val created = users.rows[3].tooltip
        assertTrue(created, created.contains("DEFAULT CURRENT_TIMESTAMP"))
        val email = users.rows[1].tooltip
        assertTrue(email, email.contains("UQ: unique (unique index index_users_email)"))
        val manager = users.rows[4].tooltip
        assertTrue(manager, manager.contains("(nullable)") && manager.contains("users(id)") && manager.contains("IDX: indexed (index index_users_manager_id)"))
        val view = s.byName["user_post_counts"]!!.rows[0].tooltip
        assertTrue(view, view.contains("LEFT JOIN posts"))
    }
}
