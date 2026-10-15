package com.hunterboard

import com.cobblemon.mod.common.api.spawning.BestSpawner
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.DiffuseLighting
import net.minecraft.client.render.OverlayTexture
import net.minecraft.util.math.RotationAxis
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * Everything one habitat pool can produce.
 *
 * A habitat block cycles through phases (one per Minecraft day) and only the species
 * of the current phase can spawn, so the list is grouped by phase rather than flattened.
 */
class HabitatDetailScreen(
    private val habitatId: String,
    private val parent: Screen
) : ScaledScreen(Text.literal("Habitat")) {

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

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

    private data class SpeciesRow(val phase: Int, val species: String, val entry: HabitatEntry)

    private val rows: List<SpeciesRow> by lazy {
        HabitatData.speciesIn(habitatId).flatMap { (species, entry) ->
            val phases = entry.phases.ifEmpty { listOf(0) }
            phases.map { SpeciesRow(it, species, entry) }
        }
    }

    private val phases: List<Int> by lazy { rows.map { it.phase }.distinct().sorted() }

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        UiKit.screenDim(context, width, height)

        val panelWidth = (width * 0.7).toInt().coerceIn(320, 620)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop
        val leftX = panelX + 10

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

        scissor(context, panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)
        var y = contentTop + 4 - scrollOffset

        // ---- Structure preview ----
        y = renderPreview(context, panelX, panelWidth, y, mouseX, mouseY)

        // ---- Biomes ----
        // Structures state their biomes in their own file; features state them nowhere,
        // so HabitatPlacement supplies the tag it recovered from Cobblemon's code.
        val placement = HabitatPlacement.forHabitat(habitatId)
        var biomes = HabitatData.biomesFor(habitatId)
        var tagLabel = HabitatData.biomeTagLabel(habitatId)
        if (tagLabel == null && placement != null && placement.biomeSpecs.isNotEmpty()) {
            tagLabel = placement.biomeSpecs.joinToString(", ") { spec -> prettyTag(spec) }
            biomes = placement.biomeSpecs.flatMap { spec ->
                if (spec.startsWith("#")) BiomeTagResolver.resolve(spec.removePrefix("#")) else listOf(spec)
            }.distinct()
        }
        context.drawText(textRenderer, Translations.tr("Generates in:"), leftX, y, UiKit.accent(), true)
        y += 12
        if (tagLabel != null) {
            context.drawText(textRenderer, "\u25C6 $tagLabel", leftX + 4, y, 0xFF88BBAA.toInt(), true)
            y += 11
        }
        if (biomes.isEmpty()) {
            val msg = if (tagLabel == null) Translations.tr("Placement unknown")
                      else Translations.tr("Biomes unknown")
            context.drawText(textRenderer, msg, leftX + 4, y, UiKit.TEXT_FAINT, true)
            y += 11
        } else {
            y = drawWrapped(context, biomes.joinToString(", "), leftX + 4, y, panelWidth - 28, UiKit.TEXT_MUTED)
        }
        y += 6

        // ---- How the world places it ----
        if (placement != null) {
            context.drawText(textRenderer, Translations.tr("Generation"), leftX, y, UiKit.accent(), true)
            y += 12
            for ((name, value) in placementLines(placement)) {
                context.drawText(textRenderer, name, leftX + 4, y, UiKit.TEXT_FAINT, true)
                context.drawText(textRenderer, value, leftX + 104, y, UiKit.TEXT_MUTED, true)
                y += 11
            }
            y += 6
        }

        // ---- Summary ----
        val speciesCount = rows.map { it.species }.distinct().size
        val phaseCount = phases.filter { it > 0 }.size
        val summary = "$speciesCount ${Translations.tr("species")} · " +
            "${if (phaseCount > 0) phaseCount else 1} ${Translations.tr("phases")}"
        context.drawText(textRenderer, summary, leftX, y, UiKit.TEXT_FAINT, true)
        y += 14

        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 6

        // ---- Species, grouped by phase ----
        for (phase in phases) {
            val group = rows.filter { it.phase == phase }
            if (group.isEmpty()) continue

            val phaseLabel = if (phase <= 0) Translations.tr("Any phase")
                             else "${Translations.tr("Phase")} $phase"
            context.drawText(textRenderer, phaseLabel, leftX, y, UiKit.accent(), true)
            // Weights only compete inside a bucket, so the share shown is the share of
            // that bucket, not of the whole phase — comparing a rare's weight against a
            // common's would be meaningless.
            val bucketTotals = group.groupBy { it.entry.bucket.lowercase().trim() }
                .mapValues { (_, inBucket) -> inBucket.sumOf { it.entry.weight.toDouble() } }
            val countText = "${group.size}"
            context.drawText(textRenderer, countText,
                panelX + panelWidth - 14 - textRenderer.getWidth(countText), y, UiKit.TEXT_FAINT, true)
            y += 12

            // rarest last: by how likely the bucket is, then by weight inside it
            val ordered = group.sortedWith(
                compareByDescending<SpeciesRow> { bucketChance(it.entry.bucket) }
                    .thenByDescending { it.entry.weight }
                    .thenBy { Translations.pokemonName(it.species) }
            )
            for (row in ordered) {
                if (y > contentBottom + 12) { y += 11; continue }
                val name = Translations.pokemonName(row.species)
                context.drawText(textRenderer, name, leftX + 6, y, UiKit.TEXT, true)

                val bucketTotal = bucketTotals[row.entry.bucket.lowercase().trim()] ?: 0.0
                val share = if (bucketTotal > 0) row.entry.weight / bucketTotal * 100.0 else 0.0
                val detail = buildString {
                    append("Lv.${row.entry.lvMin}-${row.entry.lvMax}")
                    append("  ")
                    append(Translations.formatRarity(row.entry.bucket))
                    if (row.entry.time != "any") {
                        append("  ")
                        append(Translations.formatTime(row.entry.time))
                    }
                }
                context.drawText(textRenderer, detail, leftX + 106, y, UiKit.TEXT_MUTED, true)

                val pctText = String.format("%.1f%%", share)
                context.drawText(textRenderer, pctText,
                    panelX + panelWidth - 14 - textRenderer.getWidth(pctText), y,
                    rarityColor(row.entry.bucket), true)
                y += 11
            }
            y += 6
        }

        contentHeight = y - (contentTop + 4 - scrollOffset) + 8
        context.disableScissor()

        UiKit.scrollbar(context, panelX + panelWidth - 5, contentTop, contentBottom, contentHeight, scrollOffset)

        val hint: String = Translations.tr("ESC / Click Back to return")
        UiKit.footer(context, textRenderer, panelX, panelBottom, panelWidth, hint)
    }

    /**
     * A rough idea of what the habitat looks like.
     *
     * Cobblemon assembles a habitat with a jigsaw, so there is no single "the"
     * structure — this shows one self-contained variant out of however many ship
     * with the pool, and the arrows cycle through them.
     */
    private fun renderPreview(context: DrawContext, panelX: Int, panelWidth: Int, startY: Int, mouseX: Int, mouseY: Int): Int {
        val count = HabitatStructures.variantCount(habitatId)
        if (count <= 0) return startY

        val preview = HabitatStructures.preview(habitatId, variant)
        val boxW = panelWidth - 20
        val boxH = 168
        val boxX = panelX + 10
        val boxY = startY
        previewBox = intArrayOf(boxX, boxY, boxW, boxH)

        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, UiKit.SURFACE_SUNKEN)
        UiKit.border(context, boxX, boxY, boxW, boxH, UiKit.BORDER_DIM)

        if (preview == null) {
            val msg = Translations.tr("Preview unavailable")
            context.drawText(textRenderer, msg,
                boxX + (boxW - textRenderer.getWidth(msg)) / 2, boxY + boxH / 2 - 4, UiKit.TEXT_FAINT, true)
        } else {
            drawStructure(context, boxX, boxY, boxW, boxH, preview)
            val dims = "${preview.sizeX}\u00d7${preview.sizeY}\u00d7${preview.sizeZ}"
            context.drawText(textRenderer, dims, boxX + 4, boxY + boxH - 10, UiKit.TEXT_FAINT, true)
        }

        // variant navigation
        previewBtnY = boxY + 3
        val label = "${variant.mod(count) + 1}/$count"
        val labelW = textRenderer.getWidth(label)
        nextBtnX = boxX + boxW - 14
        prevBtnX = nextBtnX - 12 - labelW - 6
        if (count > 1) {
            val prevHover = mouseX in prevBtnX..(prevBtnX + 10) && mouseY in previewBtnY..(previewBtnY + 10)
            val nextHover = mouseX in nextBtnX..(nextBtnX + 10) && mouseY in previewBtnY..(previewBtnY + 10)
            context.drawText(textRenderer, "\u25C0", prevBtnX, previewBtnY, if (prevHover) UiKit.accent() else UiKit.TEXT_MUTED, true)
            context.drawText(textRenderer, label, prevBtnX + 12, previewBtnY, UiKit.TEXT_FAINT, true)
            context.drawText(textRenderer, "\u25B6", nextBtnX, previewBtnY, if (nextHover) UiKit.accent() else UiKit.TEXT_MUTED, true)
        }

        return boxY + boxH + 6
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

    private fun drawWrapped(context: DrawContext, text: String, x: Int, startY: Int, maxWidth: Int, color: Int): Int {
        var y = startY
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (textRenderer.getWidth(candidate) > maxWidth && line.isNotEmpty()) {
                context.drawText(textRenderer, line.toString(), x, y, color, true)
                y += 10
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) {
            context.drawText(textRenderer, line.toString(), x, y, color, true)
            y += 10
        }
        return y
    }

    /**
     * How likely a habitat bucket is, as a percentage.
     *
     * Cobblemon rolls the **bucket first** and only then picks by weight among the spawns
     * of that bucket, so weight alone says nothing about rarity: a common with weight 1
     * beats a rare with weight 50. Habitats have their own table — `activatedHabitatBuckets`,
     * not `worldBuckets` — and it is read live so a server that retunes it is followed.
     */
    private fun bucketChance(bucket: String): Double {
        val key = bucket.lowercase().trim()
        val configured = runCatching {
            BestSpawner.config.activatedHabitatBuckets[key]?.toDouble()
        }.getOrNull()
        if (configured != null && configured > 0.0) return configured
        return HABITAT_BUCKETS[key] ?: 0.0
    }

    /** Cobblemon's shipped activatedHabitatBuckets, used when the config cannot be read. */
    private val HABITAT_BUCKETS = mapOf(
        "common" to 83.25, "uncommon" to 11.25, "rare" to 4.125, "ultra-rare" to 1.375
    )

    /** "#cobblemon:nether/is_wasteland" -> "Nether Wasteland" */
    private fun prettyTag(spec: String): String = spec
        .removePrefix("#").substringAfter(":")
        .removePrefix("is_").replace("/", " ").replace("_", " ")
        .split(" ").filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.replaceFirstChar { c -> c.uppercase() } }

    /** The placement facts worth showing, as label/value pairs. */
    private fun placementLines(p: HabitatPlacementInfo): List<Pair<String, String>> {
        val lines = mutableListOf<Pair<String, String>>()

        p.dimension?.let {
            lines.add(Translations.tr("Dimension") to Translations.tr(when (it) {
                "nether" -> "The Nether"
                "end" -> "The End"
                else -> "Overworld"
            }))
        }
        lines.add(Translations.tr("Placed by") to Translations.tr(when (p.kind) {
            HabitatPlacementInfo.Kind.STRUCTURE -> "Jigsaw structure"
            HabitatPlacementInfo.Kind.FEATURE -> "Worldgen feature"
            else -> "Added to another structure"
        }))

        if (p.kind == HabitatPlacementInfo.Kind.INJECTED) {
            if (p.hostPools.isNotEmpty()) {
                lines.add(Translations.tr("Inside") to p.hostPools
                    .joinToString(", ") { it.substringAfter("/").replace("/", " ") }
                    .take(64))
            }
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
                if (it != "none") lines.add(Translations.tr("Terrain") to it.replace("_", " "))
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

        p.step?.let { lines.add(Translations.tr("Step") to it.lowercase().replace("_", " ")) }
        return lines
    }

    private fun rarityColor(bucket: String): Int = when (bucket.lowercase().trim()) {
        "common" -> 0xFF55CC55.toInt()
        "uncommon" -> 0xFFFFDD55.toInt()
        "rare" -> 0xFF55AAFF.toInt()
        "ultra-rare" -> 0xFFBB66FF.toInt()
        "boss" -> 0xFFFF5544.toInt()
        else -> UiKit.TEXT_MUTED
    }

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            if (mouseX >= backBtnX && mouseX <= backBtnX + backBtnW &&
                mouseY >= backBtnY.toDouble() && mouseY <= (backBtnY + 12).toDouble()) {
                client?.setScreen(parent)
                return true
            }

            val count = HabitatStructures.variantCount(habitatId)
            if (count > 1 && mouseY >= previewBtnY.toDouble() && mouseY <= (previewBtnY + 10).toDouble()) {
                if (mouseX >= prevBtnX && mouseX <= prevBtnX + 10) { variant = (variant - 1).mod(count); return true }
                if (mouseX >= nextBtnX && mouseX <= nextBtnX + 10) { variant = (variant + 1).mod(count); return true }
            }

            val (bx, by, bw, bh) = previewBox
            if (mouseX >= bx && mouseX <= bx + bw && mouseY >= by.toDouble() && mouseY <= (by + bh).toDouble()) {
                isDraggingModel = true
                return true
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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { client?.setScreen(parent); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun shouldPause(): Boolean = false
}
