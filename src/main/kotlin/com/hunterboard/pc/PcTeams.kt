package com.hunterboard.pc

import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.hunterboard.UiKit
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import java.util.UUID

/**
 * Saved teams, always in view at the bottom of the PC's left column. A team is six places:
 * Pokémon are dragged into them from the PC or the party, a team is renamed in place and
 * equipped in one click, and a click on a member opens the moves it will have in that team.
 *
 * Equipping puts those Pokémon in the party (the ones they replace go back to the PC, into
 * their labelled box when they have one) and gives each its moves, through the same server
 * requests as moving by hand and as the moves window, one at a time and each confirmed.
 */
internal class TeamPanel(private val s: HunterPcScreen) {
    companion object {
        const val HEAD = 13
        const val CARD_H = 39
        const val PITCH = 42
        const val SLOT = 22
        const val SLOT_STEP = 25
        /** The least the column leaves the panel: its title and a card and a half. */
        const val MIN_H = HEAD + PITCH + PITCH / 2

        fun member(e: PcEntry) = PcTeamMember(e.uuid.toString(), runCatching { e.pokemon.moveSet.getMoves().map { it.name } }.getOrDefault(emptyList()))
    }

    private var x = 0; private var top = 0; private var w = 0; private var bottom = 0
    private var scroll = 0
    private val listTop get() = top + HEAD
    private val teams: List<PcTeam> get() = s.prefs.teams()
    private fun cardY(i: Int) = listTop + i * PITCH - scroll
    private fun slotX(k: Int) = x + k * SLOT_STEP
    private fun slotY(i: Int) = cardY(i) + 15
    private fun maxScroll() = maxOf(0, teams.size * PITCH - 3 - (bottom - listTop))

    /** Renaming in place: the team whose name is being typed, -1 when none. */
    private var renaming = -1
    private val field = TextFieldWidget(s.tr, 0, 0, 100, 10, Text.literal("team")).apply { setMaxLength(32); setDrawsBackground(false) }

    // a member pressed: a click opens its moves, a drag moves it to another place or out of the team
    private var press: IntArray? = null
    private var pressX = 0.0; private var pressY = 0.0
    var dragging = false; private set
    private var lastNameClick = 0L
    private var lastNameTeam = -1

    fun place(x: Int, top: Int, w: Int, bottom: Int) {
        this.x = x; this.top = top; this.w = w; this.bottom = bottom
        scroll = scroll.coerceIn(0, maxScroll())
    }

    /** True over the panel (title included). */
    fun contains(mx: Double, my: Double) = mx >= x - 2 && mx < x + w + 5 && my >= top - 3 && my < bottom

    private fun inside(mx: Number, my: Number, bx: Int, by: Int, bw: Int, bh: Int) =
        mx.toDouble() >= bx && mx.toDouble() < bx + bw && my.toDouble() >= by && my.toDouble() < by + bh

    /** (team, place) under the cursor. */
    fun slotAt(mx: Double, my: Double): Pair<Int, Int>? {
        if (my < listTop || my >= bottom) return null
        for (i in teams.indices) {
            val y = slotY(i)
            if (my < y || my >= y + SLOT) continue
            for (k in 0 until 6) if (mx >= slotX(k) && mx < slotX(k) + SLOT) return i to k
        }
        return null
    }

    private fun cardAt(mx: Double, my: Double): Int? {
        if (my < listTop || my >= bottom || mx < x - 2 || mx >= x + w + 1) return null
        return teams.indices.firstOrNull { my >= cardY(it) && my < cardY(it) + CARD_H }
    }

    // ── state of a team ──

    /** True when the team gives [m] other moves than the ones it knows now. */
    private fun movesDiffer(m: PcTeamMember): Boolean {
        val wanted = m.moves.filter { it.isNotBlank() }.distinct().take(4)
        if (wanted.isEmpty()) return false
        val e = s.entryByKey(m.uuid) ?: return false
        val now = runCatching { e.pokemon.moveSet.getMoves().map { it.name } }.getOrDefault(emptyList())
        return now != wanted
    }

    /** The party is this team, in its order, with its moves. */
    fun equipped(t: PcTeam): Boolean {
        val want = t.filled().map { it.uuid }
        if (want.isEmpty()) return false
        val party = s.session.party?.slots?.filterNotNull()?.map { it.uuid.toString() } ?: return false
        return party == want && t.filled().none { movesDiffer(it) }
    }

    // ── drawing ──

    fun draw(ctx: DrawContext, mx: Int, my: Int, delta: Float, pcDrag: Boolean) {
        val tr = s.tr
        val drag = pcDrag || dragging
        UiKit.dots(ctx, x, top - 5, w)
        UiKit.sectionTitle(ctx, tr, L("My teams", "Mes équipes"), x, top)
        if (!drag) {
            val more = 11
            val label = L("+ New", "+ Nouvelle")
            val bw = tr.getWidth(label) + 8
            val bx = x + w - more - 1 - bw
            val by = top - 2
            val hb = inside(mx, my, bx, by, bw, 11)
            UiKit.button(ctx, tr, bx, by, bw, 11, label, hb)
            s.hit(bx, by, bw, 11) { create(PcTeam(freshName(), emptyList()), rename = true) }
            if (hb) s.tip.set(mx, my, L("New team", "Nouvelle équipe") to UiKit.TEXT,
                L("Six empty places: drag Pokémon from the PC or the party into them.", "Six places vides : glissez-y des Pokémon du PC ou de l'équipe.") to UiKit.TEXT_FAINT)
            val hm = inside(mx, my, bx + bw + 1, by, more, 11)
            UiKit.button(ctx, tr, bx + bw + 1, by, more, 11, "▾", hm)
            s.hit(bx + bw + 1, by, more, 11) { openNewMenu(bx, by + 12) }
            if (hm) s.tip.set(mx, my, L("New team from…", "Nouvelle équipe depuis…") to UiKit.TEXT)
        }
        val list = teams
        val lmx = if (my in listTop until bottom) mx else -9999
        val first = s.hitCount
        s.clip(ctx, x - 2, listTop, x + w + 2, bottom)
        for (i in list.indices) {
            val y = cardY(i)
            if (y + CARD_H < listTop || y > bottom) continue
            drawCard(ctx, i, list[i], y, lmx, my, delta, drag)
        }
        if (list.isEmpty()) {
            val lines = tr.wrapLines(Text.literal(L(
                "No team yet. \"+ New\" makes one with six empty places: drag Pokémon from the PC or the party into them, then click one to choose its moves for that team.",
                "Aucune équipe. « + Nouvelle » en crée une de six places vides : glissez-y des Pokémon du PC ou de l'équipe, puis cliquez-en un pour choisir ses capacités dans cette équipe.")), w)
            lines.forEachIndexed { n, l -> ctx.drawText(tr, l, x, listTop + 3 + n * 10, UiKit.TEXT_FAINT, false) }
        }
        ctx.disableScissor()
        s.dropHits(first, listTop, bottom)
        UiKit.scrollbar(ctx, x + w + 2, listTop, bottom, list.size * PITCH, scroll)
    }

