package com.oguzhanaslann.roomer.render

import kotlin.math.max
import kotlin.math.min

/**
 * Zoom/pan maths. `screen = diagram * zoom + pan`. Plain class, no Swing, unit-tested.
 */
internal class Viewport {
    var zoom = 1.0
        private set
    var panX = 0.0
    var panY = 0.0

    fun toDiagramX(sx: Double) = (sx - panX) / zoom
    fun toDiagramY(sy: Double) = (sy - panY) / zoom
    fun toScreenX(dx: Double) = dx * zoom + panX
    fun toScreenY(dy: Double) = dy * zoom + panY

    fun set(zoom: Double, panX: Double, panY: Double) {
        this.zoom = clampZoom(zoom)
        this.panX = panX
        this.panY = panY
    }

    fun copyFrom(other: Viewport) = set(other.zoom, other.panX, other.panY)
    fun copy(): Viewport = Viewport().also { it.copyFrom(this) }

    /** Multiplies the zoom by [factor] (clamped) keeping the diagram point under screen point ([sx], [sy]) fixed. */
    fun zoomAt(sx: Double, sy: Double, factor: Double) {
        val dx = toDiagramX(sx)
        val dy = toDiagramY(sy)
        zoom = clampZoom(zoom * factor)
        panX = sx - dx * zoom
        panY = sy - dy * zoom
    }

    fun panBy(dsx: Double, dsy: Double) {
        panX += dsx
        panY += dsy
    }

    /** Shows the diagram rectangle completely, centred, with [margin] screen pixels around it. Never zooms in beyond [maxZoom]. */
    fun fit(bx: Double, by: Double, bw: Double, bh: Double, viewW: Double, viewH: Double, margin: Double, maxZoom: Double = 1.0) {
        val availW = max(1.0, viewW - 2 * margin)
        val availH = max(1.0, viewH - 2 * margin)
        val z = if (bw <= 0.0 || bh <= 0.0) 1.0 else min(availW / bw, availH / bh)
        zoom = clampZoom(min(z, maxZoom))
        centerOn(bx + bw / 2, by + bh / 2, viewW, viewH)
    }

    /** Puts diagram point ([dx], [dy]) in the middle of the view. */
    fun centerOn(dx: Double, dy: Double, viewW: Double, viewH: Double) {
        panX = viewW / 2 - dx * zoom
        panY = viewH / 2 - dy * zoom
    }

    /**
     * Pans the minimum amount so the diagram rectangle is inside the view (with [margin] screen pixels).
     * A rectangle larger than the view is aligned to its top/left. Returns true if the pan changed.
     */
    fun ensureVisible(bx: Double, by: Double, bw: Double, bh: Double, viewW: Double, viewH: Double, margin: Double): Boolean {
        val oldX = panX
        val oldY = panY
        val left = toScreenX(bx)
        val right = toScreenX(bx + bw)
        if (right - left > viewW - 2 * margin || left < margin) panX += margin - left
        else if (right > viewW - margin) panX -= right - (viewW - margin)
        val top = toScreenY(by)
        val bottom = toScreenY(by + bh)
        if (bottom - top > viewH - 2 * margin || top < margin) panY += margin - top
        else if (bottom > viewH - margin) panY -= bottom - (viewH - margin)
        return panX != oldX || panY != oldY
    }

    companion object {
        const val MIN_ZOOM = 0.1
        const val MAX_ZOOM = 3.0
        fun clampZoom(z: Double): Double = if (z.isNaN()) 1.0 else z.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
