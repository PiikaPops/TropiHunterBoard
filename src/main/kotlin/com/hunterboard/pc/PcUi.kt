package com.hunterboard.pc

import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.Pokemon
import com.hunterboard.UiKit
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import java.util.UUID

/** Colour of an IV (0–31) or an IV percentage, red → yellow → green. */
internal fun ivColor(v: Int, max: Int = 31): Int {
    val t = (v.toFloat() / max).coerceIn(0f, 1f)
    return when {
        t >= 0.999f -> 0xFF5CFFB0.toInt()
        t >= 0.8f -> 0xFF8BE36A.toInt()
        t >= 0.6f -> 0xFFD9E05A.toInt()
        t >= 0.35f -> 0xFFF0A848.toInt()
        else -> 0xFFF0645A.toInt()
    }
}

internal const val SHINY_COLOR = 0xFFFFD75E.toInt()
internal const val ALPHA_COLOR = 0xFFFF5A5A.toInt()
internal const val FAV_COLOR = 0xFFFF8AC8.toInt()
internal const val LEGEND_COLOR = 0xFFB98CFF.toInt()
/** A team member whose EVs are not its Showdown set's. */
internal const val EV_WARN = 0xFFFF9A3C.toInt()
internal const val ITEM_COLOR = 0xFF7FD4FF.toInt()

internal fun fit(tr: TextRenderer, s: String, w: Int): String {
    if (tr.getWidth(s) <= w) return s
    var t = s
    while (t.isNotEmpty() && tr.getWidth("$t…") > w) t = t.dropLast(1)
    return "$t…"
}

/**
 * Small 3D portraits, cached per Pokémon and size and dropped when no longer drawn.
 *
 * Cobblemon's ModelWidget clips itself with `enableScissor`, which ignores the matrix
 * stack. Under HunterBoard's interface-scale cap the screen is drawn through a scale
 * matrix, so the widget is moved to real pixels and drawn with that scale undone: its
 * clip then lands where the model actually is.
 */
internal class Portraits {
    private class Slot(val pokemon: Pokemon, val widget: ModelWidget)
    private val cache = HashMap<Long, Slot>()
    private val used = HashSet<Long>()

    fun draw(ctx: DrawContext, pokemon: Pokemon, id: UUID, x: Int, y: Int, size: Int, factor: Float, delta: Float) {
        val key = id.mostSignificantBits xor id.leastSignificantBits * 31 + size
        var slot = cache[key]
        if (slot == null || slot.pokemon !== pokemon) {
            val w = runCatching {
                ModelWidget(
                    pX = 0, pY = 0, pWidth = size, pHeight = size,
                    pokemon = pokemon.asRenderablePokemon(),
                    baseScale = size * SCALE, rotationY = 325f, offsetY = -size * OFFSET,
                    playCryOnClick = false, shouldFollowCursor = false
                )
            }.getOrNull() ?: return
            slot = Slot(pokemon, w).also { cache[key] = it }
        }
        used += key
        val w = slot.widget
        try {
            if (factor >= 1f) {
                w.x = x; w.y = y; w.width = size; w.height = size
                w.baseScale = size * SCALE; w.offsetY = -size * OFFSET
                w.render(ctx, -9999, -9999, delta)
            } else {
                ctx.matrices.push()
                ctx.matrices.scale(1f / factor, 1f / factor, 1f)
                w.x = kotlin.math.round(x * factor).toInt(); w.y = kotlin.math.round(y * factor).toInt()
                w.width = kotlin.math.round(size * factor).toInt(); w.height = w.width
                w.baseScale = size * SCALE * factor; w.offsetY = -size * OFFSET * factor
                w.render(ctx, -9999, -9999, delta)
                ctx.matrices.pop()
            }
        } catch (_: Exception) {}
    }

    private companion object {
        /** Model scale and anchor per pixel of box, tuned on screenshots (the radar uses 0.028 / 0.25). */
        const val SCALE = 0.038f
        const val OFFSET = 0.42
    }

    /** Call once per frame, after everything is drawn. */
    fun endFrame() { cache.keys.retainAll(used); used.clear() }
}

/** One line of a popup menu. */
internal class MenuItem(
    val label: String,
    val color: Int = 0,
    val enabled: Boolean = true,
    val separatorBefore: Boolean = false,
    val onDelete: (() -> Unit)? = null,
    val action: () -> Unit,
)

