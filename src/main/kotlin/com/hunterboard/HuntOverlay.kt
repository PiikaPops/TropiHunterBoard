package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.TagKey
import net.minecraft.util.Identifier

object HuntOverlay {

    // Panel position (null = auto bottom-right) — stored in screen pixels
    var panelX: Int? = null
    var panelY: Int? = null

    // Exposed rendered bounds in screen pixels for HudPositionScreen
    var renderedX = 0; private set
    var renderedY = 0; private set
    var renderedW = 0; private set
    var renderedH = 0; private set

    // Force visibility for position editing
    var forceVisible = false

    // Size layout per preset (Small / Normal / Large)
    private data class SizeLayout(
        val rowHeight: Int,
        val modelSize: Int,
        val padding: Int,
        val headerHeight: Int,
        val modelBaseScale: Float
    )

    private val SIZE_PRESETS = arrayOf(
        SizeLayout(rowHeight = 17, modelSize = 15, padding = 4, headerHeight = 11, modelBaseScale = 0.65f), // 0 Small
        SizeLayout(rowHeight = 24, modelSize = 22, padding = 5, headerHeight = 14, modelBaseScale = 0.85f), // 1 Normal
        SizeLayout(rowHeight = 36, modelSize = 32, padding = 6, headerHeight = 18, modelBaseScale = 1.20f), // 2 Large
        SizeLayout(rowHeight = 50, modelSize = 44, padding = 7, headerHeight = 22, modelBaseScale = 1.60f)  // 3 Extra Large
    )

    // Cached model widgets (used when usePixelArt = false)
    private var cachedWidgets: List<ModelWidget> = emptyList()
    private var lastBoardUpdate: Long = 0

    // Cached sprite identifiers (used when usePixelArt = true)
    private var cachedSpriteIds: List<Identifier?> = emptyList()
    private var cachedUsePixelArt: Boolean = false

    // Cached per-board-update values (avoid recalculating every frame)
    private var cachedBallStacks: Map<String, ItemStack> = emptyMap()
    private var cachedPanelWidth: Int = 80
    private var cachedDisplayMode: Int = -1
    private var cachedDisplayCount: Int = -1
    private var cachedRank: Int = -1
    private var cachedSizePreset: Int = -1
    private var cachedGridLayout: Boolean = false
    private var cachedSpriteLoadedCount: Int = -1

    // Cached translated names
    private var cachedPokemonNames: List<String> = emptyList()
    private var cachedBallNames: List<String> = emptyList()
    private var cachedHeaderText: String = ""
    private var cachedRemaining: Int = 0
    private var cachedTotal: Int = 0
    private var cachedReward: Int = 0
    private var cachedFooterColor: Int = 0

    // Biome highlight cache
    private var cachedBiomeHighlight: List<Boolean> = emptyList()
    private var lastBiomeCheck: Long = 0
    private const val BIOME_CHECK_INTERVAL_MS = 3_000L
    private const val BIOME_HIGHLIGHT_COLOR = 0xFF55FF55.toInt()

    // Bravo GIF frames (6 frames, 40ms each = 240ms loop)
    private val bravoFrames = Array(6) { Identifier.of("hunterboard", "img/cat-clap/frame_$it.png") }
    private const val BRAVO_FRAME_MS = 40L
    private const val BRAVO_GIF_SIZE = 10

    // Colors
    private fun BG_COLOR() = ModConfig.bgColor()
    private fun BORDER_COLOR() = ModConfig.accentColor()
    private fun TITLE_COLOR() = ModConfig.accentColor()
    private const val POKEMON_COLOR = 0xFFFFFFFF.toInt()
    private const val CAUGHT_COLOR = 0xFFFF5555.toInt()
    private const val BALL_COLOR = 0xFFAAAAFF.toInt()
    private const val REMAINING_COLOR = 0xFFFF5555.toInt()

    fun register() {
        BoardState.load()
        loadPosition()

        HudRenderCallback.EVENT.register { context, _ ->
            safeRender(context)
        }
        HunterBoard.LOGGER.info("Hunt overlay registered")
    }

