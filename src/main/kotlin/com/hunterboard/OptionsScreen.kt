package com.hunterboard

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

class OptionsScreen(
    private val parent: Screen
) : Screen(Text.literal("Options")) {

    // Slider drag state
    private var draggingSlider: String? = null // "r", "g", "b", "opacity", "raidVol", "huntSize", "raidSize", "miracleSize", "mergedSize"

    // Layout bounds (computed in render)
    private var sliderX = 0
    private var sliderW = 0

    // Hex color input
    private lateinit var hexField: TextFieldWidget
    private var updatingFromSlider = false

    // Size preset labels (index matches size preset 0-3)
    private val SIZE_LABELS = arrayOf("Small", "Normal", "Large", "Extra Large")

    // Step slider geometry cache (for drag handling)
    private var stepSliderBars = mutableMapOf<String, Pair<Int, Int>>() // sliderId -> (barX, barW)

    // Scroll state
    private var scrollOffset = 0
    private var contentHeight = 0
    private var contentTop = 0
    private var contentBottom = 0

    // Scrollbar
    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0
    private var scrollbarDragStartOffset = 0
    private var sbTrackX = 0
    private var sbThumbY = 0
    private var sbThumbHeight = 0

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
        UiKit.screenDim(context, width, height)

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        UiKit.panel(context, panelX, panelTop, panelWidth, panelHeight)

        val title: String = Translations.tr("Options")
        UiKit.header(context, textRenderer, panelX, panelTop, panelWidth, title)

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        UiKit.closeButton(context, textRenderer, closeX, closeY, mouseX, mouseY)

        // Reset button (in title bar, not scrollable)
        val resetLabel: String = Translations.tr("Reset")
        val resetBtnW = textRenderer.getWidth(resetLabel) + 8
        val resetBtnX = panelX + panelWidth - resetBtnW - 22
        val resetBtnY = panelTop + 4
        val resetHovered = mouseX >= resetBtnX && mouseX <= resetBtnX + resetBtnW &&
                           mouseY >= resetBtnY && mouseY <= resetBtnY + 14
        renderButton(context, resetBtnX, resetBtnY, resetBtnW, 14, resetLabel, resetHovered)


        val leftX = panelX + 12
        val rightX = panelX + panelWidth - 12
        val contentW = rightX - leftX
        sliderX = leftX + 80
        sliderW = contentW - 80

        contentTop = panelTop + 22
        contentBottom = panelBottom - 16
        val contentAreaHeight = contentBottom - contentTop

        // Clamp scroll
        val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)

        context.enableScissor(panelX + 1, contentTop, panelX + panelWidth - 1, contentBottom)

        var y = contentTop + 6 - scrollOffset

        // ========== THEME SECTION ==========
        val themeSection: String = Translations.tr("Theme")
        context.drawText(textRenderer, themeSection, leftX, y, UiKit.accent(), true)
        y += 14

        val themeName: String = themeDisplayName(ModConfig.uiTheme)
        val themeBtnW = themeButtonWidth()
        val themeHov = mouseX >= leftX && mouseX <= leftX + themeBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, themeBtnW, 14, themeName, themeHov)
        y += 20

        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 8

        // ========== HUD SECTION ==========
        val hudLabel: String = Translations.tr("HUD")
        context.drawText(textRenderer, hudLabel, leftX, y, UiKit.accent(), true)
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

        // Size step sliders
        if (ModConfig.mergedHudMode) {
            y = renderStepSlider(context, Translations.tr("Size"), leftX, y, contentW, ModConfig.hudSizePreset, mouseX, mouseY, "mergedSize")
        } else {
            y = renderStepSlider(context, Translations.tr("Hunt"), leftX, y, contentW, ModConfig.huntSizePreset, mouseX, mouseY, "huntSize")
            y = renderStepSlider(context, "Raid", leftX, y, contentW, ModConfig.raidSizePreset, mouseX, mouseY, "raidSize")
            y = renderStepSlider(context, "Miracle", leftX, y, contentW, ModConfig.miracleSizePreset, mouseX, mouseY, "miracleSize")
        }
        y += 4

        // ========== HUD VISIBILITY ==========
        y += 2
        val showLabel: String = Translations.tr("Show:")
        context.drawText(textRenderer, showLabel, leftX, y + 3, UiKit.TEXT_MUTED, true)
        var vx = leftX + textRenderer.getWidth(showLabel) + 8

        val raidLabel = "Raid: ${if (ModConfig.showRaidHud) "ON" else "OFF"}"
        val raidToggleW = textRenderer.getWidth("Raid: OFF") + 10
        val raidHov = mouseX >= vx && mouseX <= vx + raidToggleW && mouseY >= y && mouseY <= y + 14
        context.fill(vx, y, vx + raidToggleW, y + 14,
            if (ModConfig.showRaidHud) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, vx, y, raidToggleW, 14,
            if (raidHov || ModConfig.showRaidHud) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, raidLabel, vx + 5, y + 3,
            if (ModConfig.showRaidHud) UiKit.accent() else UiKit.TEXT_FAINT, true)
        vx += raidToggleW + 6

        val miracleLabel = "Miracle: ${if (ModConfig.showMiracleHud) "ON" else "OFF"}"
        val miracleToggleW = textRenderer.getWidth("Miracle: OFF") + 10
        val miracleHov = mouseX >= vx && mouseX <= vx + miracleToggleW && mouseY >= y && mouseY <= y + 14
        context.fill(vx, y, vx + miracleToggleW, y + 14,
            if (ModConfig.showMiracleHud) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, vx, y, miracleToggleW, 14,
            if (miracleHov || ModConfig.showMiracleHud) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, miracleLabel, vx + 5, y + 3,
            if (ModConfig.showMiracleHud) UiKit.accent() else UiKit.TEXT_FAINT, true)
        vx += miracleToggleW + 6

        val huntLabel = "Hunt: ${if (ModConfig.showHuntHud) "ON" else "OFF"}"
        val huntToggleW = textRenderer.getWidth("Hunt: OFF") + 10
        val huntHov = mouseX >= vx && mouseX <= vx + huntToggleW && mouseY >= y && mouseY <= y + 14
        context.fill(vx, y, vx + huntToggleW, y + 14,
            if (ModConfig.showHuntHud) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, vx, y, huntToggleW, 14,
            if (huntHov || ModConfig.showHuntHud) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, huntLabel, vx + 5, y + 3,
            if (ModConfig.showHuntHud) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 18

        // Full Clear + Merged HUD toggles
        val fcLabel = "${Translations.tr("Full Clear")}: ${if (ModConfig.fullClearMode) "ON" else "OFF"}"
        val fcBtnW = textRenderer.getWidth("${Translations.tr("Full Clear")}: OFF") + 10
        val fcHov = mouseX >= leftX && mouseX <= leftX + fcBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + fcBtnW, y + 14,
            if (ModConfig.fullClearMode) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, fcBtnW, 14,
            if (fcHov || ModConfig.fullClearMode) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, fcLabel, leftX + 5, y + 3,
            if (ModConfig.fullClearMode) UiKit.accent() else UiKit.TEXT_FAINT, true)

        val mhStart = leftX + fcBtnW + 6
        val mhLabel = "${Translations.tr("Merged HUD")}: ${if (ModConfig.mergedHudMode) "ON" else "OFF"}"
        val mhBtnW = textRenderer.getWidth("${Translations.tr("Merged HUD")}: OFF") + 10
        val mhHov = mouseX >= mhStart && mouseX <= mhStart + mhBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(mhStart, y, mhStart + mhBtnW, y + 14,
            if (ModConfig.mergedHudMode) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, mhStart, y, mhBtnW, 14,
            if (mhHov || ModConfig.mergedHudMode) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, mhLabel, mhStart + 5, y + 3,
            if (ModConfig.mergedHudMode) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 18

        // Auto Hide toggle
        val ahLabel = "${Translations.tr("Auto Hide")}: ${if (ModConfig.autoHideOnComplete) "ON" else "OFF"}"
        val ahBtnW = textRenderer.getWidth("${Translations.tr("Auto Hide")}: OFF") + 10
        val ahHov = mouseX >= leftX && mouseX <= leftX + ahBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + ahBtnW, y + 14,
            if (ModConfig.autoHideOnComplete) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, ahBtnW, 14,
            if (ahHov || ModConfig.autoHideOnComplete) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, ahLabel, leftX + 5, y + 3,
            if (ModConfig.autoHideOnComplete) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 18

        // Hide in Battle toggle
        val hbLabel = "${Translations.tr("Hide in Battle")}: ${if (ModConfig.hideHudInBattle) "ON" else "OFF"}"
        val hbBtnW = textRenderer.getWidth("${Translations.tr("Hide in Battle")}: OFF") + 10
        val hbHov = mouseX >= leftX && mouseX <= leftX + hbBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + hbBtnW, y + 14,
            if (ModConfig.hideHudInBattle) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, hbBtnW, 14,
            if (hbHov || ModConfig.hideHudInBattle) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, hbLabel, leftX + 5, y + 3,
            if (ModConfig.hideHudInBattle) UiKit.accent() else UiKit.TEXT_FAINT, true)

        val hcStart = leftX + hbBtnW + 6
        val hcLabel = "${Translations.tr("Hide Caught")}: ${if (ModConfig.hideCaughtInHud) "ON" else "OFF"}"
        val hcBtnW = textRenderer.getWidth("${Translations.tr("Hide Caught")}: OFF") + 10
        val hcHov = mouseX >= hcStart && mouseX <= hcStart + hcBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(hcStart, y, hcStart + hcBtnW, y + 14,
            if (ModConfig.hideCaughtInHud) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, hcStart, y, hcBtnW, 14,
            if (hcHov || ModConfig.hideCaughtInHud) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, hcLabel, hcStart + 5, y + 3,
            if (ModConfig.hideCaughtInHud) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 18

        // Battle Hunt HUD toggle
        val bhLabel = "${Translations.tr("Battle Hunt HUD")}: ${if (ModConfig.showBattleHuntHud) "ON" else "OFF"}"
        val bhBtnW = textRenderer.getWidth("${Translations.tr("Battle Hunt HUD")}: OFF") + 10
        val bhHov = mouseX >= leftX && mouseX <= leftX + bhBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + bhBtnW, y + 14,
            if (ModConfig.showBattleHuntHud) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, bhBtnW, 14,
            if (bhHov || ModConfig.showBattleHuntHud) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, bhLabel, leftX + 5, y + 3,
            if (ModConfig.showBattleHuntHud) UiKit.accent() else UiKit.TEXT_FAINT, true)

        // PvP Overlay toggle (same row, after Battle Hunt HUD)
        val pvpStart = leftX + bhBtnW + 6
        val pvpLabel = "PvP Overlay: ${if (ModConfig.pvpOverlayEnabled) "ON" else "OFF"}"
        val pvpBtnW = textRenderer.getWidth("PvP Overlay: OFF") + 10
        val pvpHov = mouseX >= pvpStart && mouseX <= pvpStart + pvpBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(pvpStart, y, pvpStart + pvpBtnW, y + 14,
            if (ModConfig.pvpOverlayEnabled) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, pvpStart, y, pvpBtnW, 14,
            if (pvpHov || ModConfig.pvpOverlayEnabled) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, pvpLabel, pvpStart + 5, y + 3,
            if (ModConfig.pvpOverlayEnabled) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 20

        // Hide Boss Damage Chat toggle
        val bdLabel = "${Translations.tr("Hide Raid Messages")}: ${if (ModConfig.hideBossDamageChat) "ON" else "OFF"}"
        val bdBtnW = textRenderer.getWidth("${Translations.tr("Hide Raid Messages")}: OFF") + 10
        val bdHov = mouseX >= leftX && mouseX <= leftX + bdBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + bdBtnW, y + 14,
            if (ModConfig.hideBossDamageChat) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, bdBtnW, 14,
            if (bdHov || ModConfig.hideBossDamageChat) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, bdLabel, leftX + 5, y + 3,
            if (ModConfig.hideBossDamageChat) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 20

        // Playtime toggle
        val ptLabel = "${Translations.tr("Playtime")}: ${if (ModConfig.showPlaytime) "ON" else "OFF"}"
        val ptBtnW = textRenderer.getWidth("${Translations.tr("Playtime")}: OFF") + 10
        val ptHov = mouseX >= leftX && mouseX <= leftX + ptBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + ptBtnW, y + 14,
            if (ModConfig.showPlaytime) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, ptBtnW, 14,
            if (ptHov || ModConfig.showPlaytime) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, ptLabel, leftX + 5, y + 3,
            if (ModConfig.showPlaytime) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 20

        // Separator
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 8

        // ========== COLOR SECTION ==========
        val colorLabel: String = Translations.tr("HUD Color")
        context.drawText(textRenderer, colorLabel, leftX, y, UiKit.accent(), true)
        y += 14

        val previewX = leftX
        val previewW = 16
        val previewH = 36
        context.fill(previewX, y, previewX + previewW, y + previewH, ModConfig.accentColor())
        drawBorder(context, previewX, y, previewW, previewH, UiKit.TEXT_FAINT)

        val sliderStartX = leftX + previewW + 8
        val sliderEndX = rightX
        val slW = sliderEndX - sliderStartX

        y = renderSlider(context, "R", sliderStartX, y, slW, ModConfig.hudColorR, 0xFFFF4444.toInt(), mouseX, mouseY, "r")
        y = renderSlider(context, "G", sliderStartX, y, slW, ModConfig.hudColorG, 0xFF44FF44.toInt(), mouseX, mouseY, "g")
        y = renderSlider(context, "B", sliderStartX, y, slW, ModConfig.hudColorB, 0xFF4444FF.toInt(), mouseX, mouseY, "b")

        context.drawText(textRenderer, "#", leftX, y + 2, UiKit.TEXT_MUTED, true)
        hexField.x = leftX + 10
        hexField.y = y
        hexField.visible = y >= contentTop - 12 && y <= contentBottom
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
        context.drawText(textRenderer, transLabel, leftX, y, UiKit.accent(), true)
        y += 14

        val opacityLabel = "${ModConfig.hudOpacity}%"
        context.drawText(textRenderer, opacityLabel, leftX, y + 1, UiKit.TEXT_MUTED, true)
        val opSliderX = leftX + 30
        val opSliderW = rightX - opSliderX
        y = renderSlider(context, null, opSliderX, y, opSliderW, (ModConfig.hudOpacity * 255 / 100), UiKit.TEXT_MUTED, mouseX, mouseY, "opacity")
        y += 4

        // Separator
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 8

        // ========== NOTIFICATIONS SECTION ==========
        val sectionLabel: String = Translations.tr("Sound Notifications")
        context.drawText(textRenderer, sectionLabel, leftX, y, UiKit.accent(), true)
        y += 14

        // Clear Sound toggle
        val csLabel = "${Translations.tr("Clear Sound")}: ${if (ModConfig.clearWarningSound) "ON" else "OFF"}"
        val csBtnW = textRenderer.getWidth("${Translations.tr("Clear Sound")}: OFF") + 10
        val csHov = mouseX >= leftX && mouseX <= leftX + csBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + csBtnW, y + 14,
            if (ModConfig.clearWarningSound) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, csBtnW, 14,
            if (csHov || ModConfig.clearWarningSound) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, csLabel, leftX + 5, y + 3,
            if (ModConfig.clearWarningSound) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 18

        // Raid Notification toggle + Miracle Notification toggle
        val rnLabel = "${Translations.tr("Raid Sound")}: ${if (ModConfig.raidNotification) "ON" else "OFF"}"
        val rnBtnW = textRenderer.getWidth("${Translations.tr("Raid Sound")}: OFF") + 10
        val rnHov = mouseX >= leftX && mouseX <= leftX + rnBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + rnBtnW, y + 14,
            if (ModConfig.raidNotification) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, rnBtnW, 14,
            if (rnHov || ModConfig.raidNotification) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, rnLabel, leftX + 5, y + 3,
            if (ModConfig.raidNotification) UiKit.accent() else UiKit.TEXT_FAINT, true)

        val mnStart = leftX + rnBtnW + 6
        val mnLabel = "${Translations.tr("Miracle Sound")}: ${if (ModConfig.miracleNotification) "ON" else "OFF"}"
        val mnBtnW = textRenderer.getWidth("${Translations.tr("Miracle Sound")}: OFF") + 10
        val mnHov = mouseX >= mnStart && mouseX <= mnStart + mnBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(mnStart, y, mnStart + mnBtnW, y + 14,
            if (ModConfig.miracleNotification) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, mnStart, y, mnBtnW, 14,
            if (mnHov || ModConfig.miracleNotification) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, mnLabel, mnStart + 5, y + 3,
            if (ModConfig.miracleNotification) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 18

        // Raid Sound Repeat toggle (5x vs 1x)
        val rpLabel = "${Translations.tr("Repeat")}: ${if (ModConfig.raidSoundRepeat) "5x" else "1x"}"
        val rpBtnW = textRenderer.getWidth("${Translations.tr("Repeat")}: 5x") + 10
        val rpHov = mouseX >= leftX && mouseX <= leftX + rpBtnW && mouseY >= y && mouseY <= y + 14
        context.fill(leftX, y, leftX + rpBtnW, y + 14,
            if (ModConfig.raidSoundRepeat) (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt() else UiKit.SURFACE)
        drawBorder(context, leftX, y, rpBtnW, 14,
            if (rpHov || ModConfig.raidSoundRepeat) UiKit.accent() else UiKit.BORDER)
        context.drawText(textRenderer, rpLabel, leftX + 5, y + 3,
            if (ModConfig.raidSoundRepeat) UiKit.accent() else UiKit.TEXT_FAINT, true)
        y += 18

        // Volume slider (shared)
        val volLabel = "${Translations.tr("Volume")}: ${ModConfig.raidNotifVolume}%"
        context.drawText(textRenderer, volLabel, leftX, y + 1, UiKit.TEXT_MUTED, true)
        val volLabelW = textRenderer.getWidth("${Translations.tr("Volume")}: 100%") + 6
        val volSliderX = leftX + volLabelW
        val volSliderW = rightX - volSliderX
        y = renderSlider(context, null, volSliderX, y, volSliderW, (ModConfig.raidNotifVolume * 255 / 100), UiKit.accent(), mouseX, mouseY, "raidVol")
        y += 4

        // Raid Start Sound button
        val ssName = ModConfig.raidStartSound.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val ssLabel = "${Translations.tr("Start Sound")}: $ssName"
        val ssBtnW = textRenderer.getWidth(ssLabel) + 10
        val ssHov = mouseX >= leftX && mouseX <= leftX + ssBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, ssBtnW, 14, ssLabel, ssHov)
        y += 18

        // Raid Warning Sound button
        val wsName = ModConfig.raidWarningSound.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val wsLabel = "${Translations.tr("Warning Sound")}: $wsName"
        val wsBtnW = textRenderer.getWidth(wsLabel) + 10
        val wsHov = mouseX >= leftX && mouseX <= leftX + wsBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, wsBtnW, 14, wsLabel, wsHov)
        y += 18

        // Miracle Sound button
        val msName = ModConfig.miracleSound.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val msLabel = "${Translations.tr("Miracle Notif Sound")}: $msName"
        val msBtnW = textRenderer.getWidth(msLabel) + 10
        val msHov = mouseX >= leftX && mouseX <= leftX + msBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, msBtnW, 14, msLabel, msHov)
        y += 18

        // Clear Warning Sound button
        val cwsSoundName = ModConfig.clearWarningSoundId.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val cwsLabel = "${Translations.tr("Clear Warning Sound")}: $cwsSoundName"
        val cwsBtnW = textRenderer.getWidth(cwsLabel) + 10
        val cwsHov = mouseX >= leftX && mouseX <= leftX + cwsBtnW && mouseY >= y && mouseY <= y + 14
        renderButton(context, leftX, y, cwsBtnW, 14, cwsLabel, cwsHov)
        y += 20

        // Separator
        context.fill(panelX + 6, y, panelX + panelWidth - 6, y + 1, UiKit.BORDER_DIM)
        y += 8

        // ========== RANK SECTION ==========
        val rankLabel: String = Translations.tr("Rank")
        context.drawText(textRenderer, rankLabel, leftX, y, UiKit.accent(), true)
        y += 14

        var rx = leftX
        for ((i, pair) in ModConfig.RANKS.withIndex()) {
            val (label, _) = pair
            val translatedLabel: String = Translations.tr(label)
            val btnW = textRenderer.getWidth(translatedLabel) + 10
            val isSelected = i == ModConfig.rank
            val isHov = mouseX >= rx && mouseX <= rx + btnW && mouseY >= y && mouseY <= y + 16
            val bg = when {
                isSelected -> (UiKit.accent() and 0x00FFFFFF) or 0x33000000.toInt()
                isHov -> UiKit.SURFACE_HOVER
                else -> UiKit.SURFACE
            }
            context.fill(rx, y, rx + btnW, y + 16, bg)
            val bc = if (isSelected) UiKit.accent() else if (isHov) UiKit.TEXT_FAINT else UiKit.BORDER
            drawBorder(context, rx, y, btnW, 16, bc)
            val tc = when {
                isSelected -> UiKit.accent()
                isHov -> UiKit.TEXT
                else -> UiKit.TEXT_MUTED
            }
            context.drawText(textRenderer, translatedLabel, rx + 5, y + 4, tc, true)
            rx += btnW + 3
        }
        y += 20

        val huntsCount = ModConfig.maxHunts()
        val huntsText = "$huntsCount ${Translations.tr("hunts displayed")}"
        context.drawText(textRenderer, huntsText, leftX + 4, y, UiKit.TEXT_FAINT, true)
        y += 14

        // Track content height for scrolling
        contentHeight = y - (contentTop + 6 - scrollOffset) + 8

        context.disableScissor()

        // Scrollbar
        if (contentHeight > contentAreaHeight && contentAreaHeight > 0) {
            sbTrackX = panelX + panelWidth - 5
            context.fill(sbTrackX, contentTop, sbTrackX + 3, contentBottom, UiKit.SURFACE)
            sbThumbHeight = maxOf(15, contentAreaHeight * contentAreaHeight / contentHeight)
            sbThumbY = contentTop + (scrollOffset * (contentAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, UiKit.accent())
        } else {
            sbThumbHeight = 0
        }

        // Footer
                val hint: String = Translations.tr("ESC to return")
        UiKit.footer(context, textRenderer, panelX, panelBottom, panelWidth, hint)

        // Version mention
        val modVersion = FabricLoader.getInstance().getModContainer(HunterBoard.MOD_ID)
            .map { it.metadata.version.friendlyString }.orElse("?")
        val versionText = "HunterBoard $modVersion"
        val versionX = (width - textRenderer.getWidth(versionText)) / 2
        context.drawText(textRenderer, versionText, versionX, height - 12, UiKit.TEXT_MUTED, true)

        super.render(context, mouseX, mouseY, delta)
    }

    /** Render a step slider with 4 discrete stops (0-3) */
    private fun renderStepSlider(
        context: DrawContext, label: String, x: Int, y: Int, w: Int,
        currentStep: Int, mouseX: Int, mouseY: Int, sliderId: String
    ): Int {
        val sliderH = 12
        // Fixed label width so all sliders align (widest label among Hunt/Raid/Miracle/Size)
        val fixedLabelW = listOf("Hunt", "Raid", "Miracle", "Size").maxOf { textRenderer.getWidth(Translations.tr(it)) } + 6
        val barX = x + fixedLabelW
        val barW = w - fixedLabelW
        val steps = 4
        val step = currentStep.coerceIn(0, 3)

        // Cache bar geometry for drag handling
        stepSliderBars[sliderId] = Pair(barX, barW)

        // Label (left-aligned)
        context.drawText(textRenderer, label, x, y + 2, UiKit.TEXT_MUTED, true)

        // Track
        context.fill(barX, y + 5, barX + barW, y + 7, UiKit.SURFACE)

        // Step markers
        for (i in 0 until steps) {
            val cx = barX + i * barW / (steps - 1)
            val markerColor = if (i == step) UiKit.accent() else UiKit.TEXT_FAINT
            context.fill(cx - 1, y + 3, cx + 1, y + 9, markerColor)
        }

        // Thumb
        val thumbX = barX + step * barW / (steps - 1) - 3
        val isActive = draggingSlider == sliderId
        val isHov = isActive || (mouseX >= barX - 4 && mouseX <= barX + barW + 4 && mouseY >= y && mouseY <= y + sliderH)
        context.fill(thumbX, y + 1, thumbX + 7, y + 11, if (isHov) UiKit.accent() else UiKit.TEXT_MUTED)
        drawBorder(context, thumbX, y + 1, 7, 10, if (isHov) UiKit.TEXT else UiKit.TEXT_FAINT)

        // Step label centered on the entire bar
        val stepLabel = Translations.tr(SIZE_LABELS[step])
        val stepLabelW = textRenderer.getWidth(stepLabel)
        val labelCenterX = barX + (barW - stepLabelW) / 2
        context.drawText(textRenderer, stepLabel, labelCenterX, y + 2, UiKit.TEXT_MUTED, true)

        return y + sliderH + 2
    }

    /** Convert mouse X to step index (0-3) for a step slider */
    private fun mouseToStep(mouseX: Double, barX: Int, barW: Int): Int {
        val ratio = ((mouseX - barX) / barW).coerceIn(0.0, 1.0)
        return (ratio * 3).roundToInt().coerceIn(0, 3)
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

        context.fill(barX, y + 3, barX + barW, y + 7, UiKit.SURFACE)
        val fillW = (value * barW / 255).coerceIn(0, barW)
        context.fill(barX, y + 3, barX + fillW, y + 7, color)

        val thumbX = barX + fillW - 2
        val thumbHov = draggingSlider == sliderId ||
                (mouseX >= barX - 2 && mouseX <= barX + barW + 2 && mouseY >= y && mouseY <= y + sliderH)
        context.fill(thumbX, y + 1, thumbX + 5, y + 9, if (thumbHov) UiKit.TEXT else UiKit.TEXT_MUTED)

        return y + sliderH + 2
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        if (mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2.0 && mouseY <= closeY + 11.0) {
            close(); return true
        }

        // Reset button (in title bar)
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

        // Scrollbar click
        val cTop = panelTop + 22
        val cBottom = panelBottom - 16
        if (sbThumbHeight > 0 && mouseX >= sbTrackX && mouseX <= sbTrackX + 6 &&
            mouseY >= cTop && mouseY <= cBottom) {
            val contentAreaHeight = cBottom - cTop
            val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
            if (mouseY >= sbThumbY && mouseY <= sbThumbY + sbThumbHeight) {
                isScrollbarDragging = true
                scrollbarDragStartY = mouseY.toInt()
                scrollbarDragStartOffset = scrollOffset
            } else {
                val ratio = (mouseY - cTop).toFloat() / contentAreaHeight
                scrollOffset = (ratio * maxScroll).toInt().coerceIn(0, maxScroll)
            }
            return true
        }

        // Ignore clicks outside content area
        if (mouseY < cTop || mouseY > cBottom) return super.mouseClicked(mouseX, mouseY, button)

        val leftX = panelX + 12
        val rightX = panelX + panelWidth - 12
        val contentW = rightX - leftX

        var y = cTop + 6 - scrollOffset

        // Theme section
        y += 14 // Theme label
        val themeBtnW = themeButtonWidth()
        if (mouseX >= leftX && mouseX <= leftX + themeBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleUiTheme()
            return true
        }
        y += 20
        y += 9 // separator

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

        // Size step sliders
        if (ModConfig.mergedHudMode) {
            if (handleStepSliderClick(mouseX, mouseY, y, "mergedSize") { ModConfig.setHudSizePreset(it) }) return true
            y += 14
        } else {
            if (handleStepSliderClick(mouseX, mouseY, y, "huntSize") { ModConfig.setHuntSizePreset(it) }) return true
            y += 14
            if (handleStepSliderClick(mouseX, mouseY, y, "raidSize") { ModConfig.setRaidSizePreset(it) }) return true
            y += 14
            if (handleStepSliderClick(mouseX, mouseY, y, "miracleSize") { ModConfig.setMiracleSizePreset(it) }) return true
            y += 14
        }
        y += 6

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
        y += 18

        // Hide in Battle toggle
        val hbBtnW = textRenderer.getWidth("${Translations.tr("Hide in Battle")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + hbBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleHideHudInBattle()
            return true
        }

        // Hide Caught toggle
        val hcStart = leftX + hbBtnW + 6
        val hcBtnW = textRenderer.getWidth("${Translations.tr("Hide Caught")}: OFF") + 10
        if (mouseX >= hcStart && mouseX <= hcStart + hcBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleHideCaughtInHud()
            return true
        }
        y += 18

        // Battle Hunt HUD toggle
        val bhBtnW = textRenderer.getWidth("${Translations.tr("Battle Hunt HUD")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + bhBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleBattleHuntHud()
            return true
        }

        // PvP Overlay toggle
        val pvpStart = leftX + bhBtnW + 6
        val pvpBtnW = textRenderer.getWidth("PvP Overlay: OFF") + 10
        if (mouseX >= pvpStart && mouseX <= pvpStart + pvpBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.togglePvpOverlay()
            return true
        }
        y += 20

        // Hide Boss Damage Chat toggle
        val bdBtnW = textRenderer.getWidth("${Translations.tr("Hide Raid Messages")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + bdBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleHideBossDamageChat()
            return true
        }
        y += 20

        // Playtime toggle
        val ptBtnW = textRenderer.getWidth("${Translations.tr("Playtime")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + ptBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleShowPlaytime()
            return true
        }
        y += 20

        // Color section
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

        // Notifications sonores section
        y += 14 // section label

        // Clear Sound toggle
        val csBtnW = textRenderer.getWidth("${Translations.tr("Clear Sound")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + csBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleClearWarningSound()
            return true
        }
        y += 18

        // Raid Sound toggle
        val rnBtnW = textRenderer.getWidth("${Translations.tr("Raid Sound")}: OFF") + 10
        if (mouseX >= leftX && mouseX <= leftX + rnBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleRaidNotification()
            return true
        }

        // Miracle Sound toggle
        val mnStart = leftX + rnBtnW + 6
        val mnBtnW = textRenderer.getWidth("${Translations.tr("Miracle Sound")}: OFF") + 10
        if (mouseX >= mnStart && mouseX <= mnStart + mnBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleMiracleNotification()
            return true
        }
        y += 18

        // Repeat toggle
        val rpBtnW = textRenderer.getWidth("${Translations.tr("Repeat")}: 5x") + 10
        if (mouseX >= leftX && mouseX <= leftX + rpBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            ModConfig.toggleRaidSoundRepeat()
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
        y += 18

        // Miracle Sound button
        val msName = ModConfig.miracleSound.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val msLabel = "${Translations.tr("Miracle Notif Sound")}: $msName"
        val msBtnW = textRenderer.getWidth(msLabel) + 10
        if (mouseX >= leftX && mouseX <= leftX + msBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            client?.setScreen(SoundPickerScreen(this, ModConfig.miracleSound) { selected ->
                ModConfig.setMiracleSound(selected)
            })
            return true
        }
        y += 18

        // Clear Warning Sound button
        val cwsSoundName = ModConfig.clearWarningSoundId.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val cwsLabel = "${Translations.tr("Clear Warning Sound")}: $cwsSoundName"
        val cwsBtnW = textRenderer.getWidth(cwsLabel) + 10
        if (mouseX >= leftX && mouseX <= leftX + cwsBtnW && mouseY >= y.toDouble() && mouseY <= (y + 14).toDouble()) {
            client?.setScreen(SoundPickerScreen(this, ModConfig.clearWarningSoundId) { selected ->
                ModConfig.setClearWarningSound(selected)
            })
            return true
        }
        y += 20

        y += 9 // separator

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

    /** Handle click on a step slider. Returns true if the click was consumed. */
    private fun handleStepSliderClick(
        mouseX: Double, mouseY: Double, y: Int,
        sliderId: String, setter: (Int) -> Unit
    ): Boolean {
        val bar = stepSliderBars[sliderId] ?: return false
        val (barX, barW) = bar
        if (mouseY >= y && mouseY <= y + 12 && mouseX >= barX - 4 && mouseX <= barX + barW + 4) {
            val step = mouseToStep(mouseX, barX, barW)
            setter(step)
            draggingSlider = sliderId
            return true
        }
        return false
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
        if (isScrollbarDragging && button == 0) {
            val contentAreaHeight = contentBottom - contentTop
            val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
            val trackRange = contentAreaHeight - sbThumbHeight
            if (trackRange > 0) {
                val scrollDelta = ((mouseY.toInt() - scrollbarDragStartY).toFloat() / trackRange * maxScroll).toInt()
                scrollOffset = (scrollbarDragStartOffset + scrollDelta).coerceIn(0, maxScroll)
            }
            return true
        }
        if (draggingSlider != null && button == 0) {
            val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
            val panelX = (width - panelWidth) / 2
            val leftX = panelX + 12
            val rightX = panelX + panelWidth - 12

            // Step sliders (size presets)
            val stepBar = stepSliderBars[draggingSlider]
            if (stepBar != null) {
                val (barX, barW) = stepBar
                val step = mouseToStep(mouseX, barX, barW)
                when (draggingSlider) {
                    "mergedSize" -> ModConfig.setHudSizePreset(step)
                    "huntSize" -> ModConfig.setHuntSizePreset(step)
                    "raidSize" -> ModConfig.setRaidSizePreset(step)
                    "miracleSize" -> ModConfig.setMiracleSizePreset(step)
                }
                return true
            }

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
        if (button == 0 && isScrollbarDragging) { isScrollbarDragging = false; return true }
        if (button == 0 && draggingSlider != null) { draggingSlider = null; return true }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val contentAreaHeight = contentBottom - contentTop
        if (contentAreaHeight <= 0) return true
        val maxScroll = maxOf(0, contentHeight - contentAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { client?.setScreen(parent); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() { client?.setScreen(parent) }

    private fun renderButton(context: DrawContext, x: Int, y: Int, w: Int, h: Int, text: String, hovered: Boolean) {
        UiKit.button(context, textRenderer, x, y, w, h, text, hovered)
    }

    /** Localized display name of a theme id. */
    private fun themeDisplayName(id: String): String = Translations.tr(when (id) {
        "cobblemon" -> "Cobblemon Theme"
        "polaris" -> "Polaris Theme"
        "rebutsurmer" -> "Rebut-sur-mer Theme"
        "rhode" -> "Rhode Theme"
        "niavarane" -> "Niavarane Theme"
        "tropimon" -> "Tropimon Theme"
        else -> "HunterBoard Theme"
    })

    /** Width fitting the longest theme name (stable hitbox across cycling). */
    private fun themeButtonWidth(): Int =
        ModConfig.UI_THEMES.maxOf { textRenderer.getWidth(themeDisplayName(it)) } + 10

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
