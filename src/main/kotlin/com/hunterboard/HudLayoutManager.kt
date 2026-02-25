package com.hunterboard

/**
 * Prevents HUD overlays from overlapping by pushing them in the direction with the most free space.
 * HUDs registered first keep their position; later ones adjust.
 * Call order: Raid → Miracle → Hunt (Raid has highest priority).
 */
object HudLayoutManager {

    private data class HudRect(val x: Int, val y: Int, val w: Int, val h: Int)

    private val huds = mutableListOf<HudRect>()

    fun beginFrame() {
        huds.clear()
    }

    /**
     * Register a HUD's desired position and get back the adjusted position.
     * If it overlaps a previously registered HUD, it gets pushed in the direction with the most space.
     */
    fun register(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return x to y

        var ax = x; var ay = y
        for (other in huds) {
            if (rectsOverlap(ax, ay, w, h, other.x, other.y, other.w, other.h)) {
                val spaceUp = other.y
                val spaceDown = screenH - (other.y + other.h)
                val spaceLeft = other.x
                val spaceRight = screenW - (other.x + other.w)

                val maxSpace = maxOf(spaceUp, spaceDown, spaceLeft, spaceRight)
                when (maxSpace) {
                    spaceUp    -> ay = other.y - h - 2
                    spaceDown  -> ay = other.y + other.h + 2
                    spaceLeft  -> ax = other.x - w - 2
                    spaceRight -> ax = other.x + other.w + 2
                }
                ax = ax.coerceIn(0, (screenW - w).coerceAtLeast(0))
                ay = ay.coerceIn(0, (screenH - h).coerceAtLeast(0))
            }
        }
        huds.add(HudRect(ax, ay, w, h))
        return ax to ay
    }

    private fun rectsOverlap(x1: Int, y1: Int, w1: Int, h1: Int, x2: Int, y2: Int, w2: Int, h2: Int): Boolean {
        return x1 < x2 + w2 && x1 + w1 > x2 && y1 < y2 + h2 && y1 + h1 > y2
    }
}
