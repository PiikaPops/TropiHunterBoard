package com.hunterboard

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId

@Environment(EnvType.CLIENT)
object HunterBoard : ClientModInitializer {
    const val MOD_ID = "hunterboard"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        LOGGER.info("HunterBoard initializing...")

        // Register keybindings (B to toggle HUD)
        KeyBindings.register()

        // Register board detection (listens for Hunting Board screens)
        BoardDetector.register()

        // Load persistent data (before overlay so position/color are available)
        ModConfig.load()
        SearchHistory.load()

        // Register HUD layout manager (must be first so beginFrame() runs before HUDs)
        HudRenderCallback.EVENT.register { _, _ -> HudLayoutManager.beginFrame() }

        // Register HUD overlays (order = layout priority: Raid > Miracle > Hunt)
        RaidTimerOverlay.loadPosition()
        RaidTimerOverlay.register()

        MiracleOverlay.loadPosition()
        MiracleOverlay.register()

        HuntOverlay.register()

        // Register auto-catch detection
        CatchDetector.register()

        // Register midnight reset checker
        registerMidnightReset()

        // Check for updates on Modrinth
        UpdateChecker.register()

        LOGGER.info("HunterBoard initialized!")
    }

    private var lastCheckedDate: LocalDate? = null
    private var midnightTickSkip = 0

    private fun registerMidnightReset() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.world == null || !ModConfig.midnightReset) return@register
            if (++midnightTickSkip < 1200) return@register // check every ~60 seconds
            midnightTickSkip = 0

            try {
                val today = LocalDate.now(ZoneId.of("Europe/Paris"))
                if (lastCheckedDate == null) {
                    lastCheckedDate = today
                    return@register
                }
                if (today != lastCheckedDate) {
                    lastCheckedDate = today
                    if (BoardState.hasTargets()) {
                        BoardState.resetBoard()
                        LOGGER.info("Midnight reset triggered")
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
