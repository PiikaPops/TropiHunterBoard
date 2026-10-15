package com.hunterboard

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Identifier

/**
 * Counts down to the server's one-minute clear warning.
 *
 * Tropimon announces three separate clears — ground items, entities, and Cobblemon
 * entities — and they arrive together, within the same second. The last of the three is
 * the one that matters to a hunter: it despawns the wild Pokemon. One countdown covers
 * all three; a second announcement simply restarts the same timer.
 *
 * The wording is matched loosely on purpose. Enumerating the exact sentences is what left
 * two of the three undetected when the server added them, and the English text of the new
 * ones is unknown — so the shape of the sentence is matched instead of its content.
 */
object ClearWarningOverlay {

    private var clearTime: Long = 0L
    private var lastSoundSecond: Int = -1

    // "Suppression des objets au sol dans 1 minute"
    // "Suppression des entités dans 1 minute"
    // "Suppression des entités Cobblemon dans 1 minute."
    private val CLEAR_FR_REGEX =
        Regex("""Suppression des .{1,40}? dans 1 minute""", RegexOption.IGNORE_CASE)
    // "Ground items will be cleared in 1 minute" and whatever the two newer ones read as
    private val CLEAR_EN_REGEX =
        Regex(""".{1,40}? will be cleared in 1 minute""", RegexOption.IGNORE_CASE)

    private fun isClearWarning(text: String): Boolean =
        CLEAR_FR_REGEX.containsMatchIn(text) || CLEAR_EN_REGEX.containsMatchIn(text)

    /** Start (or restart) the one-minute countdown. */
    private fun arm(text: String, source: String) {
        clearTime = System.currentTimeMillis() + 60_000L
        lastSoundSecond = -1
        HunterBoard.LOGGER.info("ClearWarning: armed from $source — ${text.trim()}")
    }

    /** A pending countdown belongs to the world that announced it. */
    fun resetSession() {
        clearTime = 0L
        lastSoundSecond = -1
    }

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, isOverlay ->
            if (isOverlay) return@register
            val text = message.string
            if (isClearWarning(text)) arm(text, "chat")
        }

        // The item-clear warning may also arrive as a notification rather than a chat
        // line; the text is matched the same way either way.
        TropiNotifications.onNotification { notif ->
            if (isClearWarning(notif.text)) arm(notif.text, "notification")
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
