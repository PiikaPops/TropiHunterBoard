package com.hunterboard

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import com.hunterboard.CaptureFilters.CaptureFilter
import com.hunterboard.CaptureFilters.Condition
import com.hunterboard.CaptureFilters.Field
import com.hunterboard.CaptureFilters.Group
import com.hunterboard.CaptureFilters.Kind
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * Master–detail editor for the capture filters.
 *
 * Left: every filter, with its sprite, its state and a one-line gist, and the button that
 * adds one. Right: the selected filter — what it does with matching Pokémon, its rule
 * drawn as boxes (lines inside a box are AND, boxes are joined by OR, any line can be
 * negated with NOT), the whole rule spelled out as a sentence, and what it would do to
 * the Pokémon of that species in the party right now.
 *
 * Every edit is saved as it is made; there is no "apply" to forget.
 *
 * Filters travel as codes ([CaptureFilters.exportCode]): "Export" copies one filter's,
 * "Export all" every filter's, and "Import" opens a window that reads a pasted code and
 * shows what it holds before anything changes. [importCode] opens that window at once,
 * for the "[Import]" link HunterBoard adds after a code posted in the chat.
 */
class CaptureFilterScreen(private val parent: Screen?, private var importCode: String? = null) :
    ScaledScreen(Text.literal("Capture filters")) {

    companion object {
        private var lastSelected: String? = null
        private const val ROW_H = 26
        private const val COND_H = 18
        /** Abilities that count through evolution: one colour wherever they are shown. */
        private val EVO_COLOR = 0xFF55DDDD.toInt()
        private const val CTRL_H = 14
        private const val DELETE_CONFIRM_MS = 3000L
        private const val COPIED_MS = 2000L
        private const val FLASH_MS = 6000L
        private const val IMPORT_ROW_H = 24
        private val NEW_COLOR = 0xFF55FF55.toInt()
        private val RELEASE_COLOR = 0xFFFFAA55.toInt()
    }

    private fun t(en: String, fr: String) = if (Translations.isFrench()) fr else en

    // ── per-frame interaction ──────────────────────────────────────────────────────

    private class Hit(
        val x: Int, val y: Int, val w: Int, val h: Int,
        /** Vertical clip of a scrolled area: outside it the rectangle does not count. */
        val clipTop: Int = Int.MIN_VALUE, val clipBottom: Int = Int.MAX_VALUE,
        val onScroll: ((Double, Boolean) -> Unit)? = null,
        val onClick: ((Int) -> Unit)? = null
    ) {
        fun contains(mx: Double, my: Double) =
            mx >= x && mx < x + w && my >= y && my < y + h && my >= clipTop && my < clipBottom
    }

    /** A dropdown list opened from a control. Items are (value, label). */
    private class Menu(
        val x: Int, val y: Int, val w: Int,
        val items: List<Pair<String, String>>, val selected: String?,
        val onPick: (String) -> Unit
    ) {
        var scroll = 0
        val visible get() = minOf(items.size, 10)
        val h get() = visible * 12 + 4
    }

    private val hits = mutableListOf<Hit>()
    private var menu: Menu? = null
    private var hint: String? = null

    // ── state ──────────────────────────────────────────────────────────────────────

    private var selectedId: String? = lastSelected
    private var listScroll = 0
    private var ruleScroll = 0
    private var ruleContentH = 0
    private var deleteArmedUntil = 0L

    private lateinit var search: TextFieldWidget
    private lateinit var numField: TextFieldWidget
    private var editing: Condition? = null

    // sharing
    /** "all", or the id of the filter whose "Export" button reads "Copied" for a moment. */
    private var copied: String? = null
    private var copiedUntil = 0L
    /** A message that takes over the footer for a few seconds. */
    private var flash: String? = null
    private var flashUntil = 0L
    private var importOpen = false
    private lateinit var importField: TextFieldWidget
    private var importScroll = 0
    private var importSeen: String? = null
    private var importDecoded: CaptureFilters.Decoded? = null
    private var tooltip: List<net.minecraft.text.OrderedText>? = null

    // layout
    private var px = 0; private var pw = 0; private var top = 0; private var bottom = 0
    private var listX = 0; private var listW = 0
    private var detailX = 0; private var detailRight = 0

    private fun selected(): CaptureFilter? =
        CaptureFilters.all().firstOrNull { it.id == selectedId }

    private fun select(f: CaptureFilter?) {
        commitEdit()
        selectedId = f?.id
        lastSelected = selectedId
        ruleScroll = 0
        menu = null
        deleteArmedUntil = 0L
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        layout()
        // init() runs again on every resize and on the way back from the species picker
        val previousQuery = if (::search.isInitialized) search.text else ""
        editing = null
        search = TextFieldWidget(textRenderer, listX + 1, top + 24, listW - 2, 14, Text.literal(""))
        UiKit.styleField(search, icon = false)
        search.setMaxLength(40)
        search.text = previousQuery
        search.setPlaceholder(Text.literal(t("Search…", "Rechercher…")).styled { it.withColor(UiKit.TEXT_FAINT) })
        search.setChangedListener { listScroll = 0 }
        addDrawableChild(search)

        numField = TextFieldWidget(textRenderer, 0, 0, 30, 12, Text.literal(""))
        numField.setMaxLength(3)
        numField.setTextPredicate { s -> s.all { it.isDigit() } }
        numField.visible = false
        addDrawableChild(numField)

        // drawn by hand, over the import window: not a drawable child
        val previousCode = if (::importField.isInitialized) importField.text else ""
        importField = TextFieldWidget(textRenderer, 0, 0, 200, 16, Text.literal(""))
        UiKit.styleField(importField, icon = false)
        importField.setMaxLength(200_000)
        importField.text = previousCode
        importField.setPlaceholder(Text.literal(t("Paste a code here (Ctrl+V)", "Collez un code ici (Ctrl+V)"))
            .styled { it.withColor(UiKit.TEXT_FAINT) })
        importField.visible = importOpen
        addSelectableChild(importField)
        if (importOpen) setFocused(importField)

        if (selected() == null) selectedId = CaptureFilters.all().firstOrNull()?.id
        importCode?.let { code -> importCode = null; openImport(code) }
    }

    private fun layout() {
        pw = (width - 16).coerceIn(340, 640).coerceAtMost(width - 4)
        px = (width - pw) / 2
        top = 14
        bottom = height - 14
        listX = px + 8
        listW = (pw * 0.33).toInt().coerceIn(128, 190)
        detailX = listX + listW + 10
        detailRight = px + pw - 9
    }

    // ── rendering ──────────────────────────────────────────────────────────────────

    override fun renderScaled(context: DrawContext, realMouseX: Int, realMouseY: Int, delta: Float) {
        hits.clear()
        hint = null
        tooltip = null
        layout()
        UiKit.placeField(search, listX + 1, top + 24, listW - 2)
        // under the import window nothing reacts to the mouse
        val mouseX = if (importOpen) -10_000 else realMouseX
        val mouseY = if (importOpen) -10_000 else realMouseY

        UiKit.screenDim(context, width, height)
        UiKit.panel(context, px, top, pw, bottom - top)
        UiKit.header(context, textRenderer, px, top, pw, t("Capture filter", "Filtre Auto Capture"))
        val closeX = px + pw - 12
        UiKit.closeButton(context, textRenderer, closeX, top + 4, mouseX, mouseY)
        hits += Hit(closeX - 2, top + 2, 12, 13, onClick = { close() })

        renderList(context, mouseX, mouseY)
        renderDetail(context, mouseX, mouseY)

        val flashing = flash?.takeIf { System.currentTimeMillis() < flashUntil }
        UiKit.footer(context, textRenderer, px, bottom, pw, fit(flashing ?: hint ?: t(
            "Click to change · Wheel on a number: ±1 (Shift ±5) · Saved automatically",
            "Clic : modifier · Molette sur un nombre : ±1 (Maj ±5) · Enregistré automatiquement"
        ), pw - 16))

        val e = editing
        numField.visible = e != null
        UiKit.fieldBackground(context, search)
        renderWidgets(context, mouseX, mouseY, delta)
        menu?.let { renderMenu(context, it, mouseX, mouseY) }
        if (importOpen) {
            hits.clear()
            renderImport(context, realMouseX, realMouseY, delta)
        }
        tooltip?.let { context.drawOrderedTooltip(textRenderer, it, realMouseX, realMouseY) }
    }

    // ── left: the list ─────────────────────────────────────────────────────────────

    private fun renderList(context: DrawContext, mouseX: Int, mouseY: Int) {
        val listTop = top + 42
        val addY = bottom - 18 - 18
        val shareY = addY - 2 - CTRL_H
        val listBottom = shareY - 4
        context.fill(listX - 1, top + 22, listX + listW + 1, bottom - 16, UiKit.SURFACE_SUNKEN)

        val query = search.text.trim()
        val shown = CaptureFilters.all()
            .let { list -> NameUtil.sortedByName(list) { CaptureFilters.displayName(it) } }
            .sortedByDescending { it.all }
            .filter { f -> query.isEmpty() || NameUtil.matchesQuery(CaptureFilters.displayName(f), query) ||
                f.members().any { NameUtil.matchesQuery(CaptureFilters.speciesName(it), query) || NameUtil.matchesQuery(it, query) } }

        val viewH = listBottom - listTop
        val contentH = shown.size * ROW_H
        listScroll = listScroll.coerceIn(0, maxOf(0, contentH - viewH))
        hits += Hit(listX, listTop, listW, viewH, onScroll = { amount, _ ->
            listScroll = (listScroll - (amount * ROW_H).toInt()).coerceIn(0, maxOf(0, contentH - viewH))
        })

        scissor(context, listX, listTop, listX + listW, listBottom)
        var y = listTop - listScroll
        for (f in shown) {
            if (y + ROW_H >= listTop && y <= listBottom) drawListRow(context, f, y, mouseX, mouseY, listTop, listBottom)
            y += ROW_H
        }
        context.disableScissor()
        UiKit.scrollbar(context, listX + listW - 3, listTop, listBottom, contentH, listScroll)

        if (CaptureFilters.all().isEmpty()) {
            val lines = textRenderer.wrapLines(Text.literal(t(
                "No filter yet. Captures are never touched without one.",
                "Aucun filtre. Sans filtre, aucune capture n'est touchée.")), listW - 12)
            var ly = listTop + 6
            for (l in lines) { context.drawText(textRenderer, l, listX + 6, ly, UiKit.TEXT_FAINT, true); ly += 10 }
        } else if (shown.isEmpty()) {
            context.drawText(textRenderer, t("No match", "Aucun résultat"), listX + 6, listTop + 6, UiKit.TEXT_FAINT, true)
        }

        // two ways in: a filter for one Pokémon (others can join it), or the one for all of them
        val allLabel = t("+ All", "+ Tous")
        val allW = textRenderer.getWidth(allLabel) + 12
        val newW = listW - allW - 2
        val label = t("+ Pokémon", "+ Pokémon")
        val hov = inside(mouseX, mouseY, listX, addY, newW, 18)
        UiKit.button(context, textRenderer, listX, addY, newW, 18, label, hov, active = CaptureFilters.all().isEmpty())
        hits += Hit(listX, addY, newW, 18, onClick = { openPicker() })
        if (hov) hint = t("New filter: pick a Pokémon (its family can be added after)",
                          "Nouveau filtre : choisissez un Pokémon (sa famille peut être ajoutée ensuite)")
        val allX = listX + newW + 2
        val allHov = inside(mouseX, mouseY, allX, addY, allW, 18)
        UiKit.button(context, textRenderer, allX, addY, allW, 18, allLabel, allHov, active = selected()?.all == true)
        hits += Hit(allX, addY, allW, 18, onClick = { select(CaptureFilters.createGlobal()) })
        if (allHov) hint = t("The filter for every Pokémon without a more precise one",
                             "Le filtre de tous les Pokémon qui n'en ont pas de plus précis")

        // sharing: a code pasted from someone else, or all of these as one code
        val half = (listW - 2) / 2
        val impLabel = t("Import", "Importer")
        val impHov = inside(mouseX, mouseY, listX, shareY, half, CTRL_H)
        UiKit.button(context, textRenderer, listX, shareY, half, CTRL_H, impLabel, impHov)
        hits += Hit(listX, shareY, half, CTRL_H, onClick = { openImport() })
        if (impHov) hint = t("Add filters from a code someone shared with you",
                             "Ajouter des filtres à partir d'un code qu'on vous a partagé")
        val expX = listX + half + 2
        val expW = listW - half - 2
        val expLabel = when {
            copied == "all" && System.currentTimeMillis() < copiedUntil -> t("✔ Copied", "✔ Copié")
            textRenderer.getWidth(t("Export all", "Exporter tout")) + 8 <= expW -> t("Export all", "Exporter tout")
            else -> t("Export", "Exporter")
        }
        val expHov = inside(mouseX, mouseY, expX, shareY, expW, CTRL_H)
        UiKit.button(context, textRenderer, expX, shareY, expW, CTRL_H, expLabel, expHov)
        hits += Hit(expX, shareY, expW, CTRL_H, onClick = { export(CaptureFilters.allForExport(), "all") })
        if (expHov) hint = t("Copies one code holding all your filters, to share them",
                             "Copie un code contenant tous vos filtres, pour les partager")
    }

    private fun drawListRow(context: DrawContext, f: CaptureFilter, y: Int, mouseX: Int, mouseY: Int,
                            clipTop: Int, clipBottom: Int) {
        val isSel = f.id == selectedId
        val hov = inside(mouseX, mouseY, listX, y, listW, ROW_H) && mouseY in clipTop until clipBottom
        // first, so the switch drawn on top of the row is found before it
        hits += Hit(listX, y, listW, ROW_H, clipTop, clipBottom, onClick = { select(f) })
        if (isSel) UiKit.rowHighlight(context, listX, y, listW, ROW_H)
        else if (hov) context.fill(listX, y, listX + listW, y + ROW_H, UiKit.SURFACE_HOVER)

        if (f.all) context.drawText(textRenderer, "\u2731", listX + 9, y + 8, if (f.enabled) UiKit.accentBright() else UiKit.TEXT_FAINT, true)
        else drawSprite(context, f.species, listX + 3, y + 3, 20, alpha = f.enabled)
        val nameColor = if (f.enabled) UiKit.TEXT else UiKit.TEXT_FAINT
        val textW = listW - 26 - 26
        context.drawText(textRenderer, fit(CaptureFilters.displayName(f), textW), listX + 26, y + 4, nameColor, true)
        val (gist, color) = gist(f)
        context.drawText(textRenderer, fit(gist, textW), listX + 26, y + 14, color, false)

        // on/off switch
        val sx = listX + listW - 24
        val sy = y + 9
        drawSwitch(context, sx, sy, f.enabled, inside(mouseX, mouseY, sx - 2, sy - 3, 22, 14))
        hits += Hit(sx - 2, sy - 3, 22, 14, clipTop, clipBottom, onClick = {
            f.enabled = !f.enabled; CaptureFilters.changed()
        })
        if (inside(mouseX, mouseY, sx - 2, sy - 3, 22, 14)) hint = if (f.enabled)
            t("Switch this filter off without deleting it", "Désactiver ce filtre sans le supprimer")
            else t("Switch this filter back on", "Réactiver ce filtre")
    }

    private fun gist(f: CaptureFilter): Pair<String, Int> = when {
        !f.enabled -> t("Off", "Désactivé") to UiKit.TEXT_FAINT
        !f.hasConditions() -> t("No condition", "Aucune condition") to UiKit.GOLD
        !f.isComplete() -> t("Incomplete", "Incomplet") to UiKit.GOLD
        else -> {
            val n = f.conditionCount()
            val what = if (f.mode == CaptureFilters.MODE_RELEASE) t("Release if", "Relâcher si") else t("Keep if", "Garder si")
            "$what · $n ${if (n > 1) t("conditions", "conditions") else t("condition", "condition")}" to UiKit.TEXT_MUTED
        }
    }

    private fun drawSwitch(context: DrawContext, x: Int, y: Int, on: Boolean, hov: Boolean) {
        val w = 18; val h = 8
        val track = if (on) UiKit.withAlpha(UiKit.accent(), 0xCC) else UiKit.SURFACE_HOVER
        context.fill(x, y, x + w, y + h, track)
        UiKit.border(context, x, y, w, h, if (hov) UiKit.accent() else UiKit.BORDER)
        val kx = if (on) x + w - 7 else x + 1
        context.fill(kx, y + 1, kx + 6, y + h - 1, if (on) 0xFFFFFFFF.toInt() else UiKit.TEXT_FAINT)
    }

    private fun drawSprite(context: DrawContext, species: String, x: Int, y: Int, size: Int, alpha: Boolean = true) {
        val sp = CaptureFilters.species(species)
        val id = sp?.let { SpriteHelper.getSpriteIdentifier(it.name) }
        if (id != null) {
            if (!alpha) com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 0.4f)
            com.mojang.blaze3d.systems.RenderSystem.enableBlend()
            context.drawTexture(id, x, y, 0f, 0f, size, size, size, size)
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        } else {
            context.fill(x + 2, y + 2, x + size - 2, y + size - 2, UiKit.withAlpha(UiKit.TEXT_FAINT, 0x30))
        }
    }

    // ── right: the selected filter ─────────────────────────────────────────────────

    private fun renderDetail(context: DrawContext, mouseX: Int, mouseY: Int) {
        val f = selected()
        val w = detailRight - detailX
        if (f == null) { renderEmpty(context, mouseX, mouseY, w); return }

        var y = top + 24
        y = renderBanner(context, mouseX, mouseY, y, w)

        // identity: sprite, name, dex number, on/off and delete
        val members = f.members()
        if (f.all) context.drawText(textRenderer, "\u2731", detailX + 12, y + 12, UiKit.accentBright(), true)
        else drawSprite(context, f.species, detailX, y, 32, alpha = f.enabled)
        val sp = CaptureFilters.species(f.species)
        context.drawText(textRenderer, CaptureFilters.displayName(f), detailX + 38, y + 5, UiKit.TEXT, true)
        val sub = when {
            f.all -> t("Every capture without a more precise filter", "Toute capture sans filtre plus précis")
            members.size > 1 -> "${members.size} Pokémon"
            else -> sp?.let { "#%03d".format(it.nationalPokedexNumber) } ?: ""
        }
        context.drawText(textRenderer, sub, detailX + 38, y + 18, UiKit.TEXT_FAINT, false)

        val armed = System.currentTimeMillis() < deleteArmedUntil
        val delLabel = if (armed) t("Confirm?", "Confirmer ?") else t("Delete", "Supprimer")
        val delW = textRenderer.getWidth(delLabel) + 12
        val delX = detailRight - delW
        val delHov = inside(mouseX, mouseY, delX, y + 2, delW, CTRL_H)
        UiKit.button(context, textRenderer, delX, y + 2, delW, CTRL_H, delLabel, delHov, active = armed)
        if (armed || delHov) UiKit.border(context, delX, y + 2, delW, CTRL_H, UiKit.DANGER)
        hits += Hit(delX, y + 2, delW, CTRL_H, onClick = {
            if (System.currentTimeMillis() < deleteArmedUntil) {
                val list = CaptureFilters.all()
                val i = list.indexOfFirst { it.id == f.id }
                CaptureFilters.delete(f)
                select(CaptureFilters.all().getOrNull(i.coerceAtMost(CaptureFilters.all().size - 1)))
            } else deleteArmedUntil = System.currentTimeMillis() + DELETE_CONFIRM_MS
        })
        if (delHov) hint = t("Deletes this filter (click twice)", "Supprime ce filtre (deux clics)")

        val onLabel = if (f.enabled) t("On", "Activé") else t("Off", "Désactivé")
        val onW = textRenderer.getWidth(onLabel) + 30
        val onX = delX - 6 - onW
        val onHov = inside(mouseX, mouseY, onX, y + 2, onW, CTRL_H)
        UiKit.button(context, textRenderer, onX, y + 2, onW, CTRL_H, "", onHov)
        drawSwitch(context, onX + 4, y + 5, f.enabled, onHov)
        context.drawText(textRenderer, onLabel, onX + 26, y + 5, if (f.enabled) UiKit.TEXT else UiKit.TEXT_MUTED, true)
        hits += Hit(onX, y + 2, onW, CTRL_H, onClick = { f.enabled = !f.enabled; CaptureFilters.changed() })

        // this filter alone, as a code; an arrow when the name leaves no room for the word
        val nameEnd = detailX + 38 + textRenderer.getWidth(CaptureFilters.displayName(f)) + 6
        val justCopied = copied == f.id && System.currentTimeMillis() < copiedUntil
        val fullLabel = if (justCopied) t("✔ Copied", "✔ Copié") else t("Export", "Exporter")
        val expLabel = if (onX - 6 - (textRenderer.getWidth(fullLabel) + 12) >= nameEnd) fullLabel
                       else if (justCopied) "✔" else "⇪"
        val expW = textRenderer.getWidth(expLabel) + 12
        val expX = onX - 6 - expW
        val expHov = inside(mouseX, mouseY, expX, y + 2, expW, CTRL_H)
        UiKit.button(context, textRenderer, expX, y + 2, expW, CTRL_H, expLabel, expHov)
        hits += Hit(expX, y + 2, expW, CTRL_H, onClick = { export(listOf(f), f.id) })
        if (expHov) hint = t("Copies this filter's code, to share it (Discord, chat…)",
                             "Copie le code de ce filtre, pour le partager (Discord, chat…)")
        y += 36
        if (!f.all) y = renderMembers(context, f, mouseX, mouseY, y, w)

        // what happens to the Pokémon that match
        y = renderMode(context, f, mouseX, mouseY, y, w) + 6

        // the rule, scrolled; the summary is pinned under it
        val summaryLines = summaryLines(f, w - 12)
        val summaryH = summaryLines.size * 10 + 10
        val ruleTop = y
        val ruleBottom = (bottom - 18 - summaryH - 4).coerceAtLeast(ruleTop + 40)
        renderRule(context, f, mouseX, mouseY, ruleTop, ruleBottom, w)
        renderSummary(context, summaryLines, ruleBottom + 4, summaryH, w)
    }

    /**
     * The Pokémon sharing this filter: one sprite each (click to take it out), then the two
     * ways to add some — one by one, or the whole evolution line at once.
     */
    private fun renderMembers(context: DrawContext, f: CaptureFilter, mouseX: Int, mouseY: Int, y: Int, w: Int): Int {
        val members = f.members()
        val famLabel = t("+ Family", "+ Famille")
        val addLabel = "+ Pokémon"
        val famW = textRenderer.getWidth(famLabel) + 10
        val addW = textRenderer.getWidth(addLabel) + 10
        val famX = detailRight - famW
        val addX = famX - 2 - addW
        var x = detailX
        val room = addX - 6
        var shownCount = 0
        for (m in members) {
            if (x + 18 > room - 22) break
            val hov = inside(mouseX, mouseY, x, y, 18, 18)
            if (hov && members.size > 1) context.fill(x, y, x + 18, y + 18, UiKit.withAlpha(UiKit.DANGER, 0x40))
            drawSprite(context, m, x, y, 18)
            if (members.size > 1) hits += Hit(x, y, 18, 18, onClick = { CaptureFilters.removeSpecies(f, m) })
            if (hov) hint = CaptureFilters.speciesName(m) + if (members.size > 1) t(" — click to remove", " — clic pour retirer") else ""
            x += 19
            shownCount++
        }
        if (shownCount < members.size) {
            context.drawText(textRenderer, "+${members.size - shownCount}", x + 2, y + 5, UiKit.TEXT_MUTED, true)
        }
        val addHov = inside(mouseX, mouseY, addX, y + 2, addW, CTRL_H)
        UiKit.button(context, textRenderer, addX, y + 2, addW, CTRL_H, addLabel, addHov)
        hits += Hit(addX, y + 2, addW, CTRL_H, onClick = { openPicker(f) })
        if (addHov) hint = t("Add another Pokémon to this filter", "Ajouter un autre Pokémon à ce filtre")
        val famHov = inside(mouseX, mouseY, famX, y + 2, famW, CTRL_H)
        UiKit.button(context, textRenderer, famX, y + 2, famW, CTRL_H, famLabel, famHov)
        hits += Hit(famX, y + 2, famW, CTRL_H, onClick = {
            for (m in f.members().toList()) CaptureFilters.species(m)?.let { sp ->
                CaptureFilters.family(sp).forEach { CaptureFilters.addSpecies(f, it) }
            }
        })
        if (famHov) hint = t("Add the whole evolution line of these Pokémon", "Ajouter toute la lignée d'évolution de ces Pokémon")
        return y + 22
    }

    private fun renderEmpty(context: DrawContext, mouseX: Int, mouseY: Int, w: Int) {
        val cy = (top + bottom) / 2 - 30
        val title = t("Filter your captures automatically", "Filtrez vos captures automatiquement")
        context.drawText(textRenderer, title, detailX + (w - textRenderer.getWidth(title)) / 2, cy, UiKit.TEXT, true)
        val body = t(
            "Choose a Pokémon and the conditions its captures must meet. Those that do not are released for you; shinies are always kept.",
            "Choisissez un Pokémon et les conditions que ses captures doivent remplir. Celles qui n'y répondent pas sont relâchées pour vous ; les shiny sont toujours gardés.")
        var ly = cy + 16
        for (l in textRenderer.wrapLines(Text.literal(body), w - 30)) {
            context.drawText(textRenderer, l, detailX + (w - textRenderer.getWidth(l)) / 2, ly, UiKit.TEXT_MUTED, false)
            ly += 10
        }
        val label = t("+ Create a filter", "+ Créer un filtre")
        val bw = textRenderer.getWidth(label) + 24
        val bx = detailX + (w - bw) / 2
        val by = ly + 8
        val hov = inside(mouseX, mouseY, bx, by, bw, 18)
        UiKit.button(context, textRenderer, bx, by, bw, 18, label, hov, active = true)
        hits += Hit(bx, by, bw, 18, onClick = { openPicker() })
    }

    /** The master switch and test mode, when they stop filters from doing anything. */
    private fun renderBanner(context: DrawContext, mouseX: Int, mouseY: Int, y: Int, w: Int): Int {
        val (text, action, color, run) = when {
            !ModConfig.captureFilterEnabled -> Quad(
                t("Automatic release is off: filters do nothing.", "Le relâchement automatique est désactivé : les filtres n'agissent pas."),
                t("Turn on", "Activer"), UiKit.GOLD) { ModConfig.toggleCaptureFilter() }
            ModConfig.captureFilterSimulate -> Quad(
                t("Test mode: nothing is released, the chat says what would be.", "Mode test : rien n'est relâché, le chat indique ce qui l'aurait été."),
                t("Leave test", "Quitter le test"), 0xFF55FFFF.toInt()) { ModConfig.toggleCaptureFilterSimulate() }
            else -> return y
        }
        val bw = textRenderer.getWidth(action) + 10
        context.fill(detailX, y, detailRight, y + 16, UiKit.withAlpha(color, 0x22))
        UiKit.border(context, detailX, y, w, 16, UiKit.withAlpha(color, 0x88))
        context.drawText(textRenderer, fit(text, w - bw - 14), detailX + 5, y + 4, color, false)
        val bx = detailRight - bw - 2
        val hov = inside(mouseX, mouseY, bx, y + 2, bw, 12)
        UiKit.button(context, textRenderer, bx, y + 2, bw, 12, action, hov)
        hits += Hit(bx, y + 2, bw, 12, onClick = { run() })
        return y + 20
    }

    private data class Quad(val a: String, val b: String, val c: Int, val d: () -> Unit)

    private fun renderMode(context: DrawContext, f: CaptureFilter, mouseX: Int, mouseY: Int, y: Int, w: Int): Int {
        val options = listOf(
            CaptureFilters.MODE_KEEP to t("Keep only if…", "Garder seulement si…"),
            CaptureFilters.MODE_RELEASE to t("Release if…", "Relâcher si…")
        )
        val segW = w / options.size
        for ((i, opt) in options.withIndex()) {
            val sx = detailX + i * segW
            val sw = if (i == options.size - 1) w - segW * i else segW
            val active = f.mode == opt.first
            val hov = inside(mouseX, mouseY, sx, y, sw, 16)
            UiKit.button(context, textRenderer, sx, y, sw, 16, opt.second, hov, active)
            hits += Hit(sx, y, sw, 16, onClick = {
                if (f.mode != opt.first) { f.mode = opt.first; CaptureFilters.changed() }
            })
            if (hov) hint = if (opt.first == CaptureFilters.MODE_KEEP)
                t("Captures that meet the rule are kept, all the others are released",
                  "Les captures qui respectent la règle sont gardées, toutes les autres relâchées")
            else t("Captures that meet the rule are released, all the others are kept",
                   "Les captures qui respectent la règle sont relâchées, toutes les autres gardées")
        }
        return y + 16
    }

    // ── the rule ───────────────────────────────────────────────────────────────────

    private fun renderRule(context: DrawContext, f: CaptureFilter, mouseX: Int, mouseY: Int,
                           ruleTop: Int, ruleBottom: Int, w: Int) {
        val viewH = ruleBottom - ruleTop
        ruleScroll = ruleScroll.coerceIn(0, maxOf(0, ruleContentH - viewH))
        hits += Hit(detailX, ruleTop, w, viewH, onScroll = { amount, _ ->
            commitEdit() // the typing box would otherwise drift out of the area
            ruleScroll = (ruleScroll - (amount * 16).toInt()).coerceIn(0, maxOf(0, ruleContentH - viewH))
        })

        scissor(context, detailX - 1, ruleTop, detailRight + 1, ruleBottom)
        var y = ruleTop - ruleScroll
        val start = y
        val rowW = w - 6

        if (f.groups.isEmpty()) f.groups.add(Group())
        val groups = f.groups.toList()
        for ((gi, g) in groups.withIndex()) {
            if (gi > 0) {
                // the OR between two boxes
                val label = t("OR", "OU")
                val lw = textRenderer.getWidth(label) + 10
                val cx = detailX + rowW / 2
                context.fill(detailX + 8, y + 7, cx - lw / 2 - 3, y + 8, UiKit.BORDER_DIM)
                context.fill(cx + lw / 2 + 3, y + 7, detailX + rowW - 8, y + 8, UiKit.BORDER_DIM)
                UiKit.chip(context, textRenderer, cx - lw / 2, y + 2, label, UiKit.accent())
                y += 16
            }
            val boxH = g.conditions.size * COND_H + CTRL_H + 8
            context.fill(detailX, y, detailX + rowW, y + boxH, UiKit.withAlpha(UiKit.SURFACE_SUNKEN, 0xCC))
            UiKit.border(context, detailX, y, rowW, boxH, UiKit.BORDER_DIM)
            var ry = y + 3
            for ((ci, c) in g.conditions.toList().withIndex()) {
                renderCondition(context, f, g, c, ci, detailX + 3, ry, rowW - 6, mouseX, mouseY, ruleTop, ruleBottom)
                ry += COND_H
            }
            // + AND
            val andLabel = if (g.conditions.isEmpty()) t("+ Add a condition", "+ Ajouter une condition")
                           else t("+ AND condition", "+ Condition ET")
            val aw = textRenderer.getWidth(andLabel) + 10
            val ax = detailX + 4
            val ay = ry + 2
            val aHov = inside(mouseX, mouseY, ax, ay, aw, CTRL_H - 2) && mouseY in ruleTop until ruleBottom
            context.drawText(textRenderer, andLabel, ax + 5, ay + 2, if (aHov) UiKit.accent() else UiKit.TEXT_MUTED, false)
            if (aHov) context.fill(ax + 5, ay + 11, ax + aw - 5, ay + 12, UiKit.accent())
            hits += Hit(ax, ay, aw, CTRL_H - 2, ruleTop, ruleBottom, onClick = {
                g.conditions.add(defaultCondition(g)); CaptureFilters.changed()
            })
            if (aHov) hint = t("Adds a line that must hold as well (AND)", "Ajoute une ligne qui doit aussi être vraie (ET)")
            y += boxH
        }

        // + OR
        y += 6
        val orLabel = t("+ OR group", "+ Groupe OU")
        val ow = textRenderer.getWidth(orLabel) + 14
        val oHov = inside(mouseX, mouseY, detailX, y, ow, CTRL_H) && mouseY in ruleTop until ruleBottom
        UiKit.button(context, textRenderer, detailX, y, ow, CTRL_H, orLabel, oHov)
        hits += Hit(detailX, y, ow, CTRL_H, ruleTop, ruleBottom, onClick = {
            f.groups.add(Group(mutableListOf(Condition(Field.IV_TOTAL.id, ">=", 80)))); CaptureFilters.changed()
        })
        if (oHov) hint = t("Adds another box: a capture is accepted when any box holds (OR)",
                           "Ajoute une autre boîte : une capture est acceptée si l'une des boîtes est vraie (OU)")
        y += CTRL_H + 4

        context.disableScissor()
        ruleContentH = y - start
        UiKit.scrollbar(context, detailRight - 3, ruleTop, ruleBottom, ruleContentH, ruleScroll)
    }

    private fun defaultCondition(g: Group): Condition {
        val used = g.conditions.map { it.field }.toSet()
        return when {
            Field.IV_TOTAL.id !in used -> Condition(Field.IV_TOTAL.id, ">=", 80)
            else -> Field.IVS.firstOrNull { it.id !in used }?.let { Condition(it.id, "=", 31) }
                ?: Condition(Field.HIDDEN_ABILITY.id, "=", 1)
        }
    }

    private fun renderCondition(context: DrawContext, f: CaptureFilter, g: Group, c: Condition, index: Int,
                                x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int, clipTop: Int, clipBottom: Int) {
        val field = Field.of(c.field)
        val inView = mouseY in clipTop until clipBottom
        fun hov(hx: Int, hw: Int) = inView && inside(mouseX, mouseY, hx, y, hw, CTRL_H)

        // IF / AND
        val conn = if (index == 0) t("IF", "SI") else t("AND", "ET")
        context.drawText(textRenderer, conn, x + 2, y + 3, UiKit.TEXT_FAINT, false)
        var cx = x + 22

        // NOT
        val notW = textRenderer.getWidth(t("NOT", "NON")) + 8
        val notHov = hov(cx, notW)
        if (c.not) {
            context.fill(cx, y, cx + notW, y + CTRL_H, UiKit.withAlpha(UiKit.DANGER, 0x40))
            UiKit.border(context, cx, y, notW, CTRL_H, UiKit.DANGER)
        } else {
            context.fill(cx, y, cx + notW, y + CTRL_H, UiKit.SURFACE)
            UiKit.border(context, cx, y, notW, CTRL_H, if (notHov) UiKit.withAlpha(UiKit.accent(), 0xAA) else UiKit.BORDER_DIM)
        }
        context.drawText(textRenderer, t("NOT", "NON"), cx + 4, y + 3,
            if (c.not) UiKit.DANGER else if (notHov) UiKit.TEXT_MUTED else UiKit.TEXT_FAINT, false)
        hits += Hit(cx, y, notW, CTRL_H, clipTop, clipBottom, onClick = { c.not = !c.not; CaptureFilters.changed() })
        if (notHov) hint = t("NOT turns the line around: \"NOT Speed IV = 31\" means \"Speed IV ≠ 31\"",
                             "NON inverse la ligne : « NON IV Vitesse = 31 » veut dire « IV Vitesse ≠ 31 »")
        cx += notW + 4

        val delW = 12
        val opW = 22
        // An ability some of the filter's Pokémon only get by evolving: marked on its line.
        val carried = if (field == Field.ABILITY && c.text.isNotBlank()) CaptureFilters.carriedCases(f, c.text) else emptyList()
        val evoW = if (carried.isEmpty()) 0 else 14
        val avail = w - (cx - x) - delW - 4 - evoW
        val fieldW = (avail * 0.48).toInt().coerceIn(70, 120)
        val valueW = avail - fieldW - opW - 8

        // field
        val fHov = hov(cx, fieldW)
        dropButton(context, cx, y, fieldW, field.label(), fHov)
        val fx = cx
        hits += Hit(cx, y, fieldW, CTRL_H, clipTop, clipBottom, onClick = {
            openMenu(fx, y + CTRL_H, maxOf(fieldW, 110), Field.entries.filter { it != Field.ABILITY || !f.all }.map { it.id to it.label() }, c.field) { id ->
                if (id != c.field) { resetFor(c, Field.of(id), f); CaptureFilters.changed() }
            }
        })
        if (fHov) hint = t("What this line looks at", "Ce que cette ligne regarde")
        cx += fieldW + 4

        // operator: numbers only; the others read "="
        if (field.kind == Kind.NUMBER) {
            val oHov = hov(cx, opW)
            dropButton(context, cx, y, opW, CaptureFilters.opSymbol(c.op), oHov, arrow = false)
            val ox = cx
            hits += Hit(cx, y, opW, CTRL_H, clipTop, clipBottom, onClick = {
                openMenu(ox, y + CTRL_H, 96, CaptureFilters.OPS.map { it to opLabel(it) }, c.op) { op ->
                    c.op = op; CaptureFilters.changed()
                }
            })
            if (oHov) hint = t("Comparison", "Comparaison")
        } else {
            context.drawText(textRenderer, "=", cx + (opW - textRenderer.getWidth("=")) / 2, y + 3, UiKit.TEXT_MUTED, false)
        }
        cx += opW + 4

        // value
        when (field.kind) {
            Kind.NUMBER -> renderNumber(context, c, field, cx, y, valueW, mouseX, mouseY, clipTop, clipBottom)
            Kind.BOOL -> {
                val vHov = hov(cx, valueW)
                dropButton(context, cx, y, valueW, CaptureFilters.valueText(c), vHov)
                val vx = cx
                hits += Hit(cx, y, valueW, CTRL_H, clipTop, clipBottom, onClick = {
                    openMenu(vx, y + CTRL_H, valueW, listOf("1" to CaptureFilters.yesNo(1), "0" to CaptureFilters.yesNo(0)),
                        c.value.toString()) { v -> c.value = v.toInt(); CaptureFilters.changed() }
                })
            }
            Kind.CHOICE -> {
                val vHov = hov(cx, valueW)
                val missing = c.text.isBlank()
                dropButton(context, cx, y, valueW, CaptureFilters.valueText(c), vHov, warn = missing)
                val vx = cx
                hits += Hit(cx, y, valueW, CTRL_H, clipTop, clipBottom, onClick = {
                    openMenu(vx, y + CTRL_H, maxOf(valueW, 130), choices(field, f), c.text) { v ->
                        c.text = v; CaptureFilters.changed()
                    }
                })
            }
        }
        cx += valueW + 4

        if (carried.isNotEmpty()) {
            val eHov = hov(cx - 2, evoW)
            context.fill(cx - 2, y, cx - 2 + evoW - 2, y + CTRL_H, UiKit.withAlpha(EVO_COLOR, if (eHov) 0x55 else 0x30))
            UiKit.border(context, cx - 2, y, evoW - 2, CTRL_H, EVO_COLOR)
            context.drawText(textRenderer, "⇄", cx + 1, y + 3, EVO_COLOR, false)
            if (eHov) hint = carriedSentence(carried.first()) +
                if (carried.size > 1) t(" (and ${carried.size - 1} more, see below)", " (et ${carried.size - 1} autre(s), voir en dessous)") else ""
            cx += evoW
        }

        // delete the line
        val dHov = hov(cx, delW)
        context.drawText(textRenderer, "✕", cx + 2, y + 3, if (dHov) UiKit.DANGER else UiKit.TEXT_FAINT, false)
        hits += Hit(cx, y, delW, CTRL_H, clipTop, clipBottom, onClick = {
            commitEdit()
            g.conditions.remove(c)
            if (g.conditions.isEmpty() && f.groups.size > 1) f.groups.remove(g)
            CaptureFilters.changed()
        })
        if (dHov) hint = t("Removes this line", "Supprime cette ligne")
    }

    private fun opLabel(op: String): String = CaptureFilters.opSymbol(op) + "  " + when (op) {
        ">=" -> t("at least", "au moins")
        ">" -> t("more than", "plus que")
        "=" -> t("exactly", "exactement")
        "!=" -> t("anything but", "tout sauf")
        "<" -> t("less than", "moins que")
        else -> t("at most", "au plus")
    }

    private fun renderNumber(context: DrawContext, c: Condition, field: Field, x: Int, y: Int, w: Int,
                             mouseX: Int, mouseY: Int, clipTop: Int, clipBottom: Int) {
        val inView = mouseY in clipTop until clipBottom
        val btn = 11
        val boxX = x + btn + 1
        val boxW = w - 2 * btn - 2
        fun step(d: Int) { commitEdit(); c.value = (c.value + d).coerceIn(field.min, field.max); CaptureFilters.changed() }

        for ((bx, sign) in listOf(x to -1, x + w - btn to 1)) {
            val h = inView && inside(mouseX, mouseY, bx, y, btn, CTRL_H)
            UiKit.button(context, textRenderer, bx, y, btn, CTRL_H, if (sign < 0) "−" else "+", h)
            hits += Hit(bx, y, btn, CTRL_H, clipTop, clipBottom, onClick = { step(sign) })
        }

        val boxHov = inView && inside(mouseX, mouseY, boxX, y, boxW, CTRL_H)
        context.fill(boxX, y, boxX + boxW, y + CTRL_H, UiKit.SURFACE)
        UiKit.border(context, boxX, y, boxW, CTRL_H, if (boxHov || editing === c) UiKit.withAlpha(UiKit.accent(), 0xCC) else UiKit.BORDER_DIM)
        if (editing === c) {
            numField.x = boxX + 3; numField.y = y + 3; numField.width = boxW - 6
            numField.setDrawsBackground(false)
        } else {
            val text = CaptureFilters.valueText(c)
            context.drawText(textRenderer, text, boxX + (boxW - textRenderer.getWidth(text)) / 2, y + 3, UiKit.TEXT, false)
        }
        hits += Hit(boxX, y, boxW, CTRL_H, clipTop, clipBottom,
            onScroll = { amount, shift -> step((if (amount > 0) 1 else -1) * if (shift) 5 else 1) },
            onClick = { startEdit(c) })
        if (boxHov) hint = if (field == Field.IV_TOTAL)
            t("Share of the maximum IVs (186), as on Tropimon's capture card: 0 to 100 %",
              "Part du maximum des IV (186), comme sur la carte de capture Tropimon : 0 à 100 %")
            else t("IV from 0 to 31 · click to type, wheel to adjust", "IV de 0 à 31 · clic pour saisir, molette pour ajuster")
    }

    private fun dropButton(context: DrawContext, x: Int, y: Int, w: Int, label: String, hov: Boolean,
                           arrow: Boolean = true, warn: Boolean = false) {
        context.fill(x, y, x + w, y + CTRL_H, if (hov) UiKit.SURFACE_HOVER else UiKit.SURFACE)
        UiKit.border(context, x, y, w, CTRL_H, when {
            warn -> UiKit.GOLD
            hov -> UiKit.withAlpha(UiKit.accent(), 0xCC)
            else -> UiKit.BORDER_DIM
        })
        val textW = w - (if (arrow) 14 else 6)
        val shown = fit(label, textW)
        val tx = if (arrow) x + 4 else x + (w - textRenderer.getWidth(shown)) / 2
        context.drawText(textRenderer, shown, tx, y + 3, if (warn) UiKit.GOLD else UiKit.TEXT, false)
        if (arrow) context.drawText(textRenderer, "▾", x + w - 9, y + 3, UiKit.TEXT_FAINT, false)
    }

    /** Field changed: give the line a value that makes sense for it. */
    private fun resetFor(c: Condition, field: Field, f: CaptureFilter) {
        commitEdit()
        c.field = field.id
        c.not = false
        when (field.kind) {
            Kind.NUMBER -> { c.op = if (field == Field.IV_TOTAL) ">=" else "="; c.value = if (field == Field.IV_TOTAL) 80 else 31; c.text = "" }
            Kind.BOOL -> { c.op = "="; c.value = 1; c.text = "" }
            Kind.CHOICE -> { c.op = "="; c.value = 0; c.text = choices(field, f).firstOrNull()?.first ?: "" }
        }
    }

    private fun choices(field: Field, f: CaptureFilter): List<Pair<String, String>> = when (field) {
        Field.ABILITY -> {
            val list = mutableListOf<Pair<String, String>>()
            for (m in f.members()) try {
                for (a in CaptureFilters.species(m)?.abilities ?: emptyList()) {
                    val id = a.template.name
                    if (list.any { it.first == id }) continue
                    val name = Text.translatable(a.template.displayName).string
                    list += id to (if (a is HiddenAbility) "$name ${t("(hidden)", "(caché)")}" else name)
                }
            } catch (_: Exception) {}
            list
        }
        else -> CaptureFilters.GENDERS.map { it to CaptureFilters.genderName(it) }
    }

    // ── the sentence and the party preview ─────────────────────────────────────────

    private fun summaryLines(f: CaptureFilter, w: Int): List<Pair<net.minecraft.text.OrderedText, Int>> {
        val out = mutableListOf<Pair<net.minecraft.text.OrderedText, Int>>()
        val color = if (f.hasConditions()) UiKit.TEXT else UiKit.GOLD
        for (l in textRenderer.wrapLines(Text.literal(CaptureFilters.describe(f)), w)) out += l to color
        if (!f.enabled) {
            out += Text.literal(t("This filter is off: it does nothing.", "Ce filtre est désactivé : il n'agit pas.")).asOrderedText() to UiKit.TEXT_FAINT
        } else if (f.hasConditions() && !f.isComplete()) {
            for (l in textRenderer.wrapLines(Text.literal(t(
                "⚠ A line has no value yet: the filter does nothing until it is filled in.",
                "⚠ Une ligne n'a pas encore de valeur : le filtre n'agit pas tant qu'elle n'est pas remplie.")), w)) out += l to UiKit.GOLD
        }
        for (l in textRenderer.wrapLines(Text.literal(t(
            "✦ Shinies, legendaries, mythicals and over 90 % IV: always kept.",
            "✦ Shiny, légendaires, fabuleux et plus de 90 % d'IV : toujours gardés.")), w)) out += l to UiKit.GOLD
        for (l in textRenderer.wrapLines(Text.literal(priorityNote(f)), w)) out += l to UiKit.TEXT_MUTED
        // abilities that count because the Pokémon gets the wanted one by evolving
        for (case in CaptureFilters.carriedCases(f).take(3)) {
            for (l in textRenderer.wrapLines(Text.literal("⇄ " + carriedSentence(case)), w)) out += l to EVO_COLOR
        }
        // what the filter would do with the ones already in the party
        val party = try { CobblemonClient.storage.party.slots.filterNotNull() } catch (_: Exception) { emptyList() }
        for (p in party.filter { CaptureFilters.forSpecies(it.species.resourceIdentifier.path)?.id == f.id }.take(3)) {
            val pct = CaptureFilters.ivPercent(CaptureFilters.ivsOf(p).sum())
            val (word, c) = when {
                !f.isActive() -> t("untouched", "non concerné") to UiKit.TEXT_FAINT
                CaptureFilters.verdict(f, CaptureFilters.subjectOf(p)) != CaptureFilters.Verdict.RELEASE ->
                    t("kept", "gardé") to 0xFF55FF55.toInt()
                !CaptureFilters.cardOffersRelease(p) -> t("kept (no release)", "gardé (non relâchable)") to UiKit.GOLD
                else -> t("released", "relâché") to 0xFFFFAA55.toInt()
            }
            val line = t("In your party: ", "Dans l'équipe : ") +
                "${p.species.translatedName.string} ${t("Lv.", "Nv")} ${p.level} · IV $pct % → $word"
            out += Text.literal(fit(line, w)).asOrderedText() to c
        }
        return out
    }

    /** "Goélise with Hydratation counts as Crachin: it gets it by evolving into Bekipan." */
    private fun carriedSentence(c: CaptureFilters.Carried): String {
        val who = CaptureFilters.speciesName(c.species)
        val has = CaptureFilters.abilityName(c.ability)
        val wanted = CaptureFilters.abilityName(c.wanted)
        val into = CaptureFilters.speciesName(c.into)
        return t("$who with $has counts as $wanted: it gets it by evolving into $into.",
                 "$who avec $has compte comme $wanted : il l'obtient en évoluant en $into.")
    }

    /**
     * Where this filter stands when several cover the same Pokémon: which of its Pokémon
     * answer to a narrower filter instead, or the rule itself when nothing overlaps.
     */
    private fun priorityNote(f: CaptureFilter): String {
        if (!f.enabled) return t("Off: the next filter in line decides instead.", "Désactivé : c'est le filtre suivant dans l'ordre qui décide.")
        if (f.all) {
            val named = CaptureFilters.all().count { !it.all && it.enabled }
            return if (named == 0) t("\u2261 Applies to every capture.", "\u2261 S'applique à toutes les captures.")
            else t("\u2261 Priority: the $named filter(s) naming a Pokémon come first.",
                   "\u2261 Priorité : les $named filtre(s) qui nomment un Pokémon passent avant.")
        }
        val overruled = f.members().filter { CaptureFilters.forSpecies(it)?.id != f.id }
        if (overruled.isNotEmpty()) {
            val names = overruled.take(3).joinToString(", ") { CaptureFilters.speciesName(it) } +
                if (overruled.size > 3) "…" else ""
            return t("\u2261 Priority: $names follow(s) a more precise filter.",
                     "\u2261 Priorité : $names suit un filtre plus précis.")
        }
        return t("\u2261 Priority: 1 Pokémon > group > all Pokémon.", "\u2261 Priorité : 1 Pokémon > groupe > tous les Pokémon.")
    }

    private fun renderSummary(context: DrawContext, lines: List<Pair<net.minecraft.text.OrderedText, Int>>,
                              y: Int, h: Int, w: Int) {
        context.fill(detailX, y, detailRight, y + h, UiKit.withAlpha(UiKit.accent(), 0x14))
        UiKit.border(context, detailX, y, w, h, UiKit.withAlpha(UiKit.accent(), 0x55))
        var ly = y + 5
        for ((l, c) in lines) { context.drawText(textRenderer, l, detailX + 6, ly, c, false); ly += 10 }
    }

    // ── sharing ────────────────────────────────────────────────────────────────────

    private fun showFlash(text: String) {
        flash = text
        flashUntil = System.currentTimeMillis() + FLASH_MS
    }

    private fun filtersWord(n: Int) = if (n == 1) t("1 filter", "1 filtre") else t("$n filters", "$n filtres")

    /** Puts the code of [list] in the clipboard. [key] is the button that says so. */
    private fun export(list: List<CaptureFilter>, key: String) {
        commitEdit()
        if (list.isEmpty()) { showFlash(t("No filter to export yet.", "Aucun filtre à exporter pour l'instant.")); return }
        val code = CaptureFilters.exportCode(list)
        client?.keyboard?.clipboard = code
        copied = key
        copiedUntil = System.currentTimeMillis() + COPIED_MS
        val what = filtersWord(list.size)
        showFlash(if (code.length <= CaptureFilters.CHAT_LIMIT)
            t("Code copied ($what): paste it with Ctrl+V in the chat or on Discord",
              "Code copié ($what) : collez-le avec Ctrl+V dans le chat ou sur Discord")
        else t("Code copied ($what, ${code.length} characters: too long for the chat, paste it on Discord)",
               "Code copié ($what, ${code.length} caractères : trop long pour le chat, collez-le sur Discord)"))
    }

    private fun clipboard(): String = try { client?.keyboard?.clipboard ?: "" } catch (_: Exception) { "" }

    /** Opens the import window on [code], or on the clipboard when it holds one. */
    private fun openImport(code: String? = null) {
        commitEdit()
        menu = null
        importOpen = true
        importScroll = 0
        (code ?: CaptureFilters.findCode(clipboard()))?.let { importField.text = it }
        importField.visible = true
        importField.setCursorToStart(false)
        setFocused(importField)
    }

    private fun closeImport() {
        importOpen = false
        importField.visible = false
        importField.isFocused = false
        setFocused(null)
    }

    private fun paste() {
        val clip = clipboard()
        importField.text = CaptureFilters.findCode(clip) ?: clip.trim().take(200_000)
        importField.setCursorToStart(false)
        setFocused(importField)
    }

    private fun runImport() {
        // read again: the filters shown in the preview stay out of the list
        val decoded = CaptureFilters.decodeCode(importField.text)
        if (decoded.error != null || decoded.filters.isEmpty()) return
        val result = CaptureFilters.importFilters(decoded.filters)
        importField.text = ""
        closeImport()
        search.text = ""
        select(result.firstOrNull()?.first)
        val replaced = result.count { it.second }
        showFlash(when {
            result.size == 1 && replaced == 1 -> t("Filter imported: ${CaptureFilters.displayName(result[0].first)} (it replaces yours)",
                "Filtre importé : ${CaptureFilters.displayName(result[0].first)} (il remplace le vôtre)")
            result.size == 1 -> t("Filter imported: ${CaptureFilters.displayName(result[0].first)}",
                "Filtre importé : ${CaptureFilters.displayName(result[0].first)}")
            replaced > 0 -> t("${result.size} filters imported, $replaced of them replacing yours",
                "${result.size} filtres importés, dont $replaced qui remplacent les vôtres")
            else -> t("${result.size} filters imported", "${result.size} filtres importés")
        })
    }

    /**
     * The import window: the code, what it holds — each filter with what it does, and
     * whether it is new or replaces one of the player's — and the button that adds them.
     * Nothing changes before that button.
     */
    private fun renderImport(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val text = importField.text
        if (text != importSeen) {
            importSeen = text
            importDecoded = if (text.isBlank()) null else CaptureFilters.decodeCode(text)
            importScroll = 0
        }
        val decoded = importDecoded
        val filters = decoded?.takeIf { it.error == null }?.filters.orEmpty()

        context.matrices.push()
        context.matrices.translate(0f, 0f, 300f)
        context.fill(0, 0, width, height, 0x80000000.toInt())
        // a click beside the window closes it; one on the window itself does nothing
        hits += Hit(0, 0, width, height, onClick = { closeImport() })
        val mw = (pw - 40).coerceIn(260, 400).coerceAtMost(width - 8)
        val mh = (bottom - top - 24).coerceIn(150, 260)
        val mx = (width - mw) / 2
        val my = (height - mh) / 2
        hits += Hit(mx, my, mw, mh, onClick = {})
        UiKit.panel(context, mx, my, mw, mh)
        UiKit.header(context, textRenderer, mx, my, mw, t("Import filters", "Importer des filtres"))
        val closeX = mx + mw - 12
        UiKit.closeButton(context, textRenderer, closeX, my + 4, mouseX, mouseY)
        hits += Hit(closeX - 2, my + 2, 12, 13, onClick = { closeImport() })

        // the code
        val pasteLabel = t("Paste", "Coller")
        val pasteW = textRenderer.getWidth(pasteLabel) + 14
        val fy = my + 26
        val fieldW = mw - 16 - pasteW - 4
        UiKit.placeField(importField, mx + 8, fy, fieldW)
        UiKit.fieldBackground(context, importField)
        importField.render(context, mouseX, mouseY, delta)
        val pasteX = mx + 8 + fieldW + 4
        val pHov = inside(mouseX, mouseY, pasteX, fy, pasteW, 16)
        UiKit.button(context, textRenderer, pasteX, fy, pasteW, 16, pasteLabel, pHov)
        hits += Hit(pasteX, fy, pasteW, 16, onClick = { paste() })

        // the buttons, and above them the note on replaced filters
        val lx = mx + 8
        val lw = mw - 16
        val by = my + mh - 22
        val replacing = filters.count { CaptureFilters.sameScope(it) != null }
        val noteLines = if (replacing > 0) textRenderer.wrapLines(Text.literal(t(
            "⚠ A filter for exactly the same Pokémon as one of yours replaces its rule.",
            "⚠ Un filtre pour exactement les mêmes Pokémon qu'un des vôtres remplace sa règle.")), lw) else emptyList()
        val listBottom = by - 4 - noteLines.size * 10
        var ny = listBottom + 2
        for (l in noteLines) { context.drawText(textRenderer, l, lx, ny, UiKit.GOLD, false); ny += 10 }

        val ok = filters.isNotEmpty()
        val goLabel = if (ok) t("Import (${filters.size})", "Importer (${filters.size})") else t("Import", "Importer")
        val goW = textRenderer.getWidth(goLabel) + 20
        val goX = mx + mw - 8 - goW
        val goHov = ok && inside(mouseX, mouseY, goX, by, goW, 16)
        UiKit.button(context, textRenderer, goX, by, goW, 16, goLabel, goHov, active = ok)
        if (!ok) context.fill(goX + 1, by + 1, goX + goW - 1, by + 15, 0x70000000)
        if (ok) hits += Hit(goX, by, goW, 16, onClick = { runImport() })
        val cancelLabel = t("Cancel", "Annuler")
        val cancelW = textRenderer.getWidth(cancelLabel) + 16
        val cancelX = goX - 4 - cancelW
        UiKit.button(context, textRenderer, cancelX, by, cancelW, 16, cancelLabel, inside(mouseX, mouseY, cancelX, by, cancelW, 16))
        hits += Hit(cancelX, by, cancelW, 16, onClick = { closeImport() })

        // what the code holds
        val top0 = fy + 24
        fun paragraph(s: String, color: Int) {
            var y = top0
            for (l in textRenderer.wrapLines(Text.literal(s), lw)) { context.drawText(textRenderer, l, lx, y, color, false); y += 10 }
        }
        when {
            decoded == null -> paragraph(t(
                "Copy the code someone shared with you (it starts with HBF1:), then paste it here. You will see what it holds; nothing changes before you click Import.",
                "Copiez le code qu'on vous a partagé (il commence par HBF1:), puis collez-le ici. Vous verrez ce qu'il contient ; rien ne change avant de cliquer sur Importer."),
                UiKit.TEXT_MUTED)
            decoded.error != null -> paragraph(decoded.error, UiKit.DANGER)
            else -> {
                context.drawText(textRenderer, t("This code holds ${filtersWord(filters.size)}:", "Ce code contient ${filtersWord(filters.size)} :"),
                    lx, top0, UiKit.TEXT_MUTED, false)
                val rowsTop = top0 + 12
                val viewH = listBottom - rowsTop
                val contentH = filters.size * IMPORT_ROW_H
                importScroll = importScroll.coerceIn(0, maxOf(0, contentH - viewH))
                hits += Hit(lx, rowsTop, lw, viewH, onScroll = { amount, _ ->
                    importScroll = (importScroll - (amount * IMPORT_ROW_H).toInt()).coerceIn(0, maxOf(0, contentH - viewH))
                })
                context.fill(lx - 1, rowsTop, lx + lw + 1, listBottom, UiKit.withAlpha(UiKit.SURFACE_SUNKEN, 0xCC))
                scissor(context, lx, rowsTop, lx + lw, listBottom)
                var y = rowsTop - importScroll
                for (f in filters) {
                    if (y + IMPORT_ROW_H >= rowsTop && y <= listBottom) {
                        drawImportRow(context, f, lx, y, lw - 4)
                        if (inside(mouseX, mouseY, lx, y, lw, IMPORT_ROW_H) && mouseY in rowsTop until listBottom) tooltip = importTooltip(f)
                    }
                    y += IMPORT_ROW_H
                }
                context.disableScissor()
                UiKit.scrollbar(context, lx + lw - 3, rowsTop, listBottom, contentH, importScroll)
            }
        }
        context.matrices.pop()
    }

    private fun drawImportRow(context: DrawContext, f: CaptureFilter, x: Int, y: Int, w: Int) {
        if (f.all) context.drawText(textRenderer, "✱", x + 7, y + 8, UiKit.accentBright(), true)
        else drawSprite(context, f.species, x + 1, y + 2, 20, alpha = f.enabled)
        val replaces = CaptureFilters.sameScope(f) != null
        val tag = if (replaces) t("Replaces yours", "Remplace le vôtre") else t("New", "Nouveau")
        val tagW = textRenderer.getWidth(tag) + 8
        UiKit.chip(context, textRenderer, x + w - tagW, y + 2, tag, if (replaces) UiKit.GOLD else NEW_COLOR)
        val name = CaptureFilters.displayName(f) + if (!f.enabled) t(" (off)", " (désactivé)") else ""
        context.drawText(textRenderer, fit(name, w - 26 - tagW - 4), x + 24, y + 3, if (f.enabled) UiKit.TEXT else UiKit.TEXT_FAINT, true)
        val color = if (f.mode == CaptureFilters.MODE_RELEASE) RELEASE_COLOR else UiKit.TEXT_MUTED
        context.drawText(textRenderer, fit(CaptureFilters.describe(f), w - 26), x + 24, y + 13, color, false)
    }

    /** The whole of an imported filter: its Pokémon and its rule, untruncated. */
    private fun importTooltip(f: CaptureFilter): List<net.minecraft.text.OrderedText> {
        val out = mutableListOf<net.minecraft.text.OrderedText>()
        val who = if (f.all) t("All Pokémon", "Tous les Pokémon")
                  else f.members().joinToString(", ") { CaptureFilters.speciesName(it) }
        for (l in textRenderer.wrapLines(Text.literal(who).styled { it.withColor(UiKit.TEXT) }, 220)) out += l
        for (l in textRenderer.wrapLines(Text.literal(CaptureFilters.describe(f)).styled { it.withColor(UiKit.TEXT_MUTED) }, 220)) out += l
        CaptureFilters.sameScope(f)?.let { mine ->
            for (l in textRenderer.wrapLines(Text.literal(t("Replaces: ", "Remplace : ") + CaptureFilters.describe(mine))
                .styled { it.withColor(UiKit.GOLD) }, 220)) out += l
        }
        return out
    }

    // ── menus ──────────────────────────────────────────────────────────────────────

    private fun openMenu(x: Int, y: Int, w: Int, items: List<Pair<String, String>>, selected: String?,
                         onPick: (String) -> Unit) {
        commitEdit()
        if (items.isEmpty()) return
        val m = Menu(x, y, w, items, selected, onPick)
        // flip above when it would run off the bottom
        val fy = if (y + m.h > bottom - 2) (y - CTRL_H - m.h).coerceAtLeast(top + 20) else y
        val fx = x.coerceAtMost(px + pw - w - 4)
        menu = Menu(fx, fy, w, items, selected, onPick).also {
            val i = items.indexOfFirst { p -> p.first == selected }
            if (i >= it.visible) it.scroll = (i - it.visible / 2).coerceIn(0, items.size - it.visible)
        }
    }

    private fun renderMenu(context: DrawContext, m: Menu, mouseX: Int, mouseY: Int) {
        context.matrices.push()
        context.matrices.translate(0f, 0f, 400f)
        context.fill(m.x + 2, m.y + 2, m.x + m.w + 2, m.y + m.h + 2, 0x60000000)
        context.fill(m.x, m.y, m.x + m.w, m.y + m.h, UiKit.PANEL_BOTTOM or 0xFF000000.toInt())
        UiKit.border(context, m.x, m.y, m.w, m.h, UiKit.withAlpha(UiKit.accent(), 0xCC))
        for (i in 0 until m.visible) {
            val idx = i + m.scroll
            val (value, label) = m.items.getOrNull(idx) ?: break
            val iy = m.y + 2 + i * 12
            val hov = inside(mouseX, mouseY, m.x, iy, m.w, 12)
            if (hov) UiKit.rowHighlight(context, m.x + 1, iy, m.w - 2, 12)
            val color = if (value == m.selected) UiKit.accent() else if (hov) UiKit.TEXT else UiKit.TEXT_MUTED
            context.drawText(textRenderer, fit(label, m.w - 10), m.x + 5, iy + 2, color, false)
        }
        if (m.items.size > m.visible) {
            UiKit.scrollbar(context, m.x + m.w - 4, m.y + 2, m.y + m.h - 2, m.items.size * 12, m.scroll * 12)
        }
        context.matrices.pop()
    }

    // ── numbers typed in ───────────────────────────────────────────────────────────

    private fun startEdit(c: Condition) {
        commitEdit()
        editing = c
        numField.text = c.value.toString()
        numField.visible = true
        setFocused(numField)
        numField.isFocused = true
        numField.setCursorToEnd(false)
        numField.setSelectionStart(0)
    }

    private fun commitEdit() {
        val c = editing ?: return
        editing = null
        val field = Field.of(c.field)
        numField.text.toIntOrNull()?.let {
            val v = it.coerceIn(field.min, field.max)
            if (v != c.value) { c.value = v; CaptureFilters.changed() }
        }
        numField.isFocused = false
        numField.visible = false
    }

    // ── input ──────────────────────────────────────────────────────────────────────

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (importOpen) {
            // the window's own controls only; the screen under it does not hear the click
            if (importField.isMouseOver(mouseX, mouseY)) {
                setFocused(importField)
                importField.mouseClicked(mouseX, mouseY, button)
                return true
            }
            if (button == 0) for (h in hits.asReversed()) {
                val click = h.onClick ?: continue
                if (h.contains(mouseX, mouseY)) { click(button); return true }
            }
            return true
        }
        val m = menu
        if (m != null) {
            menu = null
            if (mouseX >= m.x && mouseX < m.x + m.w && mouseY >= m.y && mouseY < m.y + m.h) {
                val idx = ((mouseY - m.y - 2) / 12).toInt() + m.scroll
                m.items.getOrNull(idx)?.let { m.onPick(it.first) }
            }
            return true
        }
        if (editing != null && numField.isMouseOver(mouseX, mouseY)) return super.mouseClickedScaled(mouseX, mouseY, button)
        commitEdit()
        if (button == 0) {
            for (h in hits.asReversed()) {
                val click = h.onClick ?: continue
                if (h.contains(mouseX, mouseY)) { click(button); return true }
            }
        }
        return super.mouseClickedScaled(mouseX, mouseY, button)
    }

    override fun mouseScrolledScaled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val m = menu
        if (m != null) {
            m.scroll = (m.scroll - verticalAmount.toInt().coerceIn(-1, 1)).coerceIn(0, maxOf(0, m.items.size - m.visible))
            return true
        }
        val shift = hasShiftDown()
        for (h in hits.asReversed()) {
            val s = h.onScroll ?: continue
            if (h.contains(mouseX, mouseY)) { s(verticalAmount, shift); return true }
        }
        return super.mouseScrolledScaled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (importOpen) {
            when (keyCode) {
                GLFW.GLFW_KEY_ESCAPE -> closeImport()
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> runImport()
                GLFW.GLFW_KEY_TAB -> {}
                else -> importField.keyPressed(keyCode, scanCode, modifiers)
            }
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (menu != null) { menu = null; return true }
            if (editing != null) { editing = null; numField.isFocused = false; numField.visible = false; return true }
        }
        if (editing != null && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_TAB)) {
            commitEdit(); return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean =
        if (importOpen) { importField.charTyped(chr, modifiers); true } else super.charTyped(chr, modifiers)

    private fun openPicker(addTo: CaptureFilter? = null) {
        commitEdit()
        client?.setScreen(SpeciesPickerScreen(this) { species ->
            if (addTo != null) CaptureFilters.addSpecies(addTo, species.resourceIdentifier.path)
            else select(CaptureFilters.create(species))
            search.text = ""
        })
    }

    override fun close() {
        commitEdit()
        client?.setScreen(parent)
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private fun inside(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h

    private fun fit(text: String, maxW: Int): String {
        if (maxW <= 0) return ""
        if (textRenderer.getWidth(text) <= maxW) return text
        val ell = "…"
        return textRenderer.trimToWidth(text, (maxW - textRenderer.getWidth(ell)).coerceAtLeast(0)) + ell
    }
}
