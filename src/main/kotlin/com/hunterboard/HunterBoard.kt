package com.hunterboard

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Environment(EnvType.CLIENT)
object HunterBoard : ClientModInitializer {
    const val MOD_ID = "hunterboard"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        LOGGER.info("HunterBoard initializing...")

        // Spawn pools, habitats, baits and biome tags all come from the server.
        // Drop them on disconnect so they cannot leak into the next world.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            SpawnData.invalidate()
            HabitatData.invalidate()
            HabitatStructures.invalidate()
            TmData.invalidate()
            BiomeTagResolver.clearCache()

            // Session state, as opposed to data caches: these describe the world just
            // left, and carrying them into the next one is wrong rather than merely
            // stale. BoardState is deliberately not among them — the hunting board is
            // saved to disk on purpose, so it survives restarts and world changes.
            CatchDetector.resetSession()
            ClearWarningOverlay.resetSession()
            // The rank read off the server describes the account on *that* network.
            // ModConfig.rank keeps the last detected value, which is what the HUD needs;
            // only the belief that it is current is dropped here.
            PlayerData.resetSession()
            // A battle interrupted by a disconnect has no end packet, so the half-built
            // replay is dropped rather than written out as if the match had finished.
            com.hunterboard.replay.ReplayRecorder.reset()
            BattleHuntOverlay.invalidateCache()
        }

        // Miracles are network-wide on Tropimon, which runs several servers behind one
        // proxy, so they must survive a hop between them and only be dropped on joining
        // somewhere else. That is a question about the server being joined, so it is
        // answered on JOIN rather than on DISCONNECT.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            MiracleOverlay.onJoin()
            // Hunts belong to an account. The session may not have been readable when the
            // board was first loaded at init, and account-switcher mods can change it while
            // the game runs; both are settled here, before the first frame of the world.
            BoardState.ensureAccount()
        }

        // Client-side commands for replay publishing (/hbreplay)
        com.hunterboard.replay.ReplayCommands.register()
        // Developer mode (JVM argument -Dhunterboard.dev=true): /hbdebug, the seasonal
        // surprises on any day. Without it the command is not registered at all.
        if (DevMode.enabled) {
            LOGGER.info("Developer mode on (-Dhunterboard.dev): /hbdebug registered")
            DebugCommands.register()
        }
        // Tropimon paintings that play a video, with its sound on a click
        VideoPaintings.register()

        // Wide locator bar across the top, fed by Tropimon's radar
        LocatorBar.register()

        // Christmas easter egg: Santa hats on every Pokémon, 20–25 December
        ChristmasHats.register()

        // Register mod sounds
        ModSounds.register()

        // Register keybindings (B to toggle HUD)
        KeyBindings.register()

        // Register board detection (listens for Hunting Board screens)
        BoardDetector.register()

        // Register PvP challenge screen detection
        PvpDetector.register()

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

        // Register battle hunt notification overlay
        BattleHuntOverlay.register()

        // Register PvP overlay (shows opponent team during /challenge battles)
        PvpOverlay.register()

        // Register radar enhancer (replaces TropiMod's PokeRadar screen)
        RadarEnhancer.register()

        // HunterBoard's PC: a "HunterBoard view" button in Cobblemon's PC
        com.hunterboard.pc.HunterPc.register()

        // Tropimon's habitat pools, from the relay (no longer shipped in the jar)
        HabitatFeed.register()

        // Filter raid chat messages
        ChatFilter.register()

        // Register auto-catch detection
        CatchDetector.register()

        // Capture filters: release the captures that fail theirs (/hbfilter)
        CaptureFilters.ensureLoaded()
        AutoRelease.register()

        // Raid card: the boss, its weaknesses and the countdown to the start
        RaidCard.register()

        // Register clear warning overlay
        ClearWarningOverlay.register()

        // Register playtime tracker and overlay
        PlaytimeTracker.register()
        PlaytimeOverlay.register()

        // April Fools prank
        AprilFoolsPrank.register()

        // Check for updates on Modrinth
        UpdateChecker.register()

        LOGGER.info("HunterBoard initialized!")
    }

}
