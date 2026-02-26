package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen

import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import java.net.URI

class SpawnInfoScreen : Screen(Text.literal("Spawn Info")) {

    companion object {
        private const val MODEL_SIZE = 36
        private val OPTIONS_ICON = Identifier.of("hunterboard", "img/option.png")
    }

    private var scrollOffset = 0
    private var contentHeight = 0
    private var modelWidgets: List<ModelWidget?> = emptyList()
    private var hoveredBiomeDetail: BiomeDetail? = null

    // Option button bounds
    private val optBtnSize = 24
    private var optBtnX = 0
    private var optBtnY = 0

    // Chat button bounds
    private var chatBtnX = 0
    private var chatBtnY = 0
    private var chatBtnW = 0
    private val chatBtnH = 14

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

    // Toggle button bounds per card (index -> bounds)
    private val TOGGLE_SIZE = 20
    private data class ToggleBounds(val x: Int, val y: Int, val index: Int)
    private var toggleButtons: MutableList<ToggleBounds> = mutableListOf()

    // Clickable pokemon name/icon bounds per card
    private data class PokemonClickBounds(val x: Int, val y: Int, val w: Int, val h: Int, val index: Int)
    private var pokemonClickAreas: MutableList<PokemonClickBounds> = mutableListOf()

    // Cached ball ItemStacks
    private var cachedBallStacks: Map<String, ItemStack> = emptyMap()

    // Cached card heights (avoid recalculating every frame)
    private var cachedCardHeights: List<Int> = emptyList()

    // Cached sprite identifiers for pixel art mode
    private var cachedSpriteIds: List<Identifier?> = emptyList()
    private var lastSpriteLoadedCount: Int = -1

    // Scrollbar drag state
    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0
    private var scrollbarDragStartOffset = 0
    // Scrollbar geometry (updated each frame)
    private var sbTrackX = 0
    private var sbContentTop = 0
    private var sbContentBottom = 0
    private var sbThumbY = 0
    private var sbThumbHeight = 0

    override fun init() {
        super.init()
        SpawnData.ensureLoaded()
        buildModelWidgets()
        buildBallCache()
        buildCardHeightCache()
        buildSpriteCache()
    }

    private fun buildSpriteCache() {
        cachedSpriteIds = BoardState.targets.map { SpriteHelper.getSpriteIdentifier(it.speciesId) }
    }

    private fun buildCardHeightCache() {
        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val cardWidth = panelWidth - 12
        val maxTextW = cardWidth - 6 - MODEL_SIZE - 6 - 8
        val visibleTargets = BoardState.targets.take(ModConfig.maxHunts())
        cachedCardHeights = visibleTargets.map { target ->
            val spawns = SpawnData.getSpawns(target.speciesId)
            val cardContentH = calculateCardContentHeight(spawns, maxTextW)
            maxOf(cardContentH, MODEL_SIZE + 12)
        }
    }

    private fun buildBallCache() {
        val stacks = mutableMapOf<String, ItemStack>()
        for (target in BoardState.targets) {
            if (target.ballId.isNotEmpty() && target.ballId !in stacks) {
                try {
                    val stack = ItemStack(Registries.ITEM.get(Identifier.of(target.ballId)))
                    if (!stack.isEmpty) stacks[target.ballId] = stack
                } catch (_: Exception) {}
            }
        }
        cachedBallStacks = stacks
    }

