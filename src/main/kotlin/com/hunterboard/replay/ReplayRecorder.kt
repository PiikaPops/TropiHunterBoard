package com.hunterboard.replay

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.net.messages.client.battle.BattleFaintPacket
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket
import com.cobblemon.mod.common.net.messages.client.battle.BattleSwitchPokemonPacket
import com.hunterboard.HunterBoard
import com.hunterboard.ModConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import net.minecraft.text.TranslatableTextContent
import net.minecraft.util.Formatting
import java.nio.file.Path

/**
 * Records a player-versus-player Cobblemon battle as a Showdown replay.
 *
 * Cobblemon drives the real Showdown simulator server-side and forwards its output to the
 * client in two streams, and this listens to both because neither alone is enough:
 *
 *  - the **message stream** (`BattleMessagePacket`) carries the simulator's narration as
 *    translatable keys — `used_move`, `turn`, `fainted`, `win`, and the 550
 *    `cobblemon.battle.*` keys whose names are Showdown's own action and effect ids. This
 *    is where the ordering and the semantics live.
 *  - the **state packets** (switch, health, faint) carry the numbers the narration leaves
 *    out: species, level, exact HP. A replay without those cannot draw a health bar.
 *
 * Only battles where every actor is a player are recorded, which is the definition of PvP
 * that does not depend on Tropimon's team-select screen being recognised.
 */
object ReplayRecorder {

    private var log: ShowdownLog? = null
    private var p1 = ""
    private var p2 = ""
    private var format = "Cobblemon"
    private var winner: String? = null

    /** Last known HP per slot, so a health change can be told from a heal. */
    private val lastHp = mutableMapOf<String, Int>()

    /**
     * Maximum HP per slot, and whether that slot reports HP as a real number.
     *
     * Cobblemon sends your own Pokémon's exact HP but only a 0..1 fraction for the
     * opponent's, and it leaves `newMaxHealth` out of a health packet whenever the maximum
     * has not changed. Taking a missing maximum to mean "this is a fraction" turned an
     * absolute value like 180 into 180 × 100, which clamped to a full bar — so damage
     * appeared not to land, and the next update that did carry a maximum dropped the bar by
     * everything at once. The convention therefore has to be remembered per slot.
     */
    private val slotMax = mutableMapOf<String, Int>()
    private val slotFlat = mutableMapOf<String, Boolean>()

    /** The replay written by the last finished battle, for the "open last replay" action. */
    var lastReplay: Path? = null
        private set

    val isRecording: Boolean get() = log != null

    // -------------------------------------------------------------- lifecycle

    fun onInit(packet: BattleInitializePacket) {
        reset()
        if (!ModConfig.pvpReplayEnabled) return
        try {
            val side1 = packet.side1.actors
            val side2 = packet.side2.actors
            if (side1.isEmpty() || side2.isEmpty()) return

            // Every actor a player: that rules out wild encounters and NPC trainers, whose
            // replays nobody asked for and which would otherwise fill the folder.
            val allPlayers = (side1 + side2).all { it.type == ActorType.PLAYER }
            if (!allPlayers) return

            p1 = side1.first().displayName.string
            p2 = side2.first().displayName.string
            val type = packet.battleFormat.battleType
            val doubles = type.slotsPerActor > 1
            format = buildFormatLabel(packet)

            val fresh = ShowdownLog()
            fresh.header(p1 = p1, p2 = p2, format = format, doubles = doubles)

            // Which side is the player watching from. Side 1 is not always theirs — a
            // challenge they accepted puts them second — and "opponent.*" effects in the
            // message stream are named relative to them, so this has to be resolved rather
            // than assumed.
            val me = MinecraftClient.getInstance()?.player?.uuid
            fresh.myside = if (me != null && side2.any { it.uuid == me }) "p2" else "p1"

            // Stat names arrive translated, so the reverse lookup is built from the same
            // strings this client will actually receive.
            fresh.registerStatLabels(
                ShowdownLog.STAT_KEYS.mapValues { (_, key) -> Text.translatable(key).string }
                    .entries.associate { (id, label) -> id to label }
            )
            fresh.start()
            log = fresh
            HunterBoard.LOGGER.info("[Replay] recording $p1 vs $p2 ($format)")
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("[Replay] could not start recording: ${e.message}")
            log = null
        }
    }

