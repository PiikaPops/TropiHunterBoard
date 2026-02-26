package com.hunterboard

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Identifier
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object RaidTimerOverlay {

    private val ZONE = ZoneId.of("Europe/Paris")

    // Chat-triggered raid detection (e.g. "<pseudo> a déclenché un Raid !")
    private val RAID_TRIGGER_REGEX = Regex(""".+ a déclenché un Raid\s*!""")

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

    // 5-minute warning state
    private var notified5MinWarning: LocalTime? = null
    private var warningDisplayUntil: Long = 0L

    // Cached for merged mode
    private var cachedLabelText = ""
    private var cachedCountdown = ""
    private var cachedUrgent = false
    private var raidDataReady = false

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            handleChat(message.string)
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleChat(message.string)
        }
        HudRenderCallback.EVENT.register { context, _ ->
            try { render(context) } catch (_: Exception) {}
        }
    }

    private fun handleChat(text: String) {
        if (RAID_TRIGGER_REGEX.containsMatchIn(text)) {
            notifDisplayUntil = System.currentTimeMillis() + 6_000L
            playRaidStartSound()
        }
    }

    fun loadPosition() {
        panelX = if (ModConfig.raidTimerPosX >= 0) ModConfig.raidTimerPosX else null
        panelY = if (ModConfig.raidTimerPosY >= 0) ModConfig.raidTimerPosY else null
    }

    private fun render(context: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (client.player == null) return
        if (ModConfig.hideHudInBattle && BattleHelper.isInBattle()) { renderedW = 0; renderedH = 0; return }
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
            if (secondsUntil <= 301) notified5MinWarning = nextRaid.toLocalTime()
        }

        // Detect raid start (within 30s of a past raid not yet notified)
        val lastRaid = allRaids.lastOrNull { !it.isAfter(now) }
        if (lastRaid != null) {
            val sinceSec = ChronoUnit.SECONDS.between(lastRaid, now)
            if (sinceSec in 0..29 && notifiedRaidTime != lastRaid.toLocalTime()) {
                notifiedRaidTime = lastRaid.toLocalTime()
                notifDisplayUntil = System.currentTimeMillis() + 6_000L
                playRaidStartSound()
            }
        }

        // Detect 5-minute warning (299-301 seconds before next raid)
        if (ModConfig.raidNotification && secondsUntil in 299..301) {
            val upcomingTime = nextRaid.toLocalTime()
            if (notified5MinWarning != upcomingTime) {
                notified5MinWarning = upcomingTime
                warningDisplayUntil = System.currentTimeMillis() + 6_000L
                val warnTimes = if (ModConfig.raidSoundRepeat) 3 else 1
                playNotificationSound(ModConfig.raidWarningSound, warnTimes, 1.2f)
            }
        }

        val textRenderer = client.textRenderer
        val screenW = client.window.scaledWidth
        val screenH = client.window.scaledHeight
        val sysNow = System.currentTimeMillis()

        // === 5-minute warning banner (top-center, shown for 6s, orange) ===
        if (warningDisplayUntil > sysNow) {
            val warnText = "\u26a0  ${Translations.tr("Raid in 5 min!")}  \u26a0"
            val wW = textRenderer.getWidth(warnText) + 20
            val wH = 18
            val wX = (screenW - wW) / 2
            val wY = if (notifDisplayUntil > sysNow) 42 else 20
            context.fill(wX, wY, wX + wW, wY + wH, 0xCC332200.toInt())
            drawBorder(context, wX, wY, wW, wH, 0xFFFF8833.toInt())
            context.drawText(textRenderer, warnText, wX + 10, wY + 5, 0xFFFFAA33.toInt(), true)
        }

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

        // Cache for merged mode
        cachedLabelText = labelText
        cachedCountdown = countdown
        cachedUrgent = secondsUntil <= 120
        raidDataReady = true

        // In merged mode, skip standalone panel rendering
        if (ModConfig.mergedHudMode) { renderedW = 0; renderedH = 0; return }

        val scale = ModConfig.raidScale()
        val panelW = maxOf(textRenderer.getWidth(labelText), textRenderer.getWidth(countdown)) + 12
        val panelH = 22
        val scaledW = (panelW * scale).toInt()
        val scaledH = (panelH * scale).toInt()

        // Default position: center-top (use scaled dimensions for clamping)
        val rawX = panelX ?: ((screenW - scaledW) / 2)
        val rawY = panelY ?: 4
        val clampedX = rawX.coerceIn(0, (screenW - scaledW).coerceAtLeast(0))
        val clampedY = rawY.coerceIn(0, (screenH - scaledH).coerceAtLeast(0))

        // Anti-overlap: register with layout manager (skip in merged mode)
        val (finalX, finalY) = if (!ModConfig.mergedHudMode) {
            HudLayoutManager.register(clampedX, clampedY, scaledW, scaledH, screenW, screenH)
        } else clampedX to clampedY

        // Expose rendered bounds in screen pixels
        renderedX = finalX
        renderedY = finalY
        renderedW = scaledW
        renderedH = scaledH

        val urgent = secondsUntil <= 120
        val accentColor = if (urgent) 0xFFFF5533.toInt() else ModConfig.accentColor()

        context.matrices.push()
        context.matrices.translate(finalX.toFloat(), finalY.toFloat(), 0f)
        context.matrices.scale(scale, scale, 1f)

        if (!ModConfig.fullClearMode) {
            context.fill(0, 0, panelW, panelH, ModConfig.bgColor())
            drawBorder(context, 0, 0, panelW, panelH, accentColor)
        }

        val labelX = (panelW - textRenderer.getWidth(labelText)) / 2
        val countdownX = (panelW - textRenderer.getWidth(countdown)) / 2
        context.drawText(textRenderer, labelText,   labelX, 3,  accentColor, true)
        context.drawText(textRenderer, countdown,   countdownX, 13, if (urgent) 0xFFFF5533.toInt() else 0xFFFFFFFF.toInt(), true)

        context.matrices.pop()
    }

    /** Whether the raid section has content to show (for merged mode) */
    fun isActive(): Boolean = raidDataReady && ModConfig.showRaidHud

    /** Height of the raid content section (for merged panel sizing) */
    fun contentHeight(): Int = if (isActive()) 22 else 0

    /** Width needed by raid content (for merged panel width calculation) */
    fun contentWidth(): Int {
        if (!isActive()) return 0
        val tr = MinecraftClient.getInstance().textRenderer
        return maxOf(tr.getWidth(cachedLabelText), tr.getWidth(cachedCountdown))
    }

    /** Draw raid content without background/border. Returns height used. */
    fun renderContent(context: DrawContext, x: Int, y: Int, panelWidth: Int): Int {
        if (!isActive()) return 0
        val tr = MinecraftClient.getInstance().textRenderer
        val accentColor = if (cachedUrgent) 0xFFFF5533.toInt() else ModConfig.accentColor()
        val labelX = x + (panelWidth - tr.getWidth(cachedLabelText)) / 2
        val countdownX = x + (panelWidth - tr.getWidth(cachedCountdown)) / 2
        context.drawText(tr, cachedLabelText, labelX, y, accentColor, true)
        context.drawText(tr, cachedCountdown, countdownX, y + 10, if (cachedUrgent) 0xFFFF5533.toInt() else 0xFFFFFFFF.toInt(), true)
        return 22
    }

    private fun playRaidStartSound() {
        // Easter egg: 1/2048 chance to play the special sound instead
        if (kotlin.random.Random.nextInt(2048) == 0) {
            playNotificationSound("hunterboard:special_rickroll", 1, 1.0f)
        } else {
            val times = if (ModConfig.raidSoundRepeat) 5 else 1
            playNotificationSound(ModConfig.raidStartSound, times, 0.8f)
        }
    }

    private fun playNotificationSound(soundId: String, times: Int, pitch: Float) {
        val volume = ModConfig.raidNotifVolumeF()
        if (volume <= 0f) return
        if (soundId.startsWith("custom:")) {
            val filename = soundId.removePrefix("custom:")
            Thread {
                for (i in 0 until times) {
                    if (i > 0) Thread.sleep(500L)
                    CustomSoundPlayer.play(filename, volume, pitch)
                }
            }.also { it.isDaemon = true }.start()
        } else {
            Thread {
                for (i in 0 until times) {
                    if (i > 0) Thread.sleep(500L)
                    MinecraftClient.getInstance().execute {
                        try {
                            val id = Identifier.of(soundId)
                            val soundEvent = SoundEvent.of(id)
                            MinecraftClient.getInstance().player?.playSound(soundEvent, volume, pitch)
                        } catch (_: Exception) {
                            MinecraftClient.getInstance().player
                                ?.playSound(SoundEvents.BLOCK_BELL_USE, volume, pitch)
                        }
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }
}