    private fun drawCard(ctx: DrawContext, i: Int, t: PcTeam, y: Int, mx: Int, my: Int, delta: Float, drag: Boolean) {
        val tr = s.tr
        val target = drag && inside(mx, my, x - 2, y, w + 3, CARD_H)
        ctx.fill(x - 2, y, x + w + 1, y + CARD_H, if (target) UiKit.withAlpha(UiKit.accent(), 0x28) else 0x12FFFFFF)
        UiKit.border(ctx, x - 2, y, w + 3, CARD_H, if (target) UiKit.withAlpha(UiKit.accent(), 0xC0) else UiKit.BORDER_DIM)
        val on = equipped(t)
        // the name row: right click for the team's menu, double click to rename
        s.hit(x - 2, y, w + 3, 14, right = { px, py -> openTeamMenu(i, px.toInt(), py.toInt()) }) { nameClick(i) }
        val menuX = x + w - 11
        val ny = y + 2
        var nameRight = menuX - 3
        if (!drag) {
            val hm = inside(mx, my, menuX, ny, 11, 11)
            UiKit.button(ctx, tr, menuX, ny, 11, 11, "▾", hm)
            s.hit(menuX, ny, 11, 11) { openTeamMenu(i, menuX, ny + 12) }
            if (hm) s.tip.set(mx, my, L("Team menu", "Menu de l'équipe") to UiKit.TEXT)
            val eq = if (on) L("Equipped", "Équipée") else L("Equip", "Équiper")
            val ew = tr.getWidth(eq) + 8
            val ex = menuX - 2 - ew
            val he = inside(mx, my, ex, ny, ew, 11)
            if (t.filled().isEmpty() || on) {
                // nothing to do: a label, not a button (equipped: the name is green too)
                UiKit.slot(ctx, ex, ny, ew, 11)
                ctx.drawText(tr, eq, ex + 4, ny + 2, if (on) 0xFF8BE36A.toInt() else UiKit.TEXT_FAINT, false)
                if (on) s.hit(ex, ny, ew, 11) { s.equipTeam(i) }
            } else {
                UiKit.button(ctx, tr, ex, ny, ew, 11, eq, he, active = true)
                s.hit(ex, ny, ew, 11) { s.equipTeam(i) }
            }
            if (he) s.tip.set(mx, my, *equipTip(t, on))
            nameRight = ex - 3
        }
        if (renaming == i) {
            UiKit.slot(ctx, x - 1, y + 1, nameRight - x + 1, 12)
            field.x = x + 2; field.y = y + 3; field.width = nameRight - x - 4
            field.render(ctx, mx, my, delta)
        } else {
            val name = fit(tr, t.name, nameRight - x - 12)
            ctx.drawText(tr, name, x, y + 3, if (on) 0xFF8BE36A.toInt() else UiKit.TEXT, false)
            if (!drag) {
                val px = x + tr.getWidth(name) + 4
                val hp = inside(mx, my, px - 2, y + 1, 11, 12)
                s.pencil(ctx, px, y + 3, if (hp) UiKit.accentBright() else UiKit.TEXT_FAINT)
                s.hit(px - 2, y + 1, 11, 12) { startRename(i) }
                if (hp) s.tip.set(mx, my, L("Rename (or double click the name)", "Renommer (ou double-clic sur le nom)") to UiKit.TEXT)
            }
        }
        val slots = t.slots()
        for (k in 0 until 6) drawSlot(ctx, i, k, slots[k], slotX(k), y + 15, mx, my, delta, drag)
    }

