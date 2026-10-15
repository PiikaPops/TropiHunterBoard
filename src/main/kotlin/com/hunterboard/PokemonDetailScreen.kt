package com.hunterboard

import com.cobblemon.mod.common.api.abilities.PotentialAbility
import com.cobblemon.mod.common.api.drop.ItemDropEntry
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.riding.RidingStyle
import com.cobblemon.mod.common.api.riding.stats.RidingStat
import com.cobblemon.mod.common.client.settings.ServerSettings
import com.cobblemon.mod.common.pokemon.PokemonSizeCategory
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class PokemonDetailScreen(
    private val species: Species,
    private val parent: Screen?,
    private val allSpecies: List<Species>? = null,
    private val initialFormName: String? = null
) : ScaledScreen(Text.literal("Pokemon Detail")) {

    private var modelWidget: ModelWidget? = null
    private var scrollOffset = 0
    private var contentHeight = 0

    // Move tabs
    private var selectedTab = 0
    private val tabKeys = arrayOf("Level-Up", "TM", "Egg", "Tutor")
    private val tabBounds = Array(4) { IntArray(4) }

    // Cached move lists
    private var cachedMoves: Array<List<MoveEntry>?> = arrayOfNulls(4)

    // Hover tooltip state
    private var hoveredMoveIndex = -1
    private var hoverStartTime = 0L
    private var tooltipMove: MoveTemplate? = null

    // Ability hover tooltip state
    private var hoveredAbilityIndex = -1
    private var abilityHoverStartTime = 0L
    private var tooltipAbility: PotentialAbility? = null

    // Item drop hover tooltip state
    private var hoveredDropIndex = -1
    private var dropHoverStartTime = 0L
    private var tooltipDropItem: Identifier? = null

    // Cached row bounds for hover detection
    private data class MoveRowBounds(val y: Int, val h: Int, val move: MoveTemplate)
    private var moveRowBounds = mutableListOf<MoveRowBounds>()
    private data class MoveClickBound(val x: Int, val y: Int, val w: Int, val h: Int, val move: MoveTemplate)
    private var moveClickBounds = mutableListOf<MoveClickBound>()
    private data class AbilityRowBounds(val x: Int, val y: Int, val w: Int, val h: Int, val ability: PotentialAbility)
    private var abilityRowBounds = mutableListOf<AbilityRowBounds>()
    private data class DropRowBounds(val x: Int, val y: Int, val w: Int, val h: Int, val itemId: Identifier)
    private var dropRowBounds = mutableListOf<DropRowBounds>()

    // Model click bounds
    private var modelRenderX = 0
    private var modelRenderY = 0

    // Biome hover tooltip
    private var hoveredBiomeDetail: BiomeDetail? = null
    /** Habitat row under the cursor; its biomes are shown as a tooltip. */
    private var hoveredHabitatId: String? = null

    /** Selected riding style tab, for species that can be ridden more than one way. */
    private var ridingStyleTab = 0
    private class RidingTabBound(val x: Int, val y: Int, val w: Int, val h: Int, val index: Int)
    private val ridingTabBounds = mutableListOf<RidingTabBound>()

    // Shiny toggle
    private var isShiny = false
    private var shinyBtnX = 0
    private var shinyBtnY = 0
    private val shinyBtnW = 14
    private val shinyBtnH = 14

    // Cosmetic overlay
    private var availableCosmetics: List<CosmeticEntry> = emptyList()
    private var selectedCosmeticIndex = -1
    private var showCosmeticOverlay = false
    private var cosmeticBtnX = 0
    private var cosmeticBtnY = 0
    private val cosmeticBtnW = 14
    private val cosmeticBtnH = 14

    // Scrollbar drag state
    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0
    private var scrollbarDragStartOffset = 0
    private var sbTrackX = 0
    private var sbContentTop = 0
    private var sbContentBottom = 0
    private var sbThumbY = 0
    private var sbThumbHeight = 0

    // F2: Prev/Next navigation
    private var currentIndex = -1

    // F3: Evolution tree click bounds
    private data class EvoClickBound(val x: Int, val y: Int, val w: Int, val h: Int, val species: Species, val formAspect: String? = null)
    private var evoClickBounds = mutableListOf<EvoClickBound>()

    // Mega evolution click bounds
    private data class MegaClickBound(val x: Int, val y: Int, val w: Int, val h: Int, val form: FormData)
    private var megaClickBounds = mutableListOf<MegaClickBound>()

    // F4: Regional form tabs
    private var availableForms: List<FormData> = emptyList()
    private var currentFormIndex = 0
    private var formTabBounds = mutableListOf<IntArray>()
    private var formTabScrollOffset = 0 // horizontal scroll for form tabs
    private var formTabLeftArrowBounds: IntArray? = null
    private var formTabRightArrowBounds: IntArray? = null

    companion object {
        private const val MODEL_SIZE = 80
        private const val STAT_BAR_MAX = 255
        private const val HOVER_DELAY_MS = 500L

        // Order of attacking types displayed in the type chart (2 rows of 9)
        val ATTACK_TYPE_ORDER = listOf(
            "normal", "fire", "water", "electric", "grass", "ice",
            "fighting", "poison", "ground", "flying", "psychic", "bug",
            "rock", "ghost", "dragon", "dark", "steel", "fairy"
        )

        // Type effectiveness chart: DEF_TYPE_CHART[defendingType][attackingType] = multiplier
        // Only stores non-1.0 values; missing entries default to 1.0
        val DEF_TYPE_CHART: Map<String, Map<String, Float>> = mapOf(
            "normal"   to mapOf("fighting" to 2f, "ghost" to 0f),
            "fire"     to mapOf("fire" to .5f, "water" to 2f, "grass" to .5f, "ice" to .5f, "ground" to 2f, "bug" to .5f, "rock" to 2f, "steel" to .5f, "fairy" to .5f),
            "water"    to mapOf("fire" to .5f, "water" to .5f, "electric" to 2f, "grass" to 2f, "ice" to .5f, "steel" to .5f),
            "electric" to mapOf("electric" to .5f, "ground" to 2f, "flying" to .5f, "steel" to .5f),
            "grass"    to mapOf("fire" to 2f, "water" to .5f, "electric" to .5f, "grass" to .5f, "ice" to 2f, "poison" to 2f, "ground" to .5f, "flying" to 2f, "bug" to 2f),
            "ice"      to mapOf("fire" to 2f, "ice" to .5f, "fighting" to 2f, "rock" to 2f, "steel" to 2f),
            "fighting" to mapOf("rock" to .5f, "bug" to .5f, "flying" to 2f, "psychic" to 2f, "dark" to .5f, "fairy" to 2f),
            "poison"   to mapOf("grass" to .5f, "fighting" to .5f, "poison" to .5f, "ground" to 2f, "bug" to .5f, "psychic" to 2f, "fairy" to .5f),
            "ground"   to mapOf("water" to 2f, "electric" to 0f, "grass" to 2f, "ice" to 2f, "poison" to .5f, "rock" to .5f),
            "flying"   to mapOf("electric" to 2f, "ground" to 0f, "grass" to .5f, "ice" to 2f, "fighting" to .5f, "bug" to .5f, "rock" to 2f),
            "psychic"  to mapOf("fighting" to .5f, "psychic" to .5f, "bug" to 2f, "ghost" to 2f, "dark" to 2f),
            "bug"      to mapOf("fire" to 2f, "grass" to .5f, "fighting" to .5f, "ground" to .5f, "flying" to 2f, "rock" to 2f),
            "rock"     to mapOf("normal" to .5f, "fire" to .5f, "water" to 2f, "grass" to 2f, "fighting" to 2f, "poison" to .5f, "ground" to 2f, "flying" to .5f, "steel" to 2f),
            "ghost"    to mapOf("normal" to 0f, "fighting" to 0f, "poison" to .5f, "bug" to .5f, "ghost" to 2f, "dark" to 2f),
            "dragon"   to mapOf("fire" to .5f, "water" to .5f, "electric" to .5f, "grass" to .5f, "ice" to 2f, "dragon" to 2f, "fairy" to 2f),
            "dark"     to mapOf("fighting" to 2f, "psychic" to 0f, "bug" to 2f, "ghost" to .5f, "dark" to .5f, "fairy" to 2f),
            "steel"    to mapOf("normal" to .5f, "fire" to 2f, "grass" to .5f, "ice" to .5f, "fighting" to 2f, "poison" to 0f, "ground" to 2f, "flying" to .5f, "psychic" to .5f, "bug" to .5f, "rock" to .5f, "dragon" to .5f, "steel" to .5f, "fairy" to .5f),
            "fairy"    to mapOf("fighting" to .5f, "bug" to .5f, "poison" to 2f, "dragon" to 0f, "dark" to .5f, "steel" to 2f)
        )
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        SpawnData.ensureLoaded()
        HabitatData.ensureLoaded()

        // F1: Record history
        SearchHistory.addEntry(species)

        // F2: Compute current index for prev/next
        currentIndex = allSpecies?.indexOfFirst { it.name == species.name } ?: -1

        // Reset form tab scroll
        formTabScrollOffset = 0

        // F4: Build form list (exclude Gmax/Eternamax; include Mega)
        availableForms = try {
            val forms = species.forms.filter { form ->
                try {
                    val name = form.name.lowercase()
                    !name.contains("gmax") && !name.contains("eternamax")
                } catch (_: Exception) { true }
            }
            if (forms.size > 1) forms else emptyList()
        } catch (_: Exception) { emptyList() }

        // Auto-select form when opened from a mega entry in the search list
        if (initialFormName != null && availableForms.isNotEmpty()) {
            val idx = availableForms.indexOfFirst { it.name.equals(initialFormName, ignoreCase = true) }
            if (idx >= 0) currentFormIndex = idx
        }

        // Load cosmetic items for this species
        availableCosmetics = try { CosmeticData.getCosmeticsForSpecies(species.name) } catch (_: Exception) { emptyList() }
        selectedCosmeticIndex = -1
        showCosmeticOverlay = false

        rebuildModel()
    }

    private fun rebuildModel() {
        try {
            val formAspects = if (availableForms.isNotEmpty() && currentFormIndex < availableForms.size) {
                try { availableForms[currentFormIndex].aspects.toSet() } catch (_: Exception) { emptySet() }
            } else emptySet()
            var aspects = if (isShiny) formAspects + "shiny" else formAspects
            if (selectedCosmeticIndex in availableCosmetics.indices) {
                aspects = aspects + availableCosmetics[selectedCosmeticIndex].aspectName
            }
            val pokemon = RenderablePokemon(species, aspects)
            modelWidget = ModelWidget(
                pX = 0, pY = 0,
                pWidth = MODEL_SIZE, pHeight = MODEL_SIZE,
                pokemon = pokemon,
                baseScale = 2.6f,
                rotationY = 325f,
                offsetY = -15.0,
                playCryOnClick = true
            )
        } catch (_: Exception) {}
    }

    // F4: Get the currently selected form (null = base/standard)
    private fun currentForm(): FormData? {
        return if (availableForms.isNotEmpty() && currentFormIndex < availableForms.size) {
            availableForms[currentFormIndex]
        } else null
    }

    // F4: Get effective spawns for current form
    /**
     * The spawns of the form on screen, or the species' own when it has none.
     *
     * Every aspect is tried, not just the first: Paldean Tauros declares `paldean` **and**
     * `aqua-breed`, and only the second tells the three breeds apart — taking `first()`
     * showed all of them. The smallest non-empty answer is the most specific one, so that
     * is the one kept.
     */
    private fun getEffectiveSpawns(): List<SpawnEntry> {
        val form = currentForm()
        if (form != null) {
            val aspects = try { form.aspects } catch (_: Exception) { emptyList() }
            var best: List<SpawnEntry>? = null
            for (aspect in aspects) {
                val found = SpawnData.getSpawns(species.name, aspect)
                if (found.isNotEmpty() && (best == null || found.size < best!!.size)) best = found
            }
            best?.let { return it }
        }
        return SpawnData.getSpawns(species.name)
    }

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        hoveredBiomeDetail = null
        hoveredHabitatId = null
        ridingTabBounds.clear()
        evoClickBounds.clear()
        megaClickBounds.clear()
        UiKit.screenDim(context, width, height)

        // The table needs more room than the text layout
        val panelWidth = if (ModConfig.spawnDisplayMode == "table")
            (width * 0.96).toInt().coerceIn(520, 1120)
        else (width * 0.8).toInt().coerceIn(380, 620)
        val panelX = (width - panelWidth) / 2
        val panelTop = 10
        val panelBottom = height - 10
        val panelHeight = panelBottom - panelTop

        UiKit.panel(context, panelX, panelTop, panelWidth, panelHeight)

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        UiKit.closeButton(context, textRenderer, closeX, closeY, mouseX, mouseY)

        // ========== HEADER ==========
        val backText: String = Translations.tr("\u2190 Back")
        val backHovered = mouseX >= panelX + 6 && mouseX <= panelX + 6 + textRenderer.getWidth(backText) + 4 &&
                          mouseY >= panelTop + 5 && mouseY <= panelTop + 17
        context.drawText(textRenderer, backText, panelX + 8, panelTop + 6,
            if (backHovered) UiKit.accent() else UiKit.TEXT_MUTED, true)

        val displayName: String = try { Translations.dualSpeciesName(species.name) } catch (_: Exception) { species.name.replaceFirstChar { it.uppercase() } }
        context.drawText(textRenderer, displayName,
            panelX + (panelWidth - textRenderer.getWidth(displayName)) / 2, panelTop + 6, UiKit.TEXT, true)

        // F2: Prev/Next buttons around dex number
        val dexText = "#${String.format("%04d", species.nationalPokedexNumber)}"
        val dexW = textRenderer.getWidth(dexText)
        val hasPrev = allSpecies != null && currentIndex > 0
        val hasNext = allSpecies != null && currentIndex >= 0 && currentIndex < (allSpecies?.size ?: 0) - 1
        val prevText = "\u25C0"
        val nextText = "\u25B6"
        val navW = textRenderer.getWidth(prevText)
        val navRightEdge = panelX + panelWidth - 22

        // Layout: [◀] #0001 [▶] right-aligned
        val nextBtnX = navRightEdge - navW
        val dexTextX = nextBtnX - dexW - 4
        val prevBtnX = dexTextX - navW - 4
        val navY = panelTop + 6

        val prevHovered = hasPrev && mouseX >= prevBtnX && mouseX <= prevBtnX + navW && mouseY >= navY && mouseY <= navY + 10
        val nextHovered = hasNext && mouseX >= nextBtnX && mouseX <= nextBtnX + navW && mouseY >= navY && mouseY <= navY + 10
        context.drawText(textRenderer, prevText, prevBtnX, navY,
            if (prevHovered) UiKit.accent() else if (hasPrev) UiKit.TEXT else UiKit.BORDER_DIM, true)
        context.drawText(textRenderer, dexText, dexTextX, navY, UiKit.TEXT, true)
        context.drawText(textRenderer, nextText, nextBtnX, navY,
            if (nextHovered) UiKit.accent() else if (hasNext) UiKit.TEXT else UiKit.BORDER_DIM, true)

        context.fillGradient(panelX + 1, panelTop + 1, panelX + panelWidth - 1, panelTop + 17,
            UiKit.withAlpha(UiKit.accent(), 0x28), UiKit.withAlpha(UiKit.accent(), 0x00))
        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, UiKit.withAlpha(UiKit.accent(), 0xCC))
        context.fill(panelX + 6, panelTop + 19, panelX + panelWidth - 6, panelTop + 20, UiKit.withAlpha(UiKit.accent(), 0x33))

        // ========== F4: FORM TABS (if multiple forms, with horizontal scroll) ==========
        var formTabsEndY = panelTop + 22
        formTabBounds.clear()
        formTabLeftArrowBounds = null
        formTabRightArrowBounds = null
        if (availableForms.size > 1) {
            val formTabY = panelTop + 22
            val arrowW = 12
            val tabAreaLeft = panelX + 8
            val tabAreaRight = panelX + panelWidth - 8

            // Compute total width of all tabs
            val tabLabels = availableForms.map { form ->
                val formName = try { form.name } catch (_: Exception) { "?" }
                when {
                    formName.isBlank() || formName.equals("Standard", ignoreCase = true) -> Translations.tr("Standard")
                    formName.lowercase().startsWith("mega") -> PokemonEntry.megaLabel(formName)
                    else -> Translations.tr(formName)
                }
            }
            val tabWidths = tabLabels.map { textRenderer.getWidth(it) + 10 }
            val totalTabsWidth = tabWidths.sum() + (availableForms.size - 1) * 2

            val needsScroll = totalTabsWidth > (tabAreaRight - tabAreaLeft)
            val visibleLeft = if (needsScroll) tabAreaLeft + arrowW + 2 else tabAreaLeft
            val visibleRight = if (needsScroll) tabAreaRight - arrowW - 2 else tabAreaRight
            val visibleWidth = visibleRight - visibleLeft

            // Clamp scroll offset
            val maxFormScroll = if (needsScroll) maxOf(0, totalTabsWidth - visibleWidth) else 0
            formTabScrollOffset = formTabScrollOffset.coerceIn(0, maxFormScroll)

            // Draw left arrow
            if (needsScroll) {
                val canScrollLeft = formTabScrollOffset > 0
                val leftArrowX = tabAreaLeft
                val lHov = canScrollLeft && mouseX >= leftArrowX && mouseX <= leftArrowX + arrowW &&
                           mouseY >= formTabY && mouseY <= formTabY + 13
                val lColor = when {
                    lHov -> UiKit.accent()
                    canScrollLeft -> UiKit.TEXT_MUTED
                    else -> UiKit.BORDER_DIM
                }
                context.drawText(textRenderer, "\u25C0", leftArrowX + 2, formTabY + 3, lColor, true)
                formTabLeftArrowBounds = intArrayOf(leftArrowX, formTabY, arrowW, 13)
            }

            // Draw tabs with scissor to clip overflow
            scissor(context, visibleLeft, formTabY, visibleRight, formTabY + 13)
            var ftx = visibleLeft - formTabScrollOffset
            for ((i, form) in availableForms.withIndex()) {
                val label = tabLabels[i]
                val tw = tabWidths[i]
                val isSelected = i == currentFormIndex
                val isHov = mouseX >= maxOf(ftx, visibleLeft) && mouseX <= minOf(ftx + tw, visibleRight) &&
                            mouseY >= formTabY && mouseY <= formTabY + 13

                val bg = when {
                    isSelected -> UiKit.withAlpha(UiKit.accent(), 0x40)
                    isHov -> UiKit.SURFACE_HOVER
                    else -> UiKit.SURFACE
                }
                context.fill(ftx, formTabY, ftx + tw, formTabY + 13, bg)
                val bc = if (isSelected) UiKit.accent() else UiKit.BORDER
                context.fill(ftx, formTabY, ftx + tw, formTabY + 1, bc)
                context.fill(ftx, formTabY, ftx + 1, formTabY + 13, bc)
                context.fill(ftx + tw - 1, formTabY, ftx + tw, formTabY + 13, bc)

                val tc = when {
                    isSelected -> UiKit.accent()
                    isHov -> UiKit.TEXT
                    else -> UiKit.TEXT_MUTED
                }
                context.drawText(textRenderer, label, ftx + 5, formTabY + 3, tc, true)
                formTabBounds.add(intArrayOf(ftx, formTabY, tw, 13))
                ftx += tw + 2
            }
            context.disableScissor()

            // Draw right arrow
            if (needsScroll) {
                val canScrollRight = formTabScrollOffset < maxFormScroll
                val rightArrowX = tabAreaRight - arrowW
                val rHov = canScrollRight && mouseX >= rightArrowX && mouseX <= rightArrowX + arrowW &&
                           mouseY >= formTabY && mouseY <= formTabY + 13
                val rColor = when {
                    rHov -> UiKit.accent()
                    canScrollRight -> UiKit.TEXT_MUTED
                    else -> UiKit.BORDER_DIM
                }
                context.drawText(textRenderer, "\u25B6", rightArrowX + 2, formTabY + 3, rColor, true)
                formTabRightArrowBounds = intArrayOf(rightArrowX, formTabY, arrowW, 13)
            }

            formTabsEndY = panelTop + 22 + 16
        }

        // Scrollable content
        val contentTop = formTabsEndY
        val contentBottom = panelBottom - 16
        val contentAreaHeight = contentBottom - contentTop

        scissor(context, panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)

        var y = contentTop + 2 - scrollOffset
        val leftX = panelX + 8
        val halfW = (panelWidth - 20) / 2
        val midX = panelX + 10 + halfW + 4

        moveRowBounds.clear()
        moveClickBounds.clear()
        abilityRowBounds.clear()
        dropRowBounds.clear()

        // ========== ROW 1: Model + Info (left) | Base Stats (right) ==========

        // --- LEFT: Model + Types + Abilities + EV Yield ---
        if (modelWidget != null) {
            try {
                modelRenderX = leftX
                modelRenderY = y - 4
                modelWidget!!.x = modelRenderX
                modelWidget!!.y = modelRenderY
                val now = java.time.LocalDate.now()
                val isAprilFools = now.monthValue == 4 && now.dayOfMonth == 1
                if (isAprilFools) {
                    val centerY = (modelRenderY + MODEL_SIZE / 2).toFloat()
                    context.matrices.push()
                    context.matrices.translate(0f, centerY, 0f)
                    context.matrices.scale(1f, -1f, 1f)
                    context.matrices.translate(0f, -centerY, 0f)
                    RenderSystem.disableCull()
                    modelWidget!!.render(context, mouseX, mouseY, delta)
                    RenderSystem.enableCull()
                    context.matrices.pop()
                } else {
                    modelWidget!!.render(context, mouseX, mouseY, delta)
                }
            } catch (_: Exception) {}
        }

        // Shiny + Cosmetic toggle buttons (centered under model)
        val hasCosmetics = availableCosmetics.isNotEmpty()
        val totalBtnW = if (hasCosmetics) shinyBtnW + 2 + cosmeticBtnW else shinyBtnW
        shinyBtnX = leftX + (MODEL_SIZE - totalBtnW) / 2
        shinyBtnY = y - 4 + MODEL_SIZE + 2
        val shinyHovered = mouseX >= shinyBtnX && mouseX <= shinyBtnX + shinyBtnW &&
                           mouseY >= shinyBtnY && mouseY <= shinyBtnY + shinyBtnH
        val shinyBg = when {
            isShiny && shinyHovered -> UiKit.withAlpha(UiKit.accent(), 0x58)
            isShiny -> UiKit.withAlpha(UiKit.accent(), 0x40)
            shinyHovered -> UiKit.SURFACE_HOVER
            else -> UiKit.SURFACE
        }
        context.fill(shinyBtnX, shinyBtnY, shinyBtnX + shinyBtnW, shinyBtnY + shinyBtnH, shinyBg)
        val shinyBorder = if (isShiny) UiKit.accent() else if (shinyHovered) UiKit.TEXT_FAINT else UiKit.BORDER
        drawBorder(context, shinyBtnX, shinyBtnY, shinyBtnW, shinyBtnH, shinyBorder)
        val starColor = if (isShiny) 0xFFFFDD00.toInt() else UiKit.TEXT_MUTED
        val star = "\u2726"
        val starW = textRenderer.getWidth(star)
        context.drawText(textRenderer, star, shinyBtnX + (shinyBtnW - starW) / 2, shinyBtnY + 3, starColor, true)

        // Cosmetic button (only if cosmetics available)
        if (hasCosmetics) {
            cosmeticBtnX = shinyBtnX + shinyBtnW + 2
            cosmeticBtnY = shinyBtnY
            val cosmeticActive = selectedCosmeticIndex >= 0
            val cosmeticHovered = mouseX >= cosmeticBtnX && mouseX <= cosmeticBtnX + cosmeticBtnW &&
                                  mouseY >= cosmeticBtnY && mouseY <= cosmeticBtnY + cosmeticBtnH
            val cosmeticBg = when {
                cosmeticActive && cosmeticHovered -> UiKit.withAlpha(UiKit.accent(), 0x58)
                cosmeticActive -> UiKit.withAlpha(UiKit.accent(), 0x40)
                cosmeticHovered -> UiKit.SURFACE_HOVER
                else -> UiKit.SURFACE
            }
            context.fill(cosmeticBtnX, cosmeticBtnY, cosmeticBtnX + cosmeticBtnW, cosmeticBtnY + cosmeticBtnH, cosmeticBg)
            val cosmeticBorder = if (cosmeticActive) UiKit.accent() else if (cosmeticHovered) UiKit.TEXT_FAINT else UiKit.BORDER
            drawBorder(context, cosmeticBtnX, cosmeticBtnY, cosmeticBtnW, cosmeticBtnH, cosmeticBorder)
            val hatIcon = "\u2666"
            val hatColor = if (cosmeticActive) 0xFFFFDD00.toInt() else UiKit.TEXT_MUTED
            val hatW = textRenderer.getWidth(hatIcon)
            context.drawText(textRenderer, hatIcon, cosmeticBtnX + (cosmeticBtnW - hatW) / 2, cosmeticBtnY + 3, hatColor, true)
        }

        // Cosmetic dropdown overlay (rendered later, after all content, before tooltips)

        val infoX = leftX + MODEL_SIZE + 6
        var leftY = y + 4

        // Types (F4: use form overrides if available)
        try {
            val form = currentForm()
            val primary = try { form?.primaryType ?: species.primaryType } catch (_: Exception) { species.primaryType }
            val secondary = try { form?.secondaryType ?: species.secondaryType } catch (_: Exception) { species.secondaryType }
            var typeX = infoX
            drawTypeBadge(context, typeX, leftY, primary.displayName.string)
            typeX += textRenderer.getWidth(primary.displayName.string) + 12
            if (secondary != null) {
                drawTypeBadge(context, typeX, leftY, secondary.displayName.string)
            }
        } catch (_: Exception) {}
        leftY += 16

        // Abilities (F4: use form overrides if available)
        val abilitiesLabel: String = Translations.tr("Abilities")
        context.drawText(textRenderer, abilitiesLabel, infoX, leftY, UiKit.accent(), true)
        leftY += 11
        try {
            val abilities = try {
                val form = currentForm()
                val formAbilities = form?.abilities
                if (formAbilities != null && formAbilities.any()) formAbilities else species.abilities
            } catch (_: Exception) { species.abilities }

            for (ability in abilities) {
                val pa = ability as? PotentialAbility ?: continue
                val isHidden = pa is HiddenAbility
                val abilityName: String = Text.translatable(pa.template.displayName).string
                val abilityHovered = abilityRowBounds.size < 20 && hoveredAbilityIndex >= 0 &&
                    abilityRowBounds.size == hoveredAbilityIndex
                val baseColor = if (isHidden) 0xFFFFDD55.toInt() else UiKit.TEXT_MUTED
                val color = if (abilityHovered) UiKit.accent() else baseColor
                context.drawText(textRenderer, abilityName, infoX + 4, leftY, color, true)
                var totalW = textRenderer.getWidth(abilityName)
                if (isHidden) {
                    val tagX = infoX + 4 + totalW + 4
                    context.drawText(textRenderer, "(H)", tagX, leftY, 0xFFAA8833.toInt(), true)
                    totalW += 4 + textRenderer.getWidth("(H)")
                }
                if (abilityHovered) {
                    context.fill(infoX + 4, leftY + 9, infoX + 4 + textRenderer.getWidth(abilityName), leftY + 10, UiKit.accent())
                }
                abilityRowBounds.add(AbilityRowBounds(infoX + 4, leftY, totalW, 10, pa))
                leftY += 10
            }
        } catch (_: Exception) {}
        leftY += 4

        // EV Yield
        val evLabel: String = Translations.tr("EV Yield")
        context.drawText(textRenderer, evLabel, infoX, leftY, UiKit.accent(), true)
        leftY += 11
        try {
            val evParts = mutableListOf<String>()
            val evYield = species.evYield
            val statOrder = listOf(
                Stats.HP to Translations.tr("HP"),
                Stats.ATTACK to Translations.tr("Attack"),
                Stats.DEFENCE to Translations.tr("Defence"),
                Stats.SPECIAL_ATTACK to Translations.tr("SpAtk"),
                Stats.SPECIAL_DEFENCE to Translations.tr("SpDef"),
                Stats.SPEED to Translations.tr("Speed")
            )
            for ((stat, label) in statOrder) {
                val value = evYield[stat]
                if (value != null && value > 0) evParts.add("$label $value")
            }
            if (evParts.isEmpty()) {
                val localEv = EvYieldData.getEvYield(species.name)
                if (localEv != null) {
                    val keyToLabel = listOf(
                        "hp" to Translations.tr("HP"), "attack" to Translations.tr("Attack"),
                        "defence" to Translations.tr("Defence"), "special_attack" to Translations.tr("SpAtk"),
                        "special_defence" to Translations.tr("SpDef"), "speed" to Translations.tr("Speed")
                    )
                    for ((key, label) in keyToLabel) {
                        val value = localEv[key] ?: 0
                        if (value > 0) evParts.add("$label $value")
                    }
                }
            }
            val noneText: String = Translations.tr("None")
            context.drawText(textRenderer, if (evParts.isEmpty()) noneText else evParts.joinToString(", "),
                infoX + 4, leftY, UiKit.TEXT_MUTED, true)
        } catch (_: Exception) {}
        leftY += 12

        // Egg Groups
        val eggGroupLabel: String = Translations.tr("Egg Groups")
        context.drawText(textRenderer, eggGroupLabel, infoX, leftY, UiKit.accent(), true)
        leftY += 11
        try {
            val rawGroups = EggGroupData.getEggGroups(species.name)
                ?: species.eggGroups.map { it.name }
            val eggGroupText = if (rawGroups.isEmpty()) Translations.tr("None")
                else rawGroups.joinToString(", ") { Translations.formatEggGroup(it) }
            context.drawText(textRenderer, eggGroupText, infoX + 4, leftY, UiKit.TEXT_MUTED, true)
        } catch (_: Exception) {
            context.drawText(textRenderer, "?", infoX + 4, leftY, UiKit.TEXT_FAINT, true)
        }
        leftY += 12

        // Body weight
        try {
            val kg = species.weight / 10f
            val weightLabel: String = Translations.tr("Body Weight:")
            context.drawText(textRenderer, "$weightLabel ${"%.1f".format(kg)} kg",
                infoX + 4, leftY, UiKit.TEXT_MUTED, true)
            leftY += 10
        } catch (_: Exception) {}

        // Alpha (Cobblemon 1.8): the "boss" spawn bucket is entirely Alpha herds
        if (try { SpawnData.hasAlphaSpawn(species.name) } catch (_: Exception) { false }) {
            context.drawText(textRenderer, "\u2726 ${Translations.tr("Alpha boss herd")}",
                infoX + 4, leftY, 0xFFFF5544.toInt(), true)
            leftY += 12
        }

        leftY = renderSizeCategories(context, infoX, leftY)

        val leftEndY = maxOf(leftY, y + MODEL_SIZE + 6)

        // --- RIGHT: Base Stats (F4: use form overrides if available) ---
        val baseStatsLabel: String = Translations.tr("Base Stats")
        context.drawText(textRenderer, baseStatsLabel, midX, y, UiKit.accent(), true)
        var statsY = y + 13

        val statEntries = listOf(
            Stats.HP to Translations.tr("HP"),
            Stats.ATTACK to Translations.tr("Attack"),
            Stats.DEFENCE to Translations.tr("Defence"),
            Stats.SPECIAL_ATTACK to Translations.tr("SpAtk"),
            Stats.SPECIAL_DEFENCE to Translations.tr("SpDef"),
            Stats.SPEED to Translations.tr("Speed")
        )
        val labelWidth = 42
        val barX = midX + labelWidth
        val barMaxW = halfW - labelWidth - 30

        val effectiveStats = try {
            val form = currentForm()
            val formStats = form?.baseStats
            if (formStats != null && formStats.isNotEmpty()) formStats else species.baseStats
        } catch (_: Exception) { species.baseStats }

        for ((stat, label) in statEntries) {
            val value = effectiveStats[stat] ?: 0
            context.drawText(textRenderer, label, midX, statsY, UiKit.TEXT_MUTED, true)
            context.fill(barX, statsY + 1, barX + barMaxW, statsY + 8, UiKit.SURFACE)
            val fillW = (value * barMaxW / STAT_BAR_MAX).coerceIn(1, barMaxW)
            context.fill(barX, statsY + 1, barX + fillW, statsY + 8, getStatColor(value))
            context.drawText(textRenderer, "$value", barX + barMaxW + 4, statsY, UiKit.TEXT, true)
            statsY += 12
        }

        val total = statEntries.sumOf { effectiveStats[it.first] ?: 0 }
        val totalLabel: String = Translations.tr("Total")
        context.drawText(textRenderer, totalLabel, midX, statsY, UiKit.TEXT_MUTED, true)
        context.drawText(textRenderer, "$total", barX + barMaxW + 4, statsY, UiKit.TEXT, true)
        statsY += 12

        // Riding stats, directly under the base stats
        statsY = renderRidingRadar(context, midX, statsY + 4, halfW, mouseX, mouseY)

        y = maxOf(leftEndY, statsY) + 4

        // ========== SEPARATOR ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 6

        // ========== TYPE CHART (full width) ==========
        val chartLabel: String = Translations.tr("Type Chart")
        context.drawText(textRenderer, chartLabel, leftX, y, UiKit.accent(), true)
        y += 13
        try {
            val chartForm = currentForm()
            val chartPrimary = try { chartForm?.primaryType ?: species.primaryType } catch (_: Exception) { species.primaryType }
            val chartSecondary = try { chartForm?.secondaryType ?: species.secondaryType } catch (_: Exception) { null }
            y = renderTypeChart(context, y, leftX, panelWidth - 20, chartPrimary.name.lowercase(), chartSecondary?.name?.lowercase())
        } catch (_: Exception) {}
        y += 4

        // ========== SEPARATOR ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 6

        // ========== EVOLUTION (left) | DROPPED ITEMS (right) ==========
        val currentFormName = currentForm()?.name?.lowercase()
        val evoTree = try { EvolutionData.getEvolutionTree(species, currentFormName) } catch (_: Exception) { null }
        val hasEvo = evoTree != null
        val megaForms = try {
            species.forms.filter { form ->
                try { form.name.lowercase().contains("mega") } catch (_: Exception) { false }
            }
        } catch (_: Exception) { emptyList() }
        val hasMega = megaForms.isNotEmpty()

        if (hasEvo || hasMega) {
            val row2StartY = y
            var evoEndY = y

            // Evolution chain
            if (hasEvo) {
                val evoLabel: String = Translations.tr("Evolution")
                context.drawText(textRenderer, evoLabel, leftX, evoEndY, UiKit.accent(), true)
                evoEndY += 13
                try {
                    evoEndY = renderEvolutionChain(context, evoTree!!, leftX + 4, evoEndY, halfW - 12, mouseX, mouseY, contentTop, contentBottom)
                } catch (_: Exception) {}
            }

            // Mega evolutions section
            if (hasMega) {
                if (hasEvo) evoEndY += 4
                val megaHeader: String = Translations.tr("Mega Evolutions")
                context.drawText(textRenderer, megaHeader, leftX + 4, evoEndY, UiKit.accent(), true)
                evoEndY += 12
                for (form in megaForms) {
                    try {
                        val mLabel = PokemonEntry.megaLabel(form.name)
                        val fullName = "$mLabel ${species.translatedName.string}"
                        val nameW = textRenderer.getWidth(fullName)
                        val inView = evoEndY >= contentTop && evoEndY <= contentBottom
                        val isHov = inView && mouseX >= leftX + 8 && mouseX <= leftX + 8 + nameW &&
                                    mouseY >= evoEndY && mouseY <= evoEndY + 10
                        val color = if (isHov) 0xFFFFDD55.toInt() else 0xFFCC99FF.toInt()
                        context.drawText(textRenderer, fullName, leftX + 8, evoEndY, color, true)
                        val ulColor = if (isHov) 0xFFFFDD55.toInt() else 0xFF664499.toInt()
                        context.fill(leftX + 8, evoEndY + 9, leftX + 8 + nameW, evoEndY + 10, ulColor)
                        megaClickBounds.add(MegaClickBound(leftX + 8, evoEndY, nameW, 10, form))
                    } catch (_: Exception) {}
                    evoEndY += 12
                }
            }

            // Dropped Items on the right
            val dropsLabel: String = Translations.tr("Dropped Items")
            context.drawText(textRenderer, dropsLabel, midX, row2StartY, UiKit.accent(), true)
            val dropY = renderDroppedItems(context, midX, row2StartY + 13, halfW, mouseX, mouseY)

            // Vertical separator
            context.fill(panelX + 10 + halfW, row2StartY, panelX + 10 + halfW + 1,
                maxOf(evoEndY, dropY), UiKit.BORDER_DIM)

            y = maxOf(evoEndY, dropY) + 4
        } else {
            // No evolution, no mega — Dropped Items takes full width
            val dropsLabel: String = Translations.tr("Dropped Items")
            context.drawText(textRenderer, dropsLabel, leftX, y, UiKit.accent(), true)
            y = renderDroppedItems(context, leftX, y + 13, panelWidth - 20, mouseX, mouseY) + 4
        }

        // Separator
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 6

        // ========== SPAWN CONDITIONS (full width) ==========
        val spawnLabel: String = Translations.tr("Spawn Conditions")
        context.drawText(textRenderer, spawnLabel, leftX, y, UiKit.accent(), true)
        var spawnY = y + 13
        val fullContentW = panelWidth - 20

        val spawns = try { getEffectiveSpawns() } catch (_: Exception) { emptyList() }
        if (spawns.isNotEmpty()) {
            val lvMin = spawns.minOf { it.lvMin }
            val lvMax = spawns.maxOf { it.lvMax }
            val lvPrefix: String = Translations.tr("Lv.")
            context.drawText(textRenderer, "$lvPrefix $lvMin-$lvMax", leftX + 4, spawnY, UiKit.TEXT_MUTED, true)
            spawnY += 13

            if (ModConfig.spawnDisplayMode == "table") {
                spawnY = renderSpawnTable(context, spawns, spawnY, panelX, panelWidth, leftX,
                    mouseX, mouseY, contentTop, contentBottom)
            } else {

            val seen = mutableSetOf<String>()
            for (spawn in spawns) {
                val key = SpawnData.dedupKey(spawn)
                if (key in seen) continue
                seen.add(key)

                // Rarity tag + weight
                val rarityText: String = Translations.formatRarity(spawn.bucket)
                val rarityColor = getRarityColor(spawn.bucket)
                val weightStr = if (spawn.weight != null) " (${Translations.tr("Weight:")} ${String.format("%.1f", spawn.weight)})" else ""
                val variantStr = if (spawn.formLabel.isNotEmpty()) " • ${spawn.formLabel}" else ""
                val rarityTagText = "[$rarityText$variantStr]$weightStr "
                context.drawText(textRenderer, rarityTagText, leftX + 8, spawnY, rarityColor, true)
                var rarityTagW = textRenderer.getWidth(rarityTagText)
                if (spawn.isFiller) {
                    val fillerBadge = Translations.tr("Filler") + " "
                    context.drawText(textRenderer, fillerBadge, leftX + 8 + rarityTagW, spawnY, COLOR_FILLER, true)
                    rarityTagW += textRenderer.getWidth(fillerBadge)
                }

                // Presets + Context tag (on same line as rarity if non-default)
                val tags = mutableListOf<String>()
                val meaningfulPresets = spawn.presets.filter { it != "natural" }
                if (meaningfulPresets.isNotEmpty()) {
                    tags.addAll(meaningfulPresets.map { Translations.formatPreset(it) })
                }
                val ctxStr = Translations.formatSpawnContext(spawn.spawnContext)
                if (ctxStr.isNotEmpty()) tags.add(ctxStr)
                if (tags.isNotEmpty()) {
                    val tagText = tags.joinToString(", ")
                    val tagX = leftX + 8 + rarityTagW
                    context.drawText(textRenderer, tagText, tagX, spawnY, 0xFFAA8855.toInt(), true)
                    spawnY += 10
                    // Biomes on next line
                    if (spawn.biomeDetails.isEmpty()) {
                        val anyText: String = Translations.tr("Any")
                        context.drawText(textRenderer, anyText, leftX + 12, spawnY, UiKit.TEXT_FAINT, true)
                        spawnY += 10
                    } else {
                        spawnY = renderBiomesInline(context, spawn.biomeDetails, leftX + 12, spawnY,
                            fullContentW - 12, mouseX, mouseY, contentTop, contentBottom)
                    }
                } else {
                    if (spawn.biomeDetails.isEmpty()) {
                        val anyText: String = Translations.tr("Any")
                        context.drawText(textRenderer, anyText, leftX + 8 + rarityTagW, spawnY, UiKit.TEXT_FAINT, true)
                        spawnY += 10
                    } else {
                        spawnY = renderBiomesInline(context, spawn.biomeDetails, leftX + 8 + rarityTagW, spawnY,
                            fullContentW - 8 - rarityTagW, mouseX, mouseY, contentTop, contentBottom)
                    }
                }

                // Excluded biomes
                if (spawn.excludedBiomes.isNotEmpty()) {
                    val exclLabel: String = Translations.tr("Excluded:")
                    val exclNames = spawn.excludedBiomes.joinToString(", ") { Translations.biomeName(it) }
                    context.drawText(textRenderer, "  \u2716 $exclLabel $exclNames", leftX + 8, spawnY, 0xFFAA5555.toInt(), true)
                    spawnY += 10
                }

                if (spawn.structures.isNotEmpty()) {
                    val structText = "\u2302 " + spawn.structures.joinToString(", ")
                    context.drawText(textRenderer, structText, leftX + 8, spawnY, 0xFFCC8844.toInt(), true)
                    spawnY += 10
                }

                // Time | Weather | Sky
                val timeStr: String = Translations.formatTime(spawn.time)
                val weatherStr: String = Translations.formatWeather(spawn.weather)
                val skyStr: String = Translations.formatSky(spawn.canSeeSky)
                context.drawText(textRenderer, "  $timeStr | $weatherStr$skyStr", leftX + 8, spawnY, 0xFF707070.toInt(), true)
                spawnY += 10

                // Height, Light, Moon (combined line)
                val extras = mutableListOf<String>()
                if (spawn.minY != null || spawn.maxY != null) {
                    val yMin = spawn.minY?.toString() ?: "?"
                    val yMax = spawn.maxY?.toString() ?: "?"
                    val heightLabel: String = Translations.tr("Height:")
                    extras.add("$heightLabel $yMin \u2192 $yMax")
                }
                if (spawn.minSkyLight != null || spawn.maxSkyLight != null) {
                    val lMin = spawn.minSkyLight?.toString() ?: "0"
                    val lMax = spawn.maxSkyLight?.toString() ?: "15"
                    val skyLightLabel: String = Translations.tr("SkyLight:")
                    extras.add("$skyLightLabel $lMin-$lMax")
                } else if (spawn.minLight != null || spawn.maxLight != null) {
                    val lMin = spawn.minLight?.toString() ?: "0"
                    val lMax = spawn.maxLight?.toString() ?: "15"
                    val lightLabel: String = Translations.tr("Light:")
                    extras.add("$lightLabel $lMin-$lMax")
                }
                SpawnData.moonPhaseLabel(spawn)?.let { phases ->
                    val moonLabel: String = Translations.tr("Moon:")
                    extras.add("$moonLabel $phases")
                }
                if (extras.isNotEmpty()) {
                    context.drawText(textRenderer, "  ${extras.joinToString(" | ")}", leftX + 8, spawnY, 0xFF887755.toInt(), true)
                    spawnY += 10
                }

                // Block requirements (combined line)
                val blockParts = mutableListOf<String>()
                if (spawn.neededBaseBlocks.isNotEmpty()) {
                    val baseLabel: String = Translations.tr("Base:")
                    val names = spawn.neededBaseBlocks.map { Translations.blockName(it) }
                    blockParts.add("$baseLabel ${names.joinToString(", ")}")
                }
                if (spawn.neededNearbyBlocks.isNotEmpty()) {
                    val nearLabel: String = Translations.tr("Nearby:")
                    val names = spawn.neededNearbyBlocks.map { Translations.blockName(it) }
                    blockParts.add("$nearLabel ${names.joinToString(", ")}")
                }
                if (blockParts.isNotEmpty()) {
                    context.drawText(textRenderer, "  ${blockParts.joinToString(" | ")}", leftX + 8, spawnY, 0xFF887755.toInt(), true)
                    spawnY += 10
                }

                // Weight multipliers
                if (spawn.weightMultipliers.isNotEmpty()) {
                    for (wm in spawn.weightMultipliers) {
                        val multStr = if (wm.multiplier == wm.multiplier.toInt().toFloat())
                            "\u00d7${wm.multiplier.toInt()}" else "\u00d7${String.format("%.1f", wm.multiplier)}"
                        val condLabel = Translations.formatMultiplierCondition(wm.conditionLabel)
                        context.drawText(textRenderer, "  $multStr $condLabel", leftX + 8, spawnY, 0xFF55BBFF.toInt(), true)
                        spawnY += 10
                    }
                }

                // Cobblemon 1.8 extras (Poke Snack, fishing, depth, herd, held items…)
                for (line in SpawnData.extraLines(spawn)) {
                    context.drawText(textRenderer, line.text, leftX + 8, spawnY, line.color, true)
                    spawnY += 10
                }

                if (spawn.requiresSlimeChunk) {
                    val scLabel: String = Translations.tr("Slime Chunk")
                    context.drawText(textRenderer, "  ★ $scLabel", leftX + 8, spawnY, 0xFF55FF55.toInt(), true)
                    spawnY += 10
                }

                val displayPresets = spawn.presets.filter { it != "natural" }
                if (displayPresets.isNotEmpty()) {
                    val presetLabel: String = Translations.tr("Preset:")
                    val presetNames = displayPresets.joinToString(", ") { Translations.formatPreset(it) }
                    context.drawText(textRenderer, "  $presetLabel $presetNames", leftX + 8, spawnY, 0xFFAA8855.toInt(), true)
                    spawnY += 10
                }

                spawnY += 2
            }
            }
        } else {
            val noSpawnText: String = Translations.tr("No spawn data")
            context.drawText(textRenderer, noSpawnText, leftX + 4, spawnY, UiKit.TEXT_FAINT, true)
            spawnY += 12
        }

        y = spawnY + 4

        // ========== HABITATS (own section: a habitat pool is not a world spawn) ==========
        val habitatEntries = try { HabitatData.getHabitats(species.name) } catch (_: Exception) { emptyList() }
        if (habitatEntries.isNotEmpty()) {
            context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
            y += 8

            context.drawText(textRenderer, Translations.tr("Habitats"), leftX, y, UiKit.accent(), true)
            y += 13
            y = if (ModConfig.spawnDisplayMode == "table") {
                renderHabitatTable(context, habitatEntries, y, panelX, panelWidth, leftX,
                    mouseX, mouseY, contentTop, contentBottom)
            } else {
                renderHabitatText(context, y, leftX, mouseX, mouseY)
            }
            y += 4
        }

        // ========== SEPARATOR ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 8

        // ========== MOVE TABS ==========
        val tabY = y
        var tabX = leftX
        for (i in tabKeys.indices) {
            val label: String = Translations.tr(tabKeys[i])
            val tw = textRenderer.getWidth(label) + 12
            val isSelected = i == selectedTab
            val isTabHovered = mouseX >= tabX && mouseX <= tabX + tw &&
                               mouseY >= tabY && mouseY <= tabY + 14

            val bg = when {
                isSelected -> UiKit.withAlpha(UiKit.accent(), 0x40)
                isTabHovered -> UiKit.SURFACE_HOVER
                else -> UiKit.SURFACE
            }
            context.fill(tabX, tabY, tabX + tw, tabY + 14, bg)

            val borderColor = if (isSelected) UiKit.accent() else UiKit.BORDER
            context.fill(tabX, tabY, tabX + tw, tabY + 1, borderColor)
            context.fill(tabX, tabY, tabX + 1, tabY + 14, borderColor)
            context.fill(tabX + tw - 1, tabY, tabX + tw, tabY + 14, borderColor)
            if (isSelected) {
                context.fill(tabX + 1, tabY + 13, tabX + tw - 1, tabY + 14, 0xF0101010.toInt())
            }

            val textColor = when {
                isSelected -> UiKit.accent()
                isTabHovered -> UiKit.TEXT
                else -> UiKit.TEXT_MUTED
            }
            context.drawText(textRenderer, label, tabX + 6, tabY + 3, textColor, true)

            tabBounds[i] = intArrayOf(tabX, tabY, tw, 14)
            tabX += tw + 2
        }

        y = tabY + 14
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER)
        y += 6

        // ========== ACTIVE MOVE LIST ==========
        val moves = cachedMoves[selectedTab] ?: run {
            val computed = when (selectedTab) {
                0 -> getLevelUpMoves()
                1 -> getTmMoves()
                2 -> getEggMoves()
                3 -> getTutorMoves()
                else -> emptyList()
            }
            cachedMoves[selectedTab] = computed
            computed
        }
        val showLevel = selectedTab == 0
        val isTmTab = selectedTab == 1

        // Cobblemon 1.8 made TMs native: say how many of these moves have a real TM item
        if (isTmTab && moves.isNotEmpty()) {
            TmData.ensureLoaded()
            val craftable = moves.count { TmData.forMove(it.move.name) != null }
            if (craftable > 0) {
                val note = "\u25C6 $craftable/${moves.size} ${Translations.tr("with a craftable TM")}"
                context.drawText(textRenderer, note, leftX, y, 0xFF66CCEE.toInt(), true)
                y += 11
            }
        }

        if (moves.isEmpty()) {
            val emptyText: String = Translations.noMovesText(tabKeys[selectedTab])
            context.drawText(textRenderer, emptyText,
                panelX + (panelWidth - textRenderer.getWidth(emptyText)) / 2, y + 6, UiKit.TEXT_FAINT, true)
            y += 22
        } else {
            y = renderMoveTable(context, moves, y, panelX, panelWidth, leftX, showLevel, mouseX, mouseY, contentTop, contentBottom, isTmTab)
        }

        contentHeight = y + scrollOffset - contentTop + 8

        context.disableScissor()

        // Cosmetic dropdown overlay (rendered on top of everything with Z-offset)
        if (showCosmeticOverlay && availableCosmetics.isNotEmpty()) {
            val overlayX = cosmeticBtnX - 20
            val rowH = 18
            val overlayItemCount = availableCosmetics.size + 1 // +1 for "None"
            val overlayH = overlayItemCount * rowH + 6 // 3px padding top + bottom
            val overlayW = availableCosmetics.maxOf { textRenderer.getWidth(it.displayName) + 24 }.coerceAtLeast(80)
            val overlayY = cosmeticBtnY + cosmeticBtnH + 2

            context.matrices.push()
            context.matrices.translate(0f, 0f, 200f)

            // Opaque background
            context.fill(overlayX, overlayY, overlayX + overlayW, overlayY + overlayH, 0xFF111111.toInt())
            drawBorder(context, overlayX, overlayY, overlayW, overlayH, UiKit.accent())

            var oy = overlayY + 3
            // "None" option
            val noneHovered = mouseX >= overlayX && mouseX <= overlayX + overlayW && mouseY >= oy && mouseY < oy + rowH
            if (noneHovered) context.fill(overlayX + 1, oy, overlayX + overlayW - 1, oy + rowH, UiKit.BORDER_DIM)
            val noneText = Translations.tr("None")
            val noneColor = if (selectedCosmeticIndex == -1) UiKit.accent() else UiKit.TEXT_MUTED
            context.drawText(textRenderer, noneText, overlayX + 6, oy + 5, noneColor, true)
            oy += rowH

            // Cosmetic items
            for ((i, cosmetic) in availableCosmetics.withIndex()) {
                val itemHovered = mouseX >= overlayX && mouseX <= overlayX + overlayW && mouseY >= oy && mouseY < oy + rowH
                if (itemHovered) context.fill(overlayX + 1, oy, overlayX + overlayW - 1, oy + rowH, UiKit.BORDER_DIM)
                if (cosmetic.itemStack != null) {
                    context.drawItem(cosmetic.itemStack, overlayX + 4, oy + 1)
                }
                val nameColor = if (selectedCosmeticIndex == i) UiKit.accent() else UiKit.TEXT_MUTED
                context.drawText(textRenderer, cosmetic.displayName, overlayX + 22, oy + 5, nameColor, true)
                oy += rowH
            }

            context.matrices.pop()
        }

        // Tooltips (after scissor)
        if (tooltipMove != null) {
            val now = System.currentTimeMillis()
            if (now - hoverStartTime >= HOVER_DELAY_MS) {
                try {
                    val desc: String = tooltipMove!!.description.string
                    if (desc.isNotEmpty()) {
                        val lines = mutableListOf<Text>()
                        lines.add(Text.literal(tooltipMove!!.displayName.string).styled { it.withBold(true) })
                        for (wLine in wrapText(desc, 200)) { lines.add(Text.literal(wLine)) }
                        context.drawTooltip(textRenderer, lines, mouseX, mouseY)
                    }
                } catch (_: Exception) {}
            }
        }
        if (tooltipAbility != null) {
            val now = System.currentTimeMillis()
            if (now - abilityHoverStartTime >= HOVER_DELAY_MS) {
                try {
                    val tmpl = tooltipAbility!!.template
                    val abilityName: String = Text.translatable(tmpl.displayName).string
                    val desc: String = Text.translatable(tmpl.description).string
                    if (desc.isNotEmpty()) {
                        val lines = mutableListOf<Text>()
                        lines.add(Text.literal(abilityName).styled { it.withBold(true) })
                        for (wLine in wrapText(desc, 200)) { lines.add(Text.literal(wLine)) }
                        context.drawTooltip(textRenderer, lines, mouseX, mouseY)
                    }
                } catch (_: Exception) {}
            }
        }
        // Item drop tooltip
        if (tooltipDropItem != null) {
            val now = System.currentTimeMillis()
            if (now - dropHoverStartTime >= HOVER_DELAY_MS) {
                try {
                    val item = Registries.ITEM.get(tooltipDropItem!!)
                    val stack = ItemStack(item)
                    val tooltipLines = stack.getTooltip(Item.TooltipContext.DEFAULT, client?.player, net.minecraft.item.tooltip.TooltipType.Default.BASIC)
                    if (tooltipLines.size > 1) {
                        val lines = mutableListOf<Text>()
                        lines.add(Text.literal(stack.name.string).styled { it.withBold(true) })
                        for (i in 1 until tooltipLines.size) {
                            lines.add(tooltipLines[i])
                        }
                        context.drawTooltip(textRenderer, lines, mouseX, mouseY)
                    }
                } catch (_: Exception) {}
            }
        }

        // Biome tooltip is rendered after footer for proper Z-ordering

        // Scrollbar
        sbTrackX = panelX + panelWidth - 5
        sbContentTop = contentTop
        sbContentBottom = contentBottom
        if (contentHeight > contentAreaHeight && contentAreaHeight > 0) {
            context.fill(sbTrackX, contentTop, sbTrackX + 3, contentBottom, UiKit.SURFACE)
            sbThumbHeight = maxOf(15, contentAreaHeight * contentAreaHeight / contentHeight)
            val maxScroll = contentHeight - contentAreaHeight
            sbThumbY = contentTop + (scrollOffset * (contentAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, UiKit.accent())
        } else {
            sbThumbHeight = 0
        }

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, UiKit.SURFACE_SUNKEN)
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, UiKit.BORDER_DIM)
        val hint: String = Translations.tr("ESC / Click Back to return")
        context.drawText(textRenderer, hint,
            panelX + (panelWidth - textRenderer.getWidth(hint)) / 2, panelBottom - 10, UiKit.TEXT_FAINT, true)

        hoveredHabitatId?.let { habitatId ->
            // Lifted above the panel like the biome tooltip below: without the Z offset
            // it interleaves with the interface it is supposed to cover.
            context.matrices.push()
            context.matrices.translate(0f, 0f, 400f)
            // Same panel as a spawn-condition biome tag: grouped by mod, multi-column.
            val grouped = HabitatData.biomeDetailsFor(habitatId)
                .mapNotNull { it.tagId }
                .flatMap { BiomeTagResolver.resolveGrouped(it) }
            val title = BiomeDetail(HabitatData.displayNameOf(habitatId), null)
            if (grouped.isNotEmpty()) {
                renderBiomeTooltip(context, grouped, title, mouseX, mouseY)
            } else {
                context.drawTooltip(
                    textRenderer,
                    listOf(
                        Text.literal(HabitatData.displayNameOf(habitatId)),
                        Text.literal("  " + Translations.tr("Placed as a worldgen feature"))
                    ),
                    mouseX, mouseY
                )
            }
            context.matrices.pop()
        }

        // Biome tooltip (rendered last, on top of everything)
        if (hoveredBiomeDetail != null && hoveredBiomeDetail!!.tagId != null) {
            val resolved = BiomeTagResolver.resolveGrouped(hoveredBiomeDetail!!.tagId!!)
            if (resolved.isNotEmpty()) {
                context.matrices.push()
                context.matrices.translate(0f, 0f, 400f)
                renderBiomeTooltip(context, resolved, hoveredBiomeDetail!!, mouseX, mouseY)
                context.matrices.pop()
            }
        }

        updateHoverState(mouseX, mouseY, panelX, panelWidth, contentTop, contentBottom)
    }

    // ==================== Dropped Items Rendering ====================

    private fun renderDroppedItems(context: DrawContext, startX: Int, startY: Int, maxWidth: Int, mouseX: Int, mouseY: Int): Int {
        var dropY = startY
        try {
            val entries = species.drops.entries
            if (entries.isEmpty()) {
                val noneText: String = Translations.tr("None")
                context.drawText(textRenderer, noneText, startX + 4, dropY, UiKit.TEXT_FAINT, true)
                dropY += 12
            } else {
                for (entry in entries) {
                    if (entry is ItemDropEntry) {
                        val item = Registries.ITEM.get(entry.item)
                        val stack = ItemStack(item)
                        if (!stack.isEmpty) {
                            context.drawItem(stack, startX + 4, dropY - 4)
                        }
                        val textStartX = startX + 24
                        val itemNameStr = item.name.string
                        val nameW = textRenderer.getWidth(itemNameStr)
                        val hovered = mouseX >= textStartX && mouseX <= textStartX + nameW &&
                                      mouseY >= dropY && mouseY < dropY + 10
                        val nameColor = if (hovered) UiKit.accent() else UiKit.TEXT_MUTED
                        context.drawText(textRenderer, "$itemNameStr ${entry.percentage.toInt()}%",
                            textStartX, dropY, nameColor, true)
                        if (hovered) {
                            context.fill(textStartX, dropY + 9, textStartX + nameW, dropY + 10, UiKit.accent())
                        }
                        dropRowBounds.add(DropRowBounds(textStartX, dropY, nameW, 10, entry.item))
                        val qty = entry.quantityRange
                        val qtyText = if (qty != null && qty.first != qty.last) "${qty.first}-${qty.last}" else "${entry.quantity}"
                        val qtyLabel: String = Translations.tr("Qty:")
                        context.drawText(textRenderer, "$qtyLabel $qtyText",
                            textStartX, dropY + 10, UiKit.TEXT_FAINT, true)
                        dropY += 22
                    }
                }
            }
        } catch (_: Exception) {
            val naText: String = Translations.tr("N/A")
            context.drawText(textRenderer, naText, startX + 4, dropY, UiKit.TEXT_FAINT, true)
            dropY += 12
        }
        return dropY
    }

    // ==================== F3: Evolution Chain Rendering ====================

    private fun renderEvolutionChain(
        context: DrawContext, tree: EvoNode,
        startX: Int, startY: Int, maxWidth: Int,
        mouseX: Int, mouseY: Int, visibleTop: Int, visibleBottom: Int
    ): Int {
        // Flatten the tree into stages
        val chain = mutableListOf<List<EvoNode>>()
        flattenTree(tree, chain, 0)

        var y = startY

        for ((stageIdx, stage) in chain.withIndex()) {
            if (stage.size == 1) {
                val node = stage[0]
                // Arrow + condition line before this stage (except first)
                if (stageIdx > 0) {
                    val condText = Translations.formatEvoCondition(node.condition)
                    val arrowLine = if (condText.isNotEmpty()) "  \u2193 $condText" else "  \u2193"
                    context.drawText(textRenderer, arrowLine, startX, y, UiKit.TEXT_MUTED, true)
                    y += 10
                }
                renderEvoName(context, node, startX, y, mouseX, mouseY, visibleTop, visibleBottom)
                y += 12
            } else {
                // Multiple evolutions at this stage — vertical list
                for ((branchIdx, node) in stage.withIndex()) {
                    val isLast = branchIdx == stage.lastIndex
                    val prefix = if (isLast) "\u2514 " else "\u251C "
                    context.drawText(textRenderer, prefix, startX + 2, y, UiKit.TEXT_FAINT, true)
                    val nameX = startX + 2 + textRenderer.getWidth(prefix)
                    val endX = renderEvoName(context, node, nameX, y, mouseX, mouseY, visibleTop, visibleBottom)

                    val condText = Translations.formatEvoCondition(node.condition)
                    if (condText.isNotEmpty()) {
                        context.drawText(textRenderer, " \u00b7 $condText", endX, y, UiKit.TEXT_MUTED, true)
                    }
                    y += 12
                }
            }
        }

        return y
    }

    private fun renderEvoName(
        context: DrawContext, node: EvoNode,
        x: Int, y: Int, mouseX: Int, mouseY: Int,
        visibleTop: Int, visibleBottom: Int
    ): Int {
        val baseName: String = node.species?.translatedName?.string ?: node.speciesName.replaceFirstChar { it.uppercase() }
        val name: String = if (node.formAspect != null) {
            val aspectLabel = node.formAspect.replaceFirstChar { it.uppercase() }
            "$baseName ($aspectLabel)"
        } else baseName
        val nameW = textRenderer.getWidth(name)
        val hovered = node.species != null && !node.isCurrent &&
                mouseX >= x && mouseX <= x + nameW &&
                mouseY >= y && mouseY <= y + 10 &&
                mouseY >= visibleTop && mouseY <= visibleBottom

        val color = when {
            node.isCurrent -> UiKit.accent()
            hovered -> 0xFFFFDD55.toInt()
            else -> UiKit.TEXT_MUTED
        }
        context.drawText(textRenderer, name, x, y, color, true)

        // Underline for clickable names
        if (node.species != null && !node.isCurrent) {
            val ulColor = if (hovered) 0xFFFFDD55.toInt() else UiKit.TEXT_FAINT
            context.fill(x, y + 9, x + nameW, y + 10, ulColor)
            evoClickBounds.add(EvoClickBound(x, y, nameW, 10, node.species, node.formAspect))
        }

        return x + nameW
    }

    private fun flattenTree(node: EvoNode, stages: MutableList<List<EvoNode>>, depth: Int) {
        // Ensure stages list is large enough
        while (stages.size <= depth) stages.add(emptyList())
        stages[depth] = stages[depth] + node

        if (node.children.size == 1) {
            flattenTree(node.children[0], stages, depth + 1)
        } else if (node.children.size > 1) {
            // All children go into the next stage as a branch
            for (child in node.children) {
                while (stages.size <= depth + 1) stages.add(emptyList())
                stages[depth + 1] = stages[depth + 1] + child
                // If a branch child has its own evolutions, add them further
                if (child.children.isNotEmpty()) {
                    for (grandchild in child.children) {
                        flattenTree(grandchild, stages, depth + 2)
                    }
                }
            }
        }
    }

    // ==================== Habitats ====================

    /** The original line-per-habitat layout, kept for the "text" display mode. */
    private fun renderHabitatText(context: DrawContext, startY: Int, leftX: Int,
                                  mouseX: Int, mouseY: Int): Int {
        var y = startY
        for (line in HabitatData.habitatLines(species.name)) {
            context.drawText(textRenderer, line.text, leftX + 8, y, line.color, true)
            if (line.habitatId != null &&
                mouseX >= leftX + 8 && mouseX <= leftX + 8 + textRenderer.getWidth(line.text) &&
                mouseY >= y && mouseY < y + 10) {
                hoveredHabitatId = line.habitatId
            }
            y += 10
        }
        return y
    }

    /**
     * Habitats as a table, matching the spawn table.
     *
     * The biome column goes through [renderBiomesInline] like the spawn conditions do,
     * so hovering a tag expands it into the biomes it covers instead of showing a flat
     * pre-resolved list.
     */
    private fun renderHabitatTable(
        context: DrawContext,
        entries: List<HabitatEntry>,
        startY: Int,
        panelX: Int,
        panelWidth: Int,
        leftX: Int,
        mouseX: Int,
        mouseY: Int,
        visibleTop: Int,
        visibleBottom: Int
    ): Int {
        val gap = 6
        val lineH = 10
        val contentW = panelWidth - 24

        val wName = 150
        val wPhases = 74
        val wLv = 56
        val wRarity = 74
        val wTime = 70
        val fixed = wName + wPhases + wLv + wRarity + wTime
        val wBiomes = (contentW - fixed - gap * 5).coerceAtLeast(110)

        var x = leftX
        val colName = x; x += wName + gap
        val colBiomes = x; x += wBiomes + gap
        val colPhases = x; x += wPhases + gap
        val colLv = x; x += wLv + gap
        val colRarity = x; x += wRarity + gap
        val colTime = x
        val tableRight = colTime + wTime

        var y = startY
        val header = UiKit.TEXT_FAINT
        context.drawText(textRenderer, Translations.tr("Habitat"), colName, y, header, true)
        context.drawText(textRenderer, Translations.tr("Biomes"), colBiomes, y, header, true)
        context.drawText(textRenderer, Translations.tr("Phases"), colPhases, y, header, true)
        context.drawText(textRenderer, Translations.tr("Lv."), colLv, y, header, true)
        context.drawText(textRenderer, Translations.tr("Rarity"), colRarity, y, header, true)
        context.drawText(textRenderer, Translations.tr("Time"), colTime, y, header, true)
        y += 11
        context.fill(leftX, y, tableRight, y + 1, UiKit.BORDER_DIM)
        y += 4

        for (entry in entries.sortedBy { HabitatData.habitatDisplayName(it) }) {
            var rowBottom = y + lineH

            fun cell(text: String, cx: Int, width: Int, color: Int): Int {
                var cy = y
                for (line in wrapText(text, width)) {
                    context.drawText(textRenderer, line, cx, cy, color, true)
                    cy += lineH
                }
                return cy
            }

            // clickable habitat name
            val name = HabitatData.habitatDisplayName(entry)
            val nameHovered = mouseX >= colName && mouseX <= colName + wName &&
                mouseY >= y && mouseY < y + lineH &&
                mouseY >= visibleTop && mouseY <= visibleBottom
            if (nameHovered) hoveredHabitatId = entry.habitatId
            rowBottom = maxOf(rowBottom, cell(name, colName, wName,
                if (nameHovered) UiKit.accent() else 0xFF88BBAA.toInt()))

            // biomes, hoverable exactly like a spawn condition
            val biomeDetails = HabitatData.biomeDetailsFor(entry.habitatId)
            rowBottom = maxOf(rowBottom, if (biomeDetails.isEmpty()) {
                cell(Translations.tr("Placed as a worldgen feature"), colBiomes, wBiomes, UiKit.TEXT_FAINT)
            } else {
                renderBiomesInline(context, biomeDetails, colBiomes, y, wBiomes,
                    mouseX, mouseY, visibleTop, visibleBottom)
            })

            val phases = if (entry.phases.isEmpty()) "—"
                         else if (entry.phases.size == 1) "${entry.phases.first()}"
                         else "${entry.phases.first()}-${entry.phases.last()}"
            rowBottom = maxOf(rowBottom, cell(phases, colPhases, wPhases, UiKit.TEXT_MUTED))
            rowBottom = maxOf(rowBottom, cell("${entry.lvMin}-${entry.lvMax}", colLv, wLv, UiKit.TEXT_MUTED))
            rowBottom = maxOf(rowBottom, cell(Translations.formatRarity(entry.bucket), colRarity, wRarity,
                getRarityColor(entry.bucket)))
            rowBottom = maxOf(rowBottom, cell(
                if (entry.time == "any") "—" else Translations.formatTime(entry.time),
                colTime, wTime, UiKit.TEXT_MUTED))

            context.fill(leftX, rowBottom + 1, tableRight, rowBottom + 2, UiKit.BORDER)
            y = rowBottom + 5
        }

        return y
    }

    // ==================== Spawn table ====================

    /**
     * One spawn condition per row, every column spelled out.
     *
     * Nothing is truncated: each cell wraps inside its column and the row grows to the
     * tallest one, so the table carries the same information as the text layout rather
     * than a summary of it. Biome names stay individually hoverable, which is how you
     * expand a tag like "Jungle" into the biomes it actually covers.
     */
    /** Tropimon filler spawns: distinct from every rarity colour so the badge reads as a badge. */
    private val COLOR_FILLER = 0xFFC08AE8.toInt()

    private fun renderSpawnTable(
        context: DrawContext,
        spawns: List<SpawnEntry>,
        startY: Int,
        panelX: Int,
        panelWidth: Int,
        leftX: Int,
        mouseX: Int,
        mouseY: Int,
        visibleTop: Int,
        visibleBottom: Int
    ): Int {
        val gap = 6
        val lineH = 10
        val contentW = panelWidth - 24

        // Fixed columns, with the two biome columns sharing whatever is left.
        //
        // These are the widths the table wants; on a narrow screen they do not fit, and
        // the old `coerceAtLeast` floors made the table wider than the panel instead of
        // narrower — which is how ten columns ended up running off the right edge at a
        // high GUI scale. So when the total does not fit, every column is squeezed by the
        // same ratio and the text simply wraps onto more lines.
        val naturalFixed = 74 + 44 + 84 + 104 + 74 + 78 + 54 + 62
        val minFlexible = 160
        val available = (contentW - gap * 9).coerceAtLeast(120)
        val squeeze = if (naturalFixed + minFlexible > available)
            available.toDouble() / (naturalFixed + minFlexible) else 1.0

        fun col(natural: Int, floor: Int) = (natural * squeeze).toInt().coerceAtLeast(floor)
        val wRarity = col(74, 30)
        val wWeight = col(44, 22)
        val wTimeWeather = col(84, 32)
        val wMultipliers = col(104, 34)
        val wContext = col(74, 30)
        val wPresets = col(78, 30)
        val wSkylight = col(54, 26)
        val wSky = col(62, 26)
        val fixed = wRarity + wWeight + wTimeWeather + wMultipliers + wContext + wPresets + wSkylight + wSky
        val flexible = (available - fixed).coerceAtLeast(60)
        val wBiomes = (flexible * 6 / 10).coerceAtLeast(34)
        val wExcluded = (flexible - wBiomes).coerceAtLeast(26)

        var x = leftX
        val colRarity = x; x += wRarity + gap
        val colWeight = x; x += wWeight + gap
        val colBiomes = x; x += wBiomes + gap
        val colExcluded = x; x += wExcluded + gap
        val colTimeWeather = x; x += wTimeWeather + gap
        val colMultipliers = x; x += wMultipliers + gap
        val colContext = x; x += wContext + gap
        val colPresets = x; x += wPresets + gap
        val colSkylight = x; x += wSkylight + gap
        val colSky = x
        val tableRight = colSky + wSky

        var y = startY
        val header = UiKit.TEXT_FAINT
        context.drawText(textRenderer, Translations.tr("Rarity"), colRarity, y, header, true)
        context.drawText(textRenderer, Translations.tr("Weight"), colWeight, y, header, true)
        context.drawText(textRenderer, Translations.tr("Biomes"), colBiomes, y, header, true)
        context.drawText(textRenderer, Translations.tr("Not in biomes"), colExcluded, y, header, true)
        context.drawText(textRenderer, Translations.tr("Time/Weather"), colTimeWeather, y, header, true)
        context.drawText(textRenderer, Translations.tr("Multipliers"), colMultipliers, y, header, true)
        context.drawText(textRenderer, Translations.tr("Context"), colContext, y, header, true)
        context.drawText(textRenderer, Translations.tr("Presets"), colPresets, y, header, true)
        context.drawText(textRenderer, Translations.tr("Skylight"), colSkylight, y, header, true)
        context.drawText(textRenderer, Translations.tr("Can see sky"), colSky, y, header, true)
        y += 11
        context.fill(leftX, y, tableRight, y + 1, UiKit.BORDER_DIM)
        y += 4

        val seen = mutableSetOf<String>()
        var index = 0
        for (spawn in spawns) {
            val key = SpawnData.dedupKey(spawn)
            if (key in seen) continue
            seen.add(key)

            val rowTop = y
            var rowBottom = y + lineH

            fun cell(text: String, cx: Int, width: Int, color: Int): Int {
                var cy = y
                for (line in wrapText(text, width)) {
                    context.drawText(textRenderer, line, cx, cy, color, true)
                    cy += lineH
                }
                return cy
            }

            // Rarity, plus the filler badge when the server added this spawn as a catch-all
            var rarityEnd = cell(
                Translations.formatRarity(spawn.bucket), colRarity, wRarity, getRarityColor(spawn.bucket))
            if (spawn.isFiller) {
                for (line in wrapText(Translations.tr("Filler"), wRarity)) {
                    context.drawText(textRenderer, line, colRarity, rarityEnd, COLOR_FILLER, true)
                    rarityEnd += lineH
                }
            }
            rowBottom = maxOf(rowBottom, rarityEnd)

            // Weight
            val weightText = spawn.weight?.let { String.format("%.1f", it) } ?: "—"
            rowBottom = maxOf(rowBottom, cell(weightText, colWeight, wWeight, UiKit.TEXT_MUTED))

            // Biomes, plus the blocks the spawn needs
            var biomeEnd = if (spawn.biomeDetails.isEmpty()) {
                cell(Translations.tr("Any"), colBiomes, wBiomes, UiKit.TEXT_FAINT)
            } else {
                renderBiomesInline(context, spawn.biomeDetails, colBiomes, y, wBiomes,
                    mouseX, mouseY, visibleTop, visibleBottom)
            }
            val requirements = mutableListOf<String>()
            if (spawn.neededBaseBlocks.isNotEmpty()) {
                requirements.add("${Translations.tr("Base:")} " +
                    spawn.neededBaseBlocks.joinToString(", ") { Translations.blockName(it) })
            }
            if (spawn.neededNearbyBlocks.isNotEmpty()) {
                requirements.add("${Translations.tr("Nearby:")} " +
                    spawn.neededNearbyBlocks.joinToString(", ") { Translations.blockName(it) })
            }
            for (requirement in requirements) {
                for (line in wrapText(requirement, wBiomes)) {
                    context.drawText(textRenderer, line, colBiomes, biomeEnd, 0xFF887755.toInt(), true)
                    biomeEnd += lineH
                }
            }
            rowBottom = maxOf(rowBottom, biomeEnd)

            // Excluded biomes
            rowBottom = maxOf(rowBottom, if (spawn.excludedBiomes.isEmpty()) {
                cell("—", colExcluded, wExcluded, UiKit.TEXT_FAINT)
            } else {
                renderBiomesInline(context, spawn.excludedBiomes, colExcluded, y, wExcluded,
                    mouseX, mouseY, visibleTop, visibleBottom)
            })

            // Time / Weather
            var twY = y
            twY = run {
                context.drawText(textRenderer, Translations.formatTime(spawn.time), colTimeWeather, twY,
                    UiKit.TEXT_MUTED, true)
                twY + lineH
            }
            context.drawText(textRenderer, Translations.formatWeather(spawn.weather), colTimeWeather, twY,
                UiKit.TEXT_MUTED, true)
            rowBottom = maxOf(rowBottom, twY + lineH)

            // Weight multipliers, one per line
            var multY = y
            if (spawn.weightMultipliers.isEmpty()) {
                context.drawText(textRenderer, "—", colMultipliers, multY, UiKit.TEXT_FAINT, true)
                multY += lineH
            } else {
                for (multiplier in spawn.weightMultipliers) {
                    val factor = if (multiplier.multiplier == multiplier.multiplier.toInt().toFloat())
                        "×${multiplier.multiplier.toInt()}"
                    else "×${String.format("%.1f", multiplier.multiplier)}"
                    val label = "$factor ${Translations.formatMultiplierCondition(multiplier.conditionLabel)}"
                    for (line in wrapText(label, wMultipliers)) {
                        context.drawText(textRenderer, line, colMultipliers, multY, 0xFF55BBFF.toInt(), true)
                        multY += lineH
                    }
                }
            }
            rowBottom = maxOf(rowBottom, multY)

            // Context (spawnable position type)
            val contextText = Translations.formatSpawnContext(spawn.spawnContext)
                .ifEmpty { Translations.tr("Grounded") }
            rowBottom = maxOf(rowBottom, cell(contextText, colContext, wContext, UiKit.TEXT_MUTED))

            // Presets
            val presets = spawn.presets
            rowBottom = maxOf(rowBottom, cell(
                if (presets.isEmpty()) "—" else presets.joinToString(", ") { Translations.formatPreset(it) },
                colPresets, wPresets, 0xFFAA8855.toInt()))

            // Skylight (falls back to block light when there is no sky requirement)
            val skylight = when {
                spawn.minSkyLight != null || spawn.maxSkyLight != null ->
                    "${spawn.minSkyLight ?: 0}-${spawn.maxSkyLight ?: 15}"
                spawn.minLight != null || spawn.maxLight != null ->
                    "☼${spawn.minLight ?: 0}-${spawn.maxLight ?: 15}"
                else -> "—"
            }
            rowBottom = maxOf(rowBottom, cell(skylight, colSkylight, wSkylight, UiKit.TEXT_MUTED))

            // Can see sky
            val skyText = when (spawn.canSeeSky) {
                true -> Translations.tr("Outdoor")
                false -> Translations.tr("Underground")
                else -> "—"
            }
            rowBottom = maxOf(rowBottom, cell(skyText, colSky, wSky, UiKit.TEXT_MUTED))

            // A visible rule under every row: rows can be several lines tall, so a faint
            // stripe on alternating rows was not enough to tell them apart.
            context.fill(leftX, rowBottom + 1, tableRight, rowBottom + 2, UiKit.BORDER)

            y = rowBottom + 5
            index++
        }

        return y
    }

    // ==================== Size & Riding (Cobblemon 1.8) ====================

    /**
     * Cobblemon 1.8 buckets every Pokemon into XS..XL. The thresholds are not per
     * species: `PokemonSizeCategory.fromScale` splits the server's intrinsic size
     * range into five equal bands, so they are read from ServerSettings and follow
     * whatever the server is running.
     */
    private fun renderSizeCategories(context: DrawContext, infoX: Int, startY: Int): Int {
        var y = startY
        try {
            val min = ServerSettings.pokemonIntrinsicSizeMin
            val max = ServerSettings.pokemonIntrinsicSizeMax
            val span = max - min
            if (span <= 0f) return y

            val categories = PokemonSizeCategory.entries
            val step = span / categories.size
            val label: String = Translations.tr("Sizes")
            context.drawText(textRenderer, label, infoX, y, UiKit.accent(), true)
            y += 11

            val parts = categories.mapIndexed { index, category ->
                val upper = min + step * (index + 1)
                if (index == categories.size - 1) "${category.name} \u2265${"%.2f".format(min + step * index)}"
                else "${category.name} <${"%.2f".format(upper)}"
            }
            context.drawText(textRenderer, parts.joinToString(" \u00b7 "), infoX + 4, y, UiKit.TEXT_MUTED, true)
            y += 10

            // Same scale expressed as this species' actual height
            val h = species.height / 10f
            if (h > 0f) {
                val heightText = "${Translations.tr("Height:")} ${"%.2f".format(h * min)}\u2013${"%.2f".format(h * max)} m"
                context.drawText(textRenderer, heightText, infoX + 4, y, UiKit.TEXT_FAINT, true)
                y += 10
            }
        } catch (_: Exception) {}
        return y
    }

    /**
     * Riding stats as a pentagon radar chart, one tab per style.
     *
     * The two outlines are not a random roll. A stat's range is `first..last` where
     * `first` is the base every individual of the species has and `last` is the ceiling:
     * Cobblemon computes the effective value as `min(first + rideBoost, last)`, and
     * `rideBoost` is a per-Pokémon value the player raises. So the filled shape is what
     * the species starts with, the outline is what it can be trained up to, and the gap
     * between them is the headroom.
     */
    private fun renderRidingRadar(context: DrawContext, midX: Int, startY: Int, halfW: Int,
                                  mouseX: Int, mouseY: Int): Int {
        var y = startY
        try {
            val riding = currentForm()?.riding ?: species.riding
            val behaviours = riding?.behaviours ?: return y
            if (behaviours.isEmpty()) return y

            val styles = listOf(RidingStyle.LAND, RidingStyle.LIQUID, RidingStyle.AIR)
                .filter { behaviours[it] != null }
            if (styles.isEmpty()) return y

            context.drawText(textRenderer, Translations.tr("Riding"), midX, y, UiKit.accent(), true)
            y += 12

            // tabs, only when the species really can do several things
            if (styles.size > 1) {
                if (ridingStyleTab >= styles.size) ridingStyleTab = 0
                var tabX = midX
                for ((index, style) in styles.withIndex()) {
                    val label = rideStyleName(style)
                    val tabW = textRenderer.getWidth(label) + 10
                    val active = index == ridingStyleTab
                    val hovered = mouseX >= tabX && mouseX <= tabX + tabW &&
                        mouseY >= y && mouseY <= y + 12
                    UiKit.tab(context, textRenderer, tabX, y, tabW, 12, label, active, hovered)
                    ridingTabBounds.add(RidingTabBound(tabX, y, tabW, 12, index))
                    tabX += tabW + 2
                }
                y += 15
            } else {
                ridingStyleTab = 0
                context.drawText(textRenderer, "  ${rideStyleName(styles[0])}", midX, y, UiKit.TEXT, true)
                y += 11
            }

            val settings = behaviours[styles[ridingStyleTab]] ?: return y
            val stats = try { settings.stats } catch (_: Exception) { null } ?: return y
            if (stats.isEmpty()) return y

            val statOrder = listOf(
                RidingStat.SPEED to Translations.tr("Spd"),
                RidingStat.ACCELERATION to Translations.tr("Accel"),
                RidingStat.JUMP to Translations.tr("Jump"),
                RidingStat.STAMINA to Translations.tr("Stam"),
                RidingStat.SKILL to Translations.tr("Skill")
            )
            y = drawRadar(context, midX, y, halfW, statOrder, stats)

            val legend = Translations.tr("filled = base · outline = trained cap")
            context.drawText(textRenderer, legend, midX, y, UiKit.TEXT_FAINT, true)
            y += 11
        } catch (_: Exception) {}
        return y
    }

    private fun drawRadar(
        context: DrawContext,
        midX: Int,
        startY: Int,
        halfW: Int,
        statOrder: List<Pair<RidingStat, String>>,
        stats: Map<RidingStat, IntRange>
    ): Int {
        val radius = 30
        val labelPad = 26
        val cx = midX + halfW / 2
        val cy = startY + radius + 6
        val n = statOrder.size

        // angle of axis i, starting at the top and going clockwise
        fun angle(i: Int) = (-Math.PI / 2.0) + (2.0 * Math.PI * i / n)
        fun px(i: Int, r: Double) = (cx + Math.cos(angle(i)) * r).toInt()
        fun py(i: Int, r: Double) = (cy + Math.sin(angle(i)) * r).toInt()

        // grid rings + axes
        for (ring in 1..3) {
            val r = radius * ring / 3.0
            val gx = IntArray(n) { px(it, r) }
            val gy = IntArray(n) { py(it, r) }
            UiKit.polygon(context, gx, gy, UiKit.BORDER_DIM)
        }
        for (i in 0 until n) {
            UiKit.line(context, cx, cy, px(i, radius.toDouble()), py(i, radius.toDouble()), UiKit.BORDER_DIM)
        }

        // base value (filled) and trained ceiling (outline)
        val minX = IntArray(n); val minY = IntArray(n)
        val maxX = IntArray(n); val maxY = IntArray(n)
        for (i in 0 until n) {
            val range = stats[statOrder[i].first]
            val lo = (range?.first ?: 0).coerceIn(0, 100)
            val hi = (range?.last ?: 0).coerceIn(0, 100)
            minX[i] = px(i, radius * lo / 100.0); minY[i] = py(i, radius * lo / 100.0)
            maxX[i] = px(i, radius * hi / 100.0); maxY[i] = py(i, radius * hi / 100.0)
        }
        UiKit.fillPolygon(context, minX, minY, UiKit.withAlpha(UiKit.accent(), 0x55))
        UiKit.polygon(context, maxX, maxY, UiKit.accentBright())
        UiKit.polygon(context, minX, minY, UiKit.accent())

        // vertex labels: name above the value range
        for (i in 0 until n) {
            val (stat, label) = statOrder[i]
            val range = stats[stat]
            val value = if (range == null) "-" else "${range.first}-${range.last}"
            val lx = px(i, radius + 9.0)
            val ly = py(i, radius + 9.0)
            val cosA = Math.cos(angle(i))
            for ((offset, text, color) in listOf(
                Triple(0, label, UiKit.TEXT_MUTED),
                Triple(9, value, UiKit.TEXT_FAINT)
            )) {
                val w = textRenderer.getWidth(text)
                val tx = when {
                    cosA > 0.3 -> lx
                    cosA < -0.3 -> lx - w
                    else -> lx - w / 2
                }
                context.drawText(textRenderer, text, tx, ly - 4 + offset, color, true)
            }
        }

        return cy + radius + labelPad
    }

    private fun rideStyleName(style: RidingStyle): String = when (style) {
        RidingStyle.LAND -> Translations.tr("Land")
        RidingStyle.LIQUID -> Translations.tr("Water")
        RidingStyle.AIR -> Translations.tr("Air")
    }

    // ==================== Move Rendering ====================

    data class MoveEntry(val level: Int, val move: MoveTemplate)

    private fun getLevelUpMoves(): List<MoveEntry> {
        return try {
            // Try form-specific moves first
            val form = currentForm()
            val formMoves = try {
                form?.moves?.levelUpMoves?.flatMap { (level, moves) ->
                    moves.map { MoveEntry(level, it) }
                }?.sortedBy { it.level }
            } catch (_: Exception) { null }
            if (formMoves != null && formMoves.isNotEmpty()) return formMoves

            val apiMoves = species.moves.levelUpMoves.flatMap { (level, moves) ->
                moves.map { MoveEntry(level, it) }
            }.sortedBy { it.level }
            if (apiMoves.isNotEmpty()) return apiMoves
            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.levelUp.map { MoveEntry(it.first, it.second) }
        } catch (_: Exception) { emptyList() }
    }

    private fun getTmMoves(): List<MoveEntry> {
        return try {
            val form = currentForm()
            val formMoves = try {
                form?.moves?.tmMoves?.map { MoveEntry(-1, it) }?.sortedBy { it.move.displayName.string }
            } catch (_: Exception) { null }
            if (formMoves != null && formMoves.isNotEmpty()) return formMoves

            val apiMoves = species.moves.tmMoves.map { MoveEntry(-1, it) }.sortedBy { it.move.displayName.string }
            if (apiMoves.isNotEmpty()) return apiMoves
            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.tm.map { MoveEntry(-1, it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun getEggMoves(): List<MoveEntry> {
        return try {
            val form = currentForm()
            val formMoves = try {
                form?.moves?.eggMoves?.map { MoveEntry(-1, it) }?.sortedBy { it.move.displayName.string }
            } catch (_: Exception) { null }
            if (formMoves != null && formMoves.isNotEmpty()) return formMoves

            val apiMoves = species.moves.eggMoves.map { MoveEntry(-1, it) }.sortedBy { it.move.displayName.string }
            if (apiMoves.isNotEmpty()) return apiMoves
            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.egg.map { MoveEntry(-1, it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun getTutorMoves(): List<MoveEntry> {
        return try {
            val form = currentForm()
            val formMoves = try {
                form?.moves?.tutorMoves?.map { MoveEntry(-1, it) }?.sortedBy { it.move.displayName.string }
            } catch (_: Exception) { null }
            if (formMoves != null && formMoves.isNotEmpty()) return formMoves

            val apiMoves = species.moves.tutorMoves.map { MoveEntry(-1, it) }.sortedBy { it.move.displayName.string }
            if (apiMoves.isNotEmpty()) return apiMoves
            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.tutor.map { MoveEntry(-1, it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun renderMoveTable(
        context: DrawContext, moves: List<MoveEntry>,
        startY: Int, panelX: Int, panelWidth: Int, leftX: Int,
        showLevel: Boolean, mouseX: Int, mouseY: Int,
        contentTop: Int, contentBottom: Int,
        markTms: Boolean = false
    ): Int {
        var y = startY
        val rowH = 14
        val tableX = panelX + 8
        val colLvl = tableX
        val colName = if (showLevel) colLvl + 24 else colLvl
        val colType = colName + 95
        val typeBadgeW = 52
        val colCat = colType + typeBadgeW + 6
        val colPow = colCat + 50
        val colAcc = colPow + 30
        val colPP = colAcc + 30

        val headerColor = UiKit.TEXT_FAINT
        if (showLevel) context.drawText(textRenderer, Translations.tr("Lv"), colLvl, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Move"), colName, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Type"), colType, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Cat."), colCat, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Pow"), colPow, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Acc"), colAcc, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("PP"), colPP, y, headerColor, true)
        y += 11
        context.fill(tableX, y, panelX + panelWidth - 8, y + 1, UiKit.BORDER_DIM)
        y += 4

        for ((index, entry) in moves.withIndex()) {
            val move = entry.move
            val rowTop = y
            try {
                val rowHovered = mouseX >= tableX && mouseX <= panelX + panelWidth - 8 &&
                                 mouseY >= rowTop && mouseY < rowTop + rowH &&
                                 mouseY >= contentTop && mouseY <= contentBottom
                if (rowHovered) {
                    context.fill(tableX, rowTop, panelX + panelWidth - 8, rowTop + rowH, UiKit.SURFACE)
                }
                if (showLevel) {
                    context.drawText(textRenderer, "${entry.level}", colLvl, y + 2, UiKit.TEXT_FAINT, true)
                }
                // A move can be TM-learnable in the learnset without a TM item existing
                val hasTmItem = markTms && TmData.forMove(move.name) != null
                val moveDisplayName = if (hasTmItem) "\u25C6 ${move.displayName.string}" else move.displayName.string
                val moveNameW = textRenderer.getWidth(moveDisplayName)
                val nameHovered = mouseX >= colName && mouseX <= colName + moveNameW &&
                                  mouseY >= rowTop && mouseY < rowTop + rowH &&
                                  mouseY >= contentTop && mouseY <= contentBottom
                val nameColor = if (nameHovered) UiKit.accent() else UiKit.TEXT
                context.drawText(textRenderer, moveDisplayName, colName, y + 2, nameColor, true)
                if (nameHovered) {
                    context.fill(colName, y + 11, colName + moveNameW, y + 12, UiKit.accent())
                }
                moveClickBounds.add(MoveClickBound(colName, rowTop, moveNameW, rowH, move))
                val typeKey = move.elementalType.name
                drawMiniTypeBadge(context, colType, y + 1, typeKey)
                val catName: String = move.damageCategory.displayName.string
                context.drawText(textRenderer, catName, colCat, y + 2, getCategoryColor(move.damageCategory.name), true)
                val power = move.power.toInt()
                context.drawText(textRenderer, if (power > 0) "$power" else "-", colPow, y + 2, UiKit.TEXT_MUTED, true)
                val acc = move.accuracy
                context.drawText(textRenderer, if (acc > 0) "${acc.toInt()}" else "-", colAcc, y + 2, UiKit.TEXT_MUTED, true)
                context.drawText(textRenderer, "${move.pp}", colPP, y + 2, UiKit.TEXT_MUTED, true)
                moveRowBounds.add(MoveRowBounds(rowTop, rowH, move))
            } catch (_: Exception) {}
            y += rowH
            if (index % 2 == 1) {
                context.fill(tableX, y - 1, panelX + panelWidth - 8, y, 0xFF151515.toInt())
            }
        }
        y += 8
        return y
    }

    private fun drawMiniTypeBadge(context: DrawContext, x: Int, y: Int, typeKey: String) {
        val badgeW = 50
        val badgeH = 11
        val color = getTypeColor(typeKey)
        val displayName = Translations.formatTypeName(typeKey)
        context.fill(x, y, x + badgeW, y + badgeH, color)
        val textW = textRenderer.getWidth(displayName)
        val textX = x + (badgeW - textW) / 2
        context.drawText(textRenderer, displayName, textX, y + 1, UiKit.TEXT, true)
    }

    private fun updateHoverState(mouseX: Int, mouseY: Int, panelX: Int, panelWidth: Int,
                                  contentTop: Int, contentBottom: Int) {
        val tableX = panelX + 8
        var foundMove: MoveTemplate? = null
        var foundMoveIndex = -1
        if (mouseX >= tableX && mouseX <= panelX + panelWidth - 8 &&
            mouseY >= contentTop && mouseY <= contentBottom) {
            for ((i, row) in moveRowBounds.withIndex()) {
                if (mouseY >= row.y && mouseY < row.y + row.h) {
                    foundMove = row.move
                    foundMoveIndex = i
                    break
                }
            }
        }
        if (foundMoveIndex != hoveredMoveIndex) {
            hoveredMoveIndex = foundMoveIndex
            hoverStartTime = System.currentTimeMillis()
            tooltipMove = foundMove
        } else if (foundMove == null) {
            tooltipMove = null
            hoveredMoveIndex = -1
        }
        var foundAbility: PotentialAbility? = null
        var foundAbilityIndex = -1
        for ((i, row) in abilityRowBounds.withIndex()) {
            if (mouseX >= row.x && mouseX <= row.x + row.w &&
                mouseY >= row.y && mouseY < row.y + row.h &&
                mouseY >= contentTop && mouseY <= contentBottom) {
                foundAbility = row.ability
                foundAbilityIndex = i
                break
            }
        }
        if (foundAbilityIndex != hoveredAbilityIndex) {
            hoveredAbilityIndex = foundAbilityIndex
            abilityHoverStartTime = System.currentTimeMillis()
            tooltipAbility = foundAbility
        } else if (foundAbility == null) {
            tooltipAbility = null
            hoveredAbilityIndex = -1
        }
        // Item drop hover detection
        var foundDrop: Identifier? = null
        var foundDropIndex = -1
        for ((i, row) in dropRowBounds.withIndex()) {
            if (mouseX >= row.x && mouseX <= row.x + row.w &&
                mouseY >= row.y && mouseY < row.y + row.h &&
                mouseY >= contentTop && mouseY <= contentBottom) {
                foundDrop = row.itemId
                foundDropIndex = i
                break
            }
        }
        if (foundDropIndex != hoveredDropIndex) {
            hoveredDropIndex = foundDropIndex
            dropHoverStartTime = System.currentTimeMillis()
            tooltipDropItem = foundDrop
        } else if (foundDrop == null) {
            tooltipDropItem = null
            hoveredDropIndex = -1
        }
    }

    // ==================== Type Chart ====================

    private fun getTypeEffectiveness(primaryType: String, secondaryType: String?): Map<String, Float> {
        return ATTACK_TYPE_ORDER.associateWith { atkType ->
            val m1 = DEF_TYPE_CHART[primaryType]?.get(atkType) ?: 1f
            val m2 = if (secondaryType != null) DEF_TYPE_CHART[secondaryType]?.get(atkType) ?: 1f else 1f
            m1 * m2
        }
    }

    private fun renderTypeChart(
        context: DrawContext, startY: Int, startX: Int, contentWidth: Int,
        primaryType: String, secondaryType: String?
    ): Int {
        val effectiveness = getTypeEffectiveness(primaryType, secondaryType)
        val typesPerRow = 9
        val cellW = contentWidth / typesPerRow
        val badgeH = 11
        val multH = 9

        var y = startY
        for (rowStart in listOf(0, 9)) {
            val rowTypes = ATTACK_TYPE_ORDER.subList(rowStart, minOf(rowStart + typesPerRow, 18))

            // Row 1: type color badges with localized full name
            var x = startX
            for (atkType in rowTypes) {
                val displayName = Translations.formatTypeName(atkType)
                val color = getTypeColor(atkType.replaceFirstChar { it.uppercase() })
                context.fill(x, y, x + cellW - 1, y + badgeH, color)
                val tw = textRenderer.getWidth(displayName)
                val textX = x + ((cellW - 1 - tw) / 2).coerceAtLeast(1)
                context.drawText(textRenderer, displayName, textX, y + 2, UiKit.TEXT, true)
                x += cellW
            }
            y += badgeH + 2

            // Row 2: effectiveness multipliers
            x = startX
            for (atkType in rowTypes) {
                val mult = effectiveness[atkType] ?: 1f
                val multText = when {
                    mult == 0f    -> Translations.immune()
                    mult <= 0.25f -> "\u00d7\u00bc"   // ×¼
                    mult <= 0.5f  -> "\u00d7\u00bd"   // ×½
                    mult >= 4f    -> "\u00d74"
                    mult >= 2f    -> "\u00d72"
                    else          -> "\u00d71"
                }
                val multColor = when {
                    mult == 0f -> UiKit.TEXT_FAINT   // immune — gray
                    mult < 1f  -> 0xFF44CC44.toInt()   // resistant — green
                    mult > 1f  -> 0xFFFF5533.toInt()   // weak — red
                    else       -> UiKit.BORDER   // neutral — dim
                }
                val tw = textRenderer.getWidth(multText)
                val textX = x + ((cellW - tw) / 2).coerceAtLeast(1)
                context.drawText(textRenderer, multText, textX, y, multColor, true)
                x += cellW
            }
            y += multH + 6
        }
        return y
    }

    // ==================== UI Helpers ====================

    private fun drawTypeBadge(context: DrawContext, x: Int, y: Int, typeName: String) {
        val w = textRenderer.getWidth(typeName) + 8
        val color = getTypeColor(typeName)
        context.fill(x, y, x + w, y + 12, color)
        context.fill(x, y, x + w, y + 1, (color and 0x00FFFFFF) or 0x44000000.toInt())
        context.drawText(textRenderer, typeName, x + 4, y + 2, 0xFFFFFFFF.toInt(), true)
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

    private fun getStatColor(value: Int): Int {
        return when {
            value >= 150 -> 0xFF00C8B0.toInt(); value >= 120 -> 0xFF55CC55.toInt()
            value >= 90 -> 0xFF88BB33.toInt(); value >= 60 -> 0xFFFFCC33.toInt()
            value >= 30 -> 0xFFFF8844.toInt(); else -> 0xFFFF4444.toInt()
        }
    }

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            // Riding style tabs
            for (tab in ridingTabBounds) {
                if (mouseX >= tab.x && mouseX <= tab.x + tab.w &&
                    mouseY >= tab.y.toDouble() && mouseY <= (tab.y + tab.h).toDouble()) {
                    ridingStyleTab = tab.index
                    return true
                }
            }

            // Habitat row click -> its wiki page
            hoveredHabitatId?.let { habitatId ->
                client?.setScreen(HabitatDetailScreen(habitatId, this))
                return true
            }


            // The table needs more room than the text layout
        val panelWidth = if (ModConfig.spawnDisplayMode == "table")
            (width * 0.96).toInt().coerceIn(520, 1120)
        else (width * 0.8).toInt().coerceIn(380, 620)
            val panelX = (width - panelWidth) / 2
            val panelTop = 10

            // Close button ✕
            val closeX = panelX + panelWidth - 12
            val closeY = panelTop + 4
            if (mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2.0 && mouseY <= closeY + 11.0) {
                close(); return true
            }

            // Scrollbar click
            if (sbThumbHeight > 0 && mouseX >= sbTrackX && mouseX <= sbTrackX + 6 &&
                mouseY >= sbContentTop && mouseY <= sbContentBottom) {
                if (mouseY >= sbThumbY && mouseY <= sbThumbY + sbThumbHeight) {
                    isScrollbarDragging = true
                    scrollbarDragStartY = mouseY.toInt()
                    scrollbarDragStartOffset = scrollOffset
                } else {
                    val contentAreaHeight = sbContentBottom - sbContentTop
                    val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
                    val ratio = (mouseY - sbContentTop).toFloat() / contentAreaHeight
                    scrollOffset = (ratio * maxScroll).toInt().coerceIn(0, maxScroll)
                }
                return true
            }

            // Back button
            val backText: String = Translations.tr("\u2190 Back")
            if (mouseX >= panelX + 6 && mouseX <= panelX + 6 + textRenderer.getWidth(backText) + 4 &&
                mouseY >= panelTop + 5.0 && mouseY <= panelTop + 17.0) {
                client?.setScreen(parent)
                return true
            }

            // F2: Prev/Next buttons
            val dexText = "#${String.format("%04d", species.nationalPokedexNumber)}"
            val dexW = textRenderer.getWidth(dexText)
            val prevText = "\u25C0"
            val nextText = "\u25B6"
            val navW = textRenderer.getWidth(prevText)
            val navRightEdge = panelX + panelWidth - 22
            val nextBtnX = navRightEdge - navW
            val dexTextX = nextBtnX - dexW - 4
            val prevBtnX = dexTextX - navW - 4
            val navY = panelTop + 6

            val hasPrev = allSpecies != null && currentIndex > 0
            val hasNext = allSpecies != null && currentIndex >= 0 && currentIndex < (allSpecies?.size ?: 0) - 1

            if (hasPrev && mouseX >= prevBtnX && mouseX <= prevBtnX + navW &&
                mouseY >= navY.toDouble() && mouseY <= navY + 10.0) {
                val prevSpecies = allSpecies!![currentIndex - 1]
                client?.setScreen(PokemonDetailScreen(prevSpecies, parent, allSpecies))
                return true
            }
            if (hasNext && mouseX >= nextBtnX && mouseX <= nextBtnX + navW &&
                mouseY >= navY.toDouble() && mouseY <= navY + 10.0) {
                val nextSpecies = allSpecies!![currentIndex + 1]
                client?.setScreen(PokemonDetailScreen(nextSpecies, parent, allSpecies))
                return true
            }

            // Shiny toggle
            if (mouseX >= shinyBtnX && mouseX <= shinyBtnX + shinyBtnW &&
                mouseY >= shinyBtnY && mouseY <= shinyBtnY + shinyBtnH) {
                isShiny = !isShiny
                showCosmeticOverlay = false
                rebuildModel()
                return true
            }

            // Cosmetic button toggle
            if (availableCosmetics.isNotEmpty() &&
                mouseX >= cosmeticBtnX && mouseX <= cosmeticBtnX + cosmeticBtnW &&
                mouseY >= cosmeticBtnY && mouseY <= cosmeticBtnY + cosmeticBtnH) {
                showCosmeticOverlay = !showCosmeticOverlay
                return true
            }

            // Cosmetic overlay click
            if (showCosmeticOverlay && availableCosmetics.isNotEmpty()) {
                val overlayX = cosmeticBtnX - 20
                val rowH = 18
                val overlayItemCount = availableCosmetics.size + 1
                val overlayH = overlayItemCount * rowH + 6
                val overlayW = availableCosmetics.maxOf { textRenderer.getWidth(it.displayName) + 24 }.coerceAtLeast(80)
                val overlayY = cosmeticBtnY + cosmeticBtnH + 2

                if (mouseX >= overlayX && mouseX <= overlayX + overlayW &&
                    mouseY >= overlayY && mouseY <= overlayY + overlayH) {
                    val clickedRow = ((mouseY - overlayY - 3) / rowH).toInt()
                    if (clickedRow == 0) {
                        selectedCosmeticIndex = -1
                    } else if (clickedRow - 1 in availableCosmetics.indices) {
                        selectedCosmeticIndex = clickedRow - 1
                    }
                    showCosmeticOverlay = false
                    rebuildModel()
                    return true
                } else {
                    showCosmeticOverlay = false
                    return true
                }
            }

            // Model click
            if (modelWidget != null) {
                val contentTop = (if (availableForms.size > 1) panelTop + 22 + 16 else panelTop + 22)
                val contentBottom = height - 10 - 16
                if (mouseX >= modelRenderX && mouseX <= modelRenderX + MODEL_SIZE &&
                    mouseY >= modelRenderY && mouseY <= modelRenderY + MODEL_SIZE &&
                    mouseY >= contentTop && mouseY <= contentBottom) {
                    modelWidget!!.mouseClicked(mouseX, mouseY, button)
                    playCrySound()
                    return true
                }
            }

            // F4: Form tab scroll arrows
            formTabLeftArrowBounds?.let { b ->
                if (mouseX >= b[0] && mouseX <= b[0] + b[2] &&
                    mouseY >= b[1].toDouble() && mouseY <= (b[1] + b[3]).toDouble()) {
                    formTabScrollOffset = maxOf(0, formTabScrollOffset - 40)
                    return true
                }
            }
            formTabRightArrowBounds?.let { b ->
                if (mouseX >= b[0] && mouseX <= b[0] + b[2] &&
                    mouseY >= b[1].toDouble() && mouseY <= (b[1] + b[3]).toDouble()) {
                    formTabScrollOffset += 40
                    return true
                }
            }

            // F4: Form tab clicks
            for ((i, bounds) in formTabBounds.withIndex()) {
                val (fx, fy, fw, fh) = bounds
                if (mouseX >= fx && mouseX <= fx + fw &&
                    mouseY >= fy.toDouble() && mouseY <= (fy + fh).toDouble()) {
                    if (currentFormIndex != i) {
                        currentFormIndex = i
                        rebuildModel()
                        cachedMoves = arrayOfNulls(4)
                        scrollOffset = 0
                    }
                    return true
                }
            }

            // F3: Evolution link clicks
            for (evo in evoClickBounds) {
                if (mouseX >= evo.x && mouseX <= evo.x + evo.w &&
                    mouseY >= evo.y.toDouble() && mouseY <= (evo.y + evo.h).toDouble()) {
                    val targetFormName = evo.formAspect?.let { EvolutionData.aspectToFormName(it) }
                    client?.setScreen(PokemonDetailScreen(evo.species, parent, allSpecies, targetFormName))
                    return true
                }
            }

            // Mega evolution link clicks
            val mcTop = if (availableForms.size > 1) panelTop + 22 + 16 else panelTop + 22
            val mcBottom = height - 10 - 16
            for (mega in megaClickBounds) {
                if (mouseX >= mega.x && mouseX <= mega.x + mega.w &&
                    mouseY >= mega.y.toDouble() && mouseY <= (mega.y + mega.h).toDouble() &&
                    mouseY >= mcTop.toDouble() && mouseY <= mcBottom.toDouble()) {
                    client?.setScreen(PokemonDetailScreen(species, parent, allSpecies, mega.form.name))
                    return true
                }
            }

            // Move name clicks → MoveDetailScreen
            val contentTop2 = if (availableForms.size > 1) panelTop + 22 + 16 else panelTop + 22
            val contentBottom2 = height - 10 - 16
            for (mc in moveClickBounds) {
                if (mouseX >= mc.x && mouseX <= mc.x + mc.w &&
                    mouseY >= mc.y.toDouble() && mouseY < (mc.y + mc.h).toDouble() &&
                    mouseY >= contentTop2.toDouble() && mouseY <= contentBottom2.toDouble()) {
                    client?.setScreen(MoveDetailScreen(mc.move, this))
                    return true
                }
            }

            // Ability clicks → AbilityDetailScreen
            for (row in abilityRowBounds) {
                if (mouseX >= row.x && mouseX <= row.x + row.w &&
                    mouseY >= row.y.toDouble() && mouseY < (row.y + row.h).toDouble() &&
                    mouseY >= contentTop2.toDouble() && mouseY <= contentBottom2.toDouble()) {
                    client?.setScreen(AbilityDetailScreen(row.ability.template, this))
                    return true
                }
            }

            // Drop clicks → ItemDropDetailScreen
            for (row in dropRowBounds) {
                if (mouseX >= row.x && mouseX <= row.x + row.w &&
                    mouseY >= row.y.toDouble() && mouseY < (row.y + row.h).toDouble() &&
                    mouseY >= contentTop2.toDouble() && mouseY <= contentBottom2.toDouble()) {
                    client?.setScreen(ItemDropDetailScreen(row.itemId, this))
                    return true
                }
            }

            // Move tab clicks
            for (i in tabBounds.indices) {
                val (tx, ty, tw, th) = tabBounds[i]
                if (tw > 0 && mouseX >= tx && mouseX <= tx + tw &&
                    mouseY >= ty && mouseY <= ty + th) {
                    if (selectedTab != i) {
                        selectedTab = i
                        hoveredMoveIndex = -1
                        tooltipMove = null
                    }
                    return true
                }
            }
        }
        return super.mouseClickedScaled(mouseX, mouseY, button)
    }

    override fun mouseDraggedScaled(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (isScrollbarDragging && button == 0) {
            val contentAreaHeight = sbContentBottom - sbContentTop
            val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
            val trackRange = contentAreaHeight - sbThumbHeight
            if (trackRange > 0) {
                val mouseDelta = mouseY.toInt() - scrollbarDragStartY
                val scrollDelta = (mouseDelta.toFloat() / trackRange * maxScroll).toInt()
                scrollOffset = (scrollbarDragStartOffset + scrollDelta).coerceIn(0, maxScroll)
            }
            return true
        }
        return super.mouseDraggedScaled(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleasedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && isScrollbarDragging) {
            isScrollbarDragging = false
            return true
        }
        return super.mouseReleasedScaled(mouseX, mouseY, button)
    }

    override fun mouseScrolledScaled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelTop = 10
        val panelBottom = height - 10
        val formOffset = if (availableForms.size > 1) 16 else 0
        val contentAreaHeight = panelBottom - 16 - (panelTop + 22 + formOffset)
        val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            client?.setScreen(parent)
            return true
        }
        // F2: Arrow key navigation
        if (keyCode == GLFW.GLFW_KEY_LEFT && allSpecies != null && currentIndex > 0) {
            client?.setScreen(PokemonDetailScreen(allSpecies[currentIndex - 1], parent, allSpecies))
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT && allSpecies != null && currentIndex >= 0 && currentIndex < allSpecies.size - 1) {
            client?.setScreen(PokemonDetailScreen(allSpecies[currentIndex + 1], parent, allSpecies))
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() {
        client?.setScreen(parent)
    }

    private fun playCrySound() {
        try {
            val namespace = species.resourceIdentifier.namespace
            val showdownId = species.showdownId()
            val cryId = Identifier.of(namespace, "pokemon.$showdownId.cry")
            val mc = MinecraftClient.getInstance()
            if (mc.soundManager.get(cryId) != null) {
                val soundEvent = SoundEvent.of(cryId)
                mc.soundManager.play(PositionedSoundInstance.master(soundEvent, 1.0f))
            }
        } catch (_: Exception) {}
    }

    private fun getRarityColor(bucket: String): Int {
        return when (bucket.lowercase().trim()) {
            "common" -> 0xFF55CC55.toInt(); "uncommon" -> 0xFFFFDD55.toInt()
            "rare" -> 0xFF55AAFF.toInt(); "ultra-rare" -> 0xFFBB66FF.toInt()
            "boss" -> 0xFFFF5544.toInt()
            else -> UiKit.TEXT_MUTED
        }
    }

    private fun renderBiomesInline(
        context: DrawContext, biomes: List<BiomeDetail>,
        startX: Int, startY: Int, maxWidth: Int,
        mouseX: Int, mouseY: Int, visibleTop: Int, visibleBottom: Int
    ): Int {
        var x = startX
        var y = startY
        for ((i, biome) in biomes.withIndex()) {
            val nameW = textRenderer.getWidth(Translations.biomeName(biome))
            val separator = if (i < biomes.lastIndex) ", " else ""
            val sepW = textRenderer.getWidth(separator)
            if (x + nameW > startX + maxWidth && x > startX) {
                y += 10
                x = startX
            }
            val isHovered = biome.tagId != null &&
                    mouseX >= x && mouseX <= x + nameW &&
                    mouseY >= y && mouseY <= y + 10 &&
                    mouseY >= visibleTop && mouseY <= visibleBottom
            if (isHovered) hoveredBiomeDetail = biome
            val color = when {
                isHovered -> UiKit.accent()
                biome.tagId != null -> UiKit.TEXT_MUTED
                else -> UiKit.TEXT_FAINT
            }
            context.drawText(textRenderer, Translations.biomeName(biome), x, y, color, true)
            if (biome.tagId != null) {
                val underlineColor = if (isHovered) UiKit.accent() else UiKit.BORDER
                context.fill(x, y + 9, x + nameW, y + 10, underlineColor)
            }
            x += nameW
            if (separator.isNotEmpty()) {
                context.drawText(textRenderer, separator, x, y, UiKit.TEXT_FAINT, true)
                x += sepW
            }
        }
        return y + 10
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

    private fun renderBiomeTooltip(
        context: DrawContext,
        biomes: List<BiomeLine>,
        detail: BiomeDetail,
        mouseX: Int,
        mouseY: Int
    ) {
        val lineHeight = 10
        val padding = 6
        val titleText = Translations.biomeName(detail)
        val titleColor = UiKit.accent()
        val biomeColor = UiKit.TEXT_MUTED
        val headerColor = 0xFF88AACC.toInt()
        val bgColor = 0xF0100010.toInt()
        val borderColor = 0xFF5000A0.toInt()
        val screenMargin = 4
        val maxAvailableHeight = height - screenMargin * 2
        val colGap = 12

        var numCols = 1
        while (numCols < 4) {
            val rowsPerCol = (biomes.size + numCols - 1) / numCols
            val h = padding + (rowsPerCol + 1) * lineHeight + padding
            if (h <= maxAvailableHeight) break
            numCols++
        }

        val rowsPerCol = (biomes.size + numCols - 1) / numCols
        val columns = mutableListOf<List<BiomeLine>>()
        var cursor = 0
        for (col in 0 until numCols) {
            if (cursor >= biomes.size) { columns.add(emptyList()); continue }
            var end = minOf(cursor + rowsPerCol, biomes.size)
            // never leave a mod heading stranded as the last line of a column
            if (end < biomes.size && end - 1 > cursor && biomes[end - 1].isHeader) end--
            columns.add(biomes.subList(cursor, end))
            cursor = end
        }

        val colWidths = columns.mapIndexed { i, col ->
            val biomeMaxW = col.maxOfOrNull { textRenderer.getWidth(if (it.isHeader) it.text else "  ${it.text}") } ?: 0
            if (i == 0) maxOf(textRenderer.getWidth(titleText), biomeMaxW) else biomeMaxW
        }

        val tooltipW = colWidths.sum() + colGap * (numCols - 1) + padding * 2
        // measured, not estimated: the heading nudge can make one column a line taller
        val maxRows = columns.maxOfOrNull { it.size } ?: 0
        val tooltipH = padding + (maxRows + 1) * lineHeight + padding

        var tx = mouseX + 12
        var ty = mouseY - 12
        if (tx + tooltipW > width - screenMargin) tx = mouseX - tooltipW - 4
        ty = ty.coerceIn(screenMargin, (height - tooltipH - screenMargin).coerceAtLeast(screenMargin))
        tx = tx.coerceIn(screenMargin, (width - tooltipW - screenMargin).coerceAtLeast(screenMargin))

        context.fill(tx, ty, tx + tooltipW, ty + tooltipH, bgColor)
        drawBorder(context, tx, ty, tooltipW, tooltipH, borderColor)

        context.drawText(textRenderer, titleText, tx + padding, ty + padding, titleColor, true)

        var colX = tx + padding
        for ((i, col) in columns.withIndex()) {
            var textY = ty + padding + lineHeight
            for (line in col) {
                if (line.isHeader) {
                    context.drawText(textRenderer, line.text, colX, textY, headerColor, true)
                } else {
                    context.drawText(textRenderer, "  ${line.text}", colX, textY, biomeColor, true)
                }
                textY += lineHeight
            }
            colX += colWidths[i] + colGap
        }
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
