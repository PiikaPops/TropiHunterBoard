package com.hunterboard

import net.minecraft.client.MinecraftClient
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.TagKey
import net.minecraft.util.Identifier

object BiomeTagResolver {

    private val cache = mutableMapOf<String, List<String>>()

    fun resolve(tagId: String): List<String> {
        val cacheKey = "$tagId|${Translations.isFrench()}"
        return cache.getOrPut(cacheKey) { resolveFromRegistry(tagId) }
    }

    fun clearCache() {
        cache.clear()
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

    private fun formatBiomeName(path: String): String {
        return path.replace("_", " ").split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
