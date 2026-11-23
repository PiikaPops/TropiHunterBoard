package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.BestSpawner
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.render.DiffuseLighting
import net.minecraft.client.render.OverlayTexture
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import net.minecraft.util.math.RotationAxis
import org.lwjgl.glfw.GLFW

/**
 * Everything one habitat pool can produce.
 *
 * The top of the page says what the habitat is and where to look for it in a few
 * lines; the worldgen figures behind that summary wait in a tooltip. Below, one tab per
 * phase — a habitat block moves to another phase every Minecraft day and only that
 * phase's species come out — and the species as cards, by rarity, each with the share
 * of the Pokémon it accounts for.
 */
class HabitatDetailScreen(
    private val habitatId: String,
    private val parent: Screen
) : ScaledScreen(Text.literal("Habitat")) {

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    private companion object {
        const val PREVIEW_H = 132
        /** Below this inner width the preview and the summary stack instead of sitting side by side. */
        const val WIDE_MIN = 420
        const val TAB_H = 14
        const val CARD_H = 28
        const val CARD_MIN_W = 136
        const val CARD_GAP = 4
        const val MODEL = 24
        /** Where the model's box starts inside a card; with [MODEL_SCALE] the head stays in. */
        const val MODEL_TOP = 2
        const val MODEL_SCALE = 0.72f
        const val SECTION_GAP = 8

        /** Cobblemon's shipped activatedHabitatBuckets, used when the config cannot be read. */
        val HABITAT_BUCKETS = mapOf(
            "common" to 83.25, "uncommon" to 11.25, "rare" to 4.125, "ultra-rare" to 1.375
        )
    }

    private var scrollOffset = 0
    private var contentHeight = 0
    private var contentAreaHeight = 0
    private var backBtnX = 0
    private var backBtnY = 0
    private var backBtnW = 0

    // 3D preview state
    private var variant = 0
    private var yaw = 35f
    private var pitch = 25f
    private var isDraggingModel = false
    private var prevBtnX = 0
    private var nextBtnX = 0
    private var previewBtnY = 0
    private var previewBox = intArrayOf(0, 0, 0, 0)   // x, y, w, h

    /** Index into [phases]; kept while a Pokémon page opened from here is read. */
    private var phaseIndex = 0

    // ---- What the page shows, worked out once ----

    /** One species of one phase, its buckets merged: a Pokémon listed as common and uncommon is one card. */
    private class Card(
        val species: String,
        /** Share of the Pokémon that come out during the phase, in percent. */
        val chance: Double,
        val lvMin: Int,
        val lvMax: Int,
        /** The bucket it comes from most often, which places the card. */
        val bucket: String,
        /** Every bucket it comes from, with its share, likeliest first. */
        val parts: List<Pair<String, Double>>
    )

    private class Section(val bucket: String, val chance: Double, val cards: List<Card>)

    private class CardHit(val x: Int, val y: Int, val w: Int, val h: Int, val card: Card)
    private class TabHit(val x: Int, val y: Int, val w: Int, val h: Int, val index: Int)
    private class Zone(val x: Int, val y: Int, val w: Int, val h: Int, val lines: List<OrderedText>)

    private val cardHits = mutableListOf<CardHit>()
    private val tabHits = mutableListOf<TabHit>()
    private val zones = mutableListOf<Zone>()

    private val entries: List<Pair<String, HabitatEntry>> by lazy { HabitatData.speciesIn(habitatId) }

    /** The phases the pool names; a pool without any is one list, phase 0. */
    private val phases: List<Int> by lazy {
        entries.flatMap { it.second.phases }.filter { it > 0 }.distinct().sorted().ifEmpty { listOf(0) }
    }

    private val speciesCount: Int by lazy { entries.map { it.first }.distinct().size }

    private val placement: HabitatPlacementInfo? by lazy { HabitatPlacement.forHabitat(habitatId) }

    /**
     * The biome tag in words and the biomes behind it. Structures state them in their own
     * file; features state them nowhere, so HabitatPlacement supplies the tag it recovered
     * from Cobblemon's code.
     */
    private val where: Where by lazy {
        var biomes = HabitatData.biomesFor(habitatId)
        var tagLabel = HabitatData.biomeTagLabel(habitatId)
        var specs = HabitatData.biomeTagsFor(habitatId)
        val p = placement
        if (tagLabel == null && p != null && p.biomeSpecs.isNotEmpty()) {
            tagLabel = p.biomeSpecs.joinToString(", ") { spec -> prettyTag(spec) }
            biomes = p.biomeSpecs.flatMap { spec ->
                if (spec.startsWith("#")) BiomeTagResolver.resolve(spec.removePrefix("#")) else listOf(spec)
            }.distinct()
            specs = p.biomeSpecs
        }
        // the underground features take the whole Overworld: its tag reads "Surface" in
        // French, the opposite of where they are, and its fifty-odd biomes say nothing
        val everywhere = specs.isNotEmpty() && specs.all { it.removePrefix("#") == "minecraft:is_overworld" }
        Where(tagLabel, biomes, everywhere)
    }

    private class Where(val tagLabel: String?, val biomes: List<String>, val everywhere: Boolean)

    private val sectionCache = HashMap<Int, List<Section>>()

    /**
     * The species of one phase, by bucket, each with its share of what comes out.
     *
     * Cobblemon rolls the **bucket first** and only then picks by weight among the spawns
     * of that bucket, so weight alone says nothing about rarity: a common with weight 1
     * beats a rare with weight 50. The roll is made over every bucket the pool uses, phase
     * or not; one with nothing in the current phase produces nothing, so among the Pokémon
     * that do come out the buckets of the phase share the whole.
     */
    private fun sectionsFor(phase: Int): List<Section> = sectionCache.getOrPut(phase) {
        val inPhase = entries.filter { (_, e) -> phase == 0 || e.phases.isEmpty() || phase in e.phases }
        val bucketTotals = inPhase.groupBy { key(it.second.bucket) }
            .mapValues { (_, inBucket) -> inBucket.sumOf { it.second.weight.toDouble() } }
            .filterValues { it > 0.0 }
        if (bucketTotals.isEmpty()) return@getOrPut emptyList()
        val rolled = bucketTotals.keys.sumOf { bucketChance(it) }
        // a bucket the config does not know (a datapack's own) still gets an even share
        fun bucketShare(bucket: String): Double =
            if (rolled > 0.0) bucketChance(bucket) / rolled else 1.0 / bucketTotals.size

        class Part(val species: String, val bucket: String, val chance: Double, val entry: HabitatEntry)
        val parts = inPhase.mapNotNull { (species, entry) ->
            val bucket = key(entry.bucket)
            val total = bucketTotals[bucket] ?: return@mapNotNull null
            Part(species, bucket, bucketShare(bucket) * entry.weight / total * 100.0, entry)
        }

        val cards = parts.groupBy { it.species }.map { (species, own) ->
            val byBucket = own.groupBy { it.bucket }
                .map { (bucket, inBucket) -> bucket to inBucket.sumOf { it.chance } }
                .sortedByDescending { it.second }
            Card(
                species = species,
                chance = own.sumOf { it.chance },
                lvMin = own.minOf { it.entry.lvMin },
                lvMax = own.maxOf { it.entry.lvMax },
                bucket = byBucket.first().first,
                parts = byBucket
            )
        }

        cards.groupBy { it.bucket }
            .map { (bucket, inBucket) ->
                Section(bucket, bucketShare(bucket) * 100.0, inBucket.sortedWith(
                    compareByDescending<Card> { it.chance }.thenBy { Translations.pokemonName(it.species) }))
            }
            .sortedByDescending { bucketChance(it.bucket) }
    }

    // ---- Drawing ----

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        UiKit.screenDim(context, width, height)
        cardHits.clear(); tabHits.clear(); zones.clear()

        val panelWidth = (width * 0.7).toInt().coerceIn(320, 620)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        UiKit.panel(context, panelX, panelTop, panelWidth, panelHeight)
        UiKit.header(context, textRenderer, panelX, panelTop, panelWidth, HabitatData.displayNameOf(habitatId))

        // Back button
        val backLabel = "← " + Translations.tr("Back")
        backBtnW = textRenderer.getWidth(backLabel) + 8
        backBtnX = panelX + 8
        backBtnY = panelTop + 4
        val backHovered = mouseX in backBtnX..(backBtnX + backBtnW) && mouseY in backBtnY..(backBtnY + 12)
        UiKit.button(context, textRenderer, backBtnX, backBtnY, backBtnW, 12, backLabel, backHovered)

        val contentTop = panelTop + 22
        val contentBottom = panelBottom - 16
        contentAreaHeight = contentBottom - contentTop
        val innerX = panelX + 10
        val innerW = panelWidth - 24
        val pointerIn = mouseY in contentTop until contentBottom
        val mx = if (pointerIn) mouseX else Int.MIN_VALUE
        val my = if (pointerIn) mouseY else Int.MIN_VALUE

        scissor(context, panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)
        val startY = contentTop + 4 - scrollOffset
        var y = startY

        y = renderHero(context, innerX, innerW, y, mx, my)
        y += 10
        y = renderPhaseTabs(context, innerX, innerW, y, mx, my)
        y = renderSpecies(context, innerX, innerW, y, mx, my, contentTop, contentBottom)

        contentHeight = y - startY + 4
        context.disableScissor()
        scrollOffset = scrollOffset.coerceIn(0, (contentHeight - contentAreaHeight).coerceAtLeast(0))

        UiKit.scrollbar(context, panelX + panelWidth - 5, contentTop, contentBottom, contentHeight, scrollOffset)

        val hint: String = Translations.tr("ESC / Click Back to return")
        UiKit.footer(context, textRenderer, panelX, panelBottom, panelWidth, hint)

        if (!isDraggingModel) {
            val card = cardHits.firstOrNull { mx in it.x until it.x + it.w && my in it.y until it.y + it.h }
            val zone = zones.firstOrNull { mx in it.x until it.x + it.w && my in it.y until it.y + it.h }
            when {
                card != null -> context.drawTooltip(textRenderer, cardTooltip(card.card), mouseX, mouseY)
                zone != null -> context.drawOrderedTooltip(textRenderer, zone.lines, mouseX, mouseY)
            }
        }
    }

    /** The preview, and beside it (or under it, when narrow) what the habitat is and where it grows. */
    private fun renderHero(context: DrawContext, x: Int, w: Int, startY: Int, mx: Int, my: Int): Int {
        val hasPreview = HabitatStructures.variantCount(habitatId) > 0
        val wide = w >= WIDE_MIN
        var factsX = x
        var factsY = startY
        var factsW = w
        var bottom = startY
        if (hasPreview) {
            val previewW = if (wide) w / 2 else w
            renderPreview(context, x, startY, previewW, PREVIEW_H, mx, my)
            bottom = startY + PREVIEW_H
            if (wide) { factsX = x + previewW + 10; factsW = w - previewW - 10 }
            else factsY = bottom + 8
        }
        return maxOf(bottom, renderFacts(context, factsX, factsY, factsW, mx, my))
    }

    private fun renderFacts(context: DrawContext, x: Int, startY: Int, w: Int, mx: Int, my: Int): Int {
        var y = startY + 2

        // what it is: the list's icon, the environment, the size of the pool
        context.drawItem(HabitatGroups.iconOf(habitatId), x, y + 1)
        context.drawText(textRenderer, HabitatGroups.groupOf(habitatId).displayName(), x + 20, y, UiKit.TEXT, true)
        val counts = buildString {
            append("$speciesCount ${Translations.tr("species")}")
            if (phases.size > 1) append(" · ${phases.size} ${Translations.tr("phases")}")
        }
        context.drawText(textRenderer, counts, x + 20, y + 10, UiKit.TEXT_MUTED, true)
        y += 26

        // where to look
        UiKit.sectionTitle(context, textRenderer, Translations.tr("Generates in:"), x, y)
        y += 12
        val tagLabel = where.tagLabel
        val biomes = where.biomes
        val villages = villageVariants()
        val hosts = hostNames()
        when {
            where.everywhere -> {
                val label = "◆ " + Translations.tr("Any biome")
                context.drawText(textRenderer, label, x + 4, y, 0xFF88BBAA.toInt(), true)
                if (biomes.isNotEmpty()) {
                    zones += Zone(x, y - 1, w, 10, biomeTooltip(tagLabel, biomes))
                }
                y += 11
            }
            tagLabel != null || biomes.isNotEmpty() -> {
                if (tagLabel != null) {
                    context.drawText(textRenderer, fit("◆ $tagLabel", w - 4), x + 4, y, 0xFF88BBAA.toInt(), true)
                    y += 11
                }
                if (biomes.isNotEmpty()) {
                    val top = y
                    y = drawBiomes(context, biomes, x + 4, y, w - 4)
                    zones += Zone(x, top, w, y - top, biomeTooltip(tagLabel, biomes))
                } else {
                    context.drawText(textRenderer, Translations.tr("Biomes unknown"), x + 4, y, UiKit.TEXT_FAINT, true)
                    y += 11
                }
            }
            villages.isNotEmpty() -> {
                context.drawText(textRenderer, fit("◆ " + Translations.tr("Village houses"), w - 4),
                    x + 4, y, 0xFF88BBAA.toInt(), true)
                y += 11
                val top = y
                y = drawBiomes(context, villages, x + 4, y, w - 4)
                zones += Zone(x, top, w, y - top, biomeTooltip(Translations.tr("Village houses"), villages))
            }
            hosts != null -> {
                context.drawText(textRenderer, fit("◆ ${Translations.tr("Inside")} : $hosts", w - 4),
                    x + 4, y, 0xFF88BBAA.toInt(), true)
                y += 11
            }
            else -> {
                context.drawText(textRenderer, Translations.tr("Placement unknown"), x + 4, y, UiKit.TEXT_FAINT, true)
                y += 11
            }
        }
        y += 6

        // how often the world makes one, and the rest on demand
        val p = placement ?: return y
        val facts = keyFacts(p)
        if (facts.isNotEmpty()) {
            val labelW = facts.maxOf { textRenderer.getWidth(it.first) } + 8
            for ((label, value) in facts) {
                context.drawText(textRenderer, label, x + 4, y, UiKit.TEXT_FAINT, true)
                context.drawText(textRenderer, fit(value, w - 4 - labelW), x + 4 + labelW, y, UiKit.TEXT_MUTED, true)
                y += 11
            }
        }
        val more = "▸ " + Translations.tr("Technical details")
        val moreW = textRenderer.getWidth(more)
        val pointed = mx in x + 4 until x + 4 + moreW && my in y - 1 until y + 9
        context.drawText(textRenderer, more, x + 4, y, if (pointed) UiKit.TEXT else UiKit.TEXT_FAINT, true)
        zones += Zone(x + 4, y - 1, moreW, 10, technicalTooltip(p))
        return y + 11
    }

    /** As many biomes as two lines hold, and how many did not fit. */
    private fun drawBiomes(context: DrawContext, biomes: List<String>, x: Int, startY: Int, w: Int): Int {
        val lines = mutableListOf(StringBuilder())
        var shown = 0
        for (biome in biomes) {
            val line = lines.last()
            val candidate = if (line.isEmpty()) biome else "$line, $biome"
            val restAfter = biomes.size - shown - 1
            val suffix = if (restAfter > 0 && lines.size == 2) " +$restAfter" else ""
            if (textRenderer.getWidth(candidate + suffix) <= w || line.isEmpty()) {
                line.clear(); line.append(candidate); shown++
            } else if (lines.size < 2) {
                line.append(",")
                lines += StringBuilder(biome); shown++
            } else break
        }
        if (shown < biomes.size) lines.last().append(" +${biomes.size - shown}")
        var y = startY
        for (line in lines) {
            context.drawText(textRenderer, fit(line.toString(), w), x, y, UiKit.TEXT_MUTED, true)
            y += 10
        }
        return y + 1
    }

    /**
     * A rough idea of what the habitat looks like.
     *
     * Cobblemon assembles a habitat with a jigsaw, so there is no single "the"
     * structure — this shows one self-contained variant out of however many ship
     * with the pool, and the arrows cycle through them.
     */
    private fun renderPreview(context: DrawContext, boxX: Int, boxY: Int, boxW: Int, boxH: Int, mx: Int, my: Int) {
        val count = HabitatStructures.variantCount(habitatId)
        val preview = HabitatStructures.preview(habitatId, variant)
        previewBox = intArrayOf(boxX, boxY, boxW, boxH)

        UiKit.slot(context, boxX, boxY, boxW, boxH)

        if (preview == null) {
            val msg = Translations.tr("Preview unavailable")
            context.drawText(textRenderer, msg,
                boxX + (boxW - textRenderer.getWidth(msg)) / 2, boxY + boxH / 2 - 4, UiKit.TEXT_FAINT, true)
        } else {
            drawStructure(context, boxX, boxY, boxW, boxH, preview)
            val dims = "${preview.sizeX}×${preview.sizeY}×${preview.sizeZ}"
            context.drawText(textRenderer, dims, boxX + 4, boxY + boxH - 10, UiKit.TEXT_FAINT, true)
        }

        // variant navigation
        previewBtnY = boxY + 4
        val label = "${variant.mod(count) + 1}/$count"
        val labelW = textRenderer.getWidth(label)
        nextBtnX = boxX + boxW - 14
        prevBtnX = nextBtnX - 12 - labelW - 6
        if (count > 1) {
            val prevHover = mx in prevBtnX..(prevBtnX + 10) && my in previewBtnY..(previewBtnY + 10)
            val nextHover = mx in nextBtnX..(nextBtnX + 10) && my in previewBtnY..(previewBtnY + 10)
            context.drawText(textRenderer, "◀", prevBtnX, previewBtnY, if (prevHover) UiKit.accent() else UiKit.TEXT_MUTED, true)
            context.drawText(textRenderer, label, prevBtnX + 12, previewBtnY, UiKit.TEXT_FAINT, true)
            context.drawText(textRenderer, "▶", nextBtnX, previewBtnY, if (nextHover) UiKit.accent() else UiKit.TEXT_MUTED, true)
        }
    }

    /** Renders the blocks as GUI entities, centred and rotated by the user. */
    private fun drawStructure(context: DrawContext, x: Int, y: Int, w: Int, h: Int, preview: StructurePreview) {
        if (preview.blocks.isEmpty()) return
        try {
            val client = MinecraftClient.getInstance()
            val renderer = client.blockRenderManager
            val consumers = context.vertexConsumers

            val maxDim = maxOf(preview.sizeX, preview.sizeY, preview.sizeZ).coerceAtLeast(1)
            val scale = (minOf(w, h) * 0.78f) / maxDim

            val matrices = context.matrices
            matrices.push()
            matrices.translate((x + w / 2).toDouble(), (y + h / 2).toDouble(), 220.0)
            matrices.scale(scale, -scale, scale)
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch))
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw))
            matrices.translate(-preview.sizeX / 2.0, -preview.sizeY / 2.0, -preview.sizeZ / 2.0)

            DiffuseLighting.enableGuiDepthLighting()
            for (block in preview.blocks) {
                matrices.push()
                matrices.translate(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
                renderer.renderBlockAsEntity(
                    block.state, matrices, consumers, 0xF000F0, OverlayTexture.DEFAULT_UV
                )
                matrices.pop()
            }
            matrices.pop()
            context.draw()
            DiffuseLighting.enableGuiDepthLighting()
        } catch (_: Exception) {
            // a broken block model must not take the screen down
        }
    }

    /** One tab per phase, or a plain heading when the pool has a single list. */
    private fun renderPhaseTabs(context: DrawContext, x: Int, w: Int, startY: Int, mx: Int, my: Int): Int {
        var y = startY
        if (phases.size <= 1) {
            UiKit.sectionTitle(context, textRenderer, Translations.tr("Pokémon"), x, y)
            val count = speciesCount.toString()
            context.drawText(textRenderer, count, x + w - textRenderer.getWidth(count), y, UiKit.TEXT_FAINT, true)
            return y + 14
        }
        var tabX = x
        for ((index, phase) in phases.withIndex()) {
            val label = "${Translations.tr("Phase")} $phase"
            val tabW = textRenderer.getWidth(label) + 14
            val pointed = mx in tabX until tabX + tabW && my in y until y + TAB_H
            UiKit.tab(context, textRenderer, tabX, y, tabW, TAB_H, label, index == phaseIndex, pointed)
            tabHits += TabHit(tabX, y, tabW, TAB_H, index)
            tabX += tabW + 2
        }
        val hint = Translations.tr("Changes every Minecraft day")
        val hintW = textRenderer.getWidth(hint)
        if (tabX + 8 + hintW <= x + w) {
            context.drawText(textRenderer, hint, x + w - hintW, y + (TAB_H - 7) / 2, UiKit.TEXT_FAINT, true)
        }
        y += TAB_H
        context.fill(x, y, x + w, y + 1, UiKit.BORDER_DIM)
        return y + 7
    }

    /** The current phase's species: a heading per bucket, then its cards. */
    private fun renderSpecies(
        context: DrawContext, x: Int, w: Int, startY: Int, mx: Int, my: Int,
        contentTop: Int, contentBottom: Int
    ): Int {
        var y = startY
        val phase = phases[phaseIndex.coerceIn(phases.indices)]
        val sections = sectionsFor(phase)
        if (sections.isEmpty()) {
            context.drawText(textRenderer, Translations.tr("Nothing this phase"), x + 4, y, UiKit.TEXT_FAINT, true)
            return y + 12
        }
        val columns = ((w + CARD_GAP) / (CARD_MIN_W + CARD_GAP)).coerceAtLeast(1)
        val cardW = (w - (columns - 1) * CARD_GAP) / columns
        for (section in sections) {
            drawBucketHeader(context, section, x, y, w)
            y += 13
            for ((index, card) in section.cards.withIndex()) {
                val cardX = x + (index % columns) * (cardW + CARD_GAP)
                val cardY = y + (index / columns) * (CARD_H + CARD_GAP)
                if (cardY + CARD_H < contentTop || cardY > contentBottom) continue
                val pointed = mx in cardX until cardX + cardW && my in cardY until cardY + CARD_H
                drawCard(context, card, cardX, cardY, cardW, pointed)
                cardHits += CardHit(cardX, cardY, cardW, CARD_H, card)
            }
            val rows = (section.cards.size + columns - 1) / columns
            y += rows * (CARD_H + CARD_GAP) - CARD_GAP + SECTION_GAP
        }
        return y
    }

    /** "Uncommon ······ 11.3%" in the bucket's colour. */
    private fun drawBucketHeader(context: DrawContext, section: Section, x: Int, y: Int, w: Int) {
        val color = rarityColor(section.bucket)
        val label = Translations.formatRarity(section.bucket)
        val share = percent(section.chance)
        context.fill(x, y, x + 2, y + 8, color)
        context.drawText(textRenderer, label, x + 5, y, color, true)
        val labelEnd = x + 5 + textRenderer.getWidth(label) + 5
        val shareX = x + w - textRenderer.getWidth(share)
        if (shareX - 5 > labelEnd) UiKit.dots(context, labelEnd, y + 4, shareX - 5 - labelEnd)
        context.drawText(textRenderer, share, shareX, y, UiKit.TEXT_MUTED, true)
    }

    private fun drawCard(context: DrawContext, card: Card, x: Int, y: Int, w: Int, pointed: Boolean) {
        UiKit.slot(context, x, y, w, CARD_H)
        if (pointed) UiKit.rowHighlight(context, x, y, w, CARD_H)

        // clipped to the card: a tall Pokémon (Dolman, Moumouflon) otherwise pokes out of
        // the top. The scissor stack intersects, so the list's own clip still applies.
        modelFor(card.species)?.let { widget ->
            widget.x = x + 2
            widget.y = y + MODEL_TOP
            scissor(context, x + 1, y + 1, x + MODEL + 4, y + CARD_H - 1)
            try { widget.render(context, 0, 0, 0f) } catch (_: Exception) {}
            context.draw()
            context.disableScissor()
        }

        val textX = x + MODEL + 5
        val pct = percent(card.chance)
        val pctW = textRenderer.getWidth(pct)
        context.drawText(textRenderer, pct, x + w - 5 - pctW, y + 5, rarityColor(card.bucket), true)
        val name = fit(Translations.pokemonName(card.species), x + w - 5 - pctW - 4 - textX)
        context.drawText(textRenderer, name, textX, y + 5, if (pointed) UiKit.accentBright() else UiKit.TEXT, true)
        context.drawText(textRenderer, levels(card), textX, y + 16, UiKit.TEXT_FAINT, true)
    }

    // ---- Tooltips ----

    private fun cardTooltip(card: Card): List<Text> {
        val lines = mutableListOf<Text>()
        lines += Text.literal(Translations.pokemonName(card.species)).styled { it.withColor(UiKit.accentBright() and 0xFFFFFF) }
        lines += Text.literal(levels(card)).styled { it.withColor(0xAAAAAA) }
        val of = if (phases.size > 1) Translations.tr("of the Pokémon in this phase")
                 else Translations.tr("of the Pokémon of this habitat")
        lines += Text.literal("${percent(card.chance)} $of").styled { it.withColor(0xDDDDDD) }
        if (card.parts.size > 1) {
            for ((bucket, share) in card.parts) {
                lines += Text.literal("  ${Translations.formatRarity(bucket)} ${percent(share)}")
                    .styled { it.withColor(rarityColor(bucket) and 0xFFFFFF) }
            }
        }
        if (speciesOf(card.species) != null) {
            lines += Text.literal(Translations.tr("Click to open its page")).styled { it.withColor(0x777777) }
        }
        return lines
    }

    private fun biomeTooltip(tagLabel: String?, biomes: List<String>): List<OrderedText> {
        val lines = mutableListOf<OrderedText>()
        val title = tagLabel ?: Translations.tr("Generates in:")
        lines += Text.literal(title).styled { it.withColor(UiKit.accentBright() and 0xFFFFFF) }.asOrderedText()
        lines += textRenderer.wrapLines(Text.literal(biomes.joinToString(", ")).styled { it.withColor(0xDDDDDD) }, 220)
        return lines
    }

    private fun technicalTooltip(p: HabitatPlacementInfo): List<OrderedText> {
        val lines = mutableListOf<OrderedText>()
        lines += Text.literal(Translations.tr("Generation")).styled { it.withColor(UiKit.accentBright() and 0xFFFFFF) }.asOrderedText()
        for ((label, value) in placementLines(p)) {
            lines += textRenderer.wrapLines(Text.literal("$label : ").styled { it.withColor(0xAAAAAA) }
                .append(Text.literal(value).styled { it.withColor(0xDDDDDD) }), 240)
        }
        return lines
    }

    // ---- Facts and formatting ----

    /** The structures a habitat is added into, for the ones that have no biomes of their own. */
    private fun hostNames(): String? {
        val p = placement ?: return null
        if (p.kind != HabitatPlacementInfo.Kind.INJECTED || p.hostPools.isEmpty()) return null
        return p.hostPools.map { Translations.prettyId(it.substringAfterLast("/").removeSuffix("_start")) }
            .distinct().joinToString(", ")
    }

    /**
     * The kinds of village whose houses a habitat is added to ("Plains", "Desert"…), from
     * vanilla's `village/<kind>/houses` pools. Structure names, so left untranslated.
     */
    private fun villageVariants(): List<String> {
        val p = placement ?: return emptyList()
        if (p.kind != HabitatPlacementInfo.Kind.INJECTED) return emptyList()
        return p.hostPools.mapNotNull { id ->
            val path = id.substringAfter(":")
            Regex("""village/([a-z_]+)/houses""").find(path)?.groupValues?.get(1)
        }.distinct().map { Translations.prettyId(it) }
    }

    /** What a player can use from the placement: how often, and at what height. */
    private fun keyFacts(p: HabitatPlacementInfo): List<Pair<String, String>> = when (p.kind) {
        HabitatPlacementInfo.Kind.STRUCTURE -> listOfNotNull(
            p.spacing?.let { Translations.tr("Spacing") to "~$it ${Translations.tr("chunks")}" }
        )
        HabitatPlacementInfo.Kind.FEATURE -> listOfNotNull(
            p.chance?.let { Translations.tr("Frequency") to "1 ${Translations.tr("chunk in")} $it" },
            p.heightRange?.let { Translations.tr("Height") to it }
        )
        else -> listOfNotNull(
            if (p.hostWeight != null && p.hostTotalWeight != null && p.hostTotalWeight > 0)
                Translations.tr("Odds") to percent(p.hostWeight * 100.0 / p.hostTotalWeight)
            else null
        )
    }

    /** Every placement fact, for the technical tooltip. */
    private fun placementLines(p: HabitatPlacementInfo): List<Pair<String, String>> {
        val lines = mutableListOf<Pair<String, String>>()

        p.dimension?.let {
            lines.add(Translations.tr("Dimension") to Translations.tr(when (it) {
                "nether" -> "The Nether"
                "end" -> "The End"
                else -> "Overworld"
            }).replaceFirstChar { c -> c.uppercase() })
        }
        lines.add(Translations.tr("Placed by") to Translations.tr(when (p.kind) {
            HabitatPlacementInfo.Kind.STRUCTURE -> "Jigsaw structure"
            HabitatPlacementInfo.Kind.FEATURE -> "Worldgen feature"
            else -> "Added to another structure"
        }))

        if (p.kind == HabitatPlacementInfo.Kind.INJECTED) {
            hostNames()?.let { lines.add(Translations.tr("Inside") to it) }
            if (p.hostWeight != null && p.hostTotalWeight != null && p.hostTotalWeight > 0) {
                lines.add(Translations.tr("Share of the set") to String.format(
                    "%d/%d  (%.1f%%)", p.hostWeight, p.hostTotalWeight,
                    p.hostWeight * 100.0 / p.hostTotalWeight))
            }
            return lines
        }

        if (p.kind == HabitatPlacementInfo.Kind.STRUCTURE) {
            if (p.spacing != null) {
                lines.add(Translations.tr("Spacing") to buildString {
                    append("~").append(p.spacing).append(" ").append(Translations.tr("chunks"))
                    p.separation?.let { append(", ").append(Translations.tr("min")).append(" ").append(it) }
                })
            }
            if (p.setWeight != null && p.setTotalWeight != null && p.setTotalWeight > 0) {
                val share = p.setWeight * 100.0 / p.setTotalWeight
                lines.add(Translations.tr("Share of the set") to
                    String.format("%d/%d  (%.1f%%)", p.setWeight, p.setTotalWeight, share))
            }
            p.startHeight?.let { lines.add(Translations.tr("Start height") to it) }
            p.terrainAdaptation?.let {
                if (it != "none") lines.add(Translations.tr("Terrain") to Translations.tr(Translations.prettyId(it)))
            }
        } else {
            p.chance?.let {
                lines.add(Translations.tr("Frequency") to "1 ${Translations.tr("chunk in")} $it")
            }
            p.count?.let { if (it > 1) lines.add(Translations.tr("Attempts") to "$it") }
            p.heightRange?.let { lines.add(Translations.tr("Height") to it) }
            p.scan?.let { lines.add(Translations.tr("Search") to it) }
            p.verticalOffset?.let { if (it != 0) lines.add(Translations.tr("Offset") to "Y $it") }
        }

        p.step?.let { lines.add(Translations.tr("Step") to Translations.tr(Translations.prettyId(it.lowercase()))) }
        return lines
    }

    private fun key(bucket: String) = bucket.lowercase().trim()

    /**
     * How likely a habitat bucket is, as a weight. Habitats have their own table —
     * `activatedHabitatBuckets`, not `worldBuckets` — and it is read live so a server
     * that retunes it is followed.
     */
    private fun bucketChance(bucket: String): Double {
        val key = key(bucket)
        val configured = runCatching {
            BestSpawner.config.activatedHabitatBuckets[key]?.toDouble()
        }.getOrNull()
        if (configured != null && configured > 0.0) return configured
        return HABITAT_BUCKETS[key] ?: 0.0
    }

    private fun levels(card: Card): String =
        if (card.lvMin == card.lvMax) "${Translations.tr("Lv.")} ${card.lvMin}"
        else "${Translations.tr("Lv.")} ${card.lvMin}–${card.lvMax}"

    private fun percent(value: Double): String = when {
        value <= 0.0 -> "0%"
        value < 0.1 -> "<" + String.format("%.1f%%", 0.1)
        value >= 99.95 -> "100%"
        else -> String.format("%.1f%%", value)
    }

    /** "#cobblemon:nether/is_wasteland" -> "Nether Wasteland" */
    private fun prettyTag(spec: String): String = Translations.biomeLabel(if (spec.startsWith("#")) spec else "#$spec")

    private fun rarityColor(bucket: String): Int = when (key(bucket)) {
        "common" -> 0xFF55CC55.toInt()
        "uncommon" -> 0xFFFFDD55.toInt()
        "rare" -> 0xFF55AAFF.toInt()
        "ultra-rare" -> 0xFFBB66FF.toInt()
        "boss" -> 0xFFFF5544.toInt()
        else -> UiKit.TEXT_MUTED
    }

    private fun fit(text: String, maxWidth: Int): String {
        if (textRenderer.getWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        return textRenderer.trimToWidth(text, (maxWidth - textRenderer.getWidth(ellipsis)).coerceAtLeast(0)).trimEnd() + ellipsis
    }

    // ---- Species and their models ----

    private val speciesCache = HashMap<String, Species?>()
    private val modelCache = HashMap<String, ModelWidget?>()

    /** The pool stores species as normalised keys ("mrmime"); find the Species they stand for. */
    private fun speciesOf(key: String): Species? {
        if (key in speciesCache) return speciesCache[key]
        val found = try {
            PokemonSpecies.species.firstOrNull { NameUtil.normalize(it.name) == key }
                ?: PokemonSpecies.getByName(key)
        } catch (_: Exception) { null }
        speciesCache[key] = found
        return found
    }

    private fun modelFor(key: String): ModelWidget? {
        if (key in modelCache) return modelCache[key]
        val widget = try {
            speciesOf(key)?.let { species ->
                ModelWidget(
                    pX = 0, pY = 0,
                    pWidth = MODEL, pHeight = MODEL,
                    pokemon = RenderablePokemon(species, emptySet()),
                    baseScale = MODEL_SCALE,
                    rotationY = 325f,
                    offsetY = -10.0
                )
            }
        } catch (_: Exception) { null }
        modelCache[key] = widget
        return widget
    }

    // ---- Input ----

    private fun selectPhase(index: Int) {
        if (phases.size <= 1) return
        phaseIndex = index.mod(phases.size)
    }

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            if (mouseX >= backBtnX && mouseX <= backBtnX + backBtnW &&
                mouseY >= backBtnY.toDouble() && mouseY <= (backBtnY + 12).toDouble()) {
                client?.setScreen(parent)
                return true
            }

            val mx = mouseX.toInt()
            val my = mouseY.toInt()
            val inContent = my in (backBtnY + 18) until (height - 25 - 16)
            if (inContent) {
                tabHits.firstOrNull { mx in it.x until it.x + it.w && my in it.y until it.y + it.h }?.let {
                    selectPhase(it.index); return true
                }
                cardHits.firstOrNull { mx in it.x until it.x + it.w && my in it.y until it.y + it.h }?.let { hit ->
                    speciesOf(hit.card.species)?.let { species ->
                        client?.setScreen(PokemonDetailScreen(species, this))
                        return true
                    }
                }

                val count = HabitatStructures.variantCount(habitatId)
                if (count > 1 && mouseY >= previewBtnY.toDouble() && mouseY <= (previewBtnY + 10).toDouble()) {
                    if (mouseX >= prevBtnX && mouseX <= prevBtnX + 10) { variant = (variant - 1).mod(count); return true }
                    if (mouseX >= nextBtnX && mouseX <= nextBtnX + 10) { variant = (variant + 1).mod(count); return true }
                }

                val (bx, by, bw, bh) = previewBox
                if (count > 0 && mouseX >= bx && mouseX <= bx + bw && mouseY >= by.toDouble() && mouseY <= (by + bh).toDouble()) {
                    isDraggingModel = true
                    return true
                }
            }
        }
        return super.mouseClickedScaled(mouseX, mouseY, button)
    }

    override fun mouseDraggedScaled(mouseX: Double, mouseY: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (isDraggingModel) {
            yaw += dx.toFloat() * 1.5f
            pitch = (pitch + dy.toFloat()).coerceIn(-89f, 89f)
            return true
        }
        return super.mouseDraggedScaled(mouseX, mouseY, button, dx, dy)
    }

    override fun mouseReleasedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        isDraggingModel = false
        return super.mouseReleasedScaled(mouseX, mouseY, button)
    }

    override fun mouseScrolledScaled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
        val maxScroll = (contentHeight - contentAreaHeight).coerceAtLeast(0)
        scrollOffset = (scrollOffset - (vAmount * 12).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE -> { client?.setScreen(parent); return true }
            GLFW.GLFW_KEY_LEFT -> if (phases.size > 1) { selectPhase(phaseIndex - 1); return true }
            GLFW.GLFW_KEY_RIGHT -> if (phases.size > 1) { selectPhase(phaseIndex + 1); return true }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun shouldPause(): Boolean = false
}
