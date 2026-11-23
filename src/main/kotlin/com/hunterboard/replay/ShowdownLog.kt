package com.hunterboard.replay

/**
 * Builds a Pokémon Showdown protocol log, the text a Showdown replay is made of.
 *
 * Cobblemon runs the real Showdown simulator, and what it sends the client is that
 * simulator's output dressed up as translatable chat: the keys are
 * `cobblemon.battle.<action>.<effect>`, where `<action>` is a Showdown minor action
 * (`damage`, `heal`, `boost`, `sidestart`, `fieldstart`, `singleturn`…) and `<effect>` is a
 * Showdown effect id (`leechseed`, `lifeorb`, `rockyhelmet`, `sandstorm`…). Of the 550
 * `cobblemon.battle.*` keys in Cobblemon 1.8.1, every one of the 24 top-level groups is a
 * protocol action, which is what makes this translation possible at all rather than a
 * guess at prose.
 *
 * This class holds no Cobblemon types on purpose: it takes names and numbers, so the
 * mapping can be exercised without a running game. [ReplayRecorder] is the half that
 * speaks to Cobblemon.
 */
class ShowdownLog {

    private val lines = mutableListOf<String>()

    /** Nickname currently in each active slot, so minor actions can name their target. */
    private val activeNick = linkedMapOf<String, String>()

    /**
     * Display name -> slot ident, for the message stream.
     *
     * Battle messages identify a Pokémon by its display name, not by slot, so the only way
     * back to `p1a:` is the name it was sent out under. Both sides can field the same
     * species, so this is keyed on the full display name and refreshed on every switch.
     */
    private val identByName = linkedMapOf<String, String>()

    private var turn = 0
    private var started = false

    /** p1/p2 -> player name, for side-wide effects and for the winner line. */
    private val playerName = linkedMapOf<String, String>()

    /** Which side the local player is on, so "opponent.*" effects land on the right one. */
    var myside: String = "p1"

    /**
     * Slots already announced as fainted.
     *
     * A faint reaches the client twice — once as a packet and once as narration — and both
     * were being written, so every KO appeared twice in the replay. The flag is cleared
     * when something new is sent out into the slot.
     */
    private val fainted = linkedSetOf<String>()

    /** Distinct Pokémon seen per side, which is the only honest team size available. */
    private val seenBySide = linkedMapOf("p1" to linkedSetOf<String>(), "p2" to linkedSetOf<String>())

    val size: Int get() = lines.size
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Cobblemon's battle packet announces how many Pokémon are *on the field*, not how many
     * are in the party, so an earlier version wrote `|teamsize|p1|1` for every singles match
     * and Showdown drew a single Poké Ball per player. The real count is only known once the
     * battle is over and every switch has been seen, so the header keeps a placeholder and
     * it is filled in here.
     */
    fun build(): String = lines.joinToString("\n") { line ->
        when {
            line.startsWith(TEAMSIZE_MARK) -> {
                val side = line.removePrefix(TEAMSIZE_MARK)
                "|teamsize|$side|${seenBySide[side]?.size?.coerceAtLeast(1) ?: 1}"
            }
            else -> line
        }
    }

    private fun add(line: String) {
        lines.add(line)
    }

    // ---------------------------------------------------------------- preamble

    /**
     * Showdown refuses to render a replay that does not declare its players and shape
     * first, so this has to be emitted before any event.
     */
    fun header(p1: String, p2: String, format: String, doubles: Boolean) {
        playerName["p1"] = p1
        playerName["p2"] = p2
        add("|j|☆$p1")
        add("|j|☆$p2")
        add("|player|p1|$p1|1|")
        add("|player|p2|$p2|2|")
        add("${TEAMSIZE_MARK}p1")
        add("${TEAMSIZE_MARK}p2")
        add("|gametype|${if (doubles) "doubles" else "singles"}")
        add("|tier|$format")
        add("|rule|Cobblemon: battle recorded by HunterBoard")
    }

