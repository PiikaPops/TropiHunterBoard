package com.hunterboard

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates Cobblemon data files across every loaded mod.
 *
 * Cobblemon's data registries are namespace-agnostic: a spawn pool lives at
 * `data/<any namespace>/spawn_pool_world/…`, so add-ons and biome-compat packs
 * (Oh The Biomes We've Gone compat, Mega Showdown, the Tropimon packs…) ship
 * their own spawns under their own namespace. Reading only the Cobblemon jar
 * silently dropped all of them from the offline fallback.
 *
 * Note this covers mods only. Spawns added by a *world* datapack live on the
 * server, and reach the client through the live API path instead.
 */
object CobblemonResources {

    /**
     * Calls [action] for every `.json` under `data/<namespace>/[folder]` in every
     * loaded mod. Failures on a single mod or file are skipped rather than
     * aborting the whole scan.
     */
    fun forEachJson(folder: String, action: (Path) -> Unit) {
        for (mod in FabricLoader.getInstance().allMods) {
            for (root in try { mod.rootPaths } catch (_: Exception) { continue }) {
                val dataDir = try { root.resolve("data") } catch (_: Exception) { continue }
                if (!try { Files.isDirectory(dataDir) } catch (_: Exception) { false }) continue

                val namespaces = try {
                    Files.newDirectoryStream(dataDir).use { it.toList() }
                } catch (_: Exception) { continue }

                for (namespace in namespaces) {
                    val dir = namespace.resolve(folder)
                    if (!try { Files.isDirectory(dir) } catch (_: Exception) { false }) continue
                    try {
                        Files.walk(dir).use { stream ->
                            stream.filter { it.toString().endsWith(".json") }
                                .forEach { file ->
                                    try { action(file) } catch (_: Exception) {}
                                }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /** Same walk as [forEachJson] but for any extension (structure .nbt files, …). */
    fun forEachFile(folder: String, extension: String, action: (Path) -> Unit) {
        for (mod in FabricLoader.getInstance().allMods) {
            for (root in try { mod.rootPaths } catch (_: Exception) { continue }) {
                val dataDir = try { root.resolve("data") } catch (_: Exception) { continue }
                if (!try { Files.isDirectory(dataDir) } catch (_: Exception) { false }) continue

                val namespaces = try {
                    Files.newDirectoryStream(dataDir).use { it.toList() }
                } catch (_: Exception) { continue }

                for (namespace in namespaces) {
                    val dir = namespace.resolve(folder)
                    if (!try { Files.isDirectory(dir) } catch (_: Exception) { false }) continue
                    try {
                        Files.walk(dir).use { stream ->
                            stream.filter { it.toString().endsWith(extension) }
                                .forEach { file -> try { action(file) } catch (_: Exception) {} }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /** Namespaces that ship a given Cobblemon data folder — useful for diagnostics. */
    fun namespacesProviding(folder: String): List<String> {
        val found = linkedSetOf<String>()
        for (mod in FabricLoader.getInstance().allMods) {
            for (root in try { mod.rootPaths } catch (_: Exception) { continue }) {
                val dataDir = try { root.resolve("data") } catch (_: Exception) { continue }
                if (!try { Files.isDirectory(dataDir) } catch (_: Exception) { false }) continue
                val namespaces = try {
                    Files.newDirectoryStream(dataDir).use { it.toList() }
                } catch (_: Exception) { continue }
                for (namespace in namespaces) {
                    val dir = namespace.resolve(folder)
                    if (try { Files.isDirectory(dir) } catch (_: Exception) { false }) {
                        found.add(namespace.fileName.toString().trim('/'))
                    }
                }
            }
        }
        return found.toList()
    }
}
