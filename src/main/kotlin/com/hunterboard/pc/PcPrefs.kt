package com.hunterboard.pc

import com.hunterboard.HunterBoard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class PcTag(val id: String, val name: String)

@Serializable
data class PcSavedSearch(val name: String, val filter: PcFilterState)

/** One Pokémon of a saved team and the moves it should have once equipped (empty = leave them). */
@Serializable
data class PcTeamMember(val uuid: String, val moves: List<String> = emptyList())

/**
 * A saved team: six places in party order. A place can stay empty (a member with a blank
 * uuid keeps the gap), so a Pokémon stays where it was dropped.
 */
@Serializable
data class PcTeam(val name: String, val members: List<PcTeamMember> = emptyList()) {
    /** The six places, null where empty. */
    fun slots(): List<PcTeamMember?> = List(6) { i -> members.getOrNull(i)?.takeIf { it.uuid.isNotBlank() } }
    /** Its Pokémon in party order, the empty places skipped. */
    fun filled(): List<PcTeamMember> = members.filter { it.uuid.isNotBlank() }.take(6)
    fun withSlots(slots: List<PcTeamMember?>): PcTeam =
        copy(members = slots.take(6).map { it ?: PcTeamMember("") }.dropLastWhile { it.uuid.isBlank() })
}

/**
 * What the release candidates look at and what keeps a Pokémon out of them, beside the
 * older [PcPrefs.keepPerSpecies], [PcPrefs.protectIvPct] and [PcPrefs.protectHidden].
 */
@Serializable
data class ReleaseRules(
    /** "all", "selection", "search" (the left column's), "species:<lineKey>", "type:<type>". */
    val scope: String = "all",
    /** Kept: those with every quality set (true), or with at least one (false). */
    val matchAll: Boolean = false,
    /** Wanted IV per stat: -1 ignored, 0 exactly 0, else at least that. All set ones count as one quality. */
    val ivs: List<Int> = listOf(-1, -1, -1, -1, -1, -1),
    /** "" ignored, else an ability id. */
    val ability: String = "",
    /** "" ignored, else a nature id. */
    val nature: String = "",
    /** 0 ignored, 1 male, 2 female. */
    val gender: Int = 0,
    /** What is never proposed (favourites and the party never are). */
    val guards: ReleaseGuards = ReleaseGuards(),
)

@Serializable
data class PcPrefsData(
    val favorites: Set<String> = emptySet(),
    /** Pokémon UUID → tag ids. */
    val tags: Map<String, Set<String>> = emptyMap(),
    val customTags: List<PcTag> = emptyList(),
    val searches: List<PcSavedSearch> = emptyList(),
    /** Pokémon UUID → when this client first saw it (epoch ms), 0 = known but date unknown. */
    val firstSeen: Map<String, Long> = emptyMap(),
    val keepPerSpecies: Int = 1,
    val protectIvPct: Int = 80,
    val protectHidden: Boolean = true,
    val view: String = "boxes",
    val dim: Boolean = true,
    val filter: PcFilterState = PcFilterState(),
    /** Box index → rule ("shiny", "special", "alpha", "favorite", "tag:<id>", "type:<type>"). */
    val boxRules: Map<String, String> = emptyMap(),
    val teams: List<PcTeam> = emptyList(),
    val release: ReleaseRules = ReleaseRules(),
)

/**
 * The PC's own memory, kept per Minecraft account (the file name is a hash of the account
 * UUID, never the UUID itself): favourites, tags, saved searches, the release-candidate
 * settings and when each Pokémon was first seen.
 *
 * Nothing here is sent anywhere: it is what lets the screen say "caught in the last 24 h"
 * when Cobblemon keeps no capture date.
 */
