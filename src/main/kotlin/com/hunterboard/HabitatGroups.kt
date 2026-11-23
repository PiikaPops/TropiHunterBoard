package com.hunterboard

import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier

/**
 * Where a habitat lives, for the wiki list: a handful of environments a player tells
 * apart at a glance, and one Minecraft item to stand for each habitat.
 *
 * Biome tags cannot do this alone. A third of the habitats are placed by worldgen
 * features whose biomes are attached in code and stated in no data file, so the table
 * is set by hand from Cobblemon 1.8.4's structure tags and feature placements — the
 * height a feature generates at and the blocks it looks for say where it is: lava at
 * y=31 is the Nether's lava sea, soul sand its valleys, end stone the End. A habitat
 * missing from the table (a newer Cobblemon, a datapack) is placed by the words in its
 * id, and falls into [Group.OTHER] when none fits.
 */
object HabitatGroups {

    /** In display order. [label] goes through [Translations.tr]. */
    enum class Group(val label: String, val icon: String) {
        GRASSLAND("Grasslands & flowers", "minecraft:grass_block"),
        FOREST("Forests & jungles", "minecraft:jungle_sapling"),
        ARID("Deserts & savannas", "minecraft:sand"),
        MOUNTAIN("Mountains", "minecraft:stone"),
        SNOW("Snow & ice", "minecraft:snow_block"),
        WATER("Water & shores", "minecraft:water_bucket"),
        UNDERGROUND("Underground", "minecraft:deepslate"),
        NETHER("Nether", "minecraft:netherrack"),
        END("The End", "minecraft:end_stone"),
        VILLAGE("Villages", "minecraft:bell"),
        OTHER("Other habitats", "minecraft:grass_block");

        /** Translated, capitalised: "The End" is "l'End" mid-sentence but a heading here. */
        fun displayName(): String = Translations.tr(label).replaceFirstChar { it.uppercase() }
    }

    private class Info(val group: Group, val icon: String)

    private val KNOWN: Map<String, Info> = mapOf(
        // grasslands, meadows, flower fields
        "berry_patch" to Info(Group.GRASSLAND, "minecraft:sweet_berries"),
        "flowerbed_clearing" to Info(Group.GRASSLAND, "minecraft:cherry_sapling"),
        "pinkflowerbed_clearing" to Info(Group.GRASSLAND, "minecraft:pink_petals"),
        "sunflowerbed_clearing" to Info(Group.GRASSLAND, "minecraft:sunflower"),
        "sandpit_clearing" to Info(Group.GRASSLAND, "minecraft:sand"),
        "oak_wildfire_scar" to Info(Group.GRASSLAND, "minecraft:oak_log"),
        "birch_wildfire_scar" to Info(Group.GRASSLAND, "minecraft:birch_log"),
        "parched_peat_bog" to Info(Group.GRASSLAND, "minecraft:packed_mud"),
        "root_nursery" to Info(Group.GRASSLAND, "minecraft:oak_sapling"),
        // forests and jungles
        "fae_mounds" to Info(Group.FOREST, "minecraft:spore_blossom"),
        "fungal_dwelling" to Info(Group.FOREST, "minecraft:red_mushroom"),
        "lush_canopy" to Info(Group.FOREST, "minecraft:jungle_sapling"),
        "lush_cenote" to Info(Group.FOREST, "minecraft:big_dripleaf"),
        "lush_peat_bog" to Info(Group.FOREST, "minecraft:mud"),
        "reclaimed_lush_monument" to Info(Group.FOREST, "minecraft:mossy_cobblestone"),
        "thermal_crevices" to Info(Group.FOREST, "minecraft:magma_block"),
        "zen_garden" to Info(Group.FOREST, "minecraft:bamboo"),
        // deserts, badlands, savannas
        "badlands_shaded_rock" to Info(Group.ARID, "minecraft:red_sand"),
        "bug_mound" to Info(Group.ARID, "minecraft:coarse_dirt"),
        "desert_oasis" to Info(Group.ARID, "minecraft:cactus"),
        "desert_shaded_rock" to Info(Group.ARID, "minecraft:sandstone"),
        "meteorite_impact" to Info(Group.ARID, "minecraft:fire_charge"),
        "reclaimed_desert_monument" to Info(Group.ARID, "minecraft:chiseled_sandstone"),
        "sunscorched_clearing" to Info(Group.ARID, "minecraft:dead_bush"),
        // mountains (a structure tagged is_mountain, a feature at y 150-255)
        "natural_lightning_rod" to Info(Group.MOUNTAIN, "minecraft:lightning_rod"),
        "rocky_outcrop" to Info(Group.MOUNTAIN, "minecraft:cobblestone"),
        // snow and ice
        "carved_ice_spikes" to Info(Group.SNOW, "minecraft:packed_ice"),
        "snowy_burrow" to Info(Group.SNOW, "minecraft:snow_block"),
        "snowy_grotto" to Info(Group.SNOW, "minecraft:powder_snow_bucket"),
        "snowy_thermal_vents" to Info(Group.SNOW, "minecraft:campfire"),
        "spruce_wildfire_scar" to Info(Group.SNOW, "minecraft:spruce_log"),
        // water
        "freshwater_pond" to Info(Group.WATER, "minecraft:lily_pad"),
        "deep_sea_spire" to Info(Group.WATER, "minecraft:prismarine"),
        "drifting_icebergs" to Info(Group.WATER, "minecraft:blue_ice"),
        "rocky_tide_pool" to Info(Group.WATER, "minecraft:nautilus_shell"),
        "sandy_tide_pool" to Info(Group.WATER, "minecraft:sea_pickle"),
        // underground: features between y=-58 and y=50, in stone and deepslate
        "ancient_wellspring" to Info(Group.UNDERGROUND, "minecraft:mossy_stone_bricks"),
        "deep_roots" to Info(Group.UNDERGROUND, "minecraft:hanging_roots"),
        "earthen_hives" to Info(Group.UNDERGROUND, "minecraft:bee_nest"),
        "exposed_geode" to Info(Group.UNDERGROUND, "minecraft:amethyst_cluster"),
        "lost_cave_ruins" to Info(Group.UNDERGROUND, "minecraft:cracked_stone_bricks"),
        "treasure_hoard" to Info(Group.UNDERGROUND, "minecraft:gold_block"),
        // the Nether: netherrack and nylium, soul sand, the lava sea at y=31
        "abandoned_fortress" to Info(Group.NETHER, "minecraft:nether_bricks"),
        "crimson_fungal_dwelling" to Info(Group.NETHER, "minecraft:crimson_fungus"),
        "warped_fungal_dwelling" to Info(Group.NETHER, "minecraft:warped_fungus"),
        "strange_fungal_dwelling" to Info(Group.NETHER, "minecraft:shroomlight"),
        "quartz_spikes" to Info(Group.NETHER, "minecraft:quartz"),
        "skeletal_fall" to Info(Group.NETHER, "minecraft:bone_block"),
        "volcanic_plume" to Info(Group.NETHER, "minecraft:lava_bucket"),
        // the End
        "chorus_briar" to Info(Group.END, "minecraft:chorus_flower"),
        // inside village houses
        "abandoned_village_house" to Info(Group.VILLAGE, "minecraft:bell")
    )

