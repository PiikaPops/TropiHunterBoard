package com.hunterboard

import net.minecraft.client.MinecraftClient

/**
 * Reads Tropimon's own view of the local player, off `tropimon:update_player_data_packet`.
 *
 * Today this serves one purpose: knowing the player's rank, which decides how many hunt
 * slots the board has. That used to be a setting the player had to find and keep in step
 * with their purchases, and a wrong value silently truncated or padded the hunt HUD.
 *
 * The payload is read reflectively, like [TropiNotifications]: the classes ship inside a
 * nested jar of a proprietary mod, so there is nothing to compile against. The chain is
 *
 *     UpdatePlayerDataPayload.getPlayerData()      → TropimonPlayerData
 *       .getRankManager()                          → PlayerRankManager
 *       .getMaxBoughtRank()                        → PlayerRank
 *       .getAditionalHunts()                       → 0..3   (sic, their spelling)
 *
 * and that last number *is* HunterBoard's rank index: Tropimon grants +1/+2/+3 hunts for
 * Super/Hyper/Master over a base of three, which is exactly how [ModConfig.RANKS] is laid
 * out. Staff ranks grant none, and `getMaxBoughtRank` reports the highest rank actually
 * bought, so a moderator who owns Master still gets six.
 */
object PlayerData {

    const val CHANNEL = "tropimon:update_player_data_packet"

    /** Rank index last read from the server, 0..3, or null if the packet has not arrived. */
    var detectedRank: Int? = null
        private set

    /** The server's own name for that rank, e.g. "MASTER", for the options screen. */
    var detectedRankName: String? = null
        private set

    /** Logged once, so a packet that never arrives can be told from one that cannot be read. */
    private var loggedFailure = false

    /**
     * Entry point for [com.hunterboard.mixin.CustomPayloadMixin]. Takes the payload as [Any]
     * so this file compiles without Tropimon on the classpath, and swallows everything: this
     * runs on the network thread inside a HEAD injection, where an exception would take the
     * connection down with it.
     */
    @JvmStatic
    fun handlePayload(payload: Any) {
        try {
            read(payload)
        } catch (t: Throwable) {
            if (!loggedFailure) {
                loggedFailure = true
                HunterBoard.LOGGER.warn(
                    "Could not read ${payload.javaClass.name}: ${t.javaClass.simpleName} ${t.message}"
                )
            }
        }
    }

    private fun call(target: Any, name: String): Any? =
        target.javaClass.getMethod(name).invoke(target)

    private fun read(payload: Any) {
        val playerData = call(payload, "getPlayerData") ?: return

        // Guard against data about somebody else. `update_player_data_packet` carries the
        // local player — another trainer's card travels on `open_profile_packet` — but the
        // cost of checking is one comparison. During login the player entity does not exist
        // yet while this packet may already have arrived, so an unknown uuid is accepted
        // rather than treated as a mismatch.
        val mine = MinecraftClient.getInstance()?.player?.uuid
        if (mine != null) {
            val subject = call(playerData, "getUuid")
            if (subject != null && subject != mine) return
        }

        val rank = call(call(playerData, "getRankManager") ?: return, "getMaxBoughtRank") ?: return
        val additionalHunts = call(rank, "getAditionalHunts") as? Int ?: return

        detectedRank = additionalHunts.coerceIn(0, 3)
        detectedRankName = (rank as? Enum<*>)?.name

        ModConfig.applyDetectedRank(detectedRank!!)
    }

    /**
     * Re-applies the last rank the server sent. Called when automatic detection is switched
     * back on, because the packet arrives once at login and will not repeat on demand.
     */
    fun reapplyRank() {
        detectedRank?.let { ModConfig.applyDetectedRank(it) }
    }

    /** Dropped on disconnect: the next server may know this player by a different rank. */
    fun resetSession() {
        detectedRank = null
        detectedRankName = null
    }
}
