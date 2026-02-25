package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import java.io.File
import java.io.FileInputStream
import java.net.URI

/**
 * Downloads and caches 2D pixel art sprites from PokeAPI.
 * Sprites are cached on disk in config/hunterboard/sprites/.
 * Falls back to null if download fails (caller should use 3D ModelWidget).
 */
object SpriteHelper {

    // Memory cache: speciesName -> texture Identifier (null = failed/unavailable)
    private val spriteCache = mutableMapOf<String, Identifier?>()
    // Species currently being downloaded (thread-safe set)
    private val downloading = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    // Counter incremented each time a new sprite is loaded (for cache invalidation)
    var loadedCount = 0
        private set

    private val CACHE_DIR: File by lazy {
        File(MinecraftClient.getInstance().runDirectory, "config/hunterboard/sprites")
    }

    /**
     * Get a sprite texture identifier for the given species name.
     * Returns null if the sprite isn't available yet (triggers async download).
     */
    fun getSpriteIdentifier(speciesName: String): Identifier? {
        val key = speciesName.lowercase()

        // Check memory cache
        if (spriteCache.containsKey(key)) return spriteCache[key]

        // Check disk cache
        val file = File(CACHE_DIR, "$key.png")
        if (file.exists() && file.length() > 0) {
            return loadAndRegister(key, file)
        }

        // Trigger async download
        if (downloading.add(key)) {
            Thread {
                try {
                    downloadSprite(key)
                } catch (_: Exception) {
                    spriteCache[key] = null
                } finally {
                    downloading.remove(key)
                }
            }.also { it.isDaemon = true }.start()
        }

        return null
    }

    private fun downloadSprite(key: String) {
        val species = PokemonSpecies.getByName(key)
        if (species == null) { spriteCache[key] = null; return }

        val dexNum = species.nationalPokedexNumber
        if (dexNum <= 0) { spriteCache[key] = null; return }

        val url = URI("https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/$dexNum.png").toURL()
        CACHE_DIR.mkdirs()
        val file = File(CACHE_DIR, "$key.png")

        url.openStream().use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Load texture on render thread
        MinecraftClient.getInstance().execute {
            loadAndRegister(key, file)
        }
    }

    private fun loadAndRegister(key: String, file: File): Identifier? {
        return try {
            val image = FileInputStream(file).use { NativeImage.read(it) }
            val texture = NativeImageBackedTexture(image)
            val id = Identifier.of("hunterboard", "sprites/$key")
            MinecraftClient.getInstance().textureManager.registerTexture(id, texture)
            spriteCache[key] = id
            loadedCount++
            id
        } catch (_: Exception) {
            spriteCache[key] = null
            null
        }
    }

    /** Clear memory cache and unregister textures */
    fun clearCache() {
        val tm = MinecraftClient.getInstance().textureManager
        for ((_, id) in spriteCache) {
            if (id != null) try { tm.destroyTexture(id) } catch (_: Exception) {}
        }
        spriteCache.clear()
        loadedCount++
    }
}
