package com.hunterboard

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object KeyBindings {

    private lateinit var toggleHudKey: KeyBinding
    private lateinit var spawnInfoKey: KeyBinding
    private lateinit var searchKey: KeyBinding
    private lateinit var cycleHudModeKey: KeyBinding

    fun register() {
        toggleHudKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.hunterboard.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.hunterboard"
            )
        )

        spawnInfoKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.hunterboard.info",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                "category.hunterboard"
            )
        )

        searchKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.hunterboard.search",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.hunterboard"
            )
        )

        cycleHudModeKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.hunterboard.cycle_mode",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET, // $ on French AZERTY
                "category.hunterboard"
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (toggleHudKey.wasPressed()) {
                BoardState.hudVisible = !BoardState.hudVisible
                val state = if (BoardState.hudVisible) "ON" else "OFF"
                HunterBoard.LOGGER.info("HunterBoard HUD: $state")
            }

            if (spawnInfoKey.wasPressed()) {
                val mc = MinecraftClient.getInstance()
                if (mc.currentScreen is SpawnInfoScreen) {
                    mc.setScreen(null)
                } else if (mc.currentScreen == null) {
                    SpawnData.ensureLoaded()
                    mc.setScreen(SpawnInfoScreen())
                }
            }

            if (searchKey.wasPressed()) {
                val mc = MinecraftClient.getInstance()
                if (mc.currentScreen == null) {
                    SpawnData.ensureLoaded()
                    mc.setScreen(PokemonSearchScreen())
                }
            }

            if (cycleHudModeKey.wasPressed()) {
                if (client.currentScreen == null) {
                    val newMode = (BoardState.displayMode + 1) % 5
                    BoardState.setDisplayMode(newMode)
                }
            }
        }
    }
}
