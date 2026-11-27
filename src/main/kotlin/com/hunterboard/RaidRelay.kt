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
 * Shares the raid boss, the moment the raid really starts, and the raid itself, between
 * HunterBoard players.
 *
 * Tropimon never says which Pokémon a raid's boss is before the fight is over, so only the
 * players standing near it know. Their mod reports it to a small relay
 * (`tools/raid_relay.py`), and everyone else's asks the relay and shows it on the raid card.
 * The same goes for the start: the server opens the raid a little more than five minutes
 * after announcing it, by an amount only the arena's countdown tells.
 *
 * And the raid itself: Tropimon's announcement only reaches the players online at that
 * moment, so a client that heard it says so, and a client that joins in the next minutes
 * asks whether a raid is on its way ([latest]).
 *
 * Only the raid travels: the boss's species and aspects, the start's delay after the
 * announcement, whether it is a mega raid, and the raid's time slot. No player name, no
 * position. Turning the option off stops both the reports and the questions.
 *
 * Every call runs off the game thread and fails silently: without the relay the card
 * simply stays as it was, with the countdown alone.
 */
object RaidRelay {

    /** The relay; the development checks point it at a local one. */
    private var base = "https://hunterboard.pokepuller.fr"

    /** What the relay knows; either part may still be missing. */
    class Answer(val species: String?, val aspects: Set<String>, val reports: Int, val delayMs: Long?)

    /** The raid announced last, [ageMs] ago by the relay's clock, with whatever else is known of it. */
    class Latest(val slot: Long, val ageMs: Long, val mega: Boolean, val species: String?,
                 val aspects: Set<String>, val reports: Int, val delayMs: Long?)

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
        send(HttpRequest.newBuilder(URI.create("$base/raid"))
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
        send(HttpRequest.newBuilder(URI.create("$base/raid"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))) { }
    }

    /** The announcement of this raid reached this client (sent once per raid). */
    fun announce(slot: Long, mega: Boolean) {
        if (!ModConfig.raidShare) return
        val body = JsonObject().apply {
            addProperty("slot", slot)
            addProperty("announced", true)
            addProperty("mega", mega)
        }.toString()
        send(HttpRequest.newBuilder(URI.create("$base/raid"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))) { }
    }

    /**
     * Asks for a raid announced in the last minutes, for a client that was not there to
     * hear it; [onAnswer] runs on a network thread, and only when there is one.
     */
    fun latest(onAnswer: (Latest) -> Unit) {
        if (!ModConfig.raidShare) return
        send(HttpRequest.newBuilder(URI.create("$base/raid/latest")).GET()) { text ->
            val json = JsonParser.parseString(text).asJsonObject
            val slot = json.get("slot")?.asLong ?: return@send
            val age = json.get("age_ms")?.asLong ?: return@send
            val aspects = json.getAsJsonArray("aspects")?.map { it.asString }?.toSet() ?: emptySet()
            onAnswer(Latest(slot, age.coerceAtLeast(0L), json.get("mega")?.asBoolean ?: false,
                json.get("species")?.asString, aspects, json.get("reports")?.asInt ?: 1, json.get("delay_ms")?.asLong))
        }
    }

    /** Asks about the raid; [onAnswer] runs on a network thread, and only when someone knows something. */
    fun ask(slot: Long, onAnswer: (Answer) -> Unit) {
        if (!ModConfig.raidShare) return
        send(HttpRequest.newBuilder(URI.create("$base/raid?slot=$slot")).GET()) { text ->
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
