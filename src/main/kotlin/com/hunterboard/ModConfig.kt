package com.hunterboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

    /**
     * Take [rank] from the server instead of from this screen.
     *
     * Tropimon pushes the real rank on `update_player_data_packet` (see [PlayerData]), so
     * the only reason this setting ever existed was that nothing read that packet. On by
     * default, and turned off the moment a rank is picked by hand: a player who corrects
     * the mod should not be overruled by the next packet.
     */
    var rankAuto: Boolean = true
        private set

    /** Show the time left before the weekly hunt reset on the hunt HUD footer. */
    var huntResetTimer: Boolean = true
        private set

    /**
     * Show Tropimon's capture card on one page — ability, IV quality and the six IVs
     * together — instead of two pages that swap at 30% of its duration. See [CaptureCard].
     */
    var combinedCaptureCard: Boolean = true
        private set

    // ---- Auto capture filter (see CaptureFilters and AutoRelease) ----------------------
    // The filters themselves live in their own file; these are the switches around them.

    /** Master switch. Harmless when on: without a filter for a species nothing happens. */
    var captureFilterEnabled: Boolean = true
        private set


    /** Seconds between the announcement in chat and the release, so it can be cancelled. */
    val CAPTURE_FILTER_DELAYS = listOf(0, 5, 10, 20, 30)
    var captureFilterDelay: Int = 10
        private set

    /** Test mode: say what would be released, release nothing. */
    var captureFilterSimulate: Boolean = false
        private set

    /** Also say so in chat when a capture passes its filter and is kept. */
    var captureFilterNotifyKept: Boolean = true
        private set
    /** One chat line per automatic release; off: releases happen silently. */
    var captureFilterAnnounce: Boolean = true
        private set

    /**
     * Language of Pokémon, move and ability names in the wiki: "" follows the game, "fr"
     * or "en" is the FR/ENG button's choice. It used to live only in memory, so the button
     * was back to the game's language at every launch.
     */
    var nameLanguage: String = ""
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

    // Hide caught targets in HUD
    var hideCaughtInHud: Boolean = false
        private set

    // Wiki: show 2D sprites in Pokémon list
    var wikiShowSprites: Boolean = false
        set(value) { field = value; save() }

    // Hide boss damage chat messages
    var hideBossDamageChat: Boolean = false
        private set

    // Use HunterBoard's custom radar screen instead of TropiMod's default
    var useHunterRadar: Boolean = true
        private set

    // HunterBoard's radar in the Tropimon frame: dark screen instead of the cyan one
    var radarDark: Boolean = true
        private set

    // "HunterBoard view" button in Cobblemon's PC (HunterBoard's PC screen)
    var hunterPc: Boolean = true
        private set

    // Download Tropimon's habitat pools from the relay (HabitatFeed)
    var habitatFeed: Boolean = true
        private set

    // Controller support for Tropimon's and Cobblemon's screens through Controlify
    var controllerCompat: Boolean = true
        private set

    // With Tropimon Better PC installed: still open Cobblemon's PC first (Better PC behind a button)
    var classicPcFirst: Boolean = true
        private set

    // Play a sound when the ground-clear countdown reaches 10 seconds
    var clearWarningSound: Boolean = true
        private set
    var clearWarningSoundId: String = "minecraft:entity.experience_orb.pickup"
        private set

    // Show session playtime on the HUD. Off by default: it is a niche readout, and the
    // toggle used to be unreachable, so it stayed on for everyone who never found it.
    var showPlaytime: Boolean = false

    /** How the wiki lays out spawn conditions: "table" (default) or "text". */
    var spawnDisplayMode: String = "table"
        private set

    /**
     * Largest effective GUI scale the mod's screens will use; 0 follows Minecraft.
     *
     * Minecraft's scale multiplies every pixel, so at scale 3 a 1080p screen leaves only
     * 640 GUI pixels across and the spawn table (about 700) ran off the edge. Capping it
     * draws the screens through a matrix instead of shrinking the panels, so nothing is
     * cut off and the text stays pixel-aligned. Never scales anything up.
     */
    var uiScaleCap: Int = 2
        private set

    // UI theme (see UiKit for palettes). Order = cycle order of the options button.
    val UI_THEMES = listOf("hunterboard", "cobblemon", "polaris", "rebutsurmer", "rhode", "niavarane", "tropimon")
    var uiTheme: String = "hunterboard"
        private set

    /**
     * The textured interface drawn in Cobblemon's own visual language (UiKit's MODERN
     * style). It replaces the look of every screen at once; turning it off brings back the
     * classic interface with whichever theme is selected above, untouched.
     */
    var modernUi: Boolean = true
        private set
    fun toggleModernUi() { modernUi = !modernUi; save() }

    // Show battle hunt notification HUD
    var showBattleHuntHud: Boolean = true
        private set

    // PvP Overlay: show opponent team during /challenge battles
    var pvpOverlayEnabled: Boolean = true
        private set

    // Record PvP battles as Showdown replays in .minecraft/hunterboard/replays
    var pvpReplayEnabled: Boolean = false
        private set


    // Replays start muted: a link opened from Discord that bursts into battle music is
    // closed rather than watched. Viewers keep the Music On/Off buttons in the player.
    var replayMuted: Boolean = true
        private set

    // --- Wide locator bar (Skyrim/Satisfactory style), fed by Tropimon's radar ---
    var locatorBarEnabled: Boolean = true
        private set
    // One uniform scale, so icons and caps are never distorted.
    var locatorBarScale: Float = 2f
        private set
    // Length as a multiple of the texture's native 182 px. The extra width is built from
    // the texture's own repeating columns rather than by stretching it.
    var locatorBarLength: Float = 1.5f
        private set
    var locatorBarY: Int = 6
        private set
    var locatorBarHideInMenus: Boolean = true
        private set
    // Hide Tropimon's narrow bar above the hotbar, so only one is shown.
    var locatorBarHideOriginal: Boolean = true
        private set
    // Keep the empty track on screen instead of letting it fade out with the radar.
    var locatorBarShowEmpty: Boolean = false
        private set
    /** Above the armour bar, over the hotbar, instead of at the top of the screen. */
    var locatorBarAboveArmor: Boolean = false
        private set

    /** HunterBoard's raid card: the boss, its weaknesses and the time left before it starts. */
    var raidCard: Boolean = true
        private set
    /** Where the raid card sits: 0 right middle, 1 top right, 2 top centre, 3 top left, 4 left middle. */
    var raidCardAnchor: Int = 0
        private set
    /** Where the raid card was dragged to in the HUD editor; -1 = not moved, the anchor above applies. */
    var raidCardX: Int = -1
        private set
    var raidCardY: Int = -1
        private set
    var raidCardScale: Float = 1f
        private set
    /** Report the raid boss seen here to the relay, and ask it when it is not in sight. */
    var raidShare: Boolean = true
        private set

    // PvP Overlay panel positions (-1 = auto)
    var pvpPlayerX: Int = -1
        private set
    var pvpPlayerY: Int = -1
        private set
    var pvpOpponentX: Int = -1
        private set
    var pvpOpponentY: Int = -1
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
    // ---- HUD scale -----------------------------------------------------------------
    // The four-step presets could not cover a high GUI scale, so each HUD now carries a
    // free factor set by dragging its corner. A value below zero means "never set", and
    // the old preset is used instead — that keeps existing configs looking the same.

    const val MIN_HUD_SCALE = 0.35f
    const val MAX_HUD_SCALE = 4.0f

    var mergedScaleValue: Float = -1f
    var huntScaleValue: Float = -1f
    var raidScaleValue: Float = -1f
    var miracleScaleValue: Float = -1f

    fun hudScale(): Float =
        if (mergedScaleValue > 0f) mergedScaleValue else sizeToScale(hudSizePreset)

    /** Scale factor per individual HUD */
    fun huntScale(): Float = when {
        mergedHudMode -> hudScale()
        huntScaleValue > 0f -> huntScaleValue
        else -> sizeToScale(huntSizePreset)
    }

    fun raidScale(): Float =
        if (raidScaleValue > 0f) raidScaleValue else sizeToScale(raidSizePreset)

    fun miracleScale(): Float =
        if (miracleScaleValue > 0f) miracleScaleValue else sizeToScale(miracleSizePreset)

    private fun clampScale(value: Float) = value.coerceIn(MIN_HUD_SCALE, MAX_HUD_SCALE)

    fun setHuntScale(value: Float) {
        if (mergedHudMode) mergedScaleValue = clampScale(value) else huntScaleValue = clampScale(value)
        save()
    }

    fun setRaidScale(value: Float) { raidScaleValue = clampScale(value); save() }
    fun setMiracleScale(value: Float) { miracleScaleValue = clampScale(value); save() }

    /** The four raw values, so a cancelled resize puts back exactly what was there. */
    fun rawHudScales(): FloatArray =
        floatArrayOf(mergedScaleValue, huntScaleValue, raidScaleValue, miracleScaleValue)

    fun restoreRawHudScales(values: FloatArray) {
        if (values.size != 4) return
        mergedScaleValue = values[0]
        huntScaleValue = values[1]
        raidScaleValue = values[2]
        miracleScaleValue = values[3]
        save()
    }

    /** Back to the preset-derived size. */
    fun resetHudScales() {
        mergedScaleValue = -1f
        huntScaleValue = -1f
        raidScaleValue = -1f
        miracleScaleValue = -1f
        save()
    }

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

    /** Picked by hand in the options screen, which also means: stop following the server. */
    fun setRank(value: Int) {
        rank = value.coerceIn(0, 3)
        rankAuto = false
        save()
    }

    fun setRankAuto(value: Boolean) {
        rankAuto = value
        save()
        // Turning it back on should take effect now, not at the next login, and the packet
        // has almost certainly already been and gone.
        if (value) PlayerData.reapplyRank()
    }

    /**
     * Applies the rank read off the server. Ignored while [rankAuto] is off, and silent
     * when nothing changes, so a packet that repeats on every money change does not
     * rewrite the config file each time.
     */
    fun applyDetectedRank(value: Int) {
        if (!rankAuto) return
        val clamped = value.coerceIn(0, 3)
        if (clamped == rank) return
        rank = clamped
        save()
        HunterBoard.LOGGER.info("Rank detected from the server: ${RANKS[rank].first} (${maxHunts()} hunts)")
    }

    fun toggleHuntResetTimer() {
        huntResetTimer = !huntResetTimer
        save()
    }

    fun toggleCombinedCaptureCard() {
        combinedCaptureCard = !combinedCaptureCard
        save()
    }

    fun toggleCaptureFilter() { captureFilterEnabled = !captureFilterEnabled; save() }
    fun toggleCaptureFilterSimulate() { captureFilterSimulate = !captureFilterSimulate; save() }
    fun toggleCaptureFilterNotifyKept() { captureFilterNotifyKept = !captureFilterNotifyKept; save() }
    fun toggleCaptureFilterAnnounce() { captureFilterAnnounce = !captureFilterAnnounce; save() }

    /** Steps through [CAPTURE_FILTER_DELAYS]; [back] goes the other way. */
    fun cycleCaptureFilterDelay(back: Boolean = false) {
        val i = CAPTURE_FILTER_DELAYS.indexOf(captureFilterDelay).coerceAtLeast(0)
        val n = CAPTURE_FILTER_DELAYS.size
        captureFilterDelay = CAPTURE_FILTER_DELAYS[(i + if (back) n - 1 else 1) % n]
        save()
    }

    fun setNameLanguage(value: String) {
        nameLanguage = if (value == "fr" || value == "en") value else ""
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

    fun toggleHideCaughtInHud() {
        hideCaughtInHud = !hideCaughtInHud
        save()
    }

    fun toggleBattleHuntHud() {
        showBattleHuntHud = !showBattleHuntHud
        save()
    }

    fun togglePvpOverlay() {
        pvpOverlayEnabled = !pvpOverlayEnabled
        save()
    }

    fun togglePvpReplay() {
        pvpReplayEnabled = !pvpReplayEnabled
        save()
    }

    fun toggleLocatorBar() { locatorBarEnabled = !locatorBarEnabled; save() }
    fun toggleLocatorShowEmpty() { locatorBarShowEmpty = !locatorBarShowEmpty; save() }
    fun toggleLocatorHideOriginal() { locatorBarHideOriginal = !locatorBarHideOriginal; save() }
    fun toggleLocatorAboveArmor() { locatorBarAboveArmor = !locatorBarAboveArmor; save() }
    fun toggleRaidCard() { raidCard = !raidCard; save() }
    fun toggleRaidShare() { raidShare = !raidShare; save() }
    /** Choosing a preset spot drops a hand-picked position. */
    fun cycleRaidCardAnchor(back: Boolean = false) {
        if (raidCardX < 0) raidCardAnchor = (raidCardAnchor + (if (back) 4 else 1)) % 5
        raidCardX = -1; raidCardY = -1
        save()
    }
    fun setRaidCardPosition(x: Int, y: Int) { raidCardX = x; raidCardY = y; save() }
    fun setRaidCardScale(value: Float) { raidCardScale = value.coerceIn(0.5f, 3f); save() }
    fun toggleLocatorHideInMenus() { locatorBarHideInMenus = !locatorBarHideInMenus; save() }
    fun setLocatorBarScale(v: Float) { locatorBarScale = v.coerceIn(0.5f, 6f); save() }
    fun setLocatorBarLength(v: Float) { locatorBarLength = v.coerceIn(1f, 4f); save() }
    fun setLocatorBarY(v: Int) { locatorBarY = v.coerceIn(0, 200); save() }

    fun toggleReplayMuted() {
        replayMuted = !replayMuted
        save()
    }

    fun setPvpPlayerPosition(x: Int, y: Int) { pvpPlayerX = x; pvpPlayerY = y; save() }
    fun setPvpOpponentPosition(x: Int, y: Int) { pvpOpponentX = x; pvpOpponentY = y; save() }
    fun resetPvpPositions() { pvpPlayerX = -1; pvpPlayerY = -1; pvpOpponentX = -1; pvpOpponentY = -1; save() }


    fun toggleHideBossDamageChat() {
        hideBossDamageChat = !hideBossDamageChat
        save()
    }

    fun setUseHunterRadar(value: Boolean) {
        useHunterRadar = value
        save()
    }

    fun toggleUseHunterRadar() {
        useHunterRadar = !useHunterRadar
        save()
    }

    fun toggleRadarDark() {
        radarDark = !radarDark
        save()
    }

    fun toggleControllerCompat() {
        controllerCompat = !controllerCompat
        save()
    }

    fun toggleHabitatFeed() {
        habitatFeed = !habitatFeed
        save()
        HabitatFeed.optionChanged()
    }

    fun toggleHunterPc() {
        hunterPc = !hunterPc
        save()
    }

    fun toggleClassicPcFirst() {
        classicPcFirst = !classicPcFirst
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

    fun toggleClearWarningSound() {
        clearWarningSound = !clearWarningSound
        save()
    }

    fun setClearWarningSound(soundId: String) {
        clearWarningSoundId = soundId
        save()
    }

    fun toggleSpawnDisplayMode() {
        spawnDisplayMode = if (spawnDisplayMode == "table") "text" else "table"
        save()
    }

    /** Cycle the interface scale cap (order of UiScale.CAPS). */
    fun cycleUiScaleCap() {
        val idx = UiScale.CAPS.indexOf(uiScaleCap)
        uiScaleCap = UiScale.CAPS[(idx + 1) % UiScale.CAPS.size]
        save()
        // every open screen laid itself out with the old factor
        try {
            val client = net.minecraft.client.MinecraftClient.getInstance()
            client.currentScreen?.let { it.init(client, client.window.scaledWidth, client.window.scaledHeight) }
        } catch (_: Exception) {}
    }

    fun toggleShowPlaytime() {
        showPlaytime = !showPlaytime
        save()
    }

    /** Cycle to the next UI theme (order of UI_THEMES). */
    fun toggleUiTheme() {
        val idx = UI_THEMES.indexOf(uiTheme)
        uiTheme = UI_THEMES[(idx + 1) % UI_THEMES.size]
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
        hideCaughtInHud = false
        showBattleHuntHud = true
        hideBossDamageChat = false
        useHunterRadar = true
        hunterPc = true
        habitatFeed = true
        controllerCompat = true
        radarDark = true
        classicPcFirst = true
        wikiShowSprites = false
        pvpOverlayEnabled = true
        pvpReplayEnabled = false
        replayMuted = true
        locatorBarEnabled = true
        locatorBarScale = 2f
        locatorBarLength = 1.5f
        locatorBarY = 6
        locatorBarHideInMenus = true
        locatorBarHideOriginal = true
        locatorBarShowEmpty = false
        pvpPlayerX = -1; pvpPlayerY = -1
        pvpOpponentX = -1; pvpOpponentY = -1
        clearWarningSound = true
        clearWarningSoundId = "minecraft:entity.experience_orb.pickup"
        showPlaytime = false
        spawnDisplayMode = "table"
        uiScaleCap = 2
        mergedScaleValue = -1f
        huntScaleValue = -1f
        raidScaleValue = -1f
        miracleScaleValue = -1f
        rankAuto = true
        huntResetTimer = true
        combinedCaptureCard = true
        captureFilterEnabled = true
        captureFilterDelay = 10
        captureFilterSimulate = false
        captureFilterNotifyKept = true
        captureFilterAnnounce = true
        locatorBarAboveArmor = false
        raidCard = true
        raidShare = true
        raidCardAnchor = 0
        raidCardX = -1
        raidCardY = -1
        raidCardScale = 1f
        nameLanguage = ""
        uiTheme = "hunterboard"
        modernUi = true
        save()
        // The rank above was reset to its default, not to what the server says; picking the
        // detection back up puts the right one in immediately.
        PlayerData.reapplyRank()
    }

    // --- Persistence ---

    private val SAVE_PATH: Path = FabricLoader.getInstance()
        .configDir.resolve("hunterboard_config.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Set while the fields are being filled from a file. A setter that saves — the wiki's
     * sprite toggle does — used to write the config back half-loaded: every field after
     * it still held its default, the file was left like that, and after a session in
     * which no option was touched those settings were gone at the next launch.
     */
    private var applying = false

    /**
     * The file as it was last read or written. [save] compares the file on disk with it to
     * tell what another game running from the same folder changed in the meantime.
     */
    private var baseline: JsonObject? = null

    private val POSITION_KEYS = setOf(
        "hudPosX", "hudPosY", "raidTimerPosX", "raidTimerPosY", "miraclePosX", "miraclePosY"
    )

    fun load() {
        try {
            val file = SAVE_PATH.toFile()
            if (file.exists()) {
                val data = json.decodeFromString(ConfigData.serializer(), file.readText())
                apply(data)
                baseline = json.encodeToJsonElement(ConfigData.serializer(), data).jsonObject

                HunterBoard.LOGGER.info("Loaded config: color=#${"%02X%02X%02X".format(hudColorR, hudColorG, hudColorB)}, opacity=$hudOpacity%, rank=${RANKS[rank].first}")
            }
        } catch (e: Exception) {
            // Every setting is now at its default and the next save will overwrite the
            // file, so the only copy of what the player had chosen is kept aside first.
            // Losing settings silently is exactly the failure that is impossible to
            // diagnose afterwards.
            HunterBoard.LOGGER.error("Failed to load config, falling back to defaults: ${e.message}")
            try {
                val broken = SAVE_PATH.resolveSibling("${SAVE_PATH.fileName}.broken")
                Files.move(SAVE_PATH, broken, StandardCopyOption.REPLACE_EXISTING)
                HunterBoard.LOGGER.error("Unreadable config kept at $broken")
            } catch (_: Exception) {
                // Nothing further to try: the warning above is the record.
            }
        }
    }

    private fun apply(data: ConfigData) {
        applying = true
        try {
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
            hideCaughtInHud = data.hideCaughtInHud
            showBattleHuntHud = data.showBattleHuntHud
            hideBossDamageChat = data.hideBossDamageChat
            useHunterRadar = data.useHunterRadar
            hunterPc = data.hunterPc
            habitatFeed = data.habitatFeed
            controllerCompat = data.controllerCompat
            radarDark = data.radarDark
            classicPcFirst = data.classicPcFirst
            wikiShowSprites = data.wikiShowSprites
            pvpOverlayEnabled = data.pvpOverlayEnabled
            pvpPlayerX = data.pvpPlayerX; pvpPlayerY = data.pvpPlayerY
            pvpOpponentX = data.pvpOpponentX; pvpOpponentY = data.pvpOpponentY
            clearWarningSound = data.clearWarningSound
            clearWarningSoundId = data.clearWarningSoundId
            showPlaytime = data.showPlaytime
            spawnDisplayMode = if (data.spawnDisplayMode == "text") "text" else "table"
            uiScaleCap = if (data.uiScaleCap in UiScale.CAPS) data.uiScaleCap else 2
            mergedScaleValue = data.mergedScaleValue
            huntScaleValue = data.huntScaleValue
            raidScaleValue = data.raidScaleValue
            miracleScaleValue = data.miracleScaleValue
            pvpReplayEnabled = data.pvpReplayEnabled
            replayMuted = data.replayMuted
            locatorBarEnabled = data.locatorBarEnabled
            locatorBarScale = data.locatorBarScale.coerceIn(0.5f, 6f)
            locatorBarLength = data.locatorBarLength.coerceIn(1f, 4f)
            locatorBarY = data.locatorBarY.coerceIn(0, 200)
            locatorBarHideInMenus = data.locatorBarHideInMenus
            locatorBarHideOriginal = data.locatorBarHideOriginal
            locatorBarShowEmpty = data.locatorBarShowEmpty
            rankAuto = data.rankAuto
            huntResetTimer = data.huntResetTimer
            combinedCaptureCard = data.combinedCaptureCard
            uiTheme = if (data.uiTheme in UI_THEMES) data.uiTheme else "hunterboard"
            modernUi = data.modernUi
            captureFilterEnabled = data.captureFilterEnabled
            captureFilterDelay = if (data.captureFilterDelay in CAPTURE_FILTER_DELAYS) data.captureFilterDelay else 10
            captureFilterSimulate = data.captureFilterSimulate
            captureFilterNotifyKept = data.captureFilterNotifyKept
            captureFilterAnnounce = data.captureFilterAnnounce
            locatorBarAboveArmor = data.locatorBarAboveArmor
            raidCard = data.raidCard
            raidShare = data.raidShare
            raidCardAnchor = data.raidCardAnchor.coerceIn(0, 4)
            raidCardX = data.raidCardX
            raidCardY = data.raidCardY
            raidCardScale = data.raidCardScale.coerceIn(0.5f, 3f)
            nameLanguage = if (data.nameLanguage == "fr" || data.nameLanguage == "en") data.nameLanguage else ""
        } finally {
            applying = false
        }
    }

    /** Every setting, by name: a positional list of sixty arguments was one slip from a swap. */
    private fun snapshot() = ConfigData(
        hudColorR = hudColorR,
        hudColorG = hudColorG,
        hudColorB = hudColorB,
        hudOpacity = hudOpacity,
        hudSizePreset = hudSizePreset,
        huntSizePreset = huntSizePreset,
        raidSizePreset = raidSizePreset,
        miracleSizePreset = miracleSizePreset,
        rank = rank,
        hudPosX = hudPosX,
        hudPosY = hudPosY,
        raidTimerPosX = raidTimerPosX,
        raidTimerPosY = raidTimerPosY,
        miraclePosX = miraclePosX,
        miraclePosY = miraclePosY,
        showRaidHud = showRaidHud,
        showMiracleHud = showMiracleHud,
        fullClearMode = fullClearMode,
        gridLayout = gridLayout,
        mergedHudMode = mergedHudMode,
        usePixelArt = usePixelArt,
        showHuntHud = showHuntHud,
        autoHideOnComplete = autoHideOnComplete,
        raidStartSound = raidStartSound,
        raidWarningSound = raidWarningSound,
        raidNotification = raidNotification,
        raidNotifVolume = raidNotifVolume,
        raidSoundRepeat = raidSoundRepeat,
        miracleNotification = miracleNotification,
        miracleSound = miracleSound,
        hideHudInBattle = hideHudInBattle,
        hideCaughtInHud = hideCaughtInHud,
        showBattleHuntHud = showBattleHuntHud,
        hideBossDamageChat = hideBossDamageChat,
        useHunterRadar = useHunterRadar,
        hunterPc = hunterPc,
        habitatFeed = habitatFeed,
        controllerCompat = controllerCompat,
        radarDark = radarDark,
        classicPcFirst = classicPcFirst,
        wikiShowSprites = wikiShowSprites,
        pvpOverlayEnabled = pvpOverlayEnabled,
        pvpPlayerX = pvpPlayerX,
        pvpPlayerY = pvpPlayerY,
        pvpOpponentX = pvpOpponentX,
        pvpOpponentY = pvpOpponentY,
        clearWarningSound = clearWarningSound,
        clearWarningSoundId = clearWarningSoundId,
        showPlaytime = showPlaytime,
        uiTheme = uiTheme,
        modernUi = modernUi,
        spawnDisplayMode = spawnDisplayMode,
        uiScaleCap = uiScaleCap,
        mergedScaleValue = mergedScaleValue,
        huntScaleValue = huntScaleValue,
        raidScaleValue = raidScaleValue,
        miracleScaleValue = miracleScaleValue,
        pvpReplayEnabled = pvpReplayEnabled,
        replayMuted = replayMuted,
        locatorBarEnabled = locatorBarEnabled,
        locatorBarScale = locatorBarScale,
        locatorBarLength = locatorBarLength,
        locatorBarY = locatorBarY,
        locatorBarHideInMenus = locatorBarHideInMenus,
        locatorBarHideOriginal = locatorBarHideOriginal,
        locatorBarShowEmpty = locatorBarShowEmpty,
        rankAuto = rankAuto,
        huntResetTimer = huntResetTimer,
        combinedCaptureCard = combinedCaptureCard,
        captureFilterEnabled = captureFilterEnabled,
        captureFilterDelay = captureFilterDelay,
        captureFilterSimulate = captureFilterSimulate,
        captureFilterNotifyKept = captureFilterNotifyKept,
        captureFilterAnnounce = captureFilterAnnounce,
        locatorBarAboveArmor = locatorBarAboveArmor,
        raidCard = raidCard,
        raidShare = raidShare,
        raidCardAnchor = raidCardAnchor,
        raidCardX = raidCardX,
        raidCardY = raidCardY,
        raidCardScale = raidCardScale,
        nameLanguage = nameLanguage
    )

    /** The file on disk in the same normalised form as [baseline], or null if unreadable. */
    private fun readDisk(): JsonObject? = try {
        val file = SAVE_PATH.toFile()
        if (!file.exists()) null
        else json.encodeToJsonElement(
            ConfigData.serializer(), json.decodeFromString(ConfigData.serializer(), file.readText())
        ).jsonObject
    } catch (_: Exception) { null }

    private fun save() {
        if (applying) return
        try {
            SAVE_PATH.parent.toFile().mkdirs()
            val mine = json.encodeToJsonElement(ConfigData.serializer(), snapshot()).jsonObject
            var out = mine

            // Two games started from the same folder — two accounts on one instance — each
            // used to write all of its settings, so the last one to save reverted whatever
            // the other had changed. Now a setting only overwrites the file when it was
            // changed here; the others are taken from the file, and adopted.
            val base = baseline
            val disk = if (base != null) readDisk() else null
            if (base != null && disk != null && disk != base) {
                out = JsonObject(mine.mapValues { (key, value) ->
                    if (value != base[key]) value else disk[key] ?: value
                })
                if (out != mine) {
                    apply(json.decodeFromJsonElement(ConfigData.serializer(), out))
                    if (POSITION_KEYS.any { out[it] != mine[it] }) reloadOverlayPositions()
                    HunterBoard.LOGGER.info("Config: merged the settings another game saved in the meantime")
                }
            }

            // Written through a temporary file and then moved into place, so the config is
            // replaced in one step. Writing over it directly means a crash or a kill
            // mid-write leaves a truncated file, which [load] cannot parse — and a config
            // that fails to parse silently reverts every setting to its default and is
            // then overwritten by the next save, losing the lot.
            val tmp = SAVE_PATH.resolveSibling("${SAVE_PATH.fileName}.tmp")
            tmp.toFile().writeText(json.encodeToString(JsonObject.serializer(), out))
            try {
                Files.move(tmp, SAVE_PATH,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // Not every filesystem offers an atomic move; a plain replace still beats
                // writing in place, because the content is already complete on disk.
                Files.move(tmp, SAVE_PATH, StandardCopyOption.REPLACE_EXISTING)
            }
            baseline = out
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to save config: ${e.message}")
        }
    }

    private fun reloadOverlayPositions() {
        try {
            HuntOverlay.loadPosition()
            RaidTimerOverlay.loadPosition()
            MiracleOverlay.loadPosition()
        } catch (_: Exception) {}
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
        val hideHudInBattle: Boolean = true,
        val hideCaughtInHud: Boolean = false,
        val showBattleHuntHud: Boolean = true,
        val hideBossDamageChat: Boolean = false,
        val useHunterRadar: Boolean = true,
        val hunterPc: Boolean = true,
        val habitatFeed: Boolean = true,
        val controllerCompat: Boolean = true,
        val radarDark: Boolean = true,
        val classicPcFirst: Boolean = true,
        val wikiShowSprites: Boolean = false,
        val pvpOverlayEnabled: Boolean = true,
        val pvpPlayerX: Int = -1,
        val pvpPlayerY: Int = -1,
        val pvpOpponentX: Int = -1,
        val pvpOpponentY: Int = -1,
        val clearWarningSound: Boolean = true,
        val clearWarningSoundId: String = "minecraft:entity.experience_orb.pickup",
        val showPlaytime: Boolean = false,
        val uiTheme: String = "hunterboard",
        val modernUi: Boolean = true,
        val spawnDisplayMode: String = "table",
        val uiScaleCap: Int = 2,
        val mergedScaleValue: Float = -1f,
        val huntScaleValue: Float = -1f,
        val raidScaleValue: Float = -1f,
        val miracleScaleValue: Float = -1f,
        val pvpReplayEnabled: Boolean = false,
        val replayMuted: Boolean = true,
        val locatorBarEnabled: Boolean = true,
        val locatorBarScale: Float = 2f,
        val locatorBarLength: Float = 1.5f,
        val locatorBarY: Int = 6,
        val locatorBarHideInMenus: Boolean = true,
        val locatorBarHideOriginal: Boolean = true,
        val locatorBarShowEmpty: Boolean = false,
        val rankAuto: Boolean = true,
        val huntResetTimer: Boolean = true,
        val combinedCaptureCard: Boolean = true,
        val captureFilterEnabled: Boolean = true,
        val captureFilterDelay: Int = 10,
        val captureFilterSimulate: Boolean = false,
        val captureFilterNotifyKept: Boolean = true,
        val captureFilterAnnounce: Boolean = true,
        val locatorBarAboveArmor: Boolean = false,
        val raidCard: Boolean = true,
        val raidShare: Boolean = true,
        val raidCardAnchor: Int = 0,
        val raidCardX: Int = -1,
        val raidCardY: Int = -1,
        val raidCardScale: Float = 1f,
        val nameLanguage: String = ""
    )
}
