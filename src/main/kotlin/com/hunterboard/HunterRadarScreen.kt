package com.hunterboard

import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * HunterBoard's own PokeRadar screen.
 * Replaces TropiMod's PokeRadarScreen while keeping its server communication.
 * Data is extracted from TropiMod's pool via reflection before this screen opens.
 */
class HunterRadarScreen(
    initialSlots: List<RadarSlot>,
    internal var tropiScreen: Any?
) : Screen(Text.literal("Pokéradar")) {

    data class RadarSlot(val species: Species, val pct: Float)

    /** EV colors for one cell: primary (dominant stat) and optional secondary. */
    private data class EvBorder(val primary: Int, val secondary: Int = 0, val primaryKey: String? = null)

    private var slots: List<RadarSlot> = initialSlots

    // ── Layout constants ──────────────────────────────────────────────────────
    companion object {
        const val COLS      = 10
        const val CELL_W    = 38
        const val CELL_H    = 48    // taller for larger model zone
        const val CELL_GAP  = 3
        const val PADDING   = 8
        const val SB_W      = 7
        val ROW_STRIDE      = CELL_H + CELL_GAP
        val GRID_W          = COLS * CELL_W + (COLS - 1) * CELL_GAP   // 407
        val PANEL_W         = PADDING + GRID_W + PADDING + SB_W + 6   // 436
        const val MODEL_W   = 32
        const val MODEL_H   = 32
        const val BTN_W     = 46
        const val BTN_H     = 14
        const val BTN_GAP   = 2
    }

    // ── Dynamic layout — 30%–80% screen height, vertically centred ───────────
    private val HEADER_H      = 22
    private val panelH        get() = (HEADER_H + 6 + contentH)
        .coerceIn((height * 0.30f).toInt(), (height * 0.80f).toInt())
    private val panelX        get() = (width  - PANEL_W) / 2
    private val panelY        get() = (height - panelH)  / 2
    private val contentTop    get() = panelY + HEADER_H
    private val contentAreaH  get() = panelH - HEADER_H - 6
    private val contentBottom get() = contentTop + contentAreaH

    // ── Scroll ────────────────────────────────────────────────────────────────
    private val rowCount  get() = (displayIndices.size + COLS - 1) / COLS
    private val contentH  get() = maxOf(1, rowCount * ROW_STRIDE - CELL_GAP)
    private val maxScroll get() = maxOf(0, contentH - contentAreaH)
    private var scrollOffset = 0

    private var sbDragging        = false
    private var sbDragStartY      = 0
    private var sbDragStartOffset = 0

    // ── Model widgets ─────────────────────────────────────────────────────────
    private val modelWidgets = mutableListOf<ModelWidget?>()

    // ── Filter ────────────────────────────────────────────────────────────────
    // evFilter: -1 = all, 0–5 = show only slots whose primary EV matches EV_ORDER[evFilter]
    private var evFilter = -1
    // display order: maps display position → original slot index in `slots`
    private var displayIndices: List<Int> = emptyList()

    // ── Hover ─────────────────────────────────────────────────────────────────
    private var hoveredSlot = -1

    // ── EV borders & Cobblemon rarity colours (computed on init / refresh) ──────
    private var evBorders: List<EvBorder> = emptyList()

    /** Biome ID (e.g. "minecraft:jungle") and RegistryEntry<Biome>, captured at construction. */
    private val biomeCapture: Pair<String?, Any?> = run {
        try {
            val w = MinecraftClient.getInstance().world ?: return@run null to null
            val p = MinecraftClient.getInstance().player ?: return@run null to null
            val entry = w.getBiome(p.blockPos)
            val id = w.registryManager.get(net.minecraft.registry.RegistryKeys.BIOME)
                .getId(entry.value())?.toString()
            id to entry
        } catch (_: Exception) { null to null }
    }

    /** Per-slot colour derived from Cobblemon's spawn pool + current biome. */
    private var slotColors: List<Int> = emptyList()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun init() {
        super.init()
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)
        if (evBorders.size != slots.size) {
            evBorders = slots.map { resolveEvBorder(it.species) }
            slotColors = slots.map { resolveRarityColor(it.species) }
        }
        if (displayIndices.isEmpty()) recomputeDisplay()
        buildModels()
    }

    /** Called by RadarEnhancer when a refresh response arrives — updates data in-place. */
    fun refreshData(newSlots: List<RadarSlot>) {
        slots = newSlots
        scrollOffset = 0
        hoveredSlot = -1
        evBorders = slots.map { resolveEvBorder(it.species) }
        slotColors = slots.map { resolveRarityColor(it.species) }
        modelWidgets.clear()
        buildModels()
        recomputeDisplay()
    }

    private fun recomputeDisplay() {
        var indices = slots.indices.toList()

        // Apply EV filter (keep only slots whose dominant EV stat matches)
        if (evFilter >= 0 && evBorders.size == slots.size) {
            val filterKey = RadarEnhancer.EV_ORDER.getOrNull(evFilter)
            if (filterKey != null)
                indices = indices.filter { i -> evBorders.getOrElse(i) { EvBorder(0) }.primaryKey == filterKey }
        }

        // Default order: by spawn % descending
        indices = indices.sortedByDescending { slots[it].pct }

        displayIndices = indices
        scrollOffset = 0
    }

    override fun close() {
        RadarEnhancer.currentHunterScreen = null
        super.close()
    }

    private fun buildModels() {
        if (modelWidgets.size == slots.size) return
        modelWidgets.clear()
        for (slot in slots) {
            try {
                modelWidgets += ModelWidget(
                    pX = 0, pY = 0,
                    pWidth = MODEL_W, pHeight = MODEL_H,
                    pokemon = RenderablePokemon(slot.species, emptySet()),
                    baseScale = 0.9f,
                    rotationY = 325f,
                    offsetY = -8.0,   // same as PokemonDetailScreen — shows full model
                    playCryOnClick = false
                )
            } catch (_: Exception) { modelWidgets += null }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val pX = panelX; val pY = panelY   // snapshot computed values once per frame
        UiKit.screenDim(context, width, height)

        val gridLeft = pX + PADDING

        // Panel background + border (shared HunterBoard identity)
        UiKit.panel(context, pX, pY, PANEL_W, panelH)

        // ── Header ──────────────────────────────────────────────────────────
        context.fillGradient(pX + 1, pY + 1, pX + PANEL_W - 1, pY + HEADER_H - 2,
            UiKit.withAlpha(UiKit.accent(), 0x28), UiKit.withAlpha(UiKit.accent(), 0x00))
        val title = "${slots.size} apparition${if (slots.size > 1) "s" else ""} possible${if (slots.size > 1) "s" else ""}"
        context.drawText(textRenderer, title, pX + PADDING, pY + 7, UiKit.TEXT, true)

        // Refresh button ↺ (top-right of panel)
        val rbX = pX + PANEL_W - 18; val rbY = pY + 4
        val rbHov = mouseX in rbX..(rbX + 13) && mouseY in rbY..(rbY + 13)
        drawVanillaButton(context, rbX, rbY, 13, 13, rbHov)
        context.drawText(textRenderer, "↺", rbX + 3, rbY + 2, 0xFFFFFFFF.toInt(), false)

        // Separator
        context.fill(pX + 2, pY + HEADER_H - 1, pX + PANEL_W - 2, pY + HEADER_H, UiKit.withAlpha(UiKit.accent(), 0xCC))

        // ── Grid (scissored) ─────────────────────────────────────────────────
        context.enableScissor(pX + 1, contentTop, pX + PADDING + GRID_W + 2, contentBottom)
        hoveredSlot = -1

        for (dispIdx in displayIndices.indices) {
            val idx = displayIndices[dispIdx]   // original slot index
            val col = dispIdx % COLS
            val row = dispIdx / COLS
            val cx = gridLeft + col * (CELL_W + CELL_GAP)
            val cy = contentTop + row * ROW_STRIDE - scrollOffset

            if (cy + CELL_H <= contentTop || cy >= contentBottom) continue

            val hovered = mouseX in cx until cx + CELL_W
                       && mouseY in maxOf(cy, contentTop) until minOf(cy + CELL_H, contentBottom)
            if (hovered) hoveredSlot = idx

            // Cell background
            context.fill(cx, cy, cx + CELL_W, cy + CELL_H,
                if (hovered) 0x55FFFFFF.toInt() else 0x1AFFFFFF.toInt())

            // EV border — dual or solid
            val evb = evBorders.getOrElse(idx) { EvBorder(0) }
            val borderColor = if (evb.primary != 0) evb.primary else 0xFF2A2A44.toInt()
            if (evb.secondary != 0)
                fillBorderDual(context, cx, cy, CELL_W, CELL_H, evb.primary, evb.secondary, 2)
            else
                fillBorderSolid(context, cx, cy, CELL_W, CELL_H, borderColor, 2)

            // Spawn % — centred, coloured by Cobblemon rarity tier (1px margin from top)
            val pct = slots[idx].pct
            val pctStr = "%.2f%%".format(pct)
            val tw = textRenderer.getWidth(pctStr)
            context.drawText(textRenderer, pctStr,
                cx + (CELL_W - tw) / 2, cy + 3,
                slotColors.getOrElse(idx) { UiKit.TEXT }, false)

            // 3D model — larger zone, positioned below the % text
            modelWidgets.getOrNull(idx)?.let { widget ->
                try {
                    widget.x = cx + (CELL_W - MODEL_W) / 2
                    widget.y = cy + 13
                    widget.render(context, mouseX, mouseY, delta)
                } catch (_: Exception) {}
            }
        }
        context.disableScissor()

        // ── Scrollbar ────────────────────────────────────────────────────────
        if (maxScroll > 0) {
            val sbX = pX + PADDING + GRID_W + 5
            val trackY = contentTop + 2; val trackH = contentAreaH - 4
            context.fill(sbX, trackY, sbX + SB_W, trackY + trackH, 0xFF1A1A2A.toInt())
            val thumbH = maxOf(16, (contentAreaH.toFloat() / contentH * trackH).toInt())
            val thumbY = trackY + (scrollOffset.toFloat() / maxScroll * (trackH - thumbH)).toInt()
            val sbHov = mouseX in sbX..(sbX + SB_W)
            context.fill(sbX, thumbY, sbX + SB_W, thumbY + thumbH,
                if (sbHov || sbDragging) UiKit.accent() else 0xFF445566.toInt())
        }

        // ── Sort + Filter buttons (right of panel) ────────────────────────────
        drawSortFilterButtons(context, mouseX, mouseY, pX)

        // ── Switch button — static top-left corner, vanilla MC style ─────────
        drawSwitchButton(context, mouseX, mouseY)

        // ── EV legend below panel ─────────────────────────────────────────────
        drawLegend(context, pY + panelH)

        // ── Tooltip ───────────────────────────────────────────────────────────
        if (hoveredSlot >= 0) {
            context.matrices.push()
            context.matrices.translate(0f, 0f, 400f)
            drawTooltip(context, slots[hoveredSlot].species, mouseX, mouseY)
            context.matrices.pop()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cobblemon spawn-pool rarity colour (biome-aware)
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveRarityColor(species: Species): Int {
        val (biomeId, biomeEntry) = biomeCapture
        return runCatching {
            val all = getCobblemonPoolEntries(species.name.lowercase())
            if (all.isEmpty()) return UiKit.TEXT
            val matching = if (biomeId == null) all
                           else all.filter { entryMatchesBiome(it, biomeId, biomeEntry) }.ifEmpty { all }
            entryBucketColor(matching.maxByOrNull { entryBucketWeight(it) } ?: return UiKit.TEXT)
        }.getOrDefault(UiKit.TEXT)
    }

    /**
     * Loads all PokemonSpawnDetail entries for [speciesName] from Cobblemon's WORLD_SPAWN_POOL.
     * Uses getters (getPokemon/getSpecies) rather than field reflection for reliability.
     */
    private fun getCobblemonPoolEntries(speciesName: String): List<Any> {
        return runCatching {
            val cls = Class.forName("com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools")
            // WORLD_SPAWN_POOL is a static field (@JvmField on the Kotlin object)
            val pool = cls.getDeclaredField("WORLD_SPAWN_POOL").also { it.isAccessible = true }.get(null)
                ?: return emptyList()
            val details = pool.javaClass.getMethod("getDetails").invoke(pool) as? List<*>
                ?: return emptyList()
            details.filterNotNull().filter { detail ->
                runCatching {
                    val getPokemon = detail.javaClass.methods.firstOrNull { it.name == "getPokemon" }
                        ?: return@filter false
                    val pokemon = getPokemon.invoke(detail) ?: return@filter false
                    val s = pokemon.javaClass.getMethod("getSpecies").invoke(pokemon) as? String
                        ?: return@filter false
                    s.lowercase().let { it == speciesName || it.substringAfter(':') == speciesName }
                }.getOrDefault(false)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Returns true if this spawn entry's biomes include [biomeId].
     * Uses SpawnDetail.getValidBiomes() (pre-computed Set<Identifier>) first,
     * then falls back to scanning condition fields for tag-based matches.
     */
    private fun entryMatchesBiome(entry: Any, biomeId: String, biomeEntry: Any?): Boolean {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val validBiomes = entry.javaClass.getMethod("getValidBiomes").invoke(entry) as? Set<*>
            if (!validBiomes.isNullOrEmpty()) {
                return validBiomes.any { it?.toString() == biomeId }
            }
            // validBiomes not pre-computed → check condition fields
            val conds = entry.javaClass.getMethod("getConditions").invoke(entry) as? List<*>
                ?: return true
            if (conds.isEmpty()) return true
            conds.all { cond -> cond == null || conditionAllowsBiome(cond, biomeId, biomeEntry) }
        }.getOrDefault(true)
    }

    private fun conditionAllowsBiome(cond: Any, biomeId: String, biomeEntry: Any?): Boolean {
        return runCatching {
            var cls: Class<*>? = cond.javaClass
            while (cls != null) {
                for (f in cls.declaredFields) {
                    if (!f.name.contains("biome", ignoreCase = true)) continue
                    f.isAccessible = true
                    val biomes = f.get(cond) ?: return true
                    val set = biomes as? Collection<*> ?: return true
                    if (set.isEmpty()) return true
                    return set.any { elem ->
                        val s = elem?.toString() ?: return@any false
                        if (s.startsWith("#")) checkBiomeTag(s.removePrefix("#"), biomeEntry)
                        else s.contains(biomeId, ignoreCase = true)
                    }
                }
                cls = cls.superclass
            }
            true
        }.getOrDefault(true)
    }

    private fun checkBiomeTag(tagId: String, biomeEntry: Any?): Boolean {
        return runCatching {
            val parts = tagId.split(":")
            val id = if (parts.size >= 2) net.minecraft.util.Identifier.of(parts[0], parts[1])
                     else net.minecraft.util.Identifier.of(tagId)
            val tag = net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.BIOME, id)
            @Suppress("UNCHECKED_CAST")
            (biomeEntry as? net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome>)?.isIn(tag) == true
        }.getOrDefault(false)
    }

    private fun entryBucketWeight(entry: Any): Double {
        return runCatching {
            val bucket = entry.javaClass.getMethod("getBucket").invoke(entry) ?: return 0.0
            (bucket.javaClass.getMethod("getWeight").invoke(bucket) as? Number)?.toDouble() ?: 0.0
        }.getOrDefault(0.0)
    }

    private fun entryBucketColor(entry: Any): Int {
        return runCatching {
            val bucket = entry.javaClass.getMethod("getBucket").invoke(entry) ?: return UiKit.TEXT
            val name = (bucket.javaClass.getMethod("getName").invoke(bucket) as? String)
                ?.lowercase()?.replace("-", "_") ?: return UiKit.TEXT
            when (name) {
                "common"     -> 0xFF55FF55.toInt()
                "uncommon"   -> 0xFFFFFF55.toInt()
                "rare"       -> 0xFF55FFFF.toInt()
                "ultra_rare" -> 0xFFFF55FF.toInt()
                else         -> UiKit.TEXT
            }
        }.getOrDefault(UiKit.TEXT)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legend
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawLegend(context: DrawContext, belowY: Int) {
        val tr = textRenderer; val fh = tr.fontHeight; val sqSz = fh - 1; val gap = 4
        val entries = RadarEnhancer.EV_ORDER.map {
            Triple(it, RadarEnhancer.EV_COLORS[it] ?: 0, RadarEnhancer.EV_LABELS[it] ?: it)
        }
        val widths = entries.map { (_, _, label) -> sqSz + gap + tr.getWidth(label) }
        val totalW = widths.sum() + gap * (entries.size - 1)
        val legendY = belowY + 6
        var curX = (width - totalW) / 2
        for ((i, e) in entries.withIndex()) {
            val (_, color, label) = e
            context.fill(curX, legendY + (fh - sqSz) / 2, curX + sqSz, legendY + (fh - sqSz) / 2 + sqSz, color)
            context.drawText(tr, label, curX + sqSz + gap, legendY, UiKit.TEXT_MUTED, false)
            curX += widths[i] + gap
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tooltip
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawTooltip(ctx: DrawContext, species: Species, mx: Int, my: Int) {
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer; val fh = tr.fontHeight; val pad = 5

        data class Line(val text: String, val color: Int)
        val lines = mutableListOf<Line>()

        val name = try { species.translatedName.string } catch (_: Exception) { species.name }
        lines += Line(name, 0xFFFFFFFF.toInt())

        try {
            val typeLine = species.standardForm.types.joinToString(" / ") { it.displayName.string }
            if (typeLine.isNotBlank()) lines += Line(typeLine, UiKit.TEXT_MUTED)
        } catch (_: Exception) {}

        lines += Line("──────────────────", UiKit.BORDER)

        val evParts = mutableListOf<String>()
        try {
            val ey = species.evYield
            for ((stat, key) in RadarEnhancer.STAT_TO_EV_KEY) {
                val v = ey[stat] ?: 0
                if (v > 0) evParts += "+$v ${RadarEnhancer.EV_LABELS[key] ?: key}"
            }
        } catch (_: Exception) {}
        if (evParts.isNotEmpty()) lines += Line("EV: ${evParts.joinToString("  ")}", 0xFF88FFAA.toInt())
        else {
            val evs = EvYieldData.getEvYield(species.name.lowercase())
            if (!evs.isNullOrEmpty())
                lines += Line("EV: " + evs.entries.joinToString("  ") { (k, v) ->
                    "+$v ${RadarEnhancer.EV_LABELS[k] ?: k}"
                }, 0xFF88FFAA.toInt())
        }

        val eggs = EggGroupData.getEggGroups(species.name.lowercase())
        if (!eggs.isNullOrEmpty())
            lines += Line("Œufs: " + eggs.joinToString(", ") { it.replaceFirstChar { c -> c.uppercaseChar() } }, 0xFFFFDD88.toInt())

        lines += Line("──────────────────", UiKit.BORDER)
        lines += Line("Clic → fiche wiki", 0xFF88AAFF.toInt())

        val sw = client.window.scaledWidth; val sh = client.window.scaledHeight
        val contentW = lines.maxOfOrNull { tr.getWidth(it.text) } ?: 80
        val tooltipW = contentW + pad * 2; val tooltipH = lines.size * (fh + 1) + pad * 2
        var tx = mx + 12; var ty = my - tooltipH / 2
        if (tx + tooltipW > sw - 2) tx = mx - tooltipW - 4
        tx = tx.coerceIn(2, sw - tooltipW - 2); ty = ty.coerceIn(2, sh - tooltipH - 2)

        ctx.fill(tx, ty, tx + tooltipW, ty + tooltipH, 0xDD111122.toInt())
        ctx.fill(tx - 1, ty - 1, tx + tooltipW + 1, ty, 0xFF334466.toInt())
        ctx.fill(tx - 1, ty + tooltipH, tx + tooltipW + 1, ty + tooltipH + 1, 0xFF334466.toInt())
        ctx.fill(tx - 1, ty - 1, tx, ty + tooltipH + 1, 0xFF334466.toInt())
        ctx.fill(tx + tooltipW, ty - 1, tx + tooltipW + 1, ty + tooltipH + 1, 0xFF334466.toInt())
        var lineY = ty + pad
        for (line in lines) { ctx.drawText(tr, line.text, tx + pad, lineY, line.color, false); lineY += fh + 1 }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Input
    // ─────────────────────────────────────────────────────────────────────────

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val pX = panelX; val pY = panelY

        // Switch to TropiMod — static top-left
        if (button == 0 && isSwitchHovered(mouseX.toInt(), mouseY.toInt())) {
            ModConfig.setUseHunterRadar(false)
            val sc = tropiScreen
            if (sc != null) RadarEnhancer.clearRegistration(sc)
            MinecraftClient.getInstance().execute {
                MinecraftClient.getInstance().setScreen(sc as? Screen)
            }
            return true
        }

        // Refresh ↺
        val rbX = pX + PANEL_W - 18; val rbY = pY + 4
        if (mouseX in rbX.toDouble()..(rbX + 13).toDouble()
            && mouseY in rbY.toDouble()..(rbY + 13).toDouble()) { tryRefresh(); return true }

        // Slot click → detail screen
        if (button == 0 && hoveredSlot >= 0) {
            val species = slots[hoveredSlot].species
            MinecraftClient.getInstance().execute {
                MinecraftClient.getInstance().setScreen(PokemonDetailScreen(species, null))
            }
            return true
        }

        // Filter button
        val btnX = pX + PANEL_W + 4
        val btnY = contentTop
        if (mouseX in btnX.toDouble()..(btnX + BTN_W).toDouble()
            && mouseY in btnY.toDouble()..(btnY + BTN_H).toDouble()) {
            evFilter = if (evFilter >= RadarEnhancer.EV_ORDER.lastIndex) -1 else evFilter + 1
            recomputeDisplay(); return true
        }

        // Scrollbar
        if (maxScroll > 0) {
            val sbX = pX + PADDING + GRID_W + 5
            if (mouseX in sbX.toDouble()..(sbX + SB_W).toDouble()
                && mouseY in contentTop.toDouble()..contentBottom.toDouble()) {
                sbDragging = true; sbDragStartY = mouseY.toInt(); sbDragStartOffset = scrollOffset
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        sbDragging = false; return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (sbDragging && button == 0 && maxScroll > 0) {
            val trackH = contentAreaH - 4
            val thumbH = maxOf(16, (contentAreaH.toFloat() / contentH * trackH).toInt())
            val range = trackH - thumbH
            if (range > 0)
                scrollOffset = (sbDragStartOffset + ((mouseY.toInt() - sbDragStartY).toFloat() / range * maxScroll).toInt())
                    .coerceIn(0, maxScroll)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, h: Double, v: Double): Boolean {
        scrollOffset = (scrollOffset - (v * 20).toInt()).coerceIn(0, maxScroll); return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun shouldPause() = false

    // ─────────────────────────────────────────────────────────────────────────
    // Refresh — triggers TropiMod's server request then re-extracts pool after delay
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryRefresh() {
        // Mirror TropiMod's reload button exactly:
        // send OpenRadarPayload(empty RadarPool) → server scans position → responds with
        // OpenRadarPayload(new pool) → TropiMod calls setScreen(PokeRadarScreen) →
        // AFTER_INIT fires → RadarEnhancer extracts updated pool → opens new HunterRadarScreen
        try {
            val radarPoolCls = Class.forName("fr.tropimon.tropimoncore.data.radar.RadarPool")
            val emptyPool = radarPoolCls.getDeclaredConstructor().newInstance()
            val payloadCls = Class.forName("fr.tropimon.tropimodcore.networking.payload.OpenRadarPayload")
            val payload = payloadCls.getDeclaredConstructor(radarPoolCls).newInstance(emptyPool)
            val netCls = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
            netCls.methods.first { it.name == "send" && it.parameterCount == 1 }.invoke(null, payload)
            HunterBoard.LOGGER.info("[HunterRadarScreen] Refresh: packet sent to server")
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("[HunterRadarScreen] Refresh failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EV border resolution
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveEvBorder(species: Species): EvBorder {
        val evs = mutableListOf<Pair<String, Int>>()
        try {
            val ey = species.evYield
            for ((stat, key) in RadarEnhancer.STAT_TO_EV_KEY) {
                val v = ey[stat] ?: 0
                if (v > 0) evs += key to v
            }
        } catch (_: Exception) {}

        if (evs.isEmpty()) {
            val data = EvYieldData.getEvYield(species.name.lowercase()) ?: return EvBorder(0)
            val sorted = data.entries.sortedByDescending { it.value }
            val pk = sorted.getOrNull(0)?.key
            val c1 = RadarEnhancer.EV_COLORS[pk] ?: 0
            val c2 = RadarEnhancer.EV_COLORS[sorted.getOrNull(1)?.key] ?: 0
            return EvBorder(c1, c2, pk)
        }

        evs.sortByDescending { it.second }
        val pk = evs.getOrNull(0)?.first
        val c1 = RadarEnhancer.EV_COLORS[pk] ?: 0
        val c2 = RadarEnhancer.EV_COLORS[evs.getOrNull(1)?.first] ?: 0
        return EvBorder(c1, c2, pk)
    }

    private fun drawSortFilterButtons(context: DrawContext, mx: Int, my: Int, pX: Int) {
        val btnX = pX + PANEL_W + 4
        val btnY = contentTop

        context.drawText(textRenderer, "Filtre", btnX + (BTN_W - textRenderer.getWidth("Filtre")) / 2,
            btnY - textRenderer.fontHeight - 2, UiKit.TEXT_MUTED, false)

        // Filter button — EV color when active
        val filterKey = RadarEnhancer.EV_ORDER.getOrNull(evFilter)
        val filterEvColor = if (filterKey != null) RadarEnhancer.EV_COLORS[filterKey] ?: UiKit.TEXT_MUTED else UiKit.TEXT_MUTED
        val filterLabel = if (evFilter < 0) "Tous" else (RadarEnhancer.EV_LABELS[filterKey] ?: "?")
        val isFilterActive = evFilter >= 0
        val filterHov = mx in btnX..(btnX + BTN_W) && my in btnY..(btnY + BTN_H)
        drawVanillaButton(context, btnX, btnY, BTN_W, BTN_H, filterHov)
        if (isFilterActive) fillBorderSolid(context, btnX, btnY, BTN_W, BTN_H, filterEvColor, 1)
        val fTxtColor = if (isFilterActive) filterEvColor else if (filterHov) 0xFFFFFFFF.toInt() else UiKit.TEXT_MUTED
        val flw = textRenderer.getWidth(filterLabel)
        context.drawText(textRenderer, filterLabel, btnX + (BTN_W - flw) / 2,
            btnY + (BTN_H - textRenderer.fontHeight) / 2, fTxtColor, false)
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Drawing helpers
    // ─────────────────────────────────────────────────────────────────────────

    // Switch button — fixed at top-left corner of the screen, vanilla MC style
    private val SWITCH_LABEL = "→ TropiMod"
    private val SWITCH_BTN_X = 4
    private val SWITCH_BTN_Y = 4
    private val SWITCH_BTN_H = 16
    private val SWITCH_BTN_W get() = textRenderer.getWidth(SWITCH_LABEL) + 12

    private fun isSwitchHovered(mx: Int, my: Int) =
        mx in SWITCH_BTN_X..(SWITCH_BTN_X + SWITCH_BTN_W) && my in SWITCH_BTN_Y..(SWITCH_BTN_Y + SWITCH_BTN_H)

    private fun drawSwitchButton(ctx: DrawContext, mx: Int, my: Int) {
        val w = SWITCH_BTN_W
        drawVanillaButton(ctx, SWITCH_BTN_X, SWITCH_BTN_Y, w, SWITCH_BTN_H, isSwitchHovered(mx, my))
        ctx.drawText(textRenderer, SWITCH_LABEL,
            SWITCH_BTN_X + (w - textRenderer.getWidth(SWITCH_LABEL)) / 2,
            SWITCH_BTN_Y + (SWITCH_BTN_H - textRenderer.fontHeight) / 2,
            0xFFFFFFFF.toInt(), true)
    }

    /** Vanilla Minecraft button look: grey fill, lighter top-left, darker bottom-right. */
    private fun drawVanillaButton(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, hovered: Boolean) {
        val fill    = if (hovered) 0xFF626262.toInt() else UiKit.BORDER
        val hiLight = if (hovered) UiKit.TEXT_MUTED else UiKit.TEXT_MUTED
        val shadow  = UiKit.SURFACE_HOVER
        ctx.fill(x, y, x + w, y + h, fill)
        // top highlight
        ctx.fill(x, y, x + w, y + 1, hiLight)
        // left highlight
        ctx.fill(x, y + 1, x + 1, y + h - 1, hiLight)
        // bottom shadow
        ctx.fill(x, y + h - 1, x + w, y + h, shadow)
        // right shadow
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, shadow)
    }

    /** Solid 4-side border. */
    private fun fillBorderSolid(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int, t: Int = 1) {
        ctx.fill(x,         y,         x + w,     y + t,     color)
        ctx.fill(x,         y + h - t, x + w,     y + h,     color)
        ctx.fill(x,         y + t,     x + t,     y + h - t, color)
        ctx.fill(x + w - t, y + t,     x + w,     y + h - t, color)
    }

    /** Split border: left side = c1, right side = c2. Used for dual-EV Pokémon. */
    private fun fillBorderDual(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, c1: Int, c2: Int, t: Int = 1) {
        val mid = x + w / 2
        ctx.fill(x,   y,         mid, y + t,     c1)
        ctx.fill(mid, y,         x+w, y + t,     c2)
        ctx.fill(x,   y + h - t, mid, y + h,     c1)
        ctx.fill(mid, y + h - t, x+w, y + h,     c2)
        ctx.fill(x,       y + t, x + t,   y + h - t, c1)
        ctx.fill(x+w - t, y + t, x + w,   y + h - t, c2)
    }
}
