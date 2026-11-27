package com.hunterboard.pc

import com.hunterboard.UiKit
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

// ─────────────────────────────────────────────────────────────────────────────
// Release
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The last word before a batch release: every Pokémon listed, the protected ones greyed
 * out with the reason, protections that can be relaxed, and a button that must be pressed
 * twice. Favourites and the party can never be released from here.
 */
internal class ReleaseDialog(screen: HunterPcScreen, private val list: List<PcEntry>, startGuards: ReleaseGuards = ReleaseGuards.DEFAULT) :
    PcDialog(screen, 300, minOf(250, screen.height - 16)) {
    private var guards = startGuards
    private var scroll = 0
    private var armedUntil = 0L
    private val rows get() = list.sortedWith(compareBy({ if (it.place.inParty) -1 else it.place.box }, { it.place.slot }))
        .map { it to PcQuery.protection(it, screen.prefs, guards) }

    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        val r = rows
        val go = r.count { it.second == null }
        title(ctx, L("Release ${list.size} Pokémon", "Relâcher ${list.size} Pokémon"))
        var y = y + 24
        ctx.drawText(tr, L("Protect:", "Protéger :"), x + 8, y + 2, UiKit.TEXT_FAINT, false)
        var cx = x + 8 + tr.getWidth(L("Protect:", "Protéger :")) + 4
        for ((label, on, flip) in guardChips()) {
            val w = tr.getWidth(label) + 14
            if (cx + w > x + w0()) { cx = x + 8; y += 13 }
            val hov = inside(mx, my, cx, y, w, 11)
            ctx.fill(cx, y, cx + w, y + 11, if (on) UiKit.withAlpha(UiKit.accent(), 0x30) else if (hov) UiKit.SURFACE_HOVER else UiKit.SURFACE_SUNKEN)
            UiKit.border(ctx, cx, y, w, 11, if (on) UiKit.accent() else UiKit.BORDER_DIM)
            ctx.drawText(tr, label, cx + 7, y + 2, if (on) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
            chipHits += Triple(intArrayOf(cx, y, w, 11), flip, Unit)
            cx += w + 3
        }
        y += 16
        val listTop = y; val listBottom = this.y + h - 44
        val rowH = 11
        ctx.enableScissorSafe(x + 6, listTop, x + w - 6, listBottom)
        r.forEachIndexed { i, (e, why) ->
            val ry = listTop + i * rowH - scroll
            if (ry + rowH < listTop || ry > listBottom) return@forEachIndexed
            UiKit.rowStripe(ctx, x + 6, ry, w - 12, rowH, i)
            val col = if (why == null) UiKit.TEXT else UiKit.TEXT_FAINT
            ctx.drawText(tr, fit(tr, (if (why == null) "✗ " else "· ") + e.displayName, 110), x + 8, ry + 2, if (why == null) 0xFFFF9A9A.toInt() else col, false)
            ctx.drawText(tr, "${L("Lv", "Nv")} ${e.level}", x + 122, ry + 2, col, false)
            ctx.drawText(tr, "${e.ivPct.toInt()}%", x + 152, ry + 2, if (why == null) ivColor(e.ivSum, 186) else col, false)
            val right = why ?: (if (e.place.inParty) L("Party", "Équipe") else "B${e.place.box + 1}·${e.place.slot + 1}")
            ctx.drawText(tr, fit(tr, right, w - 190), x + 182, ry + 2, if (why == null) UiKit.TEXT_FAINT else UiKit.GOLD, false)
        }
        ctx.disableScissor()
        maxScroll = maxOf(0, r.size * rowH - (listBottom - listTop))
        val sum = L("$go will be released · ${r.size - go} protected", "$go seront relâchés · ${r.size - go} protégés")
        ctx.drawText(tr, sum, x + 8, listBottom + 5, if (go > 0) 0xFFFF9A9A.toInt() else UiKit.TEXT_FAINT, false)
        ctx.drawText(tr, fit(tr, L("Final: they cannot be recovered.", "Définitif : impossible de les récupérer."), w - 16),
            x + 8, listBottom + 15, UiKit.TEXT_FAINT, false)
        val by = this.y + h - 20
        button(ctx, x + 8, by, 70, L("Cancel", "Annuler"), mx, my)
        val armed = System.currentTimeMillis() < armedUntil
        val label = if (go == 0) L("Nothing to release", "Rien à relâcher") else if (armed) L("Click again to confirm", "Cliquez encore pour confirmer") else L("RELEASE $go", "RELÂCHER $go")
        if (go == 0) button(ctx, x + w - 168, by, 160, label, mx, my, enabled = false) else danger(ctx, x + w - 168, by, 160, label, mx, my)
    }

    private fun w0() = w - 8
    private var maxScroll = 0
    private val chipHits = mutableListOf<Triple<IntArray, () -> Unit, Unit>>()

    private fun guardChips(): List<Triple<String, Boolean, () -> Unit>> {
        chipHits.clear()
        return listOf(
            Triple("Shiny", guards.shiny) { guards = guards.copy(shiny = !guards.shiny) },
            Triple(L("Legendary", "Légendaires"), guards.legendary) { guards = guards.copy(legendary = !guards.legendary) },
            Triple("Alpha", guards.alpha) { guards = guards.copy(alpha = !guards.alpha) },
            Triple(L("Marked", "Marqués"), guards.marked) { guards = guards.copy(marked = !guards.marked) },
            Triple(L("Items", "Objets"), guards.held) { guards = guards.copy(held = !guards.held) },
            Triple(L("Tagged", "Tagués"), guards.tagged) { guards = guards.copy(tagged = !guards.tagged) },
        )
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        for ((r, flip, _) in chipHits) if (inside(mx, my, r[0], r[1], r[2], r[3])) { flip(); armedUntil = 0; return true }
        val by = y + h - 20
        if (inside(mx, my, x + 8, by, 70, 14)) { screen.closeDialog(); return true }
        if (inside(mx, my, x + w - 168, by, 160, 14)) {
            val go = rows.filter { it.second == null }.map { it.first }
            if (go.isEmpty()) return true
            if (System.currentTimeMillis() >= armedUntil) { armedUntil = System.currentTimeMillis() + 3000; return true }
            screen.ops.start(PcOps.Kind.RELEASE, go.map { PcStep.Release(it.uuid, it.place) })
            screen.selection.removeAll(go.map { it.uuid }.toSet())
            screen.closeDialog()
            return true
        }
        return inside(mx, my, x, y, w, h)
    }

    override fun scroll(mx: Double, my: Double, amount: Double): Boolean {
        scroll = (scroll - (amount * 22).toInt()).coerceIn(0, maxScroll); return true
    }
}

/** Scissor in the screen's own pixels, for dialogs (same rule as ScaledScreen.scissor). */
internal fun DrawContext.enableScissorSafe(x1: Int, y1: Int, x2: Int, y2: Int) {
    val f = (net.minecraft.client.MinecraftClient.getInstance().currentScreen as? HunterPcScreen)?.factor ?: 1f
    if (f >= 1f) enableScissor(x1, y1, x2, y2)
    else enableScissor(kotlin.math.floor(x1 * f).toInt(), kotlin.math.floor(y1 * f).toInt(), kotlin.math.ceil(x2 * f).toInt(), kotlin.math.ceil(y2 * f).toInt())
}

// ─────────────────────────────────────────────────────────────────────────────
// Release candidates
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Proposes the copies worth releasing, by rules: among what (the whole PC, the selection,
 * the current search, a species, a type), what stays (the best N of each species, and those
 * with the IVs, ability, nature or gender asked for: any or all of them), and what is never
 * proposed. Nothing is released from here: it selects them (or opens the release
 * confirmation), so they can be looked over first. The rules are kept in the PC's prefs.
 */
internal class CandidatesDialog(screen: HunterPcScreen) : PcDialog(screen, 384, minOf(318, screen.height - 16)) {
    private var list: List<PcEntry> = emptyList()
    private var scopeSize = 0
    private var builtFrom: List<PcEntry>? = null
    private var stale = true
    private var scroll = 0
    private var maxScroll = 0
    /** A dropdown's list, drawn and clicked inside the window (the screen's menus sit below it). */
    private var popup: PcMenu? = null
    private val tip = PcTooltip()

