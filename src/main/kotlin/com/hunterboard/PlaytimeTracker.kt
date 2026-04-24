package com.hunterboard

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/**
 * Tracks uninterrupted session playtime on TropiMod.
 * Uses lastSeenTime (updated every tick) instead of DISCONNECT events, because on proxy
 * networks JOIN can fire before DISCONNECT, making disconnectTime always 0 at JOIN time.
 * A real disconnection resets the timer only after GRACE_MS of inactivity.
 */
object PlaytimeTracker {

    private var sessionStart: Long = 0L
    private var lastSeenTime: Long = 0L
    private var active = false

    private const val GRACE_MS = 10_000L

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            val now = System.currentTimeMillis()
            if (!active) {
                sessionStart = now
                active = true
            } else if (lastSeenTime > 0L && now - lastSeenTime <= GRACE_MS) {
                // Server switch — last tick was recent, keep timer going
            } else {
                // Real disconnection (was offline > GRACE_MS) → new session
                sessionStart = now
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (active && client.player != null) {
                lastSeenTime = System.currentTimeMillis()
            }
        }
    }

    /** Elapsed session time in milliseconds, or 0 if not active. */
    fun elapsedMs(): Long {
        if (!active || sessionStart == 0L) return 0L
        return System.currentTimeMillis() - sessionStart
    }

    /** Formatted as HH:MM:SS or MM:SS if under one hour. */
    fun formatted(): String {
        val total = elapsedMs() / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    fun reset() {
        sessionStart = 0L
        lastSeenTime = 0L
        active = false
    }
}
