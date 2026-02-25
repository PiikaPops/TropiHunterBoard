package com.hunterboard

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

class DonorsScreen(
    private val parent: Screen
) : Screen(Text.literal("Donors")) {

    private var scrollOffset = 0
    private val rowHeight = 14

    // Scrollbar state
    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0.0
    private var scrollbarDragStartOffset = 0
    private var sbTrackX = 0
    private var sbContentTop = 0
    private var sbContentBottom = 0
    private var sbThumbY = 0
    private var sbThumbHeight = 0

    private val goldDark = 0xFFB8860B.toInt()
    private val goldBright = 0xFFFFD700.toInt()
    private val goldText = 0xFFFFE066.toInt()
    private val goldDim = 0xFFCCA832.toInt()
    private val starColor = 0xFFFFAA00.toInt()

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        DonorsFetcher.fetchIfNeeded()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xAA000000.toInt())

        val panelWidth = (width * 0.45).toInt().coerceIn(200, 360)
        val panelX = (width - panelWidth) / 2
        val panelTop = 30
        val panelBottom = height - 30
        val panelHeight = panelBottom - panelTop

        // Panel background with golden border
        context.fill(panelX, panelTop, panelX + panelWidth, panelBottom, 0xF0101010.toInt())
        drawDoubleBorder(context, panelX, panelTop, panelWidth, panelHeight)

        // Title with stars
        val title = "\u2726 ${Translations.tr("Generous Souls")} \u2726"
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 8, goldBright, true)

        // Subtitle
        val subtitle = Translations.tr("Thank you for your support!")
        val subtitleX = panelX + (panelWidth - textRenderer.getWidth(subtitle)) / 2
        context.drawText(textRenderer, subtitle, subtitleX, panelTop + 20, goldDim, true)

        // Separator
        context.fill(panelX + 15, panelTop + 32, panelX + panelWidth - 15, panelTop + 33, goldDark)

        val donors = DonorsFetcher.donors
        val listTop = panelTop + 38
        val listBottom = panelBottom - 18
        val listAreaHeight = listBottom - listTop

        if (donors.isEmpty()) {
            val msg = if (DonorsFetcher.fetchFailed) Translations.tr("No donors yet")
                      else Translations.tr("Loading...")
            val lx = panelX + (panelWidth - textRenderer.getWidth(msg)) / 2
            context.drawText(textRenderer, msg, lx, listTop + 10, 0xFF666666.toInt(), true)
        } else {
            context.enableScissor(panelX + 1, listTop, panelX + panelWidth - 1, listBottom)

            var y = listTop + 2 - scrollOffset
            for ((index, name) in donors.withIndex()) {
                if (y + rowHeight > listTop - rowHeight && y < listBottom + rowHeight) {
                    val hovered = mouseX >= panelX + 10 && mouseX <= panelX + panelWidth - 10 &&
                            mouseY >= y && mouseY <= y + rowHeight &&
                            mouseY >= listTop && mouseY <= listBottom

                    if (hovered) {
                        context.fill(panelX + 8, y, panelX + panelWidth - 8, y + rowHeight, 0x22FFD700.toInt())
                    }

                    // Star prefix
                    val star = "\u2605"
                    val starX = panelX + 12
                    context.drawText(textRenderer, star, starX, y + 3, starColor, true)

                    // Name centered after star
                    val nameX = starX + textRenderer.getWidth(star) + 4
                    val nameColor = if (hovered) goldBright else goldText
                    context.drawText(textRenderer, name, nameX, y + 3, nameColor, true)

                    // Rank number on right
                    val rank = "#${index + 1}"
                    val rankX = panelX + panelWidth - 14 - textRenderer.getWidth(rank)
                    context.drawText(textRenderer, rank, rankX, y + 3, 0xFF666655.toInt(), true)
                }
                y += rowHeight
            }

            context.disableScissor()

            // Scrollbar
            val contentHeight = donors.size * rowHeight + 4
            val maxScroll = maxOf(0, contentHeight - listAreaHeight)
            if (contentHeight > listAreaHeight && listAreaHeight > 0) {
                sbTrackX = panelX + panelWidth - 5
                sbContentTop = listTop
                sbContentBottom = listBottom
                context.fill(sbTrackX, listTop, sbTrackX + 3, listBottom, 0xFF1A1A1A.toInt())
                sbThumbHeight = maxOf(15, listAreaHeight * listAreaHeight / contentHeight)
                sbThumbY = listTop + (scrollOffset * (listAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
                context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, goldDark)
            }
        }

        // Footer
        context.fill(panelX + 1, panelBottom - 16, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 10, panelBottom - 16, panelX + panelWidth - 10, panelBottom - 15, goldDark and 0x44FFFFFF.toInt())
        val count = if (donors.isNotEmpty()) "${donors.size} ${Translations.tr("donors")}" else ""
        val hint = if (count.isNotEmpty()) "$count  \u2022  ${Translations.tr("ESC to return")}"
                   else Translations.tr("ESC to return")
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 12, 0xFF555544.toInt(), true)

        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        val donors = DonorsFetcher.donors
        if (donors.isEmpty()) return super.mouseClicked(mouseX, mouseY, button)

        val panelWidth = (width * 0.45).toInt().coerceIn(200, 360)
        val panelX = (width - panelWidth) / 2
        val panelTop = 30
        val panelBottom = height - 30
        val listTop = panelTop + 38
        val listBottom = panelBottom - 18
        val listAreaHeight = listBottom - listTop
        val contentHeight = donors.size * rowHeight + 4

        if (contentHeight > listAreaHeight && mouseX >= sbTrackX && mouseX <= sbTrackX + 3 &&
            mouseY >= sbContentTop.toDouble() && mouseY <= sbContentBottom.toDouble()) {
            isScrollbarDragging = true
            scrollbarDragStartY = mouseY
            scrollbarDragStartOffset = scrollOffset
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (isScrollbarDragging && button == 0) {
            val donors = DonorsFetcher.donors
            val panelTop = 30
            val panelBottom = height - 30
            val listTop = panelTop + 38
            val listBottom = panelBottom - 18
            val listAreaHeight = listBottom - listTop
            val contentHeight = donors.size * rowHeight + 4
            val maxScroll = maxOf(0, contentHeight - listAreaHeight)
            val trackH = listAreaHeight - sbThumbHeight
            if (trackH > 0) {
                val deltaScroll = ((mouseY - scrollbarDragStartY) * maxScroll / trackH).toInt()
                scrollOffset = (scrollbarDragStartOffset + deltaScroll).coerceIn(0, maxScroll)
            }
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && isScrollbarDragging) { isScrollbarDragging = false; return true }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val donors = DonorsFetcher.donors
        if (donors.isEmpty()) return true
        val panelTop = 30
        val panelBottom = height - 30
        val listTop = panelTop + 38
        val listBottom = panelBottom - 18
        val listAreaHeight = listBottom - listTop
        val contentHeight = donors.size * rowHeight + 4
        val maxScroll = maxOf(0, contentHeight - listAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { client?.setScreen(parent); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() { client?.setScreen(parent) }

    private fun drawDoubleBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        // Outer border - dark gold
        context.fill(x, y, x + w, y + 1, goldDark)
        context.fill(x, y + h - 1, x + w, y + h, goldDark)
        context.fill(x, y, x + 1, y + h, goldDark)
        context.fill(x + w - 1, y, x + w, y + h, goldDark)
        // Inner border - bright gold (1px inset)
        context.fill(x + 1, y + 1, x + w - 1, y + 2, goldBright)
        context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, goldBright)
        context.fill(x + 1, y + 1, x + 2, y + h - 1, goldBright)
        context.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, goldBright)
    }
}