    private fun drawSlot(ctx: DrawContext, i: Int, k: Int, m: PcTeamMember?, sx: Int, sy: Int, mx: Int, my: Int, delta: Float, drag: Boolean) {
        val tr = s.tr
        val hov = inside(mx, my, sx, sy, SLOT, SLOT)
        UiKit.slot(ctx, sx, sy, SLOT, SLOT)
        val lifted = dragging && press?.let { it[0] == i && it[1] == k } == true
        if (m == null || lifted) {
            if (hov && drag) UiKit.border(ctx, sx, sy, SLOT, SLOT, UiKit.accentBright())
            else ctx.drawText(tr, "+", sx + (SLOT - tr.getWidth("+")) / 2 + 1, sy + 7, 0x40FFFFFF, false)
            if (hov && !drag) s.tip.set(mx, my, L("Free place", "Place libre") to UiKit.TEXT,
                L("Drag a Pokémon from the PC or the party here.", "Glissez ici un Pokémon du PC ou de l'équipe.") to UiKit.TEXT_FAINT)
            return
        }
        val e = s.entryByKey(m.uuid)
        if (e == null) {
            ctx.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x40E05050)
            ctx.drawText(tr, "?", sx + (SLOT - tr.getWidth("?")) / 2 + 1, sy + 7, UiKit.DANGER, false)
            if (hov && !drag) s.tip.set(mx, my, L("Missing", "Absent") to UiKit.DANGER,
                L("No longer in this PC or the party (released or traded). Right click to remove it.",
                  "N'est plus dans ce PC ni dans l'équipe (relâché ou échangé). Clic droit pour le retirer.") to UiKit.TEXT_FAINT)
        } else {
            s.smallPortrait(ctx, e, sx + 1, sy + 1, SLOT - 2, delta)
            ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 150f)
            if (e.shiny) ctx.fill(sx + 1, sy + 1, sx + 4, sy + 4, SHINY_COLOR)
            if (movesDiffer(m)) ctx.fill(sx + SLOT - 4, sy + 1, sx + SLOT - 1, sy + 4, UiKit.GOLD)
            // its Showdown set asked for other EVs: a warning in the bottom corner
            if (PcShowdown.evsDiffer(m.evs, e)) {
                ctx.fill(sx + SLOT - 6, sy + SLOT - 10, sx + SLOT - 1, sy + SLOT - 1, 0xC0000000.toInt())
                ctx.drawText(tr, "!", sx + SLOT - 4, sy + SLOT - 9, EV_WARN, false)
            }
            ctx.matrices.pop()
            if (hov && !drag) { s.hoverEntry(e); s.tip.set(mx, my, *memberTip(m, e)) }
        }
        if (hov) {
            ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 150f)
            UiKit.border(ctx, sx, sy, SLOT, SLOT, if (drag) UiKit.accentBright() else 0xC0FFFFFF.toInt())
            ctx.matrices.pop()
        }
    }

    private fun equipTip(t: PcTeam, on: Boolean): Array<Pair<String, Int>> =
        if (on) arrayOf(L("Equipped", "Équipée") to 0xFF8BE36A.toInt(),
            L("These Pokémon are in the party, in this order, with these moves.", "Ces Pokémon sont dans l'équipe, dans cet ordre, avec ces capacités.") to UiKit.TEXT_FAINT)
        else arrayOf(L("Equip this team", "Équiper cette équipe") to UiKit.TEXT,
            L("Its Pokémon go to the party in this order, with their moves for this team. The ones they replace go back to the PC, into their labelled box when they have one.",
              "Ses Pokémon passent dans l'équipe dans cet ordre, avec leurs capacités pour cette équipe. Ceux qu'ils remplacent retournent au PC, dans leur boîte étiquetée s'ils en ont une.") to UiKit.TEXT_FAINT)

    private fun memberTip(m: PcTeamMember, e: PcEntry): Array<Pair<String, Int>> {
        val out = mutableListOf((e.displayName + "  " + L("Lv ", "Nv ") + e.level) to (if (e.shiny) SHINY_COLOR else 0xFFFFFFFF.toInt()))
        val moves = m.moves.filter { it.isNotBlank() }
        if (moves.isEmpty()) out += L("Keeps the moves it knows.", "Garde les capacités qu'il connaît.") to UiKit.TEXT_FAINT
        else {
            out += L("Its moves in this team:", "Ses capacités dans cette équipe :") to UiKit.TEXT_MUTED
            for (n in moves) {
                val t = Moves.getByName(n)
                out += ("  " + (t?.displayName?.string ?: n)) to (t?.let { MoveTiles.color(it.elementalType) } ?: UiKit.TEXT)
            }
            if (movesDiffer(m)) out += L("Not the ones it knows now: they change when the team is equipped.",
                "Ce ne sont pas celles qu'il connaît : elles changent quand l'équipe est équipée.") to UiKit.GOLD
        }
        if (PcShowdown.evsDiffer(m.evs, e)) out += ("⚠ " + PcShowdown.evWarning(m.evs, e)) to EV_WARN
        out += L("Click: its moves · Drag: to another place, or out to remove · Right click: menu",
            "Clic : ses capacités · Glisser : vers une autre place, ou dehors pour retirer · Clic droit : menu") to UiKit.TEXT_FAINT
        return out.toTypedArray()
    }

    /** The member being dragged, under the cursor, with what letting go there does. */
    fun drawGhost(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        if (!dragging) return
        val p = press ?: return
        val m = teams.getOrNull(p[0])?.slots()?.getOrNull(p[1]) ?: return
        val tr = s.tr
        ctx.matrices.push(); ctx.matrices.translate(0f, 0f, 400f)
        ctx.fill(mx - 11, my - 11, mx + 11, my + 11, 0x90101418.toInt())
        UiKit.border(ctx, mx - 11, my - 11, 22, 22, UiKit.accentBright())
        s.entryByKey(m.uuid)?.let { s.smallPortrait(ctx, it, mx - 10, my - 10, 20, delta) }
        ghostHint(mx.toDouble(), my.toDouble())?.let { (t, c) -> ctx.drawText(tr, t, mx + 12, my + 6, c, true) }
        ctx.matrices.pop()
    }

    private fun ghostHint(mx: Double, my: Double): Pair<String, Int>? {
        val p = press ?: return null
        slotAt(mx, my)?.let { (j, k) ->
            if (j == p[0]) return if (k == p[1]) null
                else (if (teams[j].slots()[k] == null) L("move here", "déplacer ici") else L("swap", "échanger")) to UiKit.TEXT_MUTED
            return (L("copy into ", "copier dans ") + teams[j].name) to UiKit.TEXT_MUTED
        }
        if (contains(mx, my)) return null
        return L("remove from the team", "retirer de l'équipe") to UiKit.DANGER
    }

    // ── mouse and keys (called by the screen before its own handling) ──

    /** A press: true when it was on a member or a free place (the panel's own). */
    fun press(mx: Double, my: Double, button: Int): Boolean {
        if (renaming >= 0) {
            if (field.isMouseOver(mx, my)) { field.mouseClicked(mx, my, button); return true }
            commitRename()
        }
        val (i, k) = slotAt(mx, my) ?: return false
        val t = teams.getOrNull(i) ?: return false
        val m = t.slots()[k]
        if (button == 1) {
            if (m != null) openMemberMenu(i, k, mx.toInt(), my.toInt()) else openTeamMenu(i, mx.toInt(), my.toInt())
            return true
        }
        if (button == 0 && m != null) { press = intArrayOf(i, k); pressX = mx; pressY = my }
        return true
    }

    fun drag(mx: Double, my: Double): Boolean {
        if (press == null) return false
        if (!dragging && kotlin.math.abs(mx - pressX) + kotlin.math.abs(my - pressY) > 4) dragging = true
        if (dragging && mx >= x && mx < x + w) {
            if (my < listTop + 6 && my >= listTop - 10) scroll = (scroll - 4).coerceAtLeast(0)
            else if (my > bottom - 6 && my < bottom + 10) scroll = (scroll + 4).coerceAtMost(maxScroll())
        }
        return true
    }

    fun release(mx: Double, my: Double): Boolean {
        val p = press ?: return false
        press = null
        if (!dragging) { editMoves(p[0], p[1]); return true }
        dragging = false
        dropMember(p[0], p[1], mx, my)
        return true
    }

    fun scroll(amount: Double) { scroll = (scroll - (amount * 20).toInt()).coerceIn(0, maxScroll()) }

    fun key(keyCode: Int, scanCode: Int, mods: Int): Boolean {
        if (renaming < 0) return false
        when (keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitRename()
            GLFW.GLFW_KEY_ESCAPE -> renaming = -1
            else -> field.keyPressed(keyCode, scanCode, mods)
        }
        return true
    }

    fun char(c: Char, mods: Int): Boolean {
        if (renaming < 0) return false
        field.charTyped(c, mods)
        return true
    }

    /** Where a controller's cursor can rest: every place shown. */
    fun targets(): List<IntArray> = teams.indices.flatMap { i -> (0 until 6).map { k -> intArrayOf(slotX(k), slotY(i), SLOT, SLOT) } }
        .filter { it[1] >= listTop && it[1] + SLOT <= bottom }

    // ── Pokémon dragged from the PC or the party ──

    /** The team and place under the cursor for a drop from the PC; the place is -1 over the rest of a card. */
    private fun dropTarget(mx: Double, my: Double): Pair<Int, Int>? = slotAt(mx, my) ?: cardAt(mx, my)?.let { it to -1 }

    fun dropHint(list: List<PcEntry>, mx: Double, my: Double): String? {
        val (i, k) = dropTarget(mx, my) ?: return null
        val t = teams.getOrNull(i) ?: return null
        val single = list.singleOrNull()
        if (single != null) {
            val here = t.slots().indexOfFirst { it?.uuid == single.uuid.toString() }
            if (here >= 0) return if (k < 0 || k == here) L("already in ", "déjà dans ") + t.name else L("move in ", "déplacer dans ") + t.name
            if (k >= 0 && t.slots()[k] != null) return L("replace in ", "remplacer dans ") + t.name
        }
        return L("into ", "dans ") + t.name
    }

    /** Puts dropped Pokémon in the team under the cursor; false when the drop was not on a team. */
    fun drop(list: List<PcEntry>, mx: Double, my: Double): Boolean {
        val (i, k) = dropTarget(mx, my) ?: return false
        add(list, i, k)
        return true
    }

    /**
     * [list] into team [i]: the first one at place [k] (replacing who is there), the others
     * in the free places after it; [k] = -1, all in the free places. A Pokémon already in the
     * team is not added twice; alone, it moves to the place it was dropped on.
     */
    fun add(list: List<PcEntry>, i: Int, k: Int) {
        val t = teams.getOrNull(i) ?: return
        val sl = t.slots().toMutableList()
        val order = if (k >= 0) listOf(k) + (k + 1 until 6) + (0 until k) else (0 until 6).toList()
        var added = 0
        var atTarget = k >= 0
        for (e in list) {
            val key = e.uuid.toString()
            val here = sl.indexOfFirst { it?.uuid == key }
            if (here >= 0) {
                if (list.size == 1 && k >= 0 && k != here) { val tmp = sl[k]; sl[k] = sl[here]; sl[here] = tmp; save(i, t.withSlots(sl)) }
                else if (list.size == 1) s.notify(e.displayName + L(" is already in ", " est déjà dans ") + t.name)
                if (list.size == 1) return
                continue
            }
            val dest = if (atTarget) k else order.firstOrNull { sl[it] == null } ?: break
            atTarget = false
            sl[dest] = member(e)
            added++
        }
        if (added == 0) { s.notify(t.name + L(" is full.", " est complète."), UiKit.GOLD); return }
        save(i, t.withSlots(sl))
        s.notify(if (added == 1) list.first { e -> sl.any { it?.uuid == e.uuid.toString() } }.displayName + L(" added to ", " ajouté à ") + t.name
                 else L("$added Pokémon added to ", "$added Pokémon ajoutés à ") + t.name)
    }

    /** A member let go: another place of its team (swap), a place of another team (copy), or out (removed). */
    private fun dropMember(i: Int, k: Int, mx: Double, my: Double) {
        val t = teams.getOrNull(i) ?: return
        val m = t.slots()[k] ?: return
        val at = slotAt(mx, my)
        if (at != null) {
            val (j, k2) = at
            if (j == i) {
                if (k2 == k) return
                val sl = t.slots().toMutableList()
                sl[k] = sl[k2]; sl[k2] = m
                save(i, t.withSlots(sl))
                return
            }
            val other = teams[j]
            val sl = other.slots().toMutableList()
            val here = sl.indexOfFirst { it?.uuid == m.uuid }
            if (here >= 0) sl[here] = sl[k2]
            sl[k2] = m
            save(j, other.withSlots(sl))
            s.notify(nameOf(m) + L(" copied into ", " copié dans ") + other.name)
            return
        }
        if (contains(mx, my)) return
        remove(i, k)
    }

    private fun remove(i: Int, k: Int) {
        val t = teams.getOrNull(i) ?: return
        val m = t.slots()[k] ?: return
        val sl = t.slots().toMutableList()
        sl[k] = null
        save(i, t.withSlots(sl))
        s.notify(nameOf(m) + L(" removed from ", " retiré de ") + t.name)
    }

    private fun nameOf(m: PcTeamMember) = s.entryByKey(m.uuid)?.displayName ?: L("The Pokémon", "Le Pokémon")

    // ── teams ──

    private fun save(i: Int, t: PcTeam) { s.prefs.saveTeam(t, i) }

    private fun freshName(): String {
        var n = teams.size + 1
        while (teams.any { it.name.equals(L("Team ", "Équipe ") + n, true) }) n++
        return L("Team ", "Équipe ") + n
    }

    private fun create(t: PcTeam, rename: Boolean = false) {
        if (!s.prefs.saveTeam(t)) { s.notify(L("30 teams at most.", "30 équipes au maximum."), UiKit.DANGER); return }
        val i = teams.size - 1
        reveal(i)
        if (rename) startRename(i)
    }

    private fun reveal(i: Int) {
        val y = i * PITCH
        val area = bottom - listTop
        val max = maxOf(0, teams.size * PITCH - 3 - area)
        if (y < scroll) scroll = y
        else if (y + CARD_H > scroll + area) scroll = (y + CARD_H - area).coerceAtMost(max)
    }

    private fun partyMembers(): List<PcTeamMember?> =
        List(6) { k -> s.session.party?.slots?.getOrNull(k)?.let { p -> s.entry(p.uuid)?.let { member(it) } } }

    private fun nameClick(i: Int) {
        val now = System.currentTimeMillis()
        if (lastNameTeam == i && now - lastNameClick < 350) { lastNameTeam = -1; startRename(i); return }
        lastNameClick = now; lastNameTeam = i
    }

    fun startRename(i: Int) {
        val t = teams.getOrNull(i) ?: return
        renaming = i
        field.text = t.name
        field.setCursorToEnd(false)
        field.setSelectionStart(0)
        field.isFocused = true
        reveal(i)
    }

    fun commitRename() {
        val i = renaming
        renaming = -1
        val t = teams.getOrNull(i) ?: return
        val name = field.text.trim()
        if (name.isNotEmpty() && name != t.name) save(i, t.copy(name = name))
    }

    private fun editMoves(i: Int, k: Int) {
        val t = teams.getOrNull(i) ?: return
        s.dialog = TeamEditDialog(s, i, t, k)
    }

    private fun openNewMenu(x: Int, y: Int) {
        val n = s.selection.size
        s.menu = PcMenu(x, y, listOf(
            MenuItem(L("Empty team", "Équipe vide")) { create(PcTeam(freshName(), emptyList()), rename = true) },
            MenuItem(L("Copy of the current party", "Copie de l'équipe actuelle"), enabled = s.session.partyCount() > 0) {
                create(PcTeam(freshName()).withSlots(partyMembers()), rename = true)
            },
            MenuItem(L("The selected Pokémon", "Les Pokémon choisis") + " ($n)", enabled = n > 0) {
                create(PcTeam(freshName()).withSlots(s.selected().take(6).map { member(it) }), rename = true)
            },
            MenuItem(L("A Showdown paste (clipboard)", "Un paste Showdown (presse-papiers)"), separatorBefore = true) { importShowdown() },
        ), title = L("New team", "Nouvelle équipe"))
    }

    /**
     * A team from the Showdown paste in the clipboard: the player's Pokémon trained for each set,
     * with its moves, shown in the team window to check before saving — a new team, or saved
     * team [into] (its name kept) once confirmed. Empty places say why in the window.
     */
    private fun importShowdown(into: Int = -1) {
        val text = runCatching { net.minecraft.client.MinecraftClient.getInstance().keyboard.clipboard }.getOrDefault("")
        val target = teams.getOrNull(into)
        val result = PcShowdown.build(text, s.entries, target?.name ?: freshName())
        if (result.sets == 0) {
            s.notify(L("No Showdown team in the clipboard: copy it from Showdown's \"Export\" first.",
                "Pas d'équipe Showdown dans le presse-papiers : copiez-la d'abord depuis « Exporter » sur Showdown."), UiKit.GOLD)
            return
        }
        val team = if (target != null) result.team.copy(name = target.name) else result.team
        s.dialog = TeamEditDialog(s, if (target != null) into else -1, team, holes = result.holes, notes = result.notes)
        val head = L("Showdown: ${result.found}/${result.sets} found", "Showdown : ${result.found}/${result.sets} trouvé(s)")
        s.notify(head + (result.problems.firstOrNull()?.let { "  ·  $it" + (if (result.problems.size > 1) " (+${result.problems.size - 1})" else "") } ?: ""),
            if (result.problems.isEmpty()) UiKit.TEXT else UiKit.GOLD)
    }

    private fun openTeamMenu(i: Int, x: Int, y: Int) {
        val t = teams.getOrNull(i) ?: return
        val filled = t.filled().isNotEmpty()
        val n = s.selection.size
        val free = t.slots().count { it == null }
        s.menu = PcMenu(x, y, listOf(
            MenuItem(L("Equip", "Équiper"), enabled = filled) { s.equipTeam(i) },
            MenuItem(L("Moves in this team…", "Capacités dans cette équipe…"), enabled = filled) {
                editMoves(i, t.slots().indexOfFirst { it != null })
            },
            MenuItem(L("Rename", "Renommer")) { startRename(i) },
            MenuItem(L("Take the current party", "Prendre l'équipe actuelle"), enabled = s.session.partyCount() > 0, separatorBefore = true) {
                save(i, t.withSlots(partyMembers()))
            },
            MenuItem(L("Import a Showdown paste (clipboard)…", "Importer un paste Showdown (presse-papiers)…")) { importShowdown(i) },
            MenuItem(L("Add the selected Pokémon", "Ajouter les Pokémon choisis") + " ($n)", enabled = n > 0 && free > 0) { add(s.selected(), i, -1) },
            MenuItem(L("Empty it", "La vider"), enabled = filled) { save(i, t.withSlots(emptyList())) },
            MenuItem(L("Duplicate", "Dupliquer"), enabled = teams.size < 30) { create(t.copy(name = t.name.take(28) + " (2)")) },
            MenuItem(L("Move up", "Monter"), enabled = i > 0, separatorBefore = true) { s.prefs.moveTeam(i, i - 1) },
            MenuItem(L("Move down", "Descendre"), enabled = i < teams.size - 1) { s.prefs.moveTeam(i, i + 1) },
            MenuItem(L("Delete…", "Supprimer…"), color = UiKit.DANGER, separatorBefore = true) {
                s.dialog = ConfirmDialog(s, L("Delete ", "Supprimer ") + t.name,
                    L("The team is forgotten; its Pokémon stay where they are.", "L'équipe est oubliée ; ses Pokémon restent où ils sont."),
                    L("Delete", "Supprimer")) {
                    val at = teams.indexOf(t)
                    if (at >= 0) { s.prefs.deleteTeam(at); if (renaming == at) renaming = -1 }
                }
            },
        ), title = t.name)
    }

    private fun openMemberMenu(i: Int, k: Int, x: Int, y: Int) {
        val t = teams.getOrNull(i) ?: return
        val m = t.slots()[k] ?: return
        val e = s.entryByKey(m.uuid)
        s.menu = PcMenu(x, y, listOf(
            MenuItem(L("Its moves in this team…", "Ses capacités dans cette équipe…"), enabled = e != null) { editMoves(i, k) },
            MenuItem(L("Use the moves it knows now", "Reprendre ses capacités actuelles"), enabled = e != null && movesDiffer(m)) {
                e?.let { val sl = t.slots().toMutableList(); sl[k] = member(it).copy(evs = m.evs); save(i, t.withSlots(sl)) }
            },
            MenuItem(L("Show in the PC", "Voir dans le PC"), enabled = e != null) { e?.let { s.showEntry(it) } },
            MenuItem(L("Remove from the team", "Retirer de l'équipe"), color = UiKit.DANGER, separatorBefore = true) { remove(i, k) },
        ), title = e?.displayName ?: L("Missing Pokémon", "Pokémon absent"))
    }
}