/** A popup list under a button; scrolls when long. Closes on any click outside. */
internal class PcMenu(var x: Int, var y: Int, val items: List<MenuItem>, val title: String? = null) {
    private val rowH = 11
    var w = 0; private set
    var h = 0; private set
    private var scroll = 0
    private var maxRows = 0

    fun layout(tr: TextRenderer, screenW: Int, screenH: Int) {
        w = (items.maxOfOrNull { tr.getWidth(it.label) + (if (it.onDelete != null) 14 else 0) } ?: 40) + 12
        if (title != null) w = maxOf(w, tr.getWidth(title) + 12)
        w = w.coerceAtMost(screenW - 8)
        maxRows = ((screenH * 0.6f).toInt() / rowH).coerceAtLeast(4)
        val rows = minOf(items.size, maxRows)
        h = rows * rowH + 4 + (if (title != null) 12 else 0)
        x = x.coerceIn(2, maxOf(2, screenW - w - 2))
        if (y + h > screenH - 2) y = maxOf(2, screenH - h - 2)
        scroll = scroll.coerceIn(0, maxOf(0, items.size - maxRows))
    }

    fun render(ctx: DrawContext, tr: TextRenderer, mx: Int, my: Int) {
        ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 500f)
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000.toInt())
        ctx.fill(x, y, x + w, y + h, 0xF0161A20.toInt())
        UiKit.border(ctx, x, y, w, h, UiKit.withAlpha(UiKit.accent(), 0x99))
        var yy = y + 2
        if (title != null) {
            ctx.drawText(tr, title, x + 6, yy + 1, UiKit.TEXT_FAINT, false)
            yy += 12
        }
        val rows = minOf(items.size, maxRows)
        for (r in 0 until rows) {
            val it = items[r + scroll]
            if (it.separatorBefore && r > 0) ctx.fill(x + 4, yy, x + w - 4, yy + 1, 0x40FFFFFF)
            val hov = mx in x until x + w && my in yy until yy + rowH && it.enabled
            if (hov) ctx.fill(x + 1, yy, x + w - 1, yy + rowH, UiKit.withAlpha(UiKit.accent(), 0x40))
            val col = if (!it.enabled) UiKit.TEXT_FAINT else if (it.color != 0) it.color else if (hov) 0xFFFFFFFF.toInt() else UiKit.TEXT
            ctx.drawText(tr, fit(tr, it.label, w - 12 - (if (it.onDelete != null) 12 else 0)), x + 6, yy + 2, col, false)
            if (it.onDelete != null) {
                val dh = mx in (x + w - 12) until (x + w - 2) && my in yy until yy + rowH
                ctx.drawText(tr, "✕", x + w - 10, yy + 2, if (dh) UiKit.DANGER else UiKit.TEXT_FAINT, false)
            }
            yy += rowH
        }
        if (items.size > maxRows) {
            val track = rows * rowH
            val th = maxOf(8, track * maxRows / items.size)
            val ty = y + 2 + (if (title != null) 12 else 0) + (track - th) * scroll / maxOf(1, items.size - maxRows)
            ctx.fill(x + w - 3, ty, x + w - 1, ty + th, UiKit.accent())
        }
        ctx.matrices.pop()
    }

    fun contains(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h

    /** True when the click landed on the menu (the caller then closes it). */
    fun click(mx: Double, my: Double): Boolean {
        if (!contains(mx, my)) return false
        val top = y + 2 + (if (title != null) 12 else 0)
        val r = ((my - top) / rowH).toInt()
        if (r < 0) return true
        val it = items.getOrNull(r + scroll) ?: return true
        if (!it.enabled) return true
        if (it.onDelete != null && mx >= x + w - 12) it.onDelete.invoke() else it.action()
        return true
    }

    fun scroll(amount: Double) { scroll = (scroll - amount.toInt().coerceIn(-3, 3)).coerceIn(0, maxOf(0, items.size - maxRows)) }

    /** The lines shown that can be picked, as (x, y, w, h): where a controller's cursor can rest. */
    fun rows(): List<IntArray> {
        val top = y + 2 + (if (title != null) 12 else 0)
        return (0 until minOf(items.size, maxRows)).filter { items[it + scroll].enabled }.map { intArrayOf(x, top + it * rowH, w, rowH) }
    }
}

