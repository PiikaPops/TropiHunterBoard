package com.hunterboard

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

class SoundPickerScreen(
    private val parent: Screen,
    private val currentSound: String,
    private val onSelect: (String) -> Unit
) : Screen(Text.literal("Sound Picker")) {

    private lateinit var searchField: TextFieldWidget
    private var allSounds: List<Identifier> = emptyList()
    private var filteredSounds: List<Identifier> = emptyList()
    private var customSounds: List<String> = emptyList()
    private var filteredCustom: List<String> = emptyList()
    private var scrollOffset = 0
    private val rowHeight = 14
    private var selectedSound: String = currentSound
    private val playBtnSize = 10
    private var currentPreview: SoundInstance? = null
    private var playingSound: String? = null

    // Tab state: 0 = Minecraft, 1 = Custom
    private var activeTab = 0

    // Open folder button bounds
    private var openFolderBtnX = 0
    private var openFolderBtnY = 0
    private var openFolderBtnW = 0
    private val openFolderBtnH = 12

    // Scrollbar state
    private var isScrollbarDragging = false
    private var scrollbarDragStartY = 0.0
    private var scrollbarDragStartOffset = 0
    private var sbTrackX = 0
    private var sbContentTop = 0
    private var sbContentBottom = 0
    private var sbThumbY = 0
    private var sbThumbHeight = 0

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        allSounds = Registries.SOUND_EVENT.ids
            .filter { it.namespace != HunterBoard.MOD_ID || !it.path.startsWith("special_") }
            .toList().sortedBy { it.toString() }
        filteredSounds = allSounds
        customSounds = CustomSoundPlayer.listSounds()
        filteredCustom = customSounds

        // Auto-select custom tab if current sound is custom
        if (currentSound.startsWith("custom:")) activeTab = 1

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search sound...")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 36, panelWidth - 20, 14, Text.literal(placeholder))
        searchField.setMaxLength(100)
        searchField.setChangedListener { updateSearch() }
        addDrawableChild(searchField)
        setInitialFocus(searchField)
    }

    private fun updateSearch() {
        val query = searchField.text.lowercase().trim()
        scrollOffset = 0
        if (activeTab == 0) {
            filteredSounds = if (query.isEmpty()) allSounds
            else allSounds.filter { it.toString().contains(query) || formatSoundName(it).lowercase().contains(query) }
        } else {
            filteredCustom = if (query.isEmpty()) customSounds
            else customSounds.filter {
                it.lowercase().contains(query) || CustomSoundPlayer.formatSoundName(it).lowercase().contains(query)
            }
        }
    }

    private fun switchTab(tab: Int) {
        if (activeTab == tab) return
        activeTab = tab
        scrollOffset = 0
        searchField.text = ""
        updateSearch()
    }

    private fun formatSoundName(id: Identifier): String {
        return id.path.replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun currentListSize(): Int = if (activeTab == 0) filteredSounds.size else filteredCustom.size

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xAA000000.toInt())

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val panelHeight = panelBottom - panelTop

        context.fill(panelX, panelTop, panelX + panelWidth, panelBottom, 0xF0101010.toInt())
        drawBorder(context, panelX, panelTop, panelWidth, panelHeight, ModConfig.accentColor())

        val title = Translations.tr("Select")
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 6, ModConfig.accentColor(), true)

        // Close button ✕
        val closeX = panelX + panelWidth - 12
        val closeY = panelTop + 4
        val closeHovered = mouseX >= closeX - 2 && mouseX <= closeX + 9 && mouseY >= closeY - 2 && mouseY <= closeY + 11
        context.drawText(textRenderer, "\u2715", closeX, closeY, if (closeHovered) 0xFFFF5555.toInt() else 0xFF888888.toInt(), true)

        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, ModConfig.accentColor())

        // === Tabs ===
        val tabY = panelTop + 21
        val tabH = 12
        val tabMc = Translations.tr("Minecraft")
        val tabCustom = Translations.tr("Custom")
        val tabMcW = textRenderer.getWidth(tabMc) + 10
        val tabCustomW = textRenderer.getWidth(tabCustom) + 10
        val tabGap = 4
        val tabTotalW = tabMcW + tabGap + tabCustomW
        val tabStartX = panelX + (panelWidth - tabTotalW) / 2

        // Minecraft tab
        val mcActive = activeTab == 0
        val mcHovered = !mcActive && mouseX >= tabStartX && mouseX <= tabStartX + tabMcW &&
                mouseY >= tabY && mouseY <= tabY + tabH
        context.fill(tabStartX, tabY, tabStartX + tabMcW, tabY + tabH,
            if (mcActive) (ModConfig.accentColor() and 0x00FFFFFF or 0x44000000.toInt()) else if (mcHovered) 0xFF252525.toInt() else 0xFF1A1A1A.toInt())
        context.drawText(textRenderer, tabMc, tabStartX + 5, tabY + 2,
            if (mcActive) ModConfig.accentColor() else if (mcHovered) 0xFFDDDDDD.toInt() else 0xFF777777.toInt(), true)

        // Custom tab
        val customActive = activeTab == 1
        val customTabX = tabStartX + tabMcW + tabGap
        val customHovered = !customActive && mouseX >= customTabX && mouseX <= customTabX + tabCustomW &&
                mouseY >= tabY && mouseY <= tabY + tabH
        context.fill(customTabX, tabY, customTabX + tabCustomW, tabY + tabH,
            if (customActive) (ModConfig.accentColor() and 0x00FFFFFF or 0x44000000.toInt()) else if (customHovered) 0xFF252525.toInt() else 0xFF1A1A1A.toInt())
        context.drawText(textRenderer, tabCustom, customTabX + 5, tabY + 2,
            if (customActive) ModConfig.accentColor() else if (customHovered) 0xFFDDDDDD.toInt() else 0xFF777777.toInt(), true)

        // === Results list ===
        val resultsTop = panelTop + 54
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        context.enableScissor(panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        if (activeTab == 0) {
            renderMinecraftList(context, panelX, panelWidth, resultsTop, resultsBottom, mouseX, mouseY)
        } else {
            renderCustomList(context, panelX, panelWidth, resultsTop, resultsBottom, mouseX, mouseY)
        }

        context.disableScissor()

        // === Scrollbar ===
        val listSize = currentListSize()
        val contentHeight = listSize * rowHeight + 4
        val maxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        if (contentHeight > resultsAreaHeight && resultsAreaHeight > 0) {
            sbTrackX = panelX + panelWidth - 5
            sbContentTop = resultsTop
            sbContentBottom = resultsBottom
            context.fill(sbTrackX, resultsTop, sbTrackX + 3, resultsBottom, 0xFF1A1A1A.toInt())
            sbThumbHeight = maxOf(15, resultsAreaHeight * resultsAreaHeight / contentHeight)
            sbThumbY = resultsTop + (scrollOffset * (resultsAreaHeight - sbThumbHeight) / maxOf(1, maxScroll))
            context.fill(sbTrackX, sbThumbY, sbTrackX + 3, sbThumbY + sbThumbHeight, ModConfig.accentColor())
        }

        // === Footer ===
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())

        if (activeTab == 1) {
            // Open folder button — centered, white, prominent
            val openText = "\u2192 ${Translations.tr("Open folder")} \u2190"
            openFolderBtnW = textRenderer.getWidth(openText) + 16
            openFolderBtnX = panelX + (panelWidth - openFolderBtnW) / 2
            openFolderBtnY = panelBottom - 13
            val openHovered = mouseX >= openFolderBtnX && mouseX <= openFolderBtnX + openFolderBtnW &&
                    mouseY >= openFolderBtnY && mouseY <= openFolderBtnY + openFolderBtnH
            context.fill(openFolderBtnX, openFolderBtnY, openFolderBtnX + openFolderBtnW, openFolderBtnY + openFolderBtnH,
                if (openHovered) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt())
            context.drawText(textRenderer, openText, openFolderBtnX + 8, openFolderBtnY + 2,
                if (openHovered) 0xFFFFFFFF.toInt() else 0xFFDDDDDD.toInt(), true)
        } else {
            val hint: String = Translations.tr("ESC to return")
            val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
            context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)
        }

        super.render(context, mouseX, mouseY, delta)
    }

    private fun renderMinecraftList(context: DrawContext, panelX: Int, panelWidth: Int, resultsTop: Int, resultsBottom: Int, mouseX: Int, mouseY: Int) {
        var y = resultsTop + 2 - scrollOffset
        for (soundId in filteredSounds) {
            if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                val idStr = soundId.toString()
                renderSoundRow(context, panelX, panelWidth, resultsTop, resultsBottom, mouseX, mouseY,
                    y, idStr, formatSoundName(soundId), idStr)
            }
            y += rowHeight
        }
    }

    private fun renderCustomList(context: DrawContext, panelX: Int, panelWidth: Int, resultsTop: Int, resultsBottom: Int, mouseX: Int, mouseY: Int) {
        if (filteredCustom.isEmpty()) {
            val msg = Translations.tr("No custom sounds")
            val mx = panelX + (panelWidth - textRenderer.getWidth(msg)) / 2
            context.drawText(textRenderer, msg, mx, resultsTop + 10, 0xFF666666.toInt(), true)

            val hint = Translations.tr("Drop .wav or .ogg files in:")
            val hx = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
            context.drawText(textRenderer, hint, hx, resultsTop + 24, 0xFF555555.toInt(), true)

            val path = "config/hunterboard/sounds/"
            val px = panelX + (panelWidth - textRenderer.getWidth(path)) / 2
            context.drawText(textRenderer, path, px, resultsTop + 36, ModConfig.accentColor(), true)
            return
        }
        var y = resultsTop + 2 - scrollOffset
        for (filename in filteredCustom) {
            if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                val soundId = "custom:$filename"
                renderSoundRow(context, panelX, panelWidth, resultsTop, resultsBottom, mouseX, mouseY,
                    y, soundId, CustomSoundPlayer.formatSoundName(filename), filename)
            }
            y += rowHeight
        }
    }

    private fun renderSoundRow(
        context: DrawContext, panelX: Int, panelWidth: Int,
        resultsTop: Int, resultsBottom: Int, mouseX: Int, mouseY: Int,
        y: Int, soundId: String, displayName: String, rightLabel: String
    ) {
        val isSelected = soundId == selectedSound
        val hovered = mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 10 &&
                mouseY >= y && mouseY <= y + rowHeight &&
                mouseY >= resultsTop && mouseY <= resultsBottom

        if (isSelected) {
            context.fill(panelX + 6, y, panelX + panelWidth - 6, y + rowHeight,
                (ModConfig.accentColor() and 0x00FFFFFF) or 0x33000000.toInt())
        } else if (hovered) {
            context.fill(panelX + 6, y, panelX + panelWidth - 6, y + rowHeight, 0xFF252525.toInt())
        }

        // Play/Stop button
        val playX = panelX + 8
        val isPlaying = playingSound == soundId
        val playHovered = mouseX >= playX && mouseX <= playX + playBtnSize &&
                mouseY >= y && mouseY <= y + rowHeight &&
                mouseY >= resultsTop && mouseY <= resultsBottom
        val playColor = when {
            isPlaying -> 0xFFFF5555.toInt()
            playHovered -> 0xFFFFFFFF.toInt()
            isSelected -> ModConfig.accentColor()
            else -> 0xFF777777.toInt()
        }
        val tx = playX + 2
        val ty = y + 3
        if (isPlaying) {
            context.fill(tx, ty + 1, tx + 5, ty + 7, playColor)
        } else {
            context.fill(tx, ty, tx + 1, ty + 8, playColor)
            context.fill(tx + 1, ty + 1, tx + 2, ty + 7, playColor)
            context.fill(tx + 2, ty + 2, tx + 3, ty + 6, playColor)
            context.fill(tx + 3, ty + 3, tx + 4, ty + 5, playColor)
        }

        val nameColor = when {
            isSelected -> ModConfig.accentColor()
            hovered -> 0xFFFFFFFF.toInt()
            else -> 0xFFBBBBBB.toInt()
        }
        val nameX = panelX + 10 + playBtnSize
        context.drawText(textRenderer, displayName, nameX, y + 3, nameColor, true)

        val idW = textRenderer.getWidth(rightLabel)
        val nameW = textRenderer.getWidth(displayName)
        if (panelX + panelWidth - 12 - idW > nameX + nameW + 8) {
            context.drawText(textRenderer, rightLabel, panelX + panelWidth - 12 - idW, y + 3, 0xFF555555.toInt(), true)
        }
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

        val resultsTop = panelTop + 54
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        // Tab clicks
        val tabY = panelTop + 21
        val tabH = 12
        val tabMc = Translations.tr("Minecraft")
        val tabCustom = Translations.tr("Custom")
        val tabMcW = textRenderer.getWidth(tabMc) + 10
        val tabCustomW = textRenderer.getWidth(tabCustom) + 10
        val tabGap = 4
        val tabTotalW = tabMcW + tabGap + tabCustomW
        val tabStartX = panelX + (panelWidth - tabTotalW) / 2

        if (mouseY >= tabY && mouseY <= tabY + tabH) {
            if (mouseX >= tabStartX && mouseX <= tabStartX + tabMcW) {
                switchTab(0); return true
            }
            val customTabX = tabStartX + tabMcW + tabGap
            if (mouseX >= customTabX && mouseX <= customTabX + tabCustomW) {
                switchTab(1); return true
            }
        }

        // Open folder button (custom tab only)
        if (activeTab == 1 && mouseX >= openFolderBtnX && mouseX <= openFolderBtnX + openFolderBtnW &&
            mouseY >= openFolderBtnY.toDouble() && mouseY <= (openFolderBtnY + openFolderBtnH).toDouble()) {
            CustomSoundPlayer.openDirectory()
            return true
        }

        // Scrollbar click
        val listSize = currentListSize()
        val contentHeight = listSize * rowHeight + 4
        if (contentHeight > resultsAreaHeight && mouseX >= sbTrackX && mouseX <= sbTrackX + 3 &&
            mouseY >= sbContentTop.toDouble() && mouseY <= sbContentBottom.toDouble()) {
            isScrollbarDragging = true
            scrollbarDragStartY = mouseY
            scrollbarDragStartOffset = scrollOffset
            return true
        }

        // Sound list click
        if (mouseX >= panelX + 6 && mouseX <= panelX + panelWidth - 10 &&
            mouseY >= resultsTop.toDouble() && mouseY <= resultsBottom.toDouble()) {
            val clickY = mouseY - resultsTop + scrollOffset - 2
            val index = (clickY / rowHeight).toInt()

            if (activeTab == 0 && index in filteredSounds.indices) {
                val soundId = filteredSounds[index].toString()
                return handleRowClick(mouseX, panelX, soundId, false)
            } else if (activeTab == 1 && index in filteredCustom.indices) {
                val filename = filteredCustom[index]
                val soundId = "custom:$filename"
                return handleRowClick(mouseX, panelX, soundId, true)
            }
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun handleRowClick(mouseX: Double, panelX: Int, soundId: String, isCustom: Boolean): Boolean {
        val playX = panelX + 8
        if (mouseX >= playX && mouseX <= playX + playBtnSize) {
            selectedSound = soundId
            if (playingSound == soundId) {
                stopPreview()
            } else {
                stopPreview()
                try {
                    if (isCustom) {
                        val filename = soundId.removePrefix("custom:")
                        CustomSoundPlayer.play(filename, ModConfig.raidNotifVolumeF(), 1.0f)
                        playingSound = soundId
                    } else {
                        val id = Identifier.of(soundId)
                        val soundEvent = SoundEvent.of(id)
                        val instance = PositionedSoundInstance.master(soundEvent, 1.0f, ModConfig.raidNotifVolumeF())
                        client?.soundManager?.play(instance)
                        currentPreview = instance
                        playingSound = soundId
                    }
                } catch (_: Exception) {}
            }
            return true
        }
        // Click on row → select and confirm
        selectedSound = soundId
        onSelect(soundId)
        client?.setScreen(parent)
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (isScrollbarDragging && button == 0) {
            val panelTop = 25
            val panelBottom = height - 25
            val resultsTop = panelTop + 54
            val resultsBottom = panelBottom - 16
            val resultsAreaHeight = resultsBottom - resultsTop
            val contentHeight = currentListSize() * rowHeight + 4
            val maxScroll = maxOf(0, contentHeight - resultsAreaHeight)
            val trackH = resultsAreaHeight - sbThumbHeight
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
        val panelTop = 25
        val panelBottom = height - 25
        val resultsTop = panelTop + 54
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop
        val contentHeight = currentListSize() * rowHeight + 4
        val maxScroll = maxOf(0, contentHeight - resultsAreaHeight)
        scrollOffset = (scrollOffset - (verticalAmount * 20).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { client?.setScreen(parent); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() { stopPreview(); client?.setScreen(parent) }

    private fun stopPreview() {
        currentPreview?.let { client?.soundManager?.stop(it) }
        currentPreview = null
        CustomSoundPlayer.stop()
        playingSound = null
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
