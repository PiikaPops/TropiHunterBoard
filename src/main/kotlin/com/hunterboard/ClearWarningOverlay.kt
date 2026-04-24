package com.hunterboard

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Identifier

/**
 * Detects "Suppression des objets au sol dans 1 minute" chat message
 * and shows a red countdown above the XP bar during the last 10 seconds.
 * Plays the configured sound once per second for each of the 10 remaining seconds.
 */
object ClearWarningOverlay {

    private var clearTime: Long = 0L
    private var lastSoundSecond: Int = -1

    private val CLEAR_FR_REGEX = Regex("""Suppression des objets au sol dans 1 minute""", RegexOption.IGNORE_CASE)
    private val CLEAR_EN_REGEX = Regex("""Ground items will be cleared in 1 minute""", RegexOption.IGNORE_CASE)

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, isOverlay ->
            if (isOverlay) return@register
            val text = message.string
            if (CLEAR_FR_REGEX.containsMatchIn(text) || CLEAR_EN_REGEX.containsMatchIn(text)) {
                clearTime = System.currentTimeMillis() + 60_000L
                lastSoundSecond = -1
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

            if (remaining <= 0) {
                clearTime = 0L
                lastSoundSecond = -1
                return@register
            }
            if (remaining > 5_000L) return@register

            // Play sound once per second (seconds 5 down to 1)
            val secondsLeft = ((remaining + 999) / 1000).toInt()
            if (ModConfig.clearWarningSound && secondsLeft != lastSoundSecond) {
                lastSoundSecond = secondsLeft
                try {
                    val id = Identifier.of(ModConfig.clearWarningSoundId)
                    val sound = Registries.SOUND_EVENT.get(id) ?: SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP
                    client.player?.playSound(sound, 0.6f, 1.2f)
                } catch (_: Exception) {}
            }

            val text = "${Translations.tr("Clear")} ${secondsLeft}s"
            val textRenderer = client.textRenderer
            val screenW = client.window.scaledWidth
            val screenH = client.window.scaledHeight

            val x = (screenW - textRenderer.getWidth(text)) / 2
            val y = screenH - 48

            val blink = remaining <= 2_000L && (remaining / 250) % 2 == 0L
            if (!blink) {
                context.drawText(textRenderer, text, x, y, 0xFFFF3333.toInt(), true)
            }
        }
    }
}
