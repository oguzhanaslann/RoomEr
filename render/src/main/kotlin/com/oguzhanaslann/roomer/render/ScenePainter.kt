package com.oguzhanaslann.roomer.render

import com.oguzhanaslann.roomer.model.EntityKind
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Stroke
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.max
import kotlin.math.min

/**
 * Crow's-foot end symbols, drawn on the straight stub of an edge. Symbols are appended to two paths (strokes and
 * "zero" circles) so that all ends of one edge style are painted with three draw calls. Reuses its shapes: EDT only.
 *
 * Notation (outermost = minimum, next to the node = maximum): exactly one = two bars; zero or one = bar next to the
 * node + circle; zero or many = crow's foot touching the node + circle.
 */
internal class CrowsFoot {
    private val circle = Ellipse2D.Double()
    private val lines = Path2D.Double()
    private val circles = Path2D.Double()

    /**
     * Appends the symbol for an end at ([x], [y]) on the node border to [lines] / [circles].
     * ([ux], [uy]) is the unit direction in which the edge leaves the node (e.g. (1, 0) = to the right).
     */
    fun append(lines: Path2D, circles: Path2D, x: Double, y: Double, ux: Double, uy: Double, kind: EndKind) {
        // Perpendicular to the edge direction.
        val px = -uy * HALF
        val py = ux * HALF
        when (kind) {
            EndKind.EXACTLY_ONE -> {
                bar(lines, x + ux * BAR_1, y + uy * BAR_1, px, py)
                bar(lines, x + ux * BAR_2, y + uy * BAR_2, px, py)
            }
            EndKind.ZERO_OR_ONE -> {
                bar(lines, x + ux * BAR_1, y + uy * BAR_1, px, py)
                val d = BAR_1 + 4.0 + RADIUS
                circle(circles, x + ux * d, y + uy * d)
            }
            EndKind.ZERO_OR_MANY -> {
                val tx = x + ux * FOOT
                val ty = y + uy * FOOT
                lines.moveTo(x + px, y + py); lines.lineTo(tx, ty); lines.lineTo(x - px, y - py)
                val d = FOOT + 1.5 + RADIUS
                circle(circles, x + ux * d, y + uy * d)
            }
        }
    }

    /** Paints one symbol directly (legend, hovered edge). */
    fun paint(g: Graphics2D, x: Double, y: Double, ux: Double, uy: Double, kind: EndKind, lineColor: Color, fill: Color) {
        lines.reset()
        circles.reset()
        append(lines, circles, x, y, ux, uy, kind)
        flush(g, lines, circles, lineColor, fill)
    }

    private fun bar(p: Path2D, cx: Double, cy: Double, px: Double, py: Double) {
        p.moveTo(cx + px, cy + py)
        p.lineTo(cx - px, cy - py)
    }

    private fun circle(p: Path2D, cx: Double, cy: Double) {
        circle.setFrame(cx - RADIUS, cy - RADIUS, 2 * RADIUS, 2 * RADIUS)
        p.append(circle, false)
    }

    companion object {
        /** Half height of bars and of the crow's foot. */
        const val HALF = 5.5
        const val RADIUS = 3.6
        const val BAR_1 = 7.0
        const val BAR_2 = 12.0
        const val FOOT = 11.0

        /** Draws collected symbols: strokes, then circles filled with [fill] (hides the edge line) and outlined. */
        fun flush(g: Graphics2D, lines: Path2D, circles: Path2D, lineColor: Color, fill: Color) {
            g.color = lineColor
            g.draw(lines)
            if (circles.currentPoint != null) {
                g.color = fill
                g.fill(circles)
                g.color = lineColor
                g.draw(circles)
            }
        }
    }
}

/** What the canvas wants emphasised; owned by the canvas, read by the painter. */
internal class PaintState {
    var selected: NodeModel? = null
    /** Highlighted row in [selected] (search result), or -1. */
    var highlightRow = -1
    var hoverEdge: EdgeModel? = null
    /** Non-null in focus mode: nodes that stay at full strength. */
    var focusSet: Set<NodeModel>? = null
    var legendVisible = true
    var stale = false
    var hasKeyboardFocus = false
    /** True while the user pans, wheel-zooms or drags: edges may be drawn with cheaper settings. */
    var interactive = false
}

