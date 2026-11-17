package com.oguzhanaslann.roomer.render

import com.oguzhanaslann.roomer.layout.Point
import com.oguzhanaslann.roomer.layout.Size
import com.oguzhanaslann.roomer.model.Database
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Callbacks from the canvas. All are invoked on the EDT. Every method has an empty default. */
interface DiagramCanvasListener {
    /** Double-click, Enter or context menu "Go to Source" on a table/view node. */
    fun goToSourceRequested(entityName: String) {}
    /** A node was dragged (fired once, when the drag ends). [positions] holds every node. */
    fun positionsChanged(positions: Map<String, Point>) {}
    /**
     * The set of collapsed tables changed; [collapsed] is the complete new set. Fires once per actual change, both
     * from the canvas UI (chevron, Space, context menu) and from programmatic calls ([DiagramCanvas.setCollapsed],
     * [DiagramCanvas.collapseAll], [DiagramCanvas.expandAll], and [DiagramCanvas.select] with a column of a
     * collapsed table, which expands it). Never fires for a no-op call and never from [DiagramCanvas.setDiagram].
     */
    fun collapsedChanged(collapsed: Set<String>) {}
    /**
     * The selected entity changed (null = nothing selected). Fires once per actual change, from the canvas UI
     * (click, arrow keys, context menu) and from [DiagramCanvas.select], and when [DiagramCanvas.setDiagram] /
     * [DiagramCanvas.clearDiagram] drops the selection. Never fires for a no-op.
     */
    fun selectionChanged(entityName: String?) {}
    /**
     * Focus mode was turned on or off. Fires once per actual change, from the canvas UI (F, Esc, context menu) and
     * from [DiagramCanvas.focusMode] / `select(null)`, and when [DiagramCanvas.setDiagram] /
     * [DiagramCanvas.clearDiagram] ends it. Never fires for a no-op.
     */
    fun focusModeChanged(enabled: Boolean) {}
    /** `/` or Ctrl/Cmd+F was pressed while the canvas had keyboard focus. */
    fun searchRequested() {}
    /** Esc was pressed (after the canvas has already left focus mode / cleared its highlight). */
    fun escapePressed() {}
}

/**
 * Interactive ER diagram (spec §3.3, §3.4). Pure Swing/Java2D; all methods must be called on the EDT
 * except [DiagramMetrics.measure]. The canvas only reads the model and the positions it is given.
 *
 * PUBLIC API CONTRACT — the tool window is written against these signatures. Keep them.
 *
 * Mouse: wheel = zoom around the cursor ([MIN_ZOOM]..[MAX_ZOOM]); drag background or middle-button drag = pan;
 * click = select (click on the background clears the selection); drag a node = move it; double-click a node =
 * go to source; click the chevron in a node header = collapse/expand; right-click = context menu.
 *
 * Keyboard (canvas focused):
 * - Arrow keys: move the selection to the connected table (FK neighbour, either direction) that lies best in
 *   that direction; without such a neighbour, to the nearest table in that direction; without a selection,
 *   select the table nearest the viewport centre.
 * - Enter: go to source. Space: collapse/expand the selected table. F: toggle focus mode.
 * - `/` or Ctrl/Cmd+F: [DiagramCanvasListener.searchRequested].
 * - `+` / `=`: zoom in, `-`: zoom out, `0`: fit to window.
 * - Esc: leave focus mode and clear the column/edge highlight (the selection is kept), then
 *   [DiagramCanvasListener.escapePressed].
 *
 * Events: [DiagramCanvasListener.selectionChanged], [DiagramCanvasListener.focusModeChanged] and
 * [DiagramCanvasListener.collapsedChanged] fire whenever the respective state actually changes, whether through
 * the canvas UI or through [select] / [focusMode] / [setCollapsed] / [collapseAll] / [expandAll] /
 * [setDiagram] (selection or focus dropped because the table is gone). They never fire for a no-op call, so
 * calling these methods from a listener cannot loop. [setDiagram] never fires `collapsedChanged`.
 */
class DiagramCanvas : JComponent() {

    private var scene: DiagramScene? = null
    private val viewport = Viewport()
    private val state = PaintState()
    private val painter = ScenePainter()
    private var fontSet: FontSet? = null
    private var pendingFit = false
    private var focusOn = false
    private var viewportBeforeFocus: Viewport? = null
    private val listeners = ArrayList<DiagramCanvasListener>()
    private var accessible: AccessibleContext? = null
    /** Set while a mouse press selects a node: refocusing must not move the viewport under the cursor. */
    private var keepViewportOnRefocus = false