    /** Everything clickable drawn in the last frame: click, right click (backwards) and wheel. */
    private class Ctl(val x: Int, val y: Int, val w: Int, val h: Int, val wheel: ((Int) -> Unit)?, val right: (() -> Unit)?, val left: () -> Unit)
    private val ctls = ArrayList<Ctl>()
    private fun ctl(cx: Int, cy: Int, cw: Int, ch: Int, wheel: ((Int) -> Unit)? = null, right: (() -> Unit)? = null, left: () -> Unit) {
        ctls += Ctl(cx, cy, cw, ch, wheel, right, left)
        targets += intArrayOf(cx, cy, cw, ch)
    }

    private val p get() = screen.prefs
    private var rules: ReleaseRules
        get() = p.release
        set(v) { p.release = v; stale = true }

    private fun scopeEntries(): List<PcEntry> {
        val ids = PcQuery.scopeOf(rules.scope, screen.entries, screen.selection, screen.results)
        return screen.entries.filter { !it.place.inParty && (ids == null || it.uuid in ids) }
    }

    private fun refresh() {
        if (!stale && builtFrom === screen.entries) return
        builtFrom = screen.entries; stale = false
        val ids = PcQuery.scopeOf(rules.scope, screen.entries, screen.selection, screen.results)
        scopeSize = screen.entries.count { !it.place.inParty && (ids == null || it.uuid in ids) }
        list = PcQuery.candidates(screen.entries, p, ids)
        scroll = 0
    }

    private fun scopeLabel(s: String): String = when {
        s == "selection" -> L("The selection", "La sélection")
        s == "search" -> L("The current search", "La recherche actuelle")
        s.startsWith("species:") -> s.removePrefix("species:").let { k ->
            screen.entries.firstOrNull { it.lineKey == k }?.let { it.speciesName + (it.formLabel?.let { f -> " ($f)" } ?: "") } ?: L("A species no longer here", "Une espèce absente")
        }
        s.startsWith("type:") -> L("Type: ", "Type : ") + screen.typeName(s.removePrefix("type:"))
        else -> L("The whole PC", "Tout le PC")
    }

    // ── widgets ──

    private fun dropdown(ctx: DrawContext, bx: Int, by: Int, bw: Int, value: String, active: Boolean, mx: Int, my: Int, open: (Int, Int) -> Unit) {
        val hov = inside(mx, my, bx, by, bw, 12)
        UiKit.slot(ctx, bx, by, bw, 12)
        if (hov) ctx.fill(bx + 1, by + 1, bx + bw - 1, by + 11, 0x22FFFFFF)
        ctx.drawText(tr, fit(tr, value, bw - 14), bx + 4, by + 2, if (active) UiKit.accentBright() else UiKit.TEXT, false)
        ctx.drawText(tr, "▾", bx + bw - 9, by + 2, UiKit.TEXT_FAINT, false)
        ctl(bx, by, bw, 12) { open(bx, by + 13) }
    }

