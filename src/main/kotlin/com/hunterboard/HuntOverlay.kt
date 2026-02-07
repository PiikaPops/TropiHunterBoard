package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

object HuntOverlay {

    // Panel position (null = auto bottom-right)
    private var panelX: Int? = null
    private var panelY: Int? = null

    // Panel dimensions (constants)
    private const val ROW_HEIGHT = 24
    private const val MODEL_SIZE = 22
    private const val PADDING = 5
    private const val HEADER_HEIGHT = 14

    // Drag state
    private var isDragging = false
    private var dragStarted = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var dragStartMouseX = 0
    private var dragStartMouseY = 0
    private var wasMouseDown = false
    private const val DRAG_DEAD_ZONE = 5
    private const val SNAP_DISTANCE = 20

    // Cached model widgets
    private var cachedWidgets: List<ModelWidget> = emptyList()
    private var lastBoardUpdate: Long = 0

    // Colors
    private const val BG_COLOR = 0xCC000000.toInt()
    private const val BORDER_COLOR = 0xFFFFAA00.toInt()
    private const val TITLE_COLOR = 0xFFFFAA00.toInt()
    private const val POKEMON_COLOR = 0xFFFFFFFF.toInt()
    private const val CAUGHT_COLOR = 0xFF55FF55.toInt()
    private const val BALL_COLOR = 0xFFAAAAFF.toInt()
    private const val REMAINING_COLOR = 0xFFFF5555.toInt()

    fun register() {
        BoardState.load()

        HudRenderCallback.EVENT.register { context, tickDelta ->
            safeRender(context, tickDelta)
        }
        HunterBoard.LOGGER.info("Hunt overlay registered")
    }

    private fun safeRender(context: DrawContext, tickDelta: net.minecraft.client.render.RenderTickCounter) {
        try {
            render(context)
        } catch (e: Exception) {
            // Silently fail
        }
    }