    /** Ends an interaction (pan / wheel zoom / drag) shortly after the last event: repaint at full quality. */
    private val settleTimer = Timer(INTERACTION_SETTLE_MS) { endInteraction() }.apply { isRepeats = false }

    /** Colours and font source. Read at paint time; the default follows `UIManager` (the IDE theme). */
    var theme: DiagramTheme = DefaultDiagramTheme()
        set(value) {
            field = value
            fontSet = null
            repaint()
        }

    /** Whether the legend overlay (node kinds, markers, crow's-foot ends) is painted in the bottom-right corner. */
    var legendVisible: Boolean
        get() = state.legendVisible
        set(value) {
            if (state.legendVisible != value) {
                state.legendVisible = value
                repaint()
            }
        }

    /** Current zoom factor (1.0 = 100 %), always within [MIN_ZOOM]..[MAX_ZOOM]. */
    val zoom: Double get() = viewport.zoom

    init {
        isOpaque = true
        isFocusable = true
        val input = Input()
        addMouseListener(input)
        addMouseMotionListener(input)
        addMouseWheelListener(input)
        addKeyListener(input)
        addFocusListener(input)
        ToolTipManager.sharedInstance().registerComponent(this)
        getAccessibleContext().accessibleName = "Room ER diagram"
        updateAccessibleDescription()
    }

    /** Currently shown database, or null if nothing is shown. */
    val database: Database? get() = scene?.database

    /**
     * Shows [database] with nodes at [positions] (top-left, diagram space; keyed by entity name).
     * @param collapsed entities whose column list is collapsed
     * @param preserveViewport true on reload (FR-07): keep zoom, pan and — if it still exists — selection.
     *   false on first show / database switch / reset layout: fit the diagram to the window.
     * Clears the stale flag.
     *
     * Entities without an entry in [positions] are placed to the right of the others. With
     * [preserveViewport] focus mode also survives while the selected table still exists. If the canvas has
     * no size yet, fitting happens on the first resize/paint.
     */
    fun setDiagram(
        database: Database,
        positions: Map<String, Point>,
        collapsed: Set<String> = emptySet(),
        preserveViewport: Boolean = false,
    ) {
        val previous = scene
        val oldSelection = state.selected?.name
        val oldColumn = state.selected?.let { n -> n.rows.getOrNull(state.highlightRow)?.column }
        val oldFocus = focusOn

        val s = DiagramScene(database)
        s.measure(fonts())
        for (n in s.nodes) n.collapsed = n.name in collapsed
        s.applyPositions(positions)
        scene = s
        state.hoverEdge = null
        state.stale = false

        val keep = preserveViewport && previous != null
        val selected = if (keep && oldSelection != null) s.byName[oldSelection] else null
        state.selected = selected
        state.highlightRow = if (selected != null && oldColumn != null && selected.showsRows) selected.rowOf(oldColumn) else -1
        if (selected != null) s.bringToFront(selected)
        if (selected != null && focusOn) {
            state.focusSet = focusSetOf(selected)
        } else {
            focusOn = false
            state.focusSet = null
            viewportBeforeFocus = null
        }
        if (!keep) requestFit()

        updateAccessibleDescription()
        repaint()
        if (oldSelection != selected?.name) fire { it.selectionChanged(selected?.name) }
        if (oldFocus != focusOn) fire { it.focusModeChanged(focusOn) }
    }

    /** Removes the diagram (nothing is painted). */
    fun clearDiagram() {
        if (scene == null) return
        val hadSelection = state.selected != null
        val hadFocus = focusOn
        scene = null
        state.selected = null
        state.highlightRow = -1
        state.hoverEdge = null
        state.focusSet = null
        state.stale = false
        focusOn = false
        viewportBeforeFocus = null
        pendingFit = false
        updateAccessibleDescription()
        repaint()
        if (hadSelection) fire { it.selectionChanged(null) }
        if (hadFocus) fire { it.focusModeChanged(false) }
    }

    /** When true the diagram is painted with a visible "stale" marker (FR-41). */
    var stale: Boolean
        get() = state.stale
        set(value) {
            if (state.stale != value) {
                state.stale = value
                updateAccessibleDescription()
                repaint()
            }
        }

