package com.hunterboard

import com.cobblemon.mod.common.api.abilities.PotentialAbility
import com.cobblemon.mod.common.api.drop.ItemDropEntry
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class PokemonDetailScreen(
    private val species: Species,
    private val parent: Screen,
    private val allSpecies: List<Species>? = null,
    private val initialFormName: String? = null
) : Screen(Text.literal("Pokemon Detail")) {

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

    // Cached row bounds for hover detection
    private data class MoveRowBounds(val y: Int, val h: Int, val move: MoveTemplate)
    private var moveRowBounds = mutableListOf<MoveRowBounds>()
    private data class MoveClickBound(val x: Int, val y: Int, val w: Int, val h: Int, val move: MoveTemplate)
    private var moveClickBounds = mutableListOf<MoveClickBound>()
    private data class AbilityRowBounds(val x: Int, val y: Int, val w: Int, val h: Int, val ability: PotentialAbility)
    private var abilityRowBounds = mutableListOf<AbilityRowBounds>()

    // Model click bounds
    private var modelRenderX = 0
    private var modelRenderY = 0

    // Biome hover tooltip
    private var hoveredBiomeDetail: BiomeDetail? = null

    // Shiny toggle
    private var isShiny = false
    private var shinyBtnX = 0
    private var shinyBtnY = 0
    private val shinyBtnW = 14
    private val shinyBtnH = 14

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
    private data class EvoClickBound(val x: Int, val y: Int, val w: Int, val h: Int, val species: Species)
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

        rebuildModel()
    }

    private fun rebuildModel() {
        try {
            val formAspects = if (availableForms.isNotEmpty() && currentFormIndex < availableForms.size) {
                try { availableForms[currentFormIndex].aspects.toSet() } catch (_: Exception) { emptySet() }
            } else emptySet()
            val aspects = if (isShiny) formAspects + "shiny" else formAspects
            val pokemon = RenderablePokemon(species, aspects)
            modelWidget = ModelWidget(
                pX = 0, pY = 0,
                pWidth = MODEL_SIZE, pHeight = MODEL_SIZE,
                pokemon = pokemon,
                baseScale = 3.0f,
                rotationY = 325f,
                offsetY = -18.0,
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
    private fun getEffectiveSpawns(): List<SpawnEntry> {
        val form = currentForm()
        if (form != null) {
            val aspects = try { form.aspects } catch (_: Exception) { emptyList() }
            if (aspects.isNotEmpty()) {
                val formSpawns = SpawnData.getSpawns(species.name, aspects.first())
                if (formSpawns.isNotEmpty()) return formSpawns
            }
        }
        return SpawnData.getSpawns(species.name)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        hoveredBiomeDetail = null
        evoClickBounds.clear()
        megaClickBounds.clear()
        context.fill(0, 0, width, height, 0xAA000000.toInt())

        val panelWidth = (width * 0.8).toInt().coerceIn(380, 620)
        val panelX = (width - panelWidth) / 2
        val panelTop = 10
        val panelBottom = height - 10
        val panelHeight = panelBottom - panelTop

        context.fill(panelX, panelTop, panelX + panelWidth, panelBottom, 0xF0101010.toInt())
        drawBorder(context, panelX, panelTop, panelWidth, panelHeight, 0xFFFFAA00.toInt())

        // ========== HEADER ==========
        val backText: String = Translations.tr("\u2190 Back")
        val backHovered = mouseX >= panelX + 6 && mouseX <= panelX + 6 + textRenderer.getWidth(backText) + 4 &&
                          mouseY >= panelTop + 5 && mouseY <= panelTop + 17
        context.drawText(textRenderer, backText, panelX + 8, panelTop + 6,
            if (backHovered) 0xFFFFAA00.toInt() else 0xFFAAAAAA.toInt(), true)

        val displayName: String = Translations.dualSpeciesName(species.name)
        context.drawText(textRenderer, displayName,
            panelX + (panelWidth - textRenderer.getWidth(displayName)) / 2, panelTop + 6, 0xFFFFFFFF.toInt(), true)

        // F2: Prev/Next buttons around dex number
        val dexText = "#${String.format("%04d", species.nationalPokedexNumber)}"
        val dexW = textRenderer.getWidth(dexText)
        val hasPrev = allSpecies != null && currentIndex > 0
        val hasNext = allSpecies != null && currentIndex >= 0 && currentIndex < (allSpecies?.size ?: 0) - 1
        val prevText = "\u25C0"
        val nextText = "\u25B6"
        val navW = textRenderer.getWidth(prevText)
        val navRightEdge = panelX + panelWidth - 8

        // Layout: [◀] #0001 [▶] right-aligned
        val nextBtnX = navRightEdge - navW
        val dexTextX = nextBtnX - dexW - 4
        val prevBtnX = dexTextX - navW - 4
        val navY = panelTop + 6

        val prevHovered = hasPrev && mouseX >= prevBtnX && mouseX <= prevBtnX + navW && mouseY >= navY && mouseY <= navY + 10
        val nextHovered = hasNext && mouseX >= nextBtnX && mouseX <= nextBtnX + navW && mouseY >= navY && mouseY <= navY + 10
        context.drawText(textRenderer, prevText, prevBtnX, navY,
            if (prevHovered) 0xFFFFAA00.toInt() else if (hasPrev) 0xFFFFFFFF.toInt() else 0xFF333333.toInt(), true)
        context.drawText(textRenderer, dexText, dexTextX, navY, 0xFFFFFFFF.toInt(), true)
        context.drawText(textRenderer, nextText, nextBtnX, navY,
            if (nextHovered) 0xFFFFAA00.toInt() else if (hasNext) 0xFFFFFFFF.toInt() else 0xFF333333.toInt(), true)

        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, 0xFFFFAA00.toInt())

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
                    lHov -> 0xFFFFAA00.toInt()
                    canScrollLeft -> 0xFF888888.toInt()
                    else -> 0xFF333333.toInt()
                }
                context.drawText(textRenderer, "\u25C0", leftArrowX + 2, formTabY + 3, lColor, true)
                formTabLeftArrowBounds = intArrayOf(leftArrowX, formTabY, arrowW, 13)
            }

            // Draw tabs with scissor to clip overflow
            context.enableScissor(visibleLeft, formTabY, visibleRight, formTabY + 13)
            var ftx = visibleLeft - formTabScrollOffset
            for ((i, form) in availableForms.withIndex()) {
                val label = tabLabels[i]
                val tw = tabWidths[i]
                val isSelected = i == currentFormIndex
                val isHov = mouseX >= maxOf(ftx, visibleLeft) && mouseX <= minOf(ftx + tw, visibleRight) &&
                            mouseY >= formTabY && mouseY <= formTabY + 13

                val bg = when {
                    isSelected -> 0xFF2A2200.toInt()
                    isHov -> 0xFF252525.toInt()
                    else -> 0xFF1A1A1A.toInt()
                }
                context.fill(ftx, formTabY, ftx + tw, formTabY + 13, bg)
                val bc = if (isSelected) 0xFFFFAA00.toInt() else 0xFF444444.toInt()
                context.fill(ftx, formTabY, ftx + tw, formTabY + 1, bc)
                context.fill(ftx, formTabY, ftx + 1, formTabY + 13, bc)
                context.fill(ftx + tw - 1, formTabY, ftx + tw, formTabY + 13, bc)

                val tc = when {
                    isSelected -> 0xFFFFAA00.toInt()
                    isHov -> 0xFFDDDDDD.toInt()
                    else -> 0xFF888888.toInt()
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
                    rHov -> 0xFFFFAA00.toInt()
                    canScrollRight -> 0xFF888888.toInt()
                    else -> 0xFF333333.toInt()
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

        context.enableScissor(panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)

        var y = contentTop + 2 - scrollOffset
        val leftX = panelX + 8
        val halfW = (panelWidth - 20) / 2
        val midX = panelX + 10 + halfW + 4

        moveRowBounds.clear()
        moveClickBounds.clear()
        abilityRowBounds.clear()

        // ========== ROW 1: Model + Info (left) | Base Stats (right) ==========

        // --- LEFT: Model + Types + Abilities + EV Yield ---
        if (modelWidget != null) {
            try {
                modelRenderX = leftX
                modelRenderY = y - 4
                modelWidget!!.x = modelRenderX
                modelWidget!!.y = modelRenderY
                modelWidget!!.render(context, mouseX, mouseY, delta)
            } catch (_: Exception) {}
        }

        // Shiny toggle button
        shinyBtnX = leftX + (MODEL_SIZE - shinyBtnW) / 2
        shinyBtnY = y - 4 + MODEL_SIZE + 2
        val shinyHovered = mouseX >= shinyBtnX && mouseX <= shinyBtnX + shinyBtnW &&
                           mouseY >= shinyBtnY && mouseY <= shinyBtnY + shinyBtnH
        val shinyBg = when {
            isShiny && shinyHovered -> 0xFF3A3000.toInt()
            isShiny -> 0xFF2A2200.toInt()
            shinyHovered -> 0xFF303030.toInt()
            else -> 0xFF1E1E1E.toInt()
        }
        context.fill(shinyBtnX, shinyBtnY, shinyBtnX + shinyBtnW, shinyBtnY + shinyBtnH, shinyBg)
        val shinyBorder = if (isShiny) 0xFFFFAA00.toInt() else if (shinyHovered) 0xFF666666.toInt() else 0xFF444444.toInt()
        drawBorder(context, shinyBtnX, shinyBtnY, shinyBtnW, shinyBtnH, shinyBorder)
        val starColor = if (isShiny) 0xFFFFDD00.toInt() else 0xFF888888.toInt()
        val star = "\u2726"
        val starW = textRenderer.getWidth(star)
        context.drawText(textRenderer, star, shinyBtnX + (shinyBtnW - starW) / 2, shinyBtnY + 3, starColor, true)

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
        context.drawText(textRenderer, abilitiesLabel, infoX, leftY, 0xFFFFAA00.toInt(), true)
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
                val color = if (isHidden) 0xFFFFDD55.toInt() else 0xFFCCCCCC.toInt()
                context.drawText(textRenderer, abilityName, infoX + 4, leftY, color, true)
                var totalW = textRenderer.getWidth(abilityName)
                if (isHidden) {
                    val tagX = infoX + 4 + totalW + 4
                    context.drawText(textRenderer, "(H)", tagX, leftY, 0xFFAA8833.toInt(), true)
                    totalW += 4 + textRenderer.getWidth("(H)")
                }
                abilityRowBounds.add(AbilityRowBounds(infoX + 4, leftY, totalW, 10, pa))
                leftY += 10
            }
        } catch (_: Exception) {}
        leftY += 4

        // EV Yield
        val evLabel: String = Translations.tr("EV Yield")
        context.drawText(textRenderer, evLabel, infoX, leftY, 0xFFFFAA00.toInt(), true)
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
                infoX + 4, leftY, 0xFFCCCCCC.toInt(), true)
        } catch (_: Exception) {}
        leftY += 12

        // Egg Groups
        val eggGroupLabel: String = Translations.tr("Egg Groups")
        context.drawText(textRenderer, eggGroupLabel, infoX, leftY, 0xFFFFAA00.toInt(), true)
        leftY += 11
        try {
            val rawGroups = EggGroupData.getEggGroups(species.name)
                ?: species.eggGroups.map { it.name }
            val eggGroupText = if (rawGroups.isEmpty()) Translations.tr("None")
                else rawGroups.joinToString(", ") { Translations.formatEggGroup(it) }
            context.drawText(textRenderer, eggGroupText, infoX + 4, leftY, 0xFFCCCCCC.toInt(), true)
        } catch (_: Exception) {
            context.drawText(textRenderer, "?", infoX + 4, leftY, 0xFF666666.toInt(), true)
        }
        leftY += 12

        val leftEndY = maxOf(leftY, y + MODEL_SIZE + 6)

        // --- RIGHT: Base Stats (F4: use form overrides if available) ---
        val baseStatsLabel: String = Translations.tr("Base Stats")
        context.drawText(textRenderer, baseStatsLabel, midX, y, 0xFFFFAA00.toInt(), true)
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
            context.drawText(textRenderer, label, midX, statsY, 0xFFBBBBBB.toInt(), true)
            context.fill(barX, statsY + 1, barX + barMaxW, statsY + 8, 0xFF222222.toInt())
            val fillW = (value * barMaxW / STAT_BAR_MAX).coerceIn(1, barMaxW)
            context.fill(barX, statsY + 1, barX + fillW, statsY + 8, getStatColor(value))
            context.drawText(textRenderer, "$value", barX + barMaxW + 4, statsY, 0xFFFFFFFF.toInt(), true)
            statsY += 12
        }

        val total = statEntries.sumOf { effectiveStats[it.first] ?: 0 }
        val totalLabel: String = Translations.tr("Total")
        context.drawText(textRenderer, totalLabel, midX, statsY, 0xFFBBBBBB.toInt(), true)
        context.drawText(textRenderer, "$total", barX + barMaxW + 4, statsY, 0xFFFFFFFF.toInt(), true)
        statsY += 12

        y = maxOf(leftEndY, statsY) + 4

        // ========== SEPARATOR ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
        y += 6

        // ========== TYPE CHART (full width) ==========
        val chartLabel: String = Translations.tr("Type Chart")
        context.drawText(textRenderer, chartLabel, leftX, y, 0xFFFFAA00.toInt(), true)
        y += 13
        try {
            val chartForm = currentForm()
            val chartPrimary = try { chartForm?.primaryType ?: species.primaryType } catch (_: Exception) { species.primaryType }
            val chartSecondary = try { chartForm?.secondaryType ?: species.secondaryType } catch (_: Exception) { null }
            y = renderTypeChart(context, y, leftX, panelWidth - 20, chartPrimary.name.lowercase(), chartSecondary?.name?.lowercase())
        } catch (_: Exception) {}
        y += 4

        // ========== SEPARATOR ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
        y += 6

        // ========== EVOLUTION (left) | DROPPED ITEMS (right) ==========
        val evoTree = EvolutionData.getEvolutionTree(species)
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
                context.drawText(textRenderer, evoLabel, leftX, evoEndY, 0xFFFFAA00.toInt(), true)
                evoEndY += 13
                evoEndY = renderEvolutionChain(context, evoTree!!, leftX + 4, evoEndY, halfW - 12, mouseX, mouseY, contentTop, contentBottom)
            }

            // Mega evolutions section
            if (hasMega) {
                if (hasEvo) evoEndY += 4
                val megaHeader: String = Translations.tr("Mega Evolutions")
                context.drawText(textRenderer, megaHeader, leftX + 4, evoEndY, 0xFFFFAA00.toInt(), true)
                evoEndY += 12
                for (form in megaForms) {
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
                    evoEndY += 12
                }
            }

            // Dropped Items on the right
            val dropsLabel: String = Translations.tr("Dropped Items")
            context.drawText(textRenderer, dropsLabel, midX, row2StartY, 0xFFFFAA00.toInt(), true)
            val dropY = renderDroppedItems(context, midX, row2StartY + 13, halfW)

            // Vertical separator
            context.fill(panelX + 10 + halfW, row2StartY, panelX + 10 + halfW + 1,
                maxOf(evoEndY, dropY), 0xFF333333.toInt())

            y = maxOf(evoEndY, dropY) + 4
        } else {
            // No evolution, no mega — Dropped Items takes full width
            val dropsLabel: String = Translations.tr("Dropped Items")
            context.drawText(textRenderer, dropsLabel, leftX, y, 0xFFFFAA00.toInt(), true)
            y = renderDroppedItems(context, leftX, y + 13, panelWidth - 20) + 4
        }

        // Separator
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
        y += 6

        // ========== SPAWN CONDITIONS (full width) ==========
        val spawnLabel: String = Translations.tr("Spawn Conditions")
        context.drawText(textRenderer, spawnLabel, leftX, y, 0xFFFFAA00.toInt(), true)
        var spawnY = y + 13
        val fullContentW = panelWidth - 20

        val spawns = getEffectiveSpawns()
        if (spawns.isNotEmpty()) {
            val lvMin = spawns.minOf { it.lvMin }
            val lvMax = spawns.maxOf { it.lvMax }
            val lvPrefix: String = Translations.tr("Lv.")
            context.drawText(textRenderer, "$lvPrefix $lvMin-$lvMax", leftX + 4, spawnY, 0xFFBBBBBB.toInt(), true)
            spawnY += 13

            val seen = mutableSetOf<String>()
            for (spawn in spawns) {
                val key = "${spawn.biomes}|${spawn.time}|${spawn.weather}|${spawn.structures}|${spawn.canSeeSky}|${spawn.bucket}|${spawn.spawnContext}|${spawn.minY}|${spawn.maxY}|${spawn.minSkyLight}|${spawn.maxSkyLight}|${spawn.presets}"
                if (key in seen) continue
                seen.add(key)

                // Rarity tag + weight
                val rarityText: String = Translations.formatRarity(spawn.bucket)
                val rarityColor = getRarityColor(spawn.bucket)
                val weightStr = if (spawn.weight != null) " (${Translations.tr("Weight:")} ${String.format("%.1f", spawn.weight)})" else ""
                val rarityTagText = "[$rarityText]$weightStr "
                context.drawText(textRenderer, rarityTagText, leftX + 8, spawnY, rarityColor, true)
                val rarityTagW = textRenderer.getWidth(rarityTagText)

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
                        context.drawText(textRenderer, anyText, leftX + 12, spawnY, 0xFF999999.toInt(), true)
                        spawnY += 10
                    } else {
                        spawnY = renderBiomesInline(context, spawn.biomeDetails, leftX + 12, spawnY,
                            fullContentW - 12, mouseX, mouseY, contentTop, contentBottom)
                    }
                } else {
                    if (spawn.biomeDetails.isEmpty()) {
                        val anyText: String = Translations.tr("Any")
                        context.drawText(textRenderer, anyText, leftX + 8 + rarityTagW, spawnY, 0xFF999999.toInt(), true)
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
                if (spawn.moonPhase != null) {
                    val moonLabel: String = Translations.tr("Moon:")
                    extras.add("$moonLabel ${Translations.formatMoonPhase(spawn.moonPhase)}")
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

                spawnY += 2
            }
        } else {
            val noSpawnText: String = Translations.tr("No spawn data")
            context.drawText(textRenderer, noSpawnText, leftX + 4, spawnY, 0xFF666666.toInt(), true)
            spawnY += 12
        }

        y = spawnY + 4

        // ========== SEPARATOR ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
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
                isSelected -> 0xFF2A2200.toInt()
                isTabHovered -> 0xFF252525.toInt()
                else -> 0xFF1A1A1A.toInt()
            }
            context.fill(tabX, tabY, tabX + tw, tabY + 14, bg)

            val borderColor = if (isSelected) 0xFFFFAA00.toInt() else 0xFF444444.toInt()
            context.fill(tabX, tabY, tabX + tw, tabY + 1, borderColor)
            context.fill(tabX, tabY, tabX + 1, tabY + 14, borderColor)
            context.fill(tabX + tw - 1, tabY, tabX + tw, tabY + 14, borderColor)
            if (isSelected) {
                context.fill(tabX + 1, tabY + 13, tabX + tw - 1, tabY + 14, 0xF0101010.toInt())
            }

            val textColor = when {
                isSelected -> 0xFFFFAA00.toInt()
                isTabHovered -> 0xFFDDDDDD.toInt()
                else -> 0xFF888888.toInt()
            }
            context.drawText(textRenderer, label, tabX + 6, tabY + 3, textColor, true)

            tabBounds[i] = intArrayOf(tabX, tabY, tw, 14)
            tabX += tw + 2
        }

        y = tabY + 14
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF444444.toInt())
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

        if (moves.isEmpty()) {
            val emptyText: String = Translations.noMovesText(tabKeys[selectedTab])
            context.drawText(textRenderer, emptyText,
                panelX + (panelWidth - textRenderer.getWidth(emptyText)) / 2, y + 6, 0xFF666666.toInt(), true)
            y += 22
        } else {
            y = renderMoveTable(context, moves, y, panelX, panelWidth, leftX, showLevel, mouseX, mouseY, contentTop, contentBottom)
        }

        contentHeight = y + scrollOffset - contentTop + 8

        context.disableScissor()

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
        // Biome tooltip is rendered after footer for proper Z-ordering

        // Scrollbar
        sbTrackX = panelX + panelWidth - 5
        sbContentTop = contentTop
        sbContentBottom = contentBottom
        if (contentHeight > contentAreaHeight && contentAreaHeight > 0) {
            context.fill(sbTrackX, contentTop, sbTrackX + 3, contentBottom, 0xFF1A1A1A.toInt())
            sbThumbHeight = maxOf(15, contentAreaHeight * contentAreaHeight / contentHeight)
            val maxScroll = contentHeight - contentAreaHeight
            sbThumbY = contentTop + (scrollOffset * (contentAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, 0xFFFFAA00.toInt())
        } else {
            sbThumbHeight = 0
        }

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint: String = Translations.tr("ESC / Click Back to return")
        context.drawText(textRenderer, hint,
            panelX + (panelWidth - textRenderer.getWidth(hint)) / 2, panelBottom - 10, 0xFF555555.toInt(), true)

        // Biome tooltip (rendered last, on top of everything)
        if (hoveredBiomeDetail != null && hoveredBiomeDetail!!.tagId != null) {
            val resolved = BiomeTagResolver.resolve(hoveredBiomeDetail!!.tagId!!)
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

    private fun renderDroppedItems(context: DrawContext, startX: Int, startY: Int, maxWidth: Int): Int {
        var dropY = startY
        try {
            val entries = species.drops.entries
            if (entries.isEmpty()) {
                val noneText: String = Translations.tr("None")
                context.drawText(textRenderer, noneText, startX + 4, dropY, 0xFF666666.toInt(), true)
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
                        val qty = entry.quantityRange
                        val qtyText = if (qty != null && qty.first != qty.last) "${qty.first}-${qty.last}" else "${entry.quantity}"
                        context.drawText(textRenderer, "${item.name.string} ${entry.percentage.toInt()}%",
                            textStartX, dropY, 0xFFCCCCCC.toInt(), true)
                        val qtyLabel: String = Translations.tr("Qty:")
                        context.drawText(textRenderer, "$qtyLabel $qtyText",
                            textStartX, dropY + 10, 0xFF999999.toInt(), true)
                        dropY += 22
                    }
                }
            }
        } catch (_: Exception) {
            val naText: String = Translations.tr("N/A")
            context.drawText(textRenderer, naText, startX + 4, dropY, 0xFF666666.toInt(), true)
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
                    context.drawText(textRenderer, arrowLine, startX, y, 0xFF888888.toInt(), true)
                    y += 10
                }
                renderEvoName(context, node, startX, y, mouseX, mouseY, visibleTop, visibleBottom)
                y += 12
            } else {
                // Multiple evolutions at this stage — vertical list
                for ((branchIdx, node) in stage.withIndex()) {
                    val isLast = branchIdx == stage.lastIndex
                    val prefix = if (isLast) "\u2514 " else "\u251C "
                    context.drawText(textRenderer, prefix, startX + 2, y, 0xFF555555.toInt(), true)
                    val nameX = startX + 2 + textRenderer.getWidth(prefix)
                    val endX = renderEvoName(context, node, nameX, y, mouseX, mouseY, visibleTop, visibleBottom)

                    val condText = Translations.formatEvoCondition(node.condition)
                    if (condText.isNotEmpty()) {
                        context.drawText(textRenderer, " \u00b7 $condText", endX, y, 0xFF888888.toInt(), true)
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
        val name: String = node.species?.translatedName?.string ?: node.speciesName.replaceFirstChar { it.uppercase() }
        val nameW = textRenderer.getWidth(name)
        val hovered = node.species != null && !node.isCurrent &&
                mouseX >= x && mouseX <= x + nameW &&
                mouseY >= y && mouseY <= y + 10 &&
                mouseY >= visibleTop && mouseY <= visibleBottom

        val color = when {
            node.isCurrent -> 0xFFFFAA00.toInt()
            hovered -> 0xFFFFDD55.toInt()
            else -> 0xFFCCCCCC.toInt()
        }
        context.drawText(textRenderer, name, x, y, color, true)

        // Underline for clickable names
        if (node.species != null && !node.isCurrent) {
            val ulColor = if (hovered) 0xFFFFDD55.toInt() else 0xFF555555.toInt()
            context.fill(x, y + 9, x + nameW, y + 10, ulColor)
            evoClickBounds.add(EvoClickBound(x, y, nameW, 10, node.species))
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

    // ==================== Move Rendering ====================

    data class MoveEntry(val level: Int, val move: MoveTemplate)

    private fun getLevelUpMoves(): List<MoveEntry> {
        return try {
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
            val apiMoves = species.moves.tmMoves.map { MoveEntry(-1, it) }.sortedBy { it.move.displayName.string }
            if (apiMoves.isNotEmpty()) return apiMoves
            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.tm.map { MoveEntry(-1, it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun getEggMoves(): List<MoveEntry> {
        return try {
            val apiMoves = species.moves.eggMoves.map { MoveEntry(-1, it) }.sortedBy { it.move.displayName.string }
            if (apiMoves.isNotEmpty()) return apiMoves
            val fallback = MoveData.getMoves(species.name) ?: return emptyList()
            fallback.egg.map { MoveEntry(-1, it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun getTutorMoves(): List<MoveEntry> {
        return try {
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
        contentTop: Int, contentBottom: Int
    ): Int {
        var y = startY
        val rowH = 14
        val tableX = panelX + 8
        val colLvl = tableX
        val colName = if (showLevel) colLvl + 24 else colLvl
        val colType = colName + 78
        val typeBadgeW = 52
        val colCat = colType + typeBadgeW + 6
        val colPow = colCat + 50
        val colAcc = colPow + 30
        val colPP = colAcc + 30

        val headerColor = 0xFF777777.toInt()
        if (showLevel) context.drawText(textRenderer, Translations.tr("Lv"), colLvl, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Move"), colName, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Type"), colType, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Cat."), colCat, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Pow"), colPow, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("Acc"), colAcc, y, headerColor, true)
        context.drawText(textRenderer, Translations.tr("PP"), colPP, y, headerColor, true)
        y += 11
        context.fill(tableX, y, panelX + panelWidth - 8, y + 1, 0xFF2A2A2A.toInt())
        y += 4

        for ((index, entry) in moves.withIndex()) {
            val move = entry.move
            val rowTop = y
            try {
                val rowHovered = mouseX >= tableX && mouseX <= panelX + panelWidth - 8 &&
                                 mouseY >= rowTop && mouseY < rowTop + rowH &&
                                 mouseY >= contentTop && mouseY <= contentBottom
                if (rowHovered) {
                    context.fill(tableX, rowTop, panelX + panelWidth - 8, rowTop + rowH, 0xFF1E1E1E.toInt())
                }
                if (showLevel) {
                    context.drawText(textRenderer, "${entry.level}", colLvl, y + 2, 0xFF999999.toInt(), true)
                }
                val moveDisplayName = move.displayName.string
                val moveNameW = textRenderer.getWidth(moveDisplayName)
                val nameHovered = mouseX >= colName && mouseX <= colName + moveNameW &&
                                  mouseY >= rowTop && mouseY < rowTop + rowH &&
                                  mouseY >= contentTop && mouseY <= contentBottom
                val nameColor = if (nameHovered) 0xFFFFAA00.toInt() else 0xFFDDDDDD.toInt()
                context.drawText(textRenderer, moveDisplayName, colName, y + 2, nameColor, true)
                if (nameHovered) {
                    context.fill(colName, y + 11, colName + moveNameW, y + 12, 0xFFFFAA00.toInt())
                }
                moveClickBounds.add(MoveClickBound(colName, rowTop, moveNameW, rowH, move))
                val typeName = move.elementalType.name.replaceFirstChar { it.uppercase() }
                drawMiniTypeBadge(context, colType, y + 1, typeName)
                val catName: String = move.damageCategory.displayName.string
                context.drawText(textRenderer, catName, colCat, y + 2, getCategoryColor(move.damageCategory.name), true)
                val power = move.power.toInt()
                context.drawText(textRenderer, if (power > 0) "$power" else "-", colPow, y + 2, 0xFFCCCCCC.toInt(), true)
                val acc = move.accuracy
                context.drawText(textRenderer, if (acc > 0) "${acc.toInt()}" else "-", colAcc, y + 2, 0xFFCCCCCC.toInt(), true)
                context.drawText(textRenderer, "${move.pp}", colPP, y + 2, 0xFFCCCCCC.toInt(), true)
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

    private fun drawMiniTypeBadge(context: DrawContext, x: Int, y: Int, typeName: String) {
        val badgeW = 50
        val badgeH = 11
        val color = getTypeColor(typeName)
        context.fill(x, y, x + badgeW, y + badgeH, color)
        val textW = textRenderer.getWidth(typeName)
        val textX = x + (badgeW - textW) / 2
        context.drawText(textRenderer, typeName, textX, y + 1, 0xFFFFFFFF.toInt(), true)
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
                context.drawText(textRenderer, displayName, textX, y + 2, 0xFFFFFFFF.toInt(), true)
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
                    mult == 0f -> 0xFF777777.toInt()   // immune — gray
                    mult < 1f  -> 0xFF44CC44.toInt()   // resistant — green
                    mult > 1f  -> 0xFFFF5533.toInt()   // weak — red
                    else       -> 0xFF444444.toInt()   // neutral — dim
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
            else -> 0xFF888888.toInt()
        }
    }

    private fun getCategoryColor(name: String): Int {
        return when (name.lowercase()) {
            "physical" -> 0xFFFF6644.toInt(); "special" -> 0xFF6688FF.toInt()
            "status" -> 0xFFAABBCC.toInt(); else -> 0xFFCCCCCC.toInt()
        }
    }

    private fun getStatColor(value: Int): Int {
        return when {
            value >= 150 -> 0xFF00C8B0.toInt(); value >= 120 -> 0xFF55CC55.toInt()
            value >= 90 -> 0xFF88BB33.toInt(); value >= 60 -> 0xFFFFCC33.toInt()
            value >= 30 -> 0xFFFF8844.toInt(); else -> 0xFFFF4444.toInt()
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
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

            val panelWidth = (width * 0.8).toInt().coerceIn(380, 620)
            val panelX = (width - panelWidth) / 2
            val panelTop = 10

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
            val navRightEdge = panelX + panelWidth - 8
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
                rebuildModel()
                return true
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
                    client?.setScreen(PokemonDetailScreen(evo.species, parent, allSpecies))
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
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
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
            else -> 0xFF888888.toInt()
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
                isHovered -> 0xFFFFAA00.toInt()
                biome.tagId != null -> 0xFFBBBBBB.toInt()
                else -> 0xFF999999.toInt()
            }
            context.drawText(textRenderer, Translations.biomeName(biome), x, y, color, true)
            if (biome.tagId != null) {
                val underlineColor = if (isHovered) 0xFFFFAA00.toInt() else 0xFF444444.toInt()
                context.fill(x, y + 9, x + nameW, y + 10, underlineColor)
            }
            x += nameW
            if (separator.isNotEmpty()) {
                context.drawText(textRenderer, separator, x, y, 0xFF999999.toInt(), true)
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
        biomes: List<String>,
        detail: BiomeDetail,
        mouseX: Int,
        mouseY: Int
    ) {
        val lineHeight = 10
        val padding = 6
        val titleText = Translations.biomeName(detail)
        val titleColor = 0xFFFFAA00.toInt()
        val biomeColor = 0xFFCCCCCC.toInt()
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
        val columns = (0 until numCols).map { col ->
            val start = col * rowsPerCol
            biomes.subList(start, minOf(start + rowsPerCol, biomes.size))
        }

        val colWidths = columns.mapIndexed { i, col ->
            val biomeMaxW = col.maxOfOrNull { textRenderer.getWidth("  $it") } ?: 0
            if (i == 0) maxOf(textRenderer.getWidth(titleText), biomeMaxW) else biomeMaxW
        }

        val tooltipW = colWidths.sum() + colGap * (numCols - 1) + padding * 2
        val tooltipH = padding + (rowsPerCol + 1) * lineHeight + padding

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
            for (name in col) {
                context.drawText(textRenderer, "  $name", colX, textY, biomeColor, true)
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
