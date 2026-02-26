package com.hunterboard

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Identifier

object MiracleOverlay {

    // Active boosts: keyword -> expiry timestamp in ms
    private val activeBoosts = linkedMapOf<String, Long>()
    // Display names: keyword -> full name (e.g., "Shiny" -> "Shiny x2")
    private val boostDisplayNames = linkedMapOf<String, String>()
    private const val HOUR_MS = 3_600_000L
    // Known boost keyword prefixes (matched case-insensitively at start of boost name)
    private val KNOWN_BOOST_KEYWORDS = listOf("Shiny", "IVs", "Talent Caché", "Hidden Ability", "XP")

    // FR: "X a déclenché un Boost Shiny x2 pendant une heure !"
    private val BOOST_REGEX_FR  = Regex("""a déclenché un (.+?) pendant une heure""")
    // EN: "X triggered a Boost Shiny x2 for an hour !"
    private val BOOST_REGEX_EN  = Regex("""triggered a (.+?) for (?:one|an) hour""")
    // FR: "X a augmenté la durée du Boost Shiny x2 (fin dans 1 heure, 54 minutes et 9 secondes) !"
    private val EXTEND_REGEX_FR = Regex("""a augmenté la durée du (.+?) \(fin dans (.+?)\)""")
    // EN: "X has increased the duration of Boost Shiny x2 (end in 1 hour, 59 minutes and 59 seconds) !"
    private val EXTEND_REGEX_EN = Regex("""(?:extended|has increased) the duration of (.+?) \(end in (.+?)\)""")
    // FR: "- Shiny x2 (fin dans 25 minutes et 17 secondes)"
    private val LIST_REGEX_FR   = Regex("""^- (.+?) \(fin dans (.+?)\)""")
    // EN: "- Shiny x2 (end in 1 hour, 13 minutes and 38 seconds)"
    private val LIST_REGEX_EN   = Regex("""^- (.+?) \(end in (.+?)\)""")

    // Panel position (null = auto bottom-right)
    var panelX: Int? = null
    var panelY: Int? = null

    // Rendered bounds exposed for HudPositionScreen
    var renderedX = 0; private set
    var renderedY = 0; private set
    var renderedW = 0; private set
    var renderedH = 0; private set

