package com.hunterboard

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Every habitat pool, grouped by the environment it generates in.
 *
 * The list used to give each habitat two lines of text — its species and phase counts,
 * then its biomes — which read as a wall: the phase count was the same on 50 of the 51
 * pools, and a third of the habitats said "biomes unknown". Now a habitat is a tile with
 * an item and its name, under the heading of its environment ([HabitatGroups]), and the
 * figures wait in a tooltip for the one being pointed at. A search that only matched
 * through a Pokémon names that Pokémon on the tile, so the reason it is listed shows.
 */
class HabitatSearchScreen : ScaledScreen(Text.literal("Habitat Search")) {

    companion object {
        var savedScrollOffset = 0
        var savedQuery = ""

        private const val HEADER_H = 16
        private const val TILE_H = 20
        private const val ROW_GAP = 3
        private const val COL_GAP = 4
        private const val SECTION_GAP = 5
        /** Below this list width the tiles go single file: names would not fit in two. */
        private const val TWO_COLUMNS_MIN = 300
        private const val TOOLTIP_SPECIES = 4
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    /** One tile: the habitat, and the Pokémon the search found in it when that is why it shows. */
    private class Tile(val habitat: HabitatSummary, val matchedSpecies: String?)
    private class Section(val group: HabitatGroups.Group, val tiles: List<Tile>)
    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val tile: Tile)

    private lateinit var searchField: TextFieldWidget
    private var sections: List<Section> = emptyList()
    private var scrollOffset = 0
    private var lastQuery = ""

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

    private val hits = mutableListOf<Hit>()
    /** Tooltip lines per habitat: built once, the commons need a pass over every species. */
    private val tooltipCache = HashMap<String, List<Text>>()

    private fun panelWidth() = (width * 0.55).toInt().coerceIn(260, 450)

    override fun init() {
        super.init()
        HabitatData.ensureLoaded()

        val panelWidth = panelWidth()
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 37, panelWidth - 20, 16, Text.literal(placeholder))
        UiKit.styleField(searchField, hint = placeholder.trimEnd('.', '…') + "…")
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

    /** [HabitatData.revision] the list was built from: the relay's file can arrive while it is open. */
    private var builtFrom = -1

    private fun applyFilter() {
        builtFrom = HabitatData.revision
        tooltipCache.clear()
        val query = searchField.text.trim().lowercase()
        val tiles = HabitatData.allHabitats().mapNotNull { habitat -> match(habitat, query) }
        sections = tiles
            .groupBy { HabitatGroups.groupOf(it.habitat.habitatId) }
            .toSortedMap(compareBy { it.ordinal })
            .map { (group, inGroup) -> Section(group, inGroup.sortedBy { it.habitat.displayName }) }
        if (query != lastQuery) {
            scrollOffset = 0
            lastQuery = query
        }
    }

    /** Null when [habitat] does not answer [query]; the matching Pokémon when only one of its species does. */
    private fun match(habitat: HabitatSummary, query: String): Tile? {
        if (query.isEmpty()) return Tile(habitat, null)
        val id = habitat.habitatId
        if (habitat.displayName.lowercase().contains(query) || id.lowercase().contains(query) ||
            HabitatGroups.groupOf(id).displayName().lowercase().contains(query) ||
            HabitatData.biomesFor(id).any { it.lowercase().contains(query) }) {
            return Tile(habitat, null)
        }
        val species = habitat.speciesKeys.map { Translations.pokemonName(it) }
            .firstOrNull { it.lowercase().contains(query) } ?: return null
        return Tile(habitat, species)
    }

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        hits.clear()
        if (builtFrom != HabitatData.revision) applyFilter()
        UiKit.screenDim(context, width, height)

        val panelWidth = panelWidth()
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

        drawTabs(context, panelX, panelTop + 20, mouseX, mouseY)

