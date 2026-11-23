package com.hunterboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
 * The hunting board of the account currently logged in.
 * Persisted per Minecraft account (hunts are nominative), so it survives restarts and
 * several accounts can share one instance without seeing each other's board.
 */
object BoardState {
    var targets: List<HuntTarget> = emptyList()
        private set
    var reward: String = ""
        private set
    var lastUpdated: Long = 0
        private set
    var hudVisible: Boolean = true
    var showBravo: Boolean = false
    var bravoStartTime: Long = 0L
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

    /**
     * Display preferences (mode, count) — a choice of the person at the keyboard, so shared
     * by every account. Before hunts were split per account this file also held the board,
     * and it is still where [load] finds one to migrate.
     */
    private val PREFS_PATH: Path = FabricLoader.getInstance()
        .configDir.resolve("hunterboard_data.json")

    /**
     * One board per Minecraft account. Hunts are nominative on Tropimon, so with several
     * accounts on the same instance a single shared file showed one account's board to the
     * other until it was reopened. Keyed by the account UUID, which unlike the name never
     * changes.
     */
    private val HUNTS_DIR: Path = FabricLoader.getInstance()
        .configDir.resolve("hunterboard_hunts")

    /** The account whose board is in memory, or null before one could be identified. */
    private var loadedAccount: String? = null

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun updateBoard(newTargets: List<HuntTarget>) {
        targets = newTargets
        waitingForBoard = false
        showBravo = false
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
        var matched = false
        val newTargets = targets.map { target ->
            if (!matched && !target.isCaught && target.speciesId == speciesId && target.ballId == ballId) {
                matched = true
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
            // Show bravo when all targets for the player's grade are caught
            if (ModConfig.autoHideOnComplete && targets.take(ModConfig.maxHunts()).all { it.isCaught }) {
                showBravo = true
                bravoStartTime = System.currentTimeMillis()
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
        // Show/hide bravo based on remaining count
        if (ModConfig.autoHideOnComplete) {
            if (targets.take(ModConfig.maxHunts()).all { it.isCaught }) {
                showBravo = true
                bravoStartTime = System.currentTimeMillis()
            } else {
                showBravo = false
            }
        }
    }

    fun resetBoard() {
        targets = emptyList()
        reward = ""
        waitingForBoard = true
        showBravo = false
        lastUpdated = System.currentTimeMillis()
        hudVisible = true
        save()
        HunterBoard.LOGGER.info("Board reset (weekly schedule)")
    }

    /**
     * Drops the board when it belongs to a past week.
     *
     * The board is saved to disk so it survives restarts, but Tropimon regenerates it every
     * Wednesday at 21:00 (see [HuntSchedule]) and only pushes the new one when the player
     * opens the in-game board. Without this the HUD kept showing the previous week's
     * targets — ticked ones still ticked — which is worse than showing nothing, because it
     * looks current.
     *
     * The test is a timestamp comparison rather than a scheduled callback, so it reaches the
     * same verdict whether the reset passed while the game was running or while it was
     * closed, and it cannot fire twice for the same reset: [resetBoard] stamps [lastUpdated]
     * with the current time, which is after the reset it just handled.
     *
     * @return true when a board was dropped.
     */
    fun checkWeeklyReset(): Boolean {
        if (targets.isEmpty()) return false
        // A board with no timestamp predates the field; leave it alone rather than throw
        // away data on a guess.
        if (lastUpdated <= 0L) return false
        if (lastUpdated >= HuntSchedule.lastResetMillis()) return false
        HunterBoard.LOGGER.info("Board from a previous week dropped (${targets.size} targets)")
        resetBoard()
        return true
    }

    fun hasTargets(): Boolean = targets.isNotEmpty()

    fun remainingCount(): Int = targets.count { !it.isCaught }

    /**
     * The logged-in account, as a file-safe key: its UUID, or for an offline account with
     * none, its name. Null while the session is not available yet — at mod init it may not
     * be, which is why [ensureAccount] runs again on every server join.
     */
    private fun currentAccount(): String? {
        val session = try { MinecraftClient.getInstance()?.session } catch (_: Exception) { null } ?: return null
        session.uuidOrNull?.let { return it.toString() }
        val name = session.username?.filter { it.isLetterOrDigit() || it == '_' }
        return if (name.isNullOrEmpty()) null else "name-${name.lowercase()}"
    }

    private fun huntsPath(account: String): Path = HUNTS_DIR.resolve("$account.json")

    /** Loads the shared display preferences, then the board of the current account. */
    fun load() {
        loadPrefs()
        ensureAccount()
    }

    /**
     * Makes sure the board in memory belongs to the account now logged in, and swaps it if
     * not. Cheap when nothing changed, so it can run on every join. Covers both a second
     * account on the same instance and account-switcher mods that change it mid-session.
     */
    fun ensureAccount() {
        val account = currentAccount() ?: return
        if (account == loadedAccount) return

        // Every change is saved as it happens, so the outgoing board is already on disk:
        // only the in-memory state needs clearing before the next account's is read.
        targets = emptyList()
        reward = ""
        lastUpdated = 0
        showBravo = false
        waitingForBoard = false
        hudVisible = true
        loadedAccount = account

        try {
            val file = huntsPath(account).toFile()
            val data: HuntsData? = if (file.exists()) {
                json.decodeFromString<HuntsData>(file.readText())
            } else {
                migrateLegacyBoard(account)
            }
            if (data != null) {
                targets = data.targets
                reward = data.reward
                lastUpdated = data.lastUpdated
            }
            HunterBoard.LOGGER.info("Loaded ${targets.size} hunt targets for account $account")
            // Before anything is derived from the board: the game may well have been
            // closed across a Wednesday 21:00, in which case what was just loaded is
            // last week's and must not reach the HUD at all.
            val expired = checkWeeklyReset()
            // Show bravo if all hunts were already completed when loading
            if (!expired && ModConfig.autoHideOnComplete && targets.isNotEmpty() &&
                targets.take(ModConfig.maxHunts()).all { it.isCaught }) {
                showBravo = true
                bravoStartTime = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load board data for $account: ${e.message}")
        }
    }

    private fun loadPrefs() {
        try {
            val file = PREFS_PATH.toFile()
            if (!file.exists()) return
            val data = json.decodeFromString<PrefsData>(file.readText())
            displayCount = data.displayCount
            displayMode = data.displayMode
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load board display settings: ${e.message}")
        }
    }

    /**
     * The board from before the per-account split, claimed by the first account that has
     * none of its own. Nothing in the old file says whose it was, so this is a guess — but a
     * cheap one to get wrong: opening the in-game board replaces it, and a board from a past
     * week is dropped on load anyway. The old file then keeps only the display settings, so
     * the board is claimed once and never copied to a second account.
     */
    private fun migrateLegacyBoard(account: String): HuntsData? {
        val file = PREFS_PATH.toFile()
        if (!file.exists()) return null
        val legacy = try { json.decodeFromString<LegacyData>(file.readText()) } catch (_: Exception) { return null }
        if (legacy.targets.isEmpty()) return null

        val data = HuntsData(legacy.targets, legacy.reward, legacy.lastUpdated)
        writeAtomically(huntsPath(account), json.encodeToString(HuntsData.serializer(), data))
        writeAtomically(PREFS_PATH, json.encodeToString(PrefsData.serializer(), PrefsData(displayCount, displayMode)))
        HunterBoard.LOGGER.info("Moved the shared hunting board (${legacy.targets.size} targets) to account $account")
        return data
    }

    /**
     * Writes the board to the account it was loaded for — never to "whoever is logged in
     * now", which after an account switch would put one account's board in the other's file.
     */
    private fun save() {
        // A first save can come before the first join — a display setting changed on the
        // title screen. Resolve the account now if the session allows it.
        if (loadedAccount == null) ensureAccount()
        // Until an account is known, the old shared board has not been claimed, and writing
        // the shared file now would replace it with display settings only: the board lost
        // before anyone could inherit it. The change stays in memory until then.
        val account = loadedAccount ?: return
        try {
            writeAtomically(huntsPath(account), json.encodeToString(HuntsData.serializer(),
                HuntsData(targets, reward, lastUpdated)))
            writeAtomically(PREFS_PATH, json.encodeToString(PrefsData.serializer(), PrefsData(displayCount, displayMode)))
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to save board data: ${e.message}")
        }
    }

    /**
     * Same temp-then-move as the config. With two game instances open on two accounts, and
     * a crash or a kill possible mid-write, a file half-written in place would come back as
     * an unreadable board the next time.
     */
    private fun writeAtomically(target: Path, text: String) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** One account's board. */
    @Serializable
    private data class HuntsData(
        val targets: List<HuntTarget> = emptyList(),
        val reward: String = "",
        val lastUpdated: Long = 0
    )

    /** Shared display settings. */
    @Serializable
    private data class PrefsData(
        val displayCount: Int = 6,
        val displayMode: Int = 0
    )

    /** The pre-split file, read only to migrate its board. */
    @Serializable
    private data class LegacyData(
        val targets: List<HuntTarget> = emptyList(),
        val reward: String = "",
        val lastUpdated: Long = 0,
        val displayCount: Int = 6,
        val displayMode: Int = 0
    )
}
