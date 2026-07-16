package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget

import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import java.net.URI

class PokemonSearchScreen : Screen(Text.literal("Pokemon Search")) {

    companion object {
        var savedScrollOffset = 0
        var savedQuery = ""
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Skip default blur/background - we draw our own
    }

    private lateinit var searchField: TextFieldWidget
    private var filteredList: List<PokemonEntry> = emptyList()
    var allSpeciesSorted: List<Species>? = null
        private set
    private var allEntriesSorted: List<PokemonEntry>? = null
    private var scrollOffset = 0
    private val rowHeightNormal = 16
    private val rowHeightSprite = 20
    private var showSprites = ModConfig.wikiShowSprites
    private val rowHeight: Int get() = if (showSprites) rowHeightSprite else rowHeightNormal

    // History toggle
    private var showingHistory = false

    // Type icons (36x36 PNG, rendered as 10x10)
    private val typeIcons = mapOf(
        "normal" to Identifier.of("hunterboard", "img/types/normal.png"),
        "fire" to Identifier.of("hunterboard", "img/types/fire.png"),
        "water" to Identifier.of("hunterboard", "img/types/water.png"),
        "grass" to Identifier.of("hunterboard", "img/types/grass.png"),
        "electric" to Identifier.of("hunterboard", "img/types/electric.png"),
        "ice" to Identifier.of("hunterboard", "img/types/ice.png"),
        "fighting" to Identifier.of("hunterboard", "img/types/fighting.png"),
        "poison" to Identifier.of("hunterboard", "img/types/poison.png"),
        "ground" to Identifier.of("hunterboard", "img/types/ground.png"),
        "flying" to Identifier.of("hunterboard", "img/types/flying.png"),
        "psychic" to Identifier.of("hunterboard", "img/types/psychic.png"),
        "bug" to Identifier.of("hunterboard", "img/types/bug.png"),
        "rock" to Identifier.of("hunterboard", "img/types/rock.png"),
        "ghost" to Identifier.of("hunterboard", "img/types/ghost.png"),
        "dragon" to Identifier.of("hunterboard", "img/types/dragon.png"),
        "dark" to Identifier.of("hunterboard", "img/types/dark.png"),
        "steel" to Identifier.of("hunterboard", "img/types/steel.png"),
        "fairy" to Identifier.of("hunterboard", "img/types/fairy.png")
    )
    private val typeIconSize = 10

    // Options button
    private val OPTIONS_ICON = Identifier.of("hunterboard", "img/option.png")
    private val optBtnSize = 24
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

    // Donate button bounds
    private var donateBtnX = 0
    private var donateBtnY = 0
    private var donateBtnW = 0
    private val donateBtnH = 16

    // Donors button bounds
    private var donorsBtnX = 0
    private var donorsBtnY = 0
    private var donorsBtnW = 0
    private val donorsBtnH = 16

    // Language toggle button
    private var langBtnX = 0
    private var langBtnY = 0
    private var langBtnW = 0
    private val langBtnH = 12

    // Tab bounds for Pokemon/Moves/Abilities toggle
    private var movesTabX = 0
    private var movesTabW = 0
    private var abilitiesTabX = 0
    private var abilitiesTabW = 0
    private var itemsTabX = 0
    private var itemsTabW = 0
    private val tabH = 12

    // Sort / filter
    private enum class SortMode { POKEDEX, ALPHABETICAL, TYPE }
    private var sortMode: SortMode = SortMode.POKEDEX
    private var showSortDropdown = false
    private var selectedTypeFilter: String? = null
    private var showTypeSubMenu = false
    private var sortBtnX = 0
    private var sortBtnY = 0
    private var sortBtnW = 0
    private val sortBtnH = 12
    private var spriteBtnX = 0
    private var spriteBtnY = 0
    private var spriteBtnW = 0
    private val spriteBtnH = 12
    private val allTypes = listOf(
        "normal", "fire", "water", "grass", "electric", "ice",
        "fighting", "poison", "ground", "flying", "psychic", "bug",
        "rock", "ghost", "dragon", "dark", "steel", "fairy"
    )

