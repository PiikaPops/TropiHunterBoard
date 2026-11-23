package com.hunterboard

import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.client.MinecraftClient
import com.cobblemon.mod.common.api.spawning.BestSpawner
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail
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
) : ScaledScreen(Text.literal("Pokéradar")) {

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
        /** Width inside the Tropimon frame: banner margin + grid + gap + scroll track and frame. */
        val SKIN_W          = RadarSkin.GRID_X + GRID_W + 4 + RadarSkin.TRACK_FROM_RIGHT   // 467
    }

    /** The official Tropimon radar frame when its textures are installed, the HunterBoard theme otherwise. */
    private val skin = RadarSkin.available()

    /** Where the scan was made, shown in the title strip like the Tropimon radar does. */
    private val scanPos = MinecraftClient.getInstance().player?.blockPos

    // ── Dynamic layout — 30%–80% screen height, vertically centred ───────────
    private val HEADER_H      = 22
    private val panelW        get() = if (skin) SKIN_W else PANEL_W
    private val headH         get() = if (skin) RadarSkin.CONTENT_TOP else HEADER_H
    private val footH         get() = if (skin) RadarSkin.CONTENT_BOTTOM else 6
    // the frame's chrome is 41 px taller than the theme's header, so it may use a bit more of the screen
    private val panelH: Int   get() {
        val minH = if (skin) maxOf(RadarSkin.MIN_H, (height * 0.30f).toInt()) else (height * 0.30f).toInt()
        val maxH = (height * (if (skin) 0.90f else 0.80f)).toInt()
        return (headH + footH + contentH).coerceIn(minH, maxOf(minH, maxH))
    }

    /** Tropimon's fine print (uniform font, half-size coordinates) needs 2 real pixels per GUI pixel. */
    private val finePrint get() = (client?.window?.scaleFactor ?: 1.0) * uiFactor >= 2.0
    private val panelX        get() = (width  - panelW) / 2
    private val panelY        get() = (height - panelH)  / 2
    private val contentTop    get() = panelY + headH
    private val contentAreaH  get() = panelH - headH - footH
    private val contentBottom get() = contentTop + contentAreaH

    private fun gridLeftOf(pX: Int) = pX + if (skin) RadarSkin.GRID_X else PADDING

    // Scroll track: beside the grid in the theme, in the frame's own track with the skin
    private fun sbXOf(pX: Int) = if (skin) pX + panelW - RadarSkin.TRACK_FROM_RIGHT else pX + PADDING + GRID_W + 5
    private val sbW    get() = if (skin) RadarSkin.TRACK_W else SB_W
    private val trackY get() = if (skin) panelY + RadarSkin.TRACK_TOP else contentTop + 2
    private val trackH get() = if (skin) panelH - RadarSkin.TRACK_TOP - RadarSkin.TRACK_FROM_BOTTOM else contentAreaH - 4

    /** Refresh button hit box (x, y, size), inclusive on both ends like the original button. */
    private fun refreshBox(pX: Int, pY: Int): IntArray =
        if (skin) intArrayOf(pX + panelW - RadarSkin.RELOAD_FROM_RIGHT - 1, pY + RadarSkin.BANNER_Y + 1, RadarSkin.RELOAD_SIZE + 1)
        else intArrayOf(pX + PANEL_W - 18, pY + 4, 13)

    private fun filterLabel(): String {
        val key = RadarEnhancer.EV_ORDER.getOrNull(evFilter)
        return if (evFilter < 0) "Tous" else (RadarEnhancer.EV_LABELS[key] ?: "?")
    }

    /** With the skin the EV filter sits in the banner, left of the refresh button: (x, y, w, h). */
    private fun skinFilterRect(pX: Int, pY: Int): IntArray {
        val w = textRenderer.getWidth(RadarSkin.label("EV : ", finePrint)) +
            textRenderer.getWidth(RadarSkin.label(filterLabel(), finePrint)) + (if (evFilter >= 0) 9 else 0) + 6
        val right = pX + panelW - RadarSkin.RELOAD_FROM_RIGHT - 4
        return intArrayOf(right - w, pY + RadarSkin.BANNER_Y, w, RadarSkin.BANNER_H)
    }

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

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val pX = panelX; val pY = panelY   // snapshot computed values once per frame
        UiKit.screenDim(context, width, height)

        val gridLeft = gridLeftOf(pX)
        val title = "${slots.size} apparition${if (slots.size > 1) "s" else ""} possible${if (slots.size > 1) "s" else ""}"
        val (rbX, rbY, rbS) = refreshBox(pX, pY)
        val rbHov = mouseX in rbX..(rbX + rbS) && mouseY in rbY..(rbY + rbS)

        if (skin) {
            renderSkinChrome(context, pX, pY, title, rbHov, mouseX, mouseY)
        } else {
            // Panel background + border (shared HunterBoard identity)
            UiKit.panel(context, pX, pY, PANEL_W, panelH)

            // ── Header ──────────────────────────────────────────────────────────
            if (!UiKit.isModern) context.fillGradient(pX + 1, pY + 1, pX + PANEL_W - 1, pY + HEADER_H - 2,
                UiKit.withAlpha(UiKit.accent(), 0x28), UiKit.withAlpha(UiKit.accent(), 0x00))
            context.drawText(textRenderer, title, pX + PADDING, pY + 7, UiKit.TEXT, true)

            // Refresh button ↺ (top-right of panel)
            drawVanillaButton(context, rbX, rbY, 13, 13, rbHov)
            context.drawText(textRenderer, "↺", rbX + 3, rbY + 2, 0xFFFFFFFF.toInt(), false)

            // Separator
            context.fill(pX + 2, pY + HEADER_H - 1, pX + PANEL_W - 2, pY + HEADER_H, UiKit.withAlpha(UiKit.accent(), 0xCC))
        }

        // ── Grid (scissored) ─────────────────────────────────────────────────
        if (skin) scissor(context, pX + 20, contentTop, sbXOf(pX) - 1, contentBottom)
        else scissor(context, pX + 1, contentTop, pX + PADDING + GRID_W + 2, contentBottom)
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
            if (skin) {
                RadarSkin.drawSlot(context, cx, cy, CELL_W, CELL_H, ModConfig.radarDark)
                if (hovered) context.fill(cx + 1, cy + 1, cx + CELL_W - 1, cy + CELL_H - 1, 0x48FFFFFF)
            } else context.fill(cx, cy, cx + CELL_W, cy + CELL_H,
                if (hovered) 0x55FFFFFF.toInt() else 0x1AFFFFFF.toInt())

            // EV border — dual or solid (with the skin, a cell without EV data keeps the slot's own border)
            val evb = evBorders.getOrElse(idx) { EvBorder(0) }
            val borderColor = if (evb.primary != 0) evb.primary else 0xFF2A2A44.toInt()
            if (evb.secondary != 0)
                fillBorderDual(context, cx, cy, CELL_W, CELL_H, evb.primary, evb.secondary, 2)
            else if (!skin || evb.primary != 0)
                fillBorderSolid(context, cx, cy, CELL_W, CELL_H, borderColor, 2)

            // Spawn % — centred, coloured by Cobblemon rarity tier (1px margin from top).
            // On the light cyan slot the rarity colours (cyan for rare) need a dark strip behind them.
            if (skin) context.fill(cx + 2, cy + 2, cx + CELL_W - 2, cy + 12, 0xE022658B.toInt())
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
            val sbX = sbXOf(pX)
            val tY = trackY; val tH = trackH
            // the skin's frame already draws the track
            if (!skin) context.fill(sbX, tY, sbX + SB_W, tY + tH, 0xFF1A1A2A.toInt())
            val thumbH = maxOf(16, (contentAreaH.toFloat() / contentH * tH).toInt())
            val thumbY = tY + (scrollOffset.toFloat() / maxScroll * (tH - thumbH)).toInt()
            val sbHov = mouseX in sbX..(sbX + sbW)
            context.fill(sbX, thumbY, sbX + sbW, thumbY + thumbH,
                if (skin) (if (sbHov || sbDragging) 0xFFFFFFFF.toInt() else 0xFFCDF7FA.toInt())
                else if (sbHov || sbDragging) UiKit.accent() else 0xFF445566.toInt())
        }

        // ── Sort + Filter buttons (right of panel; in the banner with the skin) ─
        if (!skin) drawSortFilterButtons(context, mouseX, mouseY, pX)

        // ── Switch button — static top-left corner, vanilla MC style ─────────
        drawSwitchButton(context, mouseX, mouseY)

        // ── EV legend below panel (in the bottom bar with the skin) ───────────
        if (skin) drawSkinLegend(context, pX, pY + panelH - RadarSkin.BAR_FROM_BOTTOM)
        else drawLegend(context, pY + panelH)

        // ── Tooltip ───────────────────────────────────────────────────────────
        if (hoveredSlot >= 0) {
            context.matrices.push()
            context.matrices.translate(0f, 0f, 400f)
            drawTooltip(context, slots[hoveredSlot].species, mouseX, mouseY)
            context.matrices.pop()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tropimon frame (skin)
    // ─────────────────────────────────────────────────────────────────────────

    /** Frame, title strip, banner and its controls, laid out like Tropimon's own radar. */
    private fun renderSkinChrome(ctx: DrawContext, pX: Int, pY: Int, count: String, rbHov: Boolean, mx: Int, my: Int) {
        val w = panelW
        val fine = finePrint
        RadarSkin.drawFrame(ctx, pX, pY, w, panelH, ModConfig.radarDark)

        // Title strip: favicon, "Pokéradar", scan position (right-aligned)
        RadarSkin.drawFavicon(ctx, pX + 24, pY + 13)
        val title = Text.translatableWithFallback("gui.tropimod.pokeradar.title", "Pokéradar").string
        ctx.drawText(textRenderer, RadarSkin.heading(title, fine), pX + 36, pY + 12, 0xFFFFFFFF.toInt(), true)
        scanPos?.let { p ->
            val pos = "X: ${p.x} Y: ${p.y} Z: ${p.z}"
            if (fine) {
                ctx.matrices.push()
                ctx.matrices.translate((pX + w - 22 - textRenderer.getWidth(pos) / 2).toFloat(), (pY + 15).toFloat(), 0f)
                ctx.matrices.scale(0.5f, 0.5f, 1f)
                ctx.drawText(textRenderer, pos, 0, 0, 0xFFFFFFFF.toInt(), true)
                ctx.matrices.pop()
            } else ctx.drawText(textRenderer, pos, pX + w - 22 - textRenderer.getWidth(pos), pY + 12, 0xFFDDF8FB.toInt(), true)
        }

        // Banner: count on the left, EV filter and refresh on the right
        ctx.drawText(textRenderer, RadarSkin.heading(count, fine), pX + 31, pY + RadarSkin.BANNER_Y + 1, 0xFFFFFFFF.toInt(), true)
        val (rbX, rbY) = refreshBox(pX, pY)
        RadarSkin.drawReload(ctx, rbX + 1, rbY + 1, rbHov)

        val (fx, fy, fw, fh) = skinFilterRect(pX, pY)
        val fHov = mx in fx until fx + fw && my in fy until fy + fh
        if (fHov) ctx.fill(fx, fy + 1, fx + fw, fy + fh - 1, 0x30FFFFFF)
        val ty = fy + 2
        var tx = fx + 3
        tx = ctx.drawText(textRenderer, RadarSkin.label("EV : ", fine), tx, ty, 0xFFCFF3F7.toInt(), true)
        val key = RadarEnhancer.EV_ORDER.getOrNull(evFilter)
        if (key != null) {
            evSwatch(ctx, tx, ty, RadarEnhancer.EV_COLORS[key] ?: 0xFFFFFFFF.toInt())
            tx += 9
        }
        ctx.drawText(textRenderer, RadarSkin.label(filterLabel(), fine), tx, ty, 0xFFFFFFFF.toInt(), true)
    }

    /** EV legend inside the frame's bottom bar. */
    private fun drawSkinLegend(ctx: DrawContext, pX: Int, barY: Int) {
        val gap = 6
        val fine = finePrint
        val entries = RadarEnhancer.EV_ORDER.map {
            (RadarEnhancer.EV_COLORS[it] ?: 0) to RadarSkin.label(RadarEnhancer.EV_LABELS[it] ?: it, fine)
        }
        val widths = entries.map { (_, label) -> 9 + textRenderer.getWidth(label) }
        val totalW = widths.sum() + gap * (entries.size - 1)
        var x = pX + (panelW - totalW) / 2
        val ty = barY + 2
        for ((i, e) in entries.withIndex()) {
            evSwatch(ctx, x, ty, e.first)
            ctx.drawText(textRenderer, e.second, x + 9, ty, 0xFFFFFFFF.toInt(), true)
            x += widths[i] + gap
        }
    }

    /** A 7×7 EV colour square with a dark outline, readable on the frame's teal. */
    private fun evSwatch(ctx: DrawContext, x: Int, textY: Int, color: Int) {
        ctx.fill(x, textY, x + 7, textY + 7, 0xFF1B4F6B.toInt())
        ctx.fill(x + 1, textY + 1, x + 6, textY + 6, color)
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

    /**
     * Cobblemon 1.8 removed the SpawnBucket class: SpawnDetail.bucket is now a plain
     * String and the per-bucket weights live in the BestSpawner config, with a separate
     * table for fishing / Poke Snack / habitat spawns. The old reflection
     * (`bucket.getWeight()`) silently returned 0 for every entry.
     */
    private val DEFAULT_WORLD_BUCKETS = mapOf(
        "common" to 94.05, "uncommon" to 5.0, "rare" to 0.5, "ultra-rare" to 0.2, "boss" to 0.25
    )
    private val DEFAULT_ALT_BUCKETS = mapOf(
        "common" to 83.25, "uncommon" to 11.25, "rare" to 4.125, "ultra-rare" to 1.375
    )

    private fun entryBucketName(entry: Any): String? = runCatching {
        (entry as? SpawnDetail)?.bucket?.lowercase()?.trim()
    }.getOrNull()

    private fun entryPositionType(entry: Any): String = runCatching {
        (entry as? SpawnDetail)?.spawnablePositionType?.name?.lowercase() ?: ""
    }.getOrDefault("")

    private fun entryBucketWeight(entry: Any): Double {
        val bucket = entryBucketName(entry) ?: return 0.0
        val isAlternate = entryPositionType(entry) == "fishing"
        val configured = runCatching {
            val config = BestSpawner.config
            val table = if (isAlternate) config.fishingBuckets else config.worldBuckets
            table[bucket]?.toDouble()
        }.getOrNull()
        if (configured != null && configured > 0.0) return configured
        val fallback = if (isAlternate) DEFAULT_ALT_BUCKETS else DEFAULT_WORLD_BUCKETS
        return fallback[bucket] ?: 0.0
    }

    private fun entryBucketColor(entry: Any): Int {
        val name = entryBucketName(entry)?.replace("-", "_") ?: return UiKit.TEXT
        return when (name) {
            "common"     -> 0xFF55FF55.toInt()
            "uncommon"   -> 0xFFFFFF55.toInt()
            "rare"       -> 0xFF55FFFF.toInt()
            "ultra_rare" -> 0xFFFF55FF.toInt()
            "boss"       -> 0xFFFF5544.toInt()
            else         -> UiKit.TEXT
        }
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

        // the screen's own dimensions, not the window's: under an interface scale cap the
        // two differ, and clamping to the window would bunch the tooltip into one corner
        val sw = width; val sh = height
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

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
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
        val (rbX, rbY, rbS) = refreshBox(pX, pY)
        if (mouseX in rbX.toDouble()..(rbX + rbS).toDouble()
            && mouseY in rbY.toDouble()..(rbY + rbS).toDouble()) { tryRefresh(); return true }

        // EV filter in the banner (skin)
        if (skin && button == 0) {
            val (fx, fy, fw, fh) = skinFilterRect(pX, pY)
            if (mouseX >= fx && mouseX < fx + fw && mouseY >= fy && mouseY < fy + fh) {
                evFilter = if (evFilter >= RadarEnhancer.EV_ORDER.lastIndex) -1 else evFilter + 1
                recomputeDisplay(); return true
            }
        }

        // Slot click → detail screen; Escape or "Back" there comes back to this radar
        if (button == 0 && hoveredSlot >= 0) {
            val species = slots[hoveredSlot].species
            MinecraftClient.getInstance().execute {
                MinecraftClient.getInstance().setScreen(PokemonDetailScreen(species, this@HunterRadarScreen))
            }
            return true
        }

        // Filter button
        val btnX = pX + PANEL_W + 4
        val btnY = contentTop
        if (!skin && mouseX in btnX.toDouble()..(btnX + BTN_W).toDouble()
            && mouseY in btnY.toDouble()..(btnY + BTN_H).toDouble()) {
            evFilter = if (evFilter >= RadarEnhancer.EV_ORDER.lastIndex) -1 else evFilter + 1
            recomputeDisplay(); return true
        }

        // Scrollbar (the skin's 3 px track gets a wider grab zone)
        if (maxScroll > 0) {
            val sbX = sbXOf(pX)
            val grab = if (skin) 2 else 0
            val (top, bottom) = if (skin) trackY to trackY + trackH else contentTop to contentBottom
            if (mouseX in (sbX - grab).toDouble()..(sbX + sbW + grab).toDouble()
                && mouseY in top.toDouble()..bottom.toDouble()) {
                sbDragging = true; sbDragStartY = mouseY.toInt(); sbDragStartOffset = scrollOffset
                return true
            }
        }
        return super.mouseClickedScaled(mouseX, mouseY, button)
    }

    override fun mouseReleasedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        sbDragging = false; return super.mouseReleasedScaled(mouseX, mouseY, button)
    }

    override fun mouseDraggedScaled(mouseX: Double, mouseY: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (sbDragging && button == 0 && maxScroll > 0) {
            val tH = trackH
            val thumbH = maxOf(16, (contentAreaH.toFloat() / contentH * tH).toInt())
            val range = tH - thumbH
            if (range > 0)
                scrollOffset = (sbDragStartOffset + ((mouseY.toInt() - sbDragStartY).toFloat() / range * maxScroll).toInt())
                    .coerceIn(0, maxScroll)
            return true
        }
        return super.mouseDraggedScaled(mouseX, mouseY, button, dx, dy)
    }

    override fun mouseScrolledScaled(mouseX: Double, mouseY: Double, h: Double, v: Double): Boolean {
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