/** A yes/no question before something that cannot be undone. */
internal class ConfirmDialog(screen: HunterPcScreen, private val head: String, private val message: String,
                             private val confirm: String, private val action: () -> Unit) : PcDialog(screen, 250, 86) {
    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        title(ctx, fit(tr, head, w - 16))
        tr.wrapLines(Text.literal(message), w - 16).take(3).forEachIndexed { i, l -> ctx.drawText(tr, l, x + 8, y + 26 + i * 10, UiKit.TEXT_MUTED, false) }
        button(ctx, x + 8, y + h - 20, 70, L("Cancel", "Annuler"), mx, my)
        danger(ctx, x + w - 88, y + h - 20, 80, confirm, mx, my)
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        if (inside(mx, my, x + 8, y + h - 20, 70, 14)) { screen.closeDialog(); return true }
        if (inside(mx, my, x + w - 88, y + h - 20, 80, 14)) { screen.closeDialog(); action(); return true }
        return inside(mx, my, x, y, w, h)
    }

    override fun key(keyCode: Int, mods: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { screen.closeDialog(); action(); return true }
        return false
    }
}

/**
 * One team: its name, its six places (from the party or the selection), and for each
 * Pokémon the four moves it should have in that team — picked among what it can relearn,
 * as in the moves window.
 */
