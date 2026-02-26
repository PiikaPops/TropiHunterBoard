package com.hunterboard

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Environment(EnvType.CLIENT)
object HunterBoard : ClientModInitializer {
    const val MOD_ID = "hunterboard"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        LOGGER.info("HunterBoard initializing...")

        // Register mod sounds
        ModSounds.register()

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

        // Register clear warning overlay
        ClearWarningOverlay.register()

        // Check for updates on Modrinth
        UpdateChecker.register()

        LOGGER.info("HunterBoard initialized!")
    }

}