    fun start() {
        if (started) return
        started = true
        add("|")
        add("|start")
    }

    // ------------------------------------------------------------------ events

    /**
     * A new turn, as the server numbered it.
     *
     * Cobblemon announces turns itself (`cobblemon.battle.turn`, "It is now turn %1$s"), so
     * the number is taken from the server rather than counted locally — a replay that
     * renumbers turns after a dropped packet would be misleading in exactly the situation
     * where the player wants to check what happened.
     */
    fun turnNumber(n: Int) {
        start()
        if (n <= turn) return
        turn = n
        add("|")
        add("|turn|$n")
    }

    /** Fallback when no number was parsed: advance by one. */
    fun nextTurn() = turnNumber(turn + 1)

    /** `|move|p1a: Nick|Thunderbolt|p2a: Nick`, addressed by display name. */
    fun moveByName(userName: String, moveName: String, targetName: String?) {
        start()
        val from = identWithNickByName(userName) ?: return
        val target = targetName?.let { identWithNickByName(it) }
        add("|move|$from|$moveName|${target ?: ""}")
    }

    /** `|faint|p1a: Nick`, addressed by display name. */
    fun faintByName(name: String) {
        val slot = resolveIdent(name) ?: return
        if (!fainted.add(slot)) return
        val withNick = identWithNickByName(name) ?: return
        add("|faint|$withNick")
    }

    /**
     * `|switch|p1a: Nick|Species, L50, F|120/200`
     *
     * Also the line that teaches the log which nickname sits in which slot, so every later
     * event about this Pokémon can be attributed.
     */
    fun switchIn(
        pnx: String,
        nickname: String,
        species: String,
        level: Int,
        gender: String?,
        shiny: Boolean,
        hp: Int,
        maxHp: Int,
    ) {
        val ident = identOf(pnx)
        activeNick[ident] = nickname
        identByName[nickname] = ident
        seenBySide[ident.take(2)]?.add(nickname)
        fainted.remove(ident)

        val details = buildString {
            append(speciesName(species))
            if (level > 0) append(", L").append(level)
            if (gender == "M" || gender == "F") append(", ").append(gender)
            if (shiny) append(", shiny")
        }
        add("|switch|$ident: $nickname|$details|$hp/$maxHp")
    }

    /** `|move|p1a: Nick|Thunderbolt|p2a: Nick` */
    fun move(pnx: String, moveName: String, targetPnx: String?) {
        val from = identWithNick(pnx) ?: return
        val target = targetPnx?.let { identWithNick(it) }
        add("|move|$from|$moveName|${target ?: ""}")
    }

    /**
     * Health changes come from Cobblemon as an absolute value, while Showdown wants to know
     * whether it went down or up to pick the animation — so the direction is derived here
     * from the previous value rather than assumed.
     */
    fun health(pnx: String, hp: Int, maxHp: Int, previousHp: Int?, cause: String?) {
        val ident = identWithNick(pnx) ?: return
        val action = if (previousHp != null && hp > previousHp) "-heal" else "-damage"
        val suffix = cause?.let { "|[from] $it" } ?: ""
        add("|$action|$ident|$hp/$maxHp$suffix")
    }

    fun faint(pnx: String) {
        val ident = identOf(pnx)
        if (!fainted.add(ident)) return
        val withNick = identWithNick(pnx) ?: return
        add("|faint|$withNick")
    }

    fun win(playerName: String) {
        add("|")
        add("|win|$playerName")
    }

    fun tie() {
        add("|")
        add("|tie")
    }