internal class TeamEditDialog(
    screen: HunterPcScreen, private val index: Int, initial: PcTeam, pick: Int = -1,
    /** From a Showdown import: why each empty place is empty, shown there until it is filled. */
    holes: List<PcShowdown.Hole?> = emptyList(),
    /** From a Showdown import: what else went wrong (unknown moves), under the window. */
    private val notes: List<String> = emptyList(),
) : InputDialog(screen, 372, minOf(272, screen.height - 16), 32) {

    private val members: MutableList<PcTeamMember?> = initial.slots().toMutableList()
    private val holes: MutableList<PcShowdown.Hole?> = MutableList(6) { holes.getOrNull(it) }
    private var picked = pick.takeIf { it in 0..5 && members[it] != null } ?: members.indexOfFirst { it != null }
    private var slot = -1
    private var scroll = 0
    private var maxScroll = 0
    private var error: String? = null
    private val tip = PcTooltip()

    init { field.text = initial.name; field.setCursorToEnd(false) }

    companion object {
        /** Cards lighter than the window's zones (PcDialog.Style). */
        private const val CARD = 0x1CFFFFFF
        private const val CARD_HOVER = 0x30FFFFFF

        fun fromParty(screen: HunterPcScreen): TeamEditDialog {
            val party = List(6) { k -> screen.session.party?.slots?.getOrNull(k)?.let { p -> screen.entry(p.uuid)?.let { TeamPanel.member(it) } } }
            return TeamEditDialog(screen, -1, PcTeam(L("Team ", "Équipe ") + (screen.prefs.teams().size + 1)).withSlots(party))
        }
    }

    private fun entryOf(m: PcTeamMember): PcEntry? = screen.entryByKey(m.uuid)

    /**
     * Moves the picked member can be given: what it knows now and what it can relearn,
     * except the ones already among its four for this team.
     */
    private fun choices(e: PcEntry, m: PcTeamMember): List<MoveTemplate> {
        val known = e.pokemon.moveSet.getMoves().map { it.template }
        val other = runCatching { e.pokemon.allAccessibleMoves.toList() }.getOrDefault(emptyList()).filter { t -> known.none { it.name == t.name } }
        return (known + other.sortedBy { it.displayName.string }).filter { it.name !in m.moves }
    }

    private val leftW = 132
    private val rightX get() = x + leftW + 18
    private val memberTop get() = y + 42
    private fun memberY(i: Int) = memberTop + i * 26
    private val addY get() = memberY(6) + 4
    private fun tileX(i: Int) = rightX + MoveTiles.BADGE + (i % 2) * (MoveTiles.TILE_W + MoveTiles.BADGE + 4)
    private fun tileY(i: Int) = y + 53 + (i / 2) * (MoveTiles.TILE_H + 4)
    private val listTop get() = y + 129
    private val listBottom get() = y + h - 45
    /** The card of the move pointed at, right of the list. */
    private val infoX get() = listX + rowW + 11
    private val infoRight get() = x + w - 9

    // the move pointed at, kept a moment while the cursor crosses the gap between two rows
    private var infoMove: MoveTemplate? = null
    private var infoAt = 0L

    private val K = 1.15f
    private val rowW get() = (MoveTiles.ROW_W * K).toInt()
    private val pitch get() = (MoveTiles.ROW_H * K).toInt() + 3
    private val listX get() = rightX + (MoveTiles.BADGE * K).toInt() + 1

    override fun render(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        super.render(ctx, mx, my, delta)
        tip.clear()
        for (i in 0 until 6) if (members[i] != null) holes[i] = null   // filled since: the reason is moot
        title(ctx, if (index < 0) L("New team", "Nouvelle équipe") else L("Edit the team", "Modifier l'équipe"))
        drawField(ctx, x + 8, y + 22, leftW, mx, my, delta)

        // the six places, in party order: a well, each Pokémon a lighter card, the picked one lit
        ctx.fill(x + 5, memberTop - 3, x + 11 + leftW, memberY(6) + 1, WELL)
        UiKit.border(ctx, x + 5, memberTop - 3, leftW + 6, memberY(6) + 4 - memberTop, UiKit.BORDER_DIM)
        for (i in 0 until 6) {
            val my2 = memberY(i)
            val m = members[i]
            val hov = inside(mx, my, x + 8, my2, leftW, 24)
            when {
                picked == i -> {
                    ctx.fill(x + 8, my2, x + 8 + leftW, my2 + 24, UiKit.withAlpha(UiKit.accent(), 0x55))
                    ctx.fill(x + 8, my2, x + 10, my2 + 24, UiKit.accentBright())
                    UiKit.border(ctx, x + 8, my2, leftW, 24, UiKit.withAlpha(UiKit.accentBright(), 0xB0))
                }
                m != null -> {
                    ctx.fill(x + 8, my2, x + 8 + leftW, my2 + 24, if (hov) CARD_HOVER else CARD)
                    UiKit.border(ctx, x + 8, my2, leftW, 24, if (hov) UiKit.BORDER else UiKit.BORDER_DIM)
                }
            }
            UiKit.slot(ctx, x + 11, my2 + 1, 22, 22)
            if (m == null) {
                val hole = holes[i]
                if (hole == null) ctx.drawText(tr, L("— free —", "— libre —"), x + 38, my2 + 8, UiKit.TEXT_FAINT, false)
                else {
                    // an imported set none of the player's Pokémon matched: what it was, and why (pointed at: in full)
                    ctx.drawText(tr, fit(tr, hole.species, leftW - 34), x + 38, my2 + 3, UiKit.GOLD, false)
                    ctx.drawText(tr, fit(tr, hole.reason, leftW - 34), x + 38, my2 + 13, UiKit.TEXT_FAINT, false)
                    if (hov) tip.set(mx, my, hole.species to UiKit.GOLD, hole.why to UiKit.TEXT,
                        L("Drag a Pokémon here, or add the selected ones.", "Glissez un Pokémon ici, ou ajoutez les Pokémon choisis.") to UiKit.TEXT_FAINT)
                }
                continue
            }
            if (m.uuid.isNotBlank()) targets += intArrayOf(x + 8, my2, leftW, 24)
            val e = entryOf(m)
            if (e == null) { ctx.drawText(tr, L("missing", "absent"), x + 38, my2 + 8, UiKit.DANGER, false) }
            else {
                screen.smallPortrait(ctx, e, x + 12, my2 + 2, 20, delta)
                ctx.drawText(tr, fit(tr, e.displayName, leftW - 46), x + 38, my2 + 3, if (e.shiny) SHINY_COLOR else if (picked == i) 0xFFFFFFFF.toInt() else UiKit.TEXT, false)
                val sub = L("Lv ", "Nv ") + e.level + "  ·  " + m.moves.size + "/4"
                ctx.drawText(tr, sub, x + 38, my2 + 13, if (picked == i) UiKit.TEXT_MUTED else UiKit.TEXT_FAINT, false)
                // its Showdown set asked for other EVs: said on the card, in full when pointed at
                if (PcShowdown.evsDiffer(m.evs, e)) {
                    ctx.drawText(tr, " ⚠", x + 38 + tr.getWidth(sub), my2 + 13, EV_WARN, false)
                    if (hov && !inside(mx, my, x + 8 + leftW - 12, my2, 12, 24)) tip.set(mx, my, e.displayName to EV_WARN, PcShowdown.evWarning(m.evs, e) to UiKit.TEXT)
                }
            }
            val rx2 = x + 8 + leftW - 10
            ctx.drawText(tr, "✕", rx2, my2 + 8, if (inside(mx, my, rx2 - 2, my2 + 4, 10, 14)) UiKit.DANGER else UiKit.TEXT_FAINT, false)
        }
        val addLabel = L("+ Selected Pokémon", "+ Pokémon choisis")
        button(ctx, x + 5, addY, leftW + 6, addLabel, mx, my, enabled = screen.selection.isNotEmpty() && members.any { it == null })

        // the picked member's four moves, then what it can be given
        val m = members.getOrNull(picked)
        val e = m?.let { entryOf(it) }
        val zx1 = rightX - 5
        val zx2 = x + w - 5
        zone(ctx, zx1, y + 38, zx2, y + 109, fit(tr, L("Moves in this team", "Capacités dans cette équipe") + (e?.let { " · " + it.displayName } ?: ""), zx2 - zx1 - 14))
        zone(ctx, zx1, y + 112, zx2, listBottom + 3, L("Can be given", "Possibles"))
        var pointed: MoveTemplate? = null
        if (m == null || e == null) {
            ctx.drawText(tr, L("Pick a Pokémon on the left.", "Choisissez un Pokémon à gauche."), rightX, y + 58, UiKit.TEXT_FAINT, false)
        } else {
            for (i in 0 until 4) {
                val t = m.moves.getOrNull(i)?.let { Moves.getByName(it) }
                val hov = inside(mx, my, tileX(i) - MoveTiles.BADGE, tileY(i), MoveTiles.TILE_W + MoveTiles.BADGE, MoveTiles.TILE_H)
                MoveTiles.battle(ctx, tr, tileX(i), tileY(i), t, t?.pp ?: 0, t?.pp ?: 0, hov, slot == i, screen.fine)
                targets += intArrayOf(tileX(i) - MoveTiles.BADGE, tileY(i), MoveTiles.TILE_W + MoveTiles.BADGE, MoveTiles.TILE_H)
                if (hov && t != null) pointed = t
            }
            val res = choices(e, m)
            val left = listX - (MoveTiles.BADGE * K).toInt() - 1
            // the list in a darker well, as wide as its rows
            ctx.fill(left - 3, listTop - 2, listX + rowW + 8, listBottom + 1, WELL)
            ctx.enableScissorSafe(left, listTop, listX + rowW + 2, listBottom)
            // the row under the cursor counts the gap below it: no flicker from one row to the next
            val rowAt = if (mx >= left && mx < listX + rowW && my >= listTop && my < listBottom) ((my - listTop + scroll) / pitch) else -1
            res.forEachIndexed { i, t ->
                val ry = listTop + i * pitch - scroll
                if (ry + pitch < listTop || ry > listBottom) return@forEachIndexed
                val hov = i == rowAt
                ctx.matrices.push(); ctx.matrices.translate(listX.toFloat(), ry.toFloat(), 0f); ctx.matrices.scale(K, K, 1f)
                MoveTiles.row(ctx, 0, 0, t, hov)
                ctx.matrices.pop()
                if (ry >= listTop && ry + pitch - 3 <= listBottom) targets += intArrayOf(left, ry, listX + rowW - left, pitch - 3)
                if (hov) pointed = t
            }
            if (res.isEmpty()) tr.wrapLines(net.minecraft.text.Text.literal(L("No other move it can be given.", "Aucune autre capacité possible.")), rowW)
                .forEachIndexed { n, l -> ctx.drawText(tr, l, left + 2, listTop + 3 + n * 10, UiKit.TEXT_FAINT, false) }
            ctx.disableScissor()
            maxScroll = maxOf(0, res.size * pitch - (listBottom - listTop))
            UiKit.scrollbar(ctx, listX + rowW + 4, listTop, listBottom, res.size * pitch, scroll)
            // the move pointed at (else the one of the slot being changed), on its own card
            val now = System.currentTimeMillis()
            if (pointed != null) { infoMove = pointed; infoAt = now }
            val shown = pointed ?: infoMove?.takeIf { now - infoAt < 250 } ?: if (slot >= 0) m.moves.getOrNull(slot)?.let { Moves.getByName(it) } else null
            drawInfo(ctx, shown)
        }
        UiKit.dots(ctx, x + 5, y + h - 38, w - 10)
        // what an import could not do (moves Cobblemon does not know) stays under the window, all of it on hover
        val note = notes.takeIf { error == null && slot < 0 && it.isNotEmpty() }
        val hint = error ?: note?.let { "⚠ " + it.first() + (if (it.size > 1) "  (+${it.size - 1})" else "") }
            ?: if (slot >= 0) L("A move below, or another slot: they swap.", "Une capacité dessous, ou un autre emplacement : échange.")
            else L("Click a move slot, then a move below.", "Cliquez un emplacement, puis une capacité dessous.")
        ctx.drawText(tr, fit(tr, hint, w - 16), x + 8, y + h - 33,
            when { error != null -> UiKit.DANGER; note != null -> UiKit.GOLD; slot >= 0 -> UiKit.accentBright(); else -> UiKit.TEXT_MUTED }, false)
        if (note != null && inside(mx, my, x + 8, y + h - 35, w - 16, 12)) tip.set(mx, my, *note.map { it to UiKit.TEXT }.toTypedArray())
        button(ctx, x + 8, y + h - 20, 70, L("Cancel", "Annuler"), mx, my)
        button(ctx, x + w - 98, y + h - 20, 90, L("Save", "Enregistrer"), mx, my)
        tip.draw(ctx, tr, screen.width, screen.height)
    }

    /** Name, type and category, power, accuracy, PP and description of [t], wrapped to the card. */
    private fun drawInfo(ctx: DrawContext, t: MoveTemplate?) {
        val x1 = infoX; val x2 = infoRight
        ctx.fill(x1, listTop - 2, x2, listBottom + 1, WELL)
        UiKit.border(ctx, x1, listTop - 2, x2 - x1, listBottom + 3 - listTop, UiKit.BORDER_DIM)
        val tw = x2 - x1 - 8
        var ty = listTop + 3
        if (t == null) {
            tr.wrapLines(net.minecraft.text.Text.literal(L("Point at a move to read it here.", "Survolez une capacité pour la lire ici.")), tw)
                .forEach { l -> ctx.drawText(tr, l, x1 + 4, ty, UiKit.TEXT_FAINT, false); ty += 10 }
            return
        }
        TypeIcons.draw(ctx, listOf(t.elementalType), x1 + 4, ty, small = true)
        ctx.drawText(tr, fit(tr, t.displayName.string, tw - 12), x1 + 16, ty + 1, 0xFFFFFFFF.toInt(), true)
        ty += 13
        MoveTiles.tooltip(t, null).drop(1).forEach { (text, color) ->
            for (l in tr.wrapLines(net.minecraft.text.Text.literal(text), tw)) {
                if (ty + 8 > listBottom) return
                ctx.drawText(tr, l, x1 + 4, ty, color, false); ty += 10
            }
            ty += 2
        }
    }

    override fun submit() {
        val team = PcTeam(field.text).withSlots(members)
        if (screen.prefs.saveTeam(team, index)) {
            screen.notify(L("Team saved: ", "Équipe enregistrée : ") + field.text.trim())
            screen.closeDialog()
        } else error = L("A name is needed (30 teams at most).", "Il faut un nom (30 équipes au maximum).")
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        if (inside(mx, my, x + 8, y + h - 20, 70, 14)) { screen.closeDialog(); return true }
        if (inside(mx, my, x + w - 98, y + h - 20, 90, 14)) { submit(); return true }
        if (field.isMouseOver(mx, my)) { field.mouseClicked(mx, my, button); field.isFocused = true; return true }
        error = null
        for (i in 0 until 6) {
            val my2 = memberY(i)
            if (!inside(mx, my, x + 8, my2, leftW, 24)) continue
            if (members[i] == null) return true
            if (mx >= x + 8 + leftW - 12) {   // ✕: out of the team, the place stays free
                members[i] = null
                if (picked == i) { picked = members.indexOfFirst { it != null }; slot = -1 }
            } else { picked = i; slot = -1; scroll = 0 }
            return true
        }
        if (inside(mx, my, x + 5, addY, leftW + 6, 14) && members.any { it == null }) {
            for (e in screen.selected()) {
                val free = members.indexOfFirst { it == null }
                if (free < 0) break
                if (members.none { it?.uuid == e.uuid.toString() }) members[free] = TeamPanel.member(e)
            }
            if (picked < 0) picked = members.indexOfFirst { it != null }
            return true
        }
        val m = members.getOrNull(picked) ?: return inside(mx, my, x, y, w, h)
        val e = entryOf(m) ?: return inside(mx, my, x, y, w, h)
        for (i in 0 until 4) if (inside(mx, my, tileX(i) - MoveTiles.BADGE, tileY(i), MoveTiles.TILE_W + MoveTiles.BADGE, MoveTiles.TILE_H)) {
            val from = slot
            if (from < 0 || from == i) { slot = if (slot == i) -1 else i; return true }
            // a slot picked, then another one: the two moves change places (into an empty one: moved there)
            val moves = m.moves.toMutableList()
            while (moves.size < 4) moves += ""
            val tmp = moves[from]; moves[from] = moves[i]; moves[i] = tmp
            members[picked] = m.copy(moves = moves.filter { it.isNotBlank() })
            slot = -1
            return true
        }
        val left = listX - (MoveTiles.BADGE * K).toInt() - 1
        if (my >= listTop && my < listBottom && mx >= left && mx < listX + rowW) {
            val t = choices(e, m).getOrNull(((my - listTop + scroll) / pitch).toInt()) ?: return true
            val moves = m.moves.toMutableList()
            val target = if (slot >= 0) slot else moves.size.takeIf { it < 4 } ?: run { error = L("Pick the slot to change first.", "Choisissez d'abord l'emplacement à changer."); return true }
            while (moves.size <= target) moves += ""
            moves[target] = t.name
            members[picked] = m.copy(moves = moves.filter { it.isNotBlank() })
            slot = -1
            return true
        }
        return inside(mx, my, x, y, w, h)
    }

    override fun scroll(mx: Double, my: Double, amount: Double): Boolean { scroll = (scroll - (amount * 20).toInt()).coerceIn(0, maxScroll); return true }
}