    private fun stepper(ctx: DrawContext, bx: Int, by: Int, bw: Int, value: String, active: Boolean, mx: Int, my: Int, step: (Int) -> Unit) {
        UiKit.slot(ctx, bx, by, bw, 12)
        val lh = inside(mx, my, bx, by, 12, 12); val rh = inside(mx, my, bx + bw - 12, by, 12, 12)
        ctx.drawText(tr, "◀", bx + 3, by + 2, if (lh) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
        ctx.drawText(tr, "▶", bx + bw - 9, by + 2, if (rh) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
        ctx.drawText(tr, value, bx + (bw - tr.getWidth(value)) / 2, by + 2, if (active) UiKit.accentBright() else UiKit.TEXT, false)
        ctl(bx, by, bw, 12, wheel = step, right = { step(-1) }) { step(1) }
        ctl(bx, by, 12, 12, wheel = step) { step(-1) }
        ctl(bx + bw - 12, by, 12, 12, wheel = step) { step(1) }
    }

    private fun chip(ctx: DrawContext, cx: Int, cy: Int, label: String, on: Boolean, mx: Int, my: Int, flip: () -> Unit): Int {
        val cw = tr.getWidth(label) + 10
        val hov = inside(mx, my, cx, cy, cw, 11)
        ctx.fill(cx, cy, cx + cw, cy + 11, if (on) UiKit.withAlpha(UiKit.accent(), 0x30) else if (hov) UiKit.SURFACE_HOVER else UiKit.SURFACE_SUNKEN)
        UiKit.border(ctx, cx, cy, cw, 11, if (on) UiKit.accent() else UiKit.BORDER_DIM)
        ctx.drawText(tr, label, cx + 5, cy + 2, if (on) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
        ctl(cx, cy, cw, 11) { flip() }
        return cw
    }

    // ── drawing ──

    override fun render(ctx: DrawContext, mx0: Int, my0: Int, delta: Float) {
        super.render(ctx, mx0, my0, delta)
        ctls.clear(); tip.clear()
        refresh()
        val mx = if (popup != null) -9999 else mx0
        val my = if (popup != null) -9999 else my0
        val r = rules
        title(ctx, L("Release candidates", "Candidats au relâchement"))
        val x1 = x + 6; val x2 = x + w - 6
        val mid = x + w / 2 + 2
        var y = this.y + 22

        // ── among ──
        zone(ctx, x1, y, x2, y + 31, L("Among", "Parmi"))
        dropdown(ctx, x1 + 6, y + 16, 200, scopeLabel(r.scope), r.scope != "all", mx, my) { px, py -> openScopeMenu(px, py) }
        val n = L("$scopeSize Pokémon in the PC", "$scopeSize Pokémon du PC")
        ctx.drawText(tr, n, x2 - 6 - tr.getWidth(n), y + 18, UiKit.TEXT_MUTED, false)
        y += 34

        // ── kept ──
        val keepTop = y
        zone(ctx, x1, y, x2, y + 105, L("Kept", "À garder"))
        if (inside(mx, my, x1, y, x2 - x1, 13)) tip.set(mx, my, L("What stays", "Ce qui reste") to UiKit.TEXT,
            L("The best copies of each species stay (those with the qualities below first, then shiny, alpha, favourite, IVs), counted over the whole PC: narrowing \"Among\" never releases a species' last copies. Then every Pokémon with the qualities below stays too.",
              "Les meilleurs exemplaires de chaque espèce restent (d'abord ceux qui ont les qualités ci-dessous, puis shiny, alpha, favori, IV), comptés sur tout le PC : réduire « Parmi » ne relâche jamais les derniers d'une espèce. Ensuite, tout Pokémon qui a les qualités ci-dessous reste aussi.") to UiKit.TEXT_FAINT)
        y += 17
        ctx.drawText(tr, L("The best of each species", "Les meilleurs de chaque espèce"), x1 + 6, y + 2, UiKit.TEXT_MUTED, false)
        stepper(ctx, x2 - 76, y, 70, p.keepPerSpecies.toString(), p.keepPerSpecies != 1, mx, my) { d -> p.keepPerSpecies += d; stale = true }
        y += 15
        val lead = L("And those with", "Et ceux qui ont")
        ctx.drawText(tr, lead, x1 + 6, y + 2, UiKit.TEXT_MUTED, false)
        val modeX = x1 + 6 + tr.getWidth(lead) + 4
        dropdown(ctx, modeX, y, 92, if (r.matchAll) L("all", "toutes") else L("at least one", "au moins une"), r.matchAll, mx, my) { px, py ->
            popup = PcMenu(px, py, listOf(
                MenuItem((if (!r.matchAll) "● " else "   ") + L("at least one of these qualities", "au moins une de ces qualités")) { rules = rules.copy(matchAll = false) },
                MenuItem((if (r.matchAll) "● " else "   ") + L("all of these qualities", "toutes ces qualités")) { rules = rules.copy(matchAll = true) },
            ))
        }
        ctx.drawText(tr, L("of these qualities:", "de ces qualités :"), modeX + 96, y + 2, UiKit.TEXT_MUTED, false)
        y += 15
        // IV total, hidden ability
        ctx.drawText(tr, L("IV total ≥", "IV total ≥"), x1 + 6, y + 2, UiKit.TEXT_FAINT, false)
        stepper(ctx, x1 + 64, y, 70, if (p.protectIvPct <= 0) "—" else "${p.protectIvPct} %", p.protectIvPct > 0, mx, my) { d -> p.protectIvPct += d * 5; stale = true }
        ctx.drawText(tr, L("Hidden ability", "Talent caché"), mid, y + 2, UiKit.TEXT_FAINT, false)
        val hx = x2 - 30
        if (UiKit.isModern) UiKit.switch(ctx, hx, y + 1, p.protectHidden)
        else ctx.drawText(tr, if (p.protectHidden) "☑" else "☐", hx + 10, y + 2, UiKit.accent(), false)
        ctl(mid, y, x2 - 6 - mid, 12) { p.protectHidden = !p.protectHidden; stale = true }
        y += 15
        // IVs, stat by stat
        val labels = statLabels()
        val cellW = (x2 - x1 - 12) / 6
        for (k in 0 until 6) {
            val cx = x1 + 6 + k * cellW
            val v = r.ivs.getOrElse(k) { -1 }
            ctx.drawText(tr, labels[k], cx, y + 2, if (v >= 0) UiKit.TEXT else UiKit.TEXT_FAINT, false)
            val vx = cx + 22; val vw = cellW - 29
            val hov = inside(mx, my, vx, y, vw, 12)
            UiKit.slot(ctx, vx, y, vw, 12)
            if (hov) ctx.fill(vx + 1, y + 1, vx + vw - 1, y + 11, 0x22FFFFFF)
            val t = when { v < 0 -> "—"; v == 0 -> "= 0"; else -> "≥$v" }
            ctx.drawText(tr, t, vx + (vw - tr.getWidth(t)) / 2, y + 2, if (v >= 0) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
            ctl(vx, y, vw, 12, wheel = { d -> setIv(k, (v + d).coerceIn(-1, 31)) }, right = { setIv(k, preset(v, -1)) }) { setIv(k, preset(v, 1)) }
            if (hov) tip.set(mx, my, "IV ${labels[k]}" to UiKit.TEXT,
                L("—: not asked · = 0: exactly 0 · ≥ n: at least n. Click: 31, 30, 28, 25, 20, 10, 0 · right click: back · wheel: one by one.",
                  "— : non demandé · = 0 : exactement 0 · ≥ n : au moins n. Clic : 31, 30, 28, 25, 20, 10, 0 · clic droit : retour · molette : un par un.") to UiKit.TEXT_FAINT,
                L("The IVs asked for count as one quality, all of them needed.", "Les IV demandés comptent pour une seule qualité, tous requis.") to UiKit.TEXT_FAINT)
        }
        y += 15
        // ability, nature, gender
        val inScope = scopeEntries()
        ctx.drawText(tr, L("Ability", "Talent"), x1 + 6, y + 2, UiKit.TEXT_FAINT, false)
        val abilityLabel = if (r.ability.isEmpty()) L("Any", "Peu importe") else screen.entries.firstOrNull { it.abilityId == r.ability }?.abilityName ?: r.ability
        dropdown(ctx, x1 + 52, y, mid - 8 - (x1 + 52), abilityLabel, r.ability.isNotEmpty(), mx, my) { px, py ->
            val items = mutableListOf(MenuItem(L("Any", "Peu importe")) { rules = rules.copy(ability = "") })
            inScope.groupBy { it.abilityId }.entries.sortedBy { it.value.first().abilityName }.forEach { (id, l) ->
                items += MenuItem((if (r.ability == id) "● " else "") + "${l.first().abilityName}  (${l.size})") { rules = rules.copy(ability = id) }
            }
            popup = PcMenu(px, py, items, title = L("Abilities among them", "Talents parmi eux"))
        }
        ctx.drawText(tr, "Nature", mid, y + 2, UiKit.TEXT_FAINT, false)
        val natureLabel = if (r.nature.isEmpty()) L("Any", "Peu importe") else screen.entries.firstOrNull { it.natureId == r.nature }?.natureName ?: r.nature
        dropdown(ctx, mid + 40, y, x2 - 6 - (mid + 40), natureLabel, r.nature.isNotEmpty(), mx, my) { px, py ->
            val items = mutableListOf(MenuItem(L("Any", "Peu importe")) { rules = rules.copy(nature = "") })
            inScope.groupBy { it.natureId }.entries.sortedBy { it.value.first().natureName }.forEach { (id, l) ->
                val e = l.first()
                val mod = if (e.natureUp >= 0 && e.natureUp != e.natureDown) "  +${labels[e.natureUp]} −${labels[e.natureDown]}" else ""
                items += MenuItem((if (r.nature == id) "● " else "") + e.natureName + mod + "  (${l.size})") { rules = rules.copy(nature = id) }
            }
            popup = PcMenu(px, py, items, title = L("Natures among them", "Natures parmi eux"))
        }
        y += 15
        ctx.drawText(tr, L("Gender", "Sexe"), x1 + 6, y + 2, UiKit.TEXT_FAINT, false)
        val genderLabel = when (r.gender) { 1 -> "♂ " + L("Male", "Mâle"); 2 -> "♀ " + L("Female", "Femelle"); else -> L("Any", "Peu importe") }
        dropdown(ctx, x1 + 52, y, mid - 8 - (x1 + 52), genderLabel, r.gender != 0, mx, my) { px, py ->
            popup = PcMenu(px, py, listOf(
                MenuItem(L("Any", "Peu importe")) { rules = rules.copy(gender = 0) },
                MenuItem("♂ " + L("Male", "Mâle")) { rules = rules.copy(gender = 1) },
                MenuItem("♀ " + L("Female", "Femelle")) { rules = rules.copy(gender = 2) },
            ))
        }
        y = keepTop + 108

        // ── never proposed ──
        val g = r.guards
        val lead2 = L("Protected:", "Protégés :")
        ctx.drawText(tr, lead2, x1 + 2, y + 2, UiKit.TEXT_FAINT, false)
        var cx = x1 + 2 + tr.getWidth(lead2) + 4
        for ((label, on, flip) in listOf<Triple<String, Boolean, (ReleaseGuards) -> ReleaseGuards>>(
            Triple("Shiny", g.shiny) { it.copy(shiny = !it.shiny) },
            Triple(L("Legendary", "Légendaires"), g.legendary) { it.copy(legendary = !it.legendary) },
            Triple("Alpha", g.alpha) { it.copy(alpha = !it.alpha) },
            Triple(L("Marked", "Marqués"), g.marked) { it.copy(marked = !it.marked) },
            Triple(L("Items", "Objets"), g.held) { it.copy(held = !it.held) },
            Triple(L("Tagged", "Tagués"), g.tagged) { it.copy(tagged = !it.tagged) },
        )) {
            if (cx + tr.getWidth(label) + 10 > x2) { cx = x1 + 2; y += 13 }
            cx += chip(ctx, cx, y, label, on, mx, my) { rules = rules.copy(guards = flip(rules.guards)) } + 3
        }
        y += 15
        ctx.drawText(tr, fit(tr, L("Favourites and the party: never.", "Favoris et équipe : jamais."), x2 - x1), x1 + 2, y, UiKit.TEXT_FAINT, false)
        y += 12

        // ── the candidates, species by species ──
        val groups = list.groupBy { it.lineKey }.entries.toList()
        UiKit.dots(ctx, x1, y - 2, x2 - x1)
        ctx.drawText(tr, L("${list.size} candidates · ${groups.size} species", "${list.size} candidats · ${groups.size} espèces"), x1 + 2, y + 2,
            if (list.isEmpty()) UiKit.TEXT_FAINT else 0xFFFF9A9A.toInt(), false)
        y += 13
        val top = y; val bottom = this.y + h - 24
        ctx.fill(x1, top - 1, x2, bottom + 1, 0x48000000)
        ctx.enableScissorSafe(x1, top, x2, bottom)
        val colW = (x2 - x1 - 8) / 2
        groups.forEachIndexed { i, (_, copies) ->
            val ry = top + 1 + (i / 2) * 10 - scroll
            if (ry + 10 < top || ry > bottom) return@forEachIndexed
            val gx = x1 + 4 + (i % 2) * colW
            val e = copies.first()
            val hov = inside(mx, my, gx, ry, colW, 10) && my in top until bottom
            ctx.drawText(tr, fit(tr, "${e.speciesName}${e.formLabel?.let { " ($it)" } ?: ""} ×${copies.size}", colW - 4), gx, ry, if (hov) UiKit.TEXT else UiKit.TEXT_MUTED, false)
            if (hov) tip.set(mx, my, *(listOf((e.speciesName + " ×${copies.size}") to 0xFFFFFFFF.toInt()) + copies.take(10).map { c ->
                (L("Lv ", "Nv ") + c.level + " · ${c.ivPct.toInt()}% · " + c.natureName + " · " + c.abilityName) to ivColor(c.ivSum, 186)
            } + (if (copies.size > 10) listOf(L("…and ${copies.size - 10} more", "…et ${copies.size - 10} de plus") to UiKit.TEXT_FAINT) else emptyList())).toTypedArray())
        }
        if (groups.isEmpty()) ctx.drawText(tr, L("Nothing to release with these rules.", "Rien à relâcher avec ces règles."), x1 + 4, top + 2, UiKit.TEXT_FAINT, false)
        ctx.disableScissor()
        maxScroll = maxOf(0, ((groups.size + 1) / 2) * 10 + 2 - (bottom - top))
        UiKit.scrollbar(ctx, x2 - 4, top, bottom, ((groups.size + 1) / 2) * 10 + 2, scroll)

        val by = this.y + h - 20
        button(ctx, x + 8, by, 56, L("Close", "Fermer"), mx, my)
        button(ctx, x + 68, by, 60, L("Reset", "Défaut"), mx, my)
        button(ctx, x + 132, by, 104, L("Select them", "Les sélectionner"), mx, my, enabled = list.isNotEmpty())
        if (list.isEmpty()) button(ctx, x + w - 112, by, 104, L("Release…", "Relâcher…"), mx, my, enabled = false)
        else danger(ctx, x + w - 112, by, 104, L("Release…", "Relâcher…"), mx, my)
        if (inside(mx, my, x + 68, by, 60, 14)) tip.set(mx, my, L("Back to the default rules", "Revenir aux règles par défaut") to UiKit.TEXT)

        popup?.let { m ->
            m.layout(tr, screen.width, screen.height)
            m.render(ctx, tr, mx0, my0)
            targets.clear(); targets += m.rows()
        } ?: tip.draw(ctx, tr, screen.width, screen.height)
    }

    private fun setIv(k: Int, v: Int) {
        val ivs = rules.ivs.toMutableList().also { while (it.size < 6) it += -1 }
        ivs[k] = v
        rules = rules.copy(ivs = ivs)
    }

    /** The next (or previous) of the usual values: —, 31, 30, 28, 25, 20, 10, = 0. */
    private fun preset(v: Int, dir: Int): Int {
        val steps = listOf(-1, 31, 30, 28, 25, 20, 10, 0)
        val i = steps.indexOf(v).takeIf { it >= 0 } ?: steps.indexOfFirst { it in 1..v }.takeIf { it >= 0 } ?: 0
        return steps[Math.floorMod(i + dir, steps.size)]
    }

    private fun openScopeMenu(px: Int, py: Int) {
        val s = rules.scope
        fun mark(v: Boolean) = if (v) "● " else "   "
        val sel = screen.selection.size
        val res = screen.results.count { !it.place.inParty }
        popup = PcMenu(px, py, listOf(
            MenuItem(mark(s == "all") + L("The whole PC", "Tout le PC")) { rules = rules.copy(scope = "all") },
            MenuItem(mark(s == "selection") + L("The selection", "La sélection") + " ($sel)", enabled = sel > 0) { rules = rules.copy(scope = "selection") },
            MenuItem(mark(s == "search") + L("The current search (left column)", "La recherche actuelle (colonne de gauche)") + " ($res)",
                enabled = !screen.filter.isEmpty || screen.onlySelected) { rules = rules.copy(scope = "search") },
            MenuItem(mark(s.startsWith("species:")) + L("One species ▸", "Une espèce ▸")) { openSpeciesMenu(px, py) },
            MenuItem(mark(s.startsWith("type:")) + L("One type ▸", "Un type ▸")) { openTypeMenu(px, py) },
        ), title = L("Release candidates among…", "Candidats parmi…"))
    }

    private fun openSpeciesMenu(px: Int, py: Int) {
        val groups = screen.entries.filter { !it.place.inParty }.groupBy { it.lineKey }.entries
            .sortedBy { it.value.first().speciesName + (it.value.first().formLabel ?: "") }
        popup = PcMenu(px, py, groups.map { (k, l) ->
            val e = l.first()
            MenuItem((if (rules.scope == "species:$k") "● " else "") + e.speciesName + (e.formLabel?.let { " ($it)" } ?: "") + "  (${l.size})") { rules = rules.copy(scope = "species:$k") }
        }, title = L("One species", "Une espèce"))
    }

    private fun openTypeMenu(px: Int, py: Int) {
        val counts = HashMap<String, Int>()
        screen.entries.filter { !it.place.inParty }.forEach { e -> e.types.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        popup = PcMenu(px, py, counts.keys.sortedBy { screen.typeName(it) }.map { t ->
            MenuItem((if (rules.scope == "type:$t") "● " else "") + screen.typeName(t) + "  (${counts[t]})") { rules = rules.copy(scope = "type:$t") }
        }, title = L("One type", "Un type"))
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        popup?.let { m -> popup = null; m.click(mx, my); return true }
        val by = y + h - 20
        if (inside(mx, my, x + 8, by, 56, 14)) { screen.closeDialog(); return true }
        if (inside(mx, my, x + 68, by, 60, 14)) {
            p.keepPerSpecies = 1; p.protectIvPct = 80; p.protectHidden = true
            rules = ReleaseRules(); return true
        }
        if (list.isNotEmpty() && inside(mx, my, x + 132, by, 104, 14)) {
            screen.selection.clear(); list.forEach { screen.selection.add(it.uuid) }
            screen.onlySelected = true; screen.refilter()
            screen.notify(L("${list.size} candidates selected: look them over, then Release…", "${list.size} candidats sélectionnés : vérifiez-les, puis Relâcher…"), UiKit.GOLD)
            screen.closeDialog(); return true
        }
        if (list.isNotEmpty() && inside(mx, my, x + w - 112, by, 104, 14)) { screen.dialog = ReleaseDialog(screen, list, rules.guards); return true }
        ctls.lastOrNull { inside(mx, my, it.x, it.y, it.w, it.h) }?.let { c ->
            if (button == 1) c.right?.invoke() else if (button == 0) c.left()
            stale = true
            return true
        }
        return inside(mx, my, x, y, w, h)
    }

    override fun scroll(mx: Double, my: Double, amount: Double): Boolean {
        popup?.let { it.scroll(amount); return true }
        ctls.lastOrNull { it.wheel != null && inside(mx, my, it.x, it.y, it.w, it.h) }?.let { c ->
            c.wheel!!.invoke(if (amount > 0) 1 else -1); stale = true; return true
        }
        scroll = (scroll - (amount * 20).toInt()).coerceIn(0, maxScroll); return true
    }

    override fun key(keyCode: Int, mods: Int): Boolean {
        if (popup != null && keyCode == GLFW.GLFW_KEY_ESCAPE) { popup = null; return true }
        return false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Arrange
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Rewrites the PC in a chosen order from a chosen box, touching nothing outside the scope.
 * Labelled boxes take the Pokémon matching their rule first; "labels only" just sends those
 * Pokémon to their boxes without reordering anything else.
 */
internal class ArrangeDialog(screen: HunterPcScreen) : PcDialog(screen, 344, 196) {
    private val hasRules = screen.prefs.boxRules().isNotEmpty()
    private var scope = if (screen.selection.isNotEmpty()) 0 else if (!screen.filter.isEmpty) 1 else 2
    private var sort = if (screen.filter.sort == PcFilterState.SORT_PLACE) PcFilterState.SORT_DEX else screen.filter.sort
    private var reverse = screen.filter.reverse
    private var startBox = 0

    private val scopes get() = listOf(L("My selection", "Ma sélection"), L("Filter results", "Résultats du filtre"),
        L("Whole PC", "Tout le PC"), L("Labelled boxes only", "Étiquettes seules"))
    private val labelOnly get() = scope == 3

    private fun scoped(): List<PcEntry> = when (scope) {
        0 -> screen.selected()
        1 -> screen.results
        else -> screen.entries
    }.filter { !it.place.inParty }

    private fun plan(): Pair<List<PcEntry>, List<PcStep>?> {
        if (labelOnly) return scoped() to PcOps.planRouting(scoped(), screen.session, screen.prefs)
        val f = screen.filter.copy(sort = sort, reverse = reverse)
        val order = PcQuery.sort(scoped(), f, screen.prefs)
        return order to PcOps.planArrangement(order, screen.session, startBox, screen.prefs, PcQuery.rank(f, screen.prefs))
    }

    // layout: label column then controls
    private val ctlX get() = x + 78
    private val half get() = (w - 78 - 10) / 2
    private fun scopeRect(i: Int): IntArray = intArrayOf(ctlX + (i % 2) * (half + 4), y + 24 + (i / 2) * 16, half, 14)

    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        title(ctx, L("Arrange the PC", "Ranger le PC"))
        ctx.drawText(tr, L("Pokémon", "Pokémon"), x + 8, y + 27, UiKit.TEXT_FAINT, false)
        scopes.forEachIndexed { i, s ->
            val r = scopeRect(i)
            button(ctx, r[0], r[1], r[2], fit(tr, s, r[2] - 6), mx, my, active = scope == i, enabled = i != 3 || hasRules)
        }
        var y = y + 24 + 34
        if (!labelOnly) {
            ctx.drawText(tr, L("Order", "Ordre"), x + 8, y + 3, UiKit.TEXT_FAINT, false)
            button(ctx, ctlX, y, half, PcFilterState.sortLabel(sort), mx, my)
            button(ctx, ctlX + half + 4, y, half, if (reverse) L("Descending", "Décroissant") else L("Ascending", "Croissant"), mx, my, active = reverse)
            y += 18
            ctx.drawText(tr, L("From box", "Dès la boîte"), x + 8, y + 3, UiKit.TEXT_FAINT, false)
            button(ctx, ctlX, y, 14, "◀", mx, my)
            val name = screen.boxName(startBox)
            val nameW = half * 2 + 4 - 36
            ctx.drawText(tr, fit(tr, name, nameW), ctlX + 18 + (nameW - minOf(nameW, tr.getWidth(name))) / 2, y + 3, UiKit.TEXT, false)
            button(ctx, ctlX + half * 2 + 4 - 14, y, 14, "▶", mx, my)
            y += 20
        } else y += 4
        val rules = screen.prefs.boxRules()
        if (rules.isNotEmpty()) {
            val names = rules.values.distinct().joinToString(", ") { BoxRules.label(it, screen.prefs, screen::typeName) }
            ctx.drawText(tr, fit(tr, L("Labelled boxes: ", "Boîtes étiquetées : ") + names, w - 16), x + 8, y, UiKit.GOLD, false)
            y += 11
        }
        val (order, steps) = plan()
        val msg = when {
            order.isEmpty() -> L("Nothing to arrange.", "Rien à ranger.")
            steps == null -> L("Not enough room from this box on.", "Pas assez de place à partir de cette boîte.")
            steps.isEmpty() -> if (labelOnly) L("Every labelled Pokémon is already in its box.", "Chaque Pokémon étiqueté est déjà dans sa boîte.")
                               else L("Already in this order.", "Déjà dans cet ordre.")
            labelOnly -> L("${steps.size} Pokémon to send to their box", "${steps.size} Pokémon à envoyer dans leur boîte")
            else -> L("${order.size} Pokémon · ${steps.size} moves or swaps", "${order.size} Pokémon · ${steps.size} déplacements ou échanges")
        }
        ctx.drawText(tr, fit(tr, msg, w - 16), x + 8, y, if (steps.isNullOrEmpty()) UiKit.TEXT_FAINT else UiKit.accentBright(), false)
        y += 11
        val note = if (labelOnly) L("Only Pokémon matching a box label move, into a free slot of that box.", "Seuls les Pokémon qui correspondent à une étiquette bougent, vers une place libre de leur boîte.")
                   else L("Pokémon outside this choice stay where they are. Nothing is released.", "Les Pokémon hors de ce choix restent en place. Aucun relâchement.")
        for (l in tr.wrapLines(Text.literal(note), w - 16)) { ctx.drawText(tr, l, x + 8, y, UiKit.TEXT_FAINT, false); y += 9 }
        val by = this.y + h - 20
        button(ctx, x + 8, by, 70, L("Cancel", "Annuler"), mx, my)
        button(ctx, x + w - 98, by, 90, L("Arrange", "Ranger"), mx, my, enabled = !steps.isNullOrEmpty())
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        for (i in scopes.indices) {
            val r = scopeRect(i)
            if (inside(mx, my, r[0], r[1], r[2], r[3])) { if (i != 3 || hasRules) scope = i; return true }
        }
        var y = y + 24 + 34
        if (!labelOnly) {
            if (inside(mx, my, ctlX, y, half, 14)) {
                val all = PcFilterState.SORTS.filter { it != PcFilterState.SORT_PLACE }
                sort = all[(all.indexOf(sort) + (if (button == 1) all.size - 1 else 1)) % all.size]; return true
            }
            if (inside(mx, my, ctlX + half + 4, y, half, 14)) { reverse = !reverse; return true }
            y += 18
            if (inside(mx, my, ctlX, y, 14, 14)) { startBox = (startBox - 1 + screen.session.boxCount) % screen.session.boxCount; return true }
            if (inside(mx, my, ctlX + half * 2 + 4 - 14, y, 14, 14)) { startBox = (startBox + 1) % screen.session.boxCount; return true }
        }
        val by = this.y + h - 20
        if (inside(mx, my, x + 8, by, 70, 14)) { screen.closeDialog(); return true }
        if (inside(mx, my, x + w - 98, by, 90, 14)) {
            val steps = plan().second
            if (!steps.isNullOrEmpty()) { screen.ops.start(PcOps.Kind.ARRANGE, steps); screen.closeDialog() }
            return true
        }
        return inside(mx, my, x, this.y, w, h)
    }

    override fun scroll(mx: Double, my: Double, amount: Double): Boolean {
        if (!labelOnly) startBox = (startBox - amount.toInt().coerceIn(-1, 1) + screen.session.boxCount) % screen.session.boxCount
        return true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compare
// ─────────────────────────────────────────────────────────────────────────────

/** Two Pokémon side by side: IVs, EVs and actual stats, with the signed difference. */
internal class CompareDialog(screen: HunterPcScreen, private val a: PcEntry, private val b: PcEntry) : PcDialog(screen, 300, 208) {
    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        title(ctx, L("Compare", "Comparer"))
        val colA = x + 8; val colB = x + w / 2 + 4
        for ((e, cx) in listOf(a to colA, b to colB)) {
            UiKit.portrait(ctx, cx, y + 24, 30, 30)
            screen.portrait(ctx, e, cx + 1, y + 25, 28, delta)
            ctx.drawText(tr, fit(tr, e.displayName, w / 2 - 48), cx + 34, y + 25, if (e.shiny) SHINY_COLOR else UiKit.TEXT, true)
            ctx.drawText(tr, fit(tr, "${L("Lv", "Nv")} ${e.level} · ${e.natureName}", w / 2 - 48), cx + 34, y + 35, UiKit.TEXT_MUTED, false)
            ctx.drawText(tr, fit(tr, e.abilityName + if (e.hasItem) " · " + e.itemName else "", w / 2 - 48), cx + 34, y + 45,
                if (e.hiddenAbility) UiKit.GOLD else UiKit.TEXT_FAINT, false)
        }
        val labels = statLabels()
        var y = y + 62
        val heads = listOf("", "IV", "", "±", "EV", "", L("Stat", "Stat"), "", "±")
        val xs = listOf(0, 34, 58, 82, 112, 138, 168, 204, 240)
        heads.forEachIndexed { i, hd -> ctx.drawText(tr, hd, x + 10 + xs[i], y, UiKit.TEXT_FAINT, false) }
        y += 11
        for (k in 0 until 6) {
            UiKit.rowStripe(ctx, x + 6, y - 1, w - 12, 11, k)
            ctx.drawText(tr, labels[k], x + 10, y, UiKit.TEXT_MUTED, false)
            ctx.drawText(tr, a.ivs[k].toString(), x + 10 + 34, y, ivColor(a.ivs[k]), false)
            ctx.drawText(tr, b.ivs[k].toString(), x + 10 + 58, y, ivColor(b.ivs[k]), false)
            diff(ctx, x + 10 + 82, y, b.ivs[k] - a.ivs[k])
            ctx.drawText(tr, a.evs[k].toString(), x + 10 + 112, y, UiKit.TEXT_MUTED, false)
            ctx.drawText(tr, b.evs[k].toString(), x + 10 + 138, y, UiKit.TEXT_MUTED, false)
            ctx.drawText(tr, a.stats[k].toString() + arrow(a, k), x + 10 + 168, y, UiKit.TEXT, false)
            ctx.drawText(tr, b.stats[k].toString() + arrow(b, k), x + 10 + 204, y, UiKit.TEXT, false)
            diff(ctx, x + 10 + 240, y, b.stats[k] - a.stats[k])
            y += 11
        }
        ctx.fill(x + 8, y, x + w - 8, y + 1, UiKit.BORDER_DIM)
        y += 3
        ctx.drawText(tr, "Total", x + 10, y, UiKit.TEXT_MUTED, false)
        ctx.drawText(tr, a.ivSum.toString(), x + 10 + 34, y, ivColor(a.ivSum, 186), false)
        ctx.drawText(tr, b.ivSum.toString(), x + 10 + 58, y, ivColor(b.ivSum, 186), false)
        diff(ctx, x + 10 + 82, y, b.ivSum - a.ivSum)
        ctx.drawText(tr, a.evSum.toString(), x + 10 + 112, y, UiKit.TEXT_MUTED, false)
        ctx.drawText(tr, b.evSum.toString(), x + 10 + 138, y, UiKit.TEXT_MUTED, false)
        ctx.drawText(tr, a.stats.sum().toString(), x + 10 + 168, y, UiKit.TEXT, false)
        ctx.drawText(tr, b.stats.sum().toString(), x + 10 + 204, y, UiKit.TEXT, false)
        diff(ctx, x + 10 + 240, y, b.stats.sum() - a.stats.sum())
        ctx.drawText(tr, fit(tr, L("± right − left · ↑↓ nature", "± droite − gauche · ↑↓ nature"), w - 90), x + 8, this.y + h - 16, UiKit.TEXT_FAINT, false)
        button(ctx, x + w - 68, this.y + h - 20, 60, "OK", mx, my)
    }

    private fun arrow(e: PcEntry, k: Int) = if (e.natureUp == e.natureDown) "" else if (k == e.natureUp) "↑" else if (k == e.natureDown) "↓" else ""

    private fun diff(ctx: DrawContext, x: Int, y: Int, d: Int) {
        val t = if (d > 0) "+$d" else d.toString()
        ctx.drawText(tr, t, x, y, if (d > 0) 0xFF8BE36A.toInt() else if (d < 0) 0xFFF0645A.toInt() else UiKit.TEXT_FAINT, false)
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        if (inside(mx, my, x + w - 68, y + h - 20, 60, 14)) { screen.closeDialog(); return true }
        return inside(mx, my, x, y, w, h)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Text input dialogs
// ─────────────────────────────────────────────────────────────────────────────

internal abstract class InputDialog(screen: HunterPcScreen, w: Int, h: Int, maxLen: Int) : PcDialog(screen, w, h) {
    protected val field = TextFieldWidget(screen.tr, 0, 0, w - 16, 14, Text.literal("")).apply { setMaxLength(maxLen); isFocused = true }

    protected fun drawField(ctx: DrawContext, fx: Int, fy: Int, fw: Int, mx: Int, my: Int, delta: Float) {
        field.x = fx; field.y = fy; field.width = fw
        field.render(ctx, mx, my, delta)
    }

    override fun key(keyCode: Int, mods: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) return false
        return field.keyPressed(keyCode, 0, mods)
    }
    override fun char(c: Char): Boolean = field.charTyped(c, 0)
    abstract fun submit()
}

/**
 * Renames a PC box through Cobblemon's own request; the server answers with the new name,
 * which the box view then shows. 19 characters at most, Cobblemon's limit; an empty name
 * gives the box its default one back.
 */
internal class RenameBoxDialog(screen: HunterPcScreen, private val box: Int) : InputDialog(screen, 250, 80, 19) {
    init {
        field.text = screen.session.pc.boxes.getOrNull(box)?.name?.string?.takeIf { it.isNotBlank() } ?: ""
        field.setCursorToEnd(false)
        field.setSelectionStart(0)
    }

    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        title(ctx, L("Rename ", "Renommer ") + screen.boxName(box))
        drawField(ctx, x + 8, y + 26, w - 16, mx, my, delta)
        ctx.drawText(tr, fit(tr, L("19 characters at most. Empty: default name.", "19 caractères au plus. Vide : nom par défaut."), w - 16),
            x + 8, y + 44, UiKit.TEXT_FAINT, false)
        button(ctx, x + 8, y + h - 20, 60, L("Cancel", "Annuler"), mx, my)
        button(ctx, x + w - 88, y + h - 20, 80, L("Rename", "Renommer"), mx, my)
    }

    override fun submit() {
        val name = field.text.trim().take(19)
        if (!screen.session.offline) {
            // the constructor is Kotlin-internal in Cobblemon (public in the bytecode)
            runCatching {
                val packet = com.cobblemon.mod.common.net.messages.server.storage.pc.RequestRenamePCBoxPacket::class.java
                    .getConstructor(java.util.UUID::class.java, Int::class.javaPrimitiveType, String::class.java)
                    .newInstance(screen.session.pc.uuid, box, name)
                (packet as com.cobblemon.mod.common.api.net.NetworkPacket<*>).sendToServer()
            }.onFailure { com.hunterboard.HunterBoard.LOGGER.warn("[PC] Box rename failed: $it") }
        }
        screen.notify(if (name.isEmpty()) L("Default name given back.", "Nom par défaut rétabli.") else L("Box renamed: $name", "Boîte renommée : $name"))
        screen.closeDialog()
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        if (inside(mx, my, x + 8, y + h - 20, 60, 14)) { screen.closeDialog(); return true }
        if (inside(mx, my, x + w - 88, y + h - 20, 80, 14)) { submit(); return true }
        if (field.isMouseOver(mx, my)) { field.mouseClicked(mx, my, button); return true }
        return inside(mx, my, x, y, w, h)
    }
}

internal class SaveSearchDialog(screen: HunterPcScreen) : InputDialog(screen, 240, 76, 40) {
    private var error: String? = null
    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        title(ctx, L("Save this search", "Enregistrer la recherche"))
        drawField(ctx, x + 8, y + 26, w - 16, mx, my, delta)
        ctx.drawText(tr, fit(tr, error ?: L("A name already used replaces that search.", "Un nom existant remplace la recherche."), w - 16), x + 8, y + 44,
            if (error != null) UiKit.DANGER else UiKit.TEXT_FAINT, false)
        button(ctx, x + 8, y + h - 20, 60, L("Cancel", "Annuler"), mx, my)
        button(ctx, x + w - 88, y + h - 20, 80, L("Save", "Enregistrer"), mx, my)
    }
    override fun submit() {
        if (screen.prefs.saveSearch(field.text, screen.filter)) {
            screen.notify(L("Search saved.", "Recherche enregistrée.")); screen.closeDialog()
        } else error = L("Name required; 20 searches at most.", "Nom requis ; 20 recherches au maximum.")
    }
    override fun click(mx: Double, my: Double, button: Int): Boolean {
        if (inside(mx, my, x + 8, y + h - 20, 60, 14)) { screen.closeDialog(); return true }
        if (inside(mx, my, x + w - 88, y + h - 20, 80, 14)) { submit(); return true }
        if (field.isMouseOver(mx, my)) { field.mouseClicked(mx, my, button); return true }
        return inside(mx, my, x, y, w, h)
    }
}

/** Personal tags: create, rename (click a name), delete (✕ twice). Built-in ones are fixed. */
internal class TagManagerDialog(screen: HunterPcScreen) : InputDialog(screen, 260, minOf(230, screen.height - 16), 24) {
    private var renaming: String? = null
    private var deleteArmed: String? = null
    private var error: String? = null

    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        title(ctx, L("My tags", "Mes tags"))
        var y = y + 24
        val p = screen.prefs
        ctx.drawText(tr, L("Built-in", "Intégrés"), x + 8, y, UiKit.TEXT_FAINT, false)
        y += 10
        var cx = x + 8
        for (t in PcPrefs.BUILTIN) {
            val tw = tr.getWidth(t.name) + 8
            if (cx + tw > x + w - 8) { cx = x + 8; y += 13 }
            cx += UiKit.chip(ctx, tr, cx, y, t.name, UiKit.TEXT_MUTED) + 3
        }
        y += 16
        ctx.drawText(tr, L("Personal (${p.customTags().size}/25)", "Personnels (${p.customTags().size}/25)"), x + 8, y, UiKit.TEXT_FAINT, false)
        y += 10
        val listBottom = this.y + h - 66
        for (t in p.customTags()) {
            if (y + 11 > listBottom) break
            val hov = inside(mx, my, x + 8, y, w - 30, 11)
            if (renaming == t.id) ctx.fill(x + 6, y - 1, x + w - 6, y + 10, UiKit.withAlpha(UiKit.accent(), 0x30))
            else if (hov) ctx.fill(x + 6, y - 1, x + w - 6, y + 10, 0x18FFFFFF)
            val n = screen.entries.count { t.id in p.tagsOf(it.uuid) }
            ctx.drawText(tr, fit(tr, "${t.name}  ($n)", w - 40), x + 10, y + 1, UiKit.TEXT, false)
            val armed = deleteArmed == t.id
            ctx.drawText(tr, if (armed) "✕?" else "✕", x + w - 20, y + 1, if (armed || inside(mx, my, x + w - 22, y, 16, 11)) UiKit.DANGER else UiKit.TEXT_FAINT, false)
            y += 12
        }
        if (p.customTags().isEmpty()) ctx.drawText(tr, L("None yet.", "Aucun pour l'instant."), x + 10, y + 1, UiKit.TEXT_FAINT, false)
        val fy = this.y + h - 60
        ctx.drawText(tr, if (renaming != null) L("New name", "Nouveau nom") else L("New tag", "Nouveau tag"), x + 8, fy, UiKit.TEXT_FAINT, false)
        drawField(ctx, x + 8, fy + 10, w - 16, mx, my, delta)
        ctx.drawText(tr, fit(tr, error ?: L("1 to 24 characters, unique.", "De 1 à 24 caractères, nom unique."), w - 16), x + 8, fy + 27, if (error != null) UiKit.DANGER else UiKit.TEXT_FAINT, false)
        button(ctx, x + 8, this.y + h - 20, 60, L("Close", "Fermer"), mx, my)
        button(ctx, x + w - 88, this.y + h - 20, 80, if (renaming != null) L("Rename", "Renommer") else L("Create", "Créer"), mx, my)
    }

    override fun submit() {
        val p = screen.prefs
        val r = renaming
        val ok = if (r != null) p.renameTag(r, field.text) else p.createTag(field.text) != null
        if (ok) { field.text = ""; renaming = null; error = null } else error = L("Invalid or already used name.", "Nom invalide ou déjà utilisé.")
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        val p = screen.prefs
        var y = y + 24 + 10
        var cx = x + 8
        for (t in PcPrefs.BUILTIN) { val tw = tr.getWidth(t.name) + 8; if (cx + tw > x + w - 8) { cx = x + 8; y += 13 }; cx += tw + 3 }
        y += 16 + 10
        val listBottom = this.y + h - 66
        for (t in p.customTags()) {
            if (y + 11 > listBottom) break
            if (inside(mx, my, x + w - 22, y, 16, 11)) {
                if (deleteArmed == t.id) { p.deleteTag(t.id); deleteArmed = null; screen.refilter() } else deleteArmed = t.id
                return true
            }
            if (inside(mx, my, x + 8, y, w - 30, 11)) { renaming = t.id; field.text = t.name; deleteArmed = null; return true }
            y += 12
        }
        if (inside(mx, my, x + 8, this.y + h - 20, 60, 14)) { screen.closeDialog(); return true }
        if (inside(mx, my, x + w - 88, this.y + h - 20, 80, 14)) { submit(); return true }
        if (field.isMouseOver(mx, my)) { field.mouseClicked(mx, my, button); return true }
        deleteArmed = null
        return inside(mx, my, x, this.y, w, h)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Wanted IVs
// ─────────────────────────────────────────────────────────────────────────────

/** The IVs the "closest IVs" sort aims for; a stat can be ignored. */
internal class IvTargetDialog(screen: HunterPcScreen) : PcDialog(screen, 220, 150) {
    private val target = screen.filter.ivTarget.toMutableList().also { while (it.size < 6) it += 31 }

    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        title(ctx, L("Wanted IVs", "IV cibles"))
        val labels = statLabels()
        for (k in 0 until 6) {
            val ry = y + 24 + k * 15
            ctx.drawText(tr, labels[k], x + 10, ry + 3, UiKit.TEXT_MUTED, false)
            val vx = x + 50
            UiKit.slot(ctx, vx, ry, 70, 13)
            val v = target[k]
            ctx.drawText(tr, "◀", vx + 3, ry + 3, if (inside(mx, my, vx, ry, 14, 13)) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
            ctx.drawText(tr, "▶", vx + 61, ry + 3, if (inside(mx, my, vx + 56, ry, 14, 13)) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
            val t = if (v < 0) "—" else v.toString()
            ctx.drawText(tr, t, vx + (70 - tr.getWidth(t)) / 2, ry + 3, if (v < 0) UiKit.TEXT_FAINT else ivColor(v), false)
            val ign = L("Ignore", "Ignorer")
            button(ctx, x + 128, ry, 82, (if (v < 0) "☑ " else "☐ ") + ign, mx, my, active = v < 0)
        }
        button(ctx, x + 8, y + h - 20, 90, L("All to 31", "Tout à 31"), mx, my)
        button(ctx, x + w - 108, y + h - 20, 100, L("Apply & sort", "Appliquer et trier"), mx, my)
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        for (k in 0 until 6) {
            val ry = y + 24 + k * 15
            val vx = x + 50
            val step = if (button == 1) 5 else 1
            if (inside(mx, my, vx, ry, 14, 13)) { target[k] = if (target[k] < 0) 31 else (target[k] - step).coerceAtLeast(0); return true }
            if (inside(mx, my, vx + 56, ry, 14, 13)) { target[k] = if (target[k] < 0) 0 else (target[k] + step).coerceAtMost(31); return true }
            if (inside(mx, my, x + 128, ry, 82, 13)) { target[k] = if (target[k] < 0) 31 else -1; return true }
        }
        if (inside(mx, my, x + 8, y + h - 20, 90, 14)) { for (k in 0 until 6) target[k] = 31; return true }
        if (inside(mx, my, x + w - 108, y + h - 20, 100, 14)) {
            if (target.all { it < 0 }) return true
            screen.setFilter(screen.filter.copy(sort = PcFilterState.SORT_TARGET, reverse = false, ivTarget = target.toList()))
            screen.closeDialog(); return true
        }
        return inside(mx, my, x, y, w, h)
    }

    override fun scroll(mx: Double, my: Double, amount: Double): Boolean {
        for (k in 0 until 6) {
            val ry = y + 24 + k * 15
            if (inside(mx, my, x + 50, ry, 70, 13)) {
                target[k] = if (target[k] < 0) 31 else (target[k] + amount.toInt().coerceIn(-1, 1)).coerceIn(0, 31); return true
            }
        }
        return true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Moves
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The Pokémon's four moves and the ones it can swap in (its reserve: moves it learned and
 * set aside, and those it can relearn). Pick an active move, then a reserve one: the swap
 * goes through Cobblemon's own request, the one its summary screen sends, which works for a
 * PC Pokémon while the PC is open. The server refuses any move the Pokémon cannot know.
 */
internal class MovesDialog(screen: HunterPcScreen, private val entry: PcEntry) : PcDialog(screen, 296, minOf(262, screen.height - 16)) {
    private var picked = -1
    private var scroll = 0
    private var maxScroll = 0
    private var waitingSince = 0L
    private var before: List<String> = emptyList()
    private var status: Pair<String, Int>? = null
    private val tip = PcTooltip()

    private val pokemon get() = entry.pokemon
    private fun active(): List<com.cobblemon.mod.common.api.moves.Move?> = pokemon.moveSet.getMovesWithNulls().let { l -> List(4) { l.getOrNull(it) } }
    private fun activeNames() = active().map { it?.template?.name ?: "" }

    /** Benched moves first, then the rest of what the Pokémon can relearn, minus its active moves. */
    private fun reserve(): List<Pair<com.cobblemon.mod.common.api.moves.MoveTemplate, Boolean>> {
        val current = activeNames().toSet()
        val benched = runCatching { pokemon.benchedMoves.map { it.moveTemplate } }.getOrDefault(emptyList()).filter { it.name !in current }
        val other = runCatching { pokemon.allAccessibleMoves.toList() }.getOrDefault(emptyList())
            .filter { it.name !in current && benched.none { b -> b.name == it.name } }
            .sortedBy { it.displayName.string }
        return benched.sortedBy { it.displayName.string }.map { it to true } + other.map { it to false }
    }

    // left: the four active moves as battle buttons; right: the reserve as summary rows, enlarged,
    // in a darker well; each side a zone of its own (the team window's style)
    /** The right edge of the left zone; the active moves sit in its middle. */
    private val activeRight get() = x + 131
    private val tileX get() = (x + 4 + activeRight - MoveTiles.TILE_W - MoveTiles.BADGE) / 2 + MoveTiles.BADGE
    private fun tileY(i: Int) = y + 42 + i * 30
    private val listX get() = x + 140 + (MoveTiles.BADGE * K).toInt()
    private val rowW get() = (MoveTiles.ROW_W * K).toInt()
    private val pitch get() = (MoveTiles.ROW_H * K).toInt() + 3
    private val listTop get() = y + 40
    private val listBottom get() = y + h - 40

    private companion object { const val K = 1.4f }

    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        tip.clear()
        title(ctx, L("Moves of ", "Capacités de ") + entry.displayName)
        // answer from the server: the move set changed since the request
        if (waitingSince > 0) {
            if (activeNames() != before) { waitingSince = 0; status = L("Swap confirmed by the server.", "Échange confirmé par le serveur.") to 0xFF8BE36A.toInt() }
            else if (System.currentTimeMillis() - waitingSince > 5000) { waitingSince = 0; status = L("No answer from the server.", "Pas de réponse du serveur.") to UiKit.DANGER }
        }
        val left = listX - (MoveTiles.BADGE * K).toInt() - 1
        zone(ctx, x + 4, y + 22, activeRight, listBottom + 3, L("Active", "Actives"))
        zone(ctx, left - 5, y + 22, x + w - 5, listBottom + 3, L("Reserve", "Réserve"))
        ctx.fill(left - 2, listTop - 2, listX + rowW + 8, listBottom + 1, WELL)
        active().forEachIndexed { i, m ->
            val hov = inside(mx, my, tileX - MoveTiles.BADGE, tileY(i), MoveTiles.TILE_W + MoveTiles.BADGE, MoveTiles.TILE_H)
            MoveTiles.battle(ctx, tr, tileX, tileY(i), m?.template, m?.currentPp ?: 0, m?.maxPp ?: 0, hov, picked == i, screen.fine)
            if (hov && m != null) tip.set(mx, my, *MoveTiles.tooltip(m.template, "${m.currentPp}/${m.maxPp}"))
        }
        val res = reserve()
        ctx.enableScissorSafe(left, listTop, listX + rowW + 2, listBottom)
        res.forEachIndexed { i, (t, benched) ->
            val ry = listTop + i * pitch - scroll
            if (ry + pitch < listTop || ry > listBottom) return@forEachIndexed
            val hov = inside(mx, my, left, ry, listX + rowW - left, pitch - 3) && my in listTop until listBottom
            ctx.matrices.push()
            ctx.matrices.translate(listX.toFloat(), ry.toFloat(), 0f)
            ctx.matrices.scale(K, K, 1f)
            MoveTiles.row(ctx, 0, 0, t, hov)
            ctx.matrices.pop()
            if (hov) tip.set(mx, my, *MoveTiles.tooltip(t, null),
                (if (benched) L("Set aside earlier", "Mise de côté") else L("Can be relearned", "Peut être réapprise")) to UiKit.GOLD)
        }
        ctx.disableScissor()
        maxScroll = maxOf(0, res.size * pitch - (listBottom - listTop))
        UiKit.scrollbar(ctx, listX + rowW + 4, listTop, listBottom, res.size * pitch, scroll)
        if (res.isEmpty()) ctx.drawText(tr, L("Nothing to swap in.", "Rien à échanger."), listX, listTop + 2, UiKit.TEXT_FAINT, false)
        val hint = status ?: ((if (waitingSince > 0) L("Waiting for the server…", "En attente du serveur…")
            else if (picked < 0) L("Pick an active move, then a reserve one.", "Choisissez une capacité active, puis une de réserve.")
            else L("Now pick the move to put in its place.", "Choisissez maintenant la capacité à mettre à sa place.")) to UiKit.TEXT_FAINT)
        ctx.drawText(tr, fit(tr, hint.first, w - 16), x + 8, y + h - 34, hint.second, false)
        button(ctx, x + w - 68, y + h - 20, 60, L("Close", "Fermer"), mx, my)
        tip.draw(ctx, tr, screen.width, screen.height)
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        if (inside(mx, my, x + w - 68, y + h - 20, 60, 14)) { screen.closeDialog(); return true }
        if (waitingSince > 0) return true
        for (i in 0 until 4) if (inside(mx, my, tileX - MoveTiles.BADGE, tileY(i), MoveTiles.TILE_W + MoveTiles.BADGE, MoveTiles.TILE_H)) {
            picked = if (picked == i) -1 else i; status = null; return true
        }
        val left = listX - (MoveTiles.BADGE * K).toInt() - 1
        if (my >= listTop && my < listBottom && mx >= left && mx < listX + rowW) {
            val i = ((my - listTop + scroll) / pitch).toInt()
            val (t, _) = reserve().getOrNull(i) ?: return true
            val slot = if (picked >= 0) picked else active().indexOfFirst { it == null }
            if (slot < 0) { status = L("Pick the active move to replace first.", "Choisissez d'abord la capacité active à remplacer.") to UiKit.GOLD; return true }
            swap(active()[slot]?.template, t)
            return true
        }
        return inside(mx, my, x, y, w, h)
    }

    private fun swap(old: com.cobblemon.mod.common.api.moves.MoveTemplate?, new: com.cobblemon.mod.common.api.moves.MoveTemplate) {
        before = activeNames()
        picked = -1
        status = null
        if (screen.session.offline) { status = L("Test PC: nothing sent.", "PC de test : rien n'est envoyé.") to UiKit.TEXT_FAINT; return }
        runCatching {
            // like the box rename request, the constructor may be Kotlin-internal
            val packet = com.cobblemon.mod.common.net.messages.server.BenchMovePacket::class.java.constructors.first { it.parameterCount == 4 }
                .newInstance(entry.place.inParty, entry.uuid, old, new)
            (packet as com.cobblemon.mod.common.api.net.NetworkPacket<*>).sendToServer()
            waitingSince = System.currentTimeMillis()
        }.onFailure { status = L("Could not send the request.", "Impossible d'envoyer la demande.") to UiKit.DANGER; com.hunterboard.HunterBoard.LOGGER.warn("[PC] Move swap failed: $it") }
    }

    override fun scroll(mx: Double, my: Double, amount: Double): Boolean { scroll = (scroll - (amount * 24).toInt()).coerceIn(0, maxScroll); return true }
}
