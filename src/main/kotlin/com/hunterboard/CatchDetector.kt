package com.hunterboard

import com.cobblemon.mod.common.client.CobblemonClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import java.util.UUID

object CatchDetector {

    private var knownUuids: MutableSet<UUID> = mutableSetOf()
    private var initialized = false

    private val rewardRegex = Regex("""\+(\d+)""")

    fun register() {
        // Method 1: Party monitoring (works in solo)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.world == null || !BoardState.hasTargets()) return@register
            if (BoardState.targets.all { it.isCaught }) return@register

            try {
                val party = CobblemonClient.storage.party
                val currentUuids = mutableSetOf<UUID>()

                for (pokemon in party) {
                    if (pokemon == null) continue
                    currentUuids.add(pokemon.uuid)

                    if (!initialized) continue
                    if (pokemon.uuid in knownUuids) continue

                    // New Pokemon detected in party
                    val speciesId = pokemon.species.resourceIdentifier.path
                    val ballId = pokemon.caughtBall.name.toString()

                    for (target in BoardState.targets) {
                        if (target.isCaught) continue
                        if (target.speciesId == speciesId && target.ballId == ballId) {
                            BoardState.markTargetCaught(speciesId, ballId)
                            HunterBoard.LOGGER.info("Party-validated: ${target.pokemonName}")
                            break
                        }
                    }
                }

                knownUuids = currentUuids
                initialized = true
            } catch (_: Exception) {}
        }

        // Method 2: Chat message detection (works on servers)
        ClientReceiveMessageEvents.GAME.register listener@{ message, isOverlay ->
            if (isOverlay) return@listener
            if (!BoardState.hasTargets()) return@listener

            val text = message.string
            if (text.startsWith("Chasse complétée")) {
                handleChatValidation(text)
            }
        }

        HunterBoard.LOGGER.info("Catch detector registered (party + chat)")
    }

    private fun handleChatValidation(text: String) {
        try {
            // Extract first reward amount: "Chasse complétée (+500$ et +200$ pour ta ville)"
            val matches = rewardRegex.findAll(text).toList()
            val playerReward = matches.firstOrNull()?.groupValues?.get(1)?.toIntOrNull()

            val uncaught = BoardState.targets.filter { !it.isCaught }
            if (uncaught.isEmpty()) return

            if (playerReward != null) {
                // Only auto-validate if exactly one target matches the reward
                val candidates = uncaught.filter { it.reward == playerReward }
                if (candidates.size == 1) {
                    val target = candidates.first()
                    BoardState.markTargetCaught(target.speciesId, target.ballId)
                    HunterBoard.LOGGER.info("Chat-validated: ${target.pokemonName} (reward=$playerReward)")
                } else {
                    HunterBoard.LOGGER.info("Chat detected hunt completion (reward=$playerReward) but ${candidates.size} candidates - manual validation needed")
                }
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("Error in chat validation: ${e.message}")
        }
    }
}