/** Paints a [DiagramScene]. Stateless apart from reusable shapes; EDT (or one thread at a time) only. */
internal class ScenePainter {
    private val crowsFoot = CrowsFoot()
    private val rect = Rectangle2D.Double()
    private val round = RoundRectangle2D.Double()
    private val line = Line2D.Double()
    private val tri = Path2D.Double()
    private val visible = Rectangle2D.Double()

    private var solid: Stroke = BasicStroke(1f)
    private var dashed: Stroke = BasicStroke(1f)
    private var thick: Stroke = BasicStroke(2.6f)
    private var thickDashed: Stroke = BasicStroke(2.6f)
    private var strokeZoom = Double.NaN

    private val edgeBatch = Path2D.Double(Path2D.WIND_NON_ZERO, 4096)
    private val markLines = Path2D.Double(Path2D.WIND_NON_ZERO, 2048)
    private val markCircles = Path2D.Double(Path2D.WIND_NON_ZERO, 2048)
    private var edgeStrokeW = Float.NaN
    private var normalStroke: Stroke = BasicStroke(1f)
    private var strongStroke: Stroke = BasicStroke(1f)
    private var hoverStroke: Stroke = BasicStroke(1f)

    private var legendFonts: FontSet? = null
    private var legendTextW = 0f

    /** Below this on-screen font size (px) nodes are drawn simplified, without column text. */
    fun isDetailed(zoom: Double, fonts: FontSet): Boolean = zoom * fonts.regular.size2D >= DETAIL_MIN_FONT_PX

    fun paint(g0: Graphics2D, width: Int, height: Int, scene: DiagramScene?, vp: Viewport, state: PaintState, palette: Palette, fonts: FontSet) {
        val clip = g0.clipBounds
        g0.color = palette.canvasBg
        if (clip != null) g0.fillRect(clip.x, clip.y, clip.width, clip.height) else g0.fillRect(0, 0, width, height)
        if (scene == null) {
            if (state.hasKeyboardFocus) paintFocusRing(g0, width, height, palette)
            return
        }

        val g = g0.create() as Graphics2D
        try {
            applyHints(g)
            val zoom = vp.zoom
            g.translate(vp.panX, vp.panY)
            g.scale(zoom, zoom)
            if (clip != null) {
                visible.setRect(vp.toDiagramX(clip.x.toDouble()), vp.toDiagramY(clip.y.toDouble()), clip.width / zoom, clip.height / zoom)
            } else {
                visible.setRect(vp.toDiagramX(0.0), vp.toDiagramY(0.0), width / zoom, height / zoom)
            }
            ensureStrokes(zoom)
            scene.ensureRoutes()
            val detailed = isDetailed(zoom, fonts)
            val focus = state.focusSet
            val selected = state.selected
            val hover = state.hoverEdge

            // Edges below nodes, batched per style (one draw call per style instead of several per edge).
            // While the user pans/zooms/drags (state.interactive) the bulk of the edges is drawn cheaper: hairlines
            // without anti-aliasing up to FAST_EDGES_MAX_ZOOM, stroke normalisation above. Edges of the selected
            // table and the hovered edge stay anti-aliased. A full-quality frame follows when the interaction settles.
            val markers = zoom >= MARKER_MIN_ZOOM
            val edgeW = max(1.25, 1.0 / zoom).toFloat()
            ensureEdgeStrokes(edgeW)
            val fast = state.interactive && zoom < FAST_EDGES_MAX_ZOOM
            if (state.interactive) g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
            for (pass in 0..1) {
                edgeBatch.reset(); markLines.reset(); markCircles.reset()
                for (e in scene.edges) {
                    if (e === hover) continue
                    val attached = selected != null && (e.child === selected || e.parent === selected)
                    if ((pass == 1) != attached) continue
                    if (!edgeVisible(e)) continue
                    appendEdge(e, markers)
                }
                if (edgeBatch.currentPoint == null) continue
                val color = when {
                    pass == 1 -> palette.normal.fg
                    focus != null -> palette.dim.edge
                    else -> palette.normal.edge
                }
                val hairline = fast && pass == 0
                if (hairline) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
                g.stroke = if (hairline) HAIRLINE else if (pass == 1) strongStroke else normalStroke
                g.color = color
                g.draw(edgeBatch)
                if (markers) CrowsFoot.flush(g, markLines, markCircles, color, palette.canvasBg)
                if (hairline) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            }
            if (state.interactive) g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            // Nodes: dimmed ones first so the focus set is always on top.
            val order = scene.zOrder
            if (focus != null) {
                for (i in order.indices) {
                    val n = order[i]
                    if (n !in focus && nodeVisible(n)) paintNode(g, n, palette.dim, palette, fonts, state, zoom, detailed, dimmed = true)
                }
            }
            for (i in order.indices) {
                val n = order[i]
                if (focus != null && n !in focus) continue
                if (nodeVisible(n)) paintNode(g, n, palette.normal, palette, fonts, state, zoom, detailed, dimmed = false)
            }

            if (hover != null && edgeVisible(hover)) {
                edgeBatch.reset(); markLines.reset(); markCircles.reset()
                appendEdge(hover, markers)
                g.stroke = hoverStroke
                g.color = palette.accent
                g.draw(edgeBatch)
                if (markers) CrowsFoot.flush(g, markLines, markCircles, palette.accent, palette.canvasBg)
            }
        } finally {
            g.dispose()
        }

        // Overlays in screen space.
        val o = g0.create() as Graphics2D
        try {
            applyHints(o)
            if (state.stale) paintStale(o, palette, fonts)
            if (state.legendVisible) paintLegend(o, width, height, palette, fonts)
            if (state.hasKeyboardFocus) paintFocusRing(o, width, height, palette)
        } finally {
            o.dispose()
        }
    }

