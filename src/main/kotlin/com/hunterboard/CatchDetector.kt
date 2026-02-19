package com.hunterboard

import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.CobblemonClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import java.util.UUID

object CatchDetector {

    // Party UUID tracking to detect new arrivals
    private var knownUuids: MutableSet<UUID> = mutableSetOf()
    private var initialized = false
    private var tickSkip = 0

    // Pending catch detected via party monitoring (speciesId, normalizedBallId, timestamp)
    private var pendingParty: Triple<String, String, Long>? = null

    // Pending catch detected via PC transfer chat message (pokemonName, timestamp)
    private var pendingPcName: Pair<String, Long>? = null

    // Pending data expires after 30 seconds
    private const val PENDING_EXPIRY_MS = 30_000L

    // "Your team is full! Pikachu was added to your PC."
    private val PC_EN_REGEX = Regex("""Your team is full! (.+) was added to your PC\.""")
    // "Votre équipe est pleine ! Crabicoque a été ajouté(e) à votre PC."
    private val PC_FR_REGEX = Regex("""Votre équipe est pleine ! (.+) a été ajouté(?:e)? à votre PC\.""")

    fun register() {
        // Method 1: Party monitoring — detects new Pokémon when party isn't full
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

                    // New Pokémon detected in party — store as pending (wait for hunt completion message)
                    val speciesId = pokemon.species.resourceIdentifier.path
                    val ballId = normalizeBallId(pokemon.caughtBall.name.toString())
                    pendingParty = Triple(speciesId, ballId, System.currentTimeMillis())
                    HunterBoard.LOGGER.info("CatchDetector: new party member — $speciesId ($ballId)")
                }

                knownUuids.clear()
                knownUuids.addAll(tempUuids)
                initialized = true
            } catch (_: Exception) {}
        }

        // Method 2: Chat messages
        ClientReceiveMessageEvents.GAME.register listener@{ message, isOverlay ->
            if (isOverlay) return@listener
            if (!BoardState.hasTargets()) return@listener

            val text = message.string

            // PC transfer: party was full, Pokémon sent to PC
            val pcMatch = PC_FR_REGEX.find(text) ?: PC_EN_REGEX.find(text)
            if (pcMatch != null) {
                val pokemonName = pcMatch.groupValues[1]
                pendingPcName = Pair(pokemonName, System.currentTimeMillis())
                HunterBoard.LOGGER.info("CatchDetector: PC transfer detected — $pokemonName")
                return@listener
            }

            // Hunt completion — this is our trigger to mark the target
            if (text.contains("Chasse complétée") || text.contains("Hunt completed")) {
                handleHuntCompletion()
            }
        }

        HunterBoard.LOGGER.info("CatchDetector registered (party monitoring + chat)")
    }

    private fun handleHuntCompletion() {
        val uncaught = BoardState.targets.filter { !it.isCaught }
        if (uncaught.isEmpty()) return

        val now = System.currentTimeMillis()

        // Priority 1: PC transfer message — party was full, we know the Pokémon name
        val pcEntry = pendingPcName
        if (pcEntry != null && now - pcEntry.second < PENDING_EXPIRY_MS) {
            pendingPcName = null
            val speciesId = findSpeciesIdByName(pcEntry.first)
            if (speciesId != null) {
                val candidates = uncaught.filter { it.speciesId == speciesId }
                if (candidates.isNotEmpty()) {
                    val target = candidates.first()
                    BoardState.markTargetCaught(target.speciesId, target.ballId)
                    HunterBoard.LOGGER.info("CatchDetector: caught via PC path — $speciesId")
                    return
                }
            }
            // Name lookup failed — log and fall through to other methods
            HunterBoard.LOGGER.warn("CatchDetector: PC transfer '${pcEntry.first}' — species not found")
        }

        // Priority 2: Party monitoring — new Pokémon appeared in party
        val partyEntry = pendingParty
        if (partyEntry != null && now - partyEntry.third < PENDING_EXPIRY_MS) {
            pendingParty = null
            val (speciesId, normalizedBallId, _) = partyEntry
            val candidates = uncaught.filter { it.speciesId == speciesId }
            if (candidates.isNotEmpty()) {
                // Prefer ball-exact match, otherwise take first candidate
                val target = candidates.find { normalizeBallId(it.ballId) == normalizedBallId }
                    ?: candidates.first()
                BoardState.markTargetCaught(target.speciesId, target.ballId)
                HunterBoard.LOGGER.info("CatchDetector: caught via party path — $speciesId ($normalizedBallId)")
                return
            }
        }

        // Priority 3: Last resort — only one uncaught target remaining
        if (uncaught.size == 1) {
            val target = uncaught.first()
            BoardState.markTargetCaught(target.speciesId, target.ballId)
            HunterBoard.LOGGER.info("CatchDetector: caught via single-target fallback — ${target.speciesId}")
        } else {
            HunterBoard.LOGGER.warn("CatchDetector: hunt complete but could not identify target (${uncaught.size} uncaught)")
        }
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