    private fun buildModelWidgets() {
        val widgets = mutableListOf<ModelWidget?>()
        for (target in BoardState.targets) {
            try {
                val species = PokemonSpecies.getByName(target.speciesId)
                if (species != null) {
                    val pokemon = RenderablePokemon(species, target.aspects)
                    widgets.add(ModelWidget(
                        pX = 0, pY = 0,
                        pWidth = MODEL_SIZE, pHeight = MODEL_SIZE,
                        pokemon = pokemon,
                        baseScale = 1.3f,
                        rotationY = 325f,
                        offsetY = -10.0
                    ))
                } else {
                    widgets.add(null)
                }
            } catch (e: Exception) {
                widgets.add(null)
            }
        }
        modelWidgets = widgets
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

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Rebuild sprite cache when new sprites finish downloading
        if (ModConfig.usePixelArt && SpriteHelper.loadedCount != lastSpriteLoadedCount) {
            buildSpriteCache()
            lastSpriteLoadedCount = SpriteHelper.loadedCount
        }
        hoveredBiomeDetail = null
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
        val title: String = Translations.tr("\u2726 Spawn Info \u2726")
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 6, ModConfig.accentColor(), true)

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        val closeHovered = mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2 && mouseY <= closeY + 11
        context.drawText(textRenderer, "\u2715", closeX, closeY, if (closeHovered) 0xFFFF5555.toInt() else 0xFF888888.toInt(), true)

        // Options button (attached to panel top-right corner, outside)
        optBtnX = panelX + panelWidth + 2
        optBtnY = panelTop
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

        // Chat button (left side of header, inside panel)
        val chatLabel: String = Translations.tr("Send to Chat")
        chatBtnW = textRenderer.getWidth(chatLabel) + 8
        chatBtnX = panelX + 6
        chatBtnY = panelTop + 4
        val uncaughtCount = BoardState.targets.take(ModConfig.maxHunts()).count { !it.isCaught }
        if (uncaughtCount > 0) {
            val chatHovered = mouseX >= chatBtnX && mouseX <= chatBtnX + chatBtnW &&
                              mouseY >= chatBtnY && mouseY <= chatBtnY + chatBtnH
            val chatBg = if (chatHovered) 0xFF2A2A00.toInt() else 0xFF1A1A1A.toInt()
            context.fill(chatBtnX, chatBtnY, chatBtnX + chatBtnW, chatBtnY + chatBtnH, chatBg)
            val chatBorder = if (chatHovered) ModConfig.accentColor() else 0xFF444444.toInt()
            drawBorder(context, chatBtnX, chatBtnY, chatBtnW, chatBtnH, chatBorder)
            val chatColor = if (chatHovered) ModConfig.accentColor() else 0xFFFFFFFF.toInt()
            context.drawText(textRenderer, chatLabel, chatBtnX + 4, chatBtnY + 3, chatColor, true)
        }