/** Base of the windows drawn over the PC (confirmations, managers, comparator). */
internal abstract class PcDialog(val screen: HunterPcScreen, val w: Int, val h: Int) {
    var x = 0; var y = 0
    val tr: TextRenderer get() = screen.tr

    fun place(sw: Int, sh: Int) { x = (sw - w) / 2; y = ((sh - h) / 2).coerceAtLeast(4) }

    /** The buttons drawn in the last frame, as (x, y, w, h): where a controller's cursor can rest. */
    val targets = ArrayList<IntArray>()

    open fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        targets.clear()
        ctx.fill(0, 0, screen.width, screen.height, 0x90000000.toInt())
        UiKit.panel(ctx, x, y, w, h)
    }

    abstract fun click(mx: Double, my: Double, button: Int): Boolean
    open fun key(keyCode: Int, mods: Int): Boolean = false
    open fun char(c: Char): Boolean = false
    open fun scroll(mx: Double, my: Double, amount: Double): Boolean = false
    open fun closed() {}

    protected fun title(ctx: DrawContext, text: String) {
        if (UiKit.isModern) ctx.drawText(tr, text, x + 8, y + 7, 0xFFFFFFFF.toInt(), true)
        else UiKit.header(ctx, tr, x, y, w, text)
    }

    protected fun inside(mx: Number, my: Number, bx: Int, by: Int, bw: Int, bh: Int) =
        mx.toDouble() >= bx && mx.toDouble() < bx + bw && my.toDouble() >= by && my.toDouble() < by + bh

    protected fun button(ctx: DrawContext, bx: Int, by: Int, bw: Int, label: String, mx: Int, my: Int, active: Boolean = false, enabled: Boolean = true) {
        if (enabled) targets += intArrayOf(bx, by, bw, 14)
        if (!enabled) {
            UiKit.slot(ctx, bx, by, bw, 14)
            ctx.drawText(tr, label, bx + (bw - tr.getWidth(label)) / 2, by + 3, UiKit.TEXT_FAINT, false)
            return
        }
        UiKit.button(ctx, tr, bx, by, bw, 14, label, inside(mx, my, bx, by, bw, 14), active)
    }

    /** A zone of the window: darker than the window, framed, with its title on a band. */
    protected fun zone(ctx: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int, title: String?) {
        ctx.fill(x1, y1, x2, y2, ZONE)
        UiKit.border(ctx, x1, y1, x2 - x1, y2 - y1, UiKit.BORDER_DIM)
        if (title != null) {
            ctx.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 13, BAND)
            UiKit.sectionTitle(ctx, tr, title, x1 + 4, y1 + 3)
        }
    }

    /** Zones darker than the window, wells darker still, cards lighter: whatever the theme. */
    protected companion object Style {
        const val ZONE = 0x30000000
        const val BAND = 0x38000000
        const val WELL = 0x48000000
    }

    protected fun danger(ctx: DrawContext, bx: Int, by: Int, bw: Int, label: String, mx: Int, my: Int) {
        targets += intArrayOf(bx, by, bw, 14)
        val hov = inside(mx, my, bx, by, bw, 14)
        ctx.fill(bx, by, bx + bw, by + 14, if (hov) 0xFFB83A3A.toInt() else 0xFF8E2A2A.toInt())
        UiKit.border(ctx, bx, by, bw, 14, if (hov) 0xFFFF8A8A.toInt() else 0xFFCC5555.toInt())
        ctx.drawText(tr, label, bx + (bw - tr.getWidth(label)) / 2, by + 3, 0xFFFFFFFF.toInt(), true)
    }
}

/** A tooltip prepared while drawing and drawn last, above everything. */
internal class PcTooltip {
    val lines = mutableListOf<Pair<String, Int>>()
    var x = 0; var y = 0
    fun set(mx: Int, my: Int, vararg l: Pair<String, Int>) { lines.clear(); lines += l; x = mx; y = my }
    fun clear() = lines.clear()