    // Cached for merged mode
    private var cachedLines: List<Pair<String, Int>> = emptyList()

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            handleText(message.string)
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleText(message.string)
        }
        HudRenderCallback.EVENT.register { context, _ ->
            try { render(context) } catch (_: Exception) {}
        }
    }

    fun loadPosition() {
        panelX = if (ModConfig.miraclePosX >= 0) ModConfig.miraclePosX else null
        panelY = if (ModConfig.miraclePosY >= 0) ModConfig.miraclePosY else null
    }

    /** Match a boost name against known keyword prefixes, return the keyword or null */
    private fun matchBoostKeyword(name: String): String? {
        return KNOWN_BOOST_KEYWORDS.firstOrNull { name.startsWith(it, ignoreCase = true) }
    }

    private fun handleText(text: String) {
        // Real-time activation: add 1h (stackable)
        val triggerMatch = BOOST_REGEX_FR.find(text) ?: BOOST_REGEX_EN.find(text)
        if (triggerMatch != null) {
            val boostName = triggerMatch.groupValues[1].removePrefix("Boost ").trim()
            val keyword = matchBoostKeyword(boostName) ?: return
            boostDisplayNames[keyword] = boostName
            addOrExtend(keyword)
            playMiracleSound()
            return
        }
        // Duration extension: set exact remaining time from message
        val extendMatch = EXTEND_REGEX_FR.find(text) ?: EXTEND_REGEX_EN.find(text)
        if (extendMatch != null) {
            val boostName = extendMatch.groupValues[1].trim().removePrefix("Boost ").trim()
            val keyword = matchBoostKeyword(boostName) ?: return
            boostDisplayNames[keyword] = boostName
            val totalMs = parseTimeMs(extendMatch.groupValues[2])
            if (totalMs > 0) activeBoosts[keyword] = System.currentTimeMillis() + totalMs
            else addOrExtend(keyword)
            playMiracleSound()
            return
        }
        // /miracle list line: set exact remaining time
        val listMatch = LIST_REGEX_FR.find(text) ?: LIST_REGEX_EN.find(text) ?: return
        val boostName = listMatch.groupValues[1].trim()
        val keyword = matchBoostKeyword(boostName) ?: return
        boostDisplayNames[keyword] = boostName
        val totalMs = parseTimeMs(listMatch.groupValues[2])
        if (totalMs > 0) activeBoosts[keyword] = System.currentTimeMillis() + totalMs
    }

    private fun addOrExtend(boostName: String) {
        val now = System.currentTimeMillis()
        val existing = activeBoosts[boostName]
        activeBoosts[boostName] = if (existing != null && existing > now) existing + HOUR_MS
                                   else now + HOUR_MS
    }

    private fun parseTimeMs(timeStr: String): Long {
        val h = Regex("""(\d+) hours?|(\d+) heures?""").find(timeStr)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.toLongOrNull() ?: 0L
        val m = Regex("""(\d+) minutes?""").find(timeStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val s = Regex("""(\d+) seconds?|(\d+) secondes?""").find(timeStr)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.toLongOrNull() ?: 0L
        return (h * 3600 + m * 60 + s) * 1000L
    }

    private fun render(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.player == null) return
        if (ModConfig.hideHudInBattle && BattleHelper.isInBattle()) { renderedW = 0; renderedH = 0; return }
        if (!BoardState.hudVisible) { renderedW = 0; renderedH = 0; return }
        if (!ModConfig.showMiracleHud) { renderedW = 0; renderedH = 0; return }

        val now = System.currentTimeMillis()
        val expired = activeBoosts.entries.filter { it.value <= now }.map { it.key }
        expired.forEach { activeBoosts.remove(it); boostDisplayNames.remove(it) }

        val tr = client.textRenderer
        val screenW = client.window.scaledWidth
        val screenH = client.window.scaledHeight

        val title = "\u2726 Miracles \u2726"
        val lineH = 11
        val padH = 6
        val padV = 5
        val titleH = 11

        val lines: List<Pair<String, Int>> = if (activeBoosts.isEmpty()) {
            listOf(Translations.tr("No active boost") to 0xFF666666.toInt())
        } else {
            activeBoosts.map { (keyword, expiry) ->
                val displayName = boostDisplayNames[keyword] ?: keyword
                val sec = (expiry - now) / 1000
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val s = sec % 60
                val timeStr = if (h > 0) "%dh%02dm%02ds".format(h, m, s) else "%02dm%02ds".format(m, s)
                "$displayName  $timeStr" to 0xFFFFFFFF.toInt()
            }
        }

        // Cache for merged mode
        cachedLines = lines

        // In merged mode, skip standalone panel rendering
        if (ModConfig.mergedHudMode) { renderedW = 0; renderedH = 0; return }

        val scale = ModConfig.miracleScale()
        val maxLineW = lines.maxOf { tr.getWidth(it.first) }
        val panelW = maxOf(tr.getWidth(title), maxLineW) + padH * 2
        val panelH = padV + titleH + lines.size * lineH + padV
        val scaledW = (panelW * scale).toInt()
        val scaledH = (panelH * scale).toInt()

        val clampedX = (panelX ?: (screenW * 3 / 4 - scaledW / 2)).coerceIn(0, (screenW - scaledW).coerceAtLeast(0))
        val clampedY = (panelY ?: (screenH - scaledH - 5)).coerceIn(0, (screenH - scaledH).coerceAtLeast(0))

        // Anti-overlap: register with layout manager (skip in merged mode)
        val (finalX, finalY) = if (!ModConfig.mergedHudMode) {
            HudLayoutManager.register(clampedX, clampedY, scaledW, scaledH, screenW, screenH)
        } else clampedX to clampedY
        renderedX = finalX; renderedY = finalY; renderedW = scaledW; renderedH = scaledH

        context.matrices.push()
        context.matrices.translate(finalX.toFloat(), finalY.toFloat(), 0f)
        context.matrices.scale(scale, scale, 1f)

        if (!ModConfig.fullClearMode) {
            context.fill(0, 0, panelW, panelH, ModConfig.bgColor())
            drawBorder(context, 0, 0, panelW, panelH, ModConfig.accentColor())
        }
        context.drawText(tr, title, (panelW - tr.getWidth(title)) / 2, padV, ModConfig.accentColor(), true)

        var textY = padV + titleH
        for ((text, color) in lines) {
            context.drawText(tr, text, padH, textY, color, true)
            textY += lineH
        }

        context.matrices.pop()
    }

    /** Whether the miracle section has content to show (for merged mode) */
    fun isActive(): Boolean = ModConfig.showMiracleHud

    /** Height of the miracle content section (for merged panel sizing) */
    fun contentHeight(): Int = if (isActive()) 11 + cachedLines.size * 11 else 0

    /** Width needed by miracle content (for merged panel width calculation) */
    fun contentWidth(): Int {
        if (!isActive()) return 0
        val tr = MinecraftClient.getInstance().textRenderer
        val title = "\u2726 Miracles \u2726"
        val maxLineW = cachedLines.maxOfOrNull { tr.getWidth(it.first) } ?: 0
        return maxOf(tr.getWidth(title), maxLineW)
    }

    /** Draw miracle content without background/border, with centered title. */
    fun renderContent(context: DrawContext, x: Int, y: Int, panelWidth: Int): Int {
        if (!isActive()) return 0
        val tr = MinecraftClient.getInstance().textRenderer
        val lineH = 11
        val title = "\u2726 Miracles \u2726"
        // Center title within the panel width
        val titleX = x + (panelWidth - tr.getWidth(title)) / 2
        context.drawText(tr, title, titleX, y, ModConfig.accentColor(), true)
        var textY = y + 11
        for ((text, color) in cachedLines) {
            context.drawText(tr, text, x, textY, color, true)
            textY += lineH
        }
        return 11 + cachedLines.size * lineH
    }

    private fun playMiracleSound() {
        if (!ModConfig.miracleNotification) return
        val volume = ModConfig.raidNotifVolumeF()
        if (volume <= 0f) return
        val soundId = ModConfig.miracleSound
        if (soundId.startsWith("custom:")) {
            val filename = soundId.removePrefix("custom:")
            Thread {
                CustomSoundPlayer.play(filename, volume, 1.0f)
            }.also { it.isDaemon = true }.start()
        } else {
            MinecraftClient.getInstance().execute {
                try {
                    val id = Identifier.of(soundId)
                    val soundEvent = SoundEvent.of(id)
                    MinecraftClient.getInstance().player?.playSound(soundEvent, volume, 1.0f)
                } catch (_: Exception) {
                    MinecraftClient.getInstance().player
                        ?.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, volume, 1.0f)
                }
            }
        }
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