        val resultsTop = panelTop + 59
        val resultsBottom = panelBottom - 16
        val listLeft = panelX + 8
        val listWidth = panelWidth - 20            // the scrollbar sits in the last few pixels
        val columns = if (listWidth >= TWO_COLUMNS_MIN) 2 else 1
        val tileWidth = (listWidth - (columns - 1) * COL_GAP) / columns
        val mouseInList = mouseY in resultsTop..resultsBottom

        context.fill(panelX + 6, resultsTop - 2, panelX + panelWidth - 6, resultsTop - 1, UiKit.BORDER_DIM)
        scissor(context, panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        var hovered: Tile? = null
        var y = resultsTop + 3 - scrollOffset
        if (sections.isEmpty()) {
            val fr = Translations.isFrench()
            when {
                !HabitatData.isEmpty() -> context.drawText(textRenderer, Translations.tr("No habitat found"),
                    panelX + 15, resultsTop + 10, UiKit.TEXT_FAINT, true)
                !ModConfig.habitatFeed -> {
                    val off = if (fr) "Les habitats Tropimon sont désactivés dans les options (section Wiki)."
                        else "Tropimon habitats are turned off in the options (Wiki section)."
                    var ty = resultsTop + 10
                    for (line in textRenderer.wrapLines(Text.literal(off), panelWidth - 30)) {
                        context.drawText(textRenderer, line, panelX + 15, ty, UiKit.TEXT_FAINT, true)
                        ty += 11
                    }
                }
                // the relay has no habitat file yet
                else -> {
                    val wait = if (fr) "En attente du Wiki de Tropimon :)" else "Waiting for the Tropimon Wiki :)"
                    val ty = resultsTop + (resultsBottom - resultsTop) / 2 - 4
                    context.drawCenteredTextWithShadow(textRenderer, wait, panelX + panelWidth / 2, ty, UiKit.accentBright())
                }
            }
        }
        for (section in sections) {
            if (y + HEADER_H > resultsTop && y < resultsBottom) {
                drawHeader(context, section, listLeft, y, listWidth)
            }
            y += HEADER_H
            for (row in section.tiles.chunked(columns)) {
                for ((column, tile) in row.withIndex()) {
                    val x = listLeft + column * (tileWidth + COL_GAP)
                    if (y + TILE_H <= resultsTop || y >= resultsBottom) continue
                    val pointed = mouseInList && mouseX in x until x + tileWidth && mouseY in y until y + TILE_H
                    drawTile(context, tile, x, y, tileWidth, pointed)
                    hits.add(Hit(x, y, tileWidth, TILE_H, tile))
                    if (pointed) hovered = tile
                }
                y += TILE_H + ROW_GAP
            }
            y += SECTION_GAP
        }

        context.disableScissor()

        val contentHeight = y + scrollOffset - resultsTop
        sbMaxScroll = (contentHeight - (resultsBottom - resultsTop)).coerceAtLeast(0)
        sbTrackX = panelX + panelWidth - 5
        sbContentTop = resultsTop
        sbContentBottom = resultsBottom
        UiKit.scrollbar(context, sbTrackX, resultsTop, resultsBottom, contentHeight, scrollOffset)

        UiKit.fieldBackground(context, searchField)
        renderWidgets(context, mouseX, mouseY, delta)

        hovered?.let { context.drawTooltip(textRenderer, tooltipFor(it.habitat), mouseX, mouseY) }
    }

    /** "Forests & jungles ········ 8": the environment, a dotted leader, how many habitats. */
    private fun drawHeader(context: DrawContext, section: Section, x: Int, y: Int, w: Int) {
        val title = section.group.displayName()
        val count = section.tiles.size.toString()
        val textY = y + 5
        UiKit.sectionTitle(context, textRenderer, title, x, textY)
        val titleEnd = x + textRenderer.getWidth(title) + if (UiKit.isModern) 11 else 6
        val countX = x + w - textRenderer.getWidth(count)
        if (countX - 6 > titleEnd) UiKit.dots(context, titleEnd, textY + 4, countX - 6 - titleEnd)
        context.drawText(textRenderer, count, countX, textY, UiKit.TEXT_FAINT, true)
    }

