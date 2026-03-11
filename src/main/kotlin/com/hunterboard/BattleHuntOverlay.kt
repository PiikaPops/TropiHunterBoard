package com.hunterboard

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier

object BattleHuntOverlay {

    private var cachedOpponentKey: String? = null
    private var cachedBoardUpdate: Long = 0
    private var cachedTarget: HuntTarget? = null
    private var cachedBallStack: ItemStack? = null
    private var cachedBallName: String = ""

    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            try { render(context) } catch (_: Exception) {}
        }
    }

    private fun normalizeKey(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun render(context: DrawContext) {
        if (!BattleHelper.isInBattle()) return
        if (!ModConfig.showBattleHuntHud) return

        val client = MinecraftClient.getInstance()
        val player = client.player ?: return

        val opponentSpecies = BattleHelper.getOpponentSpecies() ?: return
        val opponentKey = normalizeKey(opponentSpecies.name)

        // Update cache when opponent changes or board updates
        if (opponentKey != cachedOpponentKey || cachedBoardUpdate != BoardState.lastUpdated) {
            cachedOpponentKey = opponentKey
            cachedBoardUpdate = BoardState.lastUpdated
            cachedTarget = BoardState.targets.firstOrNull {
                !it.isCaught && normalizeKey(it.speciesId) == opponentKey
            }
            cachedTarget?.let { target ->
                cachedBallStack = try {
                    if (target.ballId.isNotEmpty()) {
                        val stack = ItemStack(Registries.ITEM.get(Identifier.of(target.ballId)))
                        if (!stack.isEmpty) stack else null
                    } else null
                } catch (_: Exception) { null }
                cachedBallName = Translations.ballName(target.ballId)
            }
        }

        val target = cachedTarget ?: return

        val textRenderer = client.textRenderer
        val screenW = client.window.scaledWidth

        val titleText = "\u2694 ${Translations.tr("Hunt Pokémon!")} \u2694"
        val ballText = cachedBallName

        val titleW = textRenderer.getWidth(titleText)
        val ballTextW = textRenderer.getWidth(ballText)
        val ballIconW = if (cachedBallStack != null) 18 else 0 // 16px icon + 2px gap
        val contentW = maxOf(titleW, ballIconW + ballTextW)
        val panelW = contentW + 16 // 8px padding each side
        val panelH = 28

        val panelX = (screenW - panelW) / 2
        val panelY = 4

        val accentColor = ModConfig.accentColor()

        // Background
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, ModConfig.bgColor())
        drawBorder(context, panelX, panelY, panelW, panelH, accentColor)

        // Title line
        val titleX = panelX + (panelW - titleW) / 2
        context.drawText(textRenderer, titleText, titleX, panelY + 4, accentColor, true)

        // Ball line
        val ballLineW = ballIconW + ballTextW
        val ballLineX = panelX + (panelW - ballLineW) / 2
        if (cachedBallStack != null) {
            context.drawItem(cachedBallStack, ballLineX, panelY + 13)
            context.drawText(textRenderer, ballText, ballLineX + 18, panelY + 17, 0xFFFFFFFF.toInt(), true)
        } else {
            context.drawText(textRenderer, ballText, panelX + (panelW - ballTextW) / 2, panelY + 17, 0xFFFFFFFF.toInt(), true)
        }
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }

    /** Clear cached data (e.g. when board updates) */
    fun invalidateCache() {
        cachedOpponentKey = null
        cachedTarget = null
        cachedBallStack = null
    }
}
