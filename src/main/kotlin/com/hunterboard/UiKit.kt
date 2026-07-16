package com.hunterboard

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext

/**
 * HunterBoard visual identity — shared drawing kit for every screen.
 *
 * Themes are palettes over two rendering styles (ModConfig.uiTheme):
 *
 *  GRADIENT style — gradient panel body, thin border, 1px accent targeting corners,
 *  glowing header band with ◆ title ◆, flat pill buttons, underline tabs.
 *   - "hunterboard" (default): deep blue-slate, user-configurable accent color.
 *   - "polaris": snow white / ice blue, light theme (Polaris city).
 *   - "rhode": charcoal night + neon magenta (Rhode neon desert city).
 *   - "niavarane": immaculate ivory + deep red, light theme (Niavarane).
 *
 *  BEVEL style — flat body in a beveled frame, thick accent corners, header strip,
 *  raised Minecraft-style buttons, boxy tabs with accent top edge.
 *   - "cobblemon": Tropimod client GUI grays + teal (sampled from its textures).
 *   - "rebutsurmer": drab industrial concrete + worn safety yellow (Rebut-sur-mer).
 *   - "tropimon": Tropimon logo palette — warm charcoal, beige frame, bright teal.
 */
object UiKit {

    private enum class Style { GRADIENT, BEVEL }

    private class Theme(
        val style: Style,
        val panelTop: Int,
        val panelBottom: Int,
        val surface: Int,
        val surfaceHover: Int,
        val surfaceSunken: Int,
        val border: Int,
        val borderDim: Int,
        val text: Int,
        val textMuted: Int,
        val textFaint: Int,
        /** null = follow the user-configured HUD color (ModConfig) */
        val accentFixed: Int? = null,
        val accentBrightFixed: Int? = null,
        // BEVEL-only fields
        val outline: Int = 0,
        val frameLight: Int = 0,
        val frameDark: Int = 0,
        val headerStrip: Int = 0,
        val buttonBody: Int = 0,
        val buttonBodyLit: Int = 0
    )

