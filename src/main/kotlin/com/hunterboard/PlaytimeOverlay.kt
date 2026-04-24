package com.hunterboard

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient

/**
 * Displays the current session playtime in the top-left corner of the HUD.
 * Only visible when ModConfig.showPlaytime is true.
 */
object PlaytimeOverlay {

    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            if (!ModConfig.showPlaytime) return@register
            val client = MinecraftClient.getInstance()
            if (client.player == null) return@register

            val elapsed = PlaytimeTracker.elapsedMs()
            if (elapsed <= 0L) return@register

            val label = "${Translations.tr("Playtime")}: ${PlaytimeTracker.formatted()}"
            val tr = client.textRenderer
            val x = 4
            val y = 4

            // Shadow background for readability
            context.fill(x - 2, y - 2, x + tr.getWidth(label) + 2, y + tr.fontHeight + 1,
                0x66000000.toInt())
            context.drawText(tr, label, x, y, 0xFFFFFFFF.toInt(), true)
        }
    }
}