    /**
     * A minor action rebuilt from a battle message, e.g.
     * `|-damage|p2a: Nick||[from] item: Life Orb` or `|-boost|p1a: Nick|atk|2`.
     *
     * @param action the Showdown action without its leading dash, as taken from the key
     * @param args   the trailing fields, already in protocol order
     */
    fun minor(action: String, subjectName: String?, args: List<String>, fromEffect: String?) {
        val ident = subjectName?.let { resolveIdent(it) }
        val head = ident?.let { "$it: ${activeNick[it] ?: subjectName}" } ?: (subjectName ?: "")
        // Cobblemon substitutes a literal placeholder for a value the client is not told —
        // both spellings, one of which is its own typo. Passing it through produced lines
        // like `|-activate|…|UNKNOWN|UNKNOWN`, which Showdown reads as real field values.
        val tail = args.filter { it.isNotEmpty() && !isPlaceholder(it) }.joinToString("|")
        val from = fromEffect?.let { "|[from] $it" } ?: ""
        add("|-$action|$head${if (tail.isEmpty()) "" else "|$tail"}$from")
    }

    private fun isPlaceholder(s: String): Boolean =
        s.equals("UNKNOWN", true) || s.equals("UNKOWN", true)

    /**
     * Anything this translator does not recognise, kept as the sentence the player saw.
     *
     * Showdown renders `|-message|` verbatim in the battle log, so an event we cannot map
     * to a protocol action still reaches the replay instead of being dropped. Cobblemon
     * tracks Showdown closely but not perfectly, and new effects arrive with every update,
     * so a lossless fallback matters more here than a tidy log.
     */
    fun message(text: String) {
        if (text.isBlank()) return
        add("|-message|$text")
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Cobblemon's slot id, as Showdown spells it.
     *
     * Cobblemon calls this a "pnx" and reaches it through several layers of Kotlin
     * interface defaults, so rather than depend on one spelling this accepts both the
     * Showdown form it normally is (`p1a`) and an actor-index form (`0a`), and falls back
     * to the first slot rather than dropping the event.
     */
    fun identOf(pnx: String): String {
        val p = pnx.trim()
        if (p.length >= 3 && p[0] == 'p' && (p[1] == '1' || p[1] == '2')) return p.substring(0, 3)
        if (p.length >= 2 && p[0].isDigit()) return "p${p[0].digitToInt() + 1}${p[1]}"
        return "p1a"
    }

    /** `p1a: Nick`, or null when nothing has been sent out in that slot yet. */
    private fun identWithNick(pnx: String): String? {
        val ident = identOf(pnx)
        val nick = activeNick[ident] ?: return null
        return "$ident: $nick"
    }

    /**
     * `p1a: Nick` for a Pokémon the message stream named.
     *
     * Battle messages carry the display name only, so a Pokémon that has not been seen
     * switching in cannot be placed on a side. Returning null drops that one event rather
     * than inventing a slot, which would attribute it to the wrong player.
     */
    private fun identWithNickByName(name: String): String? {
        val ident = resolveIdent(name) ?: return null
        return "$ident: ${activeNick[ident] ?: name}"
    }

    /**
     * Finds the slot a message is talking about.
     *
     * Messages do not always name a Pokémon the way the switch packet did: Cobblemon
     * disambiguates between sides in prose, so the same Frosmoth arrives as "Beldeneige" on
     * one line and "Beldeneige de Manifestante" on the next. Matching the longest known
     * nickname contained in the argument covers both, and preferring the longest avoids a
     * short nickname matching inside a longer one.
     */
    private fun resolveIdent(name: String): String? {
        identByName[name]?.let { return it }
        val trimmed = name.trim()
        identByName[trimmed]?.let { return it }
        return identByName.entries
            .filter { trimmed.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /**
     * `|-boost|p1a: Nick|spe|1`
     *
     * Cobblemon puts the magnitude in the key rather than in an argument — `boost.slight`,
     * `boost.sharp`, `boost.severe` for one, two and three stages — and passes the stat as
     * its *translated* name. Both have to be converted, or the replay ends up with
     * `|-boost|…|Défense Spéciale|[from] slight`, which Showdown cannot read.
     */
    fun boost(action: String, subjectName: String?, statLabel: String?, magnitude: String?) {
        val ident = subjectName?.let { identWithNickByName(it) } ?: return
        val stat = statLabel?.let { STAT_IDS[it.trim()] } ?: return
        val stages = when (magnitude?.substringBefore('.')) {
            "severe" -> 3
            "sharp" -> 2
            else -> 1
        }
        add("|-$action|$ident|$stat|$stages")
    }

    /** `|-sidestart|p2: Name|move: Light Screen` */
    fun sideEffect(action: String, effect: String?) {
        if (effect == null) return
        val mine = effect.startsWith("ally.") || effect.startsWith("self.")
        val side = if (mine) myside else otherSide(myside)
        val name = playerName[side] ?: side
        val raw = effect.substringAfter('.', effect)
        add("|-$action|$side: $name|${prettyEffect(raw)}")
    }

    private fun otherSide(s: String) = if (s == "p1") "p2" else "p1"

    /** `lightscreen` -> `move: Light Screen`, which is how Showdown labels side effects. */
    private fun prettyEffect(id: String): String =
        "move: " + id.split('_', '-').joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }

    /** Registers the translated stat names, so the reverse lookup works in any language. */
    fun registerStatLabels(labels: Map<String, String>) {
        for ((showdownId, label) in labels) STAT_IDS[label] = showdownId
    }

    /** `great_tusk` -> `Great Tusk`, which is how Showdown names a species. */
    private fun speciesName(id: String): String =
        id.substringAfter(':').split('_', '-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    companion object {
        /**
         * Splits `cobblemon.battle.<action>.<effect>` into the two halves the protocol needs.
         *
         * The first segment after the prefix is the action; whatever follows is the effect
         * that caused it, which Showdown wants as a `[from]` tag. A key with no effect
         * segment is a plain action.
         */
        fun splitKey(key: String): Pair<String, String?>? {
            if (!key.startsWith(PREFIX)) return null
            val rest = key.removePrefix(PREFIX)
            if (rest.isEmpty()) return null
            val dot = rest.indexOf('.')
            return if (dot < 0) rest to null
            else rest.substring(0, dot) to rest.substring(dot + 1)
        }

        /** Actions Cobblemon spells the same way Showdown does. */
        val MINOR_ACTIONS = setOf(
            "damage", "heal", "boost", "unboost", "status", "curestatus", "cureteam",
            "start", "end", "ability", "endability", "item", "enditem", "activate",
            "fail", "block", "immune", "miss", "crit", "supereffective", "resisted",
            "sidestart", "sideend", "fieldstart", "fieldend", "fieldactivate",
            "weather", "singleturn", "singlemove", "formechange", "transform",
            "types", "prepare", "mustrecharge", "hitcount", "zpower", "terastallize",
        )

        const val PREFIX = "cobblemon.battle."

        /** Stands in for a team size that is not known until the battle has ended. */
        const val TEAMSIZE_MARK = "\u0000teamsize\u0000"

        /**
         * Translated stat name -> Showdown stat id.
         *
         * Seeded with English because that is what an unlocalised client shows, and filled
         * in at runtime from Cobblemon's own `cobblemon.stat.*.name` strings so a French —
         * or any other — client resolves "Défense Spéciale" just as well.
         */
        val STAT_IDS: MutableMap<String, String> = linkedMapOf(
            "Attack" to "atk",
            "Defense" to "def",
            "Sp. Atk" to "spa",
            "Sp. Def" to "spd",
            "Speed" to "spe",
            "Accuracy" to "accuracy",
            "Evasion" to "evasion",
        )

        /** Cobblemon stat keys, paired with the Showdown id each one means. */
        val STAT_KEYS = linkedMapOf(
            "atk" to "cobblemon.stat.attack.name",
            "def" to "cobblemon.stat.defence.name",
            "spa" to "cobblemon.stat.special_attack.name",
            "spd" to "cobblemon.stat.special_defence.name",
            "spe" to "cobblemon.stat.speed.name",
            "accuracy" to "cobblemon.stat.accuracy.name",
            "evasion" to "cobblemon.stat.evasion.name",
        )
    }
}