    private val THEMES: Map<String, Theme> = mapOf(
        // Deep blue-slate + user accent (default)
        "hunterboard" to Theme(
            style = Style.GRADIENT,
            panelTop = 0xF80B111C.toInt(), panelBottom = 0xF8131B29.toInt(),
            surface = 0xFF161E2C.toInt(), surfaceHover = 0xFF20293B.toInt(), surfaceSunken = 0xFF0C1220.toInt(),
            border = 0xFF2A3549.toInt(), borderDim = 0xFF1C2434.toInt(),
            text = 0xFFECF1F8.toInt(), textMuted = 0xFF9AA7BC.toInt(), textFaint = 0xFF5C6980.toInt()
        ),
        // Tropimod client GUI look (grays + teal, sampled from its textures)
        "cobblemon" to Theme(
            style = Style.BEVEL,
            panelTop = 0xFF2F2F2F.toInt(), panelBottom = 0xFF2F2F2F.toInt(),
            surface = 0xFF262626.toInt(), surfaceHover = 0xFF3F3F3F.toInt(), surfaceSunken = 0xFF1F1F1F.toInt(),
            border = 0xFF676767.toInt(), borderDim = 0xFF454545.toInt(),
            text = 0xFFF2F2F2.toInt(), textMuted = 0xFFB4B4B4.toInt(), textFaint = 0xFF7E7E7E.toInt(),
            accentFixed = 0xFF4FD4D8.toInt(), accentBrightFixed = 0xFF84FEFF.toInt(),
            outline = 0xFF1A1A1A.toInt(), frameLight = 0xFF8D8D8D.toInt(), frameDark = 0xFF4B4B4B.toInt(),
            headerStrip = 0xFF3F3C3B.toInt(), buttonBody = 0xFF565656.toInt(), buttonBodyLit = 0xFF6A6A6A.toInt()
        ),
        // Polaris — glacial night: deep ice blue, snow-white text, ice accent
        "polaris" to Theme(
            style = Style.GRADIENT,
            panelTop = 0xF816222E.toInt(), panelBottom = 0xF80F1821.toInt(),
            surface = 0xFF1C2A38.toInt(), surfaceHover = 0xFF28394A.toInt(), surfaceSunken = 0xFF101B24.toInt(),
            border = 0xFF3A526A.toInt(), borderDim = 0xFF24384A.toInt(),
            text = 0xFFEAF4FB.toInt(), textMuted = 0xFFA8C4D8.toInt(), textFaint = 0xFF628096.toInt(),
            accentFixed = 0xFF7CC8F0.toInt(), accentBrightFixed = 0xFFAEE2FF.toInt()
        ),
        // Rebut-sur-mer — drab industrial concrete, worn safety yellow
        "rebutsurmer" to Theme(
            style = Style.BEVEL,
            panelTop = 0xFF35383B.toInt(), panelBottom = 0xFF35383B.toInt(),
            surface = 0xFF292C2E.toInt(), surfaceHover = 0xFF3E4246.toInt(), surfaceSunken = 0xFF1E2022.toInt(),
            border = 0xFF6B7075.toInt(), borderDim = 0xFF45494D.toInt(),
            text = 0xFFE9EAE6.toInt(), textMuted = 0xFFA5A8A2.toInt(), textFaint = 0xFF75797B.toInt(),
            accentFixed = 0xFFD9B23C.toInt(), accentBrightFixed = 0xFFF5D964.toInt(),
            outline = 0xFF141516.toInt(), frameLight = 0xFF8E9498.toInt(), frameDark = 0xFF3E4144.toInt(),
            headerStrip = 0xFF42464A.toInt(), buttonBody = 0xFF4E5256.toInt(), buttonBodyLit = 0xFF5E6368.toInt()
        ),
        // Rhode — white towers and neon in the desert night
        "rhode" to Theme(
            style = Style.GRADIENT,
            panelTop = 0xF81B1B26.toInt(), panelBottom = 0xF8111119.toInt(),
            surface = 0xFF24242F.toInt(), surfaceHover = 0xFF32323F.toInt(), surfaceSunken = 0xFF0D0D14.toInt(),
            border = 0xFF4E4E62.toInt(), borderDim = 0xFF2C2C3A.toInt(),
            text = 0xFFF5F3FA.toInt(), textMuted = 0xFFAEA9C2.toInt(), textFaint = 0xFF6C687E.toInt(),
            accentFixed = 0xFFFF4FD8.toInt(), accentBrightFixed = 0xFFFF9BEA.toInt()
        ),
        // Niavarane — dark warm stone, ivory text, deep red accent
        "niavarane" to Theme(
            style = Style.GRADIENT,
            panelTop = 0xF8201A1C.toInt(), panelBottom = 0xF8171214.toInt(),
            surface = 0xFF2A2225.toInt(), surfaceHover = 0xFF3A2E32.toInt(), surfaceSunken = 0xFF151011.toInt(),
            border = 0xFF4E3E42.toInt(), borderDim = 0xFF322729.toInt(),
            text = 0xFFF6EFE7.toInt(), textMuted = 0xFFC4B4AC.toInt(), textFaint = 0xFF8A7A74.toInt(),
            accentFixed = 0xFFE04250.toInt(), accentBrightFixed = 0xFFFF6E7A.toInt()
        ),
        // Tropimon — logo palette: warm charcoal, beige frame, bright teal
        "tropimon" to Theme(
            style = Style.BEVEL,
            panelTop = 0xFF2A2526.toInt(), panelBottom = 0xFF2A2526.toInt(),
            surface = 0xFF231F20.toInt(), surfaceHover = 0xFF3B3435.toInt(), surfaceSunken = 0xFF191616.toInt(),
            border = 0xFF5C5254.toInt(), borderDim = 0xFF3B3435.toInt(),
            text = 0xFFF4EDE9.toInt(), textMuted = 0xFFB9ACA7.toInt(), textFaint = 0xFF837772.toInt(),
            accentFixed = 0xFF3EDBC7.toInt(), accentBrightFixed = 0xFF7FF2E2.toInt(),
            outline = 0xFF171415.toInt(), frameLight = 0xFFCBBEB8.toInt(), frameDark = 0xFF4A4243.toInt(),
            headerStrip = 0xFF362F30.toInt(), buttonBody = 0xFF4C4344.toInt(), buttonBodyLit = 0xFF5C5254.toInt()
        )
    )

    private val theme: Theme get() = THEMES[ModConfig.uiTheme] ?: THEMES.getValue("hunterboard")
    private val isBevel: Boolean get() = theme.style == Style.BEVEL

    /** True when the current theme uses a light panel background (Polaris, Niavarane). */
    val isLightTheme: Boolean
        get() {
            val c = theme.panelBottom
            val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            return lum > 128
        }

