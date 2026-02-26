package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.CobblemonClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import java.util.UUID

/**
 * Detects hunt completions by requiring BOTH signals:
 *   1. A catch event (new Pokémon in party OR PC transfer chat message)
 *   2. A "Chasse complétée" / "Hunt completed" chat message
 *
 * Since the two signals can arrive in either order with a slight delay,
 * each signal is stored as "pending" and waits for the other to confirm.
 */
object CatchDetector {

    // Party UUID tracking to detect new arrivals
    private var knownUuids: MutableSet<UUID> = mutableSetOf()
    private var initialized = false
    private var tickSkip = 0

    // Pending data expires after this duration
    private const val PENDING_EXPIRY_MS = 15_000L

    // Pending catch: we detected a Pokémon was caught but haven't seen the completion message yet
    // Stores (speciesId, ballId?, timestamp)
    private data class PendingCatch(val speciesId: String, val ballId: String?, val time: Long)
    private var pendingCatch: PendingCatch? = null

    // Pending completion: we saw "Chasse complétée" but haven't detected the catch yet
    private var pendingCompletionTime: Long? = null

    // Cooldown to prevent double-triggers
    private var lastMatchTime: Long = 0L
    private const val MATCH_COOLDOWN_MS = 3_000L

    // "Your party was full! Pikachu has been added to Your PC."
    private val PC_EN_REGEX = Regex("""Your (?:party|team) (?:was|is) full! (.+) (?:has been |was )added to [Yy]our PC\.""")
    // "Votre équipe est pleine ! Crabicoque a été ajouté(e) à votre PC."
    private val PC_FR_REGEX = Regex("""Votre équipe est pleine ! (.+) a été ajouté(?:e)? à votre PC\.""")

    fun register() {
        // Signal 1a: Party monitoring — detects new Pokémon in party
        ClientTickEvents.END_CLIENT_TICK.register tickListener@{ client ->
            if (client.world == null || !BoardState.hasTargets()) return@tickListener
            if (BoardState.remainingCount() == 0) return@tickListener
            if (++tickSkip < 10) return@tickListener
            tickSkip = 0

            try {
                val party = CobblemonClient.storage.party
                val tempUuids = mutableSetOf<UUID>()

                for (pokemon in party) {
                    if (pokemon == null) continue
                    tempUuids.add(pokemon.uuid)

                    if (!initialized) continue
                    if (pokemon.uuid in knownUuids) continue

                    // New Pokémon detected in party
                    val speciesId = pokemon.species.resourceIdentifier.path
                    val ballId = normalizeBallId(pokemon.caughtBall.name.toString())
                    HunterBoard.LOGGER.info("CatchDetector: new party member — $speciesId ($ballId)")

                    onCatchDetected(speciesId, ballId)
                }

                knownUuids.clear()
                knownUuids.addAll(tempUuids)
                initialized = true
            } catch (_: Exception) {}
        }

        // Signal 1b (PC transfer) + Signal 2 (hunt completion): Chat messages
        ClientReceiveMessageEvents.GAME.register listener@{ message, isOverlay ->
            if (isOverlay) return@listener
            if (!BoardState.hasTargets()) return@listener

            val text = message.string

            // PC transfer: party was full, Pokémon sent to PC
            val pcMatch = PC_FR_REGEX.find(text) ?: PC_EN_REGEX.find(text)
            if (pcMatch != null) {
                val pokemonName = pcMatch.groupValues[1]
                HunterBoard.LOGGER.info("CatchDetector: PC transfer detected — $pokemonName")

                val speciesId = findSpeciesIdByName(pokemonName)
                if (speciesId != null) {
                    onCatchDetected(speciesId, null)
                } else {
                    HunterBoard.LOGGER.warn("CatchDetector: PC transfer '$pokemonName' — species not resolved, ignored")
                }
                return@listener
            }

            // Hunt completion message
            if (text.contains("Chasse complétée") || text.contains("Hunt completed")) {
                onCompletionMessage()
            }
        }

        HunterBoard.LOGGER.info("CatchDetector registered (dual-signal: catch + completion)")
    }

