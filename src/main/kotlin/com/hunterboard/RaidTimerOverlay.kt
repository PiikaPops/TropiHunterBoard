package com.hunterboard

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.sound.SoundEvents
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object RaidTimerOverlay {

    private val ZONE = ZoneId.of("Europe/Paris")

    // Raid times in Paris local time
    private val RAID_TIMES = listOf(
        LocalTime.of(0, 0),
        LocalTime.of(4, 0),
        LocalTime.of(10, 30),
        LocalTime.of(15, 30),
        LocalTime.of(18, 30),
        LocalTime.of(20, 0),
        LocalTime.of(21, 30)
    )

    // Panel position (null = auto center-top)
    var panelX: Int? = null
    var panelY: Int? = null

    // Exposed rendered bounds for HudPositionScreen
    var renderedX = 0; private set
    var renderedY = 0; private set
    var renderedW = 0; private set
    var renderedH = 22; private set

    // Notification state
    private var initialized = false
    private var notifiedRaidTime: LocalTime? = null
    private var notifDisplayUntil: Long = 0L

    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            try { render(context) } catch (_: Exception) {}
        }
    }

    fun loadPosition() {
        panelX = if (ModConfig.raidTimerPosX >= 0) ModConfig.raidTimerPosX else null
        panelY = if (ModConfig.raidTimerPosY >= 0) ModConfig.raidTimerPosY else null
    }

    private fun render(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.player == null) return
        if (!BoardState.hudVisible) { renderedW = 0; renderedH = 0; return }
        if (!ModConfig.showRaidHud) { renderedW = 0; renderedH = 0; return }

        val now = ZonedDateTime.now(ZONE)
        val today = now.toLocalDate()

        // Build list of candidate raid instants (yesterday + today + tomorrow) sorted
        val allRaids = RAID_TIMES.flatMap { t ->
            listOf(
                ZonedDateTime.of(today.minusDays(1), t, ZONE),
                ZonedDateTime.of(today, t, ZONE),
                ZonedDateTime.of(today.plusDays(1), t, ZONE)
            )
        }.sortedBy { it.toInstant() }

        val nextRaid = allRaids.firstOrNull { it.isAfter(now) } ?: return
        val secondsUntil = ChronoUnit.SECONDS.between(now, nextRaid)

        // On first render: suppress stale notifications from before session start
        if (!initialized) {
            initialized = true
            notifiedRaidTime = allRaids.lastOrNull { !it.isAfter(now) }?.toLocalTime()
        }

        // Detect raid start (within 30s of a past raid not yet notified)
        val lastRaid = allRaids.lastOrNull { !it.isAfter(now) }
        if (lastRaid != null) {
            val sinceSec = ChronoUnit.SECONDS.between(lastRaid, now)
            if (sinceSec in 0..29 && notifiedRaidTime != lastRaid.toLocalTime()) {
                notifiedRaidTime = lastRaid.toLocalTime()
                notifDisplayUntil = System.currentTimeMillis() + 6_000L
                // Play bell sound 5 times every 0.5s when raid starts
                Thread {
                    for (i in 0 until 5) {
                        if (i > 0) Thread.sleep(500L)
                        MinecraftClient.getInstance().execute {
                            MinecraftClient.getInstance().player
                                ?.playSound(SoundEvents.BLOCK_BELL_USE, 1.0f, 0.8f)
                        }
                    }
                }.also { it.isDaemon = true }.start()
            }
        }

        val textRenderer = client.textRenderer
        val screenW = client.window.scaledWidth
        val screenH = client.window.scaledHeight
        val sysNow = System.currentTimeMillis()

        // === Notification banner (top-center, shown for 6s) ===
        if (notifDisplayUntil > sysNow) {
            val notifText = "\u2726  ${Translations.tr("Raid Active!")}  \u2726"
            val nW = textRenderer.getWidth(notifText) + 20
            val nH = 18
            val nX = (screenW - nW) / 2
            val nY = 20
            context.fill(nX, nY, nX + nW, nY + nH, 0xCC220000.toInt())
            drawBorder(context, nX, nY, nW, nH, 0xFFCC3333.toInt())
            context.drawText(textRenderer, notifText, nX + 10, nY + 5, 0xFFFF5555.toInt(), true)
        }

        // === Countdown panel ===
        val h = secondsUntil / 3600
        val m = (secondsUntil % 3600) / 60
        val s = secondsUntil % 60
        val countdown = if (h > 0) "%dh%02dm%02ds".format(h, m, s)
                        else       "%02dm%02ds".format(m, s)

        val nextTime = nextRaid.toLocalTime()
        val nextTimeStr = "%02dh%02d".format(nextTime.hour, nextTime.minute)
        val labelText = "${Translations.tr("Next Raid")} \u2022 $nextTimeStr"

        val panelW = maxOf(textRenderer.getWidth(labelText), textRenderer.getWidth(countdown)) + 12
        val panelH = 22

        // Default position: center-top
        val rawX = panelX ?: ((screenW - panelW) / 2)
        val rawY = panelY ?: 4
        val finalX = rawX.coerceIn(0, (screenW - panelW).coerceAtLeast(0))
        val finalY = rawY.coerceIn(0, (screenH - panelH).coerceAtLeast(0))

        // Expose rendered bounds for drag detection
        renderedX = finalX
        renderedY = finalY
        renderedW = panelW
        renderedH = panelH

        context.fill(finalX, finalY, finalX + panelW, finalY + panelH, 0xAA000000.toInt())

        val urgent = secondsUntil <= 120
        val accentColor = if (urgent) 0xFFFF5533.toInt() else ModConfig.accentColor()
        drawBorder(context, finalX, finalY, panelW, panelH, accentColor)

        context.drawText(textRenderer, labelText,   finalX + 6, finalY + 3,  accentColor, true)
        context.drawText(textRenderer, countdown,   finalX + 6, finalY + 13, if (urgent) 0xFFFF5533.toInt() else 0xFFFFFFFF.toInt(), true)
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
