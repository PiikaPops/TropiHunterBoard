package com.hunterboard.pc

import com.cobblemon.mod.common.api.types.ElementalTypes
import com.hunterboard.PokemonDetailScreen
import com.hunterboard.ScaledScreen
import com.hunterboard.UiKit
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import java.util.UUID

/**
 * HunterBoard's PC: the whole PC at a glance (every box tiled, or one sortable list), a
 * filter column, the detail of the Pokémon under the cursor, and actions on a multiple
 * selection — release, move, arrange, tag, compare.
 *
 * The screen never changes the storage itself: it plans steps and [PcOps] sends them one
 * by one, each confirmed by the client storage before the next.
 */
class HunterPcScreen(val session: PcSession, private val prefsOverride: PcPrefs? = null) :
    ScaledScreen(Text.literal("PC HunterBoard")) {

    val prefs: PcPrefs = prefsOverride ?: PcPrefs.get()
    val ops = PcOps()
    internal val tr: TextRenderer get() = textRenderer
    internal val factor: Float get() = uiFactor
    /** At least 2 real pixels per GUI pixel: Cobblemon's own fonts are readable. */
    internal val fine: Boolean get() = (client?.window?.scaleFactor ?: 1.0) * uiFactor >= 2.0
    private val portraits = Portraits()

    // ── data ──
    var entries: List<PcEntry> = emptyList(); private set
    private var byId: Map<UUID, PcEntry> = emptyMap()
    private var byKey: Map<String, PcEntry> = emptyMap()
    private var revision = Long.MIN_VALUE
    var filter: PcFilterState = prefs.filter; private set
    var results: List<PcEntry> = emptyList(); private set
    private var resultIds: Set<UUID> = emptySet()
    val selection = LinkedHashSet<UUID>()
    private var anchor: UUID? = null
    private var focus: UUID? = null
    var onlySelected = false

    // ── ui state ──
    private var view = if (prefs.view == "boxes") "boxes" else "details"
    /** What the six values of the detailed cards show: 0 IVs, 1 EVs, 2 actual stats. */
    private var listStats = 0
    internal val tip = PcTooltip()
    /** The saved teams, at the bottom of the left column. */
    internal val teams by lazy { TeamPanel(this) }
    /** Top of the teams panel, and the height the filters need (measured each frame). */
    private var teamsTop = 0
    private var filterNeed = 270
    /** How far the filter column can scroll, from the last frame: the wheel stops there. */
    private var filterMaxScroll = 0
    /** Pokémon whose box label rule points to a box they are not in (see the toolbar). */
    private var routingCount = 0
    private var scroll = 0
    private var filterScroll = 0
    internal var menu: PcMenu? = null
    internal var dialog: PcDialog? = null
    private var toast: String? = null
    private var toastColor = 0
    private var toastUntil = 0L
    private var hovered: PcEntry? = null
    private lateinit var search: TextFieldWidget
    private var lastClick = 0L
    private var lastClickId: UUID? = null

    // drag: a press on a Pokémon may become a move, a press on empty space a band selection
    private var pressX = 0.0; private var pressY = 0.0
    private var pressEntry: PcEntry? = null
    private var pressDeferredSelect = false
    private var dragging = false
    private var banding = false
    private var dragScrollbar = false

    // ── layout (recomputed each frame) ──
    private var x0 = 0; private var y0 = 0; private var pw = 0; private var ph = 0
    private var lx = 0; private var lw = 0; private var rx = 0; private var rw = 0
    private var cx = 0; private var cw = 0; private var cTop = 0; private var cBottom = 0
    private var bodyTop = 0; private var footerY = 0
    private var filtersTop = 0
    /** The filters below the search field, folded away each time the PC opens: room for the teams. */
    private var filtersOpen = false
    private var showDetail = true
    private var cell = 18; private var tileW = 0; private var tileH = 0; private var perRow = 1
    private var contentH = 0
    private val hits = mutableListOf<Hit>()

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val right: ((Double, Double) -> Unit)? = null, val action: () -> Unit) {
        fun contains(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h
    }

    companion object {
        /** The filters' fold button, below the search field. */
        private const val TOGGLE_Y = 29
        /** The search field and the fold button, the filters folded. */
        private const val FOLDED_H = TOGGLE_Y + 17
        private const val HEADER_H = 11
        private const val TILE_GAP = 4
        private const val PARTY_CELL = 22
        private val AGES = listOf(0, 1, 6, 24, 72, 168, 720)
        private const val CARD_H = 56
        private const val CARD_MIN_W = 104
        private const val CARD_GAP = 4
        private const val DHEAD_H = 14
    }

    // ─────────────────────────────────────────────────────────────────────────
    // lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun init() {
        super.init()
        val old = if (::search.isInitialized) search.text else filter.query
        search = TextFieldWidget(textRenderer, 0, 0, 120, 14, Text.literal("search"))
        search.setMaxLength(60)
        search.text = old
        search.setChangedListener { setFilter(filter.copy(query = it)) }
        UiKit.styleField(search, hint = L("Species, nickname, move…", "Espèce, surnom, attaque…"))
        addDrawableChild(search)
        refresh(force = true)
    }

    override fun tick() {
        super.tick()
        ops.tick(session)
        if (ops.state != PcOps.State.IDLE && !ops.running) finishOps()
        if (!session.valid() && !ops.running) closeFromServer()
    }

    /** The progress window only shows for batches; a single move just happens. */
    private val showProgress: Boolean get() = ops.running && ops.total > 1

    /** What the last team could not do, added to its report in the footer. */
    internal var teamProblems = ""

    /** A batch is over: say how it went in the footer, then back to normal. */
    private fun finishOps() {
        val n = ops.done
        when (ops.state) {
            PcOps.State.DONE -> if (ops.total > 1 || ops.kind == PcOps.Kind.RELEASE) notify(when (ops.kind) {
                PcOps.Kind.RELEASE -> L("$n Pokémon released, confirmed by the server.", "$n Pokémon relâché(s), confirmé par le serveur.")
                PcOps.Kind.ARRANGE -> L("PC arranged: $n moves, confirmed by the server.", "PC rangé : $n déplacement(s), confirmé par le serveur.")
                PcOps.Kind.MOVE -> L("$n Pokémon moved.", "$n Pokémon déplacé(s).")
                PcOps.Kind.TEAM -> L("Team equipped ($n changes), confirmed by the server.", "Équipe équipée ($n changement(s)), confirmé par le serveur.")
            } + teamProblems, 0xFF8BE36A.toInt())
            PcOps.State.CHANGED -> notify(L("Stopped: the PC changed meanwhile ($n done).", "Arrêté : le PC a changé entre-temps ($n fait(s))."), UiKit.GOLD)
            PcOps.State.TIMEOUT -> notify(L("Stopped: no answer from the server ($n done).", "Arrêté : pas de réponse du serveur ($n fait(s))."), UiKit.DANGER)
            PcOps.State.CANCELLED -> notify(L("Stopped ($n done).", "Arrêté ($n fait(s))."), UiKit.GOLD)
            else -> {}
        }
        ops.clear()
        refresh(force = true)
    }

    override fun shouldPause() = false

    override fun close() {
        if (ops.running) { notify(L("Wait for the server to confirm before closing.", "Attendez les confirmations du serveur avant de fermer."), UiKit.GOLD); return }
        teams.commitRename()
        prefs.filter = filter
        prefs.view = view
        prefs.save()
        val back = session.original
        if (back != null) back.closeNormally(true) else super.close()
    }

    /** Back to Cobblemon's own PC screen, still linked to the PC. */
    private fun classic() {
        if (ops.running) return
        teams.commitRename()
        prefs.filter = filter; prefs.view = view; prefs.save()
        client?.setScreen(session.original)
    }

    fun closeFromServer() {
        ops.stop()
        prefs.filter = filter; prefs.save()
        MinecraftClient.getInstance().setScreen(null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // data
    // ─────────────────────────────────────────────────────────────────────────

    private fun refresh(force: Boolean = false) {
        val rev = PcIndex.revision(session.pc, session.party)
        if (!force && rev == revision) return
        revision = rev
        entries = PcIndex.build(session.pc, session.party)
        byId = entries.associateBy { it.uuid }
        byKey = entries.associateBy { it.uuid.toString() }
        selection.retainAll(byId.keys)
        refilter()
    }

    fun refilter() {
        val base = PcQuery.filter(entries, filter, prefs, System.currentTimeMillis())
        val narrowed = if (onlySelected) base.filter { it.uuid in selection } else base
        results = PcQuery.sort(narrowed, filter, prefs)
        resultIds = results.mapTo(HashSet()) { it.uuid }
        routingCount = if (prefs.boxRules().isEmpty()) 0 else PcOps.planRouting(entries, session, prefs).size
    }

    fun setFilter(f: PcFilterState) {
        if (f == filter) return
        filter = f
        if (::search.isInitialized && search.text != f.query) search.text = f.query
        scroll = 0
        refilter()
    }

    internal fun entry(id: UUID): PcEntry? = byId[id]
    /** A Pokémon by its uuid as saved in a team. */
    internal fun entryByKey(key: String): PcEntry? = byKey[key]
    internal fun selected(): List<PcEntry> = selection.mapNotNull { byId[it] }

    internal fun notify(msg: String, color: Int = UiKit.TEXT) {
        toast = msg; toastColor = color; toastUntil = System.currentTimeMillis() + 5000
    }

    // ─────────────────────────────────────────────────────────────────────────
    // layout
    // ─────────────────────────────────────────────────────────────────────────

    private fun layout() {
        x0 = 4; y0 = 4; pw = width - 8; ph = height - 8
        bodyTop = y0 + 24
        footerY = y0 + ph - 14
        lw = 150; lx = x0 + 6
        showDetail = pw >= 560
        // wide windows get a wider detail column: two battle move buttons side by side
        rw = if (!showDetail) 0 else if (pw >= 880) 212 else 160
        rx = x0 + pw - 6 - rw
        cx = lx + lw + 6
        cw = (if (showDetail) rx - 6 else x0 + pw - 6) - cx
        cTop = bodyTop + 20
        cBottom = footerY - 3
        // left column: party, filters (what they need, scrolled when short), the teams below
        filtersTop = partyY + PARTY_CELL + 6
        val avail = footerY - 3 - filtersTop
        val filtersH = if (!filtersOpen) FOLDED_H
            else minOf(filterNeed, avail - TeamPanel.MIN_H - 10).coerceAtLeast(minOf(avail / 2, 90))
        teamsTop = filtersTop + filtersH + 10
        teams.place(lx, teamsTop, lw, footerY - 3)
        // box tiles: the biggest cell that still shows at least two boxes side by side
        cell = ((cw - TILE_GAP) / 2 - 6) / 6
        cell = cell.coerceIn(14, 22)
        tileW = cell * 6 + 6
        tileH = HEADER_H + cell * 5 + 4
        perRow = maxOf(1, (cw + TILE_GAP) / (tileW + TILE_GAP))
        contentH = if (view == "details") layoutDetails()
                   else ((session.boxCount + perRow - 1) / perRow) * (tileH + TILE_GAP)
        scroll = scroll.coerceIn(0, maxOf(0, contentH - (cBottom - cTop)))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // rendering
    // ─────────────────────────────────────────────────────────────────────────

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        PcPortraitAtlas.renderPending(context)
        refresh()
        layout()
        hits.clear()
        tip.clear()
        val blocked = dialog != null || showProgress
        val mx = if (blocked || menu != null) -9999 else mouseX
        val my = if (blocked || menu != null) -9999 else mouseY
        UiKit.screenDim(context, width, height)
        UiKit.panel(context, x0, y0, pw, ph)
        hovered = null

        drawTitleBar(context, mx, my)
        drawParty(context, mx, my, delta)
        drawFilters(context, mx, my)
        teams.draw(context, mx, my, delta, dragging)
        drawToolbar(context, mx, my)
        scissor(context, cx, cTop, cx + cw, cBottom)
        if (view == "details") drawDetails(context, mx, my, delta) else drawBoxes(context, mx, my, delta)
        context.disableScissor()
        UiKit.scrollbar(context, cx + cw - 3, cTop, cBottom, contentH, scroll)
        if (showDetail) drawSide(context, mx, my, delta)
        drawFooter(context)

        // vanilla widgets (search field) above the panel, below overlays
        UiKit.fieldBackground(context, search)
        renderWidgets(context, mx, my, delta)

        if (banding) drawBand(context, mouseX, mouseY)
        if (dragging) drawDragGhost(context, mouseX, mouseY, delta)
        teams.drawGhost(context, mouseX, mouseY, delta)
        if (!blocked && menu == null) hovered?.let { if (!dragging && !teams.dragging && !showDetail) drawMiniTooltip(context, it, mouseX, mouseY) }
        if (!blocked && menu == null && !dragging && !teams.dragging) tip.draw(context, textRenderer, width, height)

        menu?.let { it.layout(textRenderer, width, height); it.render(context, textRenderer, mouseX, mouseY) }
        if (showProgress) drawProgress(context, mouseX, mouseY)
        else dialog?.let { context.matrices.push(); context.matrices.translate(0f, 0f, 600f); it.place(width, height); it.render(context, mouseX, mouseY, delta); context.matrices.pop() }
        portraits.endFrame()
    }

    /** A still portrait from the atlas (cheap: the grid shows hundreds), live model as a fallback. */
    private fun gridPortrait(ctx: DrawContext, e: PcEntry, x: Int, y: Int, size: Int, delta: Float) {
        if (!PcPortraitAtlas.draw(ctx, e.pokemon, x, y, size) && !PcPortraitAtlas.available) portrait(ctx, e, x, y, size, delta)
    }

    override fun removed() {
        super.removed()
        PcPortraitAtlas.release()
    }

    /** Draws a live model (detail panel, dialogs); the grid uses [gridPortrait]. */
    internal fun portrait(ctx: DrawContext, e: PcEntry, x: Int, y: Int, size: Int, delta: Float) =
        portraits.draw(ctx, e.pokemon, e.uuid, x, y, size, uiFactor, delta)

    internal fun hit(x: Int, y: Int, w: Int, h: Int, right: ((Double, Double) -> Unit)? = null, action: () -> Unit) { hits += Hit(x, y, w, h, right, action) }
    internal val hitCount: Int get() = hits.size
    /** Hits added since [from] that a scrolled area clipped away must not catch clicks. */
    internal fun dropHits(from: Int, top: Int, bottom: Int) { hits.subList(from, hits.size).removeIf { it.y < top || it.y + it.h > bottom } }
    internal fun clip(ctx: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int) = scissor(ctx, x1, y1, x2, y2)
    /** A Pokémon pointed at outside the PC area (a team member): the detail column shows it. */
    internal fun hoverEntry(e: PcEntry) { if (showDetail) hovered = e }
    private fun inside(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h

    private fun smallButton(ctx: DrawContext, x: Int, y: Int, label: String, mx: Int, my: Int, active: Boolean = false, w: Int = 0, action: () -> Unit): Int {
        val bw = if (w > 0) w else textRenderer.getWidth(label) + 10
        UiKit.button(ctx, textRenderer, x, y, bw, 14, label, inside(mx, my, x, y, bw, 14), active)
        hit(x, y, bw, 14, action = action)
        return bw
    }

    private fun drawTitleBar(ctx: DrawContext, mx: Int, my: Int) {
        val titleColor = if (UiKit.isModern) 0xFFFFFFFF.toInt() else UiKit.accent()
        ctx.drawText(textRenderer, "PC HunterBoard", x0 + 8, y0 + 7, titleColor, true)
        val pcCount = entries.count { !it.place.inParty }
        val capacity = (0 until session.boxCount).sumOf { session.boxSize(it) }
        val usedBoxes = (0 until session.boxCount).count { b -> session.pc.boxes[b].slots.any { it != null } }
        val stats = "$pcCount / $capacity · $usedBoxes / ${session.boxCount} " + L("boxes", "boîtes")
        ctx.drawText(textRenderer, stats, x0 + 8 + textRenderer.getWidth("PC HunterBoard") + 12, y0 + 7,
            if (UiKit.isModern) 0xFFF6CFCB.toInt() else UiKit.TEXT_MUTED, false)

        // right: close, classic PC
        val closeX = x0 + pw - 14
        if (UiKit.closeButton(ctx, textRenderer, closeX, y0 + 6, mx, my)) Unit
        hit(closeX - 3, y0 + 3, 14, 14) { close() }
        // back to Cobblemon's PC: a real button, lit, so it is found at a glance
        if (session.original != null) {
            val label = "◀ " + L("Back to the PC", "PC de base")
            val bw = textRenderer.getWidth(label) + 16
            val bx = closeX - 10 - bw
            UiKit.button(ctx, textRenderer, bx, y0 + 4, bw, 14, label, inside(mx, my, bx, y0 + 4, bw, 14), active = true)
            hit(bx, y0 + 4, bw, 14) { classic() }
        }
    }

    // ── party ──

    private fun partyCellX(i: Int) = lx + i * (PARTY_CELL + 3)
    private val partyY get() = bodyTop + 11

    private fun drawParty(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        UiKit.sectionTitle(ctx, textRenderer, L("Party", "Équipe"), lx, bodyTop)
        for (i in 0 until 6) {
            val x = partyCellX(i); val y = partyY
            val e = session.party?.slots?.getOrNull(i)?.let { byId[it.uuid] }
            drawCell(ctx, e, x, y, PARTY_CELL, mx, my, delta, partyCell = true)
            if (e != null) {
                val lv = e.level.toString()
                ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 200f)
                ctx.drawText(textRenderer, lv, x + PARTY_CELL - textRenderer.getWidth(lv) - 1, y + PARTY_CELL - 8, 0xFFFFFFFF.toInt(), true)
                ctx.matrices.pop()
            }
        }
    }

    /** A still portrait from the atlas, for the team windows. */
    internal fun smallPortrait(ctx: DrawContext, e: PcEntry, x: Int, y: Int, size: Int, delta: Float) = gridPortrait(ctx, e, x, y, size, delta)


    // ── one Pokémon cell (boxes and party) ──

    private fun drawCell(ctx: DrawContext, e: PcEntry?, x: Int, y: Int, s: Int, mx: Int, my: Int, delta: Float, partyCell: Boolean = false) {
        val hov = inside(mx, my, x, y, s, s)
        val match = e != null && (e.uuid in resultIds)
        if (UiKit.isModern) UiKit.slot(ctx, x, y, s, s) else ctx.fill(x, y, x + s, y + s, UiKit.SURFACE_SUNKEN)
        if (e == null) {
            if (hov && dragging) UiKit.border(ctx, x, y, s, s, UiKit.accent())
            return
        }
        if (hov) hovered = e
        val sel = e.uuid in selection
        val hideIt = !match && !prefs.dim && !partyCell
        if (sel) ctx.fill(x + 1, y + 1, x + s - 1, y + s - 1, UiKit.withAlpha(UiKit.accent(), 0x55))
        if (!hideIt && !(dragging && sel)) gridPortrait(ctx, e, x + 1, y + 1, s - 2, delta)
        ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 150f)
        if (!match && !partyCell) ctx.fill(x + 1, y + 1, x + s - 1, y + s - 1, if (hideIt) 0xE0101418.toInt() else 0xA0101418.toInt())
        if (!hideIt) {
            // markers: a dot per corner, an IV bar along the bottom
            if (e.shiny) ctx.fill(x + 1, y + 1, x + 4, y + 4, SHINY_COLOR)
            if (prefs.isFavorite(e.uuid)) ctx.fill(x + s - 4, y + 1, x + s - 1, y + 4, FAV_COLOR)
            if (e.alpha) ctx.fill(x + 1, y + s - 5, x + 4, y + s - 2, ALPHA_COLOR)
            else if (e.special) ctx.fill(x + 1, y + s - 5, x + 4, y + s - 2, LEGEND_COLOR)
            if (e.hasItem) ctx.fill(x + s - 4, y + s - 5, x + s - 1, y + s - 2, ITEM_COLOR)
            val bar = ((s - 2) * e.ivPct / 100.0).toInt()
            ctx.fill(x + 1, y + s - 2, x + 1 + bar, y + s - 1, ivColor(e.ivSum, 186))
        }
        if (sel) UiKit.border(ctx, x, y, s, s, UiKit.accentBright())
        else if (hov) UiKit.border(ctx, x, y, s, s, 0xC0FFFFFF.toInt())
        ctx.matrices.pop()
    }

    // ── filters column ──

    private fun triColor(mode: Int) = when (mode) { 1 -> UiKit.accentBright(); 2 -> UiKit.DANGER; else -> UiKit.TEXT_MUTED }
    private fun cycle(v: Int, back: Boolean) = if (back) (v + 2) % 3 else (v + 1) % 3

    private fun drawFilters(ctx: DrawContext, mx: Int, my: Int) {
        val top = filtersTop
        val bottom = teamsTop - 10
        UiKit.sectionTitle(ctx, textRenderer, L("Search", "Recherche"), lx, top)
        UiKit.placeField(search, lx, top + 11, lw)
        // the filters fold under the field behind a full-width button, lit and counted when some are on
        run {
            // the search text shows in its own field: only the filters folded below count here, and a sort
            val n = filter.activeFilters + (if (filter.sort != PcFilterState.SORT_PLACE) 1 else 0)
            val label = if (filtersOpen) "▾ " + L("Hide the filters", "Masquer les filtres")
                else "▸ " + L("Filters & sort", "Filtres et tri") + (if (n > 0) " · $n " + L("on", if (n > 1) "actifs" else "actif") else "")
            val by = top + TOGGLE_Y
            UiKit.button(ctx, textRenderer, lx, by, lw, 14, fit(textRenderer, label, lw - 6), inside(mx, my, lx, by, lw, 14), !filtersOpen && n > 0)
            hit(lx, by, lw, 14) { filtersOpen = !filtersOpen; filterScroll = 0 }
        }
        if (!filtersOpen) { filterMaxScroll = 0; filterScroll = 0; return }

        // everything below the field may scroll when the window is short
        val areaTop = top + FOLDED_H + 1
        val firstHit = hits.size
        scissor(ctx, lx - 2, areaTop, lx + lw + 2, bottom)
        var y = areaTop - filterScroll
        val f = filter

        // saved searches + reset
        run {
            val half = (lw - 4) / 2
            val b1 = L("Searches ▾", "Recherches ▾")
            UiKit.button(ctx, textRenderer, lx, y, half, 13, b1, inside(mx, my, lx, y, half, 13))
            val yy = y
            hit(lx, y, half, 13) { openSearchesMenu(lx, yy + 14) }
            val b2 = L("Reset", "Réinitialiser")
            UiKit.button(ctx, textRenderer, lx + half + 4, y, half, 13, b2, inside(mx, my, lx + half + 4, y, half, 13), !f.isEmpty)
            hit(lx + half + 4, y, half, 13) { setFilter(PcFilterState(sort = f.sort, reverse = f.reverse, ivTarget = f.ivTarget)); onlySelected = false; refilter() }
            y += 18
        }

        // tri-state chips: click = only → excluded → either; right click backwards
        val chips = listOf(
            Triple("Shiny", f.shiny) { v: Int -> f.copy(shiny = v) },
            Triple("Alpha", f.alpha) { v: Int -> f.copy(alpha = v) },
            Triple(L("Legendary", "Légendaire"), f.legendary) { v: Int -> f.copy(legendary = v) },
            Triple(L("Favourite", "Favori"), f.favorite) { v: Int -> f.copy(favorite = v) },
            Triple(L("Item", "Objet"), f.held) { v: Int -> f.copy(held = v) },
            Triple(L("Mark", "Marque"), f.marked) { v: Int -> f.copy(marked = v) },
            Triple(L("Hidden ab.", "Talent caché"), f.hiddenAbility) { v: Int -> f.copy(hiddenAbility = v) },
        )
        var cxp = lx
        for ((name, mode, make) in chips) {
            val w = textRenderer.getWidth(name) + 8
            if (cxp + w > lx + lw) { cxp = lx; y += 14 }
            drawChip(ctx, cxp, y, w, name, triColor(mode), mode != 0, inside(mx, my, cxp, y, w, 12), struck = mode == 2)
            val m = mode
            hit(cxp, y, w, 12, right = { _, _ -> setFilter(make(cycle(m, true))) }) { setFilter(make(cycle(m, false))) }
            cxp += w + 3
        }
        run {
            val label = L("Duplicates", "Doublons")
            val w = textRenderer.getWidth(label) + 8
            if (cxp + w > lx + lw) { cxp = lx; y += 14 }
            drawChip(ctx, cxp, y, w, label, if (f.duplicates) UiKit.accentBright() else UiKit.TEXT_MUTED, f.duplicates, inside(mx, my, cxp, y, w, 12))
            hit(cxp, y, w, 12) { setFilter(f.copy(duplicates = !f.duplicates)) }
            y += 18
        }

        // value rows: label on the left, current value as a dropdown on the right
        fun row(label: String, value: String, active: Boolean, open: (Int, Int) -> Unit) {
            ctx.drawText(textRenderer, label, lx, y + 3, UiKit.TEXT_FAINT, false)
            val vx = lx + 52; val vw = lw - 52
            val hov = inside(mx, my, vx, y, vw, 13)
            UiKit.slot(ctx, vx, y, vw, 13)
            if (hov) ctx.fill(vx + 1, y + 1, vx + vw - 1, y + 12, 0x22FFFFFF)
            ctx.drawText(textRenderer, fit(textRenderer, value, vw - 14), vx + 4, y + 3, if (active) UiKit.accentBright() else UiKit.TEXT, false)
            ctx.drawText(textRenderer, "▾", vx + vw - 9, y + 3, UiKit.TEXT_FAINT, false)
            val yy = y
            hit(vx, y, vw, 13) { open(vx, yy + 14) }
            y += 15
        }

        row(L("Sort", "Tri"), PcFilterState.sortLabel(f.sort) + if (f.reverse) " ↑" else "", f.sort != PcFilterState.SORT_PLACE) { x, yy -> openSortMenu(x, yy) }
        row(L("Type", "Type"), if (f.type.isEmpty()) L("All", "Tous") else typeName(f.type), f.type.isNotEmpty()) { x, yy -> openTypeMenu(x, yy) }
        row(L("Gender", "Sexe"), when (f.gender) { 1 -> "♂ " + L("Male", "Mâle"); 2 -> "♀ " + L("Female", "Femelle"); 3 -> L("Genderless", "Asexué"); else -> L("All", "Tous") }, f.gender != 0) { x, yy ->
            menu = PcMenu(x, yy, listOf(
                MenuItem(L("All", "Tous")) { setFilter(filter.copy(gender = 0)) },
                MenuItem("♂ " + L("Male", "Mâle")) { setFilter(filter.copy(gender = 1)) },
                MenuItem("♀ " + L("Female", "Femelle")) { setFilter(filter.copy(gender = 2)) },
                MenuItem(L("Genderless", "Asexué")) { setFilter(filter.copy(gender = 3)) },
            ))
        }
        row(L("Ability", "Talent"), if (f.ability.isEmpty()) L("All", "Tous") else (entries.firstOrNull { it.abilityId == f.ability }?.abilityName ?: f.ability), f.ability.isNotEmpty()) { x, yy -> openAbilityMenu(x, yy) }
        row(L("Box", "Boîte"), when (f.box) { -1 -> L("All", "Toutes"); -2 -> L("Party", "Équipe"); else -> boxName(f.box) }, f.box != -1) { x, yy -> openBoxFilterMenu(x, yy) }
        row(L("Size", "Taille"), if (f.size.isEmpty()) L("All", "Toutes") else f.size, f.size.isNotEmpty()) { x, yy ->
            menu = PcMenu(x, yy, listOf(MenuItem(L("All", "Toutes")) { setFilter(filter.copy(size = "")) }) +
                PcFilterState.SIZES.map { s -> MenuItem("$s  (${entries.count { it.size.equals(s, true) }})") { setFilter(filter.copy(size = s)) } })
        }
        row("Tag", when (f.tag) { "" -> L("All", "Tous"); "-" -> L("Untagged", "Sans tag"); else -> prefs.tagName(f.tag) }, f.tag.isNotEmpty()) { x, yy -> openTagFilterMenu(x, yy) }
        row(L("Arrived", "Arrivés"), if (f.ageHours == 0) L("Any time", "Toujours") else ageLabel(f.ageHours), f.ageHours != 0) { x, yy ->
            menu = PcMenu(x, yy, AGES.map { a -> MenuItem(if (a == 0) L("Any time", "Toujours") else ageLabel(a)) { setFilter(filter.copy(ageHours = a)) } },
                title = L("Caught by you, seen here", "Capturés par vous, vus ici"))
        }

        // numeric ranges: stepped with clicks (left +, right −) and the wheel
        fun stepper(label: String, value: String, active: Boolean, onStep: (Int) -> Unit) {
            ctx.drawText(textRenderer, label, lx, y + 3, UiKit.TEXT_FAINT, false)
            val vx = lx + 52; val vw = lw - 52
            UiKit.slot(ctx, vx, y, vw, 13)
            val lh = inside(mx, my, vx, y, 12, 13); val rh = inside(mx, my, vx + vw - 12, y, 12, 13)
            ctx.drawText(textRenderer, "◀", vx + 3, y + 3, if (lh) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
            ctx.drawText(textRenderer, "▶", vx + vw - 9, y + 3, if (rh) UiKit.accentBright() else UiKit.TEXT_FAINT, false)
            ctx.drawText(textRenderer, value, vx + (vw - textRenderer.getWidth(value)) / 2, y + 3, if (active) UiKit.accentBright() else UiKit.TEXT, false)
            hit(vx, y, 14, 13) { onStep(-1) }
            hit(vx + vw - 14, y, 14, 13) { onStep(1) }
            y += 15
        }
        stepper(L("Level ≥", "Niveau ≥"), f.levelMin.toString(), f.levelMin > 1) { d -> setFilter(filter.copy(levelMin = stepLevel(filter.levelMin, d).coerceAtMost(filter.levelMax))) }
        stepper(L("Level ≤", "Niveau ≤"), f.levelMax.toString(), f.levelMax < 100) { d -> setFilter(filter.copy(levelMax = stepLevel(filter.levelMax, d).coerceAtLeast(filter.levelMin))) }
        stepper("IV ≥", "${f.ivMin} %", f.ivMin > 0) { d -> setFilter(filter.copy(ivMin = (filter.ivMin + d * 10).coerceIn(0, 100))) }

        if (f.sort == PcFilterState.SORT_TARGET) {
            val label = L("Wanted IVs…", "IV cibles…")
            UiKit.button(ctx, textRenderer, lx, y, lw, 13, label, inside(mx, my, lx, y, lw, 13))
            hit(lx, y, lw, 13) { dialog = IvTargetDialog(this) }
            y += 16
        }
        val manage = L("Manage tags…", "Gérer mes tags…")
        UiKit.button(ctx, textRenderer, lx, y, lw, 13, manage, inside(mx, my, lx, y, lw, 13))
        hit(lx, y, lw, 13) { dialog = TagManagerDialog(this) }
        y += 16

        ctx.disableScissor()
        val full = y + filterScroll - areaTop
        filterNeed = full + FOLDED_H + 1
        val maxScroll = maxOf(0, full - (bottom - areaTop))
        filterMaxScroll = maxScroll
        filterScroll = filterScroll.coerceIn(0, maxScroll)
        // a row scrolled out of the column keeps its rectangle: it must not catch clicks
        hits.subList(firstHit, hits.size).removeIf { it.y < areaTop || it.y + it.h > bottom }
        UiKit.scrollbar(ctx, lx + lw + 2, areaTop, bottom, full, filterScroll)
    }

    private fun stepLevel(v: Int, d: Int): Int {
        val step = if (v < 10 || (v == 10 && d < 0)) 1 else 5
        return (v + d * step).coerceIn(1, 100)
    }

    /** A filter chip: lit when it requires, red and struck through when it excludes. */
    private fun drawChip(ctx: DrawContext, x: Int, y: Int, w: Int, label: String, color: Int, active: Boolean, hov: Boolean, struck: Boolean = false) {
        ctx.fill(x, y, x + w, y + 12, if (active) UiKit.withAlpha(color, 0x30) else if (hov) UiKit.SURFACE_HOVER else UiKit.SURFACE_SUNKEN)
        UiKit.border(ctx, x, y, w, 12, if (active) UiKit.withAlpha(color, 0xC0) else UiKit.BORDER_DIM)
        ctx.drawText(textRenderer, label, x + 4, y + 2, color, false)
        if (struck) ctx.fill(x + 3, y + 6, x + w - 3, y + 7, color)
    }

    internal fun typeName(id: String): String = runCatching { ElementalTypes.get(id)?.displayName?.string }.getOrNull() ?: id
    internal fun boxName(b: Int): String {
        val custom = session.pc.boxes.getOrNull(b)?.name?.string?.takeIf { it.isNotBlank() }
        return custom ?: (L("Box ", "Boîte ") + (b + 1))
    }
    private fun ageLabel(h: Int) = when {
        h < 24 -> "< $h h"
        else -> "< ${h / 24} " + L(if (h / 24 > 1) "days" else "day", if (h / 24 > 1) "jours" else "jour")
    }

    // ── menus opened from the filter column ──

    private fun openSortMenu(x: Int, y: Int) {
        val items = PcFilterState.SORTS.map { s ->
            MenuItem((if (filter.sort == s) "● " else "   ") + PcFilterState.sortLabel(s)) { setFilter(filter.copy(sort = s)) }
        } + MenuItem((if (filter.reverse) "☑ " else "☐ ") + L("Reverse order", "Ordre inversé"), separatorBefore = true) { setFilter(filter.copy(reverse = !filter.reverse)) }
        menu = PcMenu(x, y, items)
    }

    private fun openTypeMenu(x: Int, y: Int) {
        val counts = HashMap<String, Int>()
        entries.forEach { e -> e.types.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val items = mutableListOf(MenuItem(L("All", "Tous")) { setFilter(filter.copy(type = "")) })
        counts.keys.sortedBy { typeName(it) }.forEach { t -> items += MenuItem("${typeName(t)}  (${counts[t]})") { setFilter(filter.copy(type = t)) } }
        menu = PcMenu(x, y, items)
    }

    private fun openAbilityMenu(x: Int, y: Int) {
        val counts = entries.groupBy { it.abilityId }
        val items = mutableListOf(MenuItem(L("All", "Tous")) { setFilter(filter.copy(ability = "")) })
        counts.entries.sortedBy { it.value.first().abilityName }.forEach { (id, list) ->
            items += MenuItem("${list.first().abilityName}  (${list.size})") { setFilter(filter.copy(ability = id)) }
        }
        menu = PcMenu(x, y, items, title = L("Abilities in your PC", "Talents présents dans le PC"))
    }

    private fun openBoxFilterMenu(x: Int, y: Int) {
        val items = mutableListOf(
            MenuItem(L("All", "Toutes")) { setFilter(filter.copy(box = -1)) },
            MenuItem(L("Party", "Équipe")) { setFilter(filter.copy(box = -2)) },
        )
        for (b in 0 until session.boxCount) {
            val n = session.pc.boxes[b].slots.count { it != null }
            items += MenuItem("${boxName(b)}  ($n)") { setFilter(filter.copy(box = b)) }
        }
        menu = PcMenu(x, y, items)
    }

    private fun openTagFilterMenu(x: Int, y: Int) {
        val items = mutableListOf(
            MenuItem(L("All", "Tous")) { setFilter(filter.copy(tag = "")) },
            MenuItem(L("Untagged", "Sans tag")) { setFilter(filter.copy(tag = "-")) },
        )
        prefs.allTags().forEachIndexed { i, t ->
            val n = entries.count { t.id in prefs.tagsOf(it.uuid) }
            items += MenuItem("${t.name}  ($n)", separatorBefore = i == 0) { setFilter(filter.copy(tag = t.id)) }
        }
        menu = PcMenu(x, y, items)
    }

    private fun openSearchesMenu(x: Int, y: Int) {
        val items = mutableListOf(MenuItem(L("Save this search…", "Enregistrer cette recherche…")) { dialog = SaveSearchDialog(this) })
        prefs.searches().forEachIndexed { i, s ->
            items += MenuItem(s.name, separatorBefore = i == 0, onDelete = { prefs.deleteSearch(s.name); menu = null }) {
                setFilter(s.filter); notify(L("Search loaded: ", "Recherche chargée : ") + s.name)
            }
        }
        if (prefs.searches().isEmpty()) items += MenuItem(L("No saved search", "Aucune recherche enregistrée"), enabled = false, separatorBefore = true) {}
        menu = PcMenu(x, y, items)
    }

    // ── toolbar above the PC ──

    private fun drawToolbar(ctx: DrawContext, mx: Int, my: Int) {
        val y = bodyTop
        var x = cx
        x += smallButton(ctx, x, y, L("Boxes", "Boîtes"), mx, my, view == "boxes") { view = "boxes"; scroll = 0 } + 2
        x += smallButton(ctx, x, y, L("Details", "Détails"), mx, my, view == "details") { view = "details"; scroll = 0 } + 6
        // short labels when the centre column is narrow (small windows)
        val compact = cw < 440
        val dimLabel = if (compact) (if (prefs.dim) L("Dimmed", "Atténués") else L("Hidden", "Masqués"))
                       else if (prefs.dim) L("Others: dimmed", "Autres : atténués") else L("Others: hidden", "Autres : masqués")
        x += smallButton(ctx, x, y, dimLabel, mx, my) { prefs.dim = !prefs.dim } + 4
        if (view == "details") {
            val v = when (listStats) { 1 -> "EV"; 2 -> "Stats"; else -> "IV" }
            val vals = if (compact) v else L("Values: ", "Valeurs : ") + v
            x += smallButton(ctx, x, y, vals, mx, my) { listStats = (listStats + 1) % 3 } + 4
        }
        val onlyLabel = (if (onlySelected) "☑ " else "☐ ") + if (compact) L("Selection", "Sélection") else L("Selection only", "Sélection seule")
        x += smallButton(ctx, x, y, onlyLabel, mx, my, onlySelected) { onlySelected = !onlySelected; refilter() } + 4
        // labelled boxes waiting for Pokémon: one click sends them there
        if (routingCount > 0) {
            val label = "⇄ " + L("$routingCount to their box", "$routingCount vers leur boîte")
            val bw = textRenderer.getWidth(label) + 10
            if (x + bw <= cx + cw) {
                UiKit.goldButton(ctx, textRenderer, x, y, bw, 14, label, inside(mx, my, x, y, bw, 14))
                hit(x, y, bw, 14) { if (!ops.running) ops.start(PcOps.Kind.ARRANGE, PcOps.planRouting(entries, session, prefs)) }
                if (inside(mx, my, x, y, bw, 14)) tip.set(mx, my,
                    L("Send to their labelled box", "Envoyer dans leur boîte étiquetée") to UiKit.TEXT,
                    L("$routingCount Pokémon match a box label but are elsewhere.", "$routingCount Pokémon correspondent à une étiquette de boîte mais sont ailleurs.") to UiKit.TEXT_FAINT)
                x += bw + 4
            }
        }
        val count = "${results.size} / ${entries.size}"
        val countX = cx + cw - 6 - textRenderer.getWidth(count)
        if (countX > x) ctx.drawText(textRenderer, count, countX, y + 3, UiKit.TEXT_FAINT, false)
    }

    // ── box view ──

    private fun tileX(b: Int) = cx + (b % perRow) * (tileW + TILE_GAP)
    private fun tileY(b: Int) = cTop + (b / perRow) * (tileH + TILE_GAP) - scroll

    private val batch = PcPortraitAtlas.Batch()

    /**
     * Every box as a tile. Drawn in three batched passes per box — backgrounds, then all
     * portraits in one call, then markers — because a full view is several hundred cells
     * and one draw call per fill or picture made the frame rate collapse.
     */
    private fun drawBoxes(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        val hmx = if (my in cTop until cBottom) mx else -9999
        val d = cell - 1   // drawn size of a cell; the missing pixel is the grid line
        for (b in 0 until session.boxCount) {
            val tx = tileX(b); val ty = tileY(b)
            if (ty + tileH < cTop || ty > cBottom) continue
            val box = session.pc.boxes[b]
            val ents = box.slots.map { p -> p?.let { byId[it.uuid] } }
            val count = ents.count { it != null }
            val matches = ents.count { it != null && it.uuid in resultIds }
            val headHov = inside(mx, my, tx, ty, tileW, HEADER_H)
            fun cx(s: Int) = tx + 3 + (s % 6) * cell
            fun cy(s: Int) = ty + HEADER_H + 1 + (s / 6) * cell
            fun visible(s: Int) = cy(s) + cell >= cTop && cy(s) <= cBottom

            ctx.draw {
                ctx.fill(tx, ty, tx + tileW, ty + tileH, if (headHov && dragging) UiKit.withAlpha(UiKit.accent(), 0x40) else UiKit.withAlpha(UiKit.SURFACE, 0xC0))
                UiKit.border(ctx, tx, ty, tileW, tileH, if (headHov && dragging) UiKit.accent() else UiKit.BORDER_DIM)
                drawBoxHeader(ctx, b, tx + 1, ty, tileW - 2, HEADER_H, mx, my, count, matches)
                for (s in ents.indices) {
                    if (!visible(s)) continue
                    val e = ents[s]
                    ctx.fill(cx(s), cy(s), cx(s) + d, cy(s) + d, if (e == null) 0xFF15191E.toInt() else 0xFF1F252C.toInt())
                    if (e != null && e.uuid in selection) ctx.fill(cx(s), cy(s), cx(s) + d, cy(s) + d, UiKit.withAlpha(UiKit.accent(), 0x55))
                }
            }

            for (s in ents.indices) {
                val e = ents[s] ?: continue
                if (!visible(s)) continue
                val hidden = e.uuid !in resultIds && !prefs.dim
                if (hidden || (dragging && e.uuid in selection)) continue
                if (!PcPortraitAtlas.queue(batch, e.pokemon, cx(s) + 1, cy(s) + 1, d - 2) && !PcPortraitAtlas.available)
                    portrait(ctx, e, cx(s) + 1, cy(s) + 1, d - 2, delta)
            }
            PcPortraitAtlas.flush(ctx, batch)

            ctx.draw {
                for (s in ents.indices) {
                    if (!visible(s)) continue
                    val x = cx(s); val y = cy(s)
                    val e = ents[s]
                    val hov = inside(hmx, my, x, y, cell, cell)
                    if (e == null) { if (hov && dragging) UiKit.border(ctx, x, y, d, d, UiKit.accent()); continue }
                    if (hov) hovered = e
                    val match = e.uuid in resultIds
                    val hidden = !match && !prefs.dim
                    if (!match) ctx.fill(x, y, x + d, y + d, if (hidden) 0xE0101418.toInt() else 0xA0101418.toInt())
                    if (!hidden) {
                        if (e.shiny) ctx.fill(x + 1, y + 1, x + 4, y + 4, SHINY_COLOR)
                        if (prefs.isFavorite(e.uuid)) ctx.fill(x + d - 4, y + 1, x + d - 1, y + 4, FAV_COLOR)
                        if (e.alpha) ctx.fill(x + 1, y + d - 5, x + 4, y + d - 2, ALPHA_COLOR)
                        else if (e.special) ctx.fill(x + 1, y + d - 5, x + 4, y + d - 2, LEGEND_COLOR)
                        if (e.hasItem) ctx.fill(x + d - 4, y + d - 5, x + d - 1, y + d - 2, ITEM_COLOR)
                        ctx.fill(x + 1, y + d - 1, x + 1 + ((d - 2) * e.ivPct / 100.0).toInt(), y + d, ivColor(e.ivSum, 186))
                    }
                    if (e.uuid in selection) UiKit.border(ctx, x, y, d, d, UiKit.accentBright())
                    else if (hov) UiKit.border(ctx, x, y, d, d, 0xC0FFFFFF.toInt())
                }
            }
        }
    }

    /** A 7×7 pencil, drawn rather than typed: the font has no reliable glyph for it. */
    internal fun pencil(ctx: DrawContext, x: Int, y: Int, color: Int) {
        for (i in 0..4) ctx.fill(x + 1 + i, y + 4 - i, x + 3 + i, y + 6 - i, color)
        ctx.fill(x, y + 6, x + 2, y + 7, color)
    }

    private var lastHeaderClick = 0L
    private var lastHeaderBox = -1
    private var selectionBeforeHeader: List<UUID> = emptyList()

    /**
     * One click on a box's name selects its Pokémon; a second click right after renames the
     * box instead, and puts the selection back as it was before the first click.
     */
    private fun headerClick(b: Int) {
        val now = System.currentTimeMillis()
        if (b == lastHeaderBox && now - lastHeaderClick < 350) {
            selection.clear(); selection.addAll(selectionBeforeHeader)
            lastHeaderBox = -1
            renameBox(b)
            return
        }
        lastHeaderClick = now; lastHeaderBox = b
        selectionBeforeHeader = selection.toList()
        selectBox(b)
    }

    private fun renameBox(b: Int) {
        if (ops.running) return
        dialog = RenameBoxDialog(this, b)
    }

    /** Header click: select the box's matching Pokémon (Ctrl adds them to the selection). */
    private fun selectBox(b: Int) {
        val ids = session.pc.boxes[b].slots.mapNotNull { it?.uuid }.filter { it in resultIds }
        if (!hasControlDown()) selection.clear()
        selection.addAll(ids)
        if (onlySelected) refilter()
    }

    private fun cellAt(mx: Double, my: Double): PcPlace? {
        if (my < cTop || my >= cBottom || mx < cx || mx >= cx + cw) return null
        for (b in 0 until session.boxCount) {
            val tx = tileX(b); val ty = tileY(b)
            if (mx < tx || mx >= tx + tileW || my < ty || my >= ty + tileH) continue
            val col = ((mx - tx - 3) / cell).toInt(); val row = ((my - ty - HEADER_H - 1) / cell).toInt()
            if (my < ty + HEADER_H + 1 || col !in 0..5 || row !in 0..4) return null
            return PcPlace(b, row * 6 + col)
        }
        return null
    }

    private fun boxHeaderAt(mx: Double, my: Double): Int? {
        if (view != "boxes") return detailsHeaderAt(mx, my)
        if (my < cTop || my >= cBottom) return null
        for (b in 0 until session.boxCount) if (mx >= tileX(b) && mx < tileX(b) + tileW && my >= tileY(b) && my < tileY(b) + HEADER_H) return b
        return null
    }

    private fun partyAt(mx: Double, my: Double): Int? {
        for (i in 0 until 6) if (mx >= partyCellX(i) && mx < partyCellX(i) + PARTY_CELL && my >= partyY && my < partyY + PARTY_CELL) return i
        return null
    }

    // ── detailed view: one card per Pokémon, box by box ──

    /** A line of the detailed view: a box (or results) heading, or a row of cards. */
    private class DRow(val y: Int, val h: Int, val box: Int, val header: Boolean, val cards: List<PcEntry>)

    private var drows: List<DRow> = emptyList()
    private var cardCols = 4
    private var cardW = 80

    /** Lays out the detailed view; returns its height. */
    private fun layoutDetails(): Int {
        cardCols = maxOf(2, (cw - 6 + CARD_GAP) / (CARD_MIN_W + CARD_GAP))
        cardW = (cw - 6 - (cardCols - 1) * CARD_GAP) / cardCols
        val rows = mutableListOf<DRow>()
        var y = 0
        fun cards(box: Int, list: List<PcEntry>) {
            list.chunked(cardCols).forEach { rows += DRow(y, CARD_H, box, false, it); y += CARD_H + CARD_GAP }
        }
        if (filter.sort == PcFilterState.SORT_PLACE) {
            for (b in 0 until session.boxCount) {
                val list = session.pc.boxes[b].slots.mapNotNull { p -> p?.let { byId[it.uuid] } }
                    .filter { prefs.dim || it.uuid in resultIds }
                if (list.isEmpty()) continue
                rows += DRow(y, DHEAD_H, b, true, emptyList()); y += DHEAD_H + 2
                cards(b, list)
                y += 4
            }
        } else {
            rows += DRow(y, DHEAD_H, -1, true, emptyList()); y += DHEAD_H + 2
            cards(-1, results)
        }
        drows = rows
        return y
    }

    private fun cardX(i: Int) = cx + i * (cardW + CARD_GAP)

    private fun drawDetails(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        val hmx = if (my in cTop until cBottom) mx else -9999
        val visible = drows.filter { cTop + it.y - scroll + it.h >= cTop && cTop + it.y - scroll <= cBottom }
        // pass 1: card backgrounds
        ctx.draw {
            for (r in visible) {
                if (r.header) continue
                val y = cTop + r.y - scroll
                r.cards.forEachIndexed { i, e ->
                    val x = cardX(i)
                    ctx.fill(x, y, x + cardW, y + CARD_H, 0xFF1B2026.toInt())
                    if (e.uuid in selection) ctx.fill(x, y, x + cardW, y + CARD_H, UiKit.withAlpha(UiKit.accent(), 0x40))
                    ctx.fill(x + 2, y + 2, x + 22, y + 22, 0xFF252C34.toInt())
                }
            }
        }
        // pass 2: every portrait in one call
        for (r in visible) {
            if (r.header) continue
            val y = cTop + r.y - scroll
            r.cards.forEachIndexed { i, e ->
                if (dragging && e.uuid in selection) return@forEachIndexed
                if (!PcPortraitAtlas.queue(batch, e.pokemon, cardX(i) + 2, y + 2, 20) && !PcPortraitAtlas.available)
                    portrait(ctx, e, cardX(i) + 2, y + 2, 20, delta)
            }
        }
        PcPortraitAtlas.flush(ctx, batch)
        // pass 3: text, stats, markers, headings
        val labels = statLabels()
        ctx.draw {
            for (r in visible) {
                val y = cTop + r.y - scroll
                if (r.header) {
                    if (r.box >= 0) {
                        val list = session.pc.boxes[r.box].slots.filterNotNull()
                        drawBoxHeader(ctx, r.box, cx, y, cw - 6, DHEAD_H, mx, my, list.size, list.count { it.uuid in resultIds })
                    } else {
                        val t = L("Sorted by ", "Triés par ") + PcFilterState.sortLabel(filter.sort) + (if (filter.reverse) " ↑" else " ↓") + "  ·  ${results.size}"
                        ctx.fill(cx, y, cx + cw - 6, y + DHEAD_H, UiKit.withAlpha(UiKit.SURFACE, 0xC0))
                        ctx.drawText(textRenderer, t, cx + 4, y + 3, UiKit.TEXT_MUTED, false)
                        hit(cx, y, cw - 6, DHEAD_H) { setFilter(filter.copy(reverse = !filter.reverse)) }
                    }
                    continue
                }
                r.cards.forEachIndexed { i, e -> drawCard(ctx, e, cardX(i), y, hmx, my, labels) }
            }
        }
        // pass 4: type icons, Cobblemon's small ones (drawn on their own, out of the batch)
        for (r in visible) {
            if (r.header) continue
            val y = cTop + r.y - scroll
            r.cards.forEachIndexed { i, e ->
                if (dragging && e.uuid in selection) return@forEachIndexed
                val iw = TypeIcons.width(e.elementalTypes.size, small = true)
                TypeIcons.draw(ctx, e.elementalTypes, cardX(i) + cardW - 6 - iw, y + 2, small = true, opacity = if (e.uuid in resultIds) 1f else 0.35f)
            }
        }
        if (drows.none { !it.header }) {
            val t = L("No Pokémon matches.", "Aucun Pokémon ne correspond.")
            ctx.drawText(textRenderer, t, cx + (cw - textRenderer.getWidth(t)) / 2, cTop + 40, UiKit.TEXT_FAINT, false)
        }
    }

    /** One card: portrait, name, level, IV %, the six chosen values, nature and ability. */
    private fun drawCard(ctx: DrawContext, e: PcEntry, x: Int, y: Int, mx: Int, my: Int, labels: List<String>) {
        val w = cardW
        val hov = inside(mx, my, x, y, w, CARD_H)
        if (hov) hovered = e
        val iw = TypeIcons.width(e.elementalTypes.size, small = true)
        ctx.drawText(textRenderer, fit(textRenderer, e.displayName, w - 34 - iw), x + 25, y + 3, if (e.shiny) SHINY_COLOR else UiKit.TEXT, false)
        val lv = L("Lv ", "Nv ") + e.level + when (e.gender) { 1 -> " ♂"; 2 -> " ♀"; else -> "" }
        ctx.drawText(textRenderer, lv, x + 25, y + 13, UiKit.TEXT_FAINT, false)
        val pct = "${e.ivPct.toInt()}%"
        ctx.drawText(textRenderer, pct, x + w - 3 - textRenderer.getWidth(pct), y + 13, ivColor(e.ivSum, 186), false)
        // the six values, three per line, label tinted by the nature
        val colW = (w - 4) / 3
        val boosted = e.natureUp != e.natureDown
        for (k in 0 until 6) {
            val v = when (listStats) { 1 -> e.evs[k]; 2 -> e.stats[k]; else -> e.ivs[k] }
            val sx = x + 2 + (k % 3) * colW
            val sy = y + 25 + (k / 3) * 9
            val lc = if (boosted && k == e.natureUp) 0xFFFF9A9A.toInt() else if (boosted && k == e.natureDown) 0xFF9AB8FF.toInt() else UiKit.TEXT_FAINT
            val vc = when (listStats) { 1 -> if (v > 0) ivColor(v, 252) else UiKit.TEXT_FAINT; 2 -> UiKit.TEXT; else -> ivColor(v) }
            ctx.drawText(textRenderer, labels[k], sx, sy, lc, false)
            val t = v.toString()
            ctx.drawText(textRenderer, t, sx + colW - 3 - textRenderer.getWidth(t), sy, vc, false)
        }
        val tail = e.natureName + " · " + e.abilityName
        ctx.drawText(textRenderer, fit(textRenderer, tail, w - (if (e.hasItem) 16 else 5)), x + 3, y + 45, if (e.hiddenAbility) UiKit.GOLD else UiKit.TEXT_MUTED, false)
        if (e.hasItem) {
            ctx.matrices.push(); ctx.matrices.translate((x + w - 11).toFloat(), (y + 44).toFloat(), 0f); ctx.matrices.scale(0.6f, 0.6f, 1f)
            ctx.drawItem(e.heldItem, 0, 0)
            ctx.matrices.pop()
        }
        // corner markers, as in the box view
        if (prefs.isFavorite(e.uuid)) ctx.fill(x + w - 4, y + 1, x + w - 1, y + 4, FAV_COLOR)
        if (e.alpha) ctx.fill(x + w - 4, y + 5, x + w - 1, y + 8, ALPHA_COLOR)
        else if (e.special) ctx.fill(x + w - 4, y + 5, x + w - 1, y + 8, LEGEND_COLOR)
        if (e.uuid !in resultIds) ctx.fill(x, y, x + w, y + CARD_H, 0xA0101418.toInt())
        if (e.uuid in selection) UiKit.border(ctx, x, y, w, CARD_H, UiKit.accentBright())
        else UiKit.border(ctx, x, y, w, CARD_H, if (hov) 0xC0FFFFFF.toInt() else UiKit.BORDER_DIM)
    }

    private fun cardAt(mx: Double, my: Double): PcEntry? {
        if (view != "details" || my < cTop || my >= cBottom || mx < cx || mx >= cx + cw - 5) return null
        for (r in drows) {
            if (r.header) continue
            val y = cTop + r.y - scroll
            if (my < y || my >= y + CARD_H) continue
            r.cards.forEachIndexed { i, e -> if (mx >= cardX(i) && mx < cardX(i) + cardW) return e }
        }
        return null
    }

    /** Heading of a box in the detailed view under the cursor (a drop target). */
    private fun detailsHeaderAt(mx: Double, my: Double): Int? {
        if (view != "details" || my < cTop || my >= cBottom) return null
        for (r in drows) if (r.header && r.box >= 0) {
            val y = cTop + r.y - scroll
            if (my >= y && my < y + DHEAD_H && mx >= cx && mx < cx + cw - 6) return r.box
        }
        return null
    }

    // ── box heading, shared by both views: name, rename, label ──

    /**
     * The name of box [b] with its two controls: a pencil to rename it (double-clicking the
     * name does the same) and a flag to give it a label; the flag takes the colour of the
     * label. A click on the name selects the box's Pokémon, a right click opens its menu.
     */
    private fun drawBoxHeader(ctx: DrawContext, b: Int, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int, count: Int, matches: Int) {
        val headHov = inside(mx, my, x, y, w, h)
        ctx.fill(x, y, x + w, y + h, if (headHov && dragging) UiKit.withAlpha(UiKit.accent(), 0x40) else UiKit.withAlpha(UiKit.SURFACE, 0xC0))
        val rule = prefs.boxRule(b)
        val c = if (filter.isEmpty && !onlySelected) "$count" else "$matches/$count"
        val ruleText = rule?.let { BoxRules.label(it, prefs, ::typeName) }
        val ruleW = if (ruleText != null && w >= 150) textRenderer.getWidth(ruleText) + 8 else 0
        val shownName = fit(textRenderer, boxName(b), w - 34 - textRenderer.getWidth(c) - ruleW)
        val ty = y + (h - 8) / 2
        ctx.drawText(textRenderer, shownName, x + 3, ty, if (headHov) UiKit.TEXT else UiKit.TEXT_MUTED, false)
        var px = x + 3 + textRenderer.getWidth(shownName) + 4
        hit(x, y, w, h, right = { _, _ -> openBoxMenu(b, mx, y + h) }) { headerClick(b) }
        if (!dragging) {
            pencil(ctx, px, ty, if (inside(mx, my, px - 2, y, 11, h)) UiKit.accentBright() else if (headHov) UiKit.TEXT_MUTED else UiKit.TEXT_FAINT)
            hit(px - 2, y, 11, h) { renameBox(b) }
            px += 11
            val fc = if (rule != null) BoxRules.color(rule) else if (inside(mx, my, px - 2, y, 11, h)) UiKit.accentBright() else if (headHov) UiKit.TEXT_MUTED else UiKit.TEXT_FAINT
            flag(ctx, px, ty, fc, rule != null)
            val fx = px
            hit(fx - 2, y, 11, h) { openBoxMenu(b, fx, y + h) }
            if (inside(mx, my, fx - 2, y, 11, h)) tip.set(mx, my,
                (if (rule != null) L("Label: ", "Étiquette : ") + ruleText else L("Give this box a label", "Donner une étiquette à cette boîte")) to UiKit.TEXT,
                L("Arranging the PC sends the matching Pokémon here.", "Ranger le PC y envoie les Pokémon correspondants.") to UiKit.TEXT_FAINT)
            px += 11
        }
        if (ruleText != null && ruleW > 0) {
            UiKit.chip(ctx, textRenderer, px + 2, y + (h - 11) / 2, ruleText, BoxRules.color(rule))
        }
        ctx.drawText(textRenderer, c, x + w - 3 - textRenderer.getWidth(c), ty, if (count >= session.boxSize(b)) UiKit.GOLD else UiKit.TEXT_FAINT, false)
    }

    /** A 7×7 flag, filled when the box has a label. */
    private fun flag(ctx: DrawContext, x: Int, y: Int, color: Int, filled: Boolean) {
        ctx.fill(x, y, x + 1, y + 8, color)
        if (filled) ctx.fill(x + 1, y, x + 7, y + 4, color)
        else { ctx.fill(x + 1, y, x + 7, y + 1, color); ctx.fill(x + 6, y, x + 7, y + 4, color); ctx.fill(x + 1, y + 3, x + 7, y + 4, color) }
    }

    /** Box menu: rename, label (rule), select its Pokémon. */
    private fun openBoxMenu(b: Int, x: Int, y: Int) {
        val rule = prefs.boxRule(b)
        fun pick(r: String?) { prefs.setBoxRule(b, r); refilter() }
        fun mark(r: String?) = if (rule == r) "● " else "   "
        val items = mutableListOf(
            MenuItem(L("Rename…", "Renommer…")) { renameBox(b) },
            MenuItem(L("Select its Pokémon", "Sélectionner ses Pokémon")) { selectBox(b) },
            MenuItem(mark(null) + L("No label", "Aucune étiquette"), separatorBefore = true) { pick(null) },
        )
        for (r in BoxRules.BASIC) items += MenuItem(mark(r) + BoxRules.label(r, prefs, ::typeName), color = BoxRules.color(r)) { pick(r) }
        items += MenuItem((if (rule?.startsWith("type:") == true) "● " else "   ") + L("A type ▸", "Un type ▸")) {
            menu = PcMenu(x, y, ElementalTypes.all().map { t -> MenuItem(mark("type:${t.name.lowercase()}") + t.displayName.string) { pick("type:${t.name.lowercase()}") } },
                title = boxName(b))
        }
        items += MenuItem((if (rule?.startsWith("tag:") == true) "● " else "   ") + L("A tag ▸", "Un tag ▸")) {
            menu = PcMenu(x, y, prefs.allTags().map { t -> MenuItem(mark("tag:${t.id}") + t.name) { pick("tag:${t.id}") } }, title = boxName(b))
        }
        menu = PcMenu(x, y, items, title = boxName(b) + " — " + L("label: what goes here when arranging", "étiquette : ce qui y va au rangement"))
    }

    // ── side column: detail + selection actions ──

    /** The Pokémon the side column shows this frame (hovered, else clicked, else last selected). */
    private var detailEntry: PcEntry? = null

    // the last Pokémon pointed at, kept a moment while the cursor crosses the gap between two cells
    private var lastHover: UUID? = null
    private var lastHoverAt = 0L

    private fun drawSide(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        val actionsH = 120
        val detailBottom = footerY - 3 - actionsH - 4
        val now = System.currentTimeMillis()
        hovered?.let { lastHover = it.uuid; lastHoverAt = now }
        val lingering = if (hovered == null && now - lastHoverAt < 250) lastHover?.let { byId[it] } else null
        val e = hovered ?: lingering ?: focus?.let { byId[it] } ?: selection.lastOrNull()?.let { byId[it] }
        detailEntry = e
        if (e == null) {
            UiKit.sectionTitle(ctx, textRenderer, L("Detail", "Fiche"), rx, bodyTop)
            val lines = textRenderer.wrapLines(Text.literal(L(
                "Point at a Pokémon to see it here. Click to select, Ctrl+click to add, Shift+click for a range, drag across empty space to select an area, drag a Pokémon onto a box or the party to move it, onto a saved team (bottom left) to add it there.",
                "Survolez un Pokémon pour voir sa fiche ici. Clic : sélectionner, Ctrl+clic : ajouter, Maj+clic : une plage, glisser sur une zone vide : sélection par zone, glisser un Pokémon sur une boîte ou l'équipe : le déplacer, sur une équipe enregistrée (en bas à gauche) : l'y ajouter.")), rw)
            var yy = bodyTop + 14
            for (l in lines) { ctx.drawText(textRenderer, l, rx, yy, UiKit.TEXT_FAINT, false); yy += 10 }
        } else drawDetail(ctx, e, mx, my, delta, detailBottom)
        drawSelectionActions(ctx, mx, my, footerY - 3 - actionsH)
    }

    private fun drawDetail(ctx: DrawContext, e: PcEntry, mx: Int, my: Int, delta: Float, bottom: Int) {
        var y = bodyTop
        UiKit.portrait(ctx, rx, y, 40, 40)
        if (!UiKit.isModern) UiKit.slot(ctx, rx, y, 40, 40)
        portrait(ctx, e, rx + 2, y + 2, 36, delta)
        val tx = rx + 45
        val tw = rw - 45
        ctx.drawText(textRenderer, fit(textRenderer, e.displayName, tw - 12), tx, y + 1, if (e.shiny) SHINY_COLOR else UiKit.TEXT, true)
        // favourite toggle
        val fav = prefs.isFavorite(e.uuid)
        val fx = rx + rw - 9
        val fh = inside(mx, my, fx - 1, y, 10, 10)
        ctx.drawText(textRenderer, if (fav) "♥" else "♡", fx, y + 1, if (fav || fh) FAV_COLOR else UiKit.TEXT_FAINT, false)
        hit(fx - 1, y, 10, 10) { prefs.setFavorite(e.uuid, !fav); refilter() }
        if (fh) tip.set(mx, my, (if (fav) L("Favourite: never released", "Favori : jamais relâché") else L("Keep as favourite", "Garder en favori")) to UiKit.TEXT)
        val sub = buildString {
            if (e.nickname != null) append(e.speciesName).append(" · ")
            append(L("Lv ", "Nv ")).append(e.level)
            append(when (e.gender) { 1 -> " ♂"; 2 -> " ♀"; else -> "" })
        }
        // the types as Cobblemon draws them, on the right of the level and place lines
        val iw = TypeIcons.width(e.elementalTypes.size)
        val ix = rx + rw - iw
        TypeIcons.draw(ctx, e.elementalTypes, ix, y + 11)
        if (inside(mx, my, ix, y + 11, iw, TypeIcons.SIZE)) tip.set(mx, my, *TypeIcons.tooltip(e.elementalTypes))
        ctx.drawText(textRenderer, fit(textRenderer, sub, tw - iw - 4), tx, y + 11, UiKit.TEXT_MUTED, false)
        val place = if (e.place.inParty) L("Party, slot ", "Équipe, place ") + (e.place.slot + 1) else boxName(e.place.box) + " · " + (e.place.slot + 1)
        ctx.drawText(textRenderer, fit(textRenderer, place, tw - iw - 4), tx, y + 21, UiKit.TEXT_FAINT, false)
        var bx = tx
        val kinds = listOf(
            Triple("Shiny", SHINY_COLOR, e.shiny), Triple("Alpha", ALPHA_COLOR, e.alpha),
            Triple(L("Legendary", "Légendaire"), LEGEND_COLOR, e.legendary), Triple(L("Mythical", "Fabuleux"), LEGEND_COLOR, e.mythical),
            Triple(L("Ultra Beast", "Ultra-Chimère"), LEGEND_COLOR, e.ultraBeast))
        for ((label, color, on) in kinds) {
            if (!on || bx + textRenderer.getWidth(label) + 8 > rx + rw) continue
            bx += UiKit.chip(ctx, textRenderer, bx, y + 30, label, color) + 2
        }
        y += 44
        fun line(label: String, value: String, color: Int = UiKit.TEXT, icon: net.minecraft.item.ItemStack? = null) {
            if (y + 9 > bottom) return
            ctx.drawText(textRenderer, label, rx, y, UiKit.TEXT_FAINT, false)
            var vx = rx + 46
            if (icon != null && !icon.isEmpty) {
                ctx.matrices.push(); ctx.matrices.translate(vx.toFloat(), (y - 1).toFloat(), 0f); ctx.matrices.scale(0.6f, 0.6f, 1f)
                ctx.drawItem(icon, 0, 0)
                ctx.matrices.pop()
                vx += 12
            }
            ctx.drawText(textRenderer, fit(textRenderer, value, rx + rw - vx), vx, y, color, false)
            y += 10
        }
        val labels = statLabels()
        val natureMod = if (e.natureUp >= 0 && e.natureUp != e.natureDown) " (+${labels[e.natureUp]} −${labels[e.natureDown]})" else ""
        line("Nature", e.natureName + natureMod)
        line(L("Ability", "Talent"), e.abilityName + if (e.hiddenAbility) " · " + L("hidden", "caché") else "", if (e.hiddenAbility) UiKit.GOLD else UiKit.TEXT)
        line(L("Item", "Objet"), if (e.hasItem) e.itemName else "—", if (e.hasItem) ITEM_COLOR else UiKit.TEXT_FAINT, if (e.hasItem) e.heldItem else null)
        if (e.otName.isNotBlank()) line(L("OT", "DO"), e.otName + if (e.ownOt) "" else " · " + L("traded", "échangé"))
        val seen = prefs.firstSeen(e.uuid)
        line(L("Arrived", "Arrivé"), if (seen <= 0L) L("unknown", "inconnu") else ago(seen) + "*", UiKit.TEXT_MUTED)

        // marks: their own icons, as many as fit, details on hover
        val marks = runCatching { e.pokemon.marks.sortedBy { it.indexNumber ?: 9999 } }.getOrDefault(emptyList())
        if (marks.isNotEmpty() && y + 12 <= bottom) {
            ctx.drawText(textRenderer, L("Marks", "Insignes"), rx, y + 2, UiKit.TEXT_FAINT, false)
            val active = runCatching { e.pokemon.activeMark }.getOrNull()
            var mx2 = rx + 46
            val perLine = maxOf(1, (rw - 46) / 13)
            marks.forEachIndexed { i, m ->
                if (i > 0 && i % perLine == 0) { y += 13; mx2 = rx + 46 }
                if (y + 12 > bottom) return@forEachIndexed
                val hov = inside(mx, my, mx2, y, 11, 11)
                MarkIcons.draw(ctx, m, mx2, y, 11, m == active, hov)
                if (hov) tip.set(mx, my, *MarkIcons.tooltip(m, e.displayName, m == active))
                mx2 += 13
            }
            y += 14
        }

        // IV / EV table
        y += 2
        if (y + 72 <= bottom) {
            ctx.drawText(textRenderer, "IV ${e.ivSum}/186 · ${"%.0f".format(e.ivPct)}%", rx, y, ivColor(e.ivSum, 186), false)
            val ev = "EV ${e.evSum}"
            ctx.drawText(textRenderer, ev, rx + rw - textRenderer.getWidth(ev), y, UiKit.TEXT_MUTED, false)
            y += 10
            for (k in 0 until 6) {
                val lc = if (k == e.natureUp && e.natureUp != e.natureDown) 0xFFFF9A9A.toInt() else if (k == e.natureDown && e.natureUp != e.natureDown) 0xFF9AB8FF.toInt() else UiKit.TEXT_FAINT
                ctx.drawText(textRenderer, labels[k], rx, y, lc, false)
                val barX = rx + 24; val barW = rw - 24 - 50
                ctx.fill(barX, y + 2, barX + barW, y + 6, UiKit.SURFACE_SUNKEN)
                ctx.fill(barX, y + 2, barX + barW * e.ivs[k] / 31, y + 6, ivColor(e.ivs[k]))
                val iv = e.ivs[k].toString()
                ctx.drawText(textRenderer, iv, barX + barW + 4, y, ivColor(e.ivs[k]), false)
                val evs = e.evs[k].toString()
                ctx.drawText(textRenderer, evs, rx + rw - textRenderer.getWidth(evs), y, if (e.evs[k] > 0) UiKit.TEXT_MUTED else UiKit.TEXT_FAINT, false)
                y += 9
            }
        }
        // moves: Cobblemon's battle buttons, details on hover, a link to manage them
        y += 4
        val twoCols = rw >= 2 * (MoveTiles.TILE_W + MoveTiles.BADGE) + 4
        val tilesH = (if (twoCols) 2 else 4) * (MoveTiles.TILE_H + 4)
        if (y + 12 + tilesH <= bottom) {
            UiKit.sectionTitle(ctx, textRenderer, L("Moves", "Capacités"), rx, y)
            val manage = L("Manage", "Gérer") + " ›"
            val mw = textRenderer.getWidth(manage)
            val mh = inside(mx, my, rx + rw - mw - 2, y - 1, mw + 4, 10)
            ctx.drawText(textRenderer, manage, rx + rw - mw, y, if (mh) UiKit.accentBright() else UiKit.TEXT_MUTED, false)
            hit(rx + rw - mw - 2, y - 1, mw + 4, 10) { dialog = MovesDialog(this, e) }
            y += 12
            val moves = runCatching { e.pokemon.moveSet.getMovesWithNulls() }.getOrDefault(emptyList())
            for (i in 0 until 4) {
                val m = moves.getOrNull(i)
                val col = if (twoCols) i % 2 else 0
                val row = if (twoCols) i / 2 else i
                val tx = rx + MoveTiles.BADGE + col * (MoveTiles.TILE_W + MoveTiles.BADGE + 4)
                val ty = y + row * (MoveTiles.TILE_H + 4)
                val hov = inside(mx, my, tx - MoveTiles.BADGE, ty, MoveTiles.TILE_W + MoveTiles.BADGE, MoveTiles.TILE_H)
                MoveTiles.battle(ctx, textRenderer, tx, ty, m?.template, m?.currentPp ?: 0, m?.maxPp ?: 0, hov, fine = fine)
                if (hov && m != null) tip.set(mx, my, *MoveTiles.tooltip(m.template, "${m.currentPp}/${m.maxPp}"))
            }
            y += tilesH
        }
        val tags = prefs.tagsOf(e.uuid)
        if (tags.isNotEmpty() && y + 12 <= bottom) {
            var tx2 = rx
            for (t in tags) {
                val n = prefs.tagName(t)
                if (tx2 + textRenderer.getWidth(n) + 8 > rx + rw) break
                tx2 += UiKit.chip(ctx, textRenderer, tx2, y + 1, n, UiKit.accentBright()) + 2
            }
        }
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 60 -> L("$m min ago", "il y a $m min")
            m < 48 * 60 -> L("${m / 60} h ago", "il y a ${m / 60} h")
            else -> L("${m / 1440} days ago", "il y a ${m / 1440} j")
        }
    }

    private fun drawSelectionActions(ctx: DrawContext, mx: Int, my: Int, top: Int) {
        UiKit.dots(ctx, rx, top - 3, rw)
        val n = selection.size
        UiKit.sectionTitle(ctx, textRenderer, if (n == 0) L("Selection", "Sélection") else L("Selection: $n", "Sélection : $n"), rx, top + 1)
        val half = (rw - 3) / 2
        var y = top + 13
        fun btn(col: Int, label: String, enabled: Boolean, danger: Boolean = false, action: (Int, Int) -> Unit) {
            val x = rx + col * (half + 3)
            val by = y
            if (!enabled) {
                UiKit.slot(ctx, x, by, half, 14)
                ctx.drawText(textRenderer, fit(textRenderer, label, half - 4), x + (half - minOf(half - 4, textRenderer.getWidth(label))) / 2, by + 3, UiKit.TEXT_FAINT, false)
                return
            }
            val hov = inside(mx, my, x, by, half, 14)
            if (danger) {
                ctx.fill(x, by, x + half, by + 14, if (hov) 0xFFB83A3A.toInt() else 0xFF7E2626.toInt())
                UiKit.border(ctx, x, by, half, 14, if (hov) 0xFFFF8A8A.toInt() else 0xFFB04848.toInt())
                ctx.drawText(textRenderer, label, x + (half - textRenderer.getWidth(label)) / 2, by + 3, 0xFFFFFFFF.toInt(), true)
            } else UiKit.button(ctx, textRenderer, x, by, half, 14, fit(textRenderer, label, half - 4), hov)
            hit(x, by, half, 14) { action(x, by + 15) }
        }
        val any = n > 0
        btn(0, L("Select all", "Tout"), results.isNotEmpty()) { _, _ -> selectAllResults() }
        btn(1, L("Clear", "Aucun"), any) { _, _ -> selection.clear(); if (onlySelected) refilter() }
        y += 16
        btn(0, L("Move ▾", "Déplacer ▾"), any) { x, yy -> openMoveMenu(x, yy) }
        btn(1, L("To party", "Vers l'équipe"), any) { _, _ -> moveSelectionToParty() }
        y += 16
        btn(0, L("Favourite", "Favori") + " ♥", any) { _, _ -> toggleFavoriteSelection() }
        btn(1, "Tags ▾", any) { x, yy -> openTagsMenu(x, yy) }
        y += 16
        btn(0, L("Compare", "Comparer"), n == 2) { _, _ -> dialog = CompareDialog(this, selected()[0], selected()[1]) }
        btn(1, L("Arrange…", "Ranger…"), true) { _, _ -> dialog = ArrangeDialog(this) }
        y += 16
        btn(0, L("Release…", "Relâcher…"), any, danger = true) { _, _ -> openRelease(selected()) }
        val candY = y + 16
        val shown = detailEntry
        btn(1, L("Wiki page", "Fiche wiki"), shown != null) { _, _ ->
            shown?.let { client?.setScreen(PokemonDetailScreen(it.pokemon.species, this)) }
        }
        val cand = "✦ " + L("Release candidates…", "Candidats au relâchement…")
        UiKit.goldButton(ctx, textRenderer, rx, candY, rw, 14, fit(textRenderer, cand, rw - 6), inside(mx, my, rx, candY, rw, 14))
        hit(rx, candY, rw, 14) { dialog = CandidatesDialog(this) }
    }

    private fun drawFooter(ctx: DrawContext) {
        val y = footerY + 3
        val now = System.currentTimeMillis()
        val t = toast
        if (t != null && now < toastUntil) {
            ctx.drawText(textRenderer, fit(textRenderer, t, pw - 20), x0 + 8, y, if (toastColor != 0) toastColor else UiKit.TEXT, false)
        } else if (controllerHint != null) {
            ctx.drawText(textRenderer, controllerHint, x0 + 8, y, UiKit.TEXT_FAINT, false)
        } else {
            val hint = L("Right click: menu · Double click: into / out of the party · Ctrl+A: select all · Del: release",
                "Clic droit : menu · Double-clic : dans / hors de l'équipe · Ctrl+A : tout sélectionner · Suppr : relâcher")
            ctx.drawText(textRenderer, fit(textRenderer, hint, pw - 20), x0 + 8, y, UiKit.TEXT_FAINT, false)
        }
    }

    private fun drawBand(ctx: DrawContext, mx: Int, my: Int) {
        val x1 = minOf(pressX.toInt(), mx); val x2 = maxOf(pressX.toInt(), mx)
        val y1 = minOf(pressY.toInt(), my).coerceAtLeast(cTop); val y2 = maxOf(pressY.toInt(), my).coerceAtMost(cBottom)
        ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 300f)
        ctx.fill(x1, y1, x2, y2, UiKit.withAlpha(UiKit.accent(), 0x30))
        UiKit.border(ctx, x1, y1, x2 - x1, y2 - y1, UiKit.accentBright())
        ctx.matrices.pop()
    }

    private fun draggedEntries(): List<PcEntry> {
        val p = pressEntry ?: return emptyList()
        return if (p.uuid in selection) selected() else listOf(p)
    }

    private fun drawDragGhost(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        val list = draggedEntries()
        val first = list.firstOrNull() ?: return
        ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 400f)
        ctx.fill(mx - 11, my - 11, mx + 11, my + 11, 0x90101418.toInt())
        UiKit.border(ctx, mx - 11, my - 11, 22, 22, UiKit.accentBright())
        gridPortrait(ctx, first, mx - 10, my - 10, 20, delta)
        if (list.size > 1) {
            val c = "×${list.size}"
            ctx.drawText(textRenderer, c, mx + 12, my - 4, 0xFFFFFFFF.toInt(), true)
        }
        val hint = dropHint(mx.toDouble(), my.toDouble())
        if (hint != null) ctx.drawText(textRenderer, hint, mx + 12, my + 6, UiKit.TEXT_MUTED, true)
        ctx.matrices.pop()
    }

    private fun drawMiniTooltip(ctx: DrawContext, e: PcEntry, mx: Int, my: Int) {
        val lines = listOf("${e.displayName}  ${L("Lv", "Nv")} ${e.level}", "IV ${e.ivPct.toInt()}% · ${e.natureName}", e.abilityName)
        val w = lines.maxOf { textRenderer.getWidth(it) } + 8
        val x = (mx + 10).coerceAtMost(width - w - 2); val y = (my + 10).coerceAtMost(height - 34)
        ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 400f)
        ctx.fill(x, y, x + w, y + lines.size * 10 + 4, 0xF0101418.toInt())
        UiKit.border(ctx, x, y, w, lines.size * 10 + 4, UiKit.BORDER)
        lines.forEachIndexed { i, l -> ctx.drawText(textRenderer, l, x + 4, y + 3 + i * 10, if (i == 0) UiKit.TEXT else UiKit.TEXT_MUTED, false) }
        ctx.matrices.pop()
    }

    private fun drawProgress(ctx: DrawContext, mx: Int, my: Int) {
        ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 700f)
        ctx.fill(0, 0, width, height, 0x90000000.toInt())
        val w = 240; val h = 74
        val x = (width - w) / 2; val y = (height - h) / 2
        UiKit.panel(ctx, x, y, w, h)
        val title = when (ops.kind) {
            PcOps.Kind.RELEASE -> L("Releasing", "Relâchement en cours")
            PcOps.Kind.ARRANGE -> L("Arranging", "Rangement en cours")
            PcOps.Kind.MOVE -> L("Moving", "Déplacement en cours")
            PcOps.Kind.TEAM -> L("Equipping the team", "Équipement de l'équipe")
        }
        ctx.drawText(textRenderer, title, x + 8, y + 7, if (UiKit.isModern) 0xFFFFFFFF.toInt() else UiKit.accent(), true)
        val barX = x + 10; val barW = w - 20; val barY = y + 28
        ctx.fill(barX, barY, barX + barW, barY + 6, UiKit.SURFACE_SUNKEN)
        if (ops.total > 0) ctx.fill(barX, barY, barX + barW * ops.done / ops.total, barY + 6, if (ops.kind == PcOps.Kind.RELEASE) 0xFFE06060.toInt() else UiKit.accent())
        val status = "${ops.done} / ${ops.total} · " + L("waiting for the server…", "en attente du serveur…")
        ctx.drawText(textRenderer, fit(textRenderer, status, w - 20), x + 10, y + 40, UiKit.TEXT_MUTED, false)
        val label = L("Stop", "Arrêter")
        val bw = 60; val bx = x + w - bw - 10; val by = y + h - 20
        UiKit.button(ctx, textRenderer, bx, by, bw, 14, label, inside(mx, my, bx, by, bw, 14))
        ctx.matrices.pop()
        progressButton = intArrayOf(bx, by, bw, 14)
    }
    private var progressButton = intArrayOf(0, 0, 0, 0)

    // ─────────────────────────────────────────────────────────────────────────
    // actions
    // ─────────────────────────────────────────────────────────────────────────

    fun selectAllResults() {
        selection.clear(); results.forEach { selection.add(it.uuid) }
    }

    private fun toggleFavoriteSelection() {
        val list = selected()
        val on = !list.all { prefs.isFavorite(it.uuid) }
        list.forEach { prefs.setFavorite(it.uuid, on) }
        notify(if (on) L("${list.size} added to favourites (never released).", "${list.size} ajouté(s) aux favoris (jamais relâchés).")
               else L("${list.size} removed from favourites.", "${list.size} retiré(s) des favoris."))
        refilter()
    }

    internal fun openTagsMenu(x: Int, y: Int, targets: List<PcEntry> = selected()) {
        if (targets.isEmpty()) return
        val items = prefs.allTags().map { t ->
            val count = targets.count { t.id in prefs.tagsOf(it.uuid) }
            val mark = when (count) { 0 -> "☐ "; targets.size -> "☑ "; else -> "▣ " }
            MenuItem(mark + t.name) {
                val add = count < targets.size
                targets.forEach { prefs.setTag(it.uuid, t.id, add) }
                refilter()
            }
        } + listOf(
            MenuItem(L("Remove every tag", "Retirer tous les tags"), separatorBefore = true) { targets.forEach { prefs.clearTags(it.uuid) }; refilter() },
            MenuItem(L("Manage tags…", "Gérer mes tags…")) { dialog = TagManagerDialog(this) },
        )
        menu = PcMenu(x, y, items, title = if (targets.size == 1) targets[0].displayName else L("${targets.size} Pokémon", "${targets.size} Pokémon"))
    }

    private fun openMoveMenu(x: Int, y: Int, targets: List<PcEntry> = selected()) {
        val items = mutableListOf<MenuItem>()
        for (b in 0 until session.boxCount) {
            val free = session.pc.boxes[b].slots.count { it == null }
            items += MenuItem("${boxName(b)}  · " + L("$free free", "$free libre(s)"), enabled = free > 0 || targets.all { it.place.box == b }) { depositInto(targets, b) }
        }
        menu = PcMenu(x, y, items, title = L("Into box (then the next ones)", "Dans la boîte (puis les suivantes)"))
    }

    /** Puts [list] into the free slots from [box] on; those already in that box stay. */
    internal fun depositInto(list: List<PcEntry>, box: Int) {
        val moving = list.filter { it.place.box != box }
        if (moving.isEmpty()) return
        val partyLeft = session.partyCount() - moving.count { it.place.inParty }
        if (partyLeft < 1 && emptyPartyRefused()) return
        val free = PcOps.freeSlots(session, box)
        if (free.size < moving.size) { notify(L("Not enough free slots.", "Pas assez de places libres."), UiKit.DANGER); return }
        val steps = moving.mapIndexed { i, e -> PcStep.Move(e.uuid, e.place, free[i]) }
        ops.start(PcOps.Kind.MOVE, steps)
    }

    private fun moveSelectionToParty() {
        val list = selected().filter { !it.place.inParty }
        val freeParty = (0 until 6).filter { session.party?.slots?.getOrNull(it) == null }
        if (list.isEmpty()) return
        if (freeParty.isEmpty()) { notify(L("The party is full: drag onto a party member to swap.", "Équipe pleine : glissez sur un membre de l'équipe pour échanger."), UiKit.GOLD); return }
        val steps = list.take(freeParty.size).mapIndexed { i, e -> PcStep.Move(e.uuid, e.place, PcPlace.party(freeParty[i])) }
        if (list.size > freeParty.size) notify(L("Only ${freeParty.size} free place(s) in the party.", "Seulement ${freeParty.size} place(s) libre(s) dans l'équipe."), UiKit.GOLD)
        ops.start(PcOps.Kind.MOVE, steps)
    }

    /**
     * Equips saved team [index]: its Pokémon into the party in order with their moves for it;
     * those they replace back to the PC, into their labelled box when they have one.
     */
    internal fun equipTeam(index: Int) {
        val team = prefs.teams().getOrNull(index) ?: return
        if (ops.running) return
        val plan = PcOps.planTeam(team, session, { id -> entry(id)?.displayName ?: "?" }) { id ->
            entry(id)?.let { BoxRules.boxesFor(it, prefs, session.boxCount) } ?: emptyList()
        }
        teamProblems = if (plan.problems.isEmpty()) "" else "  ·  " + plan.problems.first() + (if (plan.problems.size > 1) " (+${plan.problems.size - 1})" else "")
        when {
            plan.members == 0 -> notify(L("None of this team's Pokémon is here any more.", "Aucun Pokémon de cette équipe n'est encore là."), UiKit.DANGER)
            plan.steps.isEmpty() -> notify(L("This team is already equipped.", "Cette équipe est déjà équipée.") + teamProblems, UiKit.TEXT)
            else -> ops.start(PcOps.Kind.TEAM, plan.steps)
        }
    }

    /** Selects [e] and brings it into view (a team's "show in the PC"). */
    internal fun showEntry(e: PcEntry) {
        selection.clear(); selection.add(e.uuid); focus = e.uuid; anchor = e.uuid
        if (e.uuid !in resultIds) { setFilter(PcFilterState(sort = filter.sort, reverse = filter.reverse, ivTarget = filter.ivTarget)); onlySelected = false; refilter() }
        if (e.place.inParty) return
        layout()
        if (view == "details") {
            cardRows().firstOrNull { r -> r.cards.any { it.uuid == e.uuid } }?.let { r -> reveal(intArrayOf(cx, cTop + r.y - scroll, cardW, CARD_H)) }
        } else reveal(cellRect(e.place.box, e.place.slot))
    }

    internal fun openRelease(list: List<PcEntry>) {
        if (list.isEmpty()) return
        dialog = ReleaseDialog(this, list)
    }

    private fun openContextMenu(e: PcEntry, x: Int, y: Int) {
        val targets = if (e.uuid in selection && selection.size > 1) selected() else listOf(e)
        val fav = targets.all { prefs.isFavorite(it.uuid) }
        val items = mutableListOf(
            MenuItem(if (fav) L("Remove from favourites", "Retirer des favoris") else L("Keep as favourite ♥", "Garder en favori ♥")) {
                targets.forEach { prefs.setFavorite(it.uuid, !fav) }; refilter()
            },
            MenuItem("Tags ▸") { openTagsMenu(x, y, targets) },
            MenuItem(L("Move to box ▸", "Déplacer vers une boîte ▸")) { openMoveMenu(x, y, targets) },
        )
        if (targets.size == 1) {
            if (e.place.inParty) items += MenuItem(L("Deposit in the PC", "Déposer dans le PC")) { depositInto(listOf(e), 0) }
            else items += MenuItem(L("Add to the party", "Ajouter à l'équipe")) { selection.clear(); selection.add(e.uuid); moveSelectionToParty() }
            items += MenuItem(L("Manage moves…", "Gérer les capacités…")) { dialog = MovesDialog(this, e) }
            items += MenuItem(L("Wiki page", "Fiche wiki")) { client?.setScreen(PokemonDetailScreen(e.pokemon.species, this)) }
            if (selection.size == 1 && e.uuid !in selection) selected().firstOrNull()?.let { other ->
                items += MenuItem(L("Compare with ", "Comparer avec ") + other.displayName) { dialog = CompareDialog(this, other, e) }
            }
        }
        items += MenuItem(L("Select this species", "Sélectionner cette espèce"), separatorBefore = true) {
            if (!hasControlDown()) selection.clear()
            results.filter { it.lineKey == e.lineKey }.forEach { selection.add(it.uuid) }
        }
        items += MenuItem(L("Release…", "Relâcher…"), color = UiKit.DANGER, separatorBefore = true) { openRelease(targets) }
        menu = PcMenu(x, y, items, title = if (targets.size == 1) e.displayName else L("${targets.size} Pokémon", "${targets.size} Pokémon"))
    }

    // ── drop handling ──

    private fun dropHint(mx: Double, my: Double): String? {
        val list = draggedEntries()
        teams.dropHint(list, mx, my)?.let { return it }
        partyAt(mx, my)?.let { return L("to the party", "vers l'équipe") }
        boxHeaderAt(mx, my)?.let { return L("into ", "dans ") + boxName(it) }
        if (view == "boxes") cellAt(mx, my)?.let { p ->
            if (list.size > 1) return L("into ", "dans ") + boxName(p.box)
            return if (session.at(p) == null) L("move here", "déplacer ici") else L("swap", "échanger")
        }
        if (view == "details") cardAt(mx, my)?.let { t ->
            if (list.size > 1) return if (t.place.inParty) L("to the party", "vers l'équipe") else L("into ", "dans ") + boxName(t.place.box)
            return L("swap", "échanger")
        }
        return null
    }

    private fun drop(mx: Double, my: Double) {
        val list = draggedEntries()
        if (list.isEmpty()) return
        if (teams.drop(list, mx, my)) return
        val party = partyAt(mx, my)
        if (party != null) {
            val single = list.singleOrNull()
            if (single != null) { moveOne(single, PcPlace.party(party)); return }
            selection.clear(); list.forEach { selection.add(it.uuid) }; moveSelectionToParty(); return
        }
        boxHeaderAt(mx, my)?.let { depositInto(list, it); return }
        if (view == "boxes") cellAt(mx, my)?.let { p ->
            if (list.size > 1) depositInto(list, p.box) else moveOne(list[0], p)
            return
        }
        if (view == "details") cardAt(mx, my)?.let { t ->
            if (list.size == 1) moveOne(list[0], t.place)
            else if (t.place.inParty) { selection.clear(); list.forEach { selection.add(it.uuid) }; moveSelectionToParty() }
            else depositInto(list, t.place.box)
            return
        }
    }

    /**
     * Emptying the party is allowed, unless the server turned on Cobblemon's
     * `preventCompletePartyDeposit` (synced to the client): it would then silently ignore the
     * last deposit, so say it here rather than wait for the step to time out.
     */
    private fun emptyPartyRefused(): Boolean {
        if (!com.cobblemon.mod.common.client.settings.ServerSettings.preventCompletePartyDeposit) return false
        notify(L("This server does not allow emptying the party.", "Ce serveur interdit de vider complètement l'équipe."), UiKit.DANGER)
        return true
    }

    /**
     * A double click: a PC Pokémon joins the party (first free place), a party member goes
     * back to the PC — into a free slot of its labelled box when it has one (tags first, as
     * arranging does), else the first free slot.
     */
    private fun toggleParty(e: PcEntry) {
        if (!e.place.inParty) {
            val free = (0 until 6).firstOrNull { session.party?.slots?.getOrNull(it) == null }
                ?: run { notify(L("The party is full: drag onto a party member to swap.", "Équipe pleine : glissez sur un membre de l'équipe pour échanger."), UiKit.GOLD); return }
            ops.start(PcOps.Kind.MOVE, listOf(PcStep.Move(e.uuid, e.place, PcPlace.party(free))))
            return
        }
        if (session.partyCount() <= 1 && emptyPartyRefused()) return
        val labelled = BoxRules.boxesFor(e, prefs, session.boxCount)
        val dest = labelled.asSequence().flatMap { b -> (0 until session.boxSize(b)).asSequence().map { PcPlace(b, it) } }
            .firstOrNull { session.at(it) == null } ?: PcOps.freeSlots(session, 0).firstOrNull()
            ?: run { notify(L("No free slot in the PC.", "Aucune place libre dans le PC."), UiKit.DANGER); return }
        ops.start(PcOps.Kind.MOVE, listOf(PcStep.Move(e.uuid, e.place, dest)))
        if (labelled.isNotEmpty() && dest.box in labelled) notify(L("Back to ", "Rangé dans ") + boxName(dest.box))
    }

    private fun moveOne(e: PcEntry, to: PcPlace) {
        if (e.place == to) return
        val other = session.at(to)
        if (other == null) {
            if (e.place.inParty && !to.inParty && session.partyCount() <= 1 && emptyPartyRefused()) return
            // a party slot is filled from the left: Cobblemon packs the party anyway
            ops.start(PcOps.Kind.MOVE, listOf(PcStep.Move(e.uuid, e.place, to)))
        } else {
            ops.start(PcOps.Kind.MOVE, listOf(PcStep.Swap(e.uuid, e.place, other.uuid, to)))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // input
    // ─────────────────────────────────────────────────────────────────────────

    private fun entryAt(mx: Double, my: Double): PcEntry? {
        partyAt(mx, my)?.let { i -> return session.party?.slots?.getOrNull(i)?.let { byId[it.uuid] } }
        if (view == "details") return cardAt(mx, my)
        return cellAt(mx, my)?.let { p -> session.at(p)?.let { byId[it.uuid] } }
    }

    /** Pokémon in display order, for Shift ranges. */
    private fun displayOrder(): List<PcEntry> =
        if (view == "details") drows.flatMap { it.cards }
        else entries.sortedWith(compareBy({ if (it.place.inParty) -1 else it.place.box }, { it.place.slot }))
            .filter { it.uuid in resultIds || prefs.dim }

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (showProgress) {
            val (bx, by, bw, bh) = progressButton
            if (mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh) ops.stop()
            return true
        }
        dialog?.let { d -> if (!d.click(mouseX, mouseY, button) && !insideDialog(d, mouseX, mouseY)) closeDialog(); return true }
        menu?.let { m -> menu = null; if (m.click(mouseX, mouseY)) return true; return true }

        if (search.isMouseOver(mouseX, mouseY)) { search.isFocused = true; focused = search; return super.mouseClickedScaled(mouseX, mouseY, button) }
        search.isFocused = false
        focused = null
        if (teams.press(mouseX, mouseY, button)) return true

        val e = entryAt(mouseX, mouseY)
        if (button == 1) {
            if (e != null) { openContextMenu(e, mouseX.toInt(), mouseY.toInt()); return true }
            hits.lastOrNull { it.contains(mouseX, mouseY) && it.right != null }?.let { it.right!!.invoke(mouseX, mouseY); return true }
            return true
        }
        if (button != 0) return true

        // scrollbar of the main area
        if (mouseX >= cx + cw - 5 && mouseX < cx + cw + 1 && mouseY >= cTop && mouseY < cBottom && contentH > cBottom - cTop) {
            dragScrollbar = true; scrollTo(mouseY); return true
        }

        if (e != null) {
            val now = System.currentTimeMillis()
            // double click: into the party, or out of it into its box (the wiki page stays in the menu)
            if (lastClickId == e.uuid && now - lastClick < 300) {
                lastClickId = null
                if (!ops.running) toggleParty(e)
                return true
            }
            lastClick = now; lastClickId = e.uuid
            focus = e.uuid
            pressEntry = e; pressX = mouseX; pressY = mouseY; pressDeferredSelect = false
            when {
                hasShiftDown() && anchor != null -> {
                    val order = displayOrder()
                    val a = order.indexOfFirst { it.uuid == anchor }; val b = order.indexOfFirst { it.uuid == e.uuid }
                    if (a >= 0 && b >= 0) { if (!hasControlDown()) selection.clear(); order.subList(minOf(a, b), maxOf(a, b) + 1).forEach { selection.add(it.uuid) } }
                }
                hasControlDown() -> { if (!selection.remove(e.uuid)) selection.add(e.uuid); anchor = e.uuid }
                e.uuid in selection -> { pressDeferredSelect = true; anchor = e.uuid }
                else -> { selection.clear(); selection.add(e.uuid); anchor = e.uuid }
            }
            return true
        }

        hits.lastOrNull { it.contains(mouseX, mouseY) }?.let { it.action(); return true }

        // empty space in the PC area: start a band selection
        if (mouseX >= cx && mouseX < cx + cw - 5 && mouseY >= cTop && mouseY < cBottom) {
            pressEntry = null; pressX = mouseX; pressY = mouseY; banding = false
            bandArmed = true
            return true
        }
        return super.mouseClickedScaled(mouseX, mouseY, button)
    }
    private var bandArmed = false

    private fun insideDialog(d: PcDialog, mx: Double, my: Double) = mx >= d.x && mx < d.x + d.w && my >= d.y && my < d.y + d.h

    internal fun closeDialog() { dialog?.closed(); dialog = null }

    private fun scrollTo(my: Double) {
        val area = cBottom - cTop
        val maxScroll = maxOf(0, contentH - area)
        scroll = (((my - cTop) / area) * contentH - area / 2).toInt().coerceIn(0, maxScroll)
    }

    override fun mouseDraggedScaled(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (dialog != null || showProgress) return true
        if (dragScrollbar) { scrollTo(mouseY); return true }
        if (teams.drag(mouseX, mouseY)) return true
        val moved = kotlin.math.abs(mouseX - pressX) + kotlin.math.abs(mouseY - pressY) > 4
        if (pressEntry != null && moved && button == 0) { dragging = true; pressDeferredSelect = false }
        if (bandArmed && moved) banding = true
        // auto-scroll near the edges while dragging or banding
        if ((dragging || banding) && mouseX >= cx && mouseX < cx + cw) {
            if (mouseY < cTop + 8) scroll = (scroll - 6).coerceAtLeast(0)
            else if (mouseY > cBottom - 8) scroll = (scroll + 6).coerceAtMost(maxOf(0, contentH - (cBottom - cTop)))
        }
        return true
    }

    override fun mouseReleasedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        dragScrollbar = false
        if (teams.release(mouseX, mouseY)) { banding = false; bandArmed = false; pressEntry = null; return true }
        if (dragging) {
            dragging = false
            drop(mouseX, mouseY)
        } else if (pressDeferredSelect) {
            pressEntry?.let { selection.clear(); selection.add(it.uuid) }
        } else if (banding) {
            bandSelect(mouseX, mouseY)
        } else if (bandArmed && !hasControlDown() && !hasShiftDown()) {
            selection.clear(); if (onlySelected) refilter()
        }
        banding = false; bandArmed = false; pressEntry = null; pressDeferredSelect = false
        return super.mouseReleasedScaled(mouseX, mouseY, button)
    }

    private fun bandSelect(mx: Double, my: Double) {
        val x1 = minOf(pressX, mx); val x2 = maxOf(pressX, mx)
        val y1 = minOf(pressY, my); val y2 = maxOf(pressY, my)
        if (!hasControlDown() && !hasShiftDown()) selection.clear()
        if (view == "details") {
            for (r in drows) {
                if (r.header) continue
                val cy = cTop + r.y - scroll + CARD_H / 2
                r.cards.forEachIndexed { i, e ->
                    val cxm = cardX(i) + cardW / 2
                    if (cy >= y1 && cy <= y2 && cxm >= x1 && cxm <= x2 && e.uuid in resultIds) selection.add(e.uuid)
                }
            }
        } else {
            for (b in 0 until session.boxCount) {
                val tx = tileX(b); val ty = tileY(b)
                session.pc.boxes[b].slots.forEachIndexed { s, p ->
                    if (p == null || p.uuid !in resultIds) return@forEachIndexed
                    val px = tx + 3 + (s % 6) * cell + cell / 2
                    val py = ty + HEADER_H + 1 + (s / 6) * cell + cell / 2
                    if (px >= x1 && px <= x2 && py >= y1 && py <= y2) selection.add(p.uuid)
                }
            }
        }
        if (onlySelected) refilter()
    }

    override fun mouseScrolledScaled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (showProgress) return true
        dialog?.let { it.scroll(mouseX, mouseY, verticalAmount); return true }
        menu?.let { it.scroll(verticalAmount); return true }
        if (mouseX < lx + lw + 6 && mouseY >= teamsTop - 4) { teams.scroll(verticalAmount); return true }
        if (mouseX < lx + lw + 6 && mouseY > filtersTop) {
            filterScroll = (filterScroll - (verticalAmount * 15).toInt()).coerceIn(0, filterMaxScroll)
            return true
        }
        scroll = (scroll - (verticalAmount * (if (view == "details") CARD_H / 2 else 24)).toInt()).coerceIn(0, maxOf(0, contentH - (cBottom - cTop)))
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (showProgress) return true
        dialog?.let { d ->
            if (d.key(keyCode, modifiers)) return true
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) closeDialog()
            return true
        }
        if (menu != null) { if (keyCode == GLFW.GLFW_KEY_ESCAPE) menu = null; return true }
        if (teams.key(keyCode, scanCode, modifiers)) return true
        if (search.isFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) { search.isFocused = false; focused = null; return true }
            return search.keyPressed(keyCode, scanCode, modifiers)
        }
        val ctrl = Screen.hasControlDown()
        if (client?.options?.inventoryKey?.matchesKey(keyCode, scanCode) == true) { close(); return true }
        when {
            keyCode == GLFW.GLFW_KEY_ESCAPE -> {
                if (selection.isNotEmpty()) { selection.clear(); if (onlySelected) refilter() } else close()
                return true
            }
            ctrl && keyCode == GLFW.GLFW_KEY_A -> { selectAllResults(); return true }
            ctrl && keyCode == GLFW.GLFW_KEY_F -> { search.isFocused = true; focused = search; return true }
            keyCode == GLFW.GLFW_KEY_DELETE -> { openRelease(selected()); return true }
            keyCode == GLFW.GLFW_KEY_TAB -> { view = if (view == "details") "boxes" else "details"; scroll = 0; return true }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        dialog?.let { it.char(chr); return true }
        if (teams.char(chr, modifiers)) return true
        if (search.isFocused) return search.charTyped(chr, modifiers)
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // controller (Controlify, through com.hunterboard.controller): where the cursor can rest,
    // the D-pad across the grid, LB/RB from box to box, the selection moved where the cursor
    // is. Everything in this screen's own pixels; nothing here knows Controlify.
    // ─────────────────────────────────────────────────────────────────────────

    /** The controller's buttons, shown in the footer in place of the mouse and keyboard ones; null with a mouse. */
    internal var controllerHint: Text? = null

    /**
     * Every place the controller's cursor can rest, as (x, y, w, h): the open window's buttons
     * or the open menu's lines when there is one; otherwise the party, the PC's cells (empty
     * ones too: places to move to) or cards, the search field and every button.
     */
    internal fun controllerTargets(): List<IntArray> {
        if (showProgress) return listOf(progressButton.copyOf())
        dialog?.let { return it.targets.toList() }
        menu?.let { return it.rows() }
        val out = ArrayList<IntArray>()
        for (i in 0 until 6) out += intArrayOf(partyCellX(i), partyY, PARTY_CELL, PARTY_CELL)
        if (::search.isInitialized && search.visible) out += intArrayOf(search.x, search.y, search.width, search.height)
        if (view == "details") {
            cardRows().forEachIndexed { r, row -> row.cards.indices.forEach { c -> gridRect(c, r)?.let { if (shownInContent(it)) out += it } } }
        } else {
            for (b in 0 until session.boxCount) for (s in 0 until session.boxSize(b)) cellRect(b, s).let { if (shownInContent(it)) out += it }
        }
        out += teams.targets()
        hits.forEach { out += intArrayOf(it.x, it.y, it.w, it.h) }
        return out
    }

    private fun shownInContent(r: IntArray) = r[1] + r[3] / 2 in cTop until cBottom

    private fun cardRows() = drows.filter { !it.header }

    private fun cellRect(b: Int, s: Int) =
        intArrayOf(tileX(b) + 3 + (s % 6) * cell, tileY(b) + HEADER_H + 1 + (s / 6) * cell, cell - 1, cell - 1)

    /**
     * The PC as one grid: in the box view, the boxes' 6x5 cells side by side (a box's last
     * column is next to the first column of the box on its right); in the detailed view,
     * the rows of cards. (column, row) → its rectangle, or null past the edges.
     */
    private fun gridRect(c: Int, r: Int): IntArray? {
        if (c < 0 || r < 0) return null
        if (view == "details") {
            val row = cardRows().getOrNull(r) ?: return null
            if (c >= row.cards.size) return null
            return intArrayOf(cardX(c), cTop + row.y - scroll, cardW, CARD_H)
        }
        if (c >= perRow * 6) return null
        val b = (r / 5) * perRow + c / 6
        val s = (r % 5) * 6 + c % 6
        if (b >= session.boxCount || s >= session.boxSize(b)) return null
        return cellRect(b, s)
    }

    /** The grid position under (x, y), or null off the grid. */
    private fun gridAt(mx: Double, my: Double): Pair<Int, Int>? {
        if (view == "details") {
            if (my < cTop || my >= cBottom) return null
            cardRows().forEachIndexed { r, row ->
                val y = cTop + row.y - scroll
                if (my >= y && my < y + CARD_H) row.cards.indices.forEach { c -> if (mx >= cardX(c) && mx < cardX(c) + cardW) return c to r }
            }
            return null
        }
        val p = cellAt(mx, my) ?: return null
        return (p.box % perRow) * 6 + p.slot % 6 to (p.box / perRow) * 5 + p.slot / 6
    }

    /** Scrolls the content area just enough for [r] to be in it. */
    private fun reveal(r: IntArray) {
        val maxScroll = maxOf(0, contentH - (cBottom - cTop))
        if (r[1] < cTop) scroll = (scroll - (cTop - r[1])).coerceIn(0, maxScroll)
        else if (r[1] + r[3] > cBottom) scroll = (scroll + (r[1] + r[3] - cBottom)).coerceIn(0, maxScroll)
    }

    private fun blockedForController() = dialog != null || menu != null || showProgress

    private fun center(r: IntArray) = intArrayOf(r[0] + r[2] / 2, r[1] + r[3] / 2)

    /**
     * The D-pad from the cell or card under (x, y): the next one that way, scrolled into view;
     * its centre, or null when the cursor is not on the grid or the grid ends there (the
     * cursor then leaves it for the buttons around, as Controlify would have it).
     */
    internal fun controllerStep(mx: Double, my: Double, dx: Int, dy: Int): IntArray? {
        if (blockedForController()) return null
        val (c, r) = gridAt(mx, my) ?: return null
        var nc = c + dx
        val nr = r + dy
        // the detailed view's last row can be shorter: going down lands on its last card
        if (view == "details" && dx == 0) cardRows().getOrNull(nr)?.let { nc = minOf(nc, it.cards.size - 1) }
        val target = gridRect(nc, nr) ?: return null
        reveal(target)
        return center(gridRect(nc, nr) ?: return null)
    }

    /** True when (x, y) is on the party row. */
    internal fun controllerOnParty(mx: Double, my: Double) = partyAt(mx, my) != null

    /** Centre of the party's cell [i]. */
    internal fun controllerPartyCell(i: Int) = center(intArrayOf(partyCellX(i), partyY, PARTY_CELL, PARTY_CELL))

    /** The cell or card under (x, y) when it is one, to come back to from the party. */
    internal fun controllerOnGrid(mx: Double, my: Double) = gridAt(mx, my) != null

    /**
     * LB/RB: the box after ([dir] > 0) or before the one under (x, y) (the first one shown
     * when there is none), brought into view; the centre of the same cell in it, else of its
     * first cell or card. In the detailed view sorted by anything but place, a page instead.
     */
    internal fun controllerBoxStep(mx: Double, my: Double, dir: Int): IntArray? {
        if (blockedForController() || session.boxCount == 0) return null
        val maxScroll = maxOf(0, contentH - (cBottom - cTop))
        if (view == "details") {
            val heads = drows.filter { it.header && it.box >= 0 }
            if (heads.isEmpty()) {
                scroll = (scroll + dir * (cBottom - cTop - CARD_H)).coerceIn(0, maxScroll)
                val rows = cardRows()
                val r = rows.indexOfFirst { cTop + it.y - scroll >= cTop }.takeIf { it >= 0 } ?: return null
                return gridRect(0, r)?.let { center(it) }
            }
            val here = cardAt(mx, my)?.place?.box ?: detailsHeaderAt(mx, my)
                ?: heads.lastOrNull { cTop + it.y - scroll <= cTop + 2 }?.box ?: heads.first().box
            val i = heads.indexOfFirst { it.box == here }.coerceAtLeast(0)
            val head = heads[Math.floorMod(i + dir, heads.size)]
            scroll = head.y.coerceIn(0, maxScroll)
            val r = cardRows().indexOfFirst { it.box == head.box }.takeIf { it >= 0 } ?: return null
            return gridRect(0, r)?.let { center(it) }
        }
        val here = cellAt(mx, my)
        val b = here?.box ?: boxHeaderAt(mx, my)
            ?: (0 until session.boxCount).firstOrNull { tileY(it) + tileH > cTop } ?: 0
        val nb = Math.floorMod(b + dir, session.boxCount)
        val s = (here?.slot ?: 0).coerceAtMost(session.boxSize(nb) - 1)
        // the whole box in view when it is not already
        if (tileY(nb) < cTop || tileY(nb) + tileH > cBottom) scroll = (tileY(nb) + scroll - cTop).coerceIn(0, maxScroll)
        return center(cellRect(nb, s))
    }

    /**
     * RT: the selection goes where the cursor is, as if dragged there: one Pokémon onto a cell
     * (moved, or swapped with the one there), several into the box under the cursor or into
     * the party. False when there is nothing to move or nowhere to put it.
     */
    internal fun controllerDrop(mx: Double, my: Double): Boolean {
        if (blockedForController() || ops.running) return false
        val first = selected().firstOrNull() ?: return false
        pressEntry = first
        val ok = dropHint(mx, my) != null
        if (ok) drop(mx, my)
        pressEntry = null
        return ok
    }

    /** What RT would do under (x, y), for the footer; null when nothing. */
    internal fun controllerDropHint(mx: Double, my: Double): String? {
        if (blockedForController() || selection.isEmpty()) return null
        val first = selected().firstOrNull() ?: return null
        val before = pressEntry
        pressEntry = first
        val hint = dropHint(mx, my)
        pressEntry = before
        return hint
    }
}
