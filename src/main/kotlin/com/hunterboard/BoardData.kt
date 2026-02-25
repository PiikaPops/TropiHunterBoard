package com.hunterboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/**
 * Represents a single Pokemon target on the Hunting Board
 */
@Serializable
data class HuntTarget(
    val pokemonName: String,
    val speciesId: String = "",
    val aspects: Set<String> = emptySet(),
    val requiredBall: String,
    val ballId: String = "",
    val isCaught: Boolean = false,
    val reward: Int = 0,
    val tier: String = ""
)

/**
 * Global state for the current Hunting Board data
 * Persists to disk so it survives game restarts
 */
object BoardState {
    var targets: List<HuntTarget> = emptyList()
        private set
    var reward: String = ""
        private set
    var lastUpdated: Long = 0
        private set
    var hudVisible: Boolean = true
    var displayCount: Int = 6
        private set
    var displayMode: Int = 0
        private set

    // Placeholder state after midnight reset (not persisted)
    var waitingForBoard: Boolean = false

    private val MODE_KEYS = arrayOf("Full", "Compact", "Minimal", "Icon", "Icon+")
    val MODE_LABELS: Array<String> get() = MODE_KEYS.map { Translations.tr(it) }.toTypedArray()

    fun setDisplayCount(count: Int) {
        displayCount = count.coerceIn(3, ModConfig.maxHunts())
        save()
    }

    fun setDisplayMode(mode: Int) {
        displayMode = mode.coerceIn(0, 4)
        save()
    }

    private val SAVE_PATH: Path = FabricLoader.getInstance()
        .configDir.resolve("hunterboard_data.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun updateBoard(newTargets: List<HuntTarget>) {
        targets = newTargets
        waitingForBoard = false
        val totalReward = newTargets.filter { !it.isCaught }.sumOf { it.reward }
        reward = "${totalReward}$"
        lastUpdated = System.currentTimeMillis()
        // Re-show HUD if auto-hide is on and there are uncaught targets
        if (ModConfig.autoHideOnComplete && newTargets.any { !it.isCaught }) {
            hudVisible = true
        }
        save()
        HunterBoard.LOGGER.info("Board updated: ${targets.size} targets (${targets.count { it.isCaught }} caught)")
    }

    fun markTargetCaught(speciesId: String, ballId: String) {
        val newTargets = targets.map { target ->
            if (!target.isCaught && target.speciesId == speciesId && target.ballId == ballId) {
                target.copy(isCaught = true)
            } else {
                target
            }
        }
        if (newTargets != targets) {
            targets = newTargets
            val totalReward = newTargets.filter { !it.isCaught }.sumOf { it.reward }
            reward = "${totalReward}$"
            save()
            HunterBoard.LOGGER.info("Auto-validated: $speciesId caught with $ballId")
            // Auto-hide HUD when all targets are caught
            if (ModConfig.autoHideOnComplete && remainingCount() == 0) {
                hudVisible = false
            }
        }
    }

    fun toggleTargetCaught(index: Int) {
        if (index !in targets.indices) return
        val target = targets[index]
        val newTargets = targets.toMutableList()
        newTargets[index] = target.copy(isCaught = !target.isCaught)
        targets = newTargets
        val totalReward = newTargets.filter { !it.isCaught }.sumOf { it.reward }
        reward = "${totalReward}$"
        lastUpdated = System.currentTimeMillis()
        save()
        val status = if (newTargets[index].isCaught) "caught" else "uncaught"
        HunterBoard.LOGGER.info("Manual toggle: ${target.pokemonName} -> $status")
        // Auto-hide/show based on remaining count
        if (ModConfig.autoHideOnComplete) {
            hudVisible = remainingCount() > 0
        }
    }

    fun resetBoard() {
        targets = emptyList()
        reward = ""
        waitingForBoard = true
        lastUpdated = System.currentTimeMillis()
        hudVisible = true
        save()
        HunterBoard.LOGGER.info("Board reset (midnight)")
    }

    fun hasTargets(): Boolean = targets.isNotEmpty()

    fun remainingCount(): Int = targets.count { !it.isCaught }

    fun load() {
        try {
            val file = SAVE_PATH.toFile()
            if (file.exists()) {
                val data = json.decodeFromString<SaveData>(file.readText())
                targets = data.targets
                reward = data.reward
                lastUpdated = data.lastUpdated
                displayCount = data.displayCount
                displayMode = data.displayMode
                HunterBoard.LOGGER.info("Loaded ${targets.size} hunt targets from disk")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load board data: ${e.message}")
        }
    }

    private fun save() {
        try {
            SAVE_PATH.parent.toFile().mkdirs()
            val data = SaveData(targets, reward, lastUpdated, displayCount, displayMode)
            SAVE_PATH.toFile().writeText(json.encodeToString(SaveData.serializer(), data))
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to save board data: ${e.message}")
        }
    }

    @Serializable
    private data class SaveData(
        val targets: List<HuntTarget> = emptyList(),
        val reward: String = "",
        val lastUpdated: Long = 0,
        val displayCount: Int = 6,
        val displayMode: Int = 0
    )
}