    /** Current top-left position of every node. */
    fun positions(): Map<String, Point> = scene?.positions() ?: emptyMap()

    fun collapsed(): Set<String> = scene?.collapsed() ?: emptySet()

    fun setCollapsed(entityName: String, collapsed: Boolean) {
        val s = scene ?: return
        val n = s.byName[entityName] ?: return
        if (n.collapsed == collapsed) return
        n.collapsed = collapsed
        afterCollapseChange(s)
    }

    fun collapseAll() = setAllCollapsed(true)
    fun expandAll() = setAllCollapsed(false)

    private fun setAllCollapsed(collapsed: Boolean) {
        val s = scene ?: return
        var changed = false
        for (n in s.nodes) {
            if (n.collapsed != collapsed) {
                n.collapsed = collapsed
                changed = true
            }
        }
        if (changed) afterCollapseChange(s)
    }

    private fun afterCollapseChange(s: DiagramScene) {
        if (state.selected?.collapsed == true) state.highlightRow = -1
        state.hoverEdge = null
        s.invalidateRoutes()
        repaint()
        val set = s.collapsed()
        fire { it.collapsedChanged(set) }
    }

    val selectedEntity: String? get() = state.selected?.name

    /**
     * Selects and highlights [entityName] (and [column] within it, if given); with [center] scrolls it to the
     * middle of the viewport. `select(null)` clears selection and highlight. Used by search (FR-30).
     *
     * A collapsed table is expanded when a [column] in it is requested (fires `collapsedChanged`). In focus
     * mode the focus moves to the new table (and the viewport shows the new focus set); `select(null)` leaves
     * focus mode. An unknown [entityName] is ignored.
     */
    fun select(entityName: String?, column: String? = null, center: Boolean = true) {
        val s = scene ?: return
        if (entityName == null) {
            val had = state.selected != null
            state.selected = null
            state.highlightRow = -1
            if (focusOn) focusMode = false
            repaint()
            if (had) fire { it.selectionChanged(null) }
            return
        }
        val n = s.byName[entityName] ?: return
        fonts()
        val row = if (column != null) n.rowOf(column) else -1
        var expanded = false
        if (row >= 0 && n.collapsed) {
            n.collapsed = false
            s.invalidateRoutes()
            expanded = true
        }
        val changed = state.selected !== n
        state.selected = n
        state.highlightRow = row
        s.bringToFront(n)
        if (focusOn) {
            if (changed) refocus(n)
        } else if (center && hasSize()) {
            val cy = if (row >= 0 && n.height * viewport.zoom > height) n.rowCenterY(row) else n.centerY
            viewport.centerOn(n.centerX, cy, width.toDouble(), height.toDouble())
            pendingFit = false
        }
        updateAccessibleDescription()
        repaint()
        if (expanded) {
            val set = s.collapsed()
            fire { it.collapsedChanged(set) }
        }
        if (changed) fire { it.selectionChanged(n.name) }
    }

    /**
     * Focus mode (FR-31): selected table + direct FK neighbours emphasised, the rest dimmed. No-op without selection.
     *
     * Turning it on remembers the viewport and brings the focus set into view; turning it off restores that viewport.
     */
    var focusMode: Boolean
        get() = focusOn
        set(value) {
            if (value == focusOn) return
            if (value) {
                val n = state.selected ?: return
                viewportBeforeFocus = viewport.copy()
                focusOn = true
                refocus(n)
            } else {
                focusOn = false
                state.focusSet = null
                viewportBeforeFocus?.let { viewport.copyFrom(it) }
                viewportBeforeFocus = null
            }
            repaint()
            fire { it.focusModeChanged(focusOn) }
        }

    fun zoomIn() = zoomAtCentre(ZOOM_STEP)
    fun zoomOut() = zoomAtCentre(1 / ZOOM_STEP)

    private fun zoomAtCentre(factor: Double) {
        if (scene == null) return
        applyPendingFit()
        viewport.zoomAt(width / 2.0, height / 2.0, factor)
        repaint()
    }

    fun fitToWindow() {
        if (scene == null) return
        requestFit()
        repaint()
    }

    fun addCanvasListener(listener: DiagramCanvasListener) {
        listeners += listener
    }

    fun removeCanvasListener(listener: DiagramCanvasListener) {
        listeners -= listener
    }

    // ---- internals ---------------------------------------------------------------------------------------