    fun draw(ctx: DrawContext, tr: TextRenderer, screenW: Int, screenH: Int) {
        if (lines.isEmpty()) return
        // long lines wrap at 200 px
        val wrapped = lines.flatMap { (t, c) -> tr.wrapLines(net.minecraft.text.Text.literal(t), 200).map { it to c } }
        val w = wrapped.maxOf { tr.getWidth(it.first) } + 10
        val h = wrapped.size * 10 + 6
        val tx = (x + 12).let { if (it + w > screenW - 2) x - w - 6 else it }.coerceAtLeast(2)
        val ty = (y - 4).coerceIn(2, maxOf(2, screenH - h - 2))
        ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 800f)
        ctx.fill(tx, ty, tx + w, ty + h, 0xF0100F14.toInt())
        UiKit.border(ctx, tx, ty, w, h, UiKit.withAlpha(UiKit.accent(), 0xA0))
        wrapped.forEachIndexed { i, (t, c) -> ctx.drawText(tr, t, tx + 5, ty + 4 + i * 10, c, i == 0) }
        ctx.matrices.pop()
    }
}

/**
 * Moves drawn exactly as Cobblemon draws them, with its own textures and widgets: the battle
 * screen's move button for the active moves (type badge, category icon, bold name, PP) and
 * the summary's "change move" row for the reserve (power, accuracy and effect icons).
 * Everything goes through the matrix stack, so the interface-scale cap applies.
 */
internal object MoveTiles {
    private fun id(p: String) = net.minecraft.util.Identifier.of("cobblemon", p)
    private val BATTLE = id("textures/gui/battle/battle_move.png")                     // 92×48, normal + hovered
    private val BATTLE_OVERLAY = id("textures/gui/battle/battle_move_overlay.png")     // 92×24
    private val ROW = id("textures/gui/summary/summary_move_condensed.png")            // 91×36, normal + hovered
    private val ROW_OVERLAY = id("textures/gui/summary/summary_move_overlay_condensed.png")
    private val POWER = id("textures/gui/summary/summary_moves_icon_power.png")
    private val ACCURACY = id("textures/gui/summary/summary_moves_icon_accuracy.png")
    private val EFFECT = id("textures/gui/summary/summary_moves_icon_effect.png")

    /** Size of a battle button; its type badge sticks out [BADGE] pixels on the left. */
    const val TILE_W = 92
    const val TILE_H = 24
    const val ROW_W = 91
    const val ROW_H = 18
    const val BADGE = 9

    fun rgb(type: com.cobblemon.mod.common.api.types.ElementalType): FloatArray {
        val h = type.hue
        return floatArrayOf(((h shr 16) and 255) / 255f, ((h shr 8) and 255) / 255f, (h and 255) / 255f)
    }

    fun color(type: com.cobblemon.mod.common.api.types.ElementalType): Int = 0xFF000000.toInt() or (type.hue and 0xFFFFFF)

    private fun blit(ctx: DrawContext, tex: net.minecraft.util.Identifier, x: Number, y: Number, h: Number, w: Number,
                     v: Number = 0, tw: Number = w, th: Number = h, c: FloatArray? = null, scale: Float = 1f) =
        com.cobblemon.mod.common.api.gui.blitk(ctx.matrices, tex, x, y, h, w, 0, v, tw, th, 0,
            c?.get(0) ?: 1f, c?.get(1) ?: 1f, c?.get(2) ?: 1f, 1f, true, scale)

    private fun text(ctx: DrawContext, font: net.minecraft.util.Identifier?, t: net.minecraft.text.MutableText, x: Number, y: Number,
                     scale: Float, centered: Boolean, shadow: Boolean) =
        com.cobblemon.mod.common.client.render.drawScaledText(ctx, font, t, x, y, scale, 1f, Int.MAX_VALUE, 0xFFFFFFFF.toInt(), centered, shadow, null, null)

    private fun badges(ctx: DrawContext, t: com.cobblemon.mod.common.api.moves.MoveTemplate, x: Number, y: Number, catX: Number, catY: Number) {
        com.cobblemon.mod.common.client.gui.TypeIcon(x, y, t.elementalType, null, false, false, 15f, 7.5f, 1f).render(ctx)
        com.cobblemon.mod.common.client.gui.MoveCategoryIcon(catX, catY, t.damageCategory, 1f).render(ctx)
    }