class PcPrefs private constructor(private val path: Path?, d: PcPrefsData, private val fresh: Boolean) {
    private val favorites = d.favorites.toMutableSet()
    // "breed" was a built-in tag until 2026-11-21: dropped from Pokémon that still carry it
    private val tags = d.tags.mapValues { (_, ids) -> ids.filterTo(mutableSetOf()) { it !in RETIRED } }
        .filterValues { it.isNotEmpty() }.toMutableMap()
    private val custom = d.customTags.toMutableList()
    private val searchList = d.searches.toMutableList()
    private val seen = d.firstSeen.toMutableMap()
    var keepPerSpecies = d.keepPerSpecies.coerceIn(0, 6); set(v) { field = v.coerceIn(0, 6); dirty() }
    var protectIvPct = d.protectIvPct.coerceIn(0, 100); set(v) { field = v.coerceIn(0, 100); dirty() }
    var protectHidden = d.protectHidden; set(v) { field = v; dirty() }
    var view = d.view; set(v) { field = v; dirty() }
    var dim = d.dim; set(v) { field = v; dirty() }
    var filter = d.filter; set(v) { field = v; dirty() }
    private val rules = d.boxRules.toMutableMap()
    private val teamList = d.teams.toMutableList()
    var release = d.release; set(v) { if (field != v) { field = v; dirty() } }

    private var changed = false
    private var lastSave = 0L
    /** Stores whose content was taken as the starting point this session (first run only). */
    private val baselined = mutableSetOf<UUID>()

    private fun dirty() { changed = true }

    // ── favourites ──
    fun isFavorite(u: UUID) = u.toString() in favorites
    fun setFavorite(u: UUID, on: Boolean) { if (if (on) favorites.add(u.toString()) else favorites.remove(u.toString())) dirty() }

    // ── tags ──
    fun allTags(): List<PcTag> = BUILTIN + custom
    fun tagName(id: String) = allTags().firstOrNull { it.id == id }?.name ?: id
    fun tagsOf(u: UUID): Set<String> = tags[u.toString()] ?: emptySet()
    fun setTag(u: UUID, tag: String, on: Boolean) {
        val set = tags.getOrPut(u.toString()) { mutableSetOf() }
        if (if (on) set.add(tag) else set.remove(tag)) dirty()
        if (set.isEmpty()) tags.remove(u.toString())
    }
    fun clearTags(u: UUID) { if (tags.remove(u.toString()) != null) dirty() }
    fun customTags(): List<PcTag> = custom

    // ── saved teams ──
    fun teams(): List<PcTeam> = teamList
    /** Adds or replaces (same index) a team; false when the name is empty or 30 already exist. */
    fun saveTeam(team: PcTeam, index: Int = -1): Boolean {
        val t = team.copy(name = team.name.trim().take(32), members = team.members.take(6))
        if (t.name.isEmpty()) return false
        if (index in teamList.indices) teamList[index] = t
        else { if (teamList.size >= 30) return false; teamList += t }
        dirty(); return true
    }
    fun deleteTeam(index: Int) { if (index in teamList.indices) { teamList.removeAt(index); dirty() } }
    fun moveTeam(from: Int, to: Int) {
        if (from !in teamList.indices || to !in teamList.indices || from == to) return
        teamList.add(to, teamList.removeAt(from)); dirty()
    }

