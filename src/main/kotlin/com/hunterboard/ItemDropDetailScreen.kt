package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class ItemDropDetailScreen(
    private val itemId: Identifier,
    private val parent: Screen
) : Screen(Text.literal("Item Drop Detail")) {

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        if (!DropData.reverseIndexReady) {
            Thread { DropData.buildReverseIndex() }.also { it.isDaemon = true }.start()
        }
    }

    private var scrollOffset = 0
    private var contentHeight = 0

    // 0 = icon mode (default), 1 = list mode
    private var learnerViewMode = 0

    // ModelWidget cache
    private val modelWidgetCache = mutableMapOf<String, ModelWidget?>()

    // Pokemon click bounds
    private data class PokemonBound(val x: Int, val y: Int, val w: Int, val h: Int, val speciesName: String)
    private var pokemonBounds = mutableListOf<PokemonBound>()

    // Toggle button bounds
    private var toggleIconBounds = intArrayOf(0, 0, 0, 0)
    private var toggleListBounds = intArrayOf(0, 0, 0, 0)

    // Scrollbar
    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0
    private var scrollbarDragStartOffset = 0
    private var sbTrackX = 0
    private var sbContentTop = 0
    private var sbContentBottom = 0
    private var sbThumbY = 0
    private var sbThumbHeight = 0

    // Icon-mode layout constants
    private val CARD_H = 28
    private val MODEL_SIZE = 22
    private val MODEL_OFFSET_Y = -3

    private fun getItemDisplayName(): String {
        return try {
            val item = Registries.ITEM.get(itemId)
            val stack = ItemStack(item)
            stack.name.string
        } catch (_: Exception) { itemId.path }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        pokemonBounds.clear()
        context.fill(0, 0, width, height, 0xAA000000.toInt())

        val panelWidth = (width * 0.8).toInt().coerceIn(380, 620)
        val panelX = (width - panelWidth) / 2
        val panelTop = 10
        val panelBottom = height - 10
        val panelHeight = panelBottom - panelTop

        context.fill(panelX, panelTop, panelX + panelWidth, panelBottom, 0xF0101010.toInt())
        drawBorder(context, panelX, panelTop, panelWidth, panelHeight, ModConfig.accentColor())

        // Close button
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        val closeHovered = mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2 && mouseY <= closeY + 11
        context.drawText(textRenderer, "\u2715", closeX, closeY, if (closeHovered) 0xFFFF5555.toInt() else 0xFF888888.toInt(), true)

        // Back button
        val backText: String = Translations.tr("\u2190 Back")
        val backHovered = mouseX >= panelX + 6 && mouseX <= panelX + 6 + textRenderer.getWidth(backText) + 4 &&
                          mouseY in (panelTop + 5)..(panelTop + 17)
        context.drawText(textRenderer, backText, panelX + 8, panelTop + 6,
            if (backHovered) ModConfig.accentColor() else 0xFFAAAAAA.toInt(), true)

        // Item name (centered) with icon
        val itemName = getItemDisplayName()
        val nameW = textRenderer.getWidth(itemName)
        val totalW = 18 + nameW // icon (16) + 2px gap + name
        val startX = panelX + (panelWidth - totalW) / 2
        try {
            val item = Registries.ITEM.get(itemId)
            val stack = ItemStack(item)
            if (!stack.isEmpty) {
                context.drawItem(stack, startX, panelTop + 1)
            }
        } catch (_: Exception) {}
        context.drawText(textRenderer, itemName, startX + 18, panelTop + 6, 0xFFFFFFFF.toInt(), true)

        // Item description (tooltip lines from Minecraft, below item name)
        val descLines = getItemDescriptionLines()
        var descY = panelTop + 18
        for (line in descLines) {
            val wrappedLines = wrapText(line, panelWidth - 24)
            for (wl in wrappedLines) {
                val text: String = wl
                context.drawText(textRenderer, text, panelX + 12, descY, 0xFF999999.toInt(), true)
                descY += 10
            }
        }

        // Gold separator
        context.fill(panelX + 6, descY + 2, panelX + panelWidth - 6, descY + 3, ModConfig.accentColor())
        context.fill(panelX + 6, descY + 3, panelX + panelWidth - 6, descY + 4, 0xFF442200.toInt())

        // Content area
        val contentTop = descY + 6
        val contentBottom = panelBottom - 16
        val contentAreaHeight = contentBottom - contentTop

        sbContentTop = contentTop
        sbContentBottom = contentBottom

        context.enableScissor(panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)

        val leftX = panelX + 10
        val contentW = panelWidth - 20
        var y = contentTop + 4 - scrollOffset

        // === Pokémon dropping this item ===
        val sectionLabel: String = Translations.tr("Pokémon dropping this item")
        context.drawText(textRenderer, sectionLabel, leftX, y, ModConfig.accentColor(), true)

        // Toggle buttons
        val iconToggleText = "\u229E"
        val listToggleText = "\u2261"
        val tBtnH = 12
        val tBtnPad = 6
        val listBtnW = textRenderer.getWidth(listToggleText) + tBtnPad
        val iconBtnW = textRenderer.getWidth(iconToggleText) + tBtnPad
        val listBtnX = panelX + panelWidth - 10 - listBtnW
        val iconBtnX = listBtnX - iconBtnW - 3
        val tBtnY = y - 1

        toggleIconBounds = intArrayOf(iconBtnX, tBtnY, iconBtnW, tBtnH)
        toggleListBounds = intArrayOf(listBtnX, tBtnY, listBtnW, tBtnH)

        val iconSelected = learnerViewMode == 0
        val iconHov = mouseX >= iconBtnX && mouseX <= iconBtnX + iconBtnW && mouseY >= tBtnY && mouseY <= tBtnY + tBtnH
        context.fill(iconBtnX, tBtnY, iconBtnX + iconBtnW, tBtnY + tBtnH,
            if (iconSelected) (ModConfig.accentColor() and 0x00FFFFFF) or 0x44000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, iconBtnX, tBtnY, iconBtnW, tBtnH,
            if (iconSelected) ModConfig.accentColor() else if (iconHov) 0xFF666666.toInt() else 0xFF333333.toInt())
        context.drawText(textRenderer, iconToggleText, iconBtnX + tBtnPad / 2, tBtnY + 2,
            if (iconSelected) ModConfig.accentColor() else if (iconHov) 0xFFDDDDDD.toInt() else 0xFF777777.toInt(), true)

        val listSelected = learnerViewMode == 1
        val listHov = mouseX >= listBtnX && mouseX <= listBtnX + listBtnW && mouseY >= tBtnY && mouseY <= tBtnY + tBtnH
        context.fill(listBtnX, tBtnY, listBtnX + listBtnW, tBtnY + tBtnH,
            if (listSelected) (ModConfig.accentColor() and 0x00FFFFFF) or 0x44000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, listBtnX, tBtnY, listBtnW, tBtnH,
            if (listSelected) ModConfig.accentColor() else if (listHov) 0xFF666666.toInt() else 0xFF333333.toInt())
        context.drawText(textRenderer, listToggleText, listBtnX + tBtnPad / 2, tBtnY + 2,
            if (listSelected) ModConfig.accentColor() else if (listHov) 0xFFDDDDDD.toInt() else 0xFF777777.toInt(), true)

        y += 14

        // === Droppers ===
        if (!DropData.reverseIndexReady) {
            val loadingText: String = Translations.tr("Loading...")
            context.drawText(textRenderer, loadingText, leftX + 4, y, 0xFF888888.toInt(), true)
            y += 12
        } else {
            val droppers = DropData.getSpeciesWithDrop(itemId.toString())
            if (droppers.isEmpty()) {
                val noText: String = Translations.tr("No Pokémon drops this item")
                context.drawText(textRenderer, noText, leftX + 4, y, 0xFF666666.toInt(), true)
                y += 12
            } else {
                if (learnerViewMode == 0) {
                    y = renderIconGrid(context, mouseX, mouseY, droppers, leftX, y, contentW, contentTop, contentBottom)
                } else {
                    y = renderList(context, mouseX, mouseY, droppers, leftX, y, contentW, contentTop, contentBottom)
                }
            }
        }

        contentHeight = y - (contentTop + 4 - scrollOffset) + 8
        context.disableScissor()

        // Scrollbar
        if (contentHeight > contentAreaHeight && contentAreaHeight > 0) {
            sbTrackX = panelX + panelWidth - 5
            context.fill(sbTrackX, contentTop, sbTrackX + 3, contentBottom, 0xFF1A1A1A.toInt())
            sbThumbHeight = maxOf(15, contentAreaHeight * contentAreaHeight / contentHeight)
            val maxScroll = contentHeight - contentAreaHeight
            sbThumbY = contentTop + (scrollOffset * (contentAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, ModConfig.accentColor())
        } else {
            sbThumbHeight = 0
        }

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint: String = Translations.tr("ESC / Click Back to return")
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)
    }

    // ---- Icon grid (2 columns, model + name + drop info) ----
    private fun renderIconGrid(
        context: DrawContext, mouseX: Int, mouseY: Int,
        droppers: List<ItemDropper>,
        leftX: Int, startY: Int, contentW: Int,
        contentTop: Int, contentBottom: Int
    ): Int {
        val cardW = (contentW - 8) / 2
        val cardX0 = leftX + 4
        var col = 0
        var y = startY

        for (dropper in droppers) {
            val species = safeGetByName(dropper.speciesName)
            val displayName = species?.translatedName?.string
                ?: dropper.speciesName.replaceFirstChar { it.uppercase() }

            val cardX = cardX0 + col * cardW

            if (y + CARD_H >= contentTop && y <= contentBottom) {
                val hovered = mouseX >= cardX && mouseX <= cardX + cardW - 4 &&
                              mouseY >= y && mouseY <= y + CARD_H &&
                              mouseY >= contentTop && mouseY <= contentBottom

                if (hovered) {
                    context.fill(cardX, y, cardX + cardW - 4, y + CARD_H, 0x22FFFFFF.toInt())
                    pokemonBounds.add(PokemonBound(cardX, y, cardW - 4, CARD_H, dropper.speciesName))
                }

                val widget = getOrCreateWidget(dropper.speciesName)
                if (widget != null) {
                    widget.x = cardX
                    widget.y = y + MODEL_OFFSET_Y
                    try { widget.render(context, 0, 0, 0f) } catch (_: Exception) {}
                }

                val nameX = cardX + MODEL_SIZE + 4
                val nameMaxW = cardW - MODEL_SIZE - 10
                val dispText = truncateText(displayName, nameMaxW)
                val textY = y + 4
                context.drawText(textRenderer, dispText, nameX, textY,
                    if (hovered) ModConfig.accentColor() else 0xFFCCCCCC.toInt(), true)

                // Drop info (percentage + quantity)
                val qtyText = if (dropper.quantityMin != dropper.quantityMax)
                    "${dropper.quantityMin}-${dropper.quantityMax}" else "${dropper.quantityMin}"
                val dropInfo = "${dropper.percentage.toInt()}% x$qtyText"
                context.drawText(textRenderer, dropInfo, nameX, textY + 10, 0xFF888888.toInt(), true)

                if (hovered && textRenderer.getWidth(displayName) > nameMaxW) {
                    context.drawTooltip(textRenderer, listOf(Text.literal(displayName)), mouseX, mouseY)
                }
            }

            col++
            if (col >= 2) { col = 0; y += CARD_H }
        }
        if (col > 0) y += CARD_H
        return y
    }

    // ---- List mode (bullet list, 2 per row) ----
    private fun renderList(
        context: DrawContext, mouseX: Int, mouseY: Int,
        droppers: List<ItemDropper>,
        leftX: Int, startY: Int, contentW: Int,
        contentTop: Int, contentBottom: Int
    ): Int {
        val itemH = 13
        var y = startY
        val maxLineW = contentW - 16
        val halfW = maxLineW / 2
        var col = 0

        for (dropper in droppers) {
            val species = safeGetByName(dropper.speciesName)
            val displayName = species?.translatedName?.string
                ?: dropper.speciesName.replaceFirstChar { it.uppercase() }

            val qtyText = if (dropper.quantityMin != dropper.quantityMax)
                "${dropper.quantityMin}-${dropper.quantityMax}" else "${dropper.quantityMin}"
            val fullText = "$displayName ${dropper.percentage.toInt()}% x$qtyText"

            val itemX = leftX + 8 + col * halfW

            if (y + itemH >= contentTop && y <= contentBottom) {
                val textW = textRenderer.getWidth(displayName)
                val hovered = mouseX >= itemX && mouseX <= itemX + halfW - 4 &&
                              mouseY >= y && mouseY <= y + itemH &&
                              mouseY >= contentTop && mouseY <= contentBottom

                if (hovered) pokemonBounds.add(PokemonBound(itemX, y, textW, itemH, dropper.speciesName))

                context.drawText(textRenderer, "\u2022", itemX, y + 2, 0xFF555555.toInt(), true)
                val textX = itemX + 8
                val nameColor = if (hovered) ModConfig.accentColor() else 0xFFCCCCCC.toInt()
                context.drawText(textRenderer, displayName, textX, y + 2, nameColor, true)

                // Drop info after name
                val infoX = textX + textRenderer.getWidth(displayName) + 4
                context.drawText(textRenderer, "${dropper.percentage.toInt()}% x$qtyText", infoX, y + 2, 0xFF888888.toInt(), true)

                if (hovered) {
                    context.fill(textX, y + itemH - 1, textX + textW, y + itemH, ModConfig.accentColor())
                }
            }

            col++
            if (col >= 2) { col = 0; y += itemH }
        }
        if (col > 0) y += itemH
        return y
    }

    private fun getOrCreateWidget(speciesName: String): ModelWidget? {
        if (speciesName in modelWidgetCache) return modelWidgetCache[speciesName]
        return try {
            val species = safeGetByName(speciesName)
                ?: run { modelWidgetCache[speciesName] = null; return null }
            val widget = ModelWidget(
                pX = 0, pY = 0,
                pWidth = MODEL_SIZE, pHeight = MODEL_SIZE,
                pokemon = RenderablePokemon(species, emptySet()),
                baseScale = 0.85f,
                rotationY = 325f,
                offsetY = -10.0
            )
            modelWidgetCache[speciesName] = widget
            widget
        } catch (_: Exception) { modelWidgetCache[speciesName] = null; null }
    }

    private fun truncateText(text: String, maxWidth: Int): String {
        if (textRenderer.getWidth(text) <= maxWidth) return text
        val ellipsis = "\u2026"
        val ellipsisW = textRenderer.getWidth(ellipsis)
        var result = ""
        for (ch in text) {
            val test = result + ch
            if (textRenderer.getWidth(test) + ellipsisW > maxWidth) break
            result = test
        }
        return result + ellipsis
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val panelWidth = (width * 0.8).toInt().coerceIn(380, 620)
            val panelX = (width - panelWidth) / 2
            val panelTop = 10

            // Close button
            val closeX = panelX + panelWidth - 12
            val closeY = panelTop + 4
            if (mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2.0 && mouseY <= closeY + 11.0) {
                client?.setScreen(parent); return true
            }

            // Back button
            val backText: String = Translations.tr("\u2190 Back")
            if (mouseX >= panelX + 6 && mouseX <= panelX + 6 + textRenderer.getWidth(backText) + 4 &&
                mouseY >= panelTop + 5.0 && mouseY <= panelTop + 17.0) {
                client?.setScreen(parent); return true
            }

            // Toggle icon mode
            val ix = toggleIconBounds[0]; val iy = toggleIconBounds[1]; val iw = toggleIconBounds[2]; val ih = toggleIconBounds[3]
            if (mouseX >= ix && mouseX <= ix + iw && mouseY >= iy && mouseY <= iy + ih) {
                learnerViewMode = 0; scrollOffset = 0; return true
            }

            // Toggle list mode
            val lx = toggleListBounds[0]; val ly = toggleListBounds[1]; val lw = toggleListBounds[2]; val lh = toggleListBounds[3]
            if (mouseX >= lx && mouseX <= lx + lw && mouseY >= ly && mouseY <= ly + lh) {
                learnerViewMode = 1; scrollOffset = 0; return true
            }

            // Scrollbar
            if (sbThumbHeight > 0 && mouseX >= sbTrackX && mouseX <= sbTrackX + 6 &&
                mouseY >= sbContentTop && mouseY <= sbContentBottom) {
                val contentAreaHeight = sbContentBottom - sbContentTop
                val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
                if (mouseY >= sbThumbY && mouseY <= sbThumbY + sbThumbHeight) {
                    isScrollbarDragging = true
                    scrollbarDragStartY = mouseY.toInt()
                    scrollbarDragStartOffset = scrollOffset
                } else {
                    val ratio = (mouseY - sbContentTop).toFloat() / contentAreaHeight
                    scrollOffset = (ratio * maxScroll).toInt().coerceIn(0, maxScroll)
                }
                return true
            }

            // Pokemon name clicks
            for (bound in pokemonBounds) {
                if (mouseX >= bound.x && mouseX <= bound.x + bound.w &&
                    mouseY >= bound.y.toDouble() && mouseY <= (bound.y + bound.h).toDouble()) {
                    val species = safeGetByName(bound.speciesName)
                    if (species != null) {
                        client?.setScreen(PokemonDetailScreen(species, this))
                        return true
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (isScrollbarDragging && button == 0) {
            val contentAreaHeight = sbContentBottom - sbContentTop
            val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
            val trackRange = contentAreaHeight - sbThumbHeight
            if (trackRange > 0) {
                val scrollDelta = ((mouseY.toInt() - scrollbarDragStartY).toFloat() / trackRange * maxScroll).toInt()
                scrollOffset = (scrollbarDragStartOffset + scrollDelta).coerceIn(0, maxScroll)
            }
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && isScrollbarDragging) { isScrollbarDragging = false; return true }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val contentAreaHeight = sbContentBottom - sbContentTop
        if (contentAreaHeight <= 0) return true
        val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { client?.setScreen(parent); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun safeGetByName(name: String): com.cobblemon.mod.common.pokemon.Species? {
        return try {
            PokemonSpecies.species.find { it.resourceIdentifier.path == name }
                ?: PokemonSpecies.getByName(name)
        } catch (_: Exception) { null }
    }

    private fun getItemDescriptionLines(): List<String> {
        return try {
            val item = Registries.ITEM.get(itemId)
            val stack = ItemStack(item)
            val tooltipLines = stack.getTooltip(Item.TooltipContext.DEFAULT, client?.player, TooltipType.Default.BASIC)
            if (tooltipLines.size > 1) {
                tooltipLines.subList(1, tooltipLines.size).map { it.string }.filter { it.isNotBlank() }
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun wrapText(text: String, maxWidth: Int): List<String> {
        if (textRenderer.getWidth(text) <= maxWidth) return listOf(text)
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val test = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (textRenderer.getWidth(test) > maxWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = word
            } else {
                currentLine = test
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
