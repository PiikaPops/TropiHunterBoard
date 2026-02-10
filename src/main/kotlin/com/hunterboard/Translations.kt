package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.client.MinecraftClient
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier

object Translations {

    fun isFrench(): Boolean {
        return try {
            MinecraftClient.getInstance().options.language == "fr_fr"
        } catch (_: Exception) { false }
    }

    fun tr(key: String): String {
        if (!isFrench()) return key
        return FR[key] ?: key
    }

    fun pokemonName(speciesId: String): String {
        if (speciesId.isEmpty()) return speciesId
        return try {
            PokemonSpecies.getByName(speciesId)?.translatedName?.string
                ?: speciesId.replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            speciesId.replaceFirstChar { it.uppercase() }
        }
    }

    fun ballName(ballId: String): String {
        if (ballId.isEmpty()) return "?"
        return try {
            val stack = ItemStack(Registries.ITEM.get(Identifier.of(ballId)))
            if (!stack.isEmpty) stack.name.string else fallbackBallName(ballId)
        } catch (_: Exception) {
            fallbackBallName(ballId)
        }
    }

    private fun fallbackBallName(ballId: String): String {
        val ballPart = ballId.substringAfterLast(":").removeSuffix("_ball")
        if (ballPart.isEmpty()) return ballId
        return ballPart.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } + " Ball"
    }

    fun formatRarity(bucket: String): String {
        if (bucket.isEmpty()) return tr("Unknown")
        if (!isFrench()) {
            return bucket.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        return when (bucket.lowercase().trim()) {
            "common" -> "Commun"
            "uncommon" -> "Peu commun"
            "rare" -> "Rare"
            "ultra-rare" -> "Ultra-rare"
            else -> bucket.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    fun formatTime(time: String): String {
        if (!isFrench()) {
            return if (time == "any") "Any time" else time.replaceFirstChar { it.uppercase() }
        }
        return when (time.lowercase()) {
            "any" -> "Tout moment"
            "day" -> "Jour"
            "night" -> "Nuit"
            "dusk" -> "Crépuscule"
            "dawn" -> "Aube"
            "morning" -> "Matin"
            "afternoon" -> "Après-midi"
            "midnight" -> "Minuit"
            else -> time.replaceFirstChar { it.uppercase() }
        }
    }

    fun formatWeather(weather: String): String {
        if (!isFrench()) {
            return if (weather == "any") "Any weather" else weather.replaceFirstChar { it.uppercase() }
        }
        return when (weather.lowercase()) {
            "any" -> "Tout temps"
            "rain" -> "Pluie"
            "thunder" -> "Orage"
            "clear" -> "Dégagé"
            else -> weather.replaceFirstChar { it.uppercase() }
        }
    }

    fun formatSky(canSeeSky: Boolean?): String {
        return when (canSeeSky) {
            true -> if (isFrench()) " | Extérieur" else " | Outdoor"
            false -> if (isFrench()) " | Souterrain" else " | Underground"
            else -> ""
        }
    }

    private val FR = mapOf(
        // HuntOverlay
        "Hunting Board" to "Tableau de Chasse",

        // SpawnInfoScreen
        "✦ Spawn Info ✦" to "✦ Infos d'Apparition ✦",
        "Loading spawn data..." to "Chargement des données...",
        "No targets on board" to "Aucune cible sur le tableau",
        "Show:" to "Afficher :",
        "Mode:" to "Mode :",
        "Full" to "Complet",
        "Compact" to "Compact",
        "Minimal" to "Minimal",
        "Icon" to "Icône",
        "Error loading data" to "Erreur de chargement",
        "No spawn data" to "Aucune donnée d'apparition",
        "I / ESC to close  \u2022  Scroll to navigate" to "I / ESC pour fermer  \u2022  Défiler pour naviguer",
        "Any" to "Tous",

        // PokemonDetailScreen
        "\u2190 Back" to "\u2190 Retour",
        "Abilities" to "Talents",
        "EV Yield" to "Gain EV",
        "Base Stats" to "Stats de Base",
        "HP" to "PV",
        "Attack" to "Attaque",
        "Defence" to "Défense",
        "SpAtk" to "Atq.Spé",
        "SpDef" to "Déf.Spé",
        "Speed" to "Vitesse",
        "Total" to "Total",
        "Spawn Conditions" to "Conditions d'Apparition",
        "Rarity:" to "Rareté :",
        "Dropped Items" to "Objets Lâchés",
        "None" to "Aucun",
        "Qty:" to "Qté :",
        "Level-Up" to "Montée Nv",
        "TM" to "CT",
        "Egg" to "Œuf",
        "Tutor" to "Tuteur",
        "Lv" to "Nv",
        "Move" to "Capacité",
        "Type" to "Type",
        "Cat." to "Cat.",
        "Pow" to "Puis.",
        "Acc" to "Préc.",
        "PP" to "PP",
        "ESC / Click Back to return" to "ESC / Cliquer Retour pour revenir",
        "N/A" to "N/D",

        // PokemonSearchScreen
        "✦ Pokémon Search ✦" to "✦ Recherche Pokémon ✦",
        "No Pokémon found" to "Aucun Pokémon trouvé",
        "ESC to close  \u2022  Click for details" to "ESC pour fermer  \u2022  Cliquer pour détails",
        "Search" to "Rechercher",

        // Shared
        "Unknown" to "Inconnu",
        "Lv." to "Nv."
    )

    /** For "No {tab} moves" pattern */
    fun noMovesText(tabLabel: String): String {
        if (!isFrench()) return "No $tabLabel moves"
        val frTab = tr(tabLabel)
        return "Aucune capacité $frTab"
    }
}
