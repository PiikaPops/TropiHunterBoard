package com.hunterboard

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screen.Screen
import java.lang.reflect.Field

/**
 * Detects when the HunterBoardScreen from tropimodclient is opened
 * and extracts Pokemon hunt data via reflection.
 *
 * Data path:
 *   HunterBoardScreen
 *     → children (List of HunterBoardPokemonWidget)
 *       → pokemon (HunterBoardPokemon)
 *         → pokemon (com.cobblemon.mod.common.pokemon.Pokemon) → species.name
 *         → pokeball (PokeBallItem) → e.g. "cobblemon:fast_ball"
 *         → captured (boolean)
 *         → playerReward (int)
 *         → tier (HuntTier)
 */
object BoardDetector {

    private const val HUNTER_BOARD_CLASS = "fr.erusel.tropimodclient.client.gui.hunt.HunterBoardScreen"
    private const val POKEMON_WIDGET_CLASS = "fr.erusel.tropimodclient.client.gui.hunt.widgets.HunterBoardPokemonWidget"

    private var tickCounter = 0
    private var alreadyParsed = false

    fun register() {
        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            val className = screen.javaClass.name

            if (className == HUNTER_BOARD_CLASS || className.contains("HunterBoard")) {
                HunterBoard.LOGGER.info("HunterBoardScreen detected!")
                tickCounter = 0
                alreadyParsed = false

                ScreenEvents.afterTick(screen).register { scr ->
                    tickCounter++
                    // Parse after 10 ticks (let data load)
                    if (tickCounter == 10 && !alreadyParsed) {
                        alreadyParsed = true
                        parseHuntData(scr)
                    }
                }
            }
        }
        HunterBoard.LOGGER.info("Board detector registered")
    }

    private fun parseHuntData(screen: Screen) {
        try {
            val targets = mutableListOf<HuntTarget>()

            // Get the children list from screen (field_22786 = Screen.children)
            // which contains HunterBoardPokemonWidget instances
            val childrenField = findFieldInHierarchy(screen.javaClass, "field_22786")
                ?: findFieldInHierarchy(screen.javaClass, "children")

            val widgets: List<*>? = if (childrenField != null) {
                childrenField.isAccessible = true
                childrenField.get(screen) as? List<*>
            } else {
                // Fallback: try to get drawable children
                try {
                    val method = screen.javaClass.getMethod("children")
                    method.invoke(screen) as? List<*>
                } catch (e: Exception) { null }
            }

            if (widgets == null) {
                HunterBoard.LOGGER.warn("Could not find children list")
                return
            }

            for (widget in widgets) {
                if (widget == null) continue
                val widgetClass = widget.javaClass.name
                if (widgetClass != POKEMON_WIDGET_CLASS) continue

                // Get the "pokemon" field (HunterBoardPokemon) from the widget
                val huntPokemonField = widget.javaClass.getDeclaredField("pokemon")
                huntPokemonField.isAccessible = true
                val huntPokemon = huntPokemonField.get(widget) ?: continue

                // Extract data from HunterBoardPokemon
                val target = extractTarget(huntPokemon)
                if (target != null) {
                    targets.add(target)
                }
            }

            if (targets.isNotEmpty()) {
                BoardState.updateBoard(targets)
                SpawnData.ensureLoaded()
                HunterBoard.LOGGER.info("Parsed ${targets.size} hunt targets:")
                for (t in targets) {
                    val status = if (t.isCaught) "CAUGHT" else "needed"
                    HunterBoard.LOGGER.info("  ${t.pokemonName} [${t.requiredBall}] ${t.reward}$ - $status")
                }
            } else {
                HunterBoard.LOGGER.warn("No targets found in HunterBoardScreen")
            }

        } catch (e: Exception) {
            HunterBoard.LOGGER.error("Error parsing hunt data: ${e.message}", e)
        }
    }

    /**
     * Extract a HuntTarget from a HunterBoardPokemon object via reflection.
     *
     * Fields:
     *   pokemon: com.cobblemon.mod.common.pokemon.Pokemon
     *   pokeball: PokeBallItem (toString = "cobblemon:fast_ball")
     *   captured: boolean
     *   playerReward: int
     *   tier: HuntTier enum
     */
    private fun extractTarget(huntPokemon: Any): HuntTarget? {
        try {
            val clazz = huntPokemon.javaClass
            var pokemonName = ""
            var speciesId = ""
            var aspects = emptySet<String>()
            var ballName = ""
            var ballId = ""
            var captured = false
            var reward = 0
            var tier = ""

            for (field in clazz.declaredFields) {
                field.isAccessible = true
                val value = field.get(huntPokemon) ?: continue
                val name = field.name

                when (name) {
                    "pokemon" -> {
                        // Cast directly to Cobblemon's Pokemon class
                        val cobblemonPokemon = value as? com.cobblemon.mod.common.pokemon.Pokemon
                        if (cobblemonPokemon != null) {
                            speciesId = cobblemonPokemon.species.resourceIdentifier.path
                            pokemonName = speciesId.replaceFirstChar { it.uppercase() }
                            aspects = cobblemonPokemon.aspects
                        }
                    }
                    "pokeball" -> {
                        ballName = extractBallName(value)
                        ballId = extractBallId(value)
                    }
                    "captured" -> {
                        if (value is Boolean) captured = value
                    }
                    "playerReward" -> {
                        if (value is Number) reward = value.toInt()
                    }
                    "tier" -> {
                        if (value is Enum<*>) tier = value.name
                    }
                }
            }

            if (pokemonName.isEmpty()) return null

            return HuntTarget(
                pokemonName = pokemonName,
                speciesId = speciesId,
                aspects = aspects,
                requiredBall = ballName.ifEmpty { "?" },
                ballId = ballId,
                isCaught = captured,
                reward = reward,
                tier = tier
            )
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("Error extracting target: ${e.message}")
            return null
        }
    }

    /**
     * Extract a clean ball name from a PokeBallItem.
     * The item's registry ID is like "cobblemon:fast_ball"
     */
    private fun extractBallName(pokeball: Any): String {
        try {
            // Try to get registry ID
            val itemId = try {
                val item = pokeball as? net.minecraft.item.Item
                if (item != null) {
                    net.minecraft.registry.Registries.ITEM.getId(item).toString()
                } else {
                    pokeball.toString()
                }
            } catch (e: Exception) {
                pokeball.toString()
            }

            // itemId is like "cobblemon:fast_ball" or "cobblemon:poke_ball"
            val ballPart = itemId.substringAfterLast(":").removeSuffix("_ball")
            if (ballPart.isEmpty()) return itemId

            // Convert "fast" -> "Fast Ball", "ultra" -> "Ultra Ball"
            return ballPart.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } + " Ball"
        } catch (e: Exception) {
            return pokeball.toString()
        }
    }

    private fun extractBallId(pokeball: Any): String {
        return try {
            val item = pokeball as? net.minecraft.item.Item
            if (item != null) {
                net.minecraft.registry.Registries.ITEM.getId(item).toString()
            } else ""
        } catch (e: Exception) { "" }
    }

    private fun findFieldInHierarchy(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Object::class.java) {
            try {
                return current.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
