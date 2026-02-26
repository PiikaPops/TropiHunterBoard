package com.hunterboard

import com.cobblemon.mod.common.api.abilities.AbilityTemplate
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class AbilitySearchScreen : Screen(Text.literal("Ability Search")) {

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    private lateinit var searchField: TextFieldWidget
    private var filteredAbilities: List<AbilityTemplate> = emptyList()
    private var scrollOffset = 0
    private val rowHeight = 16

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
        if (!AbilityData.reverseIndexReady) {
            Thread { AbilityData.buildReverseIndex() }.also { it.isDaemon = true }.start()
        }
        applySortAndFilter()

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 37, panelWidth - 20, 16, Text.literal(placeholder))
        searchField.setMaxLength(50)
        searchField.setChangedListener { applySortAndFilter() }
        addDrawableChild(searchField)
        setInitialFocus(searchField)
    }

    private fun applySortAndFilter() {
        val base = AbilityData.getAllAbilities()
        val query = if (::searchField.isInitialized) searchField.text.lowercase().trim() else ""

        val searched = if (query.isEmpty()) base else base.filter { ability ->
            try {
                val displayName = Text.translatable(ability.displayName).string
                displayName.lowercase().contains(query) || ability.name.lowercase().contains(query)
            } catch (_: Exception) { ability.name.lowercase().contains(query) }
        }

        filteredAbilities = searched.sortedBy {
            try { Text.translatable(it.displayName).string.lowercase() } catch (_: Exception) { it.name }
        }
        scrollOffset = 0
    }

    // Track if we need to refresh when index finishes loading
    private var lastIndexReady = false

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Re-apply filter once the index finishes building
        if (AbilityData.reverseIndexReady && !lastIndexReady) {
            lastIndexReady = true
            applySortAndFilter()
        }

        context.fill(0, 0, width, height, 0xAA000000.toInt())

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        context.fill(panelX, panelTop, panelX + panelWidth, panelBottom, 0xF0101010.toInt())
        drawBorder(context, panelX, panelTop, panelWidth, panelHeight, ModConfig.accentColor())

        // Title
        val title: String = Translations.tr("\u2726 Ability Search \u2726")
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 6, ModConfig.accentColor(), true)

        // Close button
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        val closeHovered = mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2 && mouseY <= closeY + 11
        context.drawText(textRenderer, "\u2715", closeX, closeY, if (closeHovered) 0xFFFF5555.toInt() else 0xFF888888.toInt(), true)

        // Options button
        optBtnX = panelX + panelWidth + 2
        optBtnY = panelTop
        val optHovered = mouseX in optBtnX..(optBtnX + optBtnSize) && mouseY in optBtnY..(optBtnY + optBtnSize)
        val btnBase = if (optHovered) 0xFFA0A0A0.toInt() else 0xFF808080.toInt()
        val btnLight = if (optHovered) 0xFFDDDDDD.toInt() else 0xFFBBBBBB.toInt()
        val btnDark = if (optHovered) 0xFF666666.toInt() else 0xFF444444.toInt()
        context.fill(optBtnX, optBtnY, optBtnX + optBtnSize, optBtnY + optBtnSize, btnBase)
        context.fill(optBtnX, optBtnY, optBtnX + optBtnSize, optBtnY + 1, btnLight)
        context.fill(optBtnX, optBtnY, optBtnX + 1, optBtnY + optBtnSize, btnLight)
        context.fill(optBtnX, optBtnY + optBtnSize - 1, optBtnX + optBtnSize, optBtnY + optBtnSize, btnDark)
        context.fill(optBtnX + optBtnSize - 1, optBtnY, optBtnX + optBtnSize, optBtnY + optBtnSize, btnDark)
        context.drawTexture(OPTIONS_ICON, optBtnX + 4, optBtnY + 4, 0f, 0f, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8)

        // Gold separator
        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, ModConfig.accentColor())
        context.fill(panelX + 6, panelTop + 19, panelX + panelWidth - 6, panelTop + 20, 0xFF442200.toInt())

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
        context.fill(pokemonTabX, tY, pokemonTabX + pokemonTabW, tY + tabH, if (pokTabHovered) 0xFF252525.toInt() else 0xFF1A1A1A.toInt())
        context.drawText(textRenderer, pokemonLabel, pokemonTabX + 4, tY + 2, if (pokTabHovered) 0xFFDDDDDD.toInt() else 0xFF888888.toInt(), true)

        // Moves tab (inactive)
        val movTabHovered = mouseX in movesTabX..(movesTabX + movesTabW) && mouseY in tY..(tY + tabH)
        context.fill(movesTabX, tY, movesTabX + movesTabW, tY + tabH, if (movTabHovered) 0xFF252525.toInt() else 0xFF1A1A1A.toInt())
        context.drawText(textRenderer, movesLabel, movesTabX + 4, tY + 2, if (movTabHovered) 0xFFDDDDDD.toInt() else 0xFF888888.toInt(), true)

        // Abilities tab (active)
        context.fill(abilitiesTabX, tY, abilitiesTabX + abilitiesTabW, tY + tabH, 0xFF2A2200.toInt())
        drawBorder(context, abilitiesTabX, tY, abilitiesTabW, tabH, ModConfig.accentColor())
        context.drawText(textRenderer, abilitiesLabel, abilitiesTabX + 4, tY + 2, ModConfig.accentColor(), true)

        // Items tab (inactive)
        val itemTabHovered = mouseX in itemsTabX..(itemsTabX + itemsTabW) && mouseY in tY..(tY + tabH)
        context.fill(itemsTabX, tY, itemsTabX + itemsTabW, tY + tabH, if (itemTabHovered) 0xFF252525.toInt() else 0xFF1A1A1A.toInt())
        context.drawText(textRenderer, itemsLabel, itemsTabX + 4, tY + 2, if (itemTabHovered) 0xFFDDDDDD.toInt() else 0xFF888888.toInt(), true)

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
        context.fill(langBtnX, langBtnY, langBtnX + langBtnW, langBtnY + langBtnH, if (langHovered) 0xFF252525.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, langBtnX, langBtnY, langBtnW, langBtnH, if (langHovered) 0xFFFFAA00.toInt() else 0xFF555555.toInt())
        context.drawText(textRenderer, langLabel, langBtnX + 4, langBtnY + 2, if (langHovered) 0xFFFFAA00.toInt() else 0xFFAAAAAA.toInt(), true)

        // Results area
        val resultsTop = panelTop + 59
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        context.fill(panelX + 6, resultsTop - 2, panelX + panelWidth - 6, resultsTop - 1, 0xFF333333.toInt())

        context.enableScissor(panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        if (!AbilityData.reverseIndexReady) {
            val loadingText: String = Translations.tr("Loading...")
            context.drawText(textRenderer, loadingText, panelX + 15, resultsTop + 10, 0xFF888888.toInt(), true)
        } else if (filteredAbilities.isEmpty()) {
            val noResult: String = Translations.tr("No ability found")
            context.drawText(textRenderer, noResult, panelX + 15, resultsTop + 10, 0xFF666666.toInt(), true)
        } else {
            val countColX = panelX + panelWidth - 40
            var y = resultsTop + 2 - scrollOffset
            for (ability in filteredAbilities) {
                if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                    val hovered = mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                                  mouseY in y..(y + rowHeight) &&
                                  mouseY >= resultsTop && mouseY <= resultsBottom

                    if (hovered) {
                        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + rowHeight, 0xFF252525.toInt())
                    }

                    // Ability name
                    val displayName = try {
                        Text.translatable(ability.displayName).string
                    } catch (_: Exception) { ability.name }
                    val nameColor = if (hovered) ModConfig.accentColor() else 0xFFFFFFFF.toInt()
                    context.drawText(textRenderer, displayName, panelX + 10, y + 3, nameColor, true)

                    // Pokémon count
                    val count = AbilityData.getSpeciesWithAbility(ability.name).size
                    val countText = "${count}\u2726"
                    context.drawText(textRenderer, countText, countColX, y + 3, 0xFF888888.toInt(), true)
                }
                y += rowHeight
            }
        }

        context.disableScissor()

        // Scrollbar
        val contentHeight = filteredAbilities.size * rowHeight + 4
        sbMaxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        if (contentHeight > resultsAreaHeight && resultsAreaHeight > 0) {
            sbTrackX = panelX + panelWidth - 5
            sbContentTop = resultsTop
            sbContentBottom = resultsBottom
            context.fill(sbTrackX, resultsTop, sbTrackX + 3, resultsBottom, 0xFF1A1A1A.toInt())
            sbThumbHeight = maxOf(15, resultsAreaHeight * resultsAreaHeight / contentHeight)
            val maxScroll = contentHeight - resultsAreaHeight
            sbThumbY = resultsTop + (scrollOffset * (resultsAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, ModConfig.accentColor())
        }

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint: String = Translations.tr("ESC to close  \u2022  Click for details")
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)

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

            // Pokemon tab click
            val tY = panelTop + 20
            if (mouseX >= pokemonTabX && mouseX <= pokemonTabX + pokemonTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(PokemonSearchScreen())
                return true
            }

            // Moves tab click
            if (mouseX >= movesTabX && mouseX <= movesTabX + movesTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(MoveSearchScreen())
                return true
            }

            // Items tab click
            if (mouseX >= itemsTabX && mouseX <= itemsTabX + itemsTabW &&
                mouseY >= tY.toDouble() && mouseY <= (tY + tabH).toDouble()) {
                client?.setScreen(ItemDropSearchScreen())
                return true
            }

            // Scrollbar click
            val contentHeight = filteredAbilities.size * rowHeight + 4
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
            if (AbilityData.reverseIndexReady && filteredAbilities.isNotEmpty()) {
                if (mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                    mouseY >= resultsTop && mouseY <= resultsBottom) {
                    val relativeY = mouseY.toInt() - resultsTop + scrollOffset - 2
                    val index = relativeY / rowHeight
                    if (index in filteredAbilities.indices) {
                        val ability = filteredAbilities[index]
                        client?.setScreen(AbilityDetailScreen(ability, this))
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
            val contentHeight = filteredAbilities.size * rowHeight + 4
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
        val contentHeight = filteredAbilities.size * rowHeight + 4
        val maxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
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
