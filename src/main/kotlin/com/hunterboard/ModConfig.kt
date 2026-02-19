package com.hunterboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

object ModConfig {

    // HUD accent color (RGB, no alpha)
    var hudColorR: Int = 0xFF
        private set
    var hudColorG: Int = 0xAA
        private set
    var hudColorB: Int = 0x00
        private set

    // HUD background opacity (0-100)
    var hudOpacity: Int = 80
        private set

    // Server rank: 0=Dresseur(3), 1=Super(4), 2=Hyper(5), 3=Master(6)
    var rank: Int = 3
        private set

    // HUD size preset: 0=Small, 1=Normal, 2=Large
    var hudSizePreset: Int = 1
        private set

    // HUD position (-1 = auto bottom-right)
    var hudPosX: Int = -1
        private set
    var hudPosY: Int = -1
        private set

    // Raid timer position (-1 = auto center-top)
    var raidTimerPosX: Int = -1
        private set
    var raidTimerPosY: Int = -1
        private set

    // Miracle HUD position (-1 = auto bottom-right)
    var miraclePosX: Int = -1
        private set
    var miraclePosY: Int = -1
        private set

    // HUD visibility toggles
    var showRaidHud: Boolean = true
        private set
    var showMiracleHud: Boolean = true
        private set

    val RANKS = listOf(
        "Dresseur" to 3,
        "Super" to 4,
        "Hyper" to 5,
        "Master" to 6
    )

    fun maxHunts(): Int = RANKS[rank.coerceIn(0, 3)].second

    /** Full accent color with 0xFF alpha */
    fun accentColor(): Int {
        return (0xFF shl 24) or (hudColorR shl 16) or (hudColorG shl 8) or hudColorB
    }

    /** Background color: black with configured opacity */
    fun bgColor(): Int {
        val alpha = (hudOpacity * 255 / 100).coerceIn(0, 255)
        return (alpha shl 24)
    }

    fun setColor(r: Int, g: Int, b: Int) {
        hudColorR = r.coerceIn(0, 255)
        hudColorG = g.coerceIn(0, 255)
        hudColorB = b.coerceIn(0, 255)
        save()
    }

    fun setOpacity(value: Int) {
        hudOpacity = value.coerceIn(0, 100)
        save()
    }

    fun setRank(value: Int) {
        rank = value.coerceIn(0, 3)
        save()
    }

    fun setHudSizePreset(value: Int) {
        hudSizePreset = value.coerceIn(0, 3)
        save()
    }

    fun setHudPosition(x: Int, y: Int) {
        hudPosX = x
        hudPosY = y
        save()
    }

    fun resetHudPosition() {
        hudPosX = -1
        hudPosY = -1
        save()
    }

    fun setRaidTimerPosition(x: Int, y: Int) {
        raidTimerPosX = x
        raidTimerPosY = y
        save()
    }

    fun resetRaidTimerPosition() {
        raidTimerPosX = -1
        raidTimerPosY = -1
        save()
    }

    fun setMiraclePosition(x: Int, y: Int) {
        miraclePosX = x
        miraclePosY = y
        save()
    }

    fun resetMiraclePosition() {
        miraclePosX = -1
        miraclePosY = -1
        save()
    }

    fun toggleRaidHud() {
        showRaidHud = !showRaidHud
        save()
    }

    fun toggleMiracleHud() {
        showMiracleHud = !showMiracleHud
        save()
    }

    fun resetToDefaults() {
        hudColorR = 0xFF
        hudColorG = 0xAA
        hudColorB = 0x00
        hudOpacity = 80
        hudSizePreset = 1
        rank = 3
        hudPosX = -1
        hudPosY = -1
        raidTimerPosX = -1
        raidTimerPosY = -1
        miraclePosX = -1
        miraclePosY = -1
        showRaidHud = true
        showMiracleHud = true
        save()
    }

    // --- Persistence ---

    private val SAVE_PATH: Path = FabricLoader.getInstance()
        .configDir.resolve("hunterboard_config.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load() {
        try {
            val file = SAVE_PATH.toFile()
            if (file.exists()) {
                val data = json.decodeFromString<ConfigData>(file.readText())
                hudColorR = data.hudColorR.coerceIn(0, 255)
                hudColorG = data.hudColorG.coerceIn(0, 255)
                hudColorB = data.hudColorB.coerceIn(0, 255)
                hudOpacity = data.hudOpacity.coerceIn(0, 100)
                rank = data.rank.coerceIn(0, 3)
                hudSizePreset = data.hudSizePreset.coerceIn(0, 3)
                hudPosX = data.hudPosX
                hudPosY = data.hudPosY
                raidTimerPosX = data.raidTimerPosX
                raidTimerPosY = data.raidTimerPosY
                miraclePosX = data.miraclePosX
                miraclePosY = data.miraclePosY
                showRaidHud = data.showRaidHud
                showMiracleHud = data.showMiracleHud
                HunterBoard.LOGGER.info("Loaded config: color=#${"%02X%02X%02X".format(hudColorR, hudColorG, hudColorB)}, opacity=$hudOpacity%, rank=${RANKS[rank].first}")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load config: ${e.message}")
        }
    }

    private fun save() {
        try {
            SAVE_PATH.parent.toFile().mkdirs()
            val data = ConfigData(hudColorR, hudColorG, hudColorB, hudOpacity, hudSizePreset, rank, hudPosX, hudPosY, raidTimerPosX, raidTimerPosY, miraclePosX, miraclePosY, showRaidHud, showMiracleHud)
            SAVE_PATH.toFile().writeText(json.encodeToString(ConfigData.serializer(), data))
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to save config: ${e.message}")
        }
    }

    @Serializable
    private data class ConfigData(
        val hudColorR: Int = 0xFF,
        val hudColorG: Int = 0xAA,
        val hudColorB: Int = 0x00,
        val hudOpacity: Int = 80,
        val hudSizePreset: Int = 1,
        val rank: Int = 3,
        val hudPosX: Int = -1,
        val hudPosY: Int = -1,
        val raidTimerPosX: Int = -1,
        val raidTimerPosY: Int = -1,
        val miraclePosX: Int = -1,
        val miraclePosY: Int = -1,
        val showRaidHud: Boolean = true,
        val showMiracleHud: Boolean = true
    )
}