    fun loadPosition() {
        panelX = if (ModConfig.hudPosX >= 0) ModConfig.hudPosX else null
        panelY = if (ModConfig.hudPosY >= 0) ModConfig.hudPosY else null
    }

    private fun safeRender(context: DrawContext) {
        try { render(context) } catch (_: Exception) {}
    }

    private fun render(context: DrawContext) {
        // Hide HUD during Pokémon battles
        if (ModConfig.hideHudInBattle && !forceVisible && BattleHelper.isInBattle()) return

        val showHuntContent = ModConfig.showHuntHud || forceVisible

        // In non-merged mode, if hunt is hidden, nothing to do
        if (!showHuntContent && !ModConfig.mergedHudMode) return

        if (!BoardState.hudVisible && !forceVisible) return

        // Bravo flag: show congratulations instead of target list (but keep header + merged content)
        val isBravo = BoardState.showBravo && ModConfig.autoHideOnComplete && !forceVisible

        // Check merged content availability
        val hasMergedContent = ModConfig.mergedHudMode && (RaidTimerOverlay.isActive() || MiracleOverlay.isActive())

        // If hunt is hidden and no merged content, nothing to render
        if (!showHuntContent && !hasMergedContent) return

        // Placeholder when waiting for board after midnight reset (only for hunt content)
        if (showHuntContent && BoardState.waitingForBoard && !BoardState.hasTargets()) {
            if (!hasMergedContent) {
                renderPlaceholder(context)
                return
            }
        }

        val hasHuntData = showHuntContent && BoardState.hasTargets()

        // Nothing at all to render
        if (!hasHuntData && !hasMergedContent) return

        val client = MinecraftClient.getInstance()
        val textRenderer = client.textRenderer
        val mode = if (hasHuntData) BoardState.displayMode else 0
        val allTargets = if (hasHuntData) BoardState.targets else emptyList()
        val displayTargets = if (hasHuntData) allTargets.take(ModConfig.maxHunts()) else emptyList()
        val effectiveHuntSize = if (ModConfig.mergedHudMode) ModConfig.hudSizePreset else ModConfig.huntSizePreset
        val layout = SIZE_PRESETS[effectiveHuntSize.coerceIn(0, 3)]

        // Rebuild caches if board data, display settings, or size changed
        if (hasHuntData && (BoardState.lastUpdated != lastBoardUpdate
            || mode != cachedDisplayMode
            || BoardState.displayCount != cachedDisplayCount
            || ModConfig.rank != cachedRank
            || effectiveHuntSize != cachedSizePreset
            || ModConfig.gridLayout != cachedGridLayout
            || ModConfig.usePixelArt != cachedUsePixelArt
            || (ModConfig.usePixelArt && SpriteHelper.loadedCount != cachedSpriteLoadedCount))
        ) {
            rebuildModelWidgets(displayTargets, layout)
            rebuildBallCache()
            rebuildNameCaches(displayTargets)
            rebuildSpriteCache(displayTargets)
            cachedHeaderText = Translations.tr("Hunting Board")
            cachedPanelWidth = calculatePanelWidth(displayTargets, textRenderer, mode)
            cachedRemaining = displayTargets.count { !it.isCaught }
            cachedTotal = displayTargets.size
            cachedReward = displayTargets.filter { !it.isCaught }.sumOf { it.reward }
            cachedFooterColor = if (cachedRemaining == 0) CAUGHT_COLOR else REMAINING_COLOR
            cachedDisplayMode = mode
            cachedDisplayCount = BoardState.displayCount
            cachedRank = ModConfig.rank
            cachedSizePreset = effectiveHuntSize
            cachedGridLayout = ModConfig.gridLayout
            cachedUsePixelArt = ModConfig.usePixelArt
            cachedSpriteLoadedCount = SpriteHelper.loadedCount
            lastBoardUpdate = BoardState.lastUpdated
        }

        // Throttled biome highlight check
        if (hasHuntData) {
            val now = System.currentTimeMillis()
            if (now - lastBiomeCheck > BIOME_CHECK_INTERVAL_MS) {
                lastBiomeCheck = now
                cachedBiomeHighlight = if (SpawnData.isLoaded) {
                    displayTargets.map { if (!it.isCaught) isInSpawnBiome(it.speciesId) else false }
                } else emptyList()
            }
        }

        val ROW_HEIGHT = layout.rowHeight
        val MODEL_SIZE = layout.modelSize
        val PADDING = layout.padding
        val HEADER_HEIGHT = layout.headerHeight

        // Merged mode: extra height for raid + miracle sections
        val mergedRaidH = if (ModConfig.mergedHudMode && RaidTimerOverlay.isActive()) RaidTimerOverlay.contentHeight() + 3 else 0
        val mergedMiracleH = if (ModConfig.mergedHudMode && MiracleOverlay.isActive()) MiracleOverlay.contentHeight() + 3 else 0
        val mergedExtraH = mergedRaidH + mergedMiracleH

        // Hunt content dimensions (0 if hunt is hidden)
        val showHeader: Boolean
        val headerH: Int
        val footerH: Int
        val gridCols: Int
        val gridRows: Int
        val gridCellW: Int
        val gridCellH: Int
        val panelW: Int
        val huntContentH: Int

        if (hasHuntData) {
            showHeader = mode <= 2
            headerH = if (showHeader) HEADER_HEIGHT + 6 else HEADER_HEIGHT
            footerH = if (mode <= 2) 14 else 10
            gridCols = if (displayTargets.size >= 5) 3 else 2
            gridRows = if (ModConfig.gridLayout) kotlin.math.ceil(displayTargets.size.toFloat() / gridCols).toInt() else 0
            gridCellW = MODEL_SIZE + 14
            gridCellH = MODEL_SIZE + 18
            panelW = cachedPanelWidth
            huntContentH = if (isBravo) {
                // Bravo mode: header + one text line + padding
                headerH + 12 + PADDING
            } else if (ModConfig.gridLayout) {
                headerH + gridRows * gridCellH + PADDING + footerH
            } else {
                headerH + displayTargets.size * ROW_HEIGHT + PADDING + footerH
            }
        } else {
            showHeader = false
            headerH = 0
            footerH = 0
            gridCols = 0
            gridRows = 0
            gridCellW = 0
            gridCellH = 0
            val raidW = if (ModConfig.mergedHudMode && RaidTimerOverlay.isActive()) RaidTimerOverlay.contentWidth() else 0
            val miracleW = if (ModConfig.mergedHudMode && MiracleOverlay.isActive()) MiracleOverlay.contentWidth() else 0
            panelW = maxOf(raidW, miracleW) + PADDING * 2
            huntContentH = 0
        }

        val panelHeight = mergedExtraH + huntContentH

        val screenWidth = client.window.scaledWidth
        val screenHeight = client.window.scaledHeight

        // panelX/Y are in screen pixels; clamp so the panel never goes off-screen
        val rawX = panelX ?: (screenWidth - panelW - 10)
        val rawY = panelY ?: (screenHeight - panelHeight - 10)
        val clampedX = rawX.coerceIn(PADDING, (screenWidth - panelW - PADDING).coerceAtLeast(PADDING))
        val clampedY = rawY.coerceIn(PADDING, (screenHeight - panelHeight - PADDING).coerceAtLeast(PADDING))

        // Anti-overlap: register with layout manager (skip in merged mode)
        val totalW = panelW + PADDING * 2
        val totalH = panelHeight + PADDING
        val (adjX, adjY) = if (!ModConfig.mergedHudMode) {
            HudLayoutManager.register(clampedX - PADDING, clampedY - PADDING, totalW, totalH, screenWidth, screenHeight)
        } else (clampedX - PADDING) to (clampedY - PADDING)
        val x = adjX + PADDING
        val y = adjY + PADDING

        // Store rendered bounds (screen pixels) for HudPositionScreen
        renderedX = adjX
        renderedY = adjY
        renderedW = totalW
        renderedH = totalH

        // Background + border (skip in Full Clear mode)
        if (!ModConfig.fullClearMode) {
            context.fill(x - PADDING, y - PADDING, x + panelW + PADDING, y + panelHeight, BG_COLOR())
            drawBorder(context, x - PADDING, y - PADDING, panelW + PADDING * 2, panelHeight + PADDING, BORDER_COLOR())
        }

        // Merged mode: draw Raid + Miracle sections at top of panel
        var mergedOffset = 0
        if (ModConfig.mergedHudMode) {
            val hasMiracle = MiracleOverlay.isActive()
            if (RaidTimerOverlay.isActive()) {
                RaidTimerOverlay.renderContent(context, x, y + mergedOffset, panelW)
                mergedOffset += RaidTimerOverlay.contentHeight()
                // Separator line only if more content follows
                if (hasMiracle || hasHuntData) {
                    context.fill(x - PADDING + 1, y + mergedOffset + 1, x + panelW + PADDING - 1, y + mergedOffset + 2, BORDER_COLOR())
                    mergedOffset += 3
                }
            }
            if (hasMiracle) {
                MiracleOverlay.renderContent(context, x, y + mergedOffset, panelW)
                mergedOffset += MiracleOverlay.contentHeight()
                // Separator line only if hunt content follows
                if (hasHuntData) {
                    context.fill(x - PADDING + 1, y + mergedOffset + 1, x + panelW + PADDING - 1, y + mergedOffset + 2, BORDER_COLOR())
                    mergedOffset += 3
                }
            }
        }

        // Hunt content (header, targets, footer) — only when hunt data is visible
        if (hasHuntData) {

        // Header
        val contentStartY = y + mergedOffset
        var currentY: Int
        if (showHeader) {
            val headerX = x + (panelW - textRenderer.getWidth(cachedHeaderText)) / 2
            context.drawText(textRenderer, cachedHeaderText, headerX, contentStartY, TITLE_COLOR(), true)
            currentY = contentStartY + HEADER_HEIGHT + 6
        } else {
            currentY = contentStartY + headerH
        }

        if (isBravo) {
            // Bravo mode: "Congrats! [cat]" centered
            val bravoText = Translations.tr("Congrats!")
            val bravoTextW = textRenderer.getWidth(bravoText)
            val topLineW = bravoTextW + 2 + BRAVO_GIF_SIZE

            val elapsed = System.currentTimeMillis() - BoardState.bravoStartTime
            val frameIndex = ((elapsed / BRAVO_FRAME_MS) % bravoFrames.size).toInt()

            val topLineX = x + (panelW - topLineW) / 2
            val lineY = currentY + PADDING / 2
            val bravoColor = 0xFF55FF55.toInt()
            context.drawText(textRenderer, bravoText, topLineX, lineY, bravoColor, true)
            try {
                context.drawTexture(bravoFrames[frameIndex], topLineX + bravoTextW + 2, lineY - 1, 0f, 0f,
                    BRAVO_GIF_SIZE, BRAVO_GIF_SIZE, BRAVO_GIF_SIZE, BRAVO_GIF_SIZE)
            } catch (_: Exception) {}
        } else if (ModConfig.gridLayout) {
            // ========== GRID RENDERING ==========
            val isLastRow = { r: Int -> r == gridRows - 1 }
            for ((index, target) in displayTargets.withIndex()) {
                val col = index % gridCols
                val row = index / gridCols
                val cellX = x + col * gridCellW
                val cellY = currentY + row * gridCellH

                // Model position depends on row: top rows leave space for ball above, bottom row for ball below
                val modelY = if (isLastRow(row)) cellY else cellY + 16
                val modelX = cellX + (gridCellW - MODEL_SIZE) / 2

                // Ball icon: above model on top rows, below model on bottom row
                val ballStack = cachedBallStacks[target.ballId]
                if (ballStack != null) {
                    val ballIconX = cellX + (gridCellW - 16) / 2
                    val ballIconY = if (isLastRow(row)) modelY + MODEL_SIZE + 1 else cellY
                    context.drawItem(ballStack, ballIconX, ballIconY)
                }

                // Pokemon icon: sprite or 3D model
                val spriteId = if (ModConfig.usePixelArt) cachedSpriteIds.getOrNull(index) else null
                if (spriteId != null) {
                    renderSprite(context, spriteId, modelX, modelY, MODEL_SIZE)
                } else if (index < cachedWidgets.size) {
                    try {
                        val widget = cachedWidgets[index]
                        widget.x = modelX
                        widget.y = modelY
                        widget.render(context, 0, 0, 0f)
                    } catch (_: Exception) {}
                }

                // Red cross overlay on caught
                if (target.isCaught) {
                    context.matrices.push()
                    context.matrices.translate(0.0, 0.0, 200.0)
                    for (i in 0 until MODEL_SIZE step 2) {
                        val px1 = modelX + i; val py1 = modelY + i
                        context.fill(px1, py1, px1 + 3, py1 + 3, 0xCCFF3333.toInt())
                        val px2 = modelX + MODEL_SIZE - i - 3
                        context.fill(px2, py1, px2 + 3, py1 + 3, 0xCCFF3333.toInt())
                    }
                    context.matrices.pop()
                }
            }

            currentY += gridRows * gridCellH
        } else {
            // ========== LIST RENDERING ==========
            for ((index, target) in displayTargets.withIndex()) {
                val inSpawnBiome = cachedBiomeHighlight.getOrElse(index) { false }
                val color = when {
                    target.isCaught -> CAUGHT_COLOR
                    inSpawnBiome    -> BIOME_HIGHLIGHT_COLOR
                    else            -> POKEMON_COLOR
                }

                // Pokemon icon: sprite or 3D model
                val spriteId = if (ModConfig.usePixelArt) cachedSpriteIds.getOrNull(index) else null
                val iconX = x
                val iconY = currentY - MODEL_SIZE / 2
                if (spriteId != null) {
                    renderSprite(context, spriteId, iconX, iconY, MODEL_SIZE)
                } else if (index < cachedWidgets.size) {
                    try {
                        val widget = cachedWidgets[index]
                        widget.x = iconX
                        widget.y = iconY
                        widget.render(context, 0, 0, 0f)
                    } catch (_: Exception) {
                        val icon = if (target.isCaught) "\u2713" else "\u25CF"
                        context.drawText(textRenderer, icon, x + 4, currentY + (ROW_HEIGHT - 9) / 2, color, true)
                    }
                }

                // Red cross overlay on caught
                if (target.isCaught) {
                    context.matrices.push()
                    context.matrices.translate(0.0, 0.0, 200.0)
                    for (i in 0 until MODEL_SIZE step 2) {
                        val px1 = iconX + i; val py1 = iconY + i
                        context.fill(px1, py1, px1 + 3, py1 + 3, 0xCCFF3333.toInt())
                        val px2 = iconX + MODEL_SIZE - i - 3
                        context.fill(px2, py1, px2 + 3, py1 + 3, 0xCCFF3333.toInt())
                    }
                    context.matrices.pop()
                }

                // Pokemon name (modes 0, 1, 2)
                if (mode <= 2) {
                    val textX = x + MODEL_SIZE + 4
                    val textY = currentY + (ROW_HEIGHT - 9) / 2
                    val displayName = cachedPokemonNames.getOrElse(index) { target.speciesId }
                    context.drawText(textRenderer, displayName, textX, textY, color, true)
                    if (target.isCaught) {
                        val nameWidth = textRenderer.getWidth(displayName)
                        context.fill(textX, textY + 4, textX + nameWidth, textY + 5, CAUGHT_COLOR)
                    }
                }

                // Ball icon + name (modes 0, 1, 4)
                if (mode <= 1 || mode == 4) {
                    val textY = currentY + (ROW_HEIGHT - 9) / 2
                    val ballStack = cachedBallStacks[target.ballId]
                    if (mode == 0) {
                        val ballText = cachedBallNames.getOrElse(index) { target.ballId }
                        val ballTextWidth = textRenderer.getWidth(ballText)
                        val ballNameX = x + panelW - ballTextWidth
                        context.drawText(textRenderer, ballText, ballNameX, textY, BALL_COLOR, true)
                        if (ballStack != null) context.drawItem(ballStack, ballNameX - 18, currentY + (ROW_HEIGHT - 16) / 2)
                    } else {
                        if (ballStack != null) {
                            context.drawItem(ballStack, x + panelW - 18, currentY + (ROW_HEIGHT - 16) / 2)
                        } else {
                            val ballText = cachedBallNames.getOrElse(index) { target.ballId }
                            context.drawText(textRenderer, ballText, x + panelW - textRenderer.getWidth(ballText), textY, BALL_COLOR, true)
                        }
                    }
                }

                currentY += ROW_HEIGHT
            }
        }

        // Footer (skip in bravo mode)
        if (!isBravo) {
        if (mode <= 2) {
            val remainText = "${cachedRemaining}/${cachedTotal} | ${cachedReward}$"
            context.drawText(textRenderer, remainText, x, currentY + 2, cachedFooterColor, true)
        } else {
            val remainText = "${cachedRemaining}/${cachedTotal}"
            context.drawText(textRenderer, remainText, x + (panelW - textRenderer.getWidth(remainText)) / 2, currentY + 1, cachedFooterColor, true)
        }
        }

        } // end if (hasHuntData)
    }