    private inline fun fire(call: (DiagramCanvasListener) -> Unit) {
        if (listeners.isEmpty()) return
        for (l in listeners.toTypedArray()) call(l)
    }

    private fun hasSize() = width > 0 && height > 0

    /** Font set for the current theme font; re-measures the scene when the font changed (theme switch). */
    private fun fonts(): FontSet {
        val base = theme.font
        var f = fontSet
        if (f == null || f.base != base) {
            f = FontSets.of(base)
            fontSet = f
            scene?.measure(f)
        }
        return f
    }

    private fun requestFit() {
        pendingFit = true
        applyPendingFit()
    }

    private fun applyPendingFit() {
        if (!pendingFit || !hasSize()) return
        val s = scene ?: return
        fonts()
        val b = s.bounds() ?: return
        fitAvoidingLegend(b, s.nodes)
        pendingFit = false
    }

    /**
     * Fits [b] into the canvas. If the legend would then cover one of [nodes], fits again into the canvas minus the
     * legend's width (right) or height (bottom), whichever leaves the larger zoom.
     */
    private fun fitAvoidingLegend(b: java.awt.geom.Rectangle2D.Double, nodes: Collection<NodeModel>) {
        val w = width.toDouble()
        val h = height.toDouble()
        viewport.fit(b.x, b.y, b.width, b.height, w, h, FIT_MARGIN)
        if (!state.legendVisible) return
        val legend = painter.legendBounds(width, height, fonts()) ?: return
        val covered = nodes.any { n ->
            viewport.toScreenX(n.x) < legend.x + legend.width && viewport.toScreenX(n.x + n.width) > legend.x &&
                viewport.toScreenY(n.y) < legend.y + legend.height && viewport.toScreenY(n.y + n.height) > legend.y
        }
        if (!covered) return
        val gap = ScenePainter.LEGEND_INSET
        val besideW = max(1.0, legend.x - gap)
        val aboveH = max(1.0, legend.y - gap)
        val zBeside = min((besideW - 2 * FIT_MARGIN) / b.width, (h - 2 * FIT_MARGIN) / b.height)
        val zAbove = min((w - 2 * FIT_MARGIN) / b.width, (aboveH - 2 * FIT_MARGIN) / b.height)
        if (zBeside >= zAbove) viewport.fit(b.x, b.y, b.width, b.height, besideW, h, FIT_MARGIN)
        else viewport.fit(b.x, b.y, b.width, b.height, w, aboveH, FIT_MARGIN)
    }

    private fun focusSetOf(n: NodeModel): Set<NodeModel> = HashSet<NodeModel>(n.neighbours.size * 2 + 2).also {
        it += n
        it.addAll(n.neighbours)
    }

    /** Recomputes the focus set around [n] and makes sure it is on screen at a readable zoom. */
    private fun refocus(n: NodeModel) {
        val s = scene ?: return
        val set = focusSetOf(n)
        state.focusSet = set
        if (!hasSize() || keepViewportOnRefocus) return
        applyPendingFit()
        val f = fonts()
        val b = s.bounds(set) ?: return
        val w = width.toDouble()
        val h = height.toDouble()
        val visible = viewport.toScreenX(b.x) >= 0 && viewport.toScreenX(b.x + b.width) <= w &&
            viewport.toScreenY(b.y) >= 0 && viewport.toScreenY(b.y + b.height) <= h
        if (!visible || !painter.isDetailed(viewport.zoom, f)) fitAvoidingLegend(b, set)
    }

    /** The user is panning / zooming / dragging: paint cheaper frames until it settles. */
    private fun markInteraction() {
        state.interactive = true
        settleTimer.restart()
    }

    private fun endInteraction() {
        settleTimer.stop()
        if (state.interactive) {
            state.interactive = false
            repaint()
        }
    }

    private fun toggleCollapsed(n: NodeModel) {
        if (n.rows.isEmpty()) return
        setCollapsed(n.name, !n.collapsed)
    }

    private fun hitAt(sx: Int, sy: Int): Hit {
        val s = scene ?: return Hit.NONE
        fonts()
        applyPendingFit()
        return s.hit(viewport.toDiagramX(sx.toDouble()), viewport.toDiagramY(sy.toDouble()), EDGE_HIT_PX / viewport.zoom)
    }

    private fun ensureNodeVisible(n: NodeModel) {
        if (!hasSize()) return
        viewport.ensureVisible(n.x, n.y, n.width, n.height, width.toDouble(), height.toDouble(), FIT_MARGIN)
    }