    /**
     * Called when we detect a Pokémon was caught (party or PC).
     * If a completion message is already pending, validate immediately.
     * Otherwise, store as pending catch and wait for completion message.
     */
    private fun onCatchDetected(speciesId: String, ballId: String?) {
        val now = System.currentTimeMillis()

        // Check if this species is even a hunt target
        val uncaught = BoardState.targets.take(ModConfig.maxHunts()).filter { !it.isCaught }
        val candidates = uncaught.filter { it.speciesId == speciesId }
        if (candidates.isEmpty()) {
            HunterBoard.LOGGER.info("CatchDetector: $speciesId is not a hunt target, ignoring")
            return
        }

        // Check if a completion message is already pending (arrived first)
        val completionTime = pendingCompletionTime
        if (completionTime != null && now - completionTime < PENDING_EXPIRY_MS) {
            pendingCompletionTime = null
            confirmMatch(speciesId, ballId, "catch+pending-completion")
            return
        }

        // Store as pending catch, wait for the completion message
        pendingCatch = PendingCatch(speciesId, ballId, now)
        HunterBoard.LOGGER.info("CatchDetector: catch pending — $speciesId (waiting for completion message)")
    }

    /**
     * Called when we receive "Chasse complétée" / "Hunt completed".
     * If a catch is already pending, validate immediately.
     * Otherwise, store as pending completion and wait for catch detection.
     */
    private fun onCompletionMessage() {
        val now = System.currentTimeMillis()

        // Cooldown check
        if (now - lastMatchTime < MATCH_COOLDOWN_MS) {
            HunterBoard.LOGGER.info("CatchDetector: completion skipped — already matched ${now - lastMatchTime}ms ago")
            return
        }

        // Check if a catch is already pending (arrived first)
        val catch = pendingCatch
        if (catch != null && now - catch.time < PENDING_EXPIRY_MS) {
            pendingCatch = null
            confirmMatch(catch.speciesId, catch.ballId, "completion+pending-catch")
            return
        }

        // No pending catch — store completion as pending, wait for catch detection
        // Also handle single-target fallback: if only 1 uncaught target, confirm immediately
        val uncaught = BoardState.targets.take(ModConfig.maxHunts()).filter { !it.isCaught }
        if (uncaught.size == 1) {
            val target = uncaught.first()
            BoardState.markTargetCaught(target.speciesId, target.ballId)
            lastMatchTime = now
            HunterBoard.LOGGER.info("CatchDetector: caught via single-target fallback — ${target.speciesId}")
            return
        }

        pendingCompletionTime = now
        HunterBoard.LOGGER.info("CatchDetector: completion pending — waiting for catch detection")
    }

    /**
     * Both signals confirmed — actually mark the target as caught.
     */
    private fun confirmMatch(speciesId: String, ballId: String?, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastMatchTime < MATCH_COOLDOWN_MS) {
            HunterBoard.LOGGER.info("CatchDetector: confirmMatch skipped — cooldown (${now - lastMatchTime}ms)")
            return
        }

        val uncaught = BoardState.targets.take(ModConfig.maxHunts()).filter { !it.isCaught }
        val candidates = uncaught.filter { it.speciesId == speciesId }
        if (candidates.isEmpty()) {
            HunterBoard.LOGGER.warn("CatchDetector: $speciesId no longer in uncaught targets")
            return
        }

        val target = if (ballId != null) {
            candidates.find { normalizeBallId(it.ballId) == ballId } ?: candidates.first()
        } else {
            candidates.first()
        }

        BoardState.markTargetCaught(target.speciesId, target.ballId)
        lastMatchTime = now
        HunterBoard.LOGGER.info("CatchDetector: CONFIRMED via $source — $speciesId (ball: ${ballId ?: "unknown"})")
    }

    /** Strips namespace prefix: "cobblemon:fast_ball" → "fast_ball" */
    private fun normalizeBallId(id: String): String = id.substringAfterLast(":")

    /** Resolves a localized Pokémon name (e.g. "Crabicoque") to its species ID (e.g. "crabrawler") */
    private fun findSpeciesIdByName(localizedName: String): String? {
        return try {
            PokemonSpecies.implemented.find { species ->
                species.translatedName.string.equals(localizedName, ignoreCase = true)
            }?.resourceIdentifier?.path
        } catch (_: Exception) { null }
    }
}
