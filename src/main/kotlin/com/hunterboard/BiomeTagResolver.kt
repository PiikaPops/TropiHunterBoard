package com.hunterboard

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.TagKey
import net.minecraft.util.Identifier

/** One line of the biome tooltip: either a mod heading or a biome under it. */
data class BiomeLine(val text: String, val isHeader: Boolean)

object BiomeTagResolver {

    private val cache = mutableMapOf<String, List<String>>()
    private val groupedCache = mutableMapOf<String, List<BiomeLine>>()

    fun resolve(tagId: String): List<String> {
        val cacheKey = "$tagId|${Translations.isFrench()}"
        return cache.getOrPut(cacheKey) { resolveFromRegistry(tagId) }
    }

    fun clearCache() {
        cache.clear()
        groupedCache.clear()
    }

    private fun resolveFromRegistry(tagId: String): List<String> {
        try {
            val world = MinecraftClient.getInstance().world ?: return emptyList()
            val parts = tagId.split(":", limit = 2)
            if (parts.size != 2) return emptyList()

            val identifier = Identifier.of(parts[0], parts[1])
            val tagKey = TagKey.of(RegistryKeys.BIOME, identifier)
            val registry = world.registryManager.get(RegistryKeys.BIOME)
            val entryList = registry.getEntryList(tagKey)
            if (!entryList.isPresent) return emptyList()

            val biomes = mutableListOf<String>()
            for (entry in entryList.get()) {
                val key = entry.key
                if (key.isPresent) {
                    val id = key.get().value
                    biomes.add(Translations.biomeName(id.namespace, id.path))
                }
            }
            return biomes.sorted()
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Same tag, but split by the mod that provides each biome.
     *
     * A Cobblemon biome tag such as `#cobblemon:is_jungle` mixes vanilla biomes with
     * every modded biome the pack adds (Oh The Biomes We've Gone, Terralith, BWG…).
     * Flattened into one list you cannot tell whether a spawn is reachable in the
     * modded biomes you actually play in, so the tooltip groups them by mod.
     */
    fun resolveGrouped(tagId: String): List<BiomeLine> {
        val cacheKey = "$tagId|${Translations.isFrench()}"
        return groupedCache.getOrPut(cacheKey) { buildGrouped(tagId) }
    }

    private fun buildGrouped(tagId: String): List<BiomeLine> {
        val byNamespace = linkedMapOf<String, MutableList<String>>()
        try {
            val world = MinecraftClient.getInstance().world ?: return emptyList()
            val parts = tagId.split(":", limit = 2)
            if (parts.size != 2) return emptyList()

            val tagKey = TagKey.of(RegistryKeys.BIOME, Identifier.of(parts[0], parts[1]))
            val entryList = world.registryManager.get(RegistryKeys.BIOME).getEntryList(tagKey)
            if (!entryList.isPresent) return emptyList()

            for (entry in entryList.get()) {
                val id = entry.key.orElse(null)?.value ?: continue
                byNamespace.getOrPut(id.namespace) { mutableListOf() }
                    .add(Translations.biomeName(id.namespace, id.path))
            }
        } catch (_: Exception) {
            return emptyList()
        }
        if (byNamespace.isEmpty()) return emptyList()

        // Vanilla first, then whichever mod contributes the most biomes
        val order = byNamespace.keys.sortedWith(
            compareBy({ if (it == "minecraft") 0 else 1 }, { -byNamespace.getValue(it).size }, { it })
        )

        val lines = mutableListOf<BiomeLine>()
        for (namespace in order) {
            val biomes = byNamespace.getValue(namespace).distinct().sorted()
            lines.add(BiomeLine("${modDisplayName(namespace)} (${biomes.size})", true))
            for (name in biomes) lines.add(BiomeLine(name, false))
        }
        return lines
    }

    /** "minecraft" -> "Vanilla", "biomeswevegone" -> "Oh The Biomes We've Gone" */
    fun modDisplayName(namespace: String): String {
        if (namespace == "minecraft") return "Vanilla"
        val fromLoader = try {
            FabricLoader.getInstance().getModContainer(namespace)
                .map { it.metadata.name }.orElse(null)
        } catch (_: Exception) { null }
        if (!fromLoader.isNullOrBlank()) return fromLoader
        return namespace.replace("_", " ").split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun formatBiomeName(path: String): String {
        return path.replace("_", " ").split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