        // Gold separator + shadow
        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, ModConfig.accentColor())
        context.fill(panelX + 6, panelTop + 19, panelX + panelWidth - 6, panelTop + 20, 0xFF442200.toInt())

        // --- Scrollable content (no more buttons row) ---
        val contentTop = panelTop + 24
        val contentBottom = panelBottom - 16
        val contentAreaHeight = contentBottom - contentTop

        context.enableScissor(panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)

        val cardX = panelX + 6
        val cardWidth = panelWidth - 12
        val textContentX = cardX + 6 + MODEL_SIZE + 6
        val maxTextW = cardWidth - 6 - MODEL_SIZE - 6 - 8

        var y = contentTop + 2 - scrollOffset

        var hoveredBallName: String? = null
        toggleButtons.clear()
        pokemonClickAreas.clear()

        // Respect rank: only show up to maxHunts targets
        val visibleTargets = BoardState.targets.take(ModConfig.maxHunts())

        if (SpawnData.isLoading) {
            val loadingText: String = Translations.tr("Loading spawn data...")
            context.drawText(textRenderer, loadingText, cardX + 10, y + 10, 0xFFAAAAAA.toInt(), true)
            contentHeight = 30
        } else if (!BoardState.hasTargets()) {
            val noTargetsText: String = Translations.tr("No targets on board")
            context.drawText(textRenderer, noTargetsText, cardX + 10, y + 10, 0xFFAAAAAA.toInt(), true)
            contentHeight = 30
        } else {
            var totalHeight = 2

            for ((index, target) in visibleTargets.withIndex()) {
                val spawns = SpawnData.getSpawns(target.speciesId)
                val mainRarity = spawns.firstOrNull()?.bucket ?: ""
                val mainRarityColor = getRarityColor(mainRarity)

                // Use cached card height
                val cardHeight = cachedCardHeights.getOrElse(index) {
                    val cardContentH = calculateCardContentHeight(spawns, maxTextW)
                    maxOf(cardContentH, MODEL_SIZE + 12)
                }

                // Skip rendering if card is fully off-screen (but still track position)
                if (y + cardHeight < contentTop || y > contentBottom) {
                    toggleButtons.add(ToggleBounds(-1000, -1000, index))
                    y += cardHeight + 5
                    totalHeight += cardHeight + 5
                    continue
                }

                // Card background
                val cardBg = if (target.isCaught) 0xFF201414.toInt() else 0xFF1A1A1A.toInt()
                context.fill(cardX, y, cardX + cardWidth, y + cardHeight, cardBg)

                // Rarity left border (3px)
                context.fill(cardX, y, cardX + 3, y + cardHeight, mainRarityColor)

                // Subtle top edge highlight
                context.fill(cardX + 3, y, cardX + cardWidth, y + 1, 0xFF282828.toInt())

                // Bottom edge shadow
                context.fill(cardX, y + cardHeight - 1, cardX + cardWidth, y + cardHeight, 0xFF0A0A0A.toInt())

                // Pokemon icon: sprite or 3D model
                val iconX = cardX + 4
                val iconY = y - 2
                val spriteId = if (ModConfig.usePixelArt) cachedSpriteIds.getOrNull(index) else null
                if (spriteId != null) {
                    val scaledSize = (MODEL_SIZE * 1.4).toInt()
                    val spriteOffset = (scaledSize - MODEL_SIZE) / 2
                    context.drawTexture(spriteId, iconX - spriteOffset, iconY - spriteOffset, 0f, 0f, scaledSize, scaledSize, scaledSize, scaledSize)
                } else if (index < modelWidgets.size) {
                    val widget = modelWidgets[index]
                    if (widget != null) {
                        try {
                            widget.x = iconX
                            widget.y = iconY
                            widget.render(context, mouseX, mouseY, delta)
                        } catch (_: Exception) {}
                    }
                }

                // Red cross overlay on caught Pokemon (rendered in front)
                if (target.isCaught) {
                    context.matrices.push()
                    context.matrices.translate(0.0, 0.0, 200.0)
                    for (i in 0 until MODEL_SIZE step 2) {
                        val px1 = iconX + i
                        val py1 = iconY + i
                        context.fill(px1, py1, px1 + 3, py1 + 3, 0xCCFF3333.toInt())
                        val px2 = iconX + MODEL_SIZE - i - 3
                        context.fill(px2, py1, px2 + 3, py1 + 3, 0xCCFF3333.toInt())
                    }
                    context.matrices.pop()
                }

                var textY = y + 6

                // Pokemon name (translated)
                val nameColor = if (target.isCaught) 0xFFFF5555.toInt() else 0xFFFFFFFF.toInt()
                val statusIcon = if (target.isCaught) "\u2713 " else ""
                val pokeName: String = Translations.pokemonName(target.speciesId)
                val nameText = "$statusIcon$pokeName"
                // Underline name on hover to indicate clickable
                val nameTextW = textRenderer.getWidth(nameText)
                val pokeClickX = cardX + 4
                val pokeClickW = (textContentX + nameTextW) - pokeClickX
                val pokeClickY = y
                val pokeClickH = MODEL_SIZE
                val nameHovered = mouseX >= pokeClickX && mouseX <= pokeClickX + pokeClickW &&
                        mouseY >= pokeClickY && mouseY <= pokeClickY + pokeClickH &&
                        mouseY >= contentTop && mouseY <= contentBottom
                val displayNameColor = if (nameHovered) ModConfig.accentColor() else nameColor
                context.drawText(textRenderer, nameText, textContentX, textY, displayNameColor, true)
                if (nameHovered) {
                    context.fill(textContentX, textY + 10, textContentX + nameTextW, textY + 11, ModConfig.accentColor())
                }
                pokemonClickAreas.add(PokemonClickBounds(pokeClickX, pokeClickY, pokeClickW, pokeClickH, index))

                // Ball icon next to name
                val ballStack = cachedBallStacks[target.ballId]
                if (ballStack != null) {
                    val nameWidth = textRenderer.getWidth(nameText)
                    val ballIconX = textContentX + nameWidth + 4
                    val ballIconY = textY - 4
                    context.drawItem(ballStack, ballIconX, ballIconY)

                    if (mouseX >= ballIconX && mouseX <= ballIconX + 16 &&
                        mouseY >= ballIconY && mouseY <= ballIconY + 16 &&
                        mouseY >= contentTop && mouseY <= contentBottom) {
                        hoveredBallName = Translations.ballName(target.ballId)
                    }
                }

                textY += 13

                if (spawns.isNotEmpty()) {
                    // Level range
                    val lvMin = spawns.minOf { it.lvMin }
                    val lvMax = spawns.maxOf { it.lvMax }
                    val lvPrefix: String = Translations.tr("Lv.")
                    val lvText = "$lvPrefix $lvMin-$lvMax"
                    context.drawText(textRenderer, lvText, textContentX, textY, 0xFFBBBBBB.toInt(), true)
                    textY += 13

                    // Spawn conditions - with rarity per line
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
                        context.drawText(textRenderer, rarityTagText, textContentX + 4, textY, rarityColor, true)
                        val rarityTagW = textRenderer.getWidth(rarityTagText)

                        // Presets + Context tag
                        val tags = mutableListOf<String>()
                        val meaningfulPresets = spawn.presets.filter { it != "natural" }
                        if (meaningfulPresets.isNotEmpty()) {
                            tags.addAll(meaningfulPresets.map { Translations.formatPreset(it) })
                        }
                        val ctxStr = Translations.formatSpawnContext(spawn.spawnContext)
                        if (ctxStr.isNotEmpty()) tags.add(ctxStr)
                        if (tags.isNotEmpty()) {
                            val tagText = tags.joinToString(", ")
                            context.drawText(textRenderer, tagText, textContentX + 4 + rarityTagW, textY, 0xFFAA8855.toInt(), true)
                            textY += 10
                            if (spawn.biomeDetails.isEmpty()) {
                                val anyText: String = Translations.tr("Any")
                                context.drawText(textRenderer, anyText, textContentX + 8, textY, 0xFF999999.toInt(), true)
                                textY += 10
                            } else {
                                textY = renderBiomesInline(context, spawn.biomeDetails, textContentX + 8, textY,
                                    maxTextW - 12, mouseX, mouseY, contentTop, contentBottom)
                            }
                        } else {
                            // Biomes (inline after rarity tag)
                            if (spawn.biomeDetails.isEmpty()) {
                                val anyText: String = Translations.tr("Any")
                                context.drawText(textRenderer, anyText, textContentX + 4 + rarityTagW, textY, 0xFF999999.toInt(), true)
                                textY += 10
                            } else {
                                textY = renderBiomesInline(context, spawn.biomeDetails, textContentX + 4 + rarityTagW, textY,
                                    maxTextW - 8 - rarityTagW, mouseX, mouseY, contentTop, contentBottom)
                            }
                        }

                        // Excluded biomes
                        if (spawn.excludedBiomes.isNotEmpty()) {
                            val exclLabel: String = Translations.tr("Excluded:")
                            val exclNames = spawn.excludedBiomes.joinToString(", ") { Translations.biomeName(it) }
                            context.drawText(textRenderer, "  \u2716 $exclLabel $exclNames", textContentX + 4, textY, 0xFFAA5555.toInt(), true)
                            textY += 10
                        }

                        // Structures
                        if (spawn.structures.isNotEmpty()) {
                            val structText = "\u2302 " + spawn.structures.joinToString(", ")
                            context.drawText(textRenderer, structText, textContentX + 4, textY, 0xFFCC8844.toInt(), true)
                            textY += 10
                        }

                        // Time / Weather / Sky
                        val timeStr: String = Translations.formatTime(spawn.time)
                        val weatherStr: String = Translations.formatWeather(spawn.weather)
                        val skyStr: String = Translations.formatSky(spawn.canSeeSky)
                        context.drawText(textRenderer, "  $timeStr | $weatherStr$skyStr", textContentX + 4, textY, 0xFF707070.toInt(), true)
                        textY += 10

                        // Height, Light, Moon
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
                            context.drawText(textRenderer, "  ${extras.joinToString(" | ")}", textContentX + 4, textY, 0xFF887755.toInt(), true)
                            textY += 10
                        }

                        // Block requirements
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
                            context.drawText(textRenderer, "  ${blockParts.joinToString(" | ")}", textContentX + 4, textY, 0xFF887755.toInt(), true)
                            textY += 10
                        }

                        // Weight multipliers
                        if (spawn.weightMultipliers.isNotEmpty()) {
                            for (wm in spawn.weightMultipliers) {
                                val multStr = if (wm.multiplier == wm.multiplier.toInt().toFloat())
                                    "\u00d7${wm.multiplier.toInt()}" else "\u00d7${String.format("%.1f", wm.multiplier)}"
                                val condLabel = Translations.formatMultiplierCondition(wm.conditionLabel)
                                context.drawText(textRenderer, "  $multStr $condLabel", textContentX + 4, textY, 0xFF55BBFF.toInt(), true)
                                textY += 10
                            }
                        }

                        textY += 2
                    }
                } else {
                    val noDataText: String = if (SpawnData.loadError != null) Translations.tr("Error loading data") else Translations.tr("No spawn data")
                    context.drawText(textRenderer, noDataText, textContentX, textY, 0xFF666666.toInt(), true)
                }

                // Toggle button (right side of card)
                val toggleX = cardX + cardWidth - TOGGLE_SIZE - 6
                val toggleY = y + (cardHeight - TOGGLE_SIZE) / 2
                toggleButtons.add(ToggleBounds(toggleX, toggleY, index))

                val toggleHovered = mouseX >= toggleX && mouseX <= toggleX + TOGGLE_SIZE &&
                        mouseY >= toggleY && mouseY <= toggleY + TOGGLE_SIZE &&
                        mouseY >= contentTop && mouseY <= contentBottom
                val toggleBg = when {
                    target.isCaught && toggleHovered -> 0xFF3A1A1A.toInt()
                    target.isCaught -> 0xFF201414.toInt()
                    toggleHovered -> 0xFF1A3A1A.toInt()
                    else -> 0xFF142014.toInt()
                }
                context.fill(toggleX, toggleY, toggleX + TOGGLE_SIZE, toggleY + TOGGLE_SIZE, toggleBg)
                val toggleBorder = when {
                    target.isCaught && toggleHovered -> 0xFFFF7777.toInt()
                    target.isCaught -> 0xFFCC5555.toInt()
                    toggleHovered -> 0xFF77FF77.toInt()
                    else -> 0xFF55CC55.toInt()
                }
                drawBorder(context, toggleX, toggleY, TOGGLE_SIZE, TOGGLE_SIZE, toggleBorder)

                if (target.isCaught) {
                    val check = "\u2714"
                    val checkW = textRenderer.getWidth(check)
                    context.drawText(textRenderer, check,
                        toggleX + (TOGGLE_SIZE - checkW) / 2, toggleY + (TOGGLE_SIZE - 9) / 2,
                        0xFFFF5555.toInt(), true)
                } else {
                    val cross = "\u2716"
                    val crossW = textRenderer.getWidth(cross)
                    context.drawText(textRenderer, cross,
                        toggleX + (TOGGLE_SIZE - crossW) / 2, toggleY + (TOGGLE_SIZE - 9) / 2,
                        0xFF55FF55.toInt(), true)
                }

                y += cardHeight + 5
                totalHeight += cardHeight + 5
            }

            contentHeight = totalHeight
        }

        context.disableScissor()

        // Scrollbar
        sbTrackX = panelX + panelWidth - 5
        sbContentTop = contentTop
        sbContentBottom = contentBottom
        if (contentHeight > contentAreaHeight && contentAreaHeight > 0) {
            context.fill(sbTrackX, contentTop, sbTrackX + 3, contentBottom, 0xFF1A1A1A.toInt())

            sbThumbHeight = maxOf(15, contentAreaHeight * contentAreaHeight / contentHeight)
            val maxScroll = contentHeight - contentAreaHeight
            sbThumbY = contentTop + (scrollOffset * (contentAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, ModConfig.accentColor())
        } else {
            sbThumbHeight = 0
        }

        // Donors button (bottom-left of screen)
        val donorsText = "\u2605 ${Translations.tr("Generous Souls")}"
        donorsBtnW = textRenderer.getWidth(donorsText) + 16
        donorsBtnX = 6 + (width * 15 / 100)
        donorsBtnY = height - donorsBtnH - 6
        val donorsHovered = mouseX >= donorsBtnX && mouseX <= donorsBtnX + donorsBtnW &&
                            mouseY >= donorsBtnY && mouseY <= donorsBtnY + donorsBtnH
        val gBase = if (donorsHovered) 0xFF8B7320.toInt() else 0xFF6B5710.toInt()
        val gLight = if (donorsHovered) 0xFFFFD700.toInt() else 0xFFB8860B.toInt()
        val gDark = if (donorsHovered) 0xFF5A4A0A.toInt() else 0xFF3A2F05.toInt()
        context.fill(donorsBtnX, donorsBtnY, donorsBtnX + donorsBtnW, donorsBtnY + donorsBtnH, gBase)
        context.fill(donorsBtnX, donorsBtnY, donorsBtnX + donorsBtnW, donorsBtnY + 1, gLight)
        context.fill(donorsBtnX, donorsBtnY, donorsBtnX + 1, donorsBtnY + donorsBtnH, gLight)
        context.fill(donorsBtnX, donorsBtnY + donorsBtnH - 1, donorsBtnX + donorsBtnW, donorsBtnY + donorsBtnH, gDark)
        context.fill(donorsBtnX + donorsBtnW - 1, donorsBtnY, donorsBtnX + donorsBtnW, donorsBtnY + donorsBtnH, gDark)
        context.drawText(textRenderer, donorsText, donorsBtnX + 8, donorsBtnY + 4, 0xFFFFE066.toInt(), true)

        // Donate button (bottom-right, shifted 15% left)
        val donateText: String = Translations.tr("Donate to _Popichu")
        donateBtnW = textRenderer.getWidth(donateText) + 16
        donateBtnX = width - donateBtnW - 6 - (width * 15 / 100)
        donateBtnY = height - donateBtnH - 6
        val donateHovered = mouseX >= donateBtnX && mouseX <= donateBtnX + donateBtnW &&
                            mouseY >= donateBtnY && mouseY <= donateBtnY + donateBtnH
        val dBase = if (donateHovered) 0xFFA0A0A0.toInt() else 0xFF808080.toInt()
        val dLight = if (donateHovered) 0xFFDDDDDD.toInt() else 0xFFBBBBBB.toInt()
        val dDark = if (donateHovered) 0xFF666666.toInt() else 0xFF444444.toInt()
        context.fill(donateBtnX, donateBtnY, donateBtnX + donateBtnW, donateBtnY + donateBtnH, dBase)
        context.fill(donateBtnX, donateBtnY, donateBtnX + donateBtnW, donateBtnY + 1, dLight)
        context.fill(donateBtnX, donateBtnY, donateBtnX + 1, donateBtnY + donateBtnH, dLight)
        context.fill(donateBtnX, donateBtnY + donateBtnH - 1, donateBtnX + donateBtnW, donateBtnY + donateBtnH, dDark)
        context.fill(donateBtnX + donateBtnW - 1, donateBtnY, donateBtnX + donateBtnW, donateBtnY + donateBtnH, dDark)
        context.drawText(textRenderer, donateText, donateBtnX + 8, donateBtnY + 4, 0xFFFFFFFF.toInt(), true)

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint: String = Translations.tr("I / ESC to close  \u2022  Scroll to navigate")
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)

        // Version mention
        val modVersion = FabricLoader.getInstance().getModContainer(HunterBoard.MOD_ID)
            .map { it.metadata.version.friendlyString }.orElse("?")
        val versionText = "HunterBoard $modVersion"
        val versionX = (width - textRenderer.getWidth(versionText)) / 2
        context.drawText(textRenderer, versionText, versionX, height - 12, 0xFFCCCCCC.toInt(), true)

        // Tooltips rendered last (on top of everything)
        context.matrices.push()
        context.matrices.translate(0f, 0f, 400f)

        if (hoveredBallName != null) {
            context.drawTooltip(textRenderer, listOf(Text.literal(hoveredBallName)), mouseX, mouseY)
        }

        if (hoveredBiomeDetail != null && hoveredBiomeDetail!!.tagId != null) {
            val resolved = BiomeTagResolver.resolve(hoveredBiomeDetail!!.tagId!!)
            if (resolved.isNotEmpty()) {
                renderBiomeTooltip(context, resolved, hoveredBiomeDetail!!, mouseX, mouseY)
            }
        }

        context.matrices.pop()
    }

    private fun calculateCardContentHeight(spawns: List<SpawnEntry>, maxTextW: Int): Int {
        var h = 6   // top padding
        h += 13     // name line

        if (spawns.isEmpty()) {
            h += 12
        } else {
            h += 13 // level line
            val seen = mutableSetOf<String>()
            for (spawn in spawns) {
                val key = "${spawn.biomes}|${spawn.time}|${spawn.weather}|${spawn.structures}|${spawn.canSeeSky}|${spawn.bucket}|${spawn.spawnContext}|${spawn.minY}|${spawn.maxY}|${spawn.minSkyLight}|${spawn.maxSkyLight}|${spawn.presets}"
                if (key in seen) continue
                seen.add(key)

                // Presets/Context tags
                val meaningfulPresets = spawn.presets.filter { it != "natural" }
                val ctxStr = Translations.formatSpawnContext(spawn.spawnContext)
                val hasTags = meaningfulPresets.isNotEmpty() || ctxStr.isNotEmpty()

                if (hasTags) {
                    h += 10 // tags line
                    h += calculateBiomeLinesHeight(spawn.biomeDetails, maxTextW - 12)
                } else {
                    val rarityText: String = Translations.formatRarity(spawn.bucket)
                    val weightStr = if (spawn.weight != null) " (${Translations.tr("Weight:")} ${String.format("%.1f", spawn.weight)})" else ""
                    val rarityTagW = textRenderer.getWidth("[$rarityText]$weightStr ")
                    h += calculateBiomeLinesHeight(spawn.biomeDetails, maxTextW - 8 - rarityTagW)
                }

                if (spawn.excludedBiomes.isNotEmpty()) h += 10
                if (spawn.structures.isNotEmpty()) h += 10
                h += 10 // time/weather/sky

                // Height, Light, Moon
                val hasExtras = spawn.minY != null || spawn.maxY != null ||
                    spawn.minSkyLight != null || spawn.maxSkyLight != null ||
                    spawn.minLight != null || spawn.maxLight != null ||
                    spawn.moonPhase != null
                if (hasExtras) h += 10

                // Block requirements
                val hasBlocks = spawn.neededBaseBlocks.isNotEmpty() || spawn.neededNearbyBlocks.isNotEmpty()
                if (hasBlocks) h += 10

                // Weight multipliers
                h += spawn.weightMultipliers.size * 10

                h += 2 // spacing between spawns
            }
        }

        h += 4 // bottom padding
        return h
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

            // Donors button
            if (mouseX >= donorsBtnX && mouseX <= donorsBtnX + donorsBtnW &&
                mouseY >= donorsBtnY.toDouble() && mouseY <= (donorsBtnY + donorsBtnH).toDouble()) {
                DonorsFetcher.fetchIfNeeded()
                client?.setScreen(DonorsScreen(this))
                return true
            }

            // Donate button
            if (mouseX >= donateBtnX && mouseX <= donateBtnX + donateBtnW &&
                mouseY >= donateBtnY.toDouble() && mouseY <= (donateBtnY + donateBtnH).toDouble()) {
                try { Util.getOperatingSystem().open(URI("https://www.paypal.com/paypalme/Popiipops")) } catch (_: Exception) {}
                return true
            }

            // Options button
            if (mouseX >= optBtnX && mouseX <= optBtnX + optBtnSize &&
                mouseY >= optBtnY.toDouble() && mouseY <= (optBtnY + optBtnSize).toDouble()) {
                client?.setScreen(OptionsScreen(this))
                return true
            }

            // Chat button
            if (mouseX >= chatBtnX && mouseX <= chatBtnX + chatBtnW &&
                mouseY >= chatBtnY.toDouble() && mouseY <= (chatBtnY + chatBtnH).toDouble()) {
                val uncaughtNames = BoardState.targets.take(ModConfig.maxHunts())
                    .filter { !it.isCaught }
                    .joinToString(" ") { Translations.pokemonName(it.speciesId) }
                if (uncaughtNames.isNotEmpty()) {
                    val message = "[*Chasse*] $uncaughtNames"
                    client?.setScreen(ChatScreen(message))
                }
                return true
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

            // Pokemon name/icon click → open detail screen
            for (poke in pokemonClickAreas) {
                if (mouseX >= poke.x && mouseX <= poke.x + poke.w &&
                    mouseY >= poke.y && mouseY <= poke.y + poke.h &&
                    mouseY >= sbContentTop && mouseY <= sbContentBottom) {
                    val visibleTargets = BoardState.targets.take(ModConfig.maxHunts())
                    if (poke.index in visibleTargets.indices) {
                        val target = visibleTargets[poke.index]
                        val species = PokemonSpecies.getByName(target.speciesId)
                        if (species != null) {
                            client?.setScreen(PokemonDetailScreen(species, this))
                            return true
                        }
                    }
                }
            }

            // Toggle buttons
            for (toggle in toggleButtons) {
                if (mouseX >= toggle.x && mouseX <= toggle.x + TOGGLE_SIZE &&
                    mouseY >= toggle.y && mouseY <= toggle.y + TOGGLE_SIZE) {
                    BoardState.toggleTargetCaught(toggle.index)
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
                isHovered -> ModConfig.accentColor()
                biome.tagId != null -> 0xFFBBBBBB.toInt()
                else -> 0xFF999999.toInt()
            }
            context.drawText(textRenderer, Translations.biomeName(biome), x, y, color, true)

            if (biome.tagId != null) {
                val underlineColor = if (isHovered) ModConfig.accentColor() else 0xFF444444.toInt()
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

    private fun calculateBiomeLinesHeight(biomes: List<BiomeDetail>, maxWidth: Int): Int {
        if (biomes.isEmpty()) return 10
        var x = 0
        var lineCount = 1
        for ((i, biome) in biomes.withIndex()) {
            val nameW = textRenderer.getWidth(Translations.biomeName(biome))
            val sepW = if (i < biomes.lastIndex) textRenderer.getWidth(", ") else 0
            if (x + nameW > maxWidth && x > 0) {
                lineCount++
                x = 0
            }
            x += nameW + sepW
        }
        return lineCount * 10
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val panelTop = 25
        val panelBottom = height - 25
        val contentTop = panelTop + 24
        val contentAreaHeight = panelBottom - 16 - contentTop
        val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_I) {
            close()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
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

        // Calculate how many columns are needed to fit on screen
        // Each column has (biomes.size / numCols) rows + 1 title row
        var numCols = 1
        while (numCols < 4) {
            val rowsPerCol = (biomes.size + numCols - 1) / numCols
            val h = padding + (rowsPerCol + 1) * lineHeight + padding
            if (h <= maxAvailableHeight) break
            numCols++
        }

        // Split biomes into columns
        val rowsPerCol = (biomes.size + numCols - 1) / numCols
        val columns = (0 until numCols).map { col ->
            val start = col * rowsPerCol
            biomes.subList(start, minOf(start + rowsPerCol, biomes.size))
        }

        // Calculate column widths
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

        // Title
        context.drawText(textRenderer, titleText, tx + padding, ty + padding, titleColor, true)

        // Render each column
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
}