    /** The battle move button, 92×24 at (x, y). */
    fun battle(ctx: DrawContext, tr: TextRenderer, x: Int, y: Int, t: com.cobblemon.mod.common.api.moves.MoveTemplate?,
               pp: Int, maxPp: Int, hovered: Boolean, selected: Boolean = false, fine: Boolean = true) {
        // Cobblemon's battle font needs 2 real pixels per GUI pixel; below that, Minecraft's
        val font = if (fine) com.cobblemon.mod.common.client.CobblemonResources.DEFAULT_LARGE else null
        if (selected) ctx.fill(x - BADGE - 2, y - 2, x + TILE_W + 2, y + TILE_H + 2, UiKit.withAlpha(UiKit.accentBright(), 0xC0))
        if (t == null) {
            ctx.fill(x, y, x + TILE_W, y + TILE_H, 0xFF1A1D22.toInt())
            UiKit.border(ctx, x, y, TILE_W, TILE_H, if (hovered) UiKit.BORDER else UiKit.BORDER_DIM)
            val e = L("— empty —", "— vide —")
            ctx.drawText(tr, e, x + (TILE_W - tr.getWidth(e)) / 2, y + 8, UiKit.TEXT_FAINT, false)
            return
        }
        blit(ctx, BATTLE, x, y, TILE_H, TILE_W, if (hovered) TILE_H else 0, TILE_W, TILE_H * 2, rgb(t.elementalType))
        blit(ctx, BATTLE_OVERLAY, x, y, TILE_H, TILE_W)
        badges(ctx, t, x - BADGE, y + 2, x + 48, y + 14.5)
        // the battle font is wide: a long name is narrowed to stay on the button, as it would wrap otherwise
        val name = t.displayName.copy().setStyle(net.minecraft.text.Style.EMPTY.withBold(true))
        val w = tr.getWidth(if (font != null) name.copy().setStyle(name.style.withFont(font)) else name)
        val k = if (w > 72) 72f / w else 1f
        ctx.matrices.push()
        ctx.matrices.translate((x + 17).toFloat(), (y + 2 + (1 - k) * 4).toFloat(), 0f)
        ctx.matrices.scale(k, k, 1f)
        text(ctx, font, name, 0, 0, 1f, false, true)
        ctx.matrices.pop()
        val ppText = net.minecraft.text.Text.literal("$pp/$maxPp").setStyle(net.minecraft.text.Style.EMPTY.withBold(true)
            .withColor(if (pp == 0) 0xFF5555 else if (pp <= maxPp / 2) 0xFFAA00 else 0xFFFFFF))
        text(ctx, font, ppText, x + 75, y + 14, 1f, true, false)
    }

    /** The summary's "change move" row, 91×18 at (x, y). */
    fun row(ctx: DrawContext, x: Int, y: Int, t: com.cobblemon.mod.common.api.moves.MoveTemplate, hovered: Boolean, selected: Boolean = false) {
        if (selected) ctx.fill(x - BADGE - 2, y - 1, x + ROW_W + 2, y + ROW_H + 1, UiKit.withAlpha(UiKit.accentBright(), 0xC0))
        blit(ctx, ROW, x, y, ROW_H, ROW_W, if (hovered) ROW_H else 0, ROW_W, ROW_H * 2, rgb(t.elementalType))
        blit(ctx, ROW_OVERLAY, x, y, ROW_H, ROW_W)
        badges(ctx, t, x - BADGE, y, x + 77, y + 1.5)
        text(ctx, null, t.displayName.copy(), x + 14, y + 3.5, 0.5f, false, true)
        blit(ctx, POWER, (x + 10) / 0.5f, (y + 11) / 0.5f, 10, 10, scale = 0.5f)
        blit(ctx, ACCURACY, (x + 30) / 0.5f, (y + 11) / 0.5f, 10, 10, scale = 0.5f)
        blit(ctx, EFFECT, (x + 53.5f) / 0.5f, (y + 11) / 0.5f, 10, 10, scale = 0.5f)
        fun pct(d: Double) = if (d <= 0.0) "—" else "${(d * 100).toInt() / 100.0}".removeSuffix(".0") + "%"
        text(ctx, null, net.minecraft.text.Text.literal(if (t.power > 0) t.power.toInt().toString() else "—"), x + 16.5, y + 12, 0.5f, false, true)
        text(ctx, null, net.minecraft.text.Text.literal(pct(t.accuracy)), x + 37, y + 12, 0.5f, false, true)
        text(ctx, null, net.minecraft.text.Text.literal(pct(t.effectChances.firstOrNull() ?: 0.0)), x + 60.5, y + 12, 0.5f, false, true)
        text(ctx, null, net.minecraft.text.Text.translatable("cobblemon.ui.moves.pp", t.pp), x + 76, y + 12, 0.5f, false, true)
    }

