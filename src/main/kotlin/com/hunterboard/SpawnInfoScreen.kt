package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class SpawnInfoScreen : Screen(Text.literal("Spawn Info")) {

    companion object {
        private const val MODEL_SIZE = 36
    }

    private var scrollOffset = 0
    private var contentHeight = 0
    private var modelWidgets: List<ModelWidget?> = emptyList()
    private var hoveredBiomeDetail: BiomeDetail? = null

    // Button bounds
    private var countBtnX = 0
    private var countBtnY = 0
    private var countBtnW = 0
    private var modeBtnX = 0
    private var modeBtnY = 0
    private var modeBtnW = 0
    private val btnH = 14

    // Toggle button bounds per card (index -> bounds)
    private val TOGGLE_SIZE = 20
    private data class ToggleBounds(val x: Int, val y: Int, val index: Int)
    private var toggleButtons: MutableList<ToggleBounds> = mutableListOf()

    override fun init() {
        super.init()
        SpawnData.ensureLoaded()
        buildModelWidgets()
    }

    private fun buildModelWidgets() {
        val widgets = mutableListOf<ModelWidget?>()
        for (target in BoardState.targets) {
            try {
                val species = PokemonSpecies.getByName(target.speciesId)
                if (species != null) {
                    val pokemon = RenderablePokemon(species, target.aspects)
                    widgets.add(ModelWidget(
                        pX = 0, pY = 0,
                        pWidth = MODEL_SIZE, pHeight = MODEL_SIZE,
                        pokemon = pokemon,
                        baseScale = 1.3f,
                        rotationY = 325f,
                        offsetY = -10.0
                    ))
                } else {
                    widgets.add(null)
                }
            } catch (e: Exception) {
                widgets.add(null)
            }
        }
        modelWidgets = widgets
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
        return bucket.split("-").joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        hoveredBiomeDetail = null
        // Dark overlay
        context.fill(0, 0, width, height, 0xAA000000.toInt())

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        // Panel background
        context.fill(panelX, panelTop, panelX + panelWidth, panelBottom, 0xF0101010.toInt())
        drawBorder(context, panelX, panelTop, panelWidth, panelHeight, 0xFFFFAA00.toInt())

        // Title
        val title = "\u2726 Spawn Info \u2726"
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 6, 0xFFFFAA00.toInt(), true)

        // Gold separator + shadow
        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, 0xFFFFAA00.toInt())
        context.fill(panelX + 6, panelTop + 19, panelX + panelWidth - 6, panelTop + 20, 0xFF442200.toInt())

        // --- Buttons ---
        val buttonsY = panelTop + 24

        val countText = "Show: ${BoardState.displayCount}"
        countBtnW = textRenderer.getWidth("Show: 6") + 10
        countBtnX = panelX + 8
        countBtnY = buttonsY
        val countHovered = mouseX >= countBtnX && mouseX <= countBtnX + countBtnW &&
                mouseY >= countBtnY && mouseY <= countBtnY + btnH
        renderButton(context, countBtnX, countBtnY, countBtnW, btnH, countText, countHovered)

        val modeText = "Mode: ${BoardState.MODE_LABELS[BoardState.displayMode]}"
        modeBtnW = textRenderer.getWidth("Mode: Compact") + 10
        modeBtnX = countBtnX + countBtnW + 6
        modeBtnY = buttonsY
        val modeHovered = mouseX >= modeBtnX && mouseX <= modeBtnX + modeBtnW &&
                mouseY >= modeBtnY && mouseY <= modeBtnY + btnH
        renderButton(context, modeBtnX, modeBtnY, modeBtnW, btnH, modeText, modeHovered)

        // Separator after buttons
        val sep2Y = buttonsY + btnH + 4
        context.fill(panelX + 6, sep2Y, panelX + panelWidth - 6, sep2Y + 1, 0xFF333333.toInt())

        // --- Scrollable content ---
        val contentTop = sep2Y + 4
        val contentBottom = panelBottom - 16
        val contentAreaHeight = contentBottom - contentTop

        context.enableScissor(panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)

        val cardX = panelX + 6
        val cardWidth = panelWidth - 12
        val textContentX = cardX + 6 + MODEL_SIZE + 6
        val maxTextW = cardWidth - 6 - MODEL_SIZE - 6 - 8

        var y = contentTop + 2 - scrollOffset

        var hoveredBallName: String? = null
        toggleButtons.clear()

        if (SpawnData.isLoading) {
            context.drawText(textRenderer, "Loading spawn data...", cardX + 10, y + 10, 0xFFAAAAAA.toInt(), true)
            contentHeight = 30
        } else if (!BoardState.hasTargets()) {
            context.drawText(textRenderer, "No targets on board", cardX + 10, y + 10, 0xFFAAAAAA.toInt(), true)
            contentHeight = 30
        } else {
            var totalHeight = 2

            for ((index, target) in BoardState.targets.withIndex()) {
                val spawns = SpawnData.getSpawns(target.speciesId)
                val rarity = spawns.firstOrNull()?.bucket ?: ""
                val rarityColor = getRarityColor(rarity)

                // Calculate card height
                val cardContentH = calculateCardContentHeight(spawns, maxTextW)
                val cardHeight = maxOf(cardContentH, MODEL_SIZE + 12)

                // Card background
                val cardBg = if (target.isCaught) 0xFF142014.toInt() else 0xFF1A1A1A.toInt()
                context.fill(cardX, y, cardX + cardWidth, y + cardHeight, cardBg)

                // Rarity left border (3px)
                context.fill(cardX, y, cardX + 3, y + cardHeight, rarityColor)

                // Subtle top edge highlight
                context.fill(cardX + 3, y, cardX + cardWidth, y + 1, 0xFF282828.toInt())

                // Bottom edge shadow
                context.fill(cardX, y + cardHeight - 1, cardX + cardWidth, y + cardHeight, 0xFF0A0A0A.toInt())

                // 3D Model
                if (index < modelWidgets.size) {
                    val widget = modelWidgets[index]
                    if (widget != null && y + cardHeight > contentTop && y < contentBottom) {
                        try {
                            widget.x = cardX + 4
                            widget.y = y - 2
                            widget.render(context, mouseX, mouseY, delta)
                        } catch (_: Exception) {}
                    }
                }

                var textY = y + 6

                // Pokemon name
                val nameColor = if (target.isCaught) 0xFF55FF55.toInt() else 0xFFFFFFFF.toInt()
                val statusIcon = if (target.isCaught) "\u2713 " else ""
                val nameText = "$statusIcon${target.pokemonName}"
                context.drawText(textRenderer, nameText, textContentX, textY, nameColor, true)

                // Ball icon next to name
                if (target.ballId.isNotEmpty()) {
                    try {
                        val ballStack = ItemStack(Registries.ITEM.get(Identifier.of(target.ballId)))
                        if (!ballStack.isEmpty) {
                            val nameWidth = textRenderer.getWidth(nameText)
                            val ballIconX = textContentX + nameWidth + 4
                            val ballIconY = textY - 4
                            context.drawItem(ballStack, ballIconX, ballIconY)

                            // Check hover for tooltip
                            if (mouseX >= ballIconX && mouseX <= ballIconX + 16 &&
                                mouseY >= ballIconY && mouseY <= ballIconY + 16 &&
                                mouseY >= contentTop && mouseY <= contentBottom) {
                                hoveredBallName = target.requiredBall
                            }
                        }
                    } catch (_: Exception) {}
                }

                textY += 13

                if (spawns.isNotEmpty()) {
                    // Level range + rarity (colored)
                    val lvMin = spawns.minOf { it.lvMin }
                    val lvMax = spawns.maxOf { it.lvMax }
                    val lvText = "Lv. $lvMin-$lvMax"
                    context.drawText(textRenderer, lvText, textContentX, textY, 0xFFBBBBBB.toInt(), true)

                    val divider = " \u2022 "
                    val lvWidth = textRenderer.getWidth(lvText)
                    context.drawText(textRenderer, divider, textContentX + lvWidth, textY, 0xFF555555.toInt(), true)

                    val divWidth = textRenderer.getWidth(divider)
                    val rarityText = formatRarity(rarity)
                    context.drawText(textRenderer, rarityText, textContentX + lvWidth + divWidth, textY, rarityColor, true)

                    textY += 13

                    // Spawn conditions
                    val seen = mutableSetOf<String>()
                    for (spawn in spawns) {
                        val key = "${spawn.biomes}|${spawn.time}|${spawn.weather}"
                        if (key in seen) continue
                        seen.add(key)

                        // Biomes
                        if (spawn.biomeDetails.isEmpty()) {
                            context.drawText(textRenderer, "\u25CF Any", textContentX + 4, textY, 0xFF999999.toInt(), true)
                            textY += 10
                        } else {
                            textY = renderBiomesInline(context, spawn.biomeDetails, textContentX + 4, textY,
                                maxTextW - 8, mouseX, mouseY, contentTop, contentBottom)
                        }

                        // Time / Weather
                        val timeStr = if (spawn.time == "any") "Any time" else spawn.time.replaceFirstChar { it.uppercase() }
                        val weatherStr = if (spawn.weather == "any") "Any weather" else spawn.weather.replaceFirstChar { it.uppercase() }
                        context.drawText(textRenderer, "  $timeStr | $weatherStr", textContentX + 4, textY, 0xFF707070.toInt(), true)
                        textY += 11
                    }
                } else {
                    val noDataText = if (SpawnData.loadError != null) "Error loading data" else "No spawn data"
                    context.drawText(textRenderer, noDataText, textContentX, textY, 0xFF666666.toInt(), true)
                }

                // Toggle button (right side of card)
                val toggleX = cardX + cardWidth - TOGGLE_SIZE - 6
                val toggleY = y + (cardHeight - TOGGLE_SIZE) / 2
                toggleButtons.add(ToggleBounds(toggleX, toggleY, index))

                val toggleHovered = mouseX >= toggleX && mouseX <= toggleX + TOGGLE_SIZE &&
                        mouseY >= toggleY && mouseY <= toggleY + TOGGLE_SIZE &&
                        mouseY >= contentTop && mouseY <= contentBottom
                val toggleBg = when {
                    target.isCaught && toggleHovered -> 0xFF1A3A1A.toInt()
                    target.isCaught -> 0xFF143014.toInt()
                    toggleHovered -> 0xFF3A1A1A.toInt()
                    else -> 0xFF2A1515.toInt()
                }
                context.fill(toggleX, toggleY, toggleX + TOGGLE_SIZE, toggleY + TOGGLE_SIZE, toggleBg)
                val toggleBorder = when {
                    target.isCaught && toggleHovered -> 0xFF77FF77.toInt()
                    target.isCaught -> 0xFF55CC55.toInt()
                    toggleHovered -> 0xFFFF7777.toInt()
                    else -> 0xFFCC5555.toInt()
                }
                drawBorder(context, toggleX, toggleY, TOGGLE_SIZE, TOGGLE_SIZE, toggleBorder)

                if (target.isCaught) {
                    val check = "\u2714"
                    val checkW = textRenderer.getWidth(check)
                    context.drawText(textRenderer, check,
                        toggleX + (TOGGLE_SIZE - checkW) / 2, toggleY + (TOGGLE_SIZE - 9) / 2,
                        0xFF55FF55.toInt(), true)
                } else {
                    val cross = "\u2716"
                    val crossW = textRenderer.getWidth(cross)
                    context.drawText(textRenderer, cross,
                        toggleX + (TOGGLE_SIZE - crossW) / 2, toggleY + (TOGGLE_SIZE - 9) / 2,
                        0xFFFF5555.toInt(), true)
                }

                y += cardHeight + 5
                totalHeight += cardHeight + 5
            }

            contentHeight = totalHeight
        }

        context.disableScissor()

        // Scrollbar
        if (contentHeight > contentAreaHeight && contentAreaHeight > 0) {
            val trackX = panelX + panelWidth - 5
            context.fill(trackX, contentTop, trackX + 3, contentBottom, 0xFF1A1A1A.toInt())

            val thumbHeight = maxOf(15, contentAreaHeight * contentAreaHeight / contentHeight)
            val maxScroll = contentHeight - contentAreaHeight
            val thumbY = contentTop + (scrollOffset * (contentAreaHeight - thumbHeight) / maxOf(1, maxScroll))
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFFFAA00.toInt())
        }

        // Ball tooltip
        if (hoveredBallName != null) {
            context.drawTooltip(textRenderer, listOf(Text.literal(hoveredBallName)), mouseX, mouseY)
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

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint = "I / ESC to close  \u2022  Scroll to navigate"
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)
    }

    private fun renderButton(context: DrawContext, x: Int, y: Int, w: Int, h: Int, text: String, hovered: Boolean) {
        val bg = if (hovered) 0xFF353535.toInt() else 0xFF222222.toInt()
        context.fill(x, y, x + w, y + h, bg)
        val borderColor = if (hovered) 0xFFFFAA00.toInt() else 0xFF444444.toInt()
        drawBorder(context, x, y, w, h, borderColor)
        val textColor = if (hovered) 0xFFFFAA00.toInt() else 0xFFBBBBBB.toInt()
        context.drawText(textRenderer, text, x + 5, y + 3, textColor, true)
    }

    private fun calculateCardContentHeight(spawns: List<SpawnEntry>, maxTextW: Int): Int {
        var h = 6   // top padding
        h += 13     // name line

        if (spawns.isEmpty()) {
            h += 12
        } else {
            h += 13 // level + rarity
            val seen = mutableSetOf<String>()
            for (spawn in spawns) {
                val key = "${spawn.biomes}|${spawn.time}|${spawn.weather}"
                if (key in seen) continue
                seen.add(key)
                h += calculateBiomeLinesHeight(spawn.biomeDetails, maxTextW - 8)
                h += 11 // time/weather
            }
        }

        h += 4 // bottom padding
        return h
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            if (mouseX >= countBtnX && mouseX <= countBtnX + countBtnW &&
                mouseY >= countBtnY && mouseY <= countBtnY + btnH) {
                val next = if (BoardState.displayCount >= 6) 3 else BoardState.displayCount + 1
                BoardState.setDisplayCount(next)
                return true
            }

            if (mouseX >= modeBtnX && mouseX <= modeBtnX + modeBtnW &&
                mouseY >= modeBtnY && mouseY <= modeBtnY + btnH) {
                val next = (BoardState.displayMode + 1) % 4
                BoardState.setDisplayMode(next)
                return true
            }

            // Toggle buttons
            for (toggle in toggleButtons) {
                if (mouseX >= toggle.x && mouseX <= toggle.x + TOGGLE_SIZE &&
                    mouseY >= toggle.y && mouseY <= toggle.y + TOGGLE_SIZE) {
                    BoardState.toggleTargetCaught(toggle.index)
                    return true
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
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

    private fun calculateBiomeLinesHeight(biomes: List<BiomeDetail>, maxWidth: Int): Int {
        if (biomes.isEmpty()) return 10
        var x = textRenderer.getWidth("\u25CF ")
        var lineCount = 1
        for ((i, biome) in biomes.withIndex()) {
            val nameW = textRenderer.getWidth(biome.displayName)
            val sepW = if (i < biomes.lastIndex) textRenderer.getWidth(", ") else 0
            if (x + nameW > maxWidth && x > textRenderer.getWidth("\u25CF ")) {
                lineCount++
                x = textRenderer.getWidth("    ")
            }
            x += nameW + sepW
        }
        return lineCount * 10
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

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelTop = 25
        val panelBottom = height - 25
        val contentTop = panelTop + 24 + btnH + 8
        val contentAreaHeight = panelBottom - 16 - contentTop
        val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_I) {
            close()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
