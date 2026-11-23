package com.hunterboard

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import java.io.File
import java.nio.file.Files

/**
 * Development check of [HabitatFeed] against a relay (a local `raid_relay.py`): downloads the
 * habitat file from scratch, checks the wiki then uses Tropimon's pools, asks again (the
 * relay must answer "not modified"), and writes `habitat_feed.txt` in the tour folder.
 *
 * Started by UiTour when `-Puitour.habitatfeed=<relay url>` is given, before its pictures.
 */
object FeedCheck {
    private var phase = 0
    private var wait = 0
    private val log = StringBuilder()
    private var fails = 0

    private fun note(s: String) { log.appendLine(s); HunterBoard.LOGGER.info("[FeedCheck] $s") }
    private fun check(ok: Boolean, what: String) { if (!ok) fails++; note((if (ok) "PASS " else "FAIL ") + what) }
    private fun field(name: String) = HabitatFeed::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** True once finished; the tour then goes on with its pictures. */
    fun tick(client: MinecraftClient, url: String, out: File): Boolean {
        wait++
        when (phase) {
            0 -> {
                field("base").set(HabitatFeed, url)
                Files.deleteIfExists(FabricLoader.getInstance().configDir.resolve("hunterboard").resolve("habitats.json"))
                field("bundle").set(HabitatFeed, null)
                field("cacheRead").setBoolean(HabitatFeed, true)
                HabitatData.invalidate(); HabitatData.ensureLoaded()
                val before = HabitatData.speciesIn("cobblemon:berry_patch").map { it.first }.toSet()
                check(before.isEmpty() && HabitatData.isEmpty(), "no habitat at all before the relay's file (berry patch: ${before.size})")
                HabitatFeed.refresh()
                phase = 1; wait = 0
            }
            1 -> if (HabitatFeed.describe() != null || wait > 200) {
                check(HabitatFeed.describe() != null, "habitat file downloaded (${HabitatFeed.describe()})")
                check(Files.exists(FabricLoader.getInstance().configDir.resolve("hunterboard").resolve("habitats.json")), "cached in the config folder")
                phase = 2; wait = 0
            }
            2 -> if (wait > 5) {
                HabitatData.ensureLoaded()
                val pools = HabitatFeed.entries()
                check(pools.size == 51, "51 pools received (${pools.size})")
                // the berry patch now lists exactly the species of Tropimon's file
                val berry = pools.first { it.fileName == "berry_patch.json" }
                val expected = com.google.gson.JsonParser.parseString(berry.json).asJsonObject.getAsJsonArray("spawns")
                    .map { it.asJsonObject.get("species").asString.lowercase() }.map { NameUtil.normalize(it) }.toSet()
                val shown = HabitatData.speciesIn("cobblemon:berry_patch").map { it.first }.toSet()
                check(shown == expected, "the wiki's berry patch is Tropimon's (${shown.size} species, expected ${expected.size})")
                field("lastCheck").setLong(HabitatFeed, 0L)
                HabitatFeed.refresh()   // same version: the relay must answer 304
                phase = 3; wait = 0
            }
            3 -> if (wait > 60) {
                check(!(field("inFlight").getBoolean(HabitatFeed)), "second request answered")
                note(if (fails == 0) "ALL PASSED" else "$fails FAILED")
                runCatching { File(out, "habitat_feed.txt").writeText(log.toString()) }
                phase = 4
                return true
            }
            else -> return true
        }
        return false
    }
}
