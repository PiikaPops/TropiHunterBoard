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

    // HUD size preset: 0=Small, 1=Normal, 2=Large, 3=Extra Large
    // hudSizePreset is the global preset used in merged mode
    var hudSizePreset: Int = 1
        private set
    // Independent size presets per HUD (used in non-merged mode)
    var huntSizePreset: Int = 1
        private set
    var raidSizePreset: Int = 1
        private set
    var miracleSizePreset: Int = 1
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

    // Full Clear mode (transparent background + no borders)
    var fullClearMode: Boolean = false
        private set

    // Grid layout (false = list, true = grid)
    var gridLayout: Boolean = false
        private set

    // Merged HUD mode (3 HUDs stacked in one panel)
    var mergedHudMode: Boolean = false
        private set

    // Use pixel art sprites instead of 3D models
    var usePixelArt: Boolean = false
        private set

    // Show/hide Hunt HUD independently
    var showHuntHud: Boolean = true
        private set

    // Auto-hide HUD when all hunts are completed
    var autoHideOnComplete: Boolean = true
        private set

    // Hide HUDs during Pokémon battles
    var hideHudInBattle: Boolean = true
        private set

    // Raid notification sounds
    var raidStartSound: String = "minecraft:block.bell.use"
        private set
    var raidWarningSound: String = "minecraft:block.bell.use"
        private set
    var raidNotification: Boolean = true
        private set
    var raidNotifVolume: Int = 100
        private set

    // Raid sound repeat (true = 5x, false = 1x)
    var raidSoundRepeat: Boolean = true
        private set

    // Miracle notification sounds
    var miracleNotification: Boolean = true
        private set
    var miracleSound: String = "minecraft:entity.player.levelup"
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

    /** Scale factor for merged HUD based on global size preset */
    fun hudScale(): Float = sizeToScale(hudSizePreset)

    /** Scale factor per individual HUD */
    fun raidScale(): Float = sizeToScale(raidSizePreset)
    fun miracleScale(): Float = sizeToScale(miracleSizePreset)

    private fun sizeToScale(preset: Int): Float = when (preset) {
        0 -> 0.80f; 1 -> 1.00f; 2 -> 1.30f; 3 -> 1.60f; else -> 1.00f
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
        // Sync individual presets when setting global
        huntSizePreset = hudSizePreset
        raidSizePreset = hudSizePreset
        miracleSizePreset = hudSizePreset
        save()
    }

    fun setHuntSizePreset(value: Int) {
        huntSizePreset = value.coerceIn(0, 3)
        save()
    }

    fun setRaidSizePreset(value: Int) {
        raidSizePreset = value.coerceIn(0, 3)
        save()
    }

    fun setMiracleSizePreset(value: Int) {
        miracleSizePreset = value.coerceIn(0, 3)
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

    fun toggleFullClear() {
        fullClearMode = !fullClearMode
        save()
    }

    fun toggleGridLayout() {
        gridLayout = !gridLayout
        save()
    }

    fun toggleMergedHud() {
        mergedHudMode = !mergedHudMode
        save()
    }

    fun togglePixelArt() {
        usePixelArt = !usePixelArt
        save()
    }

    fun toggleHuntHud() {
        showHuntHud = !showHuntHud
        save()
    }

    fun toggleAutoHideOnComplete() {
        autoHideOnComplete = !autoHideOnComplete
        save()
    }

    fun toggleHideHudInBattle() {
        hideHudInBattle = !hideHudInBattle
        save()
    }

    fun setRaidStartSound(soundId: String) {
        raidStartSound = soundId
        save()
    }

    fun setRaidWarningSound(soundId: String) {
        raidWarningSound = soundId
        save()
    }

    fun toggleMiracleNotification() {
        miracleNotification = !miracleNotification
        save()
    }

    fun setMiracleSound(soundId: String) {
        miracleSound = soundId
        save()
    }

    fun toggleRaidSoundRepeat() {
        raidSoundRepeat = !raidSoundRepeat
        save()
    }

    fun toggleRaidNotification() {
        raidNotification = !raidNotification
        save()
    }

    fun setRaidNotifVolume(value: Int) {
        raidNotifVolume = value.coerceIn(0, 100)
        save()
    }

    /** Notification volume as a float 0.0-1.0 */
    fun raidNotifVolumeF(): Float = raidNotifVolume / 100f

    fun resetToDefaults() {
        hudColorR = 0xFF
        hudColorG = 0xAA
        hudColorB = 0x00
        hudOpacity = 80
        hudSizePreset = 1
        huntSizePreset = 1
        raidSizePreset = 1
        miracleSizePreset = 1
        rank = 3
        hudPosX = -1
        hudPosY = -1
        raidTimerPosX = -1
        raidTimerPosY = -1
        miraclePosX = -1
        miraclePosY = -1
        showRaidHud = true
        showMiracleHud = true
        fullClearMode = false
        gridLayout = false
        mergedHudMode = false
        usePixelArt = false
        showHuntHud = true
        autoHideOnComplete = true
        raidStartSound = "minecraft:block.bell.use"
        raidWarningSound = "minecraft:block.bell.use"
        raidNotification = true
        raidNotifVolume = 100
        raidSoundRepeat = true
        miracleNotification = true
        miracleSound = "minecraft:entity.player.levelup"
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
                huntSizePreset = data.huntSizePreset.coerceIn(0, 3)
                raidSizePreset = data.raidSizePreset.coerceIn(0, 3)
                miracleSizePreset = data.miracleSizePreset.coerceIn(0, 3)
                hudPosX = data.hudPosX
                hudPosY = data.hudPosY
                raidTimerPosX = data.raidTimerPosX
                raidTimerPosY = data.raidTimerPosY
                miraclePosX = data.miraclePosX
                miraclePosY = data.miraclePosY
                showRaidHud = data.showRaidHud
                showMiracleHud = data.showMiracleHud
                fullClearMode = data.fullClearMode
                gridLayout = data.gridLayout
                mergedHudMode = data.mergedHudMode
                usePixelArt = data.usePixelArt
                showHuntHud = data.showHuntHud
                autoHideOnComplete = data.autoHideOnComplete
                raidStartSound = data.raidStartSound
                raidWarningSound = data.raidWarningSound
                raidNotification = data.raidNotification
                raidNotifVolume = data.raidNotifVolume.coerceIn(0, 100)
                raidSoundRepeat = data.raidSoundRepeat
                miracleNotification = data.miracleNotification
                miracleSound = data.miracleSound
                hideHudInBattle = data.hideHudInBattle
                HunterBoard.LOGGER.info("Loaded config: color=#${"%02X%02X%02X".format(hudColorR, hudColorG, hudColorB)}, opacity=$hudOpacity%, rank=${RANKS[rank].first}")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load config: ${e.message}")
        }
    }

    private fun save() {
        try {
            SAVE_PATH.parent.toFile().mkdirs()
            val data = ConfigData(
                hudColorR, hudColorG, hudColorB, hudOpacity, hudSizePreset,
                huntSizePreset, raidSizePreset, miracleSizePreset, rank,
                hudPosX, hudPosY, raidTimerPosX, raidTimerPosY, miraclePosX, miraclePosY,
                showRaidHud, showMiracleHud, fullClearMode, gridLayout, mergedHudMode, usePixelArt,
                showHuntHud, autoHideOnComplete,
                raidStartSound, raidWarningSound, raidNotification, raidNotifVolume, raidSoundRepeat,
                miracleNotification, miracleSound, hideHudInBattle
            )
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
        val huntSizePreset: Int = 1,
        val raidSizePreset: Int = 1,
        val miracleSizePreset: Int = 1,
        val rank: Int = 3,
        val hudPosX: Int = -1,
        val hudPosY: Int = -1,
        val raidTimerPosX: Int = -1,
        val raidTimerPosY: Int = -1,
        val miraclePosX: Int = -1,
        val miraclePosY: Int = -1,
        val showRaidHud: Boolean = true,
        val showMiracleHud: Boolean = true,
        val fullClearMode: Boolean = false,
        val gridLayout: Boolean = false,
        val mergedHudMode: Boolean = false,
        val usePixelArt: Boolean = false,
        val showHuntHud: Boolean = true,
        val autoHideOnComplete: Boolean = true,
        val raidStartSound: String = "minecraft:block.bell.use",
        val raidWarningSound: String = "minecraft:block.bell.use",
        val raidNotification: Boolean = true,
        val raidNotifVolume: Int = 100,
        val raidSoundRepeat: Boolean = true,
        val miracleNotification: Boolean = true,
        val miracleSound: String = "minecraft:entity.player.levelup",
        val hideHudInBattle: Boolean = true
    )
}
