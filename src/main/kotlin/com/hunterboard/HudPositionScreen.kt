package com.hunterboard

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

class HudPositionScreen(
    private val parent: Screen
) : Screen(Text.literal("HUD Position")) {

    // Which panel is being dragged: "hunt" | "raid" | "miracle" | null
    private var draggingPanel: String? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    // Corner-drag resizing. The starting size and scale are frozen when the handle is
    // grabbed: deriving the new scale from the live size would feed back on itself and
    // make the HUD run away from the cursor.
    private val HANDLE = 9
    private var resizingPanel: String? = null
    private var resizeOriginX = 0
    private var resizeOriginY = 0
    private var resizeStartW = 1
    private var resizeStartH = 1
    private var resizeStartScale = 1f

    private var originalHuntScale = 1f
    private var originalRaidScale = 1f
    private var originalMiracleScale = 1f

    // Snap-to-align
    private val SNAP_DIST = 7
    private var snapGuideX: Int? = null
    private var snapGuideY: Int? = null

    // Save original positions to restore on cancel
    private var originalHuntX: Int? = null
    private var originalHuntY: Int? = null
    private var originalRaidX: Int? = null
    private var originalRaidY: Int? = null
    private var originalMiracleX: Int? = null
    private var originalMiracleY: Int? = null

    override fun init() {
        super.init()
        originalHuntX = HuntOverlay.panelX
        originalHuntY = HuntOverlay.panelY
        originalRaidX = RaidTimerOverlay.panelX
        originalRaidY = RaidTimerOverlay.panelY
        originalMiracleX = MiracleOverlay.panelX
        originalMiracleY = MiracleOverlay.panelY
        originalHuntScale = ModConfig.huntScale()
        originalRaidScale = ModConfig.raidScale()
        originalMiracleScale = ModConfig.miracleScale()
        HuntOverlay.forceVisible = true
    }

    /** The three HUDs as (id, x, y, w, h), skipping the ones not on screen. */
    private fun panels(): List<Triple<String, IntArray, Float>> {
        val list = mutableListOf<Triple<String, IntArray, Float>>()
        list.add(Triple("hunt",
            intArrayOf(HuntOverlay.renderedX, HuntOverlay.renderedY, HuntOverlay.renderedW, HuntOverlay.renderedH),
            ModConfig.huntScale()))
        if (RaidTimerOverlay.renderedW > 0) list.add(Triple("raid",
            intArrayOf(RaidTimerOverlay.renderedX, RaidTimerOverlay.renderedY,
                RaidTimerOverlay.renderedW, RaidTimerOverlay.renderedH),
            ModConfig.raidScale()))
        if (MiracleOverlay.renderedW > 0) list.add(Triple("miracle",
            intArrayOf(MiracleOverlay.renderedX, MiracleOverlay.renderedY,
                MiracleOverlay.renderedW, MiracleOverlay.renderedH),
            ModConfig.miracleScale()))
        return list
    }

    private fun applyScale(panel: String, scale: Float) {
        when (panel) {
            "hunt" -> ModConfig.setHuntScale(scale)
            "raid" -> ModConfig.setRaidScale(scale)
            "miracle" -> ModConfig.setMiracleScale(scale)
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0x44000000.toInt())

        // Snap alignment guides (shown while dragging)
        if (draggingPanel != null) {
            snapGuideX?.let { context.fill(it, 0, it + 1, height, 0x66FFFF00.toInt()) }
            snapGuideY?.let { context.fill(0, it, width, it + 1, 0x66FFFF00.toInt()) }
        }

        // Outline every HUD and put a grab handle on its bottom-right corner
        for ((id, box, scale) in panels()) {
            val (px, py, pw, ph) = listOf(box[0], box[1], box[2], box[3])
            if (pw <= 0 || ph <= 0) continue

            val active = resizingPanel == id || draggingPanel == id
            val outline = if (active) UiKit.accent() else 0x66FFFFFF
            context.fill(px, py, px + pw, py + 1, outline)
            context.fill(px, py + ph - 1, px + pw, py + ph, outline)
            context.fill(px, py, px + 1, py + ph, outline)
            context.fill(px + pw - 1, py, px + pw, py + ph, outline)

            val hx = px + pw - HANDLE
            val hy = py + ph - HANDLE
            val handleHot = resizingPanel == id ||
                (mouseX >= hx && mouseX <= hx + HANDLE && mouseY >= hy && mouseY <= hy + HANDLE)
            context.fill(hx, hy, hx + HANDLE, hy + HANDLE,
                if (handleHot) UiKit.accent() else 0xAAFFFFFF.toInt())
            // three diagonal notches, the usual "resize" hint
            for (i in 1..3) {
                context.fill(hx + HANDLE - i * 2 - 1, hy + i * 2, hx + HANDLE - i * 2, hy + i * 2 + 1, 0xFF202020.toInt())
            }

            if (handleHot || active) {
                val pct = "${kotlin.math.round(scale * 100f).toInt()}%"
                context.drawText(textRenderer, pct, px + pw + 4, py + ph - 8, UiKit.accent(), true)
            }
        }

        val instructionText: String = Translations.tr("Drag to move HUD")
        val instrX = (width - textRenderer.getWidth(instructionText)) / 2
        context.drawText(textRenderer, instructionText, instrX, 10, 0xFFFFFFFF.toInt(), true)

        val resizeHint: String = Translations.tr("Drag a corner to resize")
        context.drawText(textRenderer, resizeHint,
            (width - textRenderer.getWidth(resizeHint)) / 2, 22, 0xFFBBBBBB.toInt(), true)

        // Confirm button centered on screen
        val confirmLabel: String = Translations.tr("Confirm")
        val btnW = textRenderer.getWidth(confirmLabel) + 20
        val btnH = 20
        val btnX = (width - btnW) / 2
        val btnY = height / 2 - btnH / 2

        val btnHovered = mouseX >= btnX && mouseX <= btnX + btnW &&
                         mouseY >= btnY && mouseY <= btnY + btnH

        UiKit.button(context, textRenderer, btnX, btnY, btnW, btnH, "", btnHovered)

        val textColor = if (btnHovered) 0xFFFFFF00.toInt() else 0xFFFFFFFF.toInt()
        val textX = btnX + (btnW - textRenderer.getWidth(confirmLabel)) / 2
        val textY = btnY + (btnH - 8) / 2
        context.drawText(textRenderer, confirmLabel, textX, textY, textColor, true)

        // Both HUDs render automatically via HudRenderCallback
        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)

        // Confirm button
        val confirmLabel: String = Translations.tr("Confirm")
        val btnW = textRenderer.getWidth(confirmLabel) + 20
        val btnH = 20
        val btnX = (width - btnW) / 2
        val btnY = height / 2 - btnH / 2

        if (mouseX >= btnX && mouseX <= btnX + btnW &&
            mouseY >= btnY.toDouble() && mouseY <= (btnY + btnH).toDouble()) {
            saveAndClose()
            return true
        }

        // A corner handle wins over dragging the panel body
        for ((id, box, scale) in panels()) {
            val handleX = box[0] + box[2] - HANDLE
            val handleY = box[1] + box[3] - HANDLE
            if (box[2] > 0 && mouseX >= handleX && mouseX <= handleX + HANDLE &&
                mouseY >= handleY.toDouble() && mouseY <= (handleY + HANDLE).toDouble()) {
                resizingPanel = id
                resizeOriginX = box[0]
                resizeOriginY = box[1]
                resizeStartW = box[2].coerceAtLeast(1)
                resizeStartH = box[3].coerceAtLeast(1)
                resizeStartScale = scale
                return true
            }
        }

        // Check click on hunt overlay
        val hx = HuntOverlay.renderedX; val hy = HuntOverlay.renderedY
        val hw = HuntOverlay.renderedW; val hh = HuntOverlay.renderedH
        if (mouseX >= hx && mouseX <= hx + hw && mouseY >= hy && mouseY <= hy + hh) {
            draggingPanel = "hunt"
            dragOffsetX = (mouseX - hx).toInt()
            dragOffsetY = (mouseY - hy).toInt()
            return true
        }

        // Check click on raid timer
        val rx = RaidTimerOverlay.renderedX; val ry = RaidTimerOverlay.renderedY
        val rw = RaidTimerOverlay.renderedW; val rh = RaidTimerOverlay.renderedH
        if (rw > 0 && mouseX >= rx && mouseX <= rx + rw && mouseY >= ry && mouseY <= ry + rh) {
            draggingPanel = "raid"
            dragOffsetX = (mouseX - rx).toInt()
            dragOffsetY = (mouseY - ry).toInt()
            return true
        }

        // Check click on miracle overlay
        val mx = MiracleOverlay.renderedX; val my = MiracleOverlay.renderedY
        val mw = MiracleOverlay.renderedW; val mh = MiracleOverlay.renderedH
        if (mw > 0 && mouseX >= mx && mouseX <= mx + mw && mouseY >= my && mouseY <= my + mh) {
            draggingPanel = "miracle"
            dragOffsetX = (mouseX - mx).toInt()
            dragOffsetY = (mouseY - my).toInt()
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        val resizing = resizingPanel
        if (resizing != null && button == 0) {
            // Whichever axis the cursor moved furthest along drives the scale, so the
            // HUD keeps its proportions instead of stretching.
            val wantW = (mouseX - resizeOriginX).toFloat().coerceAtLeast(8f)
            val wantH = (mouseY - resizeOriginY).toFloat().coerceAtLeast(8f)
            val ratio = kotlin.math.max(wantW / resizeStartW, wantH / resizeStartH)
            applyScale(resizing, resizeStartScale * ratio)
            return true
        }
        if (draggingPanel != null && button == 0) {
            val sw = client?.window?.scaledWidth  ?: width
            val sh = client?.window?.scaledHeight ?: height

            when (draggingPanel) {
                "hunt" -> {
                    val rawX = (mouseX - dragOffsetX).toInt()
                    val rawY = (mouseY - dragOffsetY).toInt()
                    val (sx, gx) = snapAxis(rawX, HuntOverlay.renderedW, getRefX("hunt", sw))
                    val (sy, gy) = snapAxis(rawY, HuntOverlay.renderedH, getRefY("hunt", sh))
                    snapGuideX = gx; snapGuideY = gy
                    // HuntOverlay stores panelX = renderedX + PADDING(5)
                    HuntOverlay.panelX = sx.coerceIn(0, sw - HuntOverlay.renderedW) + 5
                    HuntOverlay.panelY = sy.coerceIn(0, sh - HuntOverlay.renderedH) + 5
                }
                "raid" -> {
                    val rawX = (mouseX - dragOffsetX).toInt()
                    val rawY = (mouseY - dragOffsetY).toInt()
                    val (sx, gx) = snapAxis(rawX, RaidTimerOverlay.renderedW, getRefX("raid", sw))
                    val (sy, gy) = snapAxis(rawY, RaidTimerOverlay.renderedH, getRefY("raid", sh))
                    snapGuideX = gx; snapGuideY = gy
                    RaidTimerOverlay.panelX = sx.coerceIn(0, sw - RaidTimerOverlay.renderedW)
                    RaidTimerOverlay.panelY = sy.coerceIn(0, sh - RaidTimerOverlay.renderedH)
                }
                "miracle" -> {
                    val rawX = (mouseX - dragOffsetX).toInt()
                    val rawY = (mouseY - dragOffsetY).toInt()
                    val (sx, gx) = snapAxis(rawX, MiracleOverlay.renderedW, getRefX("miracle", sw))
                    val (sy, gy) = snapAxis(rawY, MiracleOverlay.renderedH, getRefY("miracle", sh))
                    snapGuideX = gx; snapGuideY = gy
                    MiracleOverlay.panelX = sx.coerceIn(0, sw - MiracleOverlay.renderedW)
                    MiracleOverlay.panelY = sy.coerceIn(0, sh - MiracleOverlay.renderedH)
                }
            }
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && resizingPanel != null) {
            resizingPanel = null
            return true
        }
        if (button == 0 && draggingPanel != null) {
            draggingPanel = null
            snapGuideX = null
            snapGuideY = null
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            restoreAndClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() = restoreAndClose()

    // ==================== Snap helpers ====================

    /** Returns [renderedX, renderedY, renderedW, renderedH] for a given panel, or null if hidden. */
    private fun getBoundsOf(panel: String): IntArray? = when (panel) {
        "hunt"    -> HuntOverlay.renderedW.takeIf    { it > 0 }?.let { intArrayOf(HuntOverlay.renderedX,    HuntOverlay.renderedY,    HuntOverlay.renderedW,    HuntOverlay.renderedH) }
        "raid"    -> RaidTimerOverlay.renderedW.takeIf{ it > 0 }?.let { intArrayOf(RaidTimerOverlay.renderedX, RaidTimerOverlay.renderedY, RaidTimerOverlay.renderedW, RaidTimerOverlay.renderedH) }
        "miracle" -> MiracleOverlay.renderedW.takeIf  { it > 0 }?.let { intArrayOf(MiracleOverlay.renderedX,  MiracleOverlay.renderedY,  MiracleOverlay.renderedW,  MiracleOverlay.renderedH) }
        else -> null
    }

    /** X reference lines: screen edges/center + edges/centers of other visible panels. */
    private fun getRefX(exclude: String, sw: Int): List<Int> {
        val refs = mutableListOf(0, sw / 2, sw)
        for (p in listOf("hunt", "raid", "miracle")) {
            if (p == exclude) continue
            val b = getBoundsOf(p) ?: continue
            refs += listOf(b[0], b[0] + b[2] / 2, b[0] + b[2])
        }
        return refs
    }

    /** Y reference lines: screen edges/center + edges/centers of other visible panels. */
    private fun getRefY(exclude: String, sh: Int): List<Int> {
        val refs = mutableListOf(0, sh / 2, sh)
        for (p in listOf("hunt", "raid", "miracle")) {
            if (p == exclude) continue
            val b = getBoundsOf(p) ?: continue
            refs += listOf(b[1], b[1] + b[3] / 2, b[1] + b[3])
        }
        return refs
    }

    /**
     * Snaps [raw] (left/top edge of a panel with [size]) to the nearest reference.
     * Checks left edge, center, and right/bottom edge independently.
     * Returns (snappedPos, guideLinePos?) — guide is null if no snap triggered.
     */
    private fun snapAxis(raw: Int, size: Int, refs: List<Int>): Pair<Int, Int?> {
        var best = raw
        var guide: Int? = null
        var bestDist = SNAP_DIST + 1
        for (ref in refs) {
            val dl = kotlin.math.abs(raw - ref)                   // left/top edge → ref
            if (dl < bestDist) { bestDist = dl; best = ref;              guide = ref }
            val dc = kotlin.math.abs(raw + size / 2 - ref)        // center → ref
            if (dc < bestDist) { bestDist = dc; best = ref - size / 2;   guide = ref }
            val dr = kotlin.math.abs(raw + size - ref)            // right/bottom edge → ref
            if (dr < bestDist) { bestDist = dr; best = ref - size;       guide = ref }
        }
        return best to guide
    }

    // ==================== Save / Restore ====================

    private fun saveAndClose() {
        val hpx = HuntOverlay.panelX; val hpy = HuntOverlay.panelY
        if (hpx != null && hpy != null) ModConfig.setHudPosition(hpx, hpy)
        else ModConfig.resetHudPosition()

        val rpx = RaidTimerOverlay.panelX; val rpy = RaidTimerOverlay.panelY
        if (rpx != null && rpy != null) ModConfig.setRaidTimerPosition(rpx, rpy)
        else ModConfig.resetRaidTimerPosition()

        val mpx = MiracleOverlay.panelX; val mpy = MiracleOverlay.panelY
        if (mpx != null && mpy != null) ModConfig.setMiraclePosition(mpx, mpy)
        else ModConfig.resetMiraclePosition()

        HuntOverlay.forceVisible = false
        client?.setScreen(parent)
    }

    private fun restoreAndClose() {
        HuntOverlay.panelX = originalHuntX
        HuntOverlay.panelY = originalHuntY
        RaidTimerOverlay.panelX = originalRaidX
        RaidTimerOverlay.panelY = originalRaidY
        MiracleOverlay.panelX = originalMiracleX
        MiracleOverlay.panelY = originalMiracleY
        // sizes are cancelled too: a resize is applied live, like a move
        ModConfig.setHuntScale(originalHuntScale)
        ModConfig.setRaidScale(originalRaidScale)
        ModConfig.setMiracleScale(originalMiracleScale)
        HuntOverlay.forceVisible = false
        client?.setScreen(parent)
    }
}
