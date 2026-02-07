package com.hunterboard

import com.cobblemon.mod.common.api.abilities.PotentialAbility
import com.cobblemon.mod.common.api.drop.ItemDropEntry
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class PokemonDetailScreen(
    private val species: Species,
    private val parent: Screen
) : Screen(Text.literal("Pokemon Detail")) {

    private var modelWidget: ModelWidget? = null
    private var scrollOffset = 0
    private var contentHeight = 0

    // Move tabs
    private var selectedTab = 0
    private val tabLabels = arrayOf("Level-Up", "TM", "Egg", "Tutor")
    private val tabBounds = Array(4) { IntArray(4) } // x, y, w, h for each tab

    // Cached move lists (avoid recomputing every frame)
    private var cachedMoves: Array<List<MoveEntry>?> = arrayOfNulls(4)

    // Hover tooltip state
    private var hoveredMoveIndex = -1
    private var hoverStartTime = 0L
    private var tooltipMove: MoveTemplate? = null

    // Ability hover tooltip state
    private var hoveredAbilityIndex = -1
    private var abilityHoverStartTime = 0L
    private var tooltipAbility: PotentialAbility? = null

    // Cached row bounds for hover detection
    private data class MoveRowBounds(val y: Int, val h: Int, val move: MoveTemplate)
    private var moveRowBounds = mutableListOf<MoveRowBounds>()
    private data class AbilityRowBounds(val x: Int, val y: Int, val w: Int, val h: Int, val ability: PotentialAbility)
    private var abilityRowBounds = mutableListOf<AbilityRowBounds>()

    // Model click bounds (updated each frame)
    private var modelRenderX = 0
    private var modelRenderY = 0

    // Biome hover tooltip
    private var hoveredBiomeDetail: BiomeDetail? = null

    // Shiny toggle
    private var isShiny = false
    private var shinyBtnX = 0
    private var shinyBtnY = 0
    private val shinyBtnW = 14
    private val shinyBtnH = 14

    companion object {
        private const val MODEL_SIZE = 80
        private const val STAT_BAR_MAX = 255
        private const val HOVER_DELAY_MS = 500L
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        SpawnData.ensureLoaded()
        rebuildModel()
    }

    private fun rebuildModel() {
        try {
            val aspects = if (isShiny) setOf("shiny") else emptySet()
            val pokemon = RenderablePokemon(species, aspects)
            modelWidget = ModelWidget(
                pX = 0, pY = 0,
                pWidth = MODEL_SIZE, pHeight = MODEL_SIZE,
                pokemon = pokemon,
                baseScale = 3.0f,
                rotationY = 325f,
                offsetY = -18.0,
                playCryOnClick = true
            )
        } catch (_: Exception) {}
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        hoveredBiomeDetail = null
        context.fill(0, 0, width, height, 0xAA000000.toInt())

        val panelWidth = (width * 0.8).toInt().coerceIn(380, 620)
        val panelX = (width - panelWidth) / 2
        val panelTop = 10
        val panelBottom = height - 10
        val panelHeight = panelBottom - panelTop

        context.fill(panelX, panelTop, panelX + panelWidth, panelBottom, 0xF0101010.toInt())
        drawBorder(context, panelX, panelTop, panelWidth, panelHeight, 0xFFFFAA00.toInt())

        // Header
        val backText = "\u2190 Back"
        val backHovered = mouseX >= panelX + 6 && mouseX <= panelX + 6 + textRenderer.getWidth(backText) + 4 &&
                          mouseY >= panelTop + 5 && mouseY <= panelTop + 17
        context.drawText(textRenderer, backText, panelX + 8, panelTop + 6,
            if (backHovered) 0xFFFFAA00.toInt() else 0xFFAAAAAA.toInt(), true)

        val displayName = species.translatedName.string
        context.drawText(textRenderer, displayName,
            panelX + (panelWidth - textRenderer.getWidth(displayName)) / 2, panelTop + 6, 0xFFFFFFFF.toInt(), true)

        val dexText = "#${String.format("%04d", species.nationalPokedexNumber)}"
        context.drawText(textRenderer, dexText,
            panelX + panelWidth - textRenderer.getWidth(dexText) - 8, panelTop + 6, 0xFF777777.toInt(), true)

        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, 0xFFFFAA00.toInt())

        // Scrollable content
        val contentTop = panelTop + 22
        val contentBottom = panelBottom - 16
        val contentAreaHeight = contentBottom - contentTop

        context.enableScissor(panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)

        var y = contentTop + 2 - scrollOffset
        val leftX = panelX + 8
        val halfW = (panelWidth - 20) / 2
        val midX = panelX + 10 + halfW + 4

        // Clear row bounds for this frame
        moveRowBounds.clear()
        abilityRowBounds.clear()

        // ========== ROW 1: Model + Info (left) | Base Stats (right) ==========

        // --- LEFT: Model + Types + Abilities + EV Yield ---
        if (modelWidget != null) {
            try {
                modelRenderX = leftX
                modelRenderY = y - 10
                modelWidget!!.x = modelRenderX
                modelWidget!!.y = modelRenderY
                modelWidget!!.render(context, mouseX, mouseY, delta)
            } catch (_: Exception) {}
        }

        // Shiny toggle button (centered below model)
        shinyBtnX = leftX + (MODEL_SIZE - shinyBtnW) / 2
        shinyBtnY = y - 10 + MODEL_SIZE + 2
        val shinyHovered = mouseX >= shinyBtnX && mouseX <= shinyBtnX + shinyBtnW &&
                           mouseY >= shinyBtnY && mouseY <= shinyBtnY + shinyBtnH
        val shinyBg = when {
            isShiny && shinyHovered -> 0xFF3A3000.toInt()
            isShiny -> 0xFF2A2200.toInt()
            shinyHovered -> 0xFF303030.toInt()
            else -> 0xFF1E1E1E.toInt()
        }
        context.fill(shinyBtnX, shinyBtnY, shinyBtnX + shinyBtnW, shinyBtnY + shinyBtnH, shinyBg)
        val shinyBorder = if (isShiny) 0xFFFFAA00.toInt() else if (shinyHovered) 0xFF666666.toInt() else 0xFF444444.toInt()
        drawBorder(context, shinyBtnX, shinyBtnY, shinyBtnW, shinyBtnH, shinyBorder)
        val starColor = if (isShiny) 0xFFFFDD00.toInt() else 0xFF888888.toInt()
        val star = "\u2726"
        val starW = textRenderer.getWidth(star)
        context.drawText(textRenderer, star, shinyBtnX + (shinyBtnW - starW) / 2, shinyBtnY + 3, starColor, true)

        val infoX = leftX + MODEL_SIZE + 6
        var leftY = y + 4

        // Types
        try {
            val primary = species.primaryType
            val secondary = species.secondaryType
            var typeX = infoX
            drawTypeBadge(context, typeX, leftY, primary.displayName.string)
            typeX += textRenderer.getWidth(primary.displayName.string) + 12
            if (secondary != null) {
                drawTypeBadge(context, typeX, leftY, secondary.displayName.string)
            }
        } catch (_: Exception) {}
        leftY += 16

        // Abilities
        context.drawText(textRenderer, "Abilities", infoX, leftY, 0xFFFFAA00.toInt(), true)
        leftY += 11
        try {
            for (ability in species.abilities) {
                val pa = ability as? PotentialAbility ?: continue
                val isHidden = pa is HiddenAbility
                val abilityName = Text.translatable(pa.template.displayName).string
                val color = if (isHidden) 0xFFFFDD55.toInt() else 0xFFCCCCCC.toInt()
                context.drawText(textRenderer, abilityName, infoX + 4, leftY, color, true)
                var totalW = textRenderer.getWidth(abilityName)
                if (isHidden) {
                    val tagX = infoX + 4 + totalW + 4
                    context.drawText(textRenderer, "(H)", tagX, leftY, 0xFFAA8833.toInt(), true)
                    totalW += 4 + textRenderer.getWidth("(H)")
                }
                abilityRowBounds.add(AbilityRowBounds(infoX + 4, leftY, totalW, 10, pa))
                leftY += 10
            }
        } catch (_: Exception) {}
        leftY += 4

        // EV Yield
        context.drawText(textRenderer, "EV Yield", infoX, leftY, 0xFFFFAA00.toInt(), true)
        leftY += 11
        try {
            val evYield = species.evYield
            val evParts = mutableListOf<String>()
            val statOrder = listOf(Stats.HP to "HP", Stats.ATTACK to "Atk", Stats.DEFENCE to "Def",
                Stats.SPECIAL_ATTACK to "SpAtk", Stats.SPECIAL_DEFENCE to "SpDef", Stats.SPEED to "Spd")
            for ((stat, label) in statOrder) {
                val value = evYield[stat]
                if (value != null && value > 0) evParts.add("$label $value")
            }
            context.drawText(textRenderer, if (evParts.isEmpty()) "None" else evParts.joinToString(", "),
                infoX + 4, leftY, 0xFFCCCCCC.toInt(), true)
        } catch (_: Exception) {}
        leftY += 12

        val leftEndY = maxOf(leftY, y + MODEL_SIZE + 6)

        // --- RIGHT: Base Stats ---
        context.drawText(textRenderer, "Base Stats", midX, y, 0xFFFFAA00.toInt(), true)
        var statsY = y + 13

        val statEntries = listOf(
            Stats.HP to "HP", Stats.ATTACK to "Attack", Stats.DEFENCE to "Defence",
            Stats.SPECIAL_ATTACK to "SpAtk", Stats.SPECIAL_DEFENCE to "SpDef", Stats.SPEED to "Speed"
        )
        val labelWidth = 42
        val barX = midX + labelWidth
        val barMaxW = halfW - labelWidth - 30

        for ((stat, label) in statEntries) {
            val value = species.baseStats[stat] ?: 0
            context.drawText(textRenderer, label, midX, statsY, 0xFFBBBBBB.toInt(), true)
            context.fill(barX, statsY + 1, barX + barMaxW, statsY + 8, 0xFF222222.toInt())
            val fillW = (value * barMaxW / STAT_BAR_MAX).coerceIn(1, barMaxW)
            context.fill(barX, statsY + 1, barX + fillW, statsY + 8, getStatColor(value))
            context.drawText(textRenderer, "$value", barX + barMaxW + 4, statsY, 0xFFFFFFFF.toInt(), true)
            statsY += 12
        }

        val total = statEntries.sumOf { species.baseStats[it.first] ?: 0 }
        context.drawText(textRenderer, "Total", midX, statsY, 0xFFBBBBBB.toInt(), true)
        context.drawText(textRenderer, "$total", barX + barMaxW + 4, statsY, 0xFFFFFFFF.toInt(), true)
        statsY += 12

        y = maxOf(leftEndY, statsY) + 4

        // ========== SEPARATOR ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
        y += 6

        // ========== ROW 2: Spawn Conditions (left) | Dropped Items (right) ==========
        val row2StartY = y

        context.drawText(textRenderer, "Spawn Conditions", leftX, y, 0xFFFFAA00.toInt(), true)
        var spawnY = y + 13

        val spawns = SpawnData.getSpawns(species.name)
        if (spawns.isNotEmpty()) {
            val rarity = spawns.firstOrNull()?.bucket ?: ""
            context.drawText(textRenderer, "Rarity: ", leftX + 4, spawnY, 0xFFBBBBBB.toInt(), true)
            context.drawText(textRenderer, formatRarity(rarity),
                leftX + 4 + textRenderer.getWidth("Rarity: "), spawnY, getRarityColor(rarity), true)
            spawnY += 11

            val lvMin = spawns.minOf { it.lvMin }
            val lvMax = spawns.maxOf { it.lvMax }
            context.drawText(textRenderer, "Lv. $lvMin-$lvMax", leftX + 4, spawnY, 0xFFBBBBBB.toInt(), true)
            spawnY += 13

            val seen = mutableSetOf<String>()
            for (spawn in spawns) {
                val key = "${spawn.biomes}|${spawn.time}|${spawn.weather}|${spawn.structures}|${spawn.canSeeSky}"
                if (key in seen) continue
                seen.add(key)

                if (spawn.biomeDetails.isEmpty()) {
                    context.drawText(textRenderer, "\u25CF Any", leftX + 8, spawnY, 0xFF999999.toInt(), true)
                    spawnY += 10
                } else {
                    spawnY = renderBiomesInline(context, spawn.biomeDetails, leftX + 8, spawnY,
                        halfW - 12, mouseX, mouseY, contentTop, contentBottom)
                }

                // Structures
                if (spawn.structures.isNotEmpty()) {
                    val structText = "\u2302 " + spawn.structures.joinToString(", ")
                    context.drawText(textRenderer, structText, leftX + 8, spawnY, 0xFFCC8844.toInt(), true)
                    spawnY += 10
                }

                val timeStr = if (spawn.time == "any") "Any time" else spawn.time.replaceFirstChar { it.uppercase() }
                val weatherStr = if (spawn.weather == "any") "Any weather" else spawn.weather.replaceFirstChar { it.uppercase() }
                val skyStr = when (spawn.canSeeSky) {
                    true -> " | Outdoor"
                    false -> " | Underground"
                    else -> ""
                }
                context.drawText(textRenderer, "  $timeStr | $weatherStr$skyStr", leftX + 8, spawnY, 0xFF707070.toInt(), true)
                spawnY += 12
            }
        } else {
            context.drawText(textRenderer, "No spawn data", leftX + 4, spawnY, 0xFF666666.toInt(), true)
            spawnY += 12
        }

        // Vertical separator
        context.fill(panelX + 10 + halfW, row2StartY, panelX + 10 + halfW + 1,
            maxOf(spawnY, row2StartY + 30), 0xFF333333.toInt())

        // Dropped Items (right)
        context.drawText(textRenderer, "Dropped Items", midX, row2StartY, 0xFFFFAA00.toInt(), true)
        var dropY = row2StartY + 13

        try {
            val entries = species.drops.entries
            if (entries.isEmpty()) {
                context.drawText(textRenderer, "None", midX + 4, dropY, 0xFF666666.toInt(), true)
                dropY += 12
            } else {
                for (entry in entries) {
                    if (entry is ItemDropEntry) {
                        val item = Registries.ITEM.get(entry.item)
                        val stack = ItemStack(item)
                        // Draw item icon (16x16)
                        if (!stack.isEmpty) {
                            context.drawItem(stack, midX + 4, dropY - 4)
                        }
                        val textStartX = midX + 24
                        val qty = entry.quantityRange
                        val qtyText = if (qty != null && qty.first != qty.last) "${qty.first}-${qty.last}" else "${entry.quantity}"
                        context.drawText(textRenderer, "${item.name.string} ${entry.percentage.toInt()}%",
                            textStartX, dropY, 0xFFCCCCCC.toInt(), true)
                        context.drawText(textRenderer, "Qty: $qtyText",
                            textStartX, dropY + 10, 0xFF999999.toInt(), true)
                        dropY += 22
                    }
                }
            }
        } catch (_: Exception) {
            context.drawText(textRenderer, "N/A", midX + 4, dropY, 0xFF666666.toInt(), true)
            dropY += 12
        }

        y = maxOf(spawnY, dropY) + 4

        // ========== SEPARATOR ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
        y += 8

        // ========== MOVE TABS ==========
        val tabY = y
        var tabX = leftX
        for (i in tabLabels.indices) {
            val label = tabLabels[i]
            val tw = textRenderer.getWidth(label) + 12
            val isSelected = i == selectedTab
            val isTabHovered = mouseX >= tabX && mouseX <= tabX + tw &&
                               mouseY >= tabY && mouseY <= tabY + 14

            val bg = when {
                isSelected -> 0xFF2A2200.toInt()
                isTabHovered -> 0xFF252525.toInt()
                else -> 0xFF1A1A1A.toInt()
            }
            context.fill(tabX, tabY, tabX + tw, tabY + 14, bg)

            val borderColor = if (isSelected) 0xFFFFAA00.toInt() else 0xFF444444.toInt()
            // Top + sides border
            context.fill(tabX, tabY, tabX + tw, tabY + 1, borderColor)
            context.fill(tabX, tabY, tabX + 1, tabY + 14, borderColor)
            context.fill(tabX + tw - 1, tabY, tabX + tw, tabY + 14, borderColor)
            if (isSelected) {
                // Remove bottom border for selected tab (merge with content)
                context.fill(tabX + 1, tabY + 13, tabX + tw - 1, tabY + 14, 0xF0101010.toInt())
            }

            val textColor = when {
                isSelected -> 0xFFFFAA00.toInt()
                isTabHovered -> 0xFFDDDDDD.toInt()
                else -> 0xFF888888.toInt()
            }
            context.drawText(textRenderer, label, tabX + 6, tabY + 3, textColor, true)

            tabBounds[i] = intArrayOf(tabX, tabY, tw, 14)
            tabX += tw + 2
        }

        // Tab content border
        y = tabY + 14
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF444444.toInt())
        y += 6

        // ========== ACTIVE MOVE LIST ==========
        val moves = cachedMoves[selectedTab] ?: run {
            val computed = when (selectedTab) {
                0 -> getLevelUpMoves()
                1 -> getTmMoves()
                2 -> getEggMoves()
                3 -> getTutorMoves()
                else -> emptyList()
            }
            cachedMoves[selectedTab] = computed
            computed
        }
        val showLevel = selectedTab == 0

        if (moves.isEmpty()) {
            val emptyText = "No ${tabLabels[selectedTab]} moves"
            context.drawText(textRenderer, emptyText,
                panelX + (panelWidth - textRenderer.getWidth(emptyText)) / 2, y + 6, 0xFF666666.toInt(), true)
            y += 22
        } else {
            y = renderMoveTable(context, moves, y, panelX, panelWidth, leftX, showLevel, mouseX, mouseY, contentTop, contentBottom)
        }

        contentHeight = y + scrollOffset - contentTop + 8

        context.disableScissor()

        // Move tooltip (after scissor so it draws on top)
        if (tooltipMove != null) {
            val now = System.currentTimeMillis()
            if (now - hoverStartTime >= HOVER_DELAY_MS) {
                try {
                    val desc = tooltipMove!!.description.string
                    if (desc.isNotEmpty()) {
                        val lines = mutableListOf<Text>()
                        lines.add(Text.literal(tooltipMove!!.displayName.string).styled { it.withBold(true) })
                        val maxTooltipW = 200
                        for (wLine in wrapText(desc, maxTooltipW)) {
                            lines.add(Text.literal(wLine))
                        }
                        context.drawTooltip(textRenderer, lines, mouseX, mouseY)
                    }
                } catch (_: Exception) {}
            }
        }

        // Ability tooltip
        if (tooltipAbility != null) {
            val now = System.currentTimeMillis()
            if (now - abilityHoverStartTime >= HOVER_DELAY_MS) {
                try {
                    val tmpl = tooltipAbility!!.template
                    val abilityName = Text.translatable(tmpl.displayName).string
                    val desc = Text.translatable(tmpl.description).string
                    if (desc.isNotEmpty()) {
                        val lines = mutableListOf<Text>()
                        lines.add(Text.literal(abilityName).styled { it.withBold(true) })
                        val maxTooltipW = 200
                        for (wLine in wrapText(desc, maxTooltipW)) {
                            lines.add(Text.literal(wLine))
                        }
                        context.drawTooltip(textRenderer, lines, mouseX, mouseY)
                    }
                } catch (_: Exception) {}
            }
        }

        // Biome tooltip
        if (hoveredBiomeDetail != null && hoveredBiomeDetail!!.tagId != null) {
            val resolved = BiomeTagResolver.resolve(hoveredBiomeDetail!!.tagId!!)
            if (resolved.isNotEmpty()) {
                val lines = mutableListOf<Text>()
                lines.add(Text.literal(hoveredBiomeDetail!!.displayName).styled { it.withBold(true).withColor(0xFFAA00) })
                for (name in resolved) {
                    lines.add(Text.literal("  $name").styled { it.withColor(0xCCCCCC) })
                }
                context.drawTooltip(textRenderer, lines, mouseX, mouseY)
            }
        }

        // Scrollbar
        if (contentHeight > contentAreaHeight && contentAreaHeight > 0) {
            val trackX = panelX + panelWidth - 5
            context.fill(trackX, contentTop, trackX + 3, contentBottom, 0xFF1A1A1A.toInt())
            val thumbHeight = maxOf(15, contentAreaHeight * contentAreaHeight / contentHeight)
            val maxScroll = contentHeight - contentAreaHeight
            val thumbY = contentTop + (scrollOffset * (contentAreaHeight - thumbHeight) / maxOf(1, maxScroll))
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFFFAA00.toInt())
        }

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint = "ESC / Click Back to return"
        context.drawText(textRenderer, hint,
            panelX + (panelWidth - textRenderer.getWidth(hint)) / 2, panelBottom - 10, 0xFF555555.toInt(), true)

        // Update hover state
        updateHoverState(mouseX, mouseY, panelX, panelWidth, contentTop, contentBottom)
    }

    // ==================== Move Rendering ====================

    data class MoveEntry(val level: Int, val move: MoveTemplate)

    private fun getLevelUpMoves(): List<MoveEntry> {
        return try {
            val apiMoves = species.moves.levelUpMoves.flatMap { (level, moves) ->
                moves.map { MoveEntry(level, it) }
            }.sortedBy { it.level }
            if (apiMoves.isNotEmpty()) return apiMoves

            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.levelUp.map { MoveEntry(it.first, it.second) }
        } catch (_: Exception) { emptyList() }
    }

    private fun getTmMoves(): List<MoveEntry> {
        return try {
            val apiMoves = species.moves.tmMoves.map { MoveEntry(-1, it) }.sortedBy { it.move.displayName.string }
            if (apiMoves.isNotEmpty()) return apiMoves

            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.tm.map { MoveEntry(-1, it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun getEggMoves(): List<MoveEntry> {
        return try {
            val apiMoves = species.moves.eggMoves.map { MoveEntry(-1, it) }.sortedBy { it.move.displayName.string }
            if (apiMoves.isNotEmpty()) return apiMoves

            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.egg.map { MoveEntry(-1, it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun getTutorMoves(): List<MoveEntry> {
        return try {
            val apiMoves = species.moves.tutorMoves.map { MoveEntry(-1, it) }.sortedBy { it.move.displayName.string }
            if (apiMoves.isNotEmpty()) return apiMoves

            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.tutor.map { MoveEntry(-1, it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun renderMoveTable(
        context: DrawContext, moves: List<MoveEntry>,
        startY: Int, panelX: Int, panelWidth: Int, leftX: Int,
        showLevel: Boolean, mouseX: Int, mouseY: Int,
        contentTop: Int, contentBottom: Int
    ): Int {
        var y = startY
        val rowH = 14
        val contentW = panelWidth - 16

        // Column layout - centered within panel
        val tableX = panelX + 8
        val colLvl = tableX
        val colName = if (showLevel) colLvl + 24 else colLvl
        val colType = colName + 78
        val typeBadgeW = 52
        val colCat = colType + typeBadgeW + 6
        val colPow = colCat + 50
        val colAcc = colPow + 30
        val colPP = colAcc + 30

        // Header
        val headerColor = 0xFF777777.toInt()
        if (showLevel) context.drawText(textRenderer, "Lv", colLvl, y, headerColor, true)
        context.drawText(textRenderer, "Move", colName, y, headerColor, true)
        context.drawText(textRenderer, "Type", colType, y, headerColor, true)
        context.drawText(textRenderer, "Cat.", colCat, y, headerColor, true)
        context.drawText(textRenderer, "Pow", colPow, y, headerColor, true)
        context.drawText(textRenderer, "Acc", colAcc, y, headerColor, true)
        context.drawText(textRenderer, "PP", colPP, y, headerColor, true)
        y += 11
        context.fill(tableX, y, panelX + panelWidth - 8, y + 1, 0xFF2A2A2A.toInt())
        y += 4

        for ((index, entry) in moves.withIndex()) {
            val move = entry.move
            val rowTop = y
            try {
                // Row hover highlight
                val rowHovered = mouseX >= tableX && mouseX <= panelX + panelWidth - 8 &&
                                 mouseY >= rowTop && mouseY < rowTop + rowH &&
                                 mouseY >= contentTop && mouseY <= contentBottom
                if (rowHovered) {
                    context.fill(tableX, rowTop, panelX + panelWidth - 8, rowTop + rowH, 0xFF1E1E1E.toInt())
                }

                // Level
                if (showLevel) {
                    context.drawText(textRenderer, "${entry.level}", colLvl, y + 2, 0xFF999999.toInt(), true)
                }

                // Move name
                context.drawText(textRenderer, move.displayName.string, colName, y + 2, 0xFFDDDDDD.toInt(), true)

                // Type badge
                val typeName = move.elementalType.name.replaceFirstChar { it.uppercase() }
                drawMiniTypeBadge(context, colType, y + 1, typeName)

                // Category
                val catName = move.damageCategory.displayName.string
                context.drawText(textRenderer, catName, colCat, y + 2, getCategoryColor(move.damageCategory.name), true)

                // Power
                val power = move.power.toInt()
                context.drawText(textRenderer, if (power > 0) "$power" else "-", colPow, y + 2, 0xFFCCCCCC.toInt(), true)

                // Accuracy
                val acc = move.accuracy
                context.drawText(textRenderer, if (acc > 0) "${acc.toInt()}" else "-", colAcc, y + 2, 0xFFCCCCCC.toInt(), true)

                // PP
                context.drawText(textRenderer, "${move.pp}", colPP, y + 2, 0xFFCCCCCC.toInt(), true)

                // Store bounds for hover detection
                moveRowBounds.add(MoveRowBounds(rowTop, rowH, move))
            } catch (_: Exception) {}
            y += rowH

            // Subtle alternating separator
            if (index % 2 == 1) {
                context.fill(tableX, y - 1, panelX + panelWidth - 8, y, 0xFF151515.toInt())
            }
        }

        y += 8
        return y
    }

    private fun drawMiniTypeBadge(context: DrawContext, x: Int, y: Int, typeName: String) {
        val badgeW = 50
        val badgeH = 11
        val color = getTypeColor(typeName)
        context.fill(x, y, x + badgeW, y + badgeH, color)
        // Center text in badge
        val textW = textRenderer.getWidth(typeName)
        val textX = x + (badgeW - textW) / 2
        context.drawText(textRenderer, typeName, textX, y + 1, 0xFFFFFFFF.toInt(), true)
    }

    private fun updateHoverState(mouseX: Int, mouseY: Int, panelX: Int, panelWidth: Int,
                                  contentTop: Int, contentBottom: Int) {
        // Move hover
        val tableX = panelX + 8
        var foundMove: MoveTemplate? = null
        var foundMoveIndex = -1

        if (mouseX >= tableX && mouseX <= panelX + panelWidth - 8 &&
            mouseY >= contentTop && mouseY <= contentBottom) {
            for ((i, row) in moveRowBounds.withIndex()) {
                if (mouseY >= row.y && mouseY < row.y + row.h) {
                    foundMove = row.move
                    foundMoveIndex = i
                    break
                }
            }
        }

        if (foundMoveIndex != hoveredMoveIndex) {
            hoveredMoveIndex = foundMoveIndex
            hoverStartTime = System.currentTimeMillis()
            tooltipMove = foundMove
        } else if (foundMove == null) {
            tooltipMove = null
            hoveredMoveIndex = -1
        }

        // Ability hover
        var foundAbility: PotentialAbility? = null
        var foundAbilityIndex = -1

        for ((i, row) in abilityRowBounds.withIndex()) {
            if (mouseX >= row.x && mouseX <= row.x + row.w &&
                mouseY >= row.y && mouseY < row.y + row.h &&
                mouseY >= contentTop && mouseY <= contentBottom) {
                foundAbility = row.ability
                foundAbilityIndex = i
                break
            }
        }

        if (foundAbilityIndex != hoveredAbilityIndex) {
            hoveredAbilityIndex = foundAbilityIndex
            abilityHoverStartTime = System.currentTimeMillis()
            tooltipAbility = foundAbility
        } else if (foundAbility == null) {
            tooltipAbility = null
            hoveredAbilityIndex = -1
        }
    }

    // ==================== UI Helpers ====================

    private fun drawTypeBadge(context: DrawContext, x: Int, y: Int, typeName: String) {
        val w = textRenderer.getWidth(typeName) + 8
        val color = getTypeColor(typeName)
        context.fill(x, y, x + w, y + 12, color)
        context.fill(x, y, x + w, y + 1, (color and 0x00FFFFFF) or 0x44000000.toInt())
        context.drawText(textRenderer, typeName, x + 4, y + 2, 0xFFFFFFFF.toInt(), true)
    }

    private fun getTypeColor(type: String): Int {
        return when (type.lowercase()) {
            "normal" -> 0xFFA8A878.toInt()
            "fire" -> 0xFFF08030.toInt()
            "water" -> 0xFF6890F0.toInt()
            "electric" -> 0xFFF8D030.toInt()
            "grass" -> 0xFF78C850.toInt()
            "ice" -> 0xFF98D8D8.toInt()
            "fighting" -> 0xFFC03028.toInt()
            "poison" -> 0xFFA040A0.toInt()
            "ground" -> 0xFFE0C068.toInt()
            "flying" -> 0xFFA890F0.toInt()
            "psychic" -> 0xFFF85888.toInt()
            "bug" -> 0xFFA8B820.toInt()
            "rock" -> 0xFFB8A038.toInt()
            "ghost" -> 0xFF705898.toInt()
            "dragon" -> 0xFF7038F8.toInt()
            "dark" -> 0xFF705848.toInt()
            "steel" -> 0xFFB8B8D0.toInt()
            "fairy" -> 0xFFEE99AC.toInt()
            else -> 0xFF888888.toInt()
        }
    }

    private fun getCategoryColor(name: String): Int {
        return when (name.lowercase()) {
            "physical" -> 0xFFFF6644.toInt()
            "special" -> 0xFF6688FF.toInt()
            "status" -> 0xFFAABBCC.toInt()
            else -> 0xFFCCCCCC.toInt()
        }
    }

    private fun getStatColor(value: Int): Int {
        return when {
            value >= 150 -> 0xFF00C8B0.toInt()
            value >= 120 -> 0xFF55CC55.toInt()
            value >= 90 -> 0xFF88BB33.toInt()
            value >= 60 -> 0xFFFFCC33.toInt()
            value >= 30 -> 0xFFFF8844.toInt()
            else -> 0xFFFF4444.toInt()
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val panelWidth = (width * 0.8).toInt().coerceIn(380, 620)
            val panelX = (width - panelWidth) / 2
            val panelTop = 10
            val backText = "\u2190 Back"
            if (mouseX >= panelX + 6 && mouseX <= panelX + 6 + textRenderer.getWidth(backText) + 4 &&
                mouseY >= panelTop + 5.0 && mouseY <= panelTop + 17.0) {
                client?.setScreen(parent)
                return true
            }

            // Shiny toggle button click
            if (mouseX >= shinyBtnX && mouseX <= shinyBtnX + shinyBtnW &&
                mouseY >= shinyBtnY && mouseY <= shinyBtnY + shinyBtnH) {
                isShiny = !isShiny
                rebuildModel()
                return true
            }

            // Model click -> cry animation + sound
            if (modelWidget != null) {
                val contentTop = panelTop + 22
                val contentBottom = height - 10 - 16
                if (mouseX >= modelRenderX && mouseX <= modelRenderX + MODEL_SIZE &&
                    mouseY >= modelRenderY && mouseY <= modelRenderY + MODEL_SIZE &&
                    mouseY >= contentTop && mouseY <= contentBottom) {
                    modelWidget!!.mouseClicked(mouseX, mouseY, button)
                    playCrySound()
                    return true
                }
            }

            // Tab clicks
            for (i in tabBounds.indices) {
                val (tx, ty, tw, th) = tabBounds[i]
                if (tw > 0 && mouseX >= tx && mouseX <= tx + tw &&
                    mouseY >= ty && mouseY <= ty + th) {
                    if (selectedTab != i) {
                        selectedTab = i
                        // Reset hover state on tab change
                        hoveredMoveIndex = -1
                        tooltipMove = null
                    }
                    return true
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelTop = 10
        val panelBottom = height - 10
        val contentAreaHeight = panelBottom - 16 - (panelTop + 22)
        val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            client?.setScreen(parent)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() {
        client?.setScreen(parent)
    }

    private fun playCrySound() {
        try {
            val namespace = species.resourceIdentifier.namespace
            val showdownId = species.showdownId()
            val cryId = Identifier.of(namespace, "pokemon.$showdownId.cry")
            val mc = MinecraftClient.getInstance()
            if (mc.soundManager.get(cryId) != null) {
                val soundEvent = SoundEvent.of(cryId)
                mc.soundManager.play(PositionedSoundInstance.master(soundEvent, 1.0f))
            }
        } catch (_: Exception) {}
    }

    private fun getRarityColor(bucket: String): Int {
        return when (bucket.lowercase().trim()) {
            "common" -> 0xFF55CC55.toInt()
            "uncommon" -> 0xFFFFDD55.toInt()
            "rare" -> 0xFF55AAFF.toInt()
            "ultra-rare" -> 0xFFBB66FF.toInt()
            else -> 0xFF888888.toInt()
        }
    }

    private fun formatRarity(bucket: String): String {
        if (bucket.isEmpty()) return "Unknown"
        return bucket.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun renderBiomesInline(
        context: DrawContext, biomes: List<BiomeDetail>,
        startX: Int, startY: Int, maxWidth: Int,
        mouseX: Int, mouseY: Int, visibleTop: Int, visibleBottom: Int
    ): Int {
        var x = startX
        var y = startY
        val bulletW = textRenderer.getWidth("\u25CF ")
        context.drawText(textRenderer, "\u25CF ", x, y, 0xFF999999.toInt(), true)
        x += bulletW

        for ((i, biome) in biomes.withIndex()) {
            val nameW = textRenderer.getWidth(biome.displayName)
            val separator = if (i < biomes.lastIndex) ", " else ""
            val sepW = textRenderer.getWidth(separator)

            if (x + nameW > startX + maxWidth && x > startX + bulletW) {
                y += 10
                x = startX + textRenderer.getWidth("    ")
            }

            val isHovered = biome.tagId != null &&
                    mouseX >= x && mouseX <= x + nameW &&
                    mouseY >= y && mouseY <= y + 10 &&
                    mouseY >= visibleTop && mouseY <= visibleBottom
            if (isHovered) hoveredBiomeDetail = biome

            val color = when {
                isHovered -> 0xFFFFAA00.toInt()
                biome.tagId != null -> 0xFFBBBBBB.toInt()
                else -> 0xFF999999.toInt()
            }
            context.drawText(textRenderer, biome.displayName, x, y, color, true)

            if (biome.tagId != null) {
                val underlineColor = if (isHovered) 0xFFFFAA00.toInt() else 0xFF444444.toInt()
                context.fill(x, y + 9, x + nameW, y + 10, underlineColor)
            }

            x += nameW
            if (separator.isNotEmpty()) {
                context.drawText(textRenderer, separator, x, y, 0xFF999999.toInt(), true)
                x += sepW
            }
        }
        return y + 10
    }

    private fun wrapText(text: String, maxWidth: Int): List<String> {
        if (textRenderer.getWidth(text) <= maxWidth) return listOf(text)
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (textRenderer.getWidth(testLine) > maxWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = "    $word"
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)
        return lines
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