    private fun moveSelection(direction: DiagramNavigation.Direction) {
        val s = scene ?: return
        if (s.nodes.isEmpty()) return
        fonts()
        applyPendingFit()
        val spots = s.nodes.map { DiagramNavigation.Spot(it.name, it.centerX, it.centerY) }
        val current = state.selected
        val target = if (current == null) {
            DiagramNavigation.nearest(spots, viewport.toDiagramX(width / 2.0), viewport.toDiagramY(height / 2.0))
        } else {
            val neighbours = HashSet<String>()
            for (nb in current.neighbours) neighbours += nb.name
            DiagramNavigation.next(spots[current.index], direction, spots, neighbours)
        } ?: return
        select(target.name, null, center = false)
        if (!focusOn) s.byName[target.name]?.let { ensureNodeVisible(it) }
        repaint()
    }

    private fun handleEscape() {
        if (focusOn) focusMode = false
        if (state.highlightRow >= 0 || state.hoverEdge != null) {
            state.highlightRow = -1
            state.hoverEdge = null
            repaint()
        }
        fire { it.escapePressed() }
    }

    private fun updateAccessibleDescription() {
        val ctx = getAccessibleContext()
        val db = scene?.database
        ctx.accessibleDescription = if (db == null) {
            "No schema loaded"
        } else buildString {
            append("Entity relationship diagram of ").append(db.simpleName).append(", version ").append(db.version)
            append(": ").append(db.tables.size).append(" tables, ").append(db.views.size).append(" views, ")
            append(scene?.edges?.size ?: 0).append(" foreign keys.")
            state.selected?.let { append(" Selected: ").append(it.name).append('.') }
            if (state.stale) append(" Stale: the schema file has errors.")
            append(" Arrow keys move between connected tables, Enter opens the source.")
        }
    }