    private fun applyHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun ensureStrokes(zoom: Double) {
        if (zoom == strokeZoom) return
        strokeZoom = zoom
        val w = max(1.0, 0.85 / zoom).toFloat()
        val t = max(2.6, 2.2 / zoom).toFloat()
        solid = BasicStroke(w)
        dashed = BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(5f, 3.5f), 0f)
        thick = BasicStroke(t, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
        thickDashed = BasicStroke(t, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(7f, 4f), 0f)
    }

    private fun nodeVisible(n: NodeModel): Boolean =
        n.x - 8 <= visible.x + visible.width && n.x + n.width + 8 >= visible.x &&
            n.y - 8 <= visible.y + visible.height && n.y + n.height + 8 >= visible.y

    private fun edgeVisible(e: EdgeModel): Boolean =
        e.minX <= visible.x + visible.width && e.maxX >= visible.x && e.minY <= visible.y + visible.height && e.maxY >= visible.y

    private fun appendEdge(e: EdgeModel, markers: Boolean) {
        edgeBatch.append(e.path, false)
        if (markers) {
            crowsFoot.append(markLines, markCircles, e.ax, e.ay, e.aDir.toDouble(), 0.0, e.childEnd)
            if (e.bUp) crowsFoot.append(markLines, markCircles, e.bx, e.by, 0.0, -1.0, e.parentEnd)
            else crowsFoot.append(markLines, markCircles, e.bx, e.by, e.bDir.toDouble(), 0.0, e.parentEnd)
        }
    }