    private fun render(context: DrawContext) {
        if (!BoardState.hudVisible) return
        if (!BoardState.hasTargets()) return

        val client = MinecraftClient.getInstance()
        val textRenderer = client.textRenderer
        val mode = BoardState.displayMode
        val allTargets = BoardState.targets
        val displayTargets = allTargets.take(BoardState.displayCount)

        // Rebuild model widgets if board data changed
        if (BoardState.lastUpdated != lastBoardUpdate) {
            rebuildModelWidgets()
            lastBoardUpdate = BoardState.lastUpdated
        }

        // Calculate adaptive panel width
        val panelW = calculatePanelWidth(displayTargets, textRenderer, mode)

        // Calculate panel height
        val showHeader = mode != 3
        val headerH = if (showHeader) HEADER_HEIGHT + 2 else 2
        val footerH = if (mode != 3) 14 else 10
        val panelHeight = headerH + displayTargets.size * ROW_HEIGHT + PADDING + footerH

        val screenWidth = client.window.scaledWidth
        val screenHeight = client.window.scaledHeight

        handleDragging(client, panelW, panelHeight)

        // Position: default to bottom-right
        val x = panelX ?: (screenWidth - panelW - 10)
        val y = panelY ?: (screenHeight - panelHeight - 10)

        // Draw background + border
        context.fill(x - PADDING, y - PADDING, x + panelW + PADDING, y + panelHeight, BG_COLOR)
        drawBorder(context, x - PADDING, y - PADDING, panelW + PADDING * 2, panelHeight + PADDING, BORDER_COLOR)

        // Header
        var currentY = y
        if (showHeader) {
            val headerText = "Hunting Board"
            val headerX = x + (panelW - textRenderer.getWidth(headerText)) / 2
            context.drawText(textRenderer, headerText, headerX, y, TITLE_COLOR, true)
            currentY = y + HEADER_HEIGHT + 2
        }

        // Draw each target
        for ((index, target) in displayTargets.withIndex()) {
            val color = if (target.isCaught) CAUGHT_COLOR else POKEMON_COLOR

            // Render 3D model (always shown in all modes)
            if (index < cachedWidgets.size) {
                try {
                    val widget = cachedWidgets[index]
                    widget.x = x
                    widget.y = currentY - 10
                    widget.render(context, 0, 0, 0f)
                } catch (e: Exception) {
                    val icon = if (target.isCaught) "\u2713" else "\u25CF"
                    context.drawText(textRenderer, icon, x + 4, currentY + (ROW_HEIGHT - 9) / 2, color, true)
                }
            }

            // Pokemon name (modes 0, 1, 2)
            if (mode <= 2) {
                val textX = x + MODEL_SIZE + 4
                val textY = currentY + (ROW_HEIGHT - 9) / 2
                context.drawText(textRenderer, target.pokemonName, textX, textY, color, true)

                // Strikethrough if caught
                if (target.isCaught) {
                    val nameWidth = textRenderer.getWidth(target.pokemonName)
                    context.fill(textX, textY + 4, textX + nameWidth, textY + 5, CAUGHT_COLOR)
                }
            }

            // Ball icon + optional ball name (modes 0, 1)
            if (mode <= 1) {
                val textY = currentY + (ROW_HEIGHT - 9) / 2
                var ballRendered = false

                if (mode == 0) {
                    // Full mode: ball icon + ball name, right-aligned
                    val ballText = target.requiredBall
                    val ballTextWidth = textRenderer.getWidth(ballText)
                    val ballNameX = x + panelW - ballTextWidth
                    context.drawText(textRenderer, ballText, ballNameX, textY, BALL_COLOR, true)

                    if (target.ballId.isNotEmpty()) {
                        try {
                            val ballStack = ItemStack(Registries.ITEM.get(Identifier.of(target.ballId)))
                            if (!ballStack.isEmpty) {
                                context.drawItem(ballStack, ballNameX - 18, currentY + (ROW_HEIGHT - 16) / 2)
                                ballRendered = true
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    // Compact mode: ball icon only, right-aligned
                    if (target.ballId.isNotEmpty()) {
                        try {
                            val ballStack = ItemStack(Registries.ITEM.get(Identifier.of(target.ballId)))
                            if (!ballStack.isEmpty) {
                                context.drawItem(ballStack, x + panelW - 18, currentY + (ROW_HEIGHT - 16) / 2)
                                ballRendered = true
                            }
                        } catch (_: Exception) {}
                    }
                    if (!ballRendered) {
                        val ballText = target.requiredBall
                        val ballWidth = textRenderer.getWidth(ballText)
                        context.drawText(textRenderer, ballText, x + panelW - ballWidth, textY, BALL_COLOR, true)
                    }
                }
            }

            currentY += ROW_HEIGHT
        }

        // Footer
        val remaining = BoardState.remainingCount()
        val total = allTargets.size
        if (mode != 3) {
            val rewardText = BoardState.reward
            val remainText = "$remaining/$total | $rewardText"
            val remainColor = if (remaining == 0) CAUGHT_COLOR else REMAINING_COLOR
            context.drawText(textRenderer, remainText, x, currentY + 2, remainColor, true)
        } else {
            val remainText = "$remaining/$total"
            val remainColor = if (remaining == 0) CAUGHT_COLOR else REMAINING_COLOR
            val footerX = x + (panelW - textRenderer.getWidth(remainText)) / 2
            context.drawText(textRenderer, remainText, footerX, currentY + 1, remainColor, true)
        }
    }

    private fun calculatePanelWidth(
        targets: List<HuntTarget>,
        textRenderer: net.minecraft.client.font.TextRenderer,
        mode: Int
    ): Int {
        if (targets.isEmpty()) return 80

        val modelArea = MODEL_SIZE + 4
        val headerTextWidth = textRenderer.getWidth("Hunting Board") + PADDING * 2

        return when (mode) {
            3 -> MODEL_SIZE + PADDING * 3
            2 -> {
                val maxName = targets.maxOf { textRenderer.getWidth(it.pokemonName) }
                maxOf(headerTextWidth, modelArea + maxName + PADDING * 2)
            }
            1 -> {
                val maxName = targets.maxOf { textRenderer.getWidth(it.pokemonName) }
                maxOf(headerTextWidth, modelArea + maxName + 10 + 16 + PADDING)
            }
            else -> {
                val maxName = targets.maxOf { textRenderer.getWidth(it.pokemonName) }
                val maxBall = targets.maxOf { textRenderer.getWidth(it.requiredBall) }
                maxOf(headerTextWidth, modelArea + maxName + 10 + 18 + maxBall + PADDING)
            }
        }
    }

    private fun rebuildModelWidgets() {
        val newWidgets = mutableListOf<ModelWidget>()

        for (target in BoardState.targets) {
            try {
                val species = PokemonSpecies.getByName(target.speciesId)
                if (species != null) {
                    val renderablePokemon = RenderablePokemon(species, target.aspects)
                    val widget = ModelWidget(
                        pX = 0, pY = 0,
                        pWidth = MODEL_SIZE, pHeight = MODEL_SIZE,
                        pokemon = renderablePokemon,
                        baseScale = 1.2f,
                        rotationY = 325f,
                        offsetY = -16.0
                    )
                    newWidgets.add(widget)
                } else {
                    val fallbackSpecies = PokemonSpecies.getByName("bulbasaur")
                    if (fallbackSpecies != null) {
                        val widget = ModelWidget(
                            pX = 0, pY = 0,
                            pWidth = MODEL_SIZE, pHeight = MODEL_SIZE,
                            pokemon = RenderablePokemon(fallbackSpecies, emptySet()),
                            baseScale = 1.2f,
                            rotationY = 325f,
                            offsetY = -16.0
                        )
                        newWidgets.add(widget)
                    }
                }
            } catch (e: Exception) {
                HunterBoard.LOGGER.debug("Failed to create model widget for ${target.speciesId}: ${e.message}")
            }
        }

        cachedWidgets = newWidgets
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, width: Int, height: Int, color: Int) {
        context.fill(x, y, x + width, y + 1, color)
        context.fill(x, y + height - 1, x + width, y + height, color)
        context.fill(x, y, x + 1, y + height, color)
        context.fill(x + width - 1, y, x + width, y + height, color)
    }

    private fun handleDragging(client: MinecraftClient, panelW: Int, panelHeight: Int) {
        try {
            if (client.currentScreen != null) {
                isDragging = false
                dragStarted = false
                return
            }

            val mouseX = (client.mouse.x * client.window.scaledWidth / client.window.width).toInt()
            val mouseY = (client.mouse.y * client.window.scaledHeight / client.window.height).toInt()
            val isMouseDown = GLFW.glfwGetMouseButton(client.window.handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS

            val screenWidth = client.window.scaledWidth
            val screenHeight = client.window.scaledHeight
            val currentX = panelX ?: (screenWidth - panelW - 10)
            val currentPanelY = panelY ?: (screenHeight - panelHeight - 10)

            if (isMouseDown && !wasMouseDown) {
                if (mouseX >= currentX - PADDING && mouseX <= currentX + panelW + PADDING &&
                    mouseY >= currentPanelY - PADDING && mouseY <= currentPanelY + panelHeight) {
                    isDragging = true
                    dragStarted = false
                    dragOffsetX = mouseX - currentX
                    dragOffsetY = mouseY - currentPanelY
                    dragStartMouseX = mouseX
                    dragStartMouseY = mouseY
                }
            } else if (!isMouseDown) {
                // On release: snap back to bottom-right if close to default position
                if (isDragging && dragStarted && panelX != null) {
                    val defaultX = screenWidth - panelW - 10
                    val defaultY = screenHeight - panelHeight - 10
                    val dx = (panelX!! - defaultX)
                    val dy = (panelY!! - defaultY)
                    if (dx * dx + dy * dy <= SNAP_DISTANCE * SNAP_DISTANCE) {
                        panelX = null
                        panelY = null
                    }
                }
                isDragging = false
                dragStarted = false
            }

            if (isDragging && isMouseDown) {
                // Only start moving after exceeding the dead zone
                if (!dragStarted) {
                    val movedX = mouseX - dragStartMouseX
                    val movedY = mouseY - dragStartMouseY
                    if (movedX * movedX + movedY * movedY >= DRAG_DEAD_ZONE * DRAG_DEAD_ZONE) {
                        dragStarted = true
                    }
                }
                if (dragStarted) {
                    panelX = (mouseX - dragOffsetX).coerceIn(0, screenWidth - panelW - PADDING * 2)
                    panelY = (mouseY - dragOffsetY).coerceIn(0, screenHeight - panelHeight)
                }
            }

            wasMouseDown = isMouseDown
        } catch (e: Exception) {
            isDragging = false
            dragStarted = false
        }
    }
}
