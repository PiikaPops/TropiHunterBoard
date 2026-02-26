package com.hunterboard

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient

/**
 * Detects "Suppression des objets au sol dans 1 minute" chat message
 * and shows a red countdown above the XP bar during the last 10 seconds.
 */
object ClearWarningOverlay {

    // When the clear will happen (System.currentTimeMillis)
    private var clearTime: Long = 0L

    // Chat patterns (FR + EN)
    private val CLEAR_FR_REGEX = Regex("""Suppression des objets au sol dans 1 minute""", RegexOption.IGNORE_CASE)
    private val CLEAR_EN_REGEX = Regex("""Ground items will be cleared in 1 minute""", RegexOption.IGNORE_CASE)

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, isOverlay ->
            if (isOverlay) return@register
            val text = message.string
            if (CLEAR_FR_REGEX.containsMatchIn(text) || CLEAR_EN_REGEX.containsMatchIn(text)) {
                clearTime = System.currentTimeMillis() + 60_000L
                HunterBoard.LOGGER.info("ClearWarning: item clear detected, countdown set")
            }
        }

        HudRenderCallback.EVENT.register { context, _ ->
            if (clearTime == 0L) return@register
            val client = MinecraftClient.getInstance()
            if (client.player == null) return@register
            if (ModConfig.hideHudInBattle && BattleHelper.isInBattle()) return@register

            val now = System.currentTimeMillis()
            val remaining = clearTime - now

            // Only show during last 10 seconds
            if (remaining <= 0) {
                clearTime = 0L
                return@register
            }
            if (remaining > 10_000L) return@register

            val seconds = ((remaining + 999) / 1000).toInt() // ceil to avoid showing 0
            val text = "${Translations.tr("Clear")} ${seconds}s"

            val textRenderer = client.textRenderer
            val screenW = client.window.scaledWidth
            val screenH = client.window.scaledHeight

            val textW = textRenderer.getWidth(text)
            val x = (screenW - textW) / 2
            // Position: above XP bar, same as sleep message
            val y = screenH - 48

            // Blinking effect in last 2 seconds
            val blink = remaining <= 2_000L && (remaining / 250) % 2 == 0L

            if (!blink) {
                context.drawText(textRenderer, text, x, y, 0xFFFF3333.toInt(), true)
            }
        }
    }
}
