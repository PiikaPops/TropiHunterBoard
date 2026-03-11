package com.hunterboard

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.nio.file.Files

data class CosmeticEntry(
    val aspectName: String,
    val itemId: String,
    val displayName: String,
    val itemStack: ItemStack?
)

object CosmeticData {

    private var cache: MutableMap<String, List<CosmeticEntry>> = mutableMapOf()
    private var loaded = false
    private var allEntries: List<RawCosmetic> = emptyList()

    private data class RawCosmetic(
        val speciesNames: List<String>,
        val itemId: String,
        val aspects: List<String>
    )

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val cobblemonMod = FabricLoader.getInstance()
                .getModContainer("cobblemon").orElse(null) ?: return

            val entries = mutableListOf<RawCosmetic>()
            for (rootPath in cobblemonMod.rootPaths) {
                val cosmeticDir = rootPath.resolve("data/cobblemon/cosmetic_items")
                if (!Files.exists(cosmeticDir) || !Files.isDirectory(cosmeticDir)) continue

                Files.list(cosmeticDir).use { stream ->
                    stream.filter { it.toString().endsWith(".json") }.forEach { file ->
                        try {
                            val content = Files.readString(file)
                            val root = JsonParser.parseString(content).asJsonObject

                            val pokemonArray = root.getAsJsonArray("pokemon") ?: return@forEach
                            val speciesNames = pokemonArray.map { it.asString.lowercase().split(" ")[0] }

                            val cosmeticItems = root.getAsJsonArray("cosmeticItems") ?: return@forEach
                            for (item in cosmeticItems) {
                                val obj = item.asJsonObject
                                val consumedItem = obj.get("consumedItem")?.asString ?: continue
                                val aspectsArr = obj.getAsJsonArray("aspects") ?: continue
                                val aspects = aspectsArr.map { it.asString }
                                entries.add(RawCosmetic(speciesNames, consumedItem, aspects))
                            }
                        } catch (_: Exception) {}
                    }
                }
                if (entries.isNotEmpty()) break
            }
            allEntries = entries
            HunterBoard.LOGGER.info("Loaded ${entries.size} cosmetic entries from Cobblemon")
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load cosmetic data: ${e.message}")
        }
    }

    fun getCosmeticsForSpecies(speciesName: String): List<CosmeticEntry> {
        ensureLoaded()
        val key = speciesName.lowercase()
        return cache.getOrPut(key) {
            val result = mutableListOf<CosmeticEntry>()
            for (raw in allEntries) {
                if (key in raw.speciesNames) {
                    for (aspect in raw.aspects) {
                        val displayName = try {
                            val stack = ItemStack(Registries.ITEM.get(Identifier.of(raw.itemId)))
                            if (!stack.isEmpty) stack.name.string
                            else raw.itemId.substringAfter(":").replace("_", " ")
                                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                        } catch (_: Exception) {
                            raw.itemId.substringAfter(":").replace("_", " ")
                                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                        }
                        val itemStack = try {
                            val stack = ItemStack(Registries.ITEM.get(Identifier.of(raw.itemId)))
                            if (!stack.isEmpty) stack else null
                        } catch (_: Exception) { null }
                        result.add(CosmeticEntry(aspect, raw.itemId, displayName, itemStack))
                    }
                }
            }
            result
        }
    }
}
