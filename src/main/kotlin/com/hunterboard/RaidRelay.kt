package com.hunterboard

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Shares the raid boss, and the moment the raid really starts, between HunterBoard players.
 *
 * Tropimon never says which Pokémon a raid's boss is before the fight is over, so only the
 * players standing near it know. Their mod reports it to a small relay
 * (`tools/raid_relay.py`), and everyone else's asks the relay and shows it on the raid card.
 * The same goes for the start: the server opens the raid a little more than five minutes
 * after announcing it, by an amount only the arena's countdown tells.
 *
 * Only the raid travels: the boss's species and aspects, the start's delay after the
 * announcement, and the raid's time slot. No player name, no position. Turning the option off stops both the reports and the questions.
 *
 * Every call runs off the game thread and fails silently: without the relay the card
 * simply stays as it was, with the countdown alone.
 */
object RaidRelay {

    private const val BASE = "https://hunterboard.pokepuller.fr"

    /** What the relay knows; either part may still be missing. */
    class Answer(val species: String?, val aspects: Set<String>, val reports: Int, val delayMs: Long?)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
    }
    private val agent: String by lazy {
        "HunterBoard/" + FabricLoader.getInstance().getModContainer(HunterBoard.MOD_ID)
            .map { it.metadata.version.friendlyString }.orElse("?")
    }

    /**
     * The raid's identity for the relay: its announcement time in 5-minute steps. The
     * notification reaches every client within a second or two, so they all round to the
     * same number.
     */
    fun slotOf(announcedAtMillis: Long): Long = Math.round(announcedAtMillis / 300_000.0)

    /**
     * The rule this client recognises the boss by. The relay counts the boss reports of
     * this rule and later ones only: an older HunterBoard took any wild Pokémon standing in
     * the arena for the boss, and would keep spreading one that walked by.
     */
    private const val DETECTION = 2

    fun report(slot: Long, species: String, aspects: Set<String>) {
        if (!ModConfig.raidShare) return
        val body = JsonObject().apply {
            addProperty("slot", slot)
            addProperty("v", DETECTION)
            addProperty("species", species)
            add("aspects", JsonArray().also { arr -> aspects.forEach { arr.add(it) } })
        }.toString()
        send(HttpRequest.newBuilder(URI.create("$BASE/raid"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))) { }
    }

    /** How long after its announcement this raid starts, as the arena's countdown showed it. */
    fun reportStart(slot: Long, delayMs: Long) {
        if (!ModConfig.raidShare) return
        val body = JsonObject().apply {
            addProperty("slot", slot)
            addProperty("delay_ms", delayMs)
        }.toString()
        send(HttpRequest.newBuilder(URI.create("$BASE/raid"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))) { }
    }

    /** Asks about the raid; [onAnswer] runs on a network thread, and only when someone knows something. */
    fun ask(slot: Long, onAnswer: (Answer) -> Unit) {
        if (!ModConfig.raidShare) return
        send(HttpRequest.newBuilder(URI.create("$BASE/raid?slot=$slot")).GET()) { text ->
            val json = JsonParser.parseString(text).asJsonObject
            val species = json.get("species")?.asString
            val delay = json.get("delay_ms")?.asLong
            if (species == null && delay == null) return@send
            val aspects = json.getAsJsonArray("aspects")?.map { it.asString }?.toSet() ?: emptySet()
            onAnswer(Answer(species, aspects, json.get("reports")?.asInt ?: 1, delay))
        }
    }

    private fun send(builder: HttpRequest.Builder, onBody: (String) -> Unit) {
        try {
            val request = builder.timeout(Duration.ofSeconds(5)).header("User-Agent", agent).build()
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept { response ->
                try { if (response.statusCode() == 200) onBody(response.body()) } catch (_: Exception) {}
            }.exceptionally { null }
        } catch (_: Exception) {}
    }
}
