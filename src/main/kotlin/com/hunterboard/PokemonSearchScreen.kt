package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget

import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class PokemonSearchScreen : Screen(Text.literal("Pokemon Search")) {

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Skip default blur/background - we draw our own
    }

    private lateinit var searchField: TextFieldWidget
    private var filteredList: List<PokemonEntry> = emptyList()
    var allSpeciesSorted: List<Species>? = null
        private set
    private var allEntriesSorted: List<PokemonEntry>? = null
    private var scrollOffset = 0
    private val rowHeight = 16

    // History toggle
    private var showingHistory = false

    // Options button
    private val OPTIONS_ICON = Identifier.of("hunterboard", "img/option.png")
    private val optBtnSize = 40
    private var optBtnX = 0
    private var optBtnY = 0

    // Scrollbar drag state
    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0.0
    private var scrollbarDragStartOffset = 0

    // Scrollbar geometry (updated each frame)
    private var sbTrackX = 0
    private var sbContentTop = 0
    private var sbContentBottom = 0
    private var sbThumbY = 0
    private var sbThumbHeight = 0
    private var sbMaxScroll = 0

    // Tab bounds for Pokemon/Moves toggle
    private var movesTabX = 0
    private var movesTabW = 0
    private val tabH = 12

    override fun init() {
        super.init()
        SpawnData.ensureLoaded()

        if (allSpeciesSorted == null) {
            allSpeciesSorted = PokemonSpecies.species
                .sortedBy { it.nationalPokedexNumber }
        }

        if (allEntriesSorted == null) {
            val entries = mutableListOf<PokemonEntry>()
            for (sp in allSpeciesSorted!!) {
                entries.add(PokemonEntry.Regular(sp))
                try {
                    val megas = sp.forms.filter { form ->
                        try { form.name.lowercase().contains("mega") } catch (_: Exception) { false }
                    }
                    for (megaForm in megas) {
                        entries.add(PokemonEntry.Mega(sp, megaForm))
                    }
                } catch (_: Exception) {}
            }
            allEntriesSorted = entries
        }

        refreshList()

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 37, panelWidth - 20, 16, Text.literal(placeholder))
        searchField.setMaxLength(50)
        searchField.setChangedListener { updateSearch() }
        addDrawableChild(searchField)
        setInitialFocus(searchField)
    }

    private fun refreshList() {
        if (showingHistory) {
            filteredList = SearchHistory.getSpeciesList().map { PokemonEntry.Regular(it) }
        } else {
            filteredList = allEntriesSorted ?: emptyList()
        }
    }

    private fun updateSearch() {
        if (showingHistory) return
        val query = searchField.text.lowercase().trim()
        scrollOffset = 0

        if (query.isEmpty()) {
            filteredList = allEntriesSorted ?: emptyList()
            return
        }

        filteredList = (allEntriesSorted ?: emptyList()).filter { entry ->
            when (entry) {
                is PokemonEntry.Regular -> entry.species.name.contains(query) ||
                    entry.species.translatedName.string.lowercase().contains(query)
                is PokemonEntry.Mega -> {
                    val mLabel = PokemonEntry.megaLabel(entry.form.name).lowercase()
                    val fullName = "$mLabel ${entry.species.translatedName.string}".lowercase()
                    entry.species.name.contains(query) ||
                    entry.species.translatedName.string.lowercase().contains(query) ||
                    fullName.contains(query)
                }
            }
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
        drawBorder(context, panelX, panelTop, panelWidth, panelHeight, ModConfig.accentColor())

        // Title
        val title: String = if (showingHistory) Translations.tr("\u2726 History \u2726") else Translations.tr("\u2726 Pok\u00e9mon Search \u2726")
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 6, ModConfig.accentColor(), true)

        // Options button (top-right of screen, MC-style)
        optBtnX = width - optBtnSize - 4
        optBtnY = 4
        val optHovered = mouseX >= optBtnX && mouseX <= optBtnX + optBtnSize &&
                         mouseY >= optBtnY && mouseY <= optBtnY + optBtnSize
        val btnBase = if (optHovered) 0xFFA0A0A0.toInt() else 0xFF808080.toInt()
        val btnLight = if (optHovered) 0xFFDDDDDD.toInt() else 0xFFBBBBBB.toInt()
        val btnDark = if (optHovered) 0xFF666666.toInt() else 0xFF444444.toInt()
        context.fill(optBtnX, optBtnY, optBtnX + optBtnSize, optBtnY + optBtnSize, btnBase)
        context.fill(optBtnX, optBtnY, optBtnX + optBtnSize, optBtnY + 1, btnLight)
        context.fill(optBtnX, optBtnY, optBtnX + 1, optBtnY + optBtnSize, btnLight)
        context.fill(optBtnX, optBtnY + optBtnSize - 1, optBtnX + optBtnSize, optBtnY + optBtnSize, btnDark)
        context.fill(optBtnX + optBtnSize - 1, optBtnY, optBtnX + optBtnSize, optBtnY + optBtnSize, btnDark)
        context.drawTexture(OPTIONS_ICON, optBtnX + 4, optBtnY + 4, 0f, 0f, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8)

        // History toggle button (right side of header)
        val btnLabel: String = if (showingHistory) Translations.tr("All") else Translations.tr("History")
        val btnW = textRenderer.getWidth(btnLabel) + 8
        val btnX = panelX + panelWidth - btnW - 8
        val btnY = panelTop + 4
        val btnH = 12
        val btnHovered = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH
        context.fill(btnX, btnY, btnX + btnW, btnY + btnH, if (btnHovered) 0xFF252525.toInt() else 0xFF1A1A1A.toInt())
        if (btnHovered) {
            context.fill(btnX, btnY, btnX + btnW, btnY + 1, 0xFFFFAA00.toInt())
            context.fill(btnX, btnY + btnH - 1, btnX + btnW, btnY + btnH, 0xFFFFAA00.toInt())
            context.fill(btnX, btnY, btnX + 1, btnY + btnH, 0xFFFFAA00.toInt())
            context.fill(btnX + btnW - 1, btnY, btnX + btnW, btnY + btnH, 0xFFFFAA00.toInt())
        }
        context.drawText(textRenderer, btnLabel, btnX + 4, btnY + 2, if (btnHovered) 0xFFFFAA00.toInt() else 0xFFAAAAAA.toInt(), true)

        // Gold separator
        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, 0xFFFFAA00.toInt())
        context.fill(panelX + 6, panelTop + 19, panelX + panelWidth - 6, panelTop + 20, 0xFF442200.toInt())

        // Tabs: Pokémon | Capacités
        val pokemonLabel: String = Translations.tr("Pokémon")
        val movesLabel: String = Translations.tr("Moves")
        val pokTabW = textRenderer.getWidth(pokemonLabel) + 8
        movesTabW = textRenderer.getWidth(movesLabel) + 8
        val pokTabX = panelX + 8
        movesTabX = pokTabX + pokTabW + 2
        val tY = panelTop + 20

        // Pokemon tab (active)
        context.fill(pokTabX, tY, pokTabX + pokTabW, tY + tabH, 0xFF2A2200.toInt())
        drawBorder(context, pokTabX, tY, pokTabW, tabH, ModConfig.accentColor())
        context.drawText(textRenderer, pokemonLabel, pokTabX + 4, tY + 2, ModConfig.accentColor(), true)

        // Moves tab (inactive)
        val movTabHovered = mouseX in movesTabX..(movesTabX + movesTabW) && mouseY in tY..(tY + tabH)
        context.fill(movesTabX, tY, movesTabX + movesTabW, tY + tabH, if (movTabHovered) 0xFF252525.toInt() else 0xFF1A1A1A.toInt())
        context.drawText(textRenderer, movesLabel, movesTabX + 4, tY + 2, if (movTabHovered) 0xFFDDDDDD.toInt() else 0xFF888888.toInt(), true)

        // Results area
        val resultsTop = panelTop + 59
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        context.fill(panelX + 6, resultsTop - 2, panelX + panelWidth - 6, resultsTop - 1, 0xFF333333.toInt())

        // Scissor for scrollable results
        context.enableScissor(panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        if (filteredList.isEmpty()) {
            val noResult: String = if (showingHistory) Translations.tr("No history yet") else Translations.tr("No Pok\u00e9mon found")
            context.drawText(textRenderer, noResult, panelX + 15, resultsTop + 10, 0xFF666666.toInt(), true)
        } else {
            var y = resultsTop + 2 - scrollOffset
            for (entry in filteredList) {
                if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                    val hovered = mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                                  mouseY >= y && mouseY <= y + rowHeight &&
                                  mouseY >= resultsTop && mouseY <= resultsBottom

                    if (hovered) {
                        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + rowHeight, 0xFF252525.toInt())
                    }

                    when (entry) {
                        is PokemonEntry.Regular -> {
                            val spawns = SpawnData.getSpawns(entry.species.name)
                            val rarity = spawns.firstOrNull()?.bucket ?: ""
                            context.fill(panelX + 10, y + 4, panelX + 14, y + 8, getRarityColor(rarity))

                            val displayName = entry.species.translatedName.string
                            val nameColor = if (hovered) 0xFFFFAA00.toInt() else 0xFFFFFFFF.toInt()
                            context.drawText(textRenderer, displayName, panelX + 18, y + 3, nameColor, true)

                            val dexText = "#${entry.species.nationalPokedexNumber}"
                            val dexWidth = textRenderer.getWidth(dexText)
                            context.drawText(textRenderer, dexText, panelX + panelWidth - 12 - dexWidth, y + 3, 0xFF777777.toInt(), true)
                        }
                        is PokemonEntry.Mega -> {
                            // Purple crystal dot marker
                            context.fill(panelX + 10, y + 4, panelX + 14, y + 8, 0xFF9966FF.toInt())

                            // "↳" indent prefix
                            val prefix = "\u21b3 "
                            val prefixW = textRenderer.getWidth(prefix)
                            context.drawText(textRenderer, prefix, panelX + 18, y + 3, 0xFF665588.toInt(), true)

                            val mLabel = PokemonEntry.megaLabel(entry.form.name)
                            val displayName = "$mLabel ${entry.species.translatedName.string}"
                            val nameColor = if (hovered) 0xFFFFAA00.toInt() else 0xFFCC99FF.toInt()
                            context.drawText(textRenderer, displayName, panelX + 18 + prefixW, y + 3, nameColor, true)

                            val dexText = "#${entry.species.nationalPokedexNumber}"
                            val dexWidth = textRenderer.getWidth(dexText)
                            context.drawText(textRenderer, dexText, panelX + panelWidth - 12 - dexWidth, y + 3, 0xFF665588.toInt(), true)
                        }
                    }
                }
                y += rowHeight
            }
        }

        context.disableScissor()

        // Scrollbar
        val contentHeight = filteredList.size * rowHeight + 4
        sbMaxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        if (contentHeight > resultsAreaHeight && resultsAreaHeight > 0) {
            sbTrackX = panelX + panelWidth - 5
            sbContentTop = resultsTop
            sbContentBottom = resultsBottom
            context.fill(sbTrackX, resultsTop, sbTrackX + 3, resultsBottom, 0xFF1A1A1A.toInt())
            sbThumbHeight = maxOf(15, resultsAreaHeight * resultsAreaHeight / contentHeight)
            val maxScroll = contentHeight - resultsAreaHeight
            sbThumbY = resultsTop + (scrollOffset * (resultsAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, 0xFFFFAA00.toInt())
        }

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint: String = Translations.tr("ESC to close  \u2022  Click for details")
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)

        // Render search field and other widgets
        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
            val panelX = (width - panelWidth) / 2
            val panelTop = 25

            // Options button click
            if (mouseX >= optBtnX && mouseX <= optBtnX + optBtnSize &&
                mouseY >= optBtnY.toDouble() && mouseY <= (optBtnY + optBtnSize).toDouble()) {
                client?.setScreen(OptionsScreen(this))
                return true
            }

            // History toggle button click
            val btnLabel: String = if (showingHistory) Translations.tr("All") else Translations.tr("History")
            val btnW = textRenderer.getWidth(btnLabel) + 8
            val btnX = panelX + panelWidth - btnW - 8
            val btnY = panelTop + 4
            val btnH = 12
            if (mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY.toDouble() && mouseY <= (btnY + btnH).toDouble()) {
                showingHistory = !showingHistory
                scrollOffset = 0
                refreshList()
                return true
            }

            // Moves tab click
            val tY = panelTop + 20
            if (mouseX >= movesTabX && mouseX <= movesTabX + movesTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(MoveSearchScreen())
                return true
            }

            // Check scrollbar click
            val contentHeight = filteredList.size * rowHeight + 4
            val resultsTop = panelTop + 59
            val resultsBottom = height - 25 - 16
            val resultsAreaHeight = resultsBottom - resultsTop

            if (contentHeight > resultsAreaHeight && resultsAreaHeight > 0) {
                val trackX = panelX + panelWidth - 5
                if (mouseX >= trackX && mouseX <= trackX + 3 &&
                    mouseY >= resultsTop && mouseY <= resultsBottom) {
                    val thumbHeight = maxOf(15, resultsAreaHeight * resultsAreaHeight / contentHeight)
                    val maxScroll = contentHeight - resultsAreaHeight
                    val thumbY = resultsTop + (scrollOffset * (resultsAreaHeight - thumbHeight) / maxOf(1, maxScroll))

                    if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
                        isScrollbarDragging = true
                        scrollbarDragStartY = mouseY
                        scrollbarDragStartOffset = scrollOffset
                    } else {
                        val clickRatio = (mouseY - resultsTop - thumbHeight / 2.0) / (resultsAreaHeight - thumbHeight)
                        scrollOffset = (clickRatio * maxScroll).toInt().coerceIn(0, maxScroll)
                    }
                    return true
                }
            }

            // Check results click
            if (filteredList.isNotEmpty()) {
                val resultsTop2 = panelTop + 59
                val resultsBottom2 = height - 25 - 16
                if (mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                    mouseY >= resultsTop2 && mouseY <= resultsBottom2) {
                    val relativeY = mouseY.toInt() - resultsTop2 + scrollOffset - 2
                    val index = relativeY / rowHeight
                    if (index >= 0 && index < filteredList.size) {
                        when (val entry = filteredList[index]) {
                            is PokemonEntry.Regular ->
                                client?.setScreen(PokemonDetailScreen(entry.species, this, allSpeciesSorted))
                            is PokemonEntry.Mega ->
                                client?.setScreen(PokemonDetailScreen(entry.species, this, allSpeciesSorted, entry.form.name))
                        }
                        return true
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (isScrollbarDragging && button == 0) {
            val panelTop = 25
            val resultsTop = panelTop + 59
            val resultsBottom = height - 25 - 16
            val resultsAreaHeight = resultsBottom - resultsTop
            val contentHeight = filteredList.size * rowHeight + 4
            val thumbHeight = maxOf(15, resultsAreaHeight * resultsAreaHeight / contentHeight)
            val maxScroll = maxOf(0, contentHeight - resultsAreaHeight)
            val trackRange = resultsAreaHeight - thumbHeight

            if (trackRange > 0) {
                val dy = mouseY - scrollbarDragStartY
                val scrollDelta = (dy / trackRange * maxScroll).toInt()
                scrollOffset = (scrollbarDragStartOffset + scrollDelta).coerceIn(0, maxScroll)
            }
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && isScrollbarDragging) {
            isScrollbarDragging = false
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelTop = 25
        val panelBottom = height - 25
        val resultsTop = panelTop + 59
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop
        val contentHeight = filteredList.size * rowHeight + 4
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