    override fun init() {
        super.init()
        SpawnData.ensureLoaded()
        // Pre-warm ability index in background so it's ready when switching to Abilities tab
        if (!AbilityData.reverseIndexReady) {
            Thread { AbilityData.buildReverseIndex() }.also { it.isDaemon = true }.start()
        }
        // Pre-warm drop index
        if (!DropData.reverseIndexReady) {
            Thread { DropData.buildReverseIndex() }.also { it.isDaemon = true }.start()
        }

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

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 37, panelWidth - 20, 16, Text.literal(placeholder))
        searchField.setMaxLength(50)
        searchField.text = savedQuery
        lastQuery = savedQuery
        searchField.setChangedListener { updateSearch(); savedQuery = searchField.text; savedScrollOffset = scrollOffset }
        addDrawableChild(searchField)
        setInitialFocus(searchField)

        refreshList()
        scrollOffset = savedScrollOffset
    }

    private fun refreshList() {
        if (showingHistory) {
            filteredList = SearchHistory.getSpeciesList().map { PokemonEntry.Regular(it) }
        } else {
            updateSearch()
        }
    }

    private var lastQuery = ""

    private fun updateSearch() {
        if (showingHistory) return
        val query = searchField.text.lowercase().trim()
        if (query != lastQuery) {
            scrollOffset = 0
            lastQuery = query
        }

        var list = allEntriesSorted ?: emptyList()

        // Text filter — accent/apostrophe-insensitive ("flabebe" → Flabébé, "farfetch'd" → Farfetch’d)
        if (query.isNotEmpty()) {
            list = list.filter { entry ->
                when (entry) {
                    is PokemonEntry.Regular -> {
                        val displayName = Translations.speciesDisplayName(entry.species)
                        NameUtil.matchesQuery(entry.species.name, query) ||
                        NameUtil.matchesQuery(displayName, query)
                    }
                    is PokemonEntry.Mega -> {
                        val displayName = Translations.speciesDisplayName(entry.species)
                        val mLabel = PokemonEntry.megaLabel(entry.form.name)
                        NameUtil.matchesQuery(entry.species.name, query) ||
                        NameUtil.matchesQuery(displayName, query) ||
                        NameUtil.matchesQuery("$mLabel $displayName", query)
                    }
                }
            }
        }

        // Type filter
        val typeF = selectedTypeFilter
        if (typeF != null) {
            list = list.filter { entry ->
                try {
                    val sp = when (entry) {
                        is PokemonEntry.Regular -> entry.species
                        is PokemonEntry.Mega -> entry.species
                    }
                    val primary = sp.primaryType.name.lowercase()
                    val secondary = sp.secondaryType?.name?.lowercase()
                    primary == typeF || secondary == typeF
                } catch (_: Exception) { false }
            }
        }

        // Sort
        filteredList = when (sortMode) {
            SortMode.POKEDEX -> list
            SortMode.ALPHABETICAL -> list.sortedBy { entry ->
                when (entry) {
                    is PokemonEntry.Regular -> Translations.speciesDisplayName(entry.species).lowercase()
                    is PokemonEntry.Mega -> Translations.speciesDisplayName(entry.species).lowercase()
                }
            }
            SortMode.TYPE -> list
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Hide search bar when showing history
        searchField.visible = !showingHistory

        // Dimmed backdrop
        UiKit.screenDim(context, width, height)

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        // Panel + header band
        UiKit.panel(context, panelX, panelTop, panelWidth, panelHeight)
        val title: String = if (showingHistory) Translations.tr("History") else Translations.tr("Pok\u00e9mon Search")
        UiKit.header(context, textRenderer, panelX, panelTop, panelWidth, title)

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        UiKit.closeButton(context, textRenderer, closeX, closeY, mouseX, mouseY)

        // Options button (attached to panel top-right corner, outside)
        optBtnX = panelX + panelWidth + 2
        optBtnY = panelTop
        val optHovered = mouseX >= optBtnX && mouseX <= optBtnX + optBtnSize &&
                         mouseY >= optBtnY && mouseY <= optBtnY + optBtnSize
        UiKit.button(context, textRenderer, optBtnX, optBtnY, optBtnSize, optBtnSize, "", optHovered)
        context.drawTexture(OPTIONS_ICON, optBtnX + 4, optBtnY + 4, 0f, 0f, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8, optBtnSize - 8)

        // History toggle button (right side of header, before ✕)
        val btnLabel: String = if (showingHistory) Translations.tr("All") else Translations.tr("History")
        val btnW = textRenderer.getWidth(btnLabel) + 8
        val btnX = panelX + panelWidth - btnW - 22
        val btnY = panelTop + 4
        val btnH = 12
        val btnHovered = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH
        UiKit.button(context, textRenderer, btnX, btnY, btnW, btnH, btnLabel, btnHovered, showingHistory)

        // Tabs: Pokémon | Capacités | Talents | Objets
        val pokemonLabel: String = Translations.tr("Pokémon")
        val movesLabel: String = Translations.tr("Moves")
        val abilitiesLabel: String = Translations.tr("Abilities")
        val itemsLabel: String = Translations.tr("Items")
        val pokTabW = textRenderer.getWidth(pokemonLabel) + 8
        movesTabW = textRenderer.getWidth(movesLabel) + 8
        abilitiesTabW = textRenderer.getWidth(abilitiesLabel) + 8
        itemsTabW = textRenderer.getWidth(itemsLabel) + 8
        val pokTabX = panelX + 8
        movesTabX = pokTabX + pokTabW + 2
        abilitiesTabX = movesTabX + movesTabW + 2
        itemsTabX = abilitiesTabX + abilitiesTabW + 2
        val tY = panelTop + 20

        // Pokemon tab (active)
        UiKit.tab(context, textRenderer, pokTabX, tY, pokTabW, tabH, pokemonLabel, true, false)

        // Moves tab (inactive)
        val movTabHovered = mouseX in movesTabX..(movesTabX + movesTabW) && mouseY in tY..(tY + tabH)
        UiKit.tab(context, textRenderer, movesTabX, tY, movesTabW, tabH, movesLabel, false, movTabHovered)

        // Abilities tab (inactive)
        val abiTabHovered = mouseX in abilitiesTabX..(abilitiesTabX + abilitiesTabW) && mouseY in tY..(tY + tabH)
        UiKit.tab(context, textRenderer, abilitiesTabX, tY, abilitiesTabW, tabH, abilitiesLabel, false, abiTabHovered)

        // Items tab (inactive)
        val itemTabHovered = mouseX in itemsTabX..(itemsTabX + itemsTabW) && mouseY in tY..(tY + tabH)
        UiKit.tab(context, textRenderer, itemsTabX, tY, itemsTabW, tabH, itemsLabel, false, itemTabHovered)

        // Language toggle button (right side of tab row)
        val langLabel = Translations.nameLanguageLabel()
        langBtnW = textRenderer.getWidth(langLabel) + 8
        langBtnX = panelX + panelWidth - langBtnW - 8
        langBtnY = tY
        val langHovered = mouseX in langBtnX..(langBtnX + langBtnW) && mouseY in langBtnY..(langBtnY + langBtnH)
        UiKit.button(context, textRenderer, langBtnX, langBtnY, langBtnW, langBtnH, langLabel, langHovered)

        // Sort button (left of language button)
        val sortLabel = when (sortMode) {
            SortMode.POKEDEX -> "#"
            SortMode.ALPHABETICAL -> "A-Z"
            SortMode.TYPE -> selectedTypeFilter?.let { Translations.formatTypeName(it).take(3) } ?: "Type"
        }
        sortBtnW = textRenderer.getWidth(sortLabel) + 8
        sortBtnX = langBtnX - sortBtnW - 4
        sortBtnY = tY
        val sortHovered = mouseX in sortBtnX..(sortBtnX + sortBtnW) && mouseY in sortBtnY..(sortBtnY + sortBtnH)
        UiKit.button(context, textRenderer, sortBtnX, sortBtnY, sortBtnW, sortBtnH, sortLabel, sortHovered, showSortDropdown)

        // Sprite toggle button (left of sort button)
        val spriteLabel = if (showSprites) "\u25A3" else "\u25A2" // filled/empty square icon
        spriteBtnW = textRenderer.getWidth(spriteLabel) + 8
        spriteBtnX = sortBtnX - spriteBtnW - 4
        spriteBtnY = tY
        val spriteHovered = mouseX in spriteBtnX..(spriteBtnX + spriteBtnW) && mouseY in spriteBtnY..(spriteBtnY + spriteBtnH)
        UiKit.button(context, textRenderer, spriteBtnX, spriteBtnY, spriteBtnW, spriteBtnH, spriteLabel, spriteHovered, showSprites)

        // Results area (higher when search bar is hidden in history mode)
        val resultsTop = if (showingHistory) panelTop + 37 else panelTop + 59
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        context.fill(panelX + 6, resultsTop - 2, panelX + panelWidth - 6, resultsTop - 1, UiKit.BORDER_DIM)

        // Scissor for scrollable results
        context.enableScissor(panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        if (filteredList.isEmpty()) {
            val noResult: String = if (showingHistory) Translations.tr("No history yet") else Translations.tr("No Pok\u00e9mon found")
            context.drawText(textRenderer, noResult, panelX + 15, resultsTop + 10, UiKit.TEXT_FAINT, true)
        } else {
            var y = resultsTop + 2 - scrollOffset
            for (entry in filteredList) {
                if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                    val hovered = mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 6 &&
                                  mouseY >= y && mouseY <= y + rowHeight &&
                                  mouseY >= resultsTop && mouseY <= resultsBottom

                    if (hovered) {
                        UiKit.rowHighlight(context, panelX + 6, y, panelWidth - 12, rowHeight)
                    }

                    when (entry) {
                        is PokemonEntry.Regular -> {
                            val spawns = SpawnData.getSpawns(entry.species.name)
                            val rarity = spawns.firstOrNull()?.bucket ?: ""

                            // 2D sprite icon (only when toggle is ON)
                            val spriteId = if (showSprites) SpriteHelper.getSpriteIdentifier(entry.species.name) else null
                            val spriteSize = if (showSprites) 16 else 12
                            val textStartX: Int
                            if (spriteId != null) {
                                val spriteRenderSize = (spriteSize * 1.3).toInt()
                                val spriteOffset = (spriteRenderSize - spriteSize) / 2
                                context.drawTexture(spriteId, panelX + 8 - spriteOffset, y + (rowHeight - spriteSize) / 2 - spriteOffset, 0f, 0f, spriteRenderSize, spriteRenderSize, spriteRenderSize, spriteRenderSize)
                                textStartX = panelX + 8 + spriteSize + 2
                            } else {
                                context.fill(panelX + 10, y + rowHeight / 2 - 2, panelX + 14, y + rowHeight / 2 + 2, getRarityColor(rarity))
                                textStartX = panelX + 18
                            }

                            val textY = y + (rowHeight - 9) / 2
                            val iconY = y + (rowHeight - typeIconSize) / 2
                            val displayName = Translations.speciesDisplayName(entry.species)
                            val nameColor = if (hovered) UiKit.accent() else UiKit.TEXT
                            context.drawText(textRenderer, displayName, textStartX, textY, nameColor, true)

                            // Type icons after name
                            var iconX = textStartX + textRenderer.getWidth(displayName) + 3
                            try {
                                val primary = entry.species.primaryType.name.lowercase()
                                typeIcons[primary]?.let {
                                    context.drawTexture(it, iconX, iconY, 0f, 0f, typeIconSize, typeIconSize, typeIconSize, typeIconSize)
                                    iconX += typeIconSize + 1
                                }
                                val secondary = entry.species.secondaryType?.name?.lowercase()
                                if (secondary != null) {
                                    typeIcons[secondary]?.let {
                                        context.drawTexture(it, iconX, iconY, 0f, 0f, typeIconSize, typeIconSize, typeIconSize, typeIconSize)
                                    }
                                }
                            } catch (_: Exception) {}

                            val dexText = "#${entry.species.nationalPokedexNumber}"
                            val dexWidth = textRenderer.getWidth(dexText)
                            context.drawText(textRenderer, dexText, panelX + panelWidth - 12 - dexWidth, textY, UiKit.TEXT_FAINT, true)
                        }
                        is PokemonEntry.Mega -> {
                            // 2D sprite icon for mega (use base species sprite)
                            val spriteId = if (showSprites) SpriteHelper.getSpriteIdentifier(entry.species.name) else null
                            val spriteSize = if (showSprites) 16 else 12
                            val megaTextStartX: Int
                            if (spriteId != null) {
                                val spriteRenderSize = (spriteSize * 1.3).toInt()
                                val spriteOffset = (spriteRenderSize - spriteSize) / 2
                                context.drawTexture(spriteId, panelX + 8 - spriteOffset, y + (rowHeight - spriteSize) / 2 - spriteOffset, 0f, 0f, spriteRenderSize, spriteRenderSize, spriteRenderSize, spriteRenderSize)
                                megaTextStartX = panelX + 8 + spriteSize + 2
                            } else {
                                // Purple crystal dot marker fallback
                                context.fill(panelX + 10, y + rowHeight / 2 - 2, panelX + 14, y + rowHeight / 2 + 2, 0xFF9966FF.toInt())
                                megaTextStartX = panelX + 18
                            }

                            val megaTextY = y + (rowHeight - 9) / 2
                            val megaIconY = y + (rowHeight - typeIconSize) / 2

                            // "↳" indent prefix
                            val prefix = "\u21b3 "
                            val prefixW = textRenderer.getWidth(prefix)
                            context.drawText(textRenderer, prefix, megaTextStartX, megaTextY, 0xFF665588.toInt(), true)

                            val mLabel = PokemonEntry.megaLabel(entry.form.name)
                            val displayName = "$mLabel ${Translations.speciesDisplayName(entry.species)}"
                            val nameColor = if (hovered) UiKit.accent() else 0xFFCC99FF.toInt()
                            context.drawText(textRenderer, displayName, megaTextStartX + prefixW, megaTextY, nameColor, true)

                            // Type icons after name (use form types if available)
                            var iconX = megaTextStartX + prefixW + textRenderer.getWidth(displayName) + 3
                            try {
                                val form = entry.form
                                val primary = (try { form.primaryType } catch (_: Exception) { null } ?: entry.species.primaryType).name.lowercase()
                                typeIcons[primary]?.let {
                                    context.drawTexture(it, iconX, megaIconY, 0f, 0f, typeIconSize, typeIconSize, typeIconSize, typeIconSize)
                                    iconX += typeIconSize + 1
                                }
                                val secondary = (try { form.secondaryType } catch (_: Exception) { null } ?: entry.species.secondaryType)?.name?.lowercase()
                                if (secondary != null) {
                                    typeIcons[secondary]?.let {
                                        context.drawTexture(it, iconX, megaIconY, 0f, 0f, typeIconSize, typeIconSize, typeIconSize, typeIconSize)
                                    }
                                }
                            } catch (_: Exception) {}

                            val dexText = "#${entry.species.nationalPokedexNumber}"
                            val dexWidth = textRenderer.getWidth(dexText)
                            context.drawText(textRenderer, dexText, panelX + panelWidth - 12 - dexWidth, megaTextY, 0xFF665588.toInt(), true)
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
            UiKit.scrollbar(context, sbTrackX, resultsTop, resultsBottom, contentHeight, scrollOffset)?.let { (ty, th) ->
                sbThumbY = ty
                sbThumbHeight = th
            }
        }

        // Footer
        UiKit.footer(context, textRenderer, panelX, panelBottom, panelWidth,
            Translations.tr("ESC to close  \u2022  Click for details"))

        // Render search field and other widgets
        super.render(context, mouseX, mouseY, delta)

        // Donors button (bottom-left of screen)
        val donorsText = "\u2605 ${Translations.tr("Generous Souls")}"
        donorsBtnW = textRenderer.getWidth(donorsText) + 16
        donorsBtnX = 6 + (width * 15 / 100)
        donorsBtnY = height - donorsBtnH - 6
        val donorsHovered = mouseX >= donorsBtnX && mouseX <= donorsBtnX + donorsBtnW &&
                            mouseY >= donorsBtnY && mouseY <= donorsBtnY + donorsBtnH
        UiKit.goldButton(context, textRenderer, donorsBtnX, donorsBtnY, donorsBtnW, donorsBtnH, donorsText, donorsHovered)

        // Donate button (bottom-right, shifted 15% left)
        val donateText: String = Translations.tr("Donate to _Popichu")
        donateBtnW = textRenderer.getWidth(donateText) + 16
        donateBtnX = width - donateBtnW - 6 - (width * 15 / 100)
        donateBtnY = height - donateBtnH - 6
        val donateHovered = mouseX >= donateBtnX && mouseX <= donateBtnX + donateBtnW &&
                            mouseY >= donateBtnY && mouseY <= donateBtnY + donateBtnH
        UiKit.button(context, textRenderer, donateBtnX, donateBtnY, donateBtnW, donateBtnH, donateText, donateHovered)

        // Version mention
        val modVersion = FabricLoader.getInstance().getModContainer(HunterBoard.MOD_ID)
            .map { it.metadata.version.friendlyString }.orElse("?")
        val versionText = "HunterBoard $modVersion"
        val versionX = (width - textRenderer.getWidth(versionText)) / 2
        context.drawText(textRenderer, versionText, versionX, height - 12, UiKit.TEXT_MUTED, true)

        // Sort dropdown (rendered last to be on top of everything)
        if (showSortDropdown) {
            renderSortDropdown(context, mouseX, mouseY)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            // Sort dropdown / type sub-menu clicks (handle first, before anything else)
            if (showSortDropdown) {
                val handled = handleSortDropdownClick(mouseX, mouseY)
                if (handled) return true
                // Click was outside dropdown → close it
                showSortDropdown = false
                showTypeSubMenu = false
                return true
            }

            val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
            val panelX = (width - panelWidth) / 2
            val panelTop = 25

            // Sort button click
            if (mouseX >= sortBtnX && mouseX <= sortBtnX + sortBtnW &&
                mouseY >= sortBtnY.toDouble() && mouseY <= (sortBtnY + sortBtnH).toDouble()) {
                showSortDropdown = !showSortDropdown
                showTypeSubMenu = false
                return true
            }

            // Sprite toggle button click
            if (mouseX >= spriteBtnX && mouseX <= spriteBtnX + spriteBtnW &&
                mouseY >= spriteBtnY.toDouble() && mouseY <= (spriteBtnY + spriteBtnH).toDouble()) {
                showSprites = !showSprites
                ModConfig.wikiShowSprites = showSprites
                scrollOffset = 0
                return true
            }

            // Close button ✕
            val closeX = panelX + panelWidth - 12
            val closeY = panelTop + 4
            if (mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2.0 && mouseY <= closeY + 11.0) {
                close(); return true
            }

            // Donors button click
            if (mouseX >= donorsBtnX && mouseX <= donorsBtnX + donorsBtnW &&
                mouseY >= donorsBtnY.toDouble() && mouseY <= (donorsBtnY + donorsBtnH).toDouble()) {
                DonorsFetcher.fetchIfNeeded()
                client?.setScreen(DonorsScreen(this))
                return true
            }

            // Donate button click
            if (mouseX >= donateBtnX && mouseX <= donateBtnX + donateBtnW &&
                mouseY >= donateBtnY.toDouble() && mouseY <= (donateBtnY + donateBtnH).toDouble()) {
                try { Util.getOperatingSystem().open(URI("https://www.paypal.com/paypalme/Popiipops")) } catch (_: Exception) {}
                return true
            }

            // Language toggle button click
            if (mouseX >= langBtnX && mouseX <= langBtnX + langBtnW &&
                mouseY >= langBtnY.toDouble() && mouseY <= (langBtnY + langBtnH).toDouble()) {
                Translations.toggleNameLanguage()
                updateSearch()
                return true
            }

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

            // Check scrollbar click
            val contentHeight = filteredList.size * rowHeight + 4
            val resultsTop = if (showingHistory) panelTop + 37 else panelTop + 59
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
                val resultsTop2 = if (showingHistory) panelTop + 37 else panelTop + 59
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
            val resultsTop = if (showingHistory) panelTop + 37 else panelTop + 59
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
        val resultsTop = if (showingHistory) panelTop + 37 else panelTop + 59
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop
        val contentHeight = filteredList.size * rowHeight + 4
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

    private fun getRarityColor(bucket: String): Int {
        return when (bucket.lowercase().trim()) {
            "common" -> 0xFF55CC55.toInt()
            "uncommon" -> 0xFFFFDD55.toInt()
            "rare" -> 0xFF55AAFF.toInt()
            "ultra-rare" -> 0xFFBB66FF.toInt()
            else -> 0xFF888888.toInt()
        }
    }

    private fun handleSortDropdownClick(mouseX: Double, mouseY: Double): Boolean {
        val dropX = sortBtnX
        val dropY = sortBtnY + sortBtnH + 2
        val itemH = 14
        val options = listOf("Pokédex (#)" to SortMode.POKEDEX, "A-Z" to SortMode.ALPHABETICAL, "Type" to SortMode.TYPE)
        val dropW = options.maxOf { textRenderer.getWidth(Translations.tr(it.first)) } + 20
        val dropH = options.size * itemH + 4

        // Type sub-menu click (single column to the right)
        if (showTypeSubMenu) {
            val subItemH = 14
            val subX = dropX + dropW + 2
            val subY = dropY
            val typeMaxW = allTypes.maxOf { textRenderer.getWidth(Translations.formatTypeName(it)) } + typeIconSize + 4
            val allLabelW = textRenderer.getWidth(Translations.tr("All types"))
            val subW = maxOf(typeMaxW, allLabelW) + 12
            val subH = (allTypes.size + 1) * subItemH + 4

            if (mouseX >= subX && mouseX <= subX + subW && mouseY >= subY && mouseY <= subY + subH) {
                val relY = mouseY - subY - 2
                val row = (relY / subItemH).toInt()
                if (row < 0) return true
                if (row == 0) {
                    selectedTypeFilter = null
                    showSortDropdown = false; showTypeSubMenu = false
                    updateSearch(); return true
                }
                val typeIdx = row - 1
                if (typeIdx in allTypes.indices) {
                    selectedTypeFilter = allTypes[typeIdx]
                    sortMode = SortMode.TYPE
                    showSortDropdown = false; showTypeSubMenu = false
                    updateSearch(); return true
                }
                return true
            }
        }

        // Main dropdown click
        if (mouseX >= dropX && mouseX <= dropX + dropW && mouseY >= dropY && mouseY <= dropY + dropH) {
            for ((i, pair) in options.withIndex()) {
                val (_, mode) = pair
                val iy = dropY + 2 + i * itemH
                if (mouseY >= iy && mouseY <= iy + itemH) {
                    if (mode == SortMode.TYPE) {
                        sortMode = SortMode.TYPE
                        showTypeSubMenu = !showTypeSubMenu
                    } else {
                        sortMode = mode
                        selectedTypeFilter = null
                        showSortDropdown = false
                        showTypeSubMenu = false
                        updateSearch()
                    }
                    return true
                }
            }
            return true
        }

        return false
    }

    private fun renderSortDropdown(context: DrawContext, mouseX: Int, mouseY: Int) {
        // Push Z to render on top of everything
        context.matrices.push()
        context.matrices.translate(0f, 0f, 200f)

        val dropX = sortBtnX
        val dropY = sortBtnY + sortBtnH + 2
        val itemH = 14
        val options = listOf("Pokédex (#)" to SortMode.POKEDEX, "A-Z" to SortMode.ALPHABETICAL, "Type" to SortMode.TYPE)
        val dropW = options.maxOf { textRenderer.getWidth(Translations.tr(it.first)) } + 20
        val dropH = options.size * itemH + 4

        // Background + border
        UiKit.card(context, dropX, dropY, dropW, dropH)

        for ((i, pair) in options.withIndex()) {
            val (label, mode) = pair
            val iy = dropY + 2 + i * itemH
            val hovered = mouseX in dropX..(dropX + dropW) && mouseY in iy..(iy + itemH)
            val active = sortMode == mode
            if (hovered) context.fill(dropX + 1, iy, dropX + dropW - 1, iy + itemH, UiKit.SURFACE_HOVER)
            val color = if (active) UiKit.accent() else if (hovered) UiKit.TEXT else UiKit.TEXT_MUTED
            context.drawText(textRenderer, Translations.tr(label), dropX + 6, iy + 3, color, true)
            if (mode == SortMode.TYPE) {
                val arrow = if (showTypeSubMenu) "\u25BE" else "\u25B8"
                context.drawText(textRenderer, arrow, dropX + dropW - 10, iy + 3, color, true)
            }
        }

        // Type sub-menu (single column to the right of the dropdown)
        if (showTypeSubMenu) {
            val subItemH = 14
            val subX = dropX + dropW + 2
            val subY = dropY
            val typeMaxW = allTypes.maxOf { textRenderer.getWidth(Translations.formatTypeName(it)) } + typeIconSize + 4
            val allLabelW = textRenderer.getWidth(Translations.tr("All types"))
            val subW = maxOf(typeMaxW, allLabelW) + 12
            val subH = (allTypes.size + 1) * subItemH + 4

            UiKit.card(context, subX, subY, subW, subH)

            // "All types" option
            val allLabel = Translations.tr("All types")
            val allY = subY + 2
            val allHovered = mouseX in subX..(subX + subW) && mouseY in allY..(allY + subItemH)
            val allActive = selectedTypeFilter == null
            if (allHovered) context.fill(subX + 1, allY, subX + subW - 1, allY + subItemH, UiKit.SURFACE_HOVER)
            context.drawText(textRenderer, allLabel, subX + 6, allY + 3,
                if (allActive) UiKit.accent() else if (allHovered) UiKit.TEXT else UiKit.TEXT_MUTED, true)

            // Type entries
            for ((i, typeName) in allTypes.withIndex()) {
                val ty = subY + 2 + (i + 1) * subItemH
                val hovered = mouseX in subX..(subX + subW) && mouseY in ty..(ty + subItemH)
                val active = selectedTypeFilter == typeName
                if (hovered) context.fill(subX + 1, ty, subX + subW - 1, ty + subItemH, UiKit.SURFACE_HOVER)
                typeIcons[typeName]?.let {
                    context.drawTexture(it, subX + 4, ty + 2, 0f, 0f, typeIconSize, typeIconSize, typeIconSize, typeIconSize)
                }
                val color = if (active) UiKit.accent() else if (hovered) UiKit.TEXT else UiKit.TEXT_MUTED
                context.drawText(textRenderer, Translations.formatTypeName(typeName), subX + 4 + typeIconSize + 4, ty + 3, color, true)
            }
        }

        context.matrices.pop()
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
