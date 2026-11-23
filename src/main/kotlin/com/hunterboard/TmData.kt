package com.hunterboard

import com.google.gson.JsonParser
import java.nio.file.Files

data class TmRecipeItem(val item: String, val count: Int)

/**
 * A Technical Machine. Cobblemon 1.8 brought TMs into the base mod (they used to need
 * an add-on), with a crafting recipe and an unlock method per move.
 */
data class TmInfo(
    val moveName: String,
    val type: String,
    /** "default" (craftable straight away), "unlockable", "advancement"… */
    val obtainMethods: List<String>,
    val recipe: List<TmRecipeItem>
)

object TmData {

    private var tmMap: Map<String, TmInfo> = emptyMap()
    var isLoaded = false
        private set

    /** Move ids are showdown-style ("acidspray"); fold anything else down to that shape. */
    private fun key(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    fun forMove(moveName: String): TmInfo? = tmMap[key(moveName)]

    /** Set even when nothing was found, so the per-frame caller does not re-walk the jar. */
    private var triedLoad = false

    fun invalidate() {
        tmMap = emptyMap()
        isLoaded = false
        triedLoad = false
    }

    fun ensureLoaded() {
        if (triedLoad) return
        triedLoad = true
        try {
            val map = mutableMapOf<String, TmInfo>()

            CobblemonResources.forEachJson("tms") { file ->
                try {
                    val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                    val moveName = root.get("moveName")?.asString ?: return@forEachJson

                    val methods = mutableListOf<String>()
                    root.getAsJsonArray("obtainMethods")?.forEach { el ->
                        try {
                            val variant = el.asJsonObject.get("variant")?.asString ?: return@forEach
                            val short = variant.substringAfter(":")
                            if (short.isNotEmpty() && short !in methods) methods.add(short)
                        } catch (_: Exception) {}
                    }

                    val recipe = mutableListOf<TmRecipeItem>()
                    root.getAsJsonArray("recipe")?.forEach { el ->
                        try {
                            val obj = el.asJsonObject
                            val item = obj.get("item")?.asString ?: return@forEach
                            recipe.add(TmRecipeItem(item, obj.get("count")?.asInt ?: 1))
                        } catch (_: Exception) {}
                    }

                    map[key(moveName)] = TmInfo(
                        moveName = moveName,
                        type = root.get("type")?.asString ?: "",
                        obtainMethods = methods,
                        recipe = recipe
                    )
                } catch (_: Exception) {}
            }

            if (map.isNotEmpty()) {
                tmMap = map
                isLoaded = true
                HunterBoard.LOGGER.info("Loaded ${map.size} technical machines")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Failed to load TM data: ${e.message}")
        }
    }

    fun obtainMethodName(method: String): String = when (method.lowercase()) {
        "default" -> Translations.tr("Craftable")
        "unlockable" -> Translations.tr("Must be unlocked")
        "advancement" -> Translations.tr("Advancement reward")
        else -> method.replaceFirstChar { it.uppercase() }
    }

    /** Ready-to-draw TM lines for a move, or an empty list when it has no TM. */
    fun tmLines(moveName: String): List<SpawnLine> {
        val tm = forMove(moveName) ?: return emptyList()
        val lines = mutableListOf<SpawnLine>()

        val methods = tm.obtainMethods.joinToString(", ") { obtainMethodName(it) }
        val header = if (methods.isEmpty()) Translations.tr("Technical Machine")
                     else "${Translations.tr("Technical Machine")} — $methods"
        lines.add(SpawnLine(header, 0xFF66CCEE.toInt()))

        if (tm.recipe.isNotEmpty()) {
            val recipe = tm.recipe.joinToString(" + ") { "${it.count}× ${Translations.itemName(it.item)}" }
            lines.add(SpawnLine("  $recipe", 0xFF99BBCC.toInt()))
        }
        return lines
    }
}
