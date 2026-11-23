package com.hunterboard

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.texture.NativeImage
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * The official Tropimon Pokéradar look, stretched to the size of HunterBoard's radar.
 *
 * HunterBoard does not ship these textures: it reads them from the Tropimon client mod
 * (namespace `tropimodclient`) and from Cobblemon when the game has them, so the skin
 * only shows on a Tropimon install. Anywhere else [available] is false and the radar
 * keeps the HunterBoard theme.
 *
 * The frame texture is 218×206. Its outer columns and rows carry the device's details
 * (grips, title strip, banner, bottom bar, scroll track) and are drawn 1:1. The middle
 * column and the middle row are uniform in the texture and are stretched to fill.
 */
object RadarSkin {
    private val FRAME = Identifier.of("tropimodclient", "guis/pokeradar/pokeradar.png")
    private val FAVICON = Identifier.of("tropimodclient", "guis/pokeradar/pokeradar_favicon.png")
    private val RELOAD = Identifier.of("tropimodclient", "guis/pokeradar/pokeradar_reload_button.png")
    private val RELOAD_HOVER = Identifier.of("tropimodclient", "guis/pokeradar/pokeradar_reload_hover.png")
    private val SLOT = Identifier.of("cobblemon", "textures/gui/pokedex/pokedex_slot.png")
    private val UNIFORM = Identifier.of("minecraft", "uniform")

    private const val TEX_W = 218
    private const val TEX_H = 206

    /** Texture slices: [0, 27) and [189, 218) across, [0, 87) and [128, 206) down are kept 1:1. */
    private val TEX_COLS = intArrayOf(0, 27, 189, 218)
    private val TEX_ROWS = intArrayOf(0, 87, 128, 206)
    /** A column and a row of the stretched middle, sampled 1 px wide so nothing else gets stretched. */
    private const val MID_U = 30
    private const val MID_V = 100

    /** Smallest frame that still shows every fixed slice. */
    const val MIN_H = 87 + 206 - 128

    // Positions inside a frame of size w×h (from the texture)
    const val GRID_X = 27            // left edge of the banner, the list and the bottom bar
    const val CONTENT_TOP = 39       // just under the banner
    const val CONTENT_BOTTOM = 30    // from the bottom: just above the bottom bar
    const val TRACK_FROM_RIGHT = 29  // scroll track: x = w - 29, 3 px wide
    const val TRACK_W = 3
    const val TRACK_TOP = 40
    const val TRACK_FROM_BOTTOM = 32
    const val BANNER_Y = 26          // banner rows 26..36
    const val BANNER_H = 11
    const val BAR_FROM_BOTTOM = 28   // bottom bar rows h-28 .. h-18
    const val BAR_H = 11
    const val RELOAD_FROM_RIGHT = 37 // reload button: 8×8 at (w - 37, 28), like Tropimon's
    const val RELOAD_SIZE = 8

    /** True when the frame texture is loaded and still has the layout this code was written for. */
    fun available(): Boolean = runCatching {
        val rm = MinecraftClient.getInstance().resourceManager
        val res = rm.getResource(FRAME).orElse(null) ?: return false
        if (rm.getResource(SLOT).isEmpty) return false
        res.inputStream.use { NativeImage.read(it) }.use { it.width == TEX_W && it.height == TEX_H }
    }.getOrDefault(false)

    /** The screen inside the device in the texture: x 18..199, y 11..193 (its blue border included). */
    private const val SCREEN_LEFT = 18
    private const val SCREEN_RIGHT = TEX_W - 200
    private const val SCREEN_TOP = 11
    private const val SCREEN_BOTTOM = TEX_H - 194

    /** Multiplier of the dark screen: the cyan becomes a deep blue-grey, its bands still showing. */
    private val DARK = floatArrayOf(0.30f, 0.34f, 0.40f)
    private val DARK_SLOT = floatArrayOf(0.42f, 0.48f, 0.55f)

    /**
     * The frame stretched to w×h. With [dark], the screen inside the device is drawn a second
     * time through a colour multiplier: same banner, bands and scroll track, but dark; the
     * casing and the grips keep their colours.
     */
    fun drawFrame(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, dark: Boolean = false) {
        blended { slices(ctx, x, y, w, h, null) }
        if (!dark) return
        val clip = intArrayOf(x + SCREEN_LEFT, y + SCREEN_TOP, x + w - SCREEN_RIGHT, y + h - SCREEN_BOTTOM)
        RenderSystem.setShaderColor(DARK[0], DARK[1], DARK[2], 1f)
        try { blended { slices(ctx, x, y, w, h, clip) } } finally { RenderSystem.setShaderColor(1f, 1f, 1f, 1f) }
    }

