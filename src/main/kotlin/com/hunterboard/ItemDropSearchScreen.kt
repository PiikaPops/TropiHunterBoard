package com.hunterboard

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class ItemDropSearchScreen : Screen(Text.literal("Item Drop Search")) {

    companion object {
        var savedScrollOffset = 0
        var savedQuery = ""
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    private lateinit var searchField: TextFieldWidget
    private var filteredItems: List<Identifier> = emptyList()
    private var scrollOffset = 0
    private var lastQuery = ""
    private val rowHeight = 20

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
    private val tabH = 12

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
        if (!DropData.reverseIndexReady) {
            Thread { DropData.buildReverseIndex() }.also { it.isDaemon = true }.start()
        }

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 37, panelWidth - 20, 16, Text.literal(placeholder))
        searchField.setMaxLength(50)
        searchField.text = savedQuery
        lastQuery = savedQuery
        searchField.setChangedListener { applySortAndFilter(); savedQuery = searchField.text; savedScrollOffset = scrollOffset }
        addDrawableChild(searchField)
        setInitialFocus(searchField)

        applySortAndFilter()
        scrollOffset = savedScrollOffset
    }

    private fun getItemDisplayName(itemId: Identifier): String {
        return try {
            val item = Registries.ITEM.get(itemId)
            val stack = ItemStack(item)
            stack.name.string
        } catch (_: Exception) { itemId.path }
    }

    private fun applySortAndFilter() {
        val base = DropData.getAllDroppableItems()
        val query = if (::searchField.isInitialized) searchField.text.lowercase().trim() else ""

        val searched = if (query.isEmpty()) base else base.filter { itemId ->
            val displayName = getItemDisplayName(itemId)
            NameUtil.matchesQuery(displayName, query) || NameUtil.matchesQuery(itemId.path, query)
        }

        filteredItems = searched.sortedBy { getItemDisplayName(it).lowercase() }
        if (query != lastQuery) {
            scrollOffset = 0
            lastQuery = query
        }
    }

    private var lastIndexReady = false

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        if (DropData.reverseIndexReady && !lastIndexReady) {
            lastIndexReady = true
            applySortAndFilter()
        }

        UiKit.screenDim(context, width, height)

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        UiKit.panel(context, panelX, panelTop, panelWidth, panelHeight)

        // Title
        val title: String = Translations.tr("Item Search")
        UiKit.header(context, textRenderer, panelX, panelTop, panelWidth, title)

        // Close button
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        UiKit.closeButton(context, textRenderer, closeX, closeY, mouseX, mouseY)

        // Options button
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

        // Moves tab (inactive)
        val movTabHovered = mouseX in movesTabX..(movesTabX + movesTabW) && mouseY in tY..(tY + tabH)
        UiKit.tab(context, textRenderer, movesTabX, tY, movesTabW, tabH, movesLabel, false, movTabHovered)

        // Abilities tab (inactive)
        val abiTabHovered = mouseX in abilitiesTabX..(abilitiesTabX + abilitiesTabW) && mouseY in tY..(tY + tabH)
        UiKit.tab(context, textRenderer, abilitiesTabX, tY, abilitiesTabW, tabH, abilitiesLabel, false, abiTabHovered)

        // Items tab (active)
        UiKit.tab(context, textRenderer, itemsTabX, tY, itemsTabW, tabH, itemsLabel, true, false)

        // Language toggle button
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

        // Results area
        val resultsTop = panelTop + 59
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        context.fill(panelX + 6, resultsTop - 2, panelX + panelWidth - 6, resultsTop - 1, UiKit.BORDER_DIM)

        context.enableScissor(panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        if (!DropData.reverseIndexReady) {
            val loadingText: String = Translations.tr("Loading...")
            context.drawText(textRenderer, loadingText, panelX + 15, resultsTop + 10, UiKit.TEXT_MUTED, true)
        } else if (filteredItems.isEmpty()) {
            val noResult: String = Translations.tr("No item found")
            context.drawText(textRenderer, noResult, panelX + 15, resultsTop + 10, UiKit.TEXT_FAINT, true)
        } else {
            val countColX = panelX + panelWidth - 40
            var y = resultsTop + 2 - scrollOffset
            for (itemId in filteredItems) {
                if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                    val hovered = mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                                  mouseY in y..(y + rowHeight) &&
                                  mouseY >= resultsTop && mouseY <= resultsBottom

                    if (hovered) {
                        UiKit.rowHighlight(context, panelX + 6, y, panelWidth - 12, rowHeight)
                    }

                    // Item icon
                    try {
                        val item = Registries.ITEM.get(itemId)
                        val stack = ItemStack(item)
                        if (!stack.isEmpty) {
                            context.drawItem(stack, panelX + 10, y + 1)
                        }
                    } catch (_: Exception) {}

                    // Item name
                    val displayName = getItemDisplayName(itemId)
                    val nameColor = if (hovered) UiKit.accent() else UiKit.TEXT
                    context.drawText(textRenderer, displayName, panelX + 30, y + 5, nameColor, true)

                    // Pokémon count
                    val count = DropData.getSpeciesWithDrop(itemId.toString()).size
                    val countText = "${count}\u2726"
                    context.drawText(textRenderer, countText, countColX, y + 5, UiKit.TEXT_MUTED, true)
                }
                y += rowHeight
            }
        }

        context.disableScissor()

        // Scrollbar
        val contentHeight = filteredItems.size * rowHeight + 4
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

            // Close button
            val closeX = panelX + panelWidth - 12
            val closeY = panelTop + 4
            if (mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2.0 && mouseY <= closeY + 11.0) {
                close(); return true
            }

            // Language toggle
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

            // Tab clicks
            val tY = panelTop + 20
            if (mouseX >= pokemonTabX && mouseX <= pokemonTabX + pokemonTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(PokemonSearchScreen())
                return true
            }
            if (mouseX >= movesTabX && mouseX <= movesTabX + movesTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(MoveSearchScreen())
                return true
            }
            if (mouseX >= abilitiesTabX && mouseX <= abilitiesTabX + abilitiesTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(AbilitySearchScreen())
                return true
            }

            // Scrollbar click
            val contentHeight = filteredItems.size * rowHeight + 4
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

            // Results click
            if (DropData.reverseIndexReady && filteredItems.isNotEmpty()) {
                if (mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                    mouseY >= resultsTop && mouseY <= resultsBottom) {
                    val relativeY = mouseY.toInt() - resultsTop + scrollOffset - 2
                    val index = relativeY / rowHeight
                    if (index in filteredItems.indices) {
                        val itemId = filteredItems[index]
                        client?.setScreen(ItemDropDetailScreen(itemId, this))
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
            val contentHeight = filteredItems.size * rowHeight + 4
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
        if (button == 0 && isScrollbarDragging) { isScrollbarDragging = false; return true }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelTop = 25
        val resultsTop = panelTop + 59
        val resultsBottom = height - 25 - 16
        val resultsAreaHeight = resultsBottom - resultsTop
        val contentHeight = filteredItems.size * rowHeight + 4
        val maxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        savedScrollOffset = scrollOffset
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