    internal fun nodeMenu(n: NodeModel): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Go to Source").apply { addActionListener { fire { it.goToSourceRequested(n.name) } } })
        menu.add(JMenuItem(if (n.collapsed) "Expand Columns" else "Collapse Columns").apply {
            isEnabled = n.rows.isNotEmpty()
            addActionListener { toggleCollapsed(n) }
        })
        val focused = focusOn && state.selected === n
        menu.add(JCheckBoxMenuItem("Focus on Table", focused).apply {
            addActionListener {
                if (focused) {
                    focusMode = false
                } else {
                    select(n.name, null, center = false)
                    focusMode = true
                }
            }
        })
        return menu
    }

    internal fun backgroundMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Fit to Window").apply { addActionListener { fitToWindow() } })
        menu.addSeparator()
        menu.add(JMenuItem("Collapse All").apply { addActionListener { collapseAll() } })
        menu.add(JMenuItem("Expand All").apply { addActionListener { expandAll() } })
        menu.addSeparator()
        menu.add(JCheckBoxMenuItem("Show Legend", legendVisible).apply { addActionListener { legendVisible = !legendVisible } })
        return menu
    }

    // Test / demo hooks.
    internal val sceneOrNull: DiagramScene? get() = scene
    internal val viewportState: Viewport get() = viewport
    internal val highlightedColumn: String?
        get() = state.selected?.rows?.getOrNull(state.highlightRow)?.column
    /** Simulates an ongoing pan/zoom (perf test); the settle timer is not involved. */
    internal var interactiveForTest: Boolean
        get() = state.interactive
        set(value) {
            settleTimer.stop()
            state.interactive = value
        }
    internal val hasKeyboardFocusForTest: Boolean get() = state.hasKeyboardFocus
    internal var hoveredEdgeId: String?
        get() = state.hoverEdge?.id
        set(value) {
            state.hoverEdge = scene?.edges?.firstOrNull { it.id == value }
            repaint()
        }

    // ---- Swing overrides --------------------------------------------------------------------------------

    override fun paintComponent(g: Graphics) {
        val f = fonts()
        applyPendingFit()
        painter.paint(g as Graphics2D, width, height, scene, viewport, state, Palette(theme), f)
    }

    /** Look-and-feel / theme switch: drop cached metrics; they are rebuilt from the theme on the next paint. */
    override fun updateUI() {
        super.updateUI()
        fontSet = null
        revalidate()
        repaint()
    }

    /** Every size change ends up here (`setSize`, `setBounds`, layout managers): runs a fit deferred until a size exists. */
    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        applyPendingFit()
    }

    override fun getPreferredSize(): Dimension =
        if (isPreferredSizeSet) super.getPreferredSize() else Dimension(800, 600)

    override fun getToolTipText(event: MouseEvent): String? {
        val hit = hitAt(event.x, event.y)
        return when (hit.kind) {
            HitKind.ROW -> hit.node!!.rows[hit.row].tooltip
            HitKind.NODE_HEADER, HitKind.CHEVRON -> hit.node!!.tooltip
            HitKind.EDGE -> hit.edge!!.tooltip
            HitKind.NONE -> null
        }
    }

    override fun getAccessibleContext(): AccessibleContext {
        return accessible ?: AccessibleCanvas().also { accessible = it }
    }

    private inner class AccessibleCanvas : AccessibleJComponent() {
        override fun getAccessibleRole(): AccessibleRole = AccessibleRole.CANVAS
    }

    // ---- input ------------------------------------------------------------------------------------------

    private inner class Input : MouseAdapter(), KeyListener, FocusListener {
        private var pressX = 0
        private var pressY = 0
        private var lastX = 0
        private var lastY = 0
        private var panning = false
        private var moved = false
        private var backgroundPress = false
        private var dragNode: NodeModel? = null
        private var dragStartX = 0.0
        private var dragStartY = 0.0

        override fun mousePressed(e: MouseEvent) {
            requestFocusInWindow()
            pressX = e.x; pressY = e.y; lastX = e.x; lastY = e.y
            moved = false; panning = false; backgroundPress = false; dragNode = null
            if (scene == null) return
            if (e.isPopupTrigger) { showPopup(e); return }
            if (SwingUtilities.isMiddleMouseButton(e)) {
                panning = true
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                return
            }
            if (!SwingUtilities.isLeftMouseButton(e)) return
            val hit = hitAt(e.x, e.y)
            val n = hit.node
            when {
                hit.kind == HitKind.CHEVRON && n != null -> toggleCollapsed(n)
                n != null -> {
                    // In focus mode clicking a dimmed table refocuses on it without moving the viewport, so the
                    // table stays under the cursor (and can be dragged right away).
                    keepViewportOnRefocus = true
                    try {
                        select(n.name, null, center = false)
                    } finally {
                        keepViewportOnRefocus = false
                    }
                    dragNode = n
                    dragStartX = n.x
                    dragStartY = n.y
                }
                else -> {
                    panning = true
                    backgroundPress = true
                }
            }
        }

        override fun mouseDragged(e: MouseEvent) {
            val s = scene ?: return
            if (!moved && abs(e.x - pressX) + abs(e.y - pressY) < DRAG_THRESHOLD) return
            moved = true
            val n = dragNode
            if (n != null || panning) markInteraction()
            if (n != null) {
                n.x = dragStartX + (e.x - pressX) / viewport.zoom
                n.y = dragStartY + (e.y - pressY) / viewport.zoom
                s.invalidateRoutes()
                repaint()
            } else if (panning) {
                viewport.panBy((e.x - lastX).toDouble(), (e.y - lastY).toDouble())
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                repaint()
            }
            lastX = e.x; lastY = e.y
        }

        override fun mouseReleased(e: MouseEvent) {
            val s = scene
            val n = dragNode
            val wasBackground = backgroundPress
            dragNode = null; panning = false; backgroundPress = false
            cursor = Cursor.getDefaultCursor()
            if (moved) endInteraction()
            if (s == null) return
            if (e.isPopupTrigger) { showPopup(e); return }
            if (n != null && moved) {
                val positions = s.positions()
                fire { it.positionsChanged(positions) }
            } else if (wasBackground && !moved && hitAt(e.x, e.y).kind == HitKind.NONE) {
                select(null)
            }
        }

        override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                val hit = hitAt(e.x, e.y)
                val n = hit.node
                if (n != null && hit.kind != HitKind.CHEVRON) fire { it.goToSourceRequested(n.name) }
            }
        }

        override fun mouseMoved(e: MouseEvent) {
            if (scene == null) return
            val hit = hitAt(e.x, e.y)
            if (state.hoverEdge !== hit.edge) {
                state.hoverEdge = hit.edge
                repaint()
            }
            cursor = if (hit.kind == HitKind.CHEVRON) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        }

        override fun mouseExited(e: MouseEvent) {
            if (state.hoverEdge != null) {
                state.hoverEdge = null
                repaint()
            }
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            if (scene == null) return
            applyPendingFit()
            markInteraction()
            val rotation = e.preciseWheelRotation.coerceIn(-8.0, 8.0)
            viewport.zoomAt(e.x.toDouble(), e.y.toDouble(), WHEEL_STEP.pow(-rotation))
            e.consume()
            repaint()
        }

        private fun showPopup(e: MouseEvent) {
            val hit = hitAt(e.x, e.y)
            val n = hit.node
            val menu = if (n != null) {
                select(n.name, null, center = false)
                nodeMenu(n)
            } else backgroundMenu()
            menu.show(this@DiagramCanvas, e.x, e.y)
        }

        override fun keyPressed(e: KeyEvent) {
            if (scene == null) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) fire { it.escapePressed() }
                return
            }
            val shortcut = e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK) != 0
            when (e.keyCode) {
                KeyEvent.VK_LEFT, KeyEvent.VK_KP_LEFT -> moveSelection(DiagramNavigation.Direction.LEFT)
                KeyEvent.VK_RIGHT, KeyEvent.VK_KP_RIGHT -> moveSelection(DiagramNavigation.Direction.RIGHT)
                KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> moveSelection(DiagramNavigation.Direction.UP)
                KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> moveSelection(DiagramNavigation.Direction.DOWN)
                KeyEvent.VK_ENTER -> {
                    val name = selectedEntity ?: return
                    fire { it.goToSourceRequested(name) }
                }
                KeyEvent.VK_SPACE -> state.selected?.let { toggleCollapsed(it) } ?: return
                KeyEvent.VK_ESCAPE -> handleEscape()
                KeyEvent.VK_F -> if (shortcut) fire { it.searchRequested() } else return
                else -> return
            }
            e.consume()
        }

        override fun keyTyped(e: KeyEvent) {
            if (e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK or InputEvent.ALT_DOWN_MASK) != 0) return
            when (e.keyChar) {
                '/' -> fire { it.searchRequested() }
                '+', '=' -> zoomIn()
                '-', '_' -> zoomOut()
                '0' -> fitToWindow()
                'f', 'F' -> if (scene != null && state.selected != null) focusMode = !focusMode else return
                else -> return
            }
            e.consume()
        }

        override fun keyReleased(e: KeyEvent) {}

        override fun focusGained(e: FocusEvent) {
            state.hasKeyboardFocus = true
            repaint()
        }

        override fun focusLost(e: FocusEvent) {
            state.hasKeyboardFocus = false
            repaint()
        }
    }

    companion object {
        /** Zoom range (AC-11.1). */
        const val MIN_ZOOM: Double = Viewport.MIN_ZOOM
        const val MAX_ZOOM: Double = Viewport.MAX_ZOOM

        private const val ZOOM_STEP = 1.25
        private const val WHEEL_STEP = 1.12
        private const val FIT_MARGIN = 28.0
        private const val EDGE_HIT_PX = 7.0
        private const val DRAG_THRESHOLD = 4
        /** Full-quality repaint this long after the last wheel / drag event. */
        private const val INTERACTION_SETTLE_MS = 150
    }
}