    /** Tooltip lines for a move: type, category, power, accuracy, PP, priority, description. */
    fun tooltip(t: com.cobblemon.mod.common.api.moves.MoveTemplate, ppText: String?): Array<Pair<String, Int>> {
        val out = mutableListOf(t.displayName.string to 0xFFFFFFFF.toInt())
        val type = runCatching { t.elementalType.displayName.string }.getOrDefault("")
        val cat = runCatching { t.damageCategory.displayName.string }.getOrDefault("")
        out += "$type · $cat" to color(t.elementalType)
        val power = if (t.power > 0) t.power.toInt().toString() else "—"
        val acc = if (t.accuracy > 0) "${t.accuracy.toInt()} %" else "—"
        out += (L("Power ", "Puissance ") + power + " · " + L("Accuracy ", "Précision ") + acc) to UiKit.TEXT
        out += ("PP " + (ppText ?: t.pp.toString()) + if (t.priority != 0) " · " + L("Priority ", "Priorité ") + (if (t.priority > 0) "+" else "") + t.priority else "") to UiKit.TEXT_MUTED
        val desc = runCatching { t.description.string }.getOrDefault("")
        if (desc.isNotBlank()) out += desc to UiKit.TEXT_FAINT
        return out.toTypedArray()
    }
}

/** Cobblemon marks ("insignes") as their own icons, with their title and description on hover. */
internal object MarkIcons {
    fun draw(ctx: DrawContext, mark: com.cobblemon.mod.common.api.mark.Mark, x: Int, y: Int, size: Int, active: Boolean, hovered: Boolean) {
        if (active || hovered) ctx.fill(x - 1, y - 1, x + size + 1, y + size + 1, if (hovered) 0x50FFFFFF else UiKit.withAlpha(UiKit.GOLD, 0x50))
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        ctx.drawTexture(mark.texture, x, y, size, size, 0f, 0f, 32, 32, 32, 32)
        com.mojang.blaze3d.systems.RenderSystem.disableBlend()
    }

    fun tooltip(mark: com.cobblemon.mod.common.api.mark.Mark, pokemonName: String, active: Boolean): Array<Pair<String, Int>> {
        val color = runCatching { 0xFF000000.toInt() or mark.titleColour!!.toInt(16) }.getOrDefault(UiKit.GOLD)
        val title = runCatching { net.minecraft.text.Text.translatable(mark.title, pokemonName).string }.getOrDefault("")
        val name = runCatching { net.minecraft.text.Text.translatable(mark.name).string }.getOrDefault(mark.name)
        val desc = runCatching { net.minecraft.text.Text.translatable(mark.description).string }.getOrDefault("")
        val out = mutableListOf(name to 0xFFFFFFFF.toInt())
        if (title.isNotBlank()) out += "« $title »" to color
        if (desc.isNotBlank()) out += desc to UiKit.TEXT_MUTED
        if (active) out += L("Shown as the Pokémon's title", "Affiché comme titre du Pokémon") to UiKit.GOLD
        return out.toTypedArray()
    }
}

/** Cobblemon's own round type icons, as its summary draws them: 18 px, or 9 px small. */
internal object TypeIcons {
    const val SIZE = 18

    fun width(n: Int, small: Boolean = false): Int {
        if (n <= 0) return 0
        return if (small) n * 10 - 1 else n * (SIZE + 2) - 2
    }

    fun draw(ctx: DrawContext, types: List<com.cobblemon.mod.common.api.types.ElementalType>, x: Int, y: Int,
             small: Boolean = false, opacity: Float = 1f) {
        val step = if (small) 10 else SIZE + 2
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
        types.forEachIndexed { i, t ->
            runCatching { com.cobblemon.mod.common.client.gui.TypeIcon(x + i * step, y, t, null, false, small, 0f, 0f, opacity).render(ctx) }
        }
    }

    fun tooltip(types: List<com.cobblemon.mod.common.api.types.ElementalType>): Array<Pair<String, Int>> =
        types.map { t -> runCatching { t.displayName.string }.getOrDefault(t.name) to MoveTiles.color(t) }.toTypedArray()
}
