package com.hunterboard

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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

        // Register HUD overlay
        HuntOverlay.register()

        // Register auto-catch detection
        CatchDetector.register()

        LOGGER.info("HunterBoard initialized!")
    }
}