    /** Returns true if the player's current biome is a valid spawn biome for this species. */
    private fun isInSpawnBiome(speciesId: String): Boolean {
        try {
            val client = MinecraftClient.getInstance()
            val world = client.world ?: return false
            val player = client.player ?: return false
            val biomeEntry = world.getBiome(player.blockPos)
            val biomeKey = biomeEntry.key.orElse(null) ?: return false
            val biomeFullId = "${biomeKey.value.namespace}:${biomeKey.value.path}"

            for (entry in SpawnData.getSpawns(speciesId)) {
                for (biomeDetail in entry.biomeDetails) {
                    when {
                        biomeDetail.biomeId != null -> if (biomeDetail.biomeId == biomeFullId) return true
                        biomeDetail.tagId != null -> {
                            val parts = biomeDetail.tagId.split(":", limit = 2)
                            if (parts.size == 2) {
                                val tagKey = TagKey.of(RegistryKeys.BIOME, Identifier.of(parts[0], parts[1]))
                                if (biomeEntry.isIn(tagKey)) return true
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun calculatePanelWidth(
        targets: List<HuntTarget>,
        textRenderer: net.minecraft.client.font.TextRenderer,
        mode: Int
    ): Int {
        if (targets.isEmpty()) return 80
        val layout = SIZE_PRESETS[(if (ModConfig.mergedHudMode) ModConfig.hudSizePreset else ModConfig.huntSizePreset).coerceIn(0, 3)]
        val MODEL_SIZE = layout.modelSize
        val PADDING = layout.padding

        val headerTextWidth = textRenderer.getWidth(cachedHeaderText) + PADDING * 2

        // In merged mode, also consider Raid/Miracle content widths
        val mergedMinWidth = if (ModConfig.mergedHudMode) {
            val raidW = if (RaidTimerOverlay.isActive()) RaidTimerOverlay.contentWidth() else 0
            val miracleW = if (MiracleOverlay.isActive()) MiracleOverlay.contentWidth() else 0
            maxOf(raidW, miracleW)
        } else 0

        // Grid mode: cells based on model size
        if (ModConfig.gridLayout) {
            val cols = if (targets.size >= 5) 3 else 2
            val cellW = MODEL_SIZE + 14  // width unchanged
            return maxOf(headerTextWidth, cols * cellW, mergedMinWidth)
        }

        val modelArea = MODEL_SIZE + 4

        val huntWidth = when (mode) {
            3    -> MODEL_SIZE + PADDING * 3
            4    -> MODEL_SIZE + 18 + PADDING * 2
            2    -> {
                val maxName = cachedPokemonNames.maxOfOrNull { textRenderer.getWidth(it) } ?: 0
                maxOf(headerTextWidth, modelArea + maxName + PADDING * 2)
            }
            1    -> {
                val maxName = cachedPokemonNames.maxOfOrNull { textRenderer.getWidth(it) } ?: 0
                maxOf(headerTextWidth, modelArea + maxName + 10 + 16 + PADDING)
            }
            else -> {
                val maxName = cachedPokemonNames.maxOfOrNull { textRenderer.getWidth(it) } ?: 0
                val maxBall = cachedBallNames.maxOfOrNull { textRenderer.getWidth(it) } ?: 0
                maxOf(headerTextWidth, modelArea + maxName + 10 + 18 + maxBall + PADDING)
            }
        }
        return maxOf(huntWidth, mergedMinWidth)
    }

    private fun rebuildBallCache() {
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

    private fun rebuildNameCaches(targets: List<HuntTarget>) {
        cachedPokemonNames = targets.map { Translations.pokemonName(it.speciesId) }
        cachedBallNames = targets.map { Translations.ballName(it.ballId) }
    }

    private fun rebuildSpriteCache(targets: List<HuntTarget>) {
        cachedSpriteIds = targets.map { SpriteHelper.getSpriteIdentifier(it.speciesId) }
    }

    /** Render a pixel art sprite scaled up to fill the cell (PokeAPI sprites have transparent padding) */
    private fun renderSprite(context: DrawContext, spriteId: Identifier, x: Int, y: Int, size: Int) {
        val scaledSize = (size * 1.4).toInt()
        val offset = (scaledSize - size) / 2
        context.drawTexture(spriteId, x - offset, y - offset, 0f, 0f, scaledSize, scaledSize, scaledSize, scaledSize)
    }

    private fun rebuildModelWidgets(targets: List<HuntTarget>, layout: SizeLayout) {
        val newWidgets = mutableListOf<ModelWidget>()
        for (target in targets) {
            try {
                val species = PokemonSpecies.getByName(target.speciesId)
                    ?: PokemonSpecies.getByName("bulbasaur")
                if (species != null) {
                    newWidgets.add(ModelWidget(
                        pX = 0, pY = 0,
                        pWidth = layout.modelSize, pHeight = layout.modelSize,
                        pokemon = RenderablePokemon(species, if (PokemonSpecies.getByName(target.speciesId) != null) target.aspects else emptySet()),
                        baseScale = layout.modelBaseScale,
                        rotationY = 325f,
                        offsetY = -10.0
                    ))
                }
            } catch (e: Exception) {
                HunterBoard.LOGGER.debug("Failed to create model widget for ${target.speciesId}: ${e.message}")
            }
        }
        cachedWidgets = newWidgets
    }

    private fun renderPlaceholder(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        val textRenderer = client.textRenderer
        val layout = SIZE_PRESETS[(if (ModConfig.mergedHudMode) ModConfig.hudSizePreset else ModConfig.huntSizePreset).coerceIn(0, 3)]
        val PADDING = layout.padding

        val text = Translations.tr("Click on a hunting board")
        val textW = textRenderer.getWidth(text)
        val panelW = textW + PADDING * 4
        val panelH = 18

        val screenWidth = client.window.scaledWidth
        val screenHeight = client.window.scaledHeight
        val rawX = panelX ?: (screenWidth - panelW - 10)
        val rawY = panelY ?: (screenHeight - panelH - 10)
        val x = rawX.coerceIn(PADDING, (screenWidth - panelW - PADDING).coerceAtLeast(PADDING))
        val y = rawY.coerceIn(PADDING, (screenHeight - panelH - PADDING).coerceAtLeast(PADDING))

        renderedX = x - PADDING
        renderedY = y - PADDING
        renderedW = panelW + PADDING * 2
        renderedH = panelH + PADDING

        if (!ModConfig.fullClearMode) {
            context.fill(x - PADDING, y - PADDING, x + panelW + PADDING, y + panelH, BG_COLOR())
            drawBorder(context, x - PADDING, y - PADDING, panelW + PADDING * 2, panelH + PADDING, BORDER_COLOR())
        }
        context.drawText(textRenderer, text, x + PADDING, y + 4, TITLE_COLOR(), true)
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, width: Int, height: Int, color: Int) {
        context.fill(x, y, x + width, y + 1, color)
        context.fill(x, y + height - 1, x + width, y + height, color)
        context.fill(x, y, x + 1, y + height, color)
        context.fill(x + width - 1, y, x + width, y + height, color)
    }
}