    // ── box labels: a box given a rule receives the Pokémon matching it when the PC is arranged ──
    fun boxRule(box: Int): String? = rules[box.toString()]
    fun boxRules(): Map<Int, String> = rules.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
    fun setBoxRule(box: Int, rule: String?) {
        if (rule == null) { if (rules.remove(box.toString()) != null) dirty() }
        else if (rules.put(box.toString(), rule) != rule) dirty()
    }
    /** Creates a personal tag; null when the name is taken, empty, too long or the limit is reached. */
    fun createTag(name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n.length > 24 || custom.size >= 25 || allTags().any { it.name.equals(n, true) }) return null
        val id = "c" + System.currentTimeMillis().toString(36)
        custom += PcTag(id, n); dirty(); return id
    }
    fun renameTag(id: String, name: String): Boolean {
        val n = name.trim()
        val i = custom.indexOfFirst { it.id == id }
        if (i < 0 || n.isEmpty() || n.length > 24 || allTags().any { it.id != id && it.name.equals(n, true) }) return false
        custom[i] = PcTag(id, n); dirty(); return true
    }
    fun deleteTag(id: String) {
        if (custom.removeIf { it.id == id }) {
            tags.values.forEach { it.remove(id) }
            tags.entries.removeIf { it.value.isEmpty() }
            dirty()
        }
    }

    // ── saved searches ──
    fun searches(): List<PcSavedSearch> = searchList
    /** Saves (or replaces, same name) a search; false when the name is empty or 20 already exist. */
    fun saveSearch(name: String, f: PcFilterState): Boolean {
        val n = name.trim().take(40)
        if (n.isEmpty()) return false
        val i = searchList.indexOfFirst { it.name.equals(n, true) }
        if (i >= 0) searchList[i] = PcSavedSearch(n, f)
        else { if (searchList.size >= 20) return false; searchList += PcSavedSearch(n, f) }
        dirty(); return true
    }
    fun deleteSearch(name: String) { if (searchList.removeIf { it.name == name }) dirty() }

    // ── arrival history ──
    fun firstSeen(u: UUID): Long = seen[u.toString()] ?: 0L

    /**
     * Records the Pokémon of one store. The very first time this account is seen with the
     * mod, every Pokémon already there is "known, date unknown" — dating them all to today
     * would make the age filter lie.
     */
    fun observe(store: UUID, ids: Collection<UUID>, now: Long) {
        val asBaseline = fresh && baselined.add(store)
        for (u in ids) {
            val k = u.toString()
            if (k !in seen) { seen[k] = if (asBaseline) 0L else now; changed = true }
        }
    }

    fun saveIfDue(now: Long) { if (changed && now - lastSave > 10_000) save() }

    fun save() {
        val p = path ?: return
        if (!changed) return
        try {
            val data = PcPrefsData(favorites, tags.mapValues { it.value.toSet() }, custom, searchList, seen,
                keepPerSpecies, protectIvPct, protectHidden, view, dim, filter, rules.toMap(), teamList.toList(), release)
            Files.createDirectories(p.parent)
            val tmp = p.resolveSibling(p.fileName.toString() + ".tmp")
            Files.writeString(tmp, JSON.encodeToString(PcPrefsData.serializer(), data))
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            changed = false
            lastSave = System.currentTimeMillis()
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("[PC] Could not save PC preferences: ${e.message}")
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private val RETIRED = setOf("breed")

        val BUILTIN: List<PcTag> get() = listOf(
            PcTag("sell", L("To sell", "À vendre")),
            PcTag("moves", L("Moveset to do", "Moveset à faire")),
            PcTag("evs", L("EV training", "À entraîner (EV)")),
            PcTag("pvp", "PvP"),
            PcTag("raid", "Raid"),
        )

        @Volatile private var current: PcPrefs? = null
        private var account: UUID? = null

        /** The preferences of the account in game, loaded on first use. */
        fun get(): PcPrefs {
            val acc = runCatching { MinecraftClient.getInstance().session.uuidOrNull }.getOrNull()
            current?.let { if (acc == account) return it }
            current?.save()
            account = acc
            return load(acc).also { current = it }
        }

        fun flush() { current?.save() }

        /** Preferences that are never written: the tour, or no account to file them under. */
        fun detached(): PcPrefs = PcPrefs(null, PcPrefsData(), false)

        private fun load(acc: UUID?): PcPrefs {
            if (acc == null) return detached()
            val hash = MessageDigest.getInstance("SHA-256").digest("hunterboard-pc\n$acc".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(32)
            val path = FabricLoader.getInstance().configDir.resolve("hunterboard").resolve("pc").resolve("$hash.json")
            if (!Files.exists(path)) return PcPrefs(path, PcPrefsData(), true)
            return try {
                PcPrefs(path, JSON.decodeFromString(PcPrefsData.serializer(), Files.readString(path)), false)
            } catch (e: Exception) {
                // keep the unreadable file aside rather than overwrite the player's favourites
                HunterBoard.LOGGER.error("[PC] Unreadable PC preferences, kept aside: ${e.message}")
                runCatching { Files.move(path, path.resolveSibling("$hash.json.broken"), StandardCopyOption.REPLACE_EXISTING) }
                PcPrefs(path, PcPrefsData(), true)
            }
        }
    }
}
