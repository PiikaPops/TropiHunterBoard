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
    private var scrollOffset = 0
    private val rowHeight = 14
    private var selectedSound: String = currentSound
    private val playBtnSize = 10  // play button hit area width
    private var currentPreview: SoundInstance? = null
    private var playingSound: String? = null  // ID of currently playing sound

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
        allSounds = Registries.SOUND_EVENT.ids.toList().sortedBy { it.toString() }
        filteredSounds = allSounds

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25

        val placeholder: String = Translations.tr("Search sound...")
        searchField = TextFieldWidget(textRenderer, panelX + 10, panelTop + 22, panelWidth - 20, 14, Text.literal(placeholder))
        searchField.setMaxLength(100)
        searchField.setChangedListener { updateSearch() }
        addDrawableChild(searchField)
        setInitialFocus(searchField)
    }

    private fun updateSearch() {
        val query = searchField.text.lowercase().trim()
        scrollOffset = 0
        filteredSounds = if (query.isEmpty()) allSounds
        else allSounds.filter { it.toString().contains(query) || formatSoundName(it).lowercase().contains(query) }
    }

    private fun formatSoundName(id: Identifier): String {
        return id.path.replace(".", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
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

        val title = Translations.tr("Select")
        val titleX = panelX + (panelWidth - textRenderer.getWidth(title)) / 2
        context.drawText(textRenderer, title, titleX, panelTop + 6, ModConfig.accentColor(), true)

        context.fill(panelX + 6, panelTop + 18, panelX + panelWidth - 6, panelTop + 19, ModConfig.accentColor())

        val resultsTop = panelTop + 40
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        // Scissor for scrollable list
        context.enableScissor(panelX + 1, resultsTop, panelX + panelWidth - 1, resultsBottom)

        var y = resultsTop + 2 - scrollOffset
        for (soundId in filteredSounds) {
            if (y + rowHeight > resultsTop - rowHeight && y < resultsBottom + rowHeight) {
                val idStr = soundId.toString()
                val isSelected = idStr == selectedSound
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
                val isPlaying = playingSound == idStr
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
                    // Draw stop square ■
                    context.fill(tx, ty + 1, tx + 5, ty + 7, playColor)
                } else {
                    // Draw play triangle ▶
                    context.fill(tx, ty, tx + 1, ty + 8, playColor)
                    context.fill(tx + 1, ty + 1, tx + 2, ty + 7, playColor)
                    context.fill(tx + 2, ty + 2, tx + 3, ty + 6, playColor)
                    context.fill(tx + 3, ty + 3, tx + 4, ty + 5, playColor)
                }

                val displayName = formatSoundName(soundId)
                val nameColor = when {
                    isSelected -> ModConfig.accentColor()
                    hovered -> 0xFFFFFFFF.toInt()
                    else -> 0xFFBBBBBB.toInt()
                }
                val nameX = panelX + 10 + playBtnSize
                context.drawText(textRenderer, displayName, nameX, y + 3, nameColor, true)

                // Show full ID on right side
                val idW = textRenderer.getWidth(idStr)
                val nameW = textRenderer.getWidth(displayName)
                if (panelX + panelWidth - 12 - idW > nameX + nameW + 8) {
                    context.drawText(textRenderer, idStr, panelX + panelWidth - 12 - idW, y + 3, 0xFF555555.toInt(), true)
                }
            }
            y += rowHeight
        }

        context.disableScissor()

        // Scrollbar
        val contentHeight = filteredSounds.size * rowHeight + 4
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

        // Footer
        context.fill(panelX + 1, panelBottom - 14, panelX + panelWidth - 1, panelBottom - 1, 0xFF0D0D0D.toInt())
        context.fill(panelX + 6, panelBottom - 14, panelX + panelWidth - 6, panelBottom - 13, 0xFF2A2A2A.toInt())
        val hint: String = Translations.tr("ESC to return")
        val hintX = panelX + (panelWidth - textRenderer.getWidth(hint)) / 2
        context.drawText(textRenderer, hint, hintX, panelBottom - 10, 0xFF555555.toInt(), true)

        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)

        val panelWidth = (width * 0.55).toInt().coerceIn(260, 450)
        val panelX = (width - panelWidth) / 2
        val panelTop = 25
        val panelBottom = height - 25
        val resultsTop = panelTop + 40
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop

        // Scrollbar click
        val contentHeight = filteredSounds.size * rowHeight + 4
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
            if (index in filteredSounds.indices) {
                val soundId = filteredSounds[index].toString()
                val playX = panelX + 8
                // Click on play button → toggle preview
                if (mouseX >= playX && mouseX <= playX + playBtnSize) {
                    selectedSound = soundId
                    if (playingSound == soundId) {
                        // Stop current preview
                        stopPreview()
                    } else {
                        // Stop previous and play new
                        stopPreview()
                        try {
                            val id = Identifier.of(soundId)
                            val soundEvent = SoundEvent.of(id)
                            val instance = PositionedSoundInstance.master(soundEvent, 1.0f, ModConfig.raidNotifVolumeF())
                            client?.soundManager?.play(instance)
                            currentPreview = instance
                            playingSound = soundId
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
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (isScrollbarDragging && button == 0) {
            val panelTop = 25
            val panelBottom = height - 25
            val resultsTop = panelTop + 40
            val resultsBottom = panelBottom - 16
            val resultsAreaHeight = resultsBottom - resultsTop
            val contentHeight = filteredSounds.size * rowHeight + 4
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
        val resultsTop = panelTop + 40
        val resultsBottom = panelBottom - 16
        val resultsAreaHeight = resultsBottom - resultsTop
        val contentHeight = filteredSounds.size * rowHeight + 4
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
        playingSound = null
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