    /** The nine slices of the frame, optionally only their part inside [clip] (x1, y1, x2, y2). */
    private fun slices(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, clip: IntArray?) {
        val dx = intArrayOf(x, x + TEX_COLS[1], x + w - (TEX_W - TEX_COLS[2]), x + w)
        val dy = intArrayOf(y, y + TEX_ROWS[1], y + h - (TEX_H - TEX_ROWS[2]), y + h)
        for (i in 0..2) for (j in 0..2) {
            var x1 = dx[i]; var x2 = dx[i + 1]; var y1 = dy[j]; var y2 = dy[j + 1]
            var u = if (i == 1) MID_U else TEX_COLS[i]
            var v = if (j == 1) MID_V else TEX_ROWS[j]
            var rw = if (i == 1) 1 else TEX_COLS[i + 1] - TEX_COLS[i]
            var rh = if (j == 1) 1 else TEX_ROWS[j + 1] - TEX_ROWS[j]
            if (clip != null) {
                // outer slices are 1:1, so cutting them shifts the texture by the same amount;
                // stretched ones sample a single uniform pixel and need no shift
                val cx1 = maxOf(x1, clip[0]); val cx2 = minOf(x2, clip[2])
                val cy1 = maxOf(y1, clip[1]); val cy2 = minOf(y2, clip[3])
                if (i != 1) { u += cx1 - x1; rw = cx2 - cx1 }
                if (j != 1) { v += cy1 - y1; rh = cy2 - cy1 }
                x1 = cx1; x2 = cx2; y1 = cy1; y2 = cy2
            }
            if (x2 <= x1 || y2 <= y1 || rw <= 0 || rh <= 0) continue
            ctx.drawTexture(FRAME, x1, y1, x2 - x1, y2 - y1, u.toFloat(), v.toFloat(), rw, rh, TEX_W, TEX_H)
        }
    }

    /** The 14×14 favicon drawn at 7×7, as Tropimon does. */
    fun drawFavicon(ctx: DrawContext, x: Int, y: Int) = blended {
        ctx.drawTexture(FAVICON, x, y, 7, 7, 0f, 0f, 14, 14, 14, 14)
    }

    fun drawReload(ctx: DrawContext, x: Int, y: Int, hovered: Boolean) = blended {
        ctx.drawTexture(if (hovered) RELOAD_HOVER else RELOAD, x, y, RELOAD_SIZE, RELOAD_SIZE,
            0f, 0f, 16, 16, 16, 16)
    }

    /** Cobblemon's Pokédex slot (25×25, 1 px border) cut in nine so its border stays 1 px at any size. */
    fun drawSlot(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, dark: Boolean = false) = blended {
        if (dark) RenderSystem.setShaderColor(DARK_SLOT[0], DARK_SLOT[1], DARK_SLOT[2], 1f)
        try { slot(ctx, x, y, w, h) } finally { RenderSystem.setShaderColor(1f, 1f, 1f, 1f) }
    }

    private fun slot(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        val s = 25
        val xs = intArrayOf(x, x + 1, x + w - 1, x + w); val us = intArrayOf(0, 1, s - 1, s)
        val ys = intArrayOf(y, y + 1, y + h - 1, y + h); val vs = intArrayOf(0, 1, s - 1, s)
        for (i in 0..2) for (j in 0..2) {
            val dw = xs[i + 1] - xs[i]; val dh = ys[j + 1] - ys[j]
            if (dw <= 0 || dh <= 0) continue
            ctx.drawTexture(SLOT, xs[i], ys[j], dw, dh, us[i].toFloat(), vs[j].toFloat(),
                us[i + 1] - us[i], vs[j + 1] - vs[j], s, s)
        }
    }

    /**
     * Title and banner text in bold. With [fine] (at least 2 real pixels per GUI pixel) it uses
     * the "uniform" font Tropimon's radar uses; below that its 16 px glyphs are halved into
     * an unreadable smear, so the normal Minecraft font takes over.
     */
    fun heading(s: String, fine: Boolean): Text =
        Text.literal(s).setStyle(if (fine) Style.EMPTY.withFont(UNIFORM).withBold(true) else Style.EMPTY.withBold(true))

    /** Smaller text inside the frame (filter, legend): the same font choice as [heading], not bold. */
    fun label(s: String, fine: Boolean): Text =
        Text.literal(s).setStyle(if (fine) Style.EMPTY.withFont(UNIFORM) else Style.EMPTY)

    private inline fun blended(draw: () -> Unit) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        try { draw() } finally { RenderSystem.disableBlend() }
    }
}