    /** First match wins, so the narrow environments are tried before the broad ones. */
    private val KEYWORDS: List<Pair<Group, List<String>>> = listOf(
        Group.NETHER to listOf("nether", "crimson", "warped", "soul", "basalt", "blaze"),
        Group.END to listOf("chorus", "end_"),
        Group.VILLAGE to listOf("village"),
        Group.SNOW to listOf("snow", "ice", "frozen", "frost", "glacier", "tundra"),
        Group.WATER to listOf("sea", "ocean", "tide", "pond", "lake", "river", "reef", "coral", "beach"),
        Group.UNDERGROUND to listOf("cave", "geode", "deep", "mine", "grotto", "cavern"),
        Group.ARID to listOf("desert", "sand", "badland", "mesa", "savanna", "dune", "scorched"),
        Group.MOUNTAIN to listOf("mountain", "peak", "cliff", "rocky"),
        Group.FOREST to listOf("forest", "jungle", "lush", "canopy", "grove", "wood", "fungal", "mushroom"),
        Group.GRASSLAND to listOf("meadow", "plain", "field", "flower", "clearing", "grass", "prairie")
    )

    private fun path(habitatId: String) = habitatId.substringAfter(":")

    fun groupOf(habitatId: String): Group {
        val id = path(habitatId)
        KNOWN[id]?.let { return it.group }
        return KEYWORDS.firstOrNull { (_, words) -> words.any { it in id } }?.first ?: Group.OTHER
    }

    private val stacks = HashMap<String, ItemStack>()

    /** The item drawn beside a habitat: its own when listed, its group's otherwise. */
    fun iconOf(habitatId: String): ItemStack = stacks.getOrPut(habitatId) {
        val wanted = KNOWN[path(habitatId)]?.icon ?: groupOf(habitatId).icon
        stackOf(wanted) ?: stackOf(groupOf(habitatId).icon) ?: ItemStack(Items.GRASS_BLOCK)
    }

    /** Null for an id the registry does not know, rather than the air it would answer. */
    private fun stackOf(id: String): ItemStack? = try {
        val identifier = Identifier.of(id)
        if (Registries.ITEM.containsId(identifier)) ItemStack(Registries.ITEM.get(identifier)) else null
    } catch (_: Exception) { null }
}