    private fun drawTile(context: DrawContext, tile: Tile, x: Int, y: Int, w: Int, hovered: Boolean) {
        UiKit.slot(context, x, y, w, TILE_H)
        if (hovered) UiKit.rowHighlight(context, x, y, w, TILE_H)
        context.drawItem(HabitatGroups.iconOf(tile.habitat.habitatId), x + 2, y + 2)

        val textX = x + 21
        val textY = y + (TILE_H - 8) / 2
        val match = tile.matchedSpecies
        val matchWidth = if (match != null) textRenderer.getWidth(match) + 8 else 0
        val name = fit(tile.habitat.displayName, w - (textX - x) - 4 - matchWidth)
        context.drawText(textRenderer, name, textX, textY, if (hovered) UiKit.accent() else UiKit.TEXT, true)
        if (match != null) {
            context.drawText(textRenderer, match, x + w - 5 - textRenderer.getWidth(match), textY,
                UiKit.accentBright(), true)
        }
    }

    /** [text] cut down to [maxWidth] with an ellipsis, untouched when it fits. */
    private fun fit(text: String, maxWidth: Int): String {
        if (textRenderer.getWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        return textRenderer.trimToWidth(text, (maxWidth - textRenderer.getWidth(ellipsis)).coerceAtLeast(0)).trimEnd() + ellipsis
    }

    /** What the list no longer spells out: environment, counts, biomes, and a few common Pokémon. */
    private fun tooltipFor(habitat: HabitatSummary): List<Text> = tooltipCache.getOrPut(habitat.habitatId) {
        val lines = mutableListOf<Text>()
        lines += Text.literal(habitat.displayName).styled { it.withColor(UiKit.accentBright() and 0xFFFFFF) }

        val counts = buildString {
            append(HabitatGroups.groupOf(habitat.habitatId).displayName())
            append(" · ${habitat.speciesCount} ${Translations.tr("species")}")
            if (habitat.phaseCount > 1) append(" · ${habitat.phaseCount} ${Translations.tr("phases")}")
        }
        lines += Text.literal(counts).styled { it.withColor(0xAAAAAA) }

        val biomes = HabitatData.biomesFor(habitat.habitatId)
        if (biomes.isNotEmpty()) {
            val shown = biomes.take(3).joinToString(", ") + if (biomes.size > 3) " +${biomes.size - 3}" else ""
            lines += Text.literal("${Translations.tr("Generates in:")} $shown").styled { it.withColor(0xAAAAAA) }
        }

        // the commons are most of what comes out of a habitat: name the likeliest
        val commons = HabitatData.speciesIn(habitat.habitatId)
            .filter { it.second.bucket.equals("common", ignoreCase = true) }
            .sortedByDescending { it.second.weight }
            .map { it.first }
            .distinct()
        if (commons.isNotEmpty()) {
            val names = commons.take(TOOLTIP_SPECIES).joinToString(", ") { Translations.pokemonName(it) } +
                if (commons.size > TOOLTIP_SPECIES) "…" else ""
            lines += Text.literal("${Translations.tr("Commons:")} $names").styled { it.withColor(0xDDDDDD) }
        }
        lines
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

    private fun open(habitat: HabitatSummary) {
        savedScrollOffset = scrollOffset
        client?.setScreen(HabitatDetailScreen(habitat.habitatId, this))
    }

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val panelWidth = panelWidth()
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

            if (mouseY >= sbContentTop && mouseY <= sbContentBottom) {
                for (hit in hits) {
                    if (mouseX >= hit.x && mouseX < hit.x + hit.w && mouseY >= hit.y && mouseY < hit.y + hit.h) {
                        open(hit.tile.habitat); return true
                    }
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
        // Enter opens the first habitat the search left, as the other search tabs do
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            sections.firstOrNull()?.tiles?.firstOrNull()?.let { open(it.habitat); return true }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun shouldPause(): Boolean = false
}
