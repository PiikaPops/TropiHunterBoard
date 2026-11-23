package com.hunterboard

import net.minecraft.client.MinecraftClient

/**
 * Keeps the interface readable whatever Minecraft's GUI scale is set to.
 *
 * Minecraft's GUI scale multiplies every pixel, so at scale 3 a 1080p screen only offers
 * 640×360 GUI pixels — the spawn table needs about 700 across and simply ran off the
 * screen. The panels are not made smaller to compensate: instead the whole screen is
 * drawn through a matrix scaled by `target / actual`, so at GUI scale 3 with a cap of 2
 * everything is laid out in 960×540 virtual pixels and then drawn at exactly 2× physical
 * pixels. Text stays pixel-aligned because the cap divides into the real factor.
 *
 * Only ever scales *down*: a player on GUI scale 1 or 2 sees no change.
 */
object UiScale {

    /** Caps offered by the options button; 0 means "follow Minecraft". */
    val CAPS = listOf(0, 2, 3, 4)

    /**
     * How much to shrink the interface by, 1.0 when no capping applies.
     *
     * [MinecraftClient.getWindow]'s scale factor is the real one in force, which is what
     * matters: the raw `guiScale` option can be 0 ("auto") and does not say what auto
     * resolved to.
     */
    fun factor(): Float {
        val cap = ModConfig.uiScaleCap
        if (cap <= 0) return 1f
        val actual = try {
            MinecraftClient.getInstance().window.scaleFactor
        } catch (_: Exception) { return 1f }
        if (actual <= cap) return 1f
        return (cap / actual).toFloat()
    }

    /** Label for the options button. */
    fun capLabel(cap: Int): String =
        if (cap <= 0) Translations.tr("Minecraft") else "${cap}x"
}
