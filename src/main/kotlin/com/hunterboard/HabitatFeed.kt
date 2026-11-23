package com.hunterboard

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * The Tropimon server's habitat pools, downloaded from HunterBoard's relay instead of being
 * shipped in the mod, so they can follow the server without a new HunterBoard release.
 *
 * Cobblemon never sends habitat pools to the client, and Tropimon replaces all of them;
 * `tools/extract_habitats.py` pulls them out of the modpack into one file and the relay
 * (`tools/raid_relay.py`, `GET /habitats`) serves it. The last file received is kept in the
 * config folder, so the data is there offline and from the first second of the next launch;
 * the relay is asked again at start-up, on every server join and every hour, and only
 * answers with the file when its version changed (ETag / If-None-Match).
 *
 * Without the file — option off, relay never reached, relay without data — the wiki shows
 * no habitat at all (Cobblemon's own would not be Tropimon's) and says why.
 */
object HabitatFeed {
    private var base = "https://hunterboard.pokepuller.fr"
    private const val FORMAT = 1
    private const val CHECK_EVERY_MS = 60 * 60 * 1000L

    class Bundle(val version: String, val generated: String, val entries: List<TropimonData.Entry>)

    @Volatile private var bundle: Bundle? = null
    @Volatile private var cacheRead = false
    @Volatile private var inFlight = false
    @Volatile private var lastCheck = 0L
    private var ticks = 0

    private val cacheFile get() = FabricLoader.getInstance().configDir.resolve("hunterboard").resolve("habitats.json")
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() }
    private val agent: String by lazy {
        "HunterBoard/" + FabricLoader.getInstance().getModContainer(HunterBoard.MOD_ID)
            .map { it.metadata.version.friendlyString }.orElse("?")
    }

    fun register() {
        refresh()
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> if (System.currentTimeMillis() - lastCheck > 10 * 60 * 1000L) refresh() }
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++ticks % 1200 == 0 && System.currentTimeMillis() - lastCheck > CHECK_EVERY_MS) refresh()
        }
    }

    /** Tropimon's habitat pools, or none (the wiki then shows no habitat). */
    fun entries(): List<TropimonData.Entry> {
        if (!ModConfig.habitatFeed) return emptyList()
        if (!cacheRead) readCache()
        return bundle?.entries ?: emptyList()
    }

    /** "version · date" of the data in use, for the options; null without data. */
    fun describe(): String? = bundle?.let { b -> b.generated.take(10).split("-").reversed().joinToString("/") + " · " + b.version.take(8) }

    @Synchronized
    private fun readCache() {
        if (cacheRead) return
        cacheRead = true
        try {
            if (Files.exists(cacheFile)) parse(Files.readString(cacheFile))?.let { bundle = it }
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Habitat data cache unreadable: ${e.message}")
        }
        bundle?.let { HunterBoard.LOGGER.info("Habitat data ${it.version} from cache: ${it.entries.size} pools") }
    }

    private fun parse(text: String): Bundle? {
        val root = JsonParser.parseString(text).asJsonObject
        if (root.get("format")?.asInt != FORMAT) return null
        val pools = root.getAsJsonObject("pools") ?: return null
        val entries = pools.entrySet().mapNotNull { (name, value) ->
            val pool = value as? JsonObject ?: return@mapNotNull null
            val data = pool.getAsJsonObject("data") ?: return@mapNotNull null
            TropimonData.Entry("$name.json", data.toString(), pool.get("replace")?.asBoolean ?: true)
        }.sortedBy { it.fileName }
        if (entries.isEmpty()) return null
        return Bundle(root.get("version")?.asString ?: "?", root.get("generated")?.asString ?: "", entries)
    }

    /** Asks the relay for a newer file; runs off the game thread and fails silently. */
    fun refresh() {
        if (!ModConfig.habitatFeed || inFlight) return
        if (!cacheRead) readCache()
        inFlight = true
        lastCheck = System.currentTimeMillis()
        try {
            val request = HttpRequest.newBuilder(URI.create("$base/habitats"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", agent)
                .header("Accept-Encoding", "gzip")
                .apply { bundle?.let { header("If-None-Match", "\"${it.version}\"") } }
                .GET().build()
            http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenAccept { response ->
                try {
                    when (response.statusCode()) {
                        200 -> {
                            val raw = response.body()
                            val gz = response.headers().firstValue("Content-Encoding").orElse("").contains("gzip")
                            val text = String(if (gz) GZIPInputStream(ByteArrayInputStream(raw)).readAllBytes() else raw, Charsets.UTF_8)
                            val fresh = parse(text) ?: throw IllegalStateException("not a habitat file")
                            Files.createDirectories(cacheFile.parent)
                            val tmp = cacheFile.resolveSibling("habitats.json.tmp")
                            Files.writeString(tmp, text)
                            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                            val changed = bundle?.version != fresh.version
                            bundle = fresh
                            HunterBoard.LOGGER.info("Habitat data ${fresh.version} downloaded: ${fresh.entries.size} pools")
                            // the wiki reads it again the next time it needs habitats
                            if (changed) MinecraftClient.getInstance().execute { HabitatData.invalidate() }
                        }
                        304 -> HunterBoard.LOGGER.debug("Habitat data ${bundle?.version} is current")
                        else -> HunterBoard.LOGGER.debug("Habitat data: relay answered ${response.statusCode()}")
                    }
                } catch (e: Exception) {
                    HunterBoard.LOGGER.warn("Habitat data from the relay ignored: ${e.message}")
                } finally { inFlight = false }
            }.exceptionally { inFlight = false; null }
        } catch (_: Exception) { inFlight = false }
    }

    /** Option turned on again: read and ask at once; turned off: Cobblemon's habitats at once. */
    fun optionChanged() {
        HabitatData.invalidate()
        if (ModConfig.habitatFeed) refresh()
    }
}
