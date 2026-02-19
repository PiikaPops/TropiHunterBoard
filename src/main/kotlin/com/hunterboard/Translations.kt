package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.client.MinecraftClient
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
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

    fun formatSpawnContext(context: String): String {
        if (context.isEmpty() || context == "grounded") return ""
        if (!isFrench()) {
            return when (context) {
                "submerged" -> "Underwater"
                "surface" -> "Water Surface"
                "seafloor" -> "Seafloor"
                else -> context.replaceFirstChar { it.uppercase() }
            }
        }
        return when (context) {
            "submerged" -> "Sous l'eau"
            "surface" -> "Surface de l'eau"
            "seafloor" -> "Fond marin"
            else -> context.replaceFirstChar { it.uppercase() }
        }
    }

    fun formatMoonPhase(phase: Int): String {
        val phases = if (isFrench()) {
            arrayOf("Pleine Lune", "Gibbeuse déc.", "Dernier quartier", "Croissant déc.",
                    "Nouvelle Lune", "Croissant croi.", "Premier quartier", "Gibbeuse croi.")
        } else {
            arrayOf("Full Moon", "Waning Gibbous", "Third Quarter", "Waning Crescent",
                    "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous")
        }
        return phases.getOrElse(phase) { "?" }
    }

    fun formatPreset(preset: String): String {
        if (!isFrench()) {
            return when (preset.lowercase()) {
                "natural" -> "Natural"
                "urban" -> "Urban"
                "mansion" -> "Mansion"
                "fishing" -> "Fishing"
                "underground" -> "Underground"
                "foliage" -> "Foliage"
                "surface" -> "Surface"
                "submerged" -> "Submerged"
                "treetop" -> "Treetop"
                else -> preset.replaceFirstChar { it.uppercase() }
            }
        }
        return when (preset.lowercase()) {
            "natural" -> "Naturel"
            "urban" -> "Urbain"
            "mansion" -> "Manoir"
            "fishing" -> "Pêche"
            "underground" -> "Souterrain"
            "foliage" -> "Feuillage"
            "surface" -> "Surface"
            "submerged" -> "Immergé"
            "treetop" -> "Cime d'arbre"
            else -> preset.replaceFirstChar { it.uppercase() }
        }
    }

    fun categoryName(categoryKey: String): String {
        if (!isFrench()) return categoryKey.replaceFirstChar { it.uppercase() }
        return when (categoryKey.lowercase()) {
            "physical" -> "Physique"
            "special" -> "Spéciale"
            "status" -> "Statut"
            else -> categoryKey.replaceFirstChar { it.uppercase() }
        }
    }

    fun formatMultiplierCondition(label: String): String {
        // label is a raw condition tag like "thunder", "night", "lure:3+", "moon:4"
        val parts = label.split(", ").map { part ->
            when {
                part == "thunder" -> if (isFrench()) "Orage" else "Thunder"
                part == "rain" -> if (isFrench()) "Pluie" else "Rain"
                part == "day" -> if (isFrench()) "Jour" else "Day"
                part == "night" -> if (isFrench()) "Nuit" else "Night"
                part == "dusk" || part == "twilight" -> if (isFrench()) "Crépuscule" else "Twilight"
                part == "dawn" -> if (isFrench()) "Aube" else "Dawn"
                part.startsWith("lure:") -> {
                    val lvl = part.removePrefix("lure:")
                    if (isFrench()) "Leurre $lvl" else "Lure $lvl"
                }
                part.startsWith("moon:") -> {
                    val phase = part.removePrefix("moon:").toIntOrNull()
                    if (phase != null) formatMoonPhase(phase) else part
                }
                else -> part.replaceFirstChar { it.uppercase() }
            }
        }
        return parts.joinToString(", ")
    }

    fun blockName(blockId: String): String {
        if (blockId.startsWith("#")) {
            val path = blockId.removePrefix("#").substringAfter(":")
            return path.replace("_", " ").split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        return try {
            val block = Registries.BLOCK.get(Identifier.of(blockId))
            block.name.string
        } catch (_: Exception) {
            blockId.substringAfter(":").replace("_", " ")
                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
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

        // MoveSearchScreen / MoveDetailScreen
        "\u2726 Move Search \u2726" to "\u2726 Encyclopédie Capacités \u2726",
        "Moves" to "Capacités",
        "Pokémon" to "Pokémon",
        "No move found" to "Aucune capacité trouvée",
        "A-Z" to "A-Z",
        "Category" to "Catégorie",
        "Power" to "Puissance",
        "Accuracy" to "Précision",
        "Description" to "Description",
        "Learned By" to "Appris par",
        "Loading..." to "Chargement...",
        "No Pokémon learns this move" to "Aucun Pokémon n'apprend cette capacité",
        "Physical" to "Physique",
        "Special" to "Spéciale",
        "Status" to "Statut",

        // PokemonSearchScreen
        "✦ Pokémon Search ✦" to "✦ Recherche Pokémon ✦",
        "No Pokémon found" to "Aucun Pokémon trouvé",
        "ESC to close  \u2022  Click for details" to "ESC pour fermer  \u2022  Cliquer pour détails",
        "Search" to "Rechercher",

        // PokemonSearchScreen - History
        "\u2726 History \u2726" to "\u2726 Historique \u2726",
        "History" to "Historique",
        "All" to "Tous",
        "No history yet" to "Aucun historique",

        // Evolution
        "Evolution" to "\u00c9volution",

        // Regional forms
        "Standard" to "Standard",
        "Alolan" to "Alola",
        "Galarian" to "Galar",
        "Hisuian" to "Hisui",
        "Paldean" to "Paldea",

        // Mega forms
        "Mega" to "Méga",
        "Mega X" to "Méga X",
        "Mega Y" to "Méga Y",
        "Mega Evolutions" to "Méga Évolutions",

        // Miracle HUD
        "No active boost" to "Aucun boost actif",
        "Show:" to "Afficher :",

        // Options screen
        "Options" to "Options",
        "HUD" to "ATH",
        "HUD Color" to "Couleur ATH",
        "Transparency" to "Transparence",
        "Rank" to "Grade",
        "Dresseur" to "Dresseur",
        "Super" to "Super",
        "Hyper" to "Hyper",
        "Master" to "Master",
        "hunts displayed" to "chasses affich\u00e9es",
        "ESC to return" to "ESC pour revenir",
        "Reset" to "Défaut",
        "Icon+" to "Icône+",
        "Position" to "Position",
        "Size" to "Taille",
        "Small" to "Petit",
        "Normal" to "Normal",
        "Large" to "Grand",
        "Extra Large" to "Très Grand",
        "Send to Chat" to "Envoyer dans le Chat",
        "Confirm" to "Confirmer",
        "Drag to move HUD" to "Déplacez l'ATH",

        // Raid timer
        "Next Raid" to "Prochain Raid",
        "Raid Active!" to "Raid en cours !",

        // Spawn detail labels
        "SkyLight:" to "Lum. ciel :",
        "Light:" to "Lumière :",
        "Moon:" to "Lune :",
        "Height:" to "Hauteur :",
        "Base:" to "Bloc :",
        "Nearby:" to "Proximité :",
        "Weight:" to "Poids :",
        "Excluded:" to "Exclus :",
        "Multipliers:" to "Multiplicateurs :",

        // Type chart
        "Type Chart" to "Sensibilité au type",

        // Egg groups
        "Egg Groups" to "Groupes d'œufs",

        // Shared
        "Unknown" to "Inconnu",
        "Lv." to "Nv."
    )

    fun formatEvoCondition(raw: String): String {
        if (raw.isEmpty()) return ""
        val parts = raw.split("+")
        val formatted = parts.mapNotNull { part ->
            when {
                part.startsWith("Lv. ") -> if (isFrench()) part.replace("Lv.", "Nv.") else part
                part == "friendship" -> if (isFrench()) "Bonheur" else "Friendship"
                part.startsWith("friendship:") -> {
                    val amount = part.removePrefix("friendship:")
                    if (isFrench()) "Bonheur \u2265$amount" else "Friendship \u2265$amount"
                }
                part == "trade" -> if (isFrench()) "\u00c9change" else "Trade"
                part == "item" -> if (isFrench()) "Objet" else "Item"
                part == "held_item" -> if (isFrench()) "Objet tenu" else "Held Item"
                part.startsWith("item:") -> {
                    val itemId = part.removePrefix("item:")
                    formatItemName(itemId)
                }
                part.startsWith("held:") -> {
                    val itemId = part.removePrefix("held:")
                    val itemName = formatItemName(itemId)
                    if (isFrench()) "Tenu: $itemName" else "Hold: $itemName"
                }
                part.startsWith("time:") -> {
                    val range = part.removePrefix("time:")
                    when (range.lowercase()) {
                        "day" -> if (isFrench()) "Jour" else "Day"
                        "night" -> if (isFrench()) "Nuit" else "Night"
                        "dusk" -> if (isFrench()) "Cr\u00e9puscule" else "Dusk"
                        "dawn" -> if (isFrench()) "Aube" else "Dawn"
                        else -> range.replaceFirstChar { it.uppercase() }
                    }
                }
                part.startsWith("biome:") -> {
                    val biome = part.removePrefix("biome:")
                    if (isFrench()) "Biome" else "Biome"
                }
                part.startsWith("move:") -> {
                    val moveName = part.removePrefix("move:")
                    val displayName = try {
                        com.cobblemon.mod.common.api.moves.Moves.getByName(moveName)?.displayName?.string
                    } catch (_: Exception) { null }
                    val label = if (isFrench()) "Cap." else "Move"
                    "$label ${displayName ?: moveName.replaceFirstChar { it.uppercase() }}"
                }
                part.startsWith("move_type:") -> {
                    val type = part.removePrefix("move_type:")
                    val typeName = try {
                        net.minecraft.text.Text.translatable("cobblemon.type.${type.lowercase()}").string.let {
                            if (it.startsWith("cobblemon.type.")) type.replaceFirstChar { c -> c.uppercase() } else it
                        }
                    } catch (_: Exception) { type.replaceFirstChar { it.uppercase() } }
                    if (isFrench()) "Cap. $typeName" else "$typeName move"
                }
                part.startsWith("weather:") -> {
                    val w = part.removePrefix("weather:")
                    formatWeather(w)
                }
                part.startsWith("moon:") -> {
                    val phase = part.removePrefix("moon:").toIntOrNull()
                    if (phase != null) formatMoonPhase(phase) else null
                }
                part.startsWith("gender:") -> {
                    val g = part.removePrefix("gender:")
                    when (g.lowercase()) {
                        "male" -> "\u2642"
                        "female" -> "\u2640"
                        else -> g
                    }
                }
                part.startsWith("atk_def:") -> {
                    val ratio = part.removePrefix("atk_def:")
                    when (ratio.lowercase()) {
                        "attack_higher" -> if (isFrench()) "Atq > D\u00e9f" else "Atk > Def"
                        "defence_higher" -> if (isFrench()) "D\u00e9f > Atq" else "Def > Atk"
                        "equal" -> if (isFrench()) "Atq = D\u00e9f" else "Atk = Def"
                        else -> ratio
                    }
                }
                part.startsWith("random:") -> {
                    val chance = part.removePrefix("random:").toFloatOrNull()
                    if (chance != null) "${(chance * 100).toInt()}%" else null
                }
                part.startsWith("party_pokemon:") -> {
                    val pokemon = part.removePrefix("party_pokemon:")
                    val name = pokemonName(pokemon)
                    if (isFrench()) "Avec $name" else "With $name"
                }
                part.startsWith("recoil:") -> {
                    val dmg = part.removePrefix("recoil:")
                    if (isFrench()) "Recul ${dmg}PV" else "Recoil ${dmg}HP"
                }
                part.startsWith("damage_taken:") -> {
                    val dmg = part.removePrefix("damage_taken:")
                    if (isFrench()) "${dmg}PV d\u00e9g\u00e2ts" else "${dmg}HP damage"
                }
                part.startsWith("use_move:") -> {
                    val split = part.removePrefix("use_move:").split(":")
                    val move = split.getOrElse(0) { "" }
                    val times = split.getOrElse(1) { "" }
                    val displayName = try {
                        com.cobblemon.mod.common.api.moves.Moves.getByName(move)?.displayName?.string
                    } catch (_: Exception) { null }
                    val label = displayName ?: move.replaceFirstChar { it.uppercase() }
                    if (times.isNotEmpty()) "${label}\u00d7$times" else label
                }
                part.startsWith("defeat:") -> {
                    val split = part.removePrefix("defeat:").split(":")
                    val target = split.getOrElse(0) { "" }
                    val count = split.getOrElse(1) { "" }
                    val label = if (isFrench()) "Vaincre" else "Defeat"
                    val targetName = if (target.isNotEmpty()) " ${pokemonName(target)}" else ""
                    "$label$targetName${if (count.isNotEmpty()) "\u00d7$count" else ""}"
                }
                part.startsWith("steps:") -> {
                    val steps = part.removePrefix("steps:")
                    if (isFrench()) "$steps pas" else "$steps steps"
                }
                part == "overworld" -> null // skip, not useful info
                part == "party" -> null // skip
                else -> part.replace("_", " ").replaceFirstChar { it.uppercase() }
            }
        }
        return formatted.joinToString(", ")
    }

    private fun formatItemName(itemId: String): String {
        if (itemId.isEmpty()) return if (isFrench()) "Objet" else "Item"
        return try {
            val id = if (itemId.contains(":")) itemId else "cobblemon:$itemId"
            val stack = ItemStack(Registries.ITEM.get(Identifier.of(id)))
            if (!stack.isEmpty) stack.name.string
            else itemId.substringAfter(":").replace("_", " ")
                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        } catch (_: Exception) {
            itemId.substringAfter(":").replace("_", " ")
                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    fun formatTypeName(englishName: String): String {
        if (!isFrench()) return englishName.replaceFirstChar { it.uppercase() }
        return when (englishName.lowercase()) {
            "normal"   -> "Normal"
            "fire"     -> "Feu"
            "water"    -> "Eau"
            "electric" -> "\u00c9lectrik"
            "grass"    -> "Plante"
            "ice"      -> "Glace"
            "fighting" -> "Combat"
            "poison"   -> "Poison"
            "ground"   -> "Sol"
            "flying"   -> "Vol"
            "psychic"  -> "Psy"
            "bug"      -> "Insecte"
            "rock"     -> "Roche"
            "ghost"    -> "Spectre"
            "dragon"   -> "Dragon"
            "dark"     -> "T\u00e9n\u00e8bres"
            "steel"    -> "Acier"
            "fairy"    -> "F\u00e9e"
            else -> englishName.replaceFirstChar { it.uppercase() }
        }
    }

    fun immune(): String = if (isFrench()) "Immunis\u00e9" else "Immune"

    fun formatEggGroup(name: String): String {
        if (!isFrench()) return name.lowercase().split("_")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        return when (name.lowercase()) {
            "monster"    -> "Monstre"
            "water1"     -> "Eau 1"
            "water2"     -> "Eau 2"
            "water3"     -> "Eau 3"
            "bug"        -> "Insecte"
            "flying"     -> "Vol"
            "field"      -> "Terrestre"
            "fairy"      -> "F\u00e9e"
            "grass"      -> "Plante"
            "human_like" -> "Humano\u00efde"
            "mineral"    -> "Min\u00e9ral"
            "amorphous"  -> "Amorphe"
            "ditto"      -> "M\u00e9tamorph"
            "dragon"     -> "Dragon"
            "undiscovered" -> "Inconnu"
            else -> name.lowercase().split("_")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    /** For "No {tab} moves" pattern */
    fun noMovesText(tabLabel: String): String {
        if (!isFrench()) return "No $tabLabel moves"
        val frTab = tr(tabLabel)
        return "Aucune capacité $frTab"
    }

    /** Translate a biome name from BiomeDetail */
    fun biomeName(detail: BiomeDetail): String {
        if (!isFrench()) return detail.displayName

        // Direct biomes: use MC's built-in translation (biome.namespace.path)
        if (detail.biomeId != null) {
            try {
                val parts = detail.biomeId.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = "biome.${parts[0]}.${parts[1]}"
                    val translated = Text.translatable(key).string
                    if (translated != key) return translated
                }
            } catch (_: Exception) {}
        }

        // Tags or fallback: use manual map
        return FR_BIOME_TAGS[detail.displayName] ?: detail.displayName
    }

    /** Translate a biome name from registry path (for tooltips) */
    fun biomeName(namespace: String, path: String): String {
        if (!isFrench()) {
            return path.replace("_", " ").split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        try {
            val key = "biome.$namespace.$path"
            val translated = Text.translatable(key).string
            if (translated != key) return translated
        } catch (_: Exception) {}
        val english = path.replace("_", " ").split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        return FR_BIOME_TAGS[english] ?: english
    }

    // Cobblemon biome tag display names → French
    private val FR_BIOME_TAGS = mapOf(
        "Mountain" to "Montagne",
        "Forest" to "Forêt",
        "Plains" to "Plaines",
        "Taiga" to "Taïga",
        "Desert" to "Désert",
        "Badlands" to "Badlands",
        "Ocean" to "Océan",
        "River" to "Rivière",
        "Swamp" to "Marais",
        "Beach" to "Plage",
        "Jungle" to "Jungle",
        "Savanna" to "Savane",
        "Snowy" to "Enneigé",
        "Cave" to "Grotte",
        "Hill" to "Colline",
        "Peak" to "Pic",
        "Frozen" to "Gelé",
        "Mushroom" to "Champignon",
        "Floral" to "Floral",
        "Deep Ocean" to "Océan Profond",
        "Nether" to "Nether",
        "End" to "End",
        "Overworld" to "Surface",
        "Tropical" to "Tropical",
        "Sandy" to "Sablonneux",
        "Temperate" to "Tempéré",
        "Freshwater" to "Eau Douce",
        "Arid" to "Aride",
        "Cold" to "Froid",
        "Warm" to "Chaud",
        "Spooky" to "Sinistre",
        "Dripstone" to "Stalactite",
        "Lush" to "Luxuriant",
        "Coastal" to "Côtier",
        "Freezing" to "Glacial",
        "Highlands" to "Hautes Terres",
        "Volcanic" to "Volcanique",
        "Tundra" to "Toundra",
        "Meadow" to "Prairie",
        "Grove" to "Bosquet",
        "Stony" to "Rocheux",
        "Cherry" to "Cerisier",
        "Bamboo" to "Bambou",
        "Mangrove" to "Mangrove",
        "Sparse" to "Clairsemé",
        "Windswept" to "Venteux",
        "Deep" to "Profond",
        "Lukewarm" to "Tiède",
        "Wooded" to "Boisé",
        "Eroded" to "Érodé",
        "Flowery" to "Fleuri",
        "Glacial" to "Glacial",
        "Plateau" to "Plateau",
        "Shrubland" to "Broussailles",
        "Wetland" to "Zone Humide",
        "Birch" to "Bouleau",
        "Spruce" to "Épicéa",
        "Dark" to "Sombre",
        "Coral" to "Corail",
        "Reef" to "Récif",
        "Island" to "Île",
        "Shore" to "Côte",
        "Cliff" to "Falaise"
    )
}
