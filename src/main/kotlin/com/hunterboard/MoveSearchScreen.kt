package com.hunterboard

import com.cobblemon.mod.common.api.moves.MoveTemplate
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class MoveSearchScreen : Screen(Text.literal("Move Search")) {

    companion object {
        var savedScrollOffset = 0
        var savedQuery = ""
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    private lateinit var searchField: TextFieldWidget
    private var allMovesSorted: List<MoveTemplate>? = null
    private var filteredMoves: List<MoveTemplate> = emptyList()
    private var scrollOffset = 0
    private var lastQuery = ""
    private val rowHeight = 16

    // Sort mode: 0=A-Z, 1=Type, 2=Category, 3=Power
    private var sortMode = 0

    // Options button
    private val OPTIONS_ICON = Identifier.of("hunterboard", "img/option.png")
    private val optBtnSize = 24
    private var optBtnX = 0
    private var optBtnY = 0

    // Tab bounds
    private var pokemonTabX = 0
    private var pokemonTabW = 0
    private var movesTabX = 0
    private var movesTabW = 0
    private var abilitiesTabX = 0
    private var abilitiesTabW = 0
    private var itemsTabX = 0
    private var itemsTabW = 0
    private val tabY get() = 25 + 20
    private val tabH = 12

    // Sort button bounds
    private val sortLabels = arrayOf("A-Z", "Type", "Cat.", "Pow")
    private var sortBtnBounds = Array(4) { intArrayOf(0, 0, 0) } // x, y, w

    // Language toggle button bounds
    private var langBtnXField = 0
    private var langBtnYField = 0
    private var langBtnWField = 0

    // Scrollbar drag state
    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0.0
    private var scrollbarDragStartOffset = 0
    private var sbTrackX = 0
    private var sbContentTop = 0
    private var sbContentBottom = 0
    private var sbThumbY = 0
    private var sbThumbHeight = 0
    private var sbMaxScroll = 0

    override fun init() {
        super.init()
        if (allMovesSorted == null) {
            allMovesSorted = MoveData.getAllMoves()
        }

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 50, panelWidth - 20, 16, Text.literal(placeholder))
        searchField.setMaxLength(50)
        searchField.text = savedQuery
        lastQuery = savedQuery
        searchField.setChangedListener { applySortAndFilter(); savedQuery = searchField.text; savedScrollOffset = scrollOffset }
        addDrawableChild(searchField)
        setInitialFocus(searchField)

        applySortAndFilter()
        scrollOffset = savedScrollOffset

        // Pre-warm reverse index in background
        Thread { MoveData.buildReverseIndex() }.start()
    }

    private fun applySortAndFilter() {
        val base = allMovesSorted ?: emptyList()
        val query = if (::searchField.isInitialized) searchField.text.lowercase().trim() else ""

        val searched = if (query.isEmpty()) base else base.filter { move ->
            NameUtil.matchesQuery(Translations.moveDisplayName(move), query) ||
            NameUtil.matchesQuery(move.name, query) ||
            move.elementalType.name.lowercase().contains(query)
        }

        filteredMoves = when (sortMode) {
            1 -> searched.sortedWith(compareBy({ it.elementalType.name }, { Translations.moveDisplayName(it).lowercase() }))
            2 -> searched.sortedWith(compareBy({ it.damageCategory.name }, { Translations.moveDisplayName(it).lowercase() }))
            3 -> searched.sortedByDescending { it.power.toInt() }
            else -> searched.sortedBy { Translations.moveDisplayName(it).lowercase() }
        }
        if (query != lastQuery) {
            scrollOffset = 0
            lastQuery = query
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        UiKit.screenDim(context, width, height)

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        UiKit.panel(context, panelX, panelTop, panelWidth, panelHeight)

        // Title
        val title: String = Translations.tr("Move Search")
        UiKit.header(context, textRenderer, panelX, panelTop, panelWidth, title)

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        UiKit.closeButton(context, textRenderer, closeX, closeY, mouseX, mouseY)

        // Options button (attached to panel top-right corner, outside)
        optBtnX = panelX + panelWidth + 2
        optBtnY = panelTop
        val optHovered = mouseX in optBtnX..(optBtnX + optBtnSize) && mouseY in optBtnY..(optBtnY + optBtnSize)
        UiKit.button(context, textRenderer, optBtnX, optBtnY, optBtnSize, optBtnSize, "", optHovered)
        context.drawTexture(OPTIONS_ICON, optBtnX + 4, optBtnY + 4, 0f, 0f, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8)

        // Gold separator

        // Tabs: Pokémon | Capacités | Talents | Objets
        val pokemonLabel: String = Translations.tr("Pokémon")
        val movesLabel: String = Translations.tr("Moves")
        val abilitiesLabel: String = Translations.tr("Abilities")
        val itemsLabel: String = Translations.tr("Items")
        pokemonTabW = textRenderer.getWidth(pokemonLabel) + 8
        movesTabW = textRenderer.getWidth(movesLabel) + 8
        abilitiesTabW = textRenderer.getWidth(abilitiesLabel) + 8
        itemsTabW = textRenderer.getWidth(itemsLabel) + 8
        pokemonTabX = panelX + 8
        movesTabX = pokemonTabX + pokemonTabW + 2
        abilitiesTabX = movesTabX + movesTabW + 2
        itemsTabX = abilitiesTabX + abilitiesTabW + 2
        val tY = panelTop + 20

        // Pokemon tab (inactive)
        val pokTabHovered = mouseX in pokemonTabX..(pokemonTabX + pokemonTabW) && mouseY in tY..(tY + tabH)
        UiKit.tab(context, textRenderer, pokemonTabX, tY, pokemonTabW, tabH, pokemonLabel, false, pokTabHovered)

        // Moves tab (active)
        UiKit.tab(context, textRenderer, movesTabX, tY, movesTabW, tabH, movesLabel, true, false)

        // Abilities tab (inactive)
        val abiTabHovered = mouseX in abilitiesTabX..(abilitiesTabX + abilitiesTabW) && mouseY in tY..(tY + tabH)
        UiKit.tab(context, textRenderer, abilitiesTabX, tY, abilitiesTabW, tabH, abilitiesLabel, false, abiTabHovered)

        // Items tab (inactive)
        val itemTabHovered = mouseX in itemsTabX..(itemsTabX + itemsTabW) && mouseY in tY..(tY + tabH)
        UiKit.tab(context, textRenderer, itemsTabX, tY, itemsTabW, tabH, itemsLabel, false, itemTabHovered)

        // Language toggle button (right side of tab row)
        val langLabel = Translations.nameLanguageLabel()
        langBtnWField = textRenderer.getWidth(langLabel) + 8
        langBtnXField = panelX + panelWidth - langBtnWField - 8
        langBtnYField = tY
        val langBtnW = langBtnWField
        val langBtnX = langBtnXField
        val langBtnY = langBtnYField
        val langBtnH = tabH
        val langHovered = mouseX in langBtnX..(langBtnX + langBtnW) && mouseY in langBtnY..(langBtnY + langBtnH)
        context.fill(langBtnX, langBtnY, langBtnX + langBtnW, langBtnY + langBtnH, if (langHovered) UiKit.SURFACE_HOVER else UiKit.SURFACE)
        drawBorder(context, langBtnX, langBtnY, langBtnW, langBtnH, if (langHovered) UiKit.accent() else UiKit.TEXT_FAINT)
        context.drawText(textRenderer, langLabel, langBtnX + 4, langBtnY + 2, if (langHovered) UiKit.accent() else UiKit.TEXT_MUTED, true)

        // Sort buttons
        val sortY = panelTop + 34
        var sortX = panelX + 8
        for (i in sortLabels.indices) {
            val label: String = Translations.tr(sortLabels[i])
            val sw = textRenderer.getWidth(label) + 6
            sortBtnBounds[i] = intArrayOf(sortX, sortY, sw)
            val isActive = sortMode == i
            val sortHovered = mouseX in sortX..(sortX + sw) && mouseY in sortY..(sortY + 10)
            val bg = when {
                isActive -> UiKit.withAlpha(UiKit.accent(), 0x40)
                sortHovered -> UiKit.SURFACE_HOVER
                else -> UiKit.SURFACE
            }
            context.fill(sortX, sortY, sortX + sw, sortY + 10, bg)
            if (isActive) {
                context.fill(sortX, sortY + 9, sortX + sw, sortY + 10, UiKit.accent())
            }
            val color = when {
                isActive -> UiKit.accent()
                sortHovered -> UiKit.TEXT
                else -> UiKit.TEXT_MUTED
            }
            context.drawText(textRenderer, label, sortX + 3, sortY + 1, color, true)
            sortX += sw + 3
        }

        // Results area
        val resultsTop = panelTop + 72
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        context.fill(panelX + 6, resultsTop - 2, panelX + panelWidth - 6, resultsTop - 1, UiKit.BORDER_DIM)

        context.enableScissor(panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        if (filteredMoves.isEmpty()) {
            val noResult: String = Translations.tr("No move found")
            context.drawText(textRenderer, noResult, panelX + 15, resultsTop + 10, UiKit.TEXT_FAINT, true)
        } else {
            val typeColW = 50
            val typeColX = panelX + 10
            val nameColX = typeColX + typeColW + 4
            val ppColX = panelX + panelWidth - 28
            val accColX = ppColX - 32
            val powColX = accColX - 26
            val catColX = powColX - 50

            var y = resultsTop + 2 - scrollOffset
            for (move in filteredMoves) {
                if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                    val hovered = mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                                  mouseY in y..(y + rowHeight) &&
                                  mouseY >= resultsTop && mouseY <= resultsBottom

                    if (hovered) {
                        UiKit.rowHighlight(context, panelX + 6, y, panelWidth - 12, rowHeight)
                    }

                    // Type badge
                    val typeKey = move.elementalType.name
                    drawMiniTypeBadge(context, typeColX, y + 2, typeKey)

                    // Move name
                    val displayName = Translations.moveDisplayName(move)
                    val nameColor = if (hovered) UiKit.accent() else UiKit.TEXT
                    context.drawText(textRenderer, displayName, nameColX, y + 3, nameColor, true)

                    // Category (right area)
                    val catName = move.damageCategory.displayName.string
                    val catColor = getCategoryColor(move.damageCategory.name)
                    context.drawText(textRenderer, catName, catColX, y + 3, catColor, true)

                    // Power
                    val power = move.power.toInt()
                    context.drawText(textRenderer, if (power > 0) "$power" else "-", powColX, y + 3, UiKit.TEXT_MUTED, true)

                    // Accuracy
                    val acc = move.accuracy
                    context.drawText(textRenderer, if (acc > 0) "${acc.toInt()}%" else "-", accColX, y + 3, UiKit.TEXT_MUTED, true)

                    // PP
                    context.drawText(textRenderer, "${move.pp}pp", ppColX, y + 3, UiKit.TEXT_FAINT, true)
                }
                y += rowHeight
            }
        }

        context.disableScissor()

        // Scrollbar
        val contentHeight = filteredMoves.size * rowHeight + 4
        sbMaxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        if (contentHeight > resultsAreaHeight && resultsAreaHeight > 0) {
            sbTrackX = panelX + panelWidth - 5
            sbContentTop = resultsTop
            sbContentBottom = resultsBottom
            context.fill(sbTrackX, resultsTop, sbTrackX + 3, resultsBottom, UiKit.SURFACE)
            sbThumbHeight = maxOf(15, resultsAreaHeight * resultsAreaHeight / contentHeight)
            val maxScroll = contentHeight - resultsAreaHeight
            sbThumbY = resultsTop + (scrollOffset * (resultsAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, UiKit.accent())
        }

        // Footer
                val hint: String = Translations.tr("ESC to close  \u2022  Click for details")
        UiKit.footer(context, textRenderer, panelX, panelBottom, panelWidth, hint)

        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
            val panelX = (width - panelWidth) / 2
            val panelTop = 25

            // Close button ✕
            val closeX = panelX + panelWidth - 12
            val closeY = panelTop + 4
            if (mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2.0 && mouseY <= closeY + 11.0) {
                close(); return true
            }

            // Language toggle button click
            if (mouseX >= langBtnXField && mouseX <= langBtnXField + langBtnWField &&
                mouseY >= langBtnYField.toDouble() && mouseY <= (langBtnYField + tabH).toDouble()) {
                Translations.toggleNameLanguage()
                applySortAndFilter()
                return true
            }

            // Options button
            if (mouseX >= optBtnX && mouseX <= optBtnX + optBtnSize &&
                mouseY >= optBtnY.toDouble() && mouseY <= (optBtnY + optBtnSize).toDouble()) {
                client?.setScreen(OptionsScreen(this))
                return true
            }

            // Pokemon tab click
            val tY = panelTop + 20
            if (mouseX >= pokemonTabX && mouseX <= pokemonTabX + pokemonTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(PokemonSearchScreen())
                return true
            }

            // Abilities tab click
            if (mouseX >= abilitiesTabX && mouseX <= abilitiesTabX + abilitiesTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(AbilitySearchScreen())
                return true
            }

            // Items tab click
            if (mouseX >= itemsTabX && mouseX <= itemsTabX + itemsTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(ItemDropSearchScreen())
                return true
            }

            // Sort button clicks
            for (i in sortBtnBounds.indices) {
                val (sx, sy, sw) = sortBtnBounds[i]
                if (mouseX >= sx && mouseX <= sx + sw &&
                    mouseY >= sy.toDouble() && mouseY <= (sy + 10).toDouble()) {
                    sortMode = i
                    applySortAndFilter()
                    return true
                }
            }

            // Scrollbar click
            val contentHeight = filteredMoves.size * rowHeight + 4
            val resultsTop = panelTop + 72
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

            // Results click
            if (filteredMoves.isNotEmpty()) {
                if (mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                    mouseY >= resultsTop && mouseY <= resultsBottom) {
                    val relativeY = mouseY.toInt() - resultsTop + scrollOffset - 2
                    val index = relativeY / rowHeight
                    if (index in filteredMoves.indices) {
                        val move = filteredMoves[index]
                        client?.setScreen(MoveDetailScreen(move, this))
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
            val resultsTop = panelTop + 72
            val resultsBottom = height - 25 - 16
            val resultsAreaHeight = resultsBottom - resultsTop
            val contentHeight = filteredMoves.size * rowHeight + 4
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
        val resultsTop = panelTop + 72
        val resultsBottom = height - 25 - 16
        val resultsAreaHeight = resultsBottom - resultsTop
        val contentHeight = filteredMoves.size * rowHeight + 4
        val maxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        savedScrollOffset = scrollOffset
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun drawMiniTypeBadge(context: DrawContext, x: Int, y: Int, typeKey: String) {
        val badgeW = 48
        val badgeH = 11
        val displayName = Translations.formatTypeName(typeKey)
        val color = getTypeColor(typeKey)
        context.fill(x, y, x + badgeW, y + badgeH, color)
        val textW = textRenderer.getWidth(displayName)
        val textX = x + (badgeW - textW) / 2
        context.drawText(textRenderer, displayName, textX, y + 1, 0xFFFFFFFF.toInt(), true)
    }

    private fun getTypeColor(type: String): Int {
        return when (type.lowercase()) {
            "normal" -> 0xFFA8A878.toInt(); "fire" -> 0xFFF08030.toInt()
            "water" -> 0xFF6890F0.toInt(); "electric" -> 0xFFF8D030.toInt()
            "grass" -> 0xFF78C850.toInt(); "ice" -> 0xFF98D8D8.toInt()
            "fighting" -> 0xFFC03028.toInt(); "poison" -> 0xFFA040A0.toInt()
            "ground" -> 0xFFE0C068.toInt(); "flying" -> 0xFFA890F0.toInt()
            "psychic" -> 0xFFF85888.toInt(); "bug" -> 0xFFA8B820.toInt()
            "rock" -> 0xFFB8A038.toInt(); "ghost" -> 0xFF705898.toInt()
            "dragon" -> 0xFF7038F8.toInt(); "dark" -> 0xFF705848.toInt()
            "steel" -> 0xFFB8B8D0.toInt(); "fairy" -> 0xFFEE99AC.toInt()
            else -> UiKit.TEXT_MUTED
        }
    }

    private fun getCategoryColor(name: String): Int {
        return when (name.lowercase()) {
            "physical" -> 0xFFFF6644.toInt(); "special" -> 0xFF6688FF.toInt()
            "status" -> 0xFFAABBCC.toInt(); else -> UiKit.TEXT_MUTED
        }
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