    private fun ensureEdgeStrokes(w: Float) {
        if (w == edgeStrokeW) return
        edgeStrokeW = w
        normalStroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        strongStroke = BasicStroke(w * 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        hoverStroke = BasicStroke(w * 2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    }

    private fun paintNode(g: Graphics2D, n: NodeModel, t: Tones, palette: Palette, fonts: FontSet, state: PaintState, zoom: Double, detailed: Boolean, dimmed: Boolean) {
        g.translate(n.x, n.y)
        val w = n.width
        val h = n.height
        val headerColor = when (n.kind) {
            EntityKind.TABLE -> t.headerTable
            EntityKind.VIEW -> t.headerView
            EntityKind.FTS_TABLE -> t.headerFts
        }
        val isSelected = !dimmed && state.selected === n
        if (detailed) {
            g.color = t.nodeBg
            g.fill(n.outline)
            g.color = headerColor
            g.fill(n.headerShape)
            paintHeader(g, n, t, fonts)
            if (n.showsRows) {
                g.color = t.border
                g.stroke = solid
                line.setLine(0.0, n.headerH, w, n.headerH)
                g.draw(line)
                paintRows(g, n, t, palette, fonts, state, isSelected, dimmed)
            }
        } else {
            g.color = headerColor
            g.fill(n.outline)
            paintSimplifiedTitle(g, n, t, fonts, zoom)
        }
        g.color = t.border
        g.stroke = if (n.kind == EntityKind.VIEW) dashed else solid
        g.draw(n.outline)

        if (isSelected) {
            // Selection: thicker accent outline plus corner handles (not colour-only, NFR-05).
            g.color = palette.accent
            g.stroke = if (n.kind == EntityKind.VIEW) thickDashed else thick
            g.draw(n.outline)
            val hs = max(7.0, 5.5 / zoom)
            g.stroke = solid
            handle(g, 0.0, 0.0, hs, palette.accent, t.nodeBg)
            handle(g, w, 0.0, hs, palette.accent, t.nodeBg)
            handle(g, 0.0, h, hs, palette.accent, t.nodeBg)
            handle(g, w, h, hs, palette.accent, t.nodeBg)
        }
        g.translate(-n.x, -n.y)
    }

    private fun handle(g: Graphics2D, cx: Double, cy: Double, size: Double, fill: Color, outline: Color) {
        rect.setRect(cx - size / 2, cy - size / 2, size, size)
        g.color = fill
        g.fill(rect)
        g.color = outline
        g.draw(rect)
    }

    private fun paintHeader(g: Graphics2D, n: NodeModel, t: Tones, fonts: FontSet) {
        val hh = n.headerH
        if (n.rows.isNotEmpty()) {
            val cy = hh / 2
            val x = NodeModel.PAD.toDouble()
            tri.reset()
            if (n.collapsed) {
                tri.moveTo(x + 1.5, cy - 4.5); tri.lineTo(x + 1.5, cy + 4.5); tri.lineTo(x + 7.0, cy)
            } else {
                tri.moveTo(x, cy - 3.0); tri.lineTo(x + 8.5, cy - 3.0); tri.lineTo(x + 4.25, cy + 3.5)
            }
            tri.closePath()
            g.color = t.fg2
            g.fill(tri)
        }
        val stereotype = n.stereotype
        if (stereotype != null) {
            g.font = fonts.small
            g.color = t.fg2
            g.drawString(stereotype, NodeModel.TITLE_X, 5f + fonts.smallAscent)
            g.font = fonts.bold
            g.color = t.fg
            g.drawString(n.title, NodeModel.TITLE_X, 5f + fonts.smallH + fonts.ascent - 1f)
        } else {
            g.font = fonts.bold
            g.color = t.fg
            g.drawString(n.title, NodeModel.TITLE_X, ((hh - fonts.textH) / 2).toFloat() + fonts.ascent)
        }
    }

    private fun paintRows(g: Graphics2D, n: NodeModel, t: Tones, palette: Palette, fonts: FontSet, state: PaintState, isSelected: Boolean, dimmed: Boolean) {
        val rowH = n.rowH
        val hh = n.headerH
        // Only the rows inside the visible area.
        val first = max(0, ((visible.y - n.y - hh) / rowH).toInt())
        val last = min(n.rows.size - 1, ((visible.y + visible.height - n.y - hh) / rowH).toInt())
        val hover = if (dimmed) null else state.hoverEdge
        val hoverRows: IntArray? = when {
            hover == null -> null
            hover.child === n && hover.parent === n -> hover.childRows + hover.parentRows
            hover.child === n -> hover.childRows
            hover.parent === n -> hover.parentRows
            else -> null
        }
        val badgeH = fonts.smallH + 2.0
        val textOffset = ((rowH - fonts.textH) / 2).toFloat() + fonts.ascent
        var i = first
        while (i <= last) {
            val r = n.rows[i]
            val top = hh + i * rowH
            val highlighted = (isSelected && state.highlightRow == i) || (hoverRows != null && contains(hoverRows, i))
            if (highlighted) {
                // Fill plus outline: the highlight does not rely on colour alone.
                rect.setRect(1.5, top + 0.5, n.width - 3.0, rowH - 1.0)
                g.color = palette.rowHighlight
                g.fill(rect)
                g.color = palette.accent
                g.stroke = solid
                g.draw(rect)
            }
            var bx = NodeModel.PAD.toDouble()
            if (r.badges.isNotEmpty()) {
                g.font = fonts.smallBold
                val by = top + (rowH - badgeH) / 2
                for (b in r.badges.indices) {
                    val bw = r.badgeW[b].toDouble()
                    round.setRoundRect(bx, by, bw, badgeH, 5.0, 5.0)
                    val text = r.badges[b]
                    if (text === DiagramScene.BADGE_PK) {
                        g.color = t.fg2
                        g.fill(round)
                        g.color = t.nodeBg
                    } else {
                        g.color = t.fg2
                        g.stroke = solid
                        g.draw(round)
                    }
                    g.drawString(text, (bx + NodeModel.BADGE_PAD_X).toFloat(), (by + 1.0 + fonts.smallAscent).toFloat())
                    bx += bw + NodeModel.BADGE_GAP
                }
            }
            val baseline = top.toFloat() + textOffset
            g.font = if (r.isSql) fonts.italic else fonts.regular
            g.color = if (r.isSql) t.fg2 else t.fg
            g.drawString(r.name, n.nameX, baseline)
            if (r.type.isNotEmpty()) {
                g.color = t.fg2
                g.drawString(r.type, n.typeX, baseline)
            }
            i++
        }
    }

    private fun contains(a: IntArray, v: Int): Boolean {
        for (x in a) if (x == v) return true
        return false
    }

    private fun paintSimplifiedTitle(g: Graphics2D, n: NodeModel, t: Tones, fonts: FontSet, zoom: Double) {
        if (n.titleW <= 0f) return
        val size = fonts.bold.size2D
        var s = min((n.width - 10.0) / n.titleW, (n.height - 4.0) / fonts.textH)
        s = min(s, max(1.0, SIMPLIFIED_TITLE_PX / (zoom * size)))
        if (s * zoom * size < 3.2) return
        g.font = fonts.bold
        g.color = t.fg
        val cx = n.width / 2
        val cy = n.height / 2
        g.translate(cx, cy)
        g.scale(s, s)
        g.drawString(n.title, -n.titleW / 2, fonts.ascent - fonts.textH / 2)
        g.scale(1 / s, 1 / s)
        g.translate(-cx, -cy)
    }

    // ---- overlays ------------------------------------------------------------------------------------

    /** Keyboard focus indicator for the canvas itself (NFR-05): an accent frame along the component border. */
    private fun paintFocusRing(g: Graphics2D, width: Int, height: Int, p: Palette) {
        if (width < 4 || height < 4) return
        g.color = p.accent
        g.stroke = FOCUS_RING
        rect.setRect(1.0, 1.0, width - 2.0, height - 2.0)
        g.draw(rect)
    }

    private fun paintStale(g: Graphics2D, p: Palette, fonts: FontSet) {
        val text = STALE_TEXT
        val tw = fonts.width(fonts.regular, text)
        val h = fonts.textH + 12.0
        val icon = fonts.textH.toDouble()
        val w = 10.0 + icon + 7.0 + tw + 12.0
        val x = 10.0
        val y = 10.0
        round.setRoundRect(x, y, w, h, h, h)
        g.color = p.overlayBg
        g.fill(round)
        g.color = p.warning
        g.stroke = BasicStroke(1.4f)
        g.draw(round)
        // Warning triangle with an exclamation mark (a glyph, so the marker is not colour-only).
        val ix = x + 10.0
        val iy = y + (h - icon) / 2
        tri.reset()
        tri.moveTo(ix + icon / 2, iy); tri.lineTo(ix + icon, iy + icon); tri.lineTo(ix, iy + icon); tri.closePath()
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(tri)
        line.setLine(ix + icon / 2, iy + icon * 0.38, ix + icon / 2, iy + icon * 0.66); g.draw(line)
        line.setLine(ix + icon / 2, iy + icon * 0.82, ix + icon / 2, iy + icon * 0.83); g.draw(line)
        g.color = p.normal.fg
        g.font = fonts.regular
        g.drawString(text, (ix + icon + 7.0).toFloat(), (y + (h - fonts.textH) / 2).toFloat() + fonts.ascent)
    }

    /** Screen rectangle the legend occupies in a canvas of [width] x [height], or null if it is not painted (too small). */
    fun legendBounds(width: Int, height: Int, fonts: FontSet): Rectangle2D.Double? {
        if (legendFonts !== fonts) {
            legendFonts = fonts
            var m = 0f
            for (s in LEGEND_LABELS) m = max(m, fonts.width(fonts.small, s))
            legendTextW = m
        }
        val rowH = fonts.smallH + 7.0
        val w = LEGEND_PAD + LEGEND_GLYPH_W + 8.0 + legendTextW + LEGEND_PAD
        val h = LEGEND_PAD + LEGEND_LABELS.size * rowH + LEGEND_PAD - 3.0
        if (width < w + 60 || height < h + 60) return null
        return Rectangle2D.Double(width - w - LEGEND_INSET, height - h - LEGEND_INSET, w, h)
    }

    private fun paintLegend(g: Graphics2D, width: Int, height: Int, p: Palette, fonts: FontSet) {
        val bounds = legendBounds(width, height, fonts) ?: return
        val t = p.normal
        val rowH = fonts.smallH + 7.0
        val glyphW = LEGEND_GLYPH_W
        val pad = LEGEND_PAD
        val w = bounds.width
        val h = bounds.height
        val x = bounds.x
        val y = bounds.y
        round.setRoundRect(x, y, w, h, 8.0, 8.0)
        g.color = p.overlayBg
        g.fill(round)
        g.color = t.border
        g.stroke = BasicStroke(1f)
        g.draw(round)

        val gx = x + pad
        val tx = (gx + glyphW + 8.0).toFloat()
        val badgeH = fonts.smallH + 2.0
        for (i in LEGEND_LABELS.indices) {
            val top = y + pad + i * rowH
            val cy = top + rowH / 2
            g.stroke = BasicStroke(1f)
            when (i) {
                0 -> { // table
                    rect.setRect(gx + 10, cy - 6, 26.0, 12.0)
                    g.color = t.nodeBg; g.fill(rect)
                    rect.setRect(gx + 10, cy - 6, 26.0, 5.0)
                    g.color = t.headerTable; g.fill(rect)
                    rect.setRect(gx + 10, cy - 6, 26.0, 12.0)
                    g.color = t.border; g.draw(rect)
                }
                1 -> { // view
                    round.setRoundRect(gx + 10, cy - 6, 26.0, 12.0, 9.0, 9.0)
                    g.color = t.headerView; g.fill(round)
                    g.color = t.border
                    g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(3f, 2.5f), 0f)
                    g.draw(round)
                }
                2 -> { // FTS
                    tri.reset()
                    tri.moveTo(gx + 14, cy - 6); tri.lineTo(gx + 32, cy - 6); tri.lineTo(gx + 36, cy - 2)
                    tri.lineTo(gx + 36, cy + 6); tri.lineTo(gx + 10, cy + 6); tri.lineTo(gx + 10, cy - 2); tri.closePath()
                    g.color = t.headerFts; g.fill(tri)
                    g.color = t.border; g.draw(tri)
                }
                3, 4, 5, 6 -> {
                    val text = LEGEND_BADGES[i - 3]
                    g.font = fonts.smallBold
                    val bw = fonts.width(fonts.smallBold, text) + 2 * NodeModel.BADGE_PAD_X.toDouble()
                    val bx = gx + (glyphW - bw) / 2
                    round.setRoundRect(bx, cy - badgeH / 2, bw, badgeH, 5.0, 5.0)
                    if (i == 3) {
                        g.color = t.fg2; g.fill(round); g.color = t.nodeBg
                    } else {
                        g.color = t.nodeBg; g.fill(round); g.color = t.fg2; g.draw(round)
                    }
                    g.drawString(text, (bx + NodeModel.BADGE_PAD_X).toFloat(), (cy - badgeH / 2 + 1.0 + fonts.smallAscent).toFloat())
                }
                7, 8 -> {
                    val text = if (i == 7) "TEXT?" else "TEXT"
                    g.font = fonts.small
                    g.color = t.fg2
                    val tw = fonts.width(fonts.small, text)
                    g.drawString(text, (gx + (glyphW - tw) / 2).toFloat(), (cy - fonts.smallH / 2 + fonts.smallAscent).toFloat())
                }
                else -> { // relationship ends, drawn against a short piece of node border on the right
                    val kind = LEGEND_ENDS[i - 9]
                    val nx = gx + glyphW - 4
                    g.color = t.fg
                    g.stroke = BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    line.setLine(gx + 4, cy, nx, cy); g.draw(line)
                    crowsFoot.paint(g, nx, cy, -1.0, 0.0, kind, t.fg, p.overlayBg)
                    g.stroke = BasicStroke(2.2f)
                    g.color = t.border
                    line.setLine(nx + 1.0, cy - 7, nx + 1.0, cy + 7); g.draw(line)
                }
            }
            g.font = fonts.small
            g.color = t.fg
            g.drawString(LEGEND_LABELS[i], tx, (cy - fonts.smallH / 2 + fonts.smallAscent).toFloat())
        }
    }

    companion object {
        const val DETAIL_MIN_FONT_PX = 4.6
        const val MARKER_MIN_ZOOM = 0.22
        /**
         * Below this zoom, unselected edges are drawn as non-anti-aliased hairlines while the user is panning /
         * zooming / dragging (anti-aliased strokes cost 5–7 ms per frame at 100 tables; hairlines ~0.2 ms). Above it
         * few edges fit on screen, so they stay anti-aliased. Static frames are always full quality.
         */
        const val FAST_EDGES_MAX_ZOOM = 1.5
        private val HAIRLINE = BasicStroke(0f)
        private val FOCUS_RING = BasicStroke(2f)
        private const val LEGEND_PAD = 9.0
        private const val LEGEND_GLYPH_W = 46.0
        const val LEGEND_INSET = 10.0
        const val SIMPLIFIED_TITLE_PX = 11.0
        const val STALE_TEXT = "Stale – schema file has errors"

        val LEGEND_LABELS = arrayOf(
            "Table", "View", "FTS table",
            "Primary key", "Foreign key", "Unique column", "Indexed / composite unique",
            "Nullable column", "NOT NULL column",
            "Exactly one", "Zero or one", "Zero or many",
        )
        val LEGEND_BADGES = arrayOf(DiagramScene.BADGE_PK, DiagramScene.BADGE_FK, DiagramScene.BADGE_UNIQUE, DiagramScene.BADGE_INDEX)
        val LEGEND_ENDS = arrayOf(EndKind.EXACTLY_ONE, EndKind.ZERO_OR_ONE, EndKind.ZERO_OR_MANY)
    }
}
