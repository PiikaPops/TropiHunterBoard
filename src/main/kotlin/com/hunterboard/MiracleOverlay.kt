package com.hunterboard

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

object MiracleOverlay {

    // Active boosts: name -> expiry timestamp in ms
    private val activeBoosts = linkedMapOf<String, Long>()
    private const val HOUR_MS = 3_600_000L
    // Known boost names (as displayed without "Boost " prefix)
    private val KNOWN_BOOSTS = setOf("Shiny x2", "IVs +10", "Talent Caché 10%", "XP x2")

    // Chat: "X a déclenché un Boost Shiny x2 pendant une heure !"
    private val BOOST_REGEX  = Regex("""a déclenché un (.+?) pendant une heure""")
    // Chat: "X a augmenté la durée du Boost Shiny x2 (fin dans 1 heure, 54 minutes et 9 secondes) !"
    private val EXTEND_REGEX = Regex("""a augmenté la durée du (.+?) \(fin dans (.+?)\)""")
    // /miracle list: "- Shiny x2 (fin dans 25 minutes et 17 secondes)"
    private val LIST_REGEX   = Regex("""^- (.+?) \(fin dans (.+?)\)""")

    // Panel position (null = auto bottom-right)
    var panelX: Int? = null
    var panelY: Int? = null

    // Rendered bounds exposed for HudPositionScreen
    var renderedX = 0; private set
    var renderedY = 0; private set
    var renderedW = 0; private set
    var renderedH = 0; private set

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

    private fun handleText(text: String) {
        // Real-time activation: add 1h (stackable)
        val triggerMatch = BOOST_REGEX.find(text)
        if (triggerMatch != null) {
            val boostName = triggerMatch.groupValues[1].removePrefix("Boost ")
            if (boostName in KNOWN_BOOSTS) addOrExtend(boostName)
            return
        }
        // Duration extension: set exact remaining time from message
        val extendMatch = EXTEND_REGEX.find(text)
        if (extendMatch != null) {
            val boostName = extendMatch.groupValues[1].trim().removePrefix("Boost ")
            if (boostName in KNOWN_BOOSTS) {
                val totalMs = parseTimeMs(extendMatch.groupValues[2])
                if (totalMs > 0) activeBoosts[boostName] = System.currentTimeMillis() + totalMs
                else addOrExtend(boostName)
            }
            return
        }
        // /miracle list line: set exact remaining time
        val listMatch = LIST_REGEX.find(text) ?: return
        val boostName = listMatch.groupValues[1]
        if (boostName !in KNOWN_BOOSTS) return
        val totalMs = parseTimeMs(listMatch.groupValues[2])
        if (totalMs > 0) activeBoosts[boostName] = System.currentTimeMillis() + totalMs
    }

    private fun addOrExtend(boostName: String) {
        val now = System.currentTimeMillis()
        val existing = activeBoosts[boostName]
        activeBoosts[boostName] = if (existing != null && existing > now) existing + HOUR_MS
                                   else now + HOUR_MS
    }

    private fun parseTimeMs(timeStr: String): Long {
        val h = Regex("""(\d+) heure""").find(timeStr)?.groupValues?.get(1)?.toLong() ?: 0L
        val m = Regex("""(\d+) minute""").find(timeStr)?.groupValues?.get(1)?.toLong() ?: 0L
        val s = Regex("""(\d+) seconde""").find(timeStr)?.groupValues?.get(1)?.toLong() ?: 0L
        return (h * 3600 + m * 60 + s) * 1000L
    }

    private fun render(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.player == null) return
        if (!BoardState.hudVisible) { renderedW = 0; renderedH = 0; return }
        if (!ModConfig.showMiracleHud) { renderedW = 0; renderedH = 0; return }

        val now = System.currentTimeMillis()
        activeBoosts.entries.removeIf { it.value <= now }

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
            activeBoosts.map { (name, expiry) ->
                val sec = (expiry - now) / 1000
                val h = sec / 3600
                val m = (sec % 3600) / 60
                val s = sec % 60
                val timeStr = if (h > 0) "%dh%02dm%02ds".format(h, m, s) else "%02dm%02ds".format(m, s)
                "$name  $timeStr" to 0xFFFFFFFF.toInt()
            }
        }

        val maxLineW = lines.maxOf { tr.getWidth(it.first) }
        val panelW = maxOf(tr.getWidth(title), maxLineW) + padH * 2
        val panelH = padV + titleH + lines.size * lineH + padV

        val finalX = (panelX ?: (screenW * 3 / 4 - panelW / 2)).coerceIn(0, (screenW - panelW).coerceAtLeast(0))
        val finalY = (panelY ?: (screenH - panelH - 5)).coerceIn(0, (screenH - panelH).coerceAtLeast(0))
        renderedX = finalX; renderedY = finalY; renderedW = panelW; renderedH = panelH

        context.fill(finalX, finalY, finalX + panelW, finalY + panelH, 0xAA000000.toInt())
        drawBorder(context, finalX, finalY, panelW, panelH, ModConfig.accentColor())
        context.drawText(tr, title, finalX + (panelW - tr.getWidth(title)) / 2, finalY + padV, ModConfig.accentColor(), true)

        var textY = finalY + padV + titleH
        for ((text, color) in lines) {
            context.drawText(tr, text, finalX + padH, textY, color, true)
            textY += lineH
        }
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
