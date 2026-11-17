package com.oguzhanaslann.roomer.render

import java.awt.Color
import java.awt.Font
import java.awt.SystemColor
import javax.swing.UIManager

/**
 * Colours and font of the diagram (spec FR-26). Every property is read AT PAINT TIME, so an implementation
 * that looks its values up lazily (like [DefaultDiagramTheme]) follows look-and-feel switches automatically.
 *
 * Only the first eight properties are mandatory; every secondary tone has a default that is derived by
 * blending the primary ones, so a theme never needs colour literals.
 */
interface DiagramTheme {
    /** Base font. Bold / italic / small variants are derived from it. */
    val font: Font

    /** Background of the canvas itself. */
    val canvasBackground: Color

    /** Fill of a node body. */
    val nodeBackground: Color

    /** Primary text and selected/emphasised line colour. */
    val foreground: Color

    /** Node outlines and separators. */
    val border: Color

    /** Selection outline, hovered edge, highlighted column outline. */
    val accent: Color

    /** Base tone for highlighted column rows (blended with [nodeBackground], text stays [foreground]). */
    val selectionBackground: Color

    /** Glyph colour of the "stale" marker (the marker also has a glyph and text, never colour only). */
    val warning: Color

    /** Column types, stereotypes, legend text. */
    val secondaryForeground: Color get() = blend(foreground, nodeBackground, 0.32)

    val tableHeaderBackground: Color get() = blend(nodeBackground, foreground, 0.09)
    val viewHeaderBackground: Color get() = blend(nodeBackground, accent, 0.22)
    val ftsHeaderBackground: Color get() = blend(nodeBackground, foreground, 0.20)

    /** Relationship lines that are neither hovered nor attached to the selection. */
    val edge: Color get() = blend(foreground, canvasBackground, 0.30)

    /** Fill of a highlighted column row. */
    val rowHighlight: Color get() = blend(nodeBackground, selectionBackground, 0.42)

    /** Background of the legend and stale overlays. */
    val overlayBackground: Color get() = blend(canvasBackground, nodeBackground, 0.5)

    /** How far (0..1) dimmed elements are blended towards [canvasBackground] in focus mode. */
    val dimAmount: Double get() = 0.74

    companion object {
        /** Linear blend: `t = 0` gives [a], `t = 1` gives [b]. */
        @JvmStatic
        fun blend(a: Color, b: Color, t: Double): Color {
            val u = t.coerceIn(0.0, 1.0)
            val r = (a.red + (b.red - a.red) * u).toInt()
            val g = (a.green + (b.green - a.green) * u).toInt()
            val bl = (a.blue + (b.blue - a.blue) * u).toInt()
            return Color(r, g, bl)
        }
    }
}

/**
 * Resolves everything from [UIManager] on every access. IntelliJ's look-and-feel populates `UIManager`,
 * so inside the IDE this follows the IDE theme (light, dark, high contrast) including runtime switches;
 * outside the IDE the same keys resolve against any standard Swing look-and-feel.
 */
open class DefaultDiagramTheme : DiagramTheme {

    override val font: Font
        get() = UIManager.getFont("Label.font") ?: UIManager.getFont("Panel.font") ?: FALLBACK_FONT

    override val canvasBackground: Color
        get() = color("Panel.background", "control") ?: SystemColor.control

    override val nodeBackground: Color
        get() = color("TextField.background", "List.background", "window") ?: canvasBackground

    override val foreground: Color
        get() = color("Label.foreground", "textText", "controlText") ?: SystemColor.controlText

    override val border: Color
        get() = color("Component.borderColor", "Separator.foreground", "controlShadow")
            ?: DiagramTheme.blend(foreground, canvasBackground, 0.5)

    override val accent: Color
        get() = color("Component.focusColor", "Focus.color", "List.selectionBackground", "textHighlight")
            ?: foreground

    override val selectionBackground: Color
        get() = color("List.selectionBackground", "textHighlight") ?: accent

    override val warning: Color
        get() = color("Label.errorForeground", "Component.errorFocusColor", "ValidationTooltip.errorBorderColor")
            ?: foreground

    protected fun color(vararg keys: String): Color? {
        for (key in keys) {
            val c = UIManager.getColor(key)
            if (c != null) return c
        }
        return null
    }

    private companion object {
        val FALLBACK_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 13)
    }
}

/** Flat snapshot of the tones used for one emphasis level (normal or dimmed). */
internal class Tones(
    val nodeBg: Color,
    val headerTable: Color,
    val headerView: Color,
    val headerFts: Color,
    val fg: Color,
    val fg2: Color,
    val border: Color,
    val edge: Color,
)

/** Theme resolved once per paint, so painting does not hit `UIManager` per node. */
internal class Palette(theme: DiagramTheme) {
    val canvasBg: Color = theme.canvasBackground
    val accent: Color = theme.accent
    val rowHighlight: Color = theme.rowHighlight
    val warning: Color = theme.warning
    val overlayBg: Color = theme.overlayBackground
    val normal = Tones(
        nodeBg = theme.nodeBackground,
        headerTable = theme.tableHeaderBackground,
        headerView = theme.viewHeaderBackground,
        headerFts = theme.ftsHeaderBackground,
        fg = theme.foreground,
        fg2 = theme.secondaryForeground,
        border = theme.border,
        edge = theme.edge,
    )
    val dim: Tones = run {
        val t = theme.dimAmount
        fun d(c: Color) = DiagramTheme.blend(c, canvasBg, t)
        Tones(d(normal.nodeBg), d(normal.headerTable), d(normal.headerView), d(normal.headerFts),
            d(normal.fg), d(normal.fg2), d(normal.border), d(normal.edge))
    }
}
