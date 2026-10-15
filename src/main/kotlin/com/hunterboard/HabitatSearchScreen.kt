package com.hunterboard

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/** Every habitat pool: where it generates, how many phases, how many species. */
class HabitatSearchScreen : ScaledScreen(Text.literal("Habitat Search")) {

    companion object {
        var savedScrollOffset = 0
        var savedQuery = ""
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    private lateinit var searchField: TextFieldWidget
    private var filtered: List<HabitatSummary> = emptyList()
    private var scrollOffset = 0
    private var lastQuery = ""
    private val rowHeight = 22

    private val OPTIONS_ICON = Identifier.of("hunterboard", "img/option.png")
    private val optBtnSize = 24
    private var optBtnX = 0
    private var optBtnY = 0

    private var pokemonTabX = 0; private var pokemonTabW = 0
    private var movesTabX = 0; private var movesTabW = 0
    private var abilitiesTabX = 0; private var abilitiesTabW = 0
    private var itemsTabX = 0; private var itemsTabW = 0
    private var habitatsTabX = 0; private var habitatsTabW = 0
    private val tabH = 12

    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0.0
    private var scrollbarDragStartOffset = 0
    private var sbTrackX = 0
    private var sbContentTop = 0
    private var sbContentBottom = 0
    private var sbMaxScroll = 0

    private var rowBounds = mutableListOf<Triple<Int, Int, String>>()   // y, height, habitatId

    override fun init() {
        super.init()
        HabitatData.ensureLoaded()

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 37, panelWidth - 20, 16, Text.literal(placeholder))
        searchField.setMaxLength(50)
        searchField.text = savedQuery
        lastQuery = savedQuery
        searchField.setChangedListener {
            applyFilter(); savedQuery = searchField.text; savedScrollOffset = scrollOffset
        }
        addDrawableChild(searchField)
        setInitialFocus(searchField)

        applyFilter()
        scrollOffset = savedScrollOffset
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        val all = HabitatData.allHabitats()
        filtered = if (query.isEmpty()) all else all.filter { habitat ->
            habitat.displayName.lowercase().contains(query) ||
                habitat.habitatId.lowercase().contains(query) ||
                HabitatData.biomesFor(habitat.habitatId).any { it.lowercase().contains(query) } ||
                habitat.speciesKeys.any { Translations.pokemonName(it).lowercase().contains(query) }
        }
        if (query != lastQuery) {
            scrollOffset = 0
            lastQuery = query
        }
    }

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        rowBounds.clear()
        UiKit.screenDim(context, width, height)

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        UiKit.panel(context, panelX, panelTop, panelWidth, panelHeight)
        UiKit.header(context, textRenderer, panelX, panelTop, panelWidth, Translations.tr("Habitat Search"))
        UiKit.closeButton(context, textRenderer, panelX + panelWidth - 12, panelTop + 4, mouseX, mouseY)

        optBtnX = panelX + panelWidth + 2
        optBtnY = panelTop
        val optHovered = mouseX in optBtnX..(optBtnX + optBtnSize) && mouseY in optBtnY..(optBtnY + optBtnSize)
        UiKit.button(context, textRenderer, optBtnX, optBtnY, optBtnSize, optBtnSize, "", optHovered)
        context.drawTexture(OPTIONS_ICON, optBtnX + 4, optBtnY + 4, 0f, 0f, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8)

        val tY = panelTop + 20
        drawTabs(context, panelX, tY, mouseX, mouseY)

        val resultsTop = panelTop + 59
        val resultsBottom = panelBottom - 16

        context.fill(panelX + 6, resultsTop - 2, panelX + panelWidth - 6, resultsTop - 1, UiKit.BORDER_DIM)
        scissor(context, panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        if (filtered.isEmpty()) {
            context.drawText(textRenderer, Translations.tr("No habitat found"),
                panelX + 15, resultsTop + 10, UiKit.TEXT_FAINT, true)
        } else {
            var y = resultsTop + 2 - scrollOffset
            for (habitat in filtered) {
                if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                    val hovered = mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                        mouseY in y..(y + rowHeight) && mouseY >= resultsTop && mouseY <= resultsBottom
                    if (hovered) UiKit.rowHighlight(context, panelX + 6, y, panelWidth - 12, rowHeight)

                    context.drawText(textRenderer, habitat.displayName, panelX + 12, y + 2,
                        if (hovered) UiKit.accent() else UiKit.TEXT, true)

                    val meta = "${habitat.speciesCount} ${Translations.tr("species")}" +
                        if (habitat.phaseCount > 1) " · ${habitat.phaseCount} ${Translations.tr("phases")}" else ""
                    context.drawText(textRenderer, meta,
                        panelX + panelWidth - 14 - textRenderer.getWidth(meta), y + 2, UiKit.TEXT_FAINT, true)

                    val biomes = HabitatData.biomesFor(habitat.habitatId)
                    val biomeText = if (biomes.isEmpty()) Translations.tr("Biomes unknown")
                                    else biomes.take(3).joinToString(", ") +
                                        if (biomes.size > 3) " +${biomes.size - 3}" else ""
                    context.drawText(textRenderer, biomeText, panelX + 12, y + 12, UiKit.TEXT_MUTED, true)

                    rowBounds.add(Triple(y, rowHeight, habitat.habitatId))
                }
                y += rowHeight
            }
        }

