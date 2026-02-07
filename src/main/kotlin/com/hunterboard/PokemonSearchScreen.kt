package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

class PokemonSearchScreen : Screen(Text.literal("Pokemon Search")) {

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Skip default blur/background - we draw our own
    }

    private lateinit var searchField: TextFieldWidget
    private var filteredSpecies: List<Species> = emptyList()
    private var allSpeciesSorted: List<Species>? = null
    private var scrollOffset = 0
    private val rowHeight = 16

    override fun init() {
        super.init()
        SpawnData.ensureLoaded()

        if (allSpeciesSorted == null) {
            allSpeciesSorted = PokemonSpecies.species
                .sortedBy { it.nationalPokedexNumber }
        }
        filteredSpecies = allSpeciesSorted!!

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 24, panelWidth - 20, 16, Text.literal("Search"))
        searchField.setMaxLength(50)
        searchField.setChangedListener { updateSearch() }
        addDrawableChild(searchField)
        setInitialFocus(searchField)
    }

    private fun updateSearch() {
        val query = searchField.text.lowercase().trim()
        scrollOffset = 0

        if (query.isEmpty()) {
            filteredSpecies = allSpeciesSorted ?: emptyList()
            return
        }

        filteredSpecies = (allSpeciesSorted ?: emptyList())
            .filter { sp ->
                sp.name.contains(query) ||
                sp.translatedName.string.lowercase().contains(query)
            }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
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
        val title = "\u2726 Pok\u00e9mon Search \u2726"
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 6, 0xFFFFAA00.toInt(), true)

        // Gold separator
        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, 0xFFFFAA00.toInt())
        context.fill(panelX + 6, panelTop + 19, panelX + panelWidth - 6, panelTop + 20, 0xFF442200.toInt())

        // Results area
        val resultsTop = panelTop + 46
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        context.fill(panelX + 6, resultsTop - 2, panelX + panelWidth - 6, resultsTop - 1, 0xFF333333.toInt())

        // Scissor for scrollable results
        context.enableScissor(panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        if (filteredSpecies.isEmpty()) {
            context.drawText(textRenderer, "No Pok\u00e9mon found", panelX + 15, resultsTop + 10, 0xFF666666.toInt(), true)
        } else {
            var y = resultsTop + 2 - scrollOffset
            for (species in filteredSpecies) {
                if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                    // Hover highlight
                    val hovered = mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                                  mouseY >= y && mouseY <= y + rowHeight &&
                                  mouseY >= resultsTop && mouseY <= resultsBottom

                    if (hovered) {
                        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + rowHeight, 0xFF252525.toInt())
                    }

                    // Rarity dot
                    val spawns = SpawnData.getSpawns(species.name)
                    val rarity = spawns.firstOrNull()?.bucket ?: ""
                    val rarityColor = getRarityColor(rarity)
                    context.fill(panelX + 10, y + 4, panelX + 14, y + 8, rarityColor)

                    // Pokemon name
                    val displayName = species.translatedName.string
                    val nameColor = if (hovered) 0xFFFFAA00.toInt() else 0xFFFFFFFF.toInt()
                    context.drawText(textRenderer, displayName, panelX + 18, y + 3, nameColor, true)

                    // Dex number right-aligned
                    val dexText = "#${species.nationalPokedexNumber}"
                    val dexWidth = textRenderer.getWidth(dexText)
                    context.drawText(textRenderer, dexText, panelX + panelWidth - 12 - dexWidth, y + 3, 0xFF777777.toInt(), true)
                }
                y += rowHeight
            }
        }

        context.disableScissor()

        // Scrollbar
        val contentHeight = filteredSpecies.size * rowHeight + 4
        if (contentHeight > resultsAreaHeight && resultsAreaHeight > 0) {
            val trackX = panelX + panelWidth - 5
            context.fill(trackX, resultsTop, trackX + 3, resultsBottom, 0xFF1A1A1A.toInt())
            val thumbHeight = maxOf(15, resultsAreaHeight * resultsAreaHeight / contentHeight)
            val maxScroll = contentHeight - resultsAreaHeight
            val thumbY = resultsTop + (scrollOffset * (resultsAreaHeight - thumbHeight) / maxOf(1, maxScroll))
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFFFAA00.toInt())
        }

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint = "ESC to close  \u2022  Click for details"
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)

        // Render search field and other widgets
        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && filteredSpecies.isNotEmpty()) {
            val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
            val panelX = (width - panelWidth) / 2
            val panelTop = 25
            val resultsTop = panelTop + 46
            val resultsBottom = height - 25 - 16

            if (mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                mouseY >= resultsTop && mouseY <= resultsBottom) {
                val relativeY = mouseY.toInt() - resultsTop + scrollOffset - 2
                val index = relativeY / rowHeight
                if (index >= 0 && index < filteredSpecies.size) {
                    val species = filteredSpecies[index]
                    client?.setScreen(PokemonDetailScreen(species, this))
                    return true
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelTop = 25
        val panelBottom = height - 25
        val resultsTop = panelTop + 46
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop
        val contentHeight = filteredSpecies.size * rowHeight + 4
        val maxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
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

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