/** Shared [FontSet] per base font, so the canvas and [DiagramMetrics] measure with the same objects. */
internal object FontSets {
    @Volatile
    private var last: FontSet? = null

    fun of(base: Font): FontSet {
        val cached = last
        if (cached != null && cached.base == base) return cached
        return FontSet(base).also { last = it }
    }
}

/** Node sizes for layout. Safe to call from any thread (uses no realized component). */
object DiagramMetrics {
    /** Sizes for the default theme (the font `UIManager` provides for labels) — what a [DiagramCanvas] paints by default. */
    fun measure(database: Database, collapsed: Set<String> = emptySet()): Map<String, Size> =
        measure(database, collapsed, DEFAULT_THEME)

    /** Sizes for a canvas that uses [theme] (only its font matters). Same code path as painting. */
    fun measure(database: Database, collapsed: Set<String>, theme: DiagramTheme): Map<String, Size> {
        val fonts = FontSets.of(theme.font)
        val scene = DiagramScene(database)
        val result = LinkedHashMap<String, Size>(scene.nodes.size * 2)
        for (n in scene.nodes) {
            n.measure(fonts)
            n.collapsed = n.name in collapsed
            result[n.name] = Size(n.width, n.height)
        }
        return result
    }

    private val DEFAULT_THEME = DefaultDiagramTheme()
}