        context.disableScissor()

        val contentHeight = filtered.size * rowHeight + 4
        sbMaxScroll = (contentHeight - (resultsBottom - resultsTop)).coerceAtLeast(0)
        sbTrackX = panelX + panelWidth - 5
        sbContentTop = resultsTop
        sbContentBottom = resultsBottom
        UiKit.scrollbar(context, sbTrackX, resultsTop, resultsBottom, contentHeight, scrollOffset)

        renderWidgets(context, mouseX, mouseY, delta)
    }

    private fun drawTabs(context: DrawContext, panelX: Int, tY: Int, mouseX: Int, mouseY: Int) {
        val labels = listOf(
            Translations.tr("Pokémon"), Translations.tr("Moves"), Translations.tr("Abilities"),
            Translations.tr("Items"), Translations.tr("Habitats")
        )
        val widths = labels.map { textRenderer.getWidth(it) + 8 }
        var x = panelX + 8
        val xs = widths.map { w -> val cur = x; x += w + 2; cur }

        pokemonTabX = xs[0]; pokemonTabW = widths[0]
        movesTabX = xs[1]; movesTabW = widths[1]
        abilitiesTabX = xs[2]; abilitiesTabW = widths[2]
        itemsTabX = xs[3]; itemsTabW = widths[3]
        habitatsTabX = xs[4]; habitatsTabW = widths[4]

        for (i in labels.indices) {
            val active = i == 4
            val hovered = !active && mouseX in xs[i]..(xs[i] + widths[i]) && mouseY in tY..(tY + tabH)
            UiKit.tab(context, textRenderer, xs[i], tY, widths[i], tabH, labels[i], active, hovered)
        }
    }

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
            val panelX = (width - panelWidth) / 2
            val panelTop = 25

            val closeX = panelX + panelWidth - 12
            val closeY = panelTop + 4
            if (mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2.0 && mouseY <= closeY + 11.0) {
                close(); return true
            }

            if (mouseX >= optBtnX && mouseX <= optBtnX + optBtnSize &&
                mouseY >= optBtnY.toDouble() && mouseY <= (optBtnY + optBtnSize).toDouble()) {
                client?.setScreen(OptionsScreen(this)); return true
            }

            val tY = panelTop + 20
            if (mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                if (mouseX >= pokemonTabX && mouseX <= pokemonTabX + pokemonTabW) {
                    client?.setScreen(PokemonSearchScreen()); return true
                }
                if (mouseX >= movesTabX && mouseX <= movesTabX + movesTabW) {
                    client?.setScreen(MoveSearchScreen()); return true
                }
                if (mouseX >= abilitiesTabX && mouseX <= abilitiesTabX + abilitiesTabW) {
                    client?.setScreen(AbilitySearchScreen()); return true
                }
                if (mouseX >= itemsTabX && mouseX <= itemsTabX + itemsTabW) {
                    client?.setScreen(ItemDropSearchScreen()); return true
                }
            }

            for ((rowY, rowH, habitatId) in rowBounds) {
                if (mouseY >= rowY.toDouble() && mouseY <= (rowY + rowH).toDouble() &&
                    mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6) {
                    savedScrollOffset = scrollOffset
                    client?.setScreen(HabitatDetailScreen(habitatId, this))
                    return true
                }
            }

            if (sbMaxScroll > 0 && mouseX >= sbTrackX - 2 && mouseX <= sbTrackX + 5 &&
                mouseY >= sbContentTop.toDouble() && mouseY <= sbContentBottom.toDouble()) {
                isScrollbarDragging = true
                scrollbarDragStartY = mouseY
                scrollbarDragStartOffset = scrollOffset
                return true
            }
        }
        return super.mouseClickedScaled(mouseX, mouseY, button)
    }

    override fun mouseDraggedScaled(mouseX: Double, mouseY: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (isScrollbarDragging && sbMaxScroll > 0) {
            val viewHeight = sbContentBottom - sbContentTop
            val delta = ((mouseY - scrollbarDragStartY) * sbMaxScroll / viewHeight.coerceAtLeast(1)).toInt()
            scrollOffset = (scrollbarDragStartOffset + delta).coerceIn(0, sbMaxScroll)
            savedScrollOffset = scrollOffset
            return true
        }
        return super.mouseDraggedScaled(mouseX, mouseY, button, dx, dy)
    }

    override fun mouseReleasedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        isScrollbarDragging = false
        return super.mouseReleasedScaled(mouseX, mouseY, button)
    }

    override fun mouseScrolledScaled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
        scrollOffset = (scrollOffset - (vAmount * 12).toInt()).coerceIn(0, sbMaxScroll)
        savedScrollOffset = scrollOffset
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun shouldPause(): Boolean = false
}