    // ── Palette (theme-dependent) ────────────────────────────────────────────
    val PANEL_TOP: Int get() = theme.panelTop
    val PANEL_BOTTOM: Int get() = theme.panelBottom
    val SURFACE: Int get() = theme.surface
    val SURFACE_HOVER: Int get() = theme.surfaceHover
    val SURFACE_SUNKEN: Int get() = theme.surfaceSunken
    val BORDER: Int get() = theme.border
    val BORDER_DIM: Int get() = theme.borderDim
    val TEXT: Int get() = theme.text
    val TEXT_MUTED: Int get() = theme.textMuted
    val TEXT_FAINT: Int get() = theme.textFaint
    val DANGER = 0xFFFF5C5C.toInt()
    val GOLD = 0xFFFFD75E.toInt()

    /** Theme accent: fixed per theme, or the user-configured color. */
    fun accent(): Int = theme.accentFixed ?: ModConfig.accentColor()

    /** Brighter accent for icons/highlights. */
    fun accentBright(): Int = theme.accentBrightFixed ?: ModConfig.accentColor()

    /** Replace the alpha channel of [color] with [alpha] (0-255). */
    fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    /** Multiply the RGB channels of [color] by [factor] (keeps alpha). */
    fun dim(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ── Structure ────────────────────────────────────────────────────────────

    /** Full-screen dim behind panels. */
    fun screenDim(ctx: DrawContext, width: Int, height: Int) {
        if (isBevel) {
            ctx.fill(0, 0, width, height, 0x90000000.toInt())
        } else {
            ctx.fillGradient(0, 0, width, height, 0x90050810.toInt(), 0xC0050810.toInt())
        }
    }

    /**
     * Main panel.
     * BEVEL: flat body in a beveled frame + thick accent targeting corners.
     * GRADIENT: gradient body, subtle border, thin accent targeting corners.
     */
    fun panel(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        // Soft drop shadow
        ctx.fill(x + 3, y + h, x + w + 3, y + h + 3, 0x40000000)
        ctx.fill(x + w, y + 3, x + w + 3, y + h, 0x40000000)
        if (isBevel) {
            // Outer outline + beveled frame (light top/left, dark bottom/right)
            border(ctx, x, y, w, h, theme.outline)
            ctx.fill(x + 1, y + 1, x + w - 1, y + 2, theme.frameLight)
            ctx.fill(x + 1, y + 1, x + 2, y + h - 1, theme.frameLight)
            ctx.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, theme.frameDark)
            ctx.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, theme.frameDark)
            // Body
            ctx.fill(x + 2, y + 2, x + w - 2, y + h - 2, theme.panelTop)
            // Thick accent targeting corners (Tropimod navigator signature)
            corners(ctx, x, y, w, h, accent(), len = 10, thickness = 2)
        } else {
            ctx.fillGradient(x, y, x + w, y + h, theme.panelTop, theme.panelBottom)
            border(ctx, x, y, w, h, BORDER)
            corners(ctx, x, y, w, h, accent())
        }
    }

    /** Lightweight inner panel (cards, dropdowns) — no brackets. */
    fun card(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, hovered: Boolean = false) {
        ctx.fill(x, y, x + w, y + h, if (hovered) SURFACE_HOVER else SURFACE)
        border(ctx, x, y, w, h, if (hovered) withAlpha(accent(), 0xAA) else BORDER_DIM)
    }

    fun border(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        ctx.fill(x, y, x + w, y + 1, color)
        ctx.fill(x, y + h - 1, x + w, y + h, color)
        ctx.fill(x, y, x + 1, y + h, color)
        ctx.fill(x + w - 1, y, x + w, y + h, color)
    }

    /** Targeting brackets: short accent lines on each corner. */
    fun corners(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int, len: Int = 7, thickness: Int = 1) {
        val r = x + w
        val b = y + h
        val t = thickness
        // Top-left
        ctx.fill(x, y, x + len, y + t, color); ctx.fill(x, y, x + t, y + len, color)
        // Top-right
        ctx.fill(r - len, y, r, y + t, color); ctx.fill(r - t, y, r, y + len, color)
        // Bottom-left
        ctx.fill(x, b - t, x + len, b, color); ctx.fill(x, b - len, x + t, b, color)
        // Bottom-right
        ctx.fill(r - len, b - t, r, b, color); ctx.fill(r - t, b - len, r, b, color)
    }

    /**
     * Header band: centered title, closed by a separator. Reserve ~20px.
     * BEVEL: contrasting strip with plain title (Tropimod look).
     * GRADIENT: accent glow band with ◆ title ◆.
     */
    fun header(ctx: DrawContext, tr: TextRenderer, x: Int, y: Int, w: Int, title: String) {
        if (isBevel) {
            ctx.fill(x + 2, y + 2, x + w - 2, y + 17, theme.headerStrip)
            val tx = x + (w - tr.getWidth(title)) / 2
            ctx.drawText(tr, title, tx, y + 5, TEXT, true)
            ctx.fill(x + 2, y + 17, x + w - 2, y + 18, theme.outline)
            ctx.fill(x + 2, y + 18, x + w - 2, y + 19, theme.frameDark)
        } else {
            ctx.fillGradient(x + 1, y + 1, x + w - 1, y + 17, withAlpha(accent(), 0x30), withAlpha(accent(), 0x00))
            val label = "◆ $title ◆"
            val tx = x + (w - tr.getWidth(label)) / 2
            ctx.drawText(tr, label, tx, y + 5, accent(), true)
            ctx.fill(x + 6, y + 17, x + w - 6, y + 18, withAlpha(accent(), 0xCC))
            ctx.fill(x + 6, y + 18, x + w - 6, y + 19, withAlpha(accent(), 0x33))
        }
    }

    /** Footer bar with a centered hint. Draws in the last 14px above [bottom]. */
    fun footer(ctx: DrawContext, tr: TextRenderer, x: Int, bottom: Int, w: Int, hint: String) {
        if (isBevel) {
            ctx.fill(x + 2, bottom - 14, x + w - 2, bottom - 2, SURFACE_SUNKEN)
            ctx.fill(x + 2, bottom - 14, x + w - 2, bottom - 13, theme.outline)
        } else {
            ctx.fill(x + 1, bottom - 14, x + w - 1, bottom - 1, SURFACE_SUNKEN)
            ctx.fill(x + 6, bottom - 14, x + w - 6, bottom - 13, BORDER_DIM)
        }
        val hx = x + (w - tr.getWidth(hint)) / 2
        ctx.drawText(tr, hint, hx, bottom - 10, TEXT_FAINT, true)
    }

    // ── Widgets ──────────────────────────────────────────────────────────────

    /** Close ✕ button (12x12 hit area). Returns true if [mx],[my] hovers it. */
    fun closeButton(ctx: DrawContext, tr: TextRenderer, x: Int, y: Int, mx: Int, my: Int): Boolean {
        val hovered = mx >= x - 2 && mx <= x + 9 && my >= y - 2 && my <= y + 11
        val idle = if (isBevel) TEXT_MUTED else TEXT_FAINT
        ctx.drawText(tr, "✕", x, y, if (hovered) DANGER else idle, true)
        return hovered
    }

    /**
     * Standard button.
     * BEVEL: raised Minecraft-style bevel, accent frame when active.
     * GRADIENT: flat pill with accent outline + soft glow when lit.
     */
    fun button(ctx: DrawContext, tr: TextRenderer, x: Int, y: Int, w: Int, h: Int,
               label: String, hovered: Boolean, active: Boolean = false) {
        val lit = hovered || active
        if (isBevel) {
            ctx.fill(x, y, x + w, y + h, if (lit) theme.buttonBodyLit else theme.buttonBody)
            // Bevel: light top/left, dark bottom/right
            val hi = if (lit) 0xFFAAAAAA.toInt() else theme.frameLight
            ctx.fill(x, y, x + w, y + 1, hi)
            ctx.fill(x, y, x + 1, y + h, hi)
            ctx.fill(x, y + h - 1, x + w, y + h, 0xFF2A2A2A.toInt())
            ctx.fill(x + w - 1, y, x + w, y + h, 0xFF2A2A2A.toInt())
            if (active) border(ctx, x, y, w, h, accent())
            val tx = x + (w - tr.getWidth(label)) / 2
            val ty = y + (h - 8) / 2
            ctx.drawText(tr, label, tx, ty, if (active) accentBright() else TEXT, true)
        } else {
            ctx.fill(x, y, x + w, y + h, if (lit) SURFACE_HOVER else SURFACE)
            if (lit) ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, withAlpha(accent(), 0x22))
            border(ctx, x, y, w, h, if (lit) withAlpha(accent(), 0xDD) else BORDER)
            val tx = x + (w - tr.getWidth(label)) / 2
            val ty = y + (h - 8) / 2
            ctx.drawText(tr, label, tx, ty, if (lit) accent() else TEXT_MUTED, true)
        }
    }

    /** Gold variant for special buttons (donors, donations) — same in every theme. */
    fun goldButton(ctx: DrawContext, tr: TextRenderer, x: Int, y: Int, w: Int, h: Int,
                   label: String, hovered: Boolean) {
        ctx.fillGradient(x, y, x + w, y + h,
            if (hovered) 0xFF4A3B12.toInt() else 0xFF33290D.toInt(),
            if (hovered) 0xFF2E250A.toInt() else 0xFF201A06.toInt())
        border(ctx, x, y, w, h, if (hovered) GOLD else 0xFF8A7326.toInt())
        val tx = x + (w - tr.getWidth(label)) / 2
        val ty = y + (h - 8) / 2
        ctx.drawText(tr, label, tx, ty, if (hovered) 0xFFFFE894.toInt() else GOLD, true)
    }

    /**
     * Tab.
     * BEVEL: boxy tab, active = raised body + accent top edge (inventory tabs look).
     * GRADIENT: transparent with accent underline indicator.
     */
    fun tab(ctx: DrawContext, tr: TextRenderer, x: Int, y: Int, w: Int, h: Int,
            label: String, active: Boolean, hovered: Boolean) {
        if (isBevel) {
            val body = when {
                active -> theme.buttonBody
                hovered -> SURFACE_HOVER
                else -> SURFACE
            }
            ctx.fill(x, y, x + w, y + h + 1, body)
            if (active) {
                ctx.fill(x, y, x + w, y + 2, accent())
            } else {
                ctx.fill(x, y, x + w, y + 1, theme.frameDark)
            }
            val color = if (active || hovered) TEXT else TEXT_MUTED
            ctx.drawText(tr, label, x + 4, y + 3, color, true)
        } else {
            if (active) {
                ctx.fillGradient(x, y, x + w, y + h, withAlpha(accent(), 0x33), withAlpha(accent(), 0x11))
                ctx.fill(x, y + h - 1, x + w, y + h + 1, accent())
            } else if (hovered) {
                ctx.fill(x, y, x + w, y + h, SURFACE_HOVER)
                ctx.fill(x, y + h - 1, x + w, y + h, withAlpha(accent(), 0x66))
            }
            val color = if (active) accent() else if (hovered) TEXT else TEXT_MUTED
            ctx.drawText(tr, label, x + 4, y + 2, color, true)
        }
    }

    /** Small colored chip/badge with translucent fill (rarity, types, variants). */
    fun chip(ctx: DrawContext, tr: TextRenderer, x: Int, y: Int, label: String, color: Int): Int {
        val w = tr.getWidth(label) + 8
        val h = 11
        ctx.fill(x, y, x + w, y + h, withAlpha(color, 0x33))
        border(ctx, x, y, w, h, withAlpha(color, 0x88))
        ctx.drawText(tr, label, x + 4, y + 2, color, true)
        return w
    }

    /**
     * Scrollbar: sunken track + accent thumb.
     * Returns thumbY to height, or null when no scroll is needed.
     */
    fun scrollbar(ctx: DrawContext, x: Int, top: Int, bottom: Int,
                  contentHeight: Int, scrollOffset: Int): Pair<Int, Int>? {
        val areaHeight = bottom - top
        if (contentHeight <= areaHeight || areaHeight <= 0) return null
        ctx.fill(x, top, x + 3, bottom, SURFACE_SUNKEN)
        val thumbH = maxOf(15, areaHeight * areaHeight / contentHeight)
        val maxScroll = contentHeight - areaHeight
        val thumbY = top + (scrollOffset * (areaHeight - thumbH) / maxOf(1, maxScroll))
        val thumbColor = accent()
        ctx.fill(x, thumbY, x + 3, thumbY + thumbH, thumbColor)
        ctx.fill(x, thumbY, x + 3, thumbY + 1, withAlpha(thumbColor, 0x88))
        ctx.fill(x, thumbY + thumbH - 1, x + 3, thumbY + thumbH, withAlpha(thumbColor, 0x88))
        return thumbY to thumbH
    }

    /** Standard row highlight for hoverable list rows. */
    fun rowHighlight(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        if (isBevel) {
            ctx.fill(x, y, x + w, y + h, 0x22FFFFFF)
            ctx.fill(x, y, x + 2, y + h, accent())
        } else {
            ctx.fillGradient(x, y, x + w, y + h, withAlpha(accent(), 0x28), withAlpha(accent(), 0x10))
            ctx.fill(x, y, x + 2, y + h, accent())
        }
    }
}
