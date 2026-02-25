package com.hunterboard

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class OptionsScreen(
    private val parent: Screen
) : Screen(Text.literal("Options")) {

    // Slider drag state
    private var draggingSlider: String? = null // "r", "g", "b", "opacity"

    // Layout bounds (computed in render)
    private var sliderX = 0
    private var sliderW = 0

    // Hex color input
    private lateinit var hexField: TextFieldWidget
    private var updatingFromSlider = false

    // Size preset labels (index matches ModConfig.hudSizePreset)
    private val SIZE_LABELS = arrayOf("Small", "Normal", "Large", "Extra Large")

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val leftX = panelX + 12

        hexField = TextFieldWidget(textRenderer, leftX + 12, 0, 52, 12, Text.literal("Hex"))
        hexField.setMaxLength(6)
        hexField.text = "%02X%02X%02X".format(ModConfig.hudColorR, ModConfig.hudColorG, ModConfig.hudColorB)
        hexField.setTextPredicate { text -> text.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
        hexField.setChangedListener { text ->
            if (!updatingFromSlider) {
                val hex = text.trim()
                if (hex.length == 6) {
                    try {
                        val r = hex.substring(0, 2).toInt(16)
                        val g = hex.substring(2, 4).toInt(16)
                        val b = hex.substring(4, 6).toInt(16)
                        ModConfig.setColor(r, g, b)
                    } catch (_: Exception) {}
                }
            }
        }
        addDrawableChild(hexField)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xAA000000.toInt())

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        context.fill(panelX, panelTop, panelX + panelWidth, panelBottom, 0xF0101010.toInt())
        drawBorder(context, panelX, panelTop, panelWidth, panelHeight, ModConfig.accentColor())

        val title: String = Translations.tr("Options")
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 6, ModConfig.accentColor(), true)

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        val closeHovered = mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2 && mouseY <= closeY + 11
        context.drawText(textRenderer, "\u2715", closeX, closeY, if (closeHovered) 0xFFFF5555.toInt() else 0xFF888888.toInt(), true)

        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, ModConfig.accentColor())
        context.fill(panelX + 6, panelTop + 19, panelX + panelWidth - 6, panelTop + 20, 0xFF442200.toInt())

        val leftX = panelX + 12
        val rightX = panelX + panelWidth - 12
        val contentW = rightX - leftX
        sliderX = leftX + 80
        sliderW = contentW - 80

        var y = panelTop + 28

        // ========== HUD SECTION ==========
        val hudLabel: String = Translations.tr("HUD")
        context.drawText(textRenderer, hudLabel, leftX, y, ModConfig.accentColor(), true)

        val resetLabel: String = Translations.tr("Reset")
        val resetBtnW = textRenderer.getWidth(resetLabel) + 8
        val resetBtnX = panelX + panelWidth - resetBtnW - 22
        val resetBtnY = panelTop + 4
        val resetHovered = mouseX >= resetBtnX && mouseX <= resetBtnX + resetBtnW &&
                           mouseY >= resetBtnY && mouseY <= resetBtnY + 14
        renderButton(context, resetBtnX, resetBtnY, resetBtnW, 14, resetLabel, resetHovered)
        y += 14

        // Mode + Position on same row
        val modeLabel: String = Translations.tr("Mode:")
        val modeLabelText: String = Translations.tr(BoardState.MODE_LABELS[BoardState.displayMode])
        val modeText = "$modeLabel $modeLabelText"
        val widestMode: String = Translations.tr("Compact")
        val modeBtnW = textRenderer.getWidth("$modeLabel $widestMode") + 10
        val modeHovered = mouseX >= leftX && mouseX <= leftX + modeBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, modeBtnW, 14, modeText, modeHovered)

        val posLabel: String = Translations.tr("Position")
        val posBtnW = textRenderer.getWidth(posLabel) + 10
        val posBtnX = rightX - posBtnW
        val posHovered = mouseX >= posBtnX && mouseX <= posBtnX + posBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, posBtnX, y, posBtnW, 14, posLabel, posHovered)
        y += 18

        // Grid Layout toggle + Pixel Art toggle
        val gridLabel: String = if (ModConfig.gridLayout) Translations.tr("List Display") else Translations.tr("Square Display")
        val gridBtnW = textRenderer.getWidth(gridLabel) + 10
        val gridHovered = mouseX >= leftX && mouseX <= leftX + gridBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, gridBtnW, 14, gridLabel, gridHovered)

        val paLabel: String = if (ModConfig.usePixelArt) Translations.tr("3D Models") else Translations.tr("2D Model")
        val paBtnW = textRenderer.getWidth(paLabel) + 10
        val paStart = leftX + gridBtnW + 6
        val paHov = mouseX >= paStart && mouseX <= paStart + paBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, paStart, y, paBtnW, 14, paLabel, paHov)
        y += 18

        // Size preset buttons (Small / Normal / Large)
        val sizeLabel: String = Translations.tr("Size")
        context.drawText(textRenderer, sizeLabel, leftX, y + 4, 0xFFBBBBBB.toInt(), true)
        var sx = leftX + textRenderer.getWidth(sizeLabel) + 8
        for ((i, key) in SIZE_LABELS.withIndex()) {
            val label: String = Translations.tr(key)
            val btnW = textRenderer.getWidth(label) + 10
            val isSelected = i == ModConfig.hudSizePreset
            val isHov = mouseX >= sx && mouseX <= sx + btnW && mouseY >= y && mouseY <= y + 14
            val bg = when {
                isSelected -> (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt()
                isHov -> 0xFF252525.toInt()
                else -> 0xFF1A1A1A.toInt()
            }
            context.fill(sx, y, sx + btnW, y + 14, bg)
            val bc = if (isSelected) ModConfig.accentColor() else if (isHov) 0xFF666666.toInt() else 0xFF444444.toInt()
            drawBorder(context, sx, y, btnW, 14, bc)
            val tc = if (isSelected) ModConfig.accentColor() else if (isHov) 0xFFDDDDDD.toInt() else 0xFF888888.toInt()
            context.drawText(textRenderer, label, sx + 5, y + 3, tc, true)
            sx += btnW + 3
        }
        y += 20

        // ========== HUD VISIBILITY ==========
        y += 2
        val showLabel: String = Translations.tr("Show:")
        context.drawText(textRenderer, showLabel, leftX, y + 3, 0xFFBBBBBB.toInt(), true)
        var vx = leftX + textRenderer.getWidth(showLabel) + 8

        val raidLabel = "Raid: ${if (ModConfig.showRaidHud) "ON" else "OFF"}"
        val raidToggleW = textRenderer.getWidth("Raid: OFF") + 10
        val raidHov = mouseX >= vx && mouseX <= vx + raidToggleW && mouseY >= y && mouseY <= y + 14
        context.fill(vx, y, vx + raidToggleW, y + 14,
            if (ModConfig.showRaidHud) (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, vx, y, raidToggleW, 14,
            if (raidHov || ModConfig.showRaidHud) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, raidLabel, vx + 5, y + 3,
            if (ModConfig.showRaidHud) ModConfig.accentColor() else 0xFF666666.toInt(), true)
        vx += raidToggleW + 6

        val miracleLabel = "Miracle: ${if (ModConfig.showMiracleHud) "ON" else "OFF"}"
        val miracleToggleW = textRenderer.getWidth("Miracle: OFF") + 10
        val miracleHov = mouseX >= vx && mouseX <= vx + miracleToggleW && mouseY >= y && mouseY <= y + 14
        context.fill(vx, y, vx + miracleToggleW, y + 14,
            if (ModConfig.showMiracleHud) (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, vx, y, miracleToggleW, 14,
            if (miracleHov || ModConfig.showMiracleHud) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, miracleLabel, vx + 5, y + 3,
            if (ModConfig.showMiracleHud) ModConfig.accentColor() else 0xFF666666.toInt(), true)
        vx += miracleToggleW + 6

        val huntLabel = "Hunt: ${if (ModConfig.showHuntHud) "ON" else "OFF"}"
        val huntToggleW = textRenderer.getWidth("Hunt: OFF") + 10
        val huntHov = mouseX >= vx && mouseX <= vx + huntToggleW && mouseY >= y && mouseY <= y + 14
        context.fill(vx, y, vx + huntToggleW, y + 14,
            if (ModConfig.showHuntHud) (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, vx, y, huntToggleW, 14,
            if (huntHov || ModConfig.showHuntHud) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, huntLabel, vx + 5, y + 3,
            if (ModConfig.showHuntHud) ModConfig.accentColor() else 0xFF666666.toInt(), true)
        y += 18

        // Full Clear + Merged HUD toggles
        val fcLabel = "${Translations.tr("Full Clear")}: ${if (ModConfig.fullClearMode) "ON" else "OFF"}"
        val fcBtnW = textRenderer.getWidth("${Translations.tr("Full Clear")}: OFF") + 10
        val fcHov = mouseX >= leftX && mouseX <= leftX + fcBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + fcBtnW, y + 14,
            if (ModConfig.fullClearMode) (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, leftX, y, fcBtnW, 14,
            if (fcHov || ModConfig.fullClearMode) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, fcLabel, leftX + 5, y + 3,
            if (ModConfig.fullClearMode) ModConfig.accentColor() else 0xFF666666.toInt(), true)

        val mhStart = leftX + fcBtnW + 6
        val mhLabel = "${Translations.tr("Merged HUD")}: ${if (ModConfig.mergedHudMode) "ON" else "OFF"}"
        val mhBtnW = textRenderer.getWidth("${Translations.tr("Merged HUD")}: OFF") + 10
        val mhHov = mouseX >= mhStart && mouseX <= mhStart + mhBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(mhStart, y, mhStart + mhBtnW, y + 14,
            if (ModConfig.mergedHudMode) (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, mhStart, y, mhBtnW, 14,
            if (mhHov || ModConfig.mergedHudMode) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, mhLabel, mhStart + 5, y + 3,
            if (ModConfig.mergedHudMode) ModConfig.accentColor() else 0xFF666666.toInt(), true)
        y += 18

        // Auto Hide + Midnight Reset toggles
        val ahLabel = "${Translations.tr("Auto Hide")}: ${if (ModConfig.autoHideOnComplete) "ON" else "OFF"}"
        val ahBtnW = textRenderer.getWidth("${Translations.tr("Auto Hide")}: OFF") + 10
        val ahHov = mouseX >= leftX && mouseX <= leftX + ahBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + ahBtnW, y + 14,
            if (ModConfig.autoHideOnComplete) (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, leftX, y, ahBtnW, 14,
            if (ahHov || ModConfig.autoHideOnComplete) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, ahLabel, leftX + 5, y + 3,
            if (ModConfig.autoHideOnComplete) ModConfig.accentColor() else 0xFF666666.toInt(), true)

        val mrStart = leftX + ahBtnW + 6
        val mrLabel = "${Translations.tr("Midnight Reset")}: ${if (ModConfig.midnightReset) "ON" else "OFF"}"
        val mrBtnW = textRenderer.getWidth("${Translations.tr("Midnight Reset")}: OFF") + 10
        val mrHov = mouseX >= mrStart && mouseX <= mrStart + mrBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(mrStart, y, mrStart + mrBtnW, y + 14,
            if (ModConfig.midnightReset) (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, mrStart, y, mrBtnW, 14,
            if (mrHov || ModConfig.midnightReset) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, mrLabel, mrStart + 5, y + 3,
            if (ModConfig.midnightReset) ModConfig.accentColor() else 0xFF666666.toInt(), true)
        y += 20

        // ========== RAID SECTION ==========
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
        y += 8
        context.drawText(textRenderer, "Raid", leftX, y, ModConfig.accentColor(), true)
        y += 14

        // Raid Notification toggle
        val rnLabel = "${Translations.tr("Raid Notification")}: ${if (ModConfig.raidNotification) "ON" else "OFF"}"
        val rnBtnW = textRenderer.getWidth("${Translations.tr("Raid Notification")}: OFF") + 10
        val rnHov = mouseX >= leftX && mouseX <= leftX + rnBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + rnBtnW, y + 14,
            if (ModConfig.raidNotification) (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt() else 0xFF1A1A1A.toInt())
        drawBorder(context, leftX, y, rnBtnW, 14,
            if (rnHov || ModConfig.raidNotification) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, rnLabel, leftX + 5, y + 3,
            if (ModConfig.raidNotification) ModConfig.accentColor() else 0xFF666666.toInt(), true)
        y += 18

        // Volume slider
        val volLabel = "${Translations.tr("Volume")}: ${ModConfig.raidNotifVolume}%"
        context.drawText(textRenderer, volLabel, leftX, y + 1, 0xFFBBBBBB.toInt(), true)
        val volLabelW = textRenderer.getWidth("${Translations.tr("Volume")}: 100%") + 6
        val volSliderX = leftX + volLabelW
        val volSliderW = rightX - volSliderX
        y = renderSlider(context, null, volSliderX, y, volSliderW, (ModConfig.raidNotifVolume * 255 / 100), ModConfig.accentColor(), mouseX, mouseY, "raidVol")
        y += 4

        // Start Sound button
        val ssName = ModConfig.raidStartSound.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val ssLabel = "${Translations.tr("Start Sound")}: $ssName"
        val ssBtnW = textRenderer.getWidth(ssLabel) + 10
        val ssHov = mouseX >= leftX && mouseX <= leftX + ssBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, ssBtnW, 14, ssLabel, ssHov)
        y += 18

        // Warning Sound button
        val wsName = ModConfig.raidWarningSound.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val wsLabel = "${Translations.tr("Warning Sound")}: $wsName"
        val wsBtnW = textRenderer.getWidth(wsLabel) + 10
        val wsHov = mouseX >= leftX && mouseX <= leftX + wsBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, wsBtnW, 14, wsLabel, wsHov)
        y += 20

        // Separator
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
        y += 8

        // ========== COLOR SECTION ==========
        val colorLabel: String = Translations.tr("HUD Color")
        context.drawText(textRenderer, colorLabel, leftX, y, ModConfig.accentColor(), true)
        y += 14

        val previewX = leftX
        val previewW = 16
        val previewH = 36
        context.fill(previewX, y, previewX + previewW, y + previewH, ModConfig.accentColor())
        drawBorder(context, previewX, y, previewW, previewH, 0xFF666666.toInt())

        val sliderStartX = leftX + previewW + 8
        val sliderEndX = rightX
        val slW = sliderEndX - sliderStartX

        y = renderSlider(context, "R", sliderStartX, y, slW, ModConfig.hudColorR, 0xFFFF4444.toInt(), mouseX, mouseY, "r")
        y = renderSlider(context, "G", sliderStartX, y, slW, ModConfig.hudColorG, 0xFF44FF44.toInt(), mouseX, mouseY, "g")
        y = renderSlider(context, "B", sliderStartX, y, slW, ModConfig.hudColorB, 0xFF4444FF.toInt(), mouseX, mouseY, "b")

        context.drawText(textRenderer, "#", leftX, y + 2, 0xFFBBBBBB.toInt(), true)
        hexField.x = leftX + 10
        hexField.y = y
        if (draggingSlider != null || !hexField.isFocused) {
            val currentHex = "%02X%02X%02X".format(ModConfig.hudColorR, ModConfig.hudColorG, ModConfig.hudColorB)
            if (hexField.text != currentHex) {
                updatingFromSlider = true
                hexField.text = currentHex
                updatingFromSlider = false
            }
        }
        y += 16

        // ========== TRANSPARENCY SECTION ==========
        val transLabel: String = Translations.tr("Transparency")
        context.drawText(textRenderer, transLabel, leftX, y, ModConfig.accentColor(), true)
        y += 14

        val opacityLabel = "${ModConfig.hudOpacity}%"
        context.drawText(textRenderer, opacityLabel, leftX, y + 1, 0xFFBBBBBB.toInt(), true)
        val opSliderX = leftX + 30
        val opSliderW = rightX - opSliderX
        y = renderSlider(context, null, opSliderX, y, opSliderW, (ModConfig.hudOpacity * 255 / 100), 0xFFBBBBBB.toInt(), mouseX, mouseY, "opacity")
        y += 4

        // Separator
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, 0xFF333333.toInt())
        y += 8

        // ========== RANK SECTION ==========
        val rankLabel: String = Translations.tr("Rank")
        context.drawText(textRenderer, rankLabel, leftX, y, ModConfig.accentColor(), true)
        y += 14

        var rx = leftX
        for ((i, pair) in ModConfig.RANKS.withIndex()) {
            val (label, _) = pair
            val translatedLabel: String = Translations.tr(label)
            val btnW = textRenderer.getWidth(translatedLabel) + 10
            val isSelected = i == ModConfig.rank
            val isHov = mouseX >= rx && mouseX <= rx + btnW && mouseY >= y && mouseY <= y + 16
            val bg = when {
                isSelected -> (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt()
                isHov -> 0xFF252525.toInt()
                else -> 0xFF1A1A1A.toInt()
            }
            context.fill(rx, y, rx + btnW, y + 16, bg)
            val bc = if (isSelected) ModConfig.accentColor() else if (isHov) 0xFF666666.toInt() else 0xFF444444.toInt()
            drawBorder(context, rx, y, btnW, 16, bc)
            val tc = when {
                isSelected -> ModConfig.accentColor()
                isHov -> 0xFFDDDDDD.toInt()
                else -> 0xFF888888.toInt()
            }
            context.drawText(textRenderer, translatedLabel, rx + 5, y + 4, tc, true)
            rx += btnW + 3
        }
        y += 20

        val huntsCount = ModConfig.maxHunts()
        val huntsText = "$huntsCount ${Translations.tr("hunts displayed")}"
        context.drawText(textRenderer, huntsText, leftX + 4, y, 0xFF999999.toInt(), true)

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint: String = Translations.tr("ESC to return")
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)

        super.render(context, mouseX, mouseY, delta)
    }

    private fun renderSlider(
        context: DrawContext, label: String?,
        x: Int, y: Int, w: Int, value: Int, color: Int,
        mouseX: Int, mouseY: Int, sliderId: String
    ): Int {
        val sliderH = 10
        val labelW = if (label != null) 12 else 0
        val barX = x + labelW
        val barW = w - labelW

        if (label != null) context.drawText(textRenderer, label, x, y + 1, color, true)

        context.fill(barX, y + 3, barX + barW, y + 7, 0xFF222222.toInt())
        val fillW = (value * barW / 255).coerceIn(0, barW)
        context.fill(barX, y + 3, barX + fillW, y + 7, color)

        val thumbX = barX + fillW - 2
        val thumbHov = draggingSlider == sliderId ||
                (mouseX >= barX - 2 && mouseX <= barX + barW + 2 && mouseY >= y && mouseY <= y + sliderH)
        context.fill(thumbX, y + 1, thumbX + 5, y + 9, if (thumbHov) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())

        return y + sliderH + 2
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        if (mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2.0 && mouseY <= closeY + 11.0) {
            close(); return true
        }

        val leftX = panelX + 12
        val rightX = panelX + panelWidth - 12

        var y = panelTop + 28

        // Reset button
        val resetLabel: String = Translations.tr("Reset")
        val resetBtnW = textRenderer.getWidth(resetLabel) + 8
        val resetBtnX = panelX + panelWidth - resetBtnW - 22
        val resetBtnY = panelTop + 4
        if (mouseX >= resetBtnX && mouseX <= resetBtnX + resetBtnW &&
            mouseY >= resetBtnY.toDouble() && mouseY <= (resetBtnY + 14).toDouble()) {
            ModConfig.resetToDefaults()
            BoardState.setDisplayCount(ModConfig.maxHunts())
            HuntOverlay.loadPosition()
            RaidTimerOverlay.loadPosition()
            MiracleOverlay.loadPosition()
            return true
        }

        y += 14 // HUD label

        // Mode button
        val widestMode: String = Translations.tr("Compact")
        val modeLabel: String = Translations.tr("Mode:")
        val modeBtnW = textRenderer.getWidth("$modeLabel $widestMode") + 10
        if (mouseX >= leftX && mouseX <= leftX + modeBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            BoardState.setDisplayMode((BoardState.displayMode + 1) % 5)
            return true
        }

        // Position button
        val posLabel: String = Translations.tr("Position")
        val posBtnW = textRenderer.getWidth(posLabel) + 10
        val posBtnX = rightX - posBtnW
        if (mouseX >= posBtnX && mouseX <= posBtnX + posBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            client?.setScreen(HudPositionScreen(this))
            return true
        }
        y += 18

        // Grid Layout toggle + Pixel Art toggle
        val gridLabel: String = if (ModConfig.gridLayout) Translations.tr("List Display") else Translations.tr("Square Display")
        val gridBtnW = textRenderer.getWidth(gridLabel) + 10
        if (mouseX >= leftX && mouseX <= leftX + gridBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleGridLayout()
            return true
        }

        val paLabel: String = if (ModConfig.usePixelArt) Translations.tr("3D Models") else Translations.tr("2D Model")
        val paBtnW = textRenderer.getWidth(paLabel) + 10
        val paStart = leftX + gridBtnW + 6
        if (mouseX >= paStart && mouseX <= paStart + paBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.togglePixelArt()
            return true
        }
        y += 18

        // Size preset buttons
        val sizeLabel: String = Translations.tr("Size")
        var sx = leftX + textRenderer.getWidth(sizeLabel) + 8
        for ((i, key) in SIZE_LABELS.withIndex()) {
            val label: String = Translations.tr(key)
            val btnW = textRenderer.getWidth(label) + 10
            if (mouseX >= sx && mouseX <= sx + btnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
                ModConfig.setHudSizePreset(i)
                return true
            }
            sx += btnW + 3
        }
        y += 20

        // HUD Visibility toggles
        y += 2
        val showLabel: String = Translations.tr("Show:")
        var vx = leftX + textRenderer.getWidth(showLabel) + 8

        val raidToggleW = textRenderer.getWidth("Raid: OFF") + 10
        if (mouseX >= vx && mouseX <= vx + raidToggleW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleRaidHud()
            return true
        }
        vx += raidToggleW + 6

        val miracleToggleW = textRenderer.getWidth("Miracle: OFF") + 10
        if (mouseX >= vx && mouseX <= vx + miracleToggleW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleMiracleHud()
            return true
        }
        vx += miracleToggleW + 6

        val huntToggleW = textRenderer.getWidth("Hunt: OFF") + 10
        if (mouseX >= vx && mouseX <= vx + huntToggleW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleHuntHud()
            return true
        }
        y += 18

        // Full Clear toggle
        val fcBtnW = textRenderer.getWidth("${Translations.tr("Full Clear")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + fcBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleFullClear()
            return true
        }

        // Merged HUD toggle
        val mhStart = leftX + fcBtnW + 6
        val mhBtnW = textRenderer.getWidth("${Translations.tr("Merged HUD")}: OFF") + 10
        if (mouseX >= mhStart && mouseX <= mhStart + mhBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleMergedHud()
            return true
        }
        y += 18

        // Auto Hide toggle
        val ahBtnW = textRenderer.getWidth("${Translations.tr("Auto Hide")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + ahBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleAutoHideOnComplete()
            return true
        }

        // Midnight Reset toggle
        val mrStart = leftX + ahBtnW + 6
        val mrBtnW = textRenderer.getWidth("${Translations.tr("Midnight Reset")}: OFF") + 10
        if (mouseX >= mrStart && mouseX <= mrStart + mrBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleMidnightReset()
            return true
        }
        y += 20

        // Raid section
        y += 9 // separator
        y += 14 // Raid label

        // Raid Notification toggle
        val rnBtnW = textRenderer.getWidth("${Translations.tr("Raid Notification")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + rnBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleRaidNotification()
            return true
        }
        y += 18

        // Volume slider
        val volLabelW = textRenderer.getWidth("${Translations.tr("Volume")}: 100%") + 6
        val volSliderX = leftX + volLabelW
        val volSliderW = rightX - volSliderX
        if (mouseY >= y && mouseY <= y + 10 && mouseX >= volSliderX && mouseX <= volSliderX + volSliderW) {
            draggingSlider = "raidVol"
            updateSliderValue(mouseX, volSliderX, volSliderW)
            return true
        }
        y += 14

        // Start Sound button
        val ssName = ModConfig.raidStartSound.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val ssLabel = "${Translations.tr("Start Sound")}: $ssName"
        val ssBtnW = textRenderer.getWidth(ssLabel) + 10
        if (mouseX >= leftX && mouseX <= leftX + ssBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            client?.setScreen(SoundPickerScreen(this, ModConfig.raidStartSound) { selected ->
                ModConfig.setRaidStartSound(selected)
            })
            return true
        }
        y += 18

        // Warning Sound button
        val wsName = ModConfig.raidWarningSound.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val wsLabel = "${Translations.tr("Warning Sound")}: $wsName"
        val wsBtnW = textRenderer.getWidth(wsLabel) + 10
        if (mouseX >= leftX && mouseX <= leftX + wsBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            client?.setScreen(SoundPickerScreen(this, ModConfig.raidWarningSound) { selected ->
                ModConfig.setRaidWarningSound(selected)
            })
            return true
        }
        y += 20

        y += 9 // separator

        y += 14 // color label

        // Color sliders
        val previewW = 16
        val sliderStartX = leftX + previewW + 8
        val sliderEndX = rightX
        val slW = sliderEndX - sliderStartX

        val sliderId = checkSliderClick(mouseX, mouseY, sliderStartX, y, slW, 3)
        if (sliderId != null) {
            draggingSlider = arrayOf("r", "g", "b")[sliderId]
            updateSliderValue(mouseX, sliderStartX + 12, slW - 12)
            return true
        }
        y += 36
        y += 16 // hex field

        y += 14 // transparency label

        // Opacity slider
        val opSliderX = leftX + 30
        val opSliderW = rightX - opSliderX
        if (mouseY >= y && mouseY <= y + 10 && mouseX >= opSliderX && mouseX <= opSliderX + opSliderW) {
            draggingSlider = "opacity"
            updateSliderValue(mouseX, opSliderX, opSliderW)
            return true
        }
        y += 12
        y += 5
        y += 8

        y += 14 // rank label

        // Rank buttons
        var rx = leftX
        for ((i, pair) in ModConfig.RANKS.withIndex()) {
            val translatedLabel: String = Translations.tr(pair.first)
            val btnW = textRenderer.getWidth(translatedLabel) + 10
            if (mouseX >= rx && mouseX <= rx + btnW && mouseY >= y.toDouble() && mouseY <= (y + 16).toDouble()) {
                ModConfig.setRank(i)
                if (BoardState.displayCount > ModConfig.maxHunts()) {
                    BoardState.setDisplayCount(ModConfig.maxHunts())
                }
                return true
            }
            rx += btnW + 3
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun checkSliderClick(mouseX: Double, mouseY: Double, x: Int, startY: Int, w: Int, count: Int): Int? {
        for (i in 0 until count) {
            val sy = startY + i * 12
            if (mouseY >= sy && mouseY <= sy + 10 && mouseX >= x && mouseX <= x + w) return i
        }
        return null
    }

    private fun updateSliderValue(mouseX: Double, barX: Int, barW: Int) {
        val ratio = ((mouseX - barX) / barW).coerceIn(0.0, 1.0)
        val value = (ratio * 255).toInt()
        when (draggingSlider) {
            "r"       -> ModConfig.setColor(value, ModConfig.hudColorG, ModConfig.hudColorB)
            "g"       -> ModConfig.setColor(ModConfig.hudColorR, value, ModConfig.hudColorB)
            "b"       -> ModConfig.setColor(ModConfig.hudColorR, ModConfig.hudColorG, value)
            "opacity" -> ModConfig.setOpacity(value * 100 / 255)
            "raidVol" -> ModConfig.setRaidNotifVolume(value * 100 / 255)
        }
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (draggingSlider != null && button == 0) {
            val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
            val panelX = (width - panelWidth) / 2
            val leftX = panelX + 12
            val rightX = panelX + panelWidth - 12

            if (draggingSlider == "opacity") {
                val opSliderX = leftX + 30
                updateSliderValue(mouseX, opSliderX, rightX - opSliderX)
            } else if (draggingSlider == "raidVol") {
                val volLabelW = textRenderer.getWidth("${Translations.tr("Volume")}: 100%") + 6
                val volSliderX = leftX + volLabelW
                updateSliderValue(mouseX, volSliderX, rightX - volSliderX)
            } else {
                val sliderStartX = leftX + 16 + 8 + 12
                updateSliderValue(mouseX, sliderStartX, rightX - sliderStartX)
            }
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && draggingSlider != null) { draggingSlider = null; return true }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { client?.setScreen(parent); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() { client?.setScreen(parent) }

    private fun renderButton(context: DrawContext, x: Int, y: Int, w: Int, h: Int, text: String, hovered: Boolean) {
        context.fill(x, y, x + w, y + h, if (hovered) 0xFF353535.toInt() else 0xFF222222.toInt())
        drawBorder(context, x, y, w, h, if (hovered) ModConfig.accentColor() else 0xFF444444.toInt())
        context.drawText(textRenderer, text, x + 5, y + 3, if (hovered) ModConfig.accentColor() else 0xFFBBBBBB.toInt(), true)
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