    /**
     * Cobblemon's format has no Showdown tier name, so one is composed from the generation
     * and battle type. It is only a label in the replay's header.
     */
    private fun buildFormatLabel(packet: BattleInitializePacket): String = try {
        val f = packet.battleFormat
        val kind = if (f.battleType.slotsPerActor > 1) "Doubles" else "Singles"
        "[Gen ${f.gen}] Cobblemon $kind"
    } catch (_: Exception) {
        "Cobblemon"
    }

    fun onEnd() {
        val current = log ?: return
        log = null
        try {
            winner?.let { current.win(it) } ?: current.tie()
            if (current.isEmpty) return
            // One page, used for both the file and the upload — see ReplayWriter.buildPage.
            val page = ReplayWriter.buildPage(current.build(), p1, p2, format)
            val file = ReplayWriter.writePage(page, p1, p2)
            lastReplay = file
            if (file != null) announce(file, p1, p2)
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("[Replay] could not finish replay: ${e.message}")
        } finally {
            reset()
        }
    }

    /**
     * Tells the player the replay exists, with a click that opens it.
     *
     * The file is the whole deliverable now that nothing is published: without a line in
     * chat a finished replay would be a file the player has to go looking for.
     */
    private fun announce(file: Path, p1: String, p2: String) {
        val client = MinecraftClient.getInstance() ?: return
        val message = Text.literal("[HunterBoard] ").formatted(Formatting.GOLD)
            .append(Text.literal("replay : ").formatted(Formatting.GRAY))
            .append(
                Text.literal("$p1 vs $p2").styled {
                    it.withColor(0x55FFFF).withUnderline(true)
                        .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_FILE, file.toString()))
                        .withHoverEvent(
                            HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(file.toString()))
                        )
                }
            )
        client.execute { client.inGameHud?.chatHud?.addMessage(message) }
    }

    fun reset() {
        log = null
        winner = null
        lastHp.clear()
        slotMax.clear()
        slotFlat.clear()
        p1 = ""; p2 = ""
        format = "Cobblemon"
    }

    // ------------------------------------------------------------ state packets

    fun onSwitch(packet: BattleSwitchPokemonPacket) {
        val l = log ?: return
        try {
            val dto = packet.newPokemon
            val props = dto.properties
            val hp = dto.hpValue
            val maxHp = dto.maxHp

            // Cobblemon sends either absolute HP or a 0..1 fraction depending on whether the
            // viewer is allowed to see the real numbers (isFlatHp). Showdown wants integers,
            // so a fraction is scaled to a nominal 100 rather than rounded to 0/1.
            val (cur, max) = hpPair(hp, maxHp, dto.isFlatHp)
            val ident = l.identOf(packet.pnx)
            slotFlat[ident] = dto.isFlatHp && maxHp > 1f
            slotMax[ident] = max

            l.switchIn(
                pnx = packet.pnx,
                nickname = dto.displayName.string,
                species = props.species ?: "unknown",
                level = props.level ?: 0,
                gender = props.gender?.name?.take(1),
                shiny = props.shiny == true,
                hp = cur,
                maxHp = max,
            )
            lastHp[l.identOf(packet.pnx)] = cur
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("[Replay] switch not recorded: ${e.message}")
        }
    }

    fun onHealth(packet: BattleHealthChangePacket) {
        val l = log ?: return
        try {
            val ident = l.identOf(packet.pnx)
            val sent = packet.newMaxHealth
            val hp = packet.newHealth

            // A value above 1 cannot be a fraction, which catches a slot whose switch was
            // never seen — the last way this could silently clamp a health bar to full.
            val flat = sent != null || slotFlat[ident] == true || hp > 1f
            val max = sent?.toInt()?.coerceAtLeast(1) ?: slotMax[ident] ?: 100
            if (sent != null) {
                slotMax[ident] = max
                slotFlat[ident] = true
            }

            val cur: Int
            val denominator: Int
            if (flat) {
                cur = hp.toInt().coerceIn(0, max)
                denominator = max
            } else {
                cur = (hp * 100f).toInt().coerceIn(0, 100)
                denominator = 100
            }
            l.health(packet.pnx, cur, denominator, lastHp[ident], null)
            lastHp[ident] = cur
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("[Replay] health not recorded: ${e.message}")
        }
    }

    fun onFaint(packet: BattleFaintPacket) {
        val l = log ?: return
        try {
            l.faint(packet.pnx)
            lastHp[l.identOf(packet.pnx)] = 0
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("[Replay] faint not recorded: ${e.message}")
        }
    }

    /** Scales Cobblemon's two HP conventions onto whole numbers Showdown can draw. */
    private fun hpPair(hp: Float, maxHp: Float, flat: Boolean): Pair<Int, Int> =
        if (flat && maxHp > 1f) {
            hp.toInt().coerceIn(0, maxHp.toInt()) to maxHp.toInt().coerceAtLeast(1)
        } else {
            val pct = (hp * 100f).toInt().coerceIn(0, 100)
            pct to 100
        }

    // ----------------------------------------------------------- message stream

    fun onMessages(packet: BattleMessagePacket) {
        val l = log ?: return
        for (message in packet.messages) {
            try {
                handle(l, message)
            } catch (e: Exception) {
                HunterBoard.LOGGER.debug("[Replay] message not recorded: ${e.message}")
            }
        }
    }

    private fun handle(l: ShowdownLog, message: Text) {
        val content = message.content
        if (content !is TranslatableTextContent) {
            l.message(message.string)
            return
        }
        val split = ShowdownLog.splitKey(content.key)
        if (split == null) {
            l.message(message.string)
            return
        }
        val (action, effect) = split
        val args = content.args.map { argText(it) }

        when (action) {
            // ---- the structural actions, which carry the battle's shape
            "used_move" -> l.moveByName(args.getOrElse(0) { "" }, args.getOrElse(1) { "" }, null)
            "used_move_on" -> l.moveByName(
                args.getOrElse(0) { "" }, args.getOrElse(1) { "" }, args.getOrNull(2)
            )
            "turn" -> args.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull()
                ?.let { l.turnNumber(it) } ?: l.nextTurn()
            "fainted" -> l.faintByName(args.getOrElse(0) { "" })
            "win" -> winner = args.getOrElse(0) { "" }
            "forfeit" -> {
                // The forfeiter loses, so the winner is the other name on the card.
                val quitter = args.getOrElse(0) { "" }
                winner = if (quitter == p1) p2 else p1
                l.message(message.string)
            }
            // ---- effectiveness and accuracy: no subject, Showdown infers it
            "crit" -> l.minor("crit", null, emptyList(), null)
            "missed" -> l.minor("miss", null, emptyList(), null)
            "superEffective" -> l.minor("supereffective", null, emptyList(), null)
            "resisted" -> l.minor("resisted", null, emptyList(), null)
            "crit_spread" -> l.minor("crit", args.getOrNull(0), emptyList(), null)
            "superEffective_spread" -> l.minor("supereffective", args.getOrNull(0), emptyList(), null)
            "resisted_spread" -> l.minor("resisted", args.getOrNull(0), emptyList(), null)
            "immune" -> l.minor("immune", args.getOrNull(0), emptyList(), null)
            // ---- stat stages: the amount is in the key, the stat arrives translated
            "boost", "unboost" -> {
                // "boost.cap.*" says a stat would not go further, which is not a change.
                if (effect?.startsWith("cap") == true) l.message(message.string)
                else l.boost(action, args.getOrNull(0), args.getOrNull(1), effect)
            }
            // ---- side conditions belong to a player, not to a Pokémon
            "sidestart", "sideend" -> l.sideEffect(action, effect)
            // ---- the narration of a health change carries its cause but not its numbers:
            //      Cobblemon substitutes the literal "UNKOWN" for HP the client may not see.
            //      The health packet is the authority, so only the cause is kept, as text.
            "damage", "heal" -> l.message(message.string)
            // ---- switches are recorded from the packet, which has the species and HP;
            //      repeating them here would show each Pokémon entering twice
            "switch", "withdraw", "dragged_out" -> l.message(message.string)
            // ---- anything whose key is already a Showdown action
            in ShowdownLog.MINOR_ACTIONS -> {
                val subject = args.getOrNull(0)
                val rest = args.drop(1)
                l.minor(action, subject, rest, effect?.takeIf { it != "generic" })
            }
            // ---- prompts and UI chatter belong to the client, not to a replay
            "ui", "choose_actions", "switch_option", "must_switch", "waiting",
            "run_prompt", "throw_pokeball_prompt", "select_target", "your_team",
            "error", "errors", "crash",
            -> Unit
            else -> l.message(message.string)
        }
    }

    /**
     * The readable form of a message argument.
     *
     * Arguments are usually Components — a Pokémon's display name, a move's translated
     * name — and the display name is the only handle the message stream gives for matching
     * a Pokémon back to its slot, so it has to be resolved to the same string the switch
     * packet produced.
     */
    private fun argText(arg: Any?): String = when (arg) {
        null -> ""
        is Text -> arg.string
        else -> arg.toString()
    }
}
