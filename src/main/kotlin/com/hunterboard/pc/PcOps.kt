package com.hunterboard.pc

import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.api.storage.pc.PCPosition
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.pc.PCGUI
import com.cobblemon.mod.common.client.storage.ClientPC
import com.cobblemon.mod.common.client.storage.ClientParty
import com.cobblemon.mod.common.net.messages.server.storage.SwapPCPartyPokemonPacket
import com.cobblemon.mod.common.net.messages.server.storage.party.MovePartyPokemonPacket
import com.cobblemon.mod.common.net.messages.server.storage.party.SwapPartyPokemonPacket
import com.cobblemon.mod.common.net.messages.server.storage.pc.MovePCPokemonPacket
import com.cobblemon.mod.common.net.messages.server.storage.pc.MovePCPokemonToPartyPacket
import com.cobblemon.mod.common.net.messages.server.storage.pc.MovePartyPokemonToPCPacket
import com.cobblemon.mod.common.net.messages.server.storage.pc.ReleasePCPokemonPacket
import com.cobblemon.mod.common.net.messages.server.storage.pc.SwapPCPokemonPacket
import com.cobblemon.mod.common.pokemon.Pokemon
import com.hunterboard.HunterBoard
import java.util.UUID

/**
 * The PC being worked on: Cobblemon's client copy of it and of the party, plus the
 * Cobblemon screen it was opened from (kept so the link to the PC stays open and the
 * player can go back to it). [offline] sessions (the UI tour) never send anything.
 */
class PcSession(val pc: ClientPC, val party: ClientParty?, val original: PCGUI?, val offline: Boolean = false) {
    fun at(p: PcPlace): Pokemon? =
        if (p.inParty) party?.slots?.getOrNull(p.slot) else pc.boxes.getOrNull(p.box)?.slots?.getOrNull(p.slot)

    fun placeOf(id: UUID): PcPlace? {
        party?.slots?.forEachIndexed { i, p -> if (p?.uuid == id) return PcPlace.party(i) }
        pc.boxes.forEachIndexed { b, box -> box.slots.forEachIndexed { s, p -> if (p?.uuid == id) return PcPlace(b, s) } }
        return null
    }

    val boxCount: Int get() = pc.boxes.size
    fun boxSize(b: Int): Int = pc.boxes.getOrNull(b)?.slots?.size ?: 30
    val partySize: Int get() = party?.slots?.size ?: 6
    fun partyCount(): Int = party?.slots?.count { it != null } ?: 0

    /** Still the PC Cobblemon holds for this player (it drops it when the link closes). */
    fun valid(): Boolean = offline || CobblemonClient.storage.pcStores[pc.uuid] === pc

    fun send(step: PcStep) {
        if (offline) return
        when (step) {
            is PcStep.Release -> ReleasePCPokemonPacket(step.id, step.at.pc()).sendToServer()
            is PcStep.Move -> when {
                step.from.inParty && step.to.inParty -> MovePartyPokemonPacket(step.id, step.from.party(), step.to.party()).sendToServer()
                step.from.inParty -> MovePartyPokemonToPCPacket(step.id, step.from.party(), step.to.pc()).sendToServer()
                step.to.inParty -> MovePCPokemonToPartyPacket(step.id, step.from.pc(), step.to.party()).sendToServer()
                else -> MovePCPokemonPacket(step.id, step.from.pc(), step.to.pc()).sendToServer()
            }
            is PcStep.Swap -> when {
                step.pa.inParty && step.pb.inParty -> SwapPartyPokemonPacket(step.a, step.pa.party(), step.b, step.pb.party()).sendToServer()
                step.pa.inParty -> SwapPCPartyPokemonPacket(step.a, step.pa.party(), step.b, step.pb.pc()).sendToServer()
                step.pb.inParty -> SwapPCPartyPokemonPacket(step.b, step.pb.party(), step.a, step.pa.pc()).sendToServer()
                else -> SwapPCPokemonPacket(step.a, step.pa.pc(), step.b, step.pb.pc()).sendToServer()
            }
            // the two move requests have Kotlin-internal constructors: built by reflection
            is PcStep.SetMove -> {
                val place = placeOf(step.id) ?: return
                val old = step.old?.let { com.cobblemon.mod.common.api.moves.Moves.getByName(it) }
                val new = com.cobblemon.mod.common.api.moves.Moves.getByName(step.new) ?: return
                val packet = com.cobblemon.mod.common.net.messages.server.BenchMovePacket::class.java.constructors
                    .first { it.parameterCount == 4 }.newInstance(place.inParty, step.id, old, new)
                (packet as com.cobblemon.mod.common.api.net.NetworkPacket<*>).sendToServer()
            }
            is PcStep.OrderMoves -> {
                val place = placeOf(step.id) ?: return
                if (!place.inParty) return
                val packet = com.cobblemon.mod.common.net.messages.server.RequestMoveSwapPacket::class.java.constructors
                    .first { it.parameterCount == 3 }.newInstance(step.a, step.b, place.slot)
                (packet as com.cobblemon.mod.common.api.net.NetworkPacket<*>).sendToServer()
            }
        }
    }

    /** The current move names of a Pokémon, four slots, null where empty. */
    fun movesOf(id: UUID): List<String?>? {
        val place = placeOf(id) ?: return null
        val p = at(place) ?: return null
        val list = p.moveSet.getMovesWithNulls()
        return List(4) { list.getOrNull(it)?.name }
    }

    private fun PcPlace.pc() = PCPosition(box, slot)
    private fun PcPlace.party() = PartyPosition(slot)
}

/** One request to the server, with what must be true before sending it and once it is done. */
sealed class PcStep {
    abstract fun ready(s: PcSession): Boolean
    abstract fun done(s: PcSession): Boolean

    data class Release(val id: UUID, val at: PcPlace) : PcStep() {
        override fun ready(s: PcSession) = !at.inParty && s.at(at)?.uuid == id
        override fun done(s: PcSession) = s.pc.findByUUID(id) == null && s.party?.findByUUID(id) == null
    }

    data class Move(val id: UUID, val from: PcPlace, val to: PcPlace) : PcStep() {
        override fun ready(s: PcSession) = s.at(from)?.uuid == id && s.at(to) == null
        override fun done(s: PcSession) = s.at(to)?.uuid == id
    }

    data class Swap(val a: UUID, val pa: PcPlace, val b: UUID, val pb: PcPlace) : PcStep() {
        override fun ready(s: PcSession) = s.at(pa)?.uuid == a && s.at(pb)?.uuid == b
        override fun done(s: PcSession) = s.at(pb)?.uuid == a && s.at(pa)?.uuid == b
    }

    /** Replaces move [old] (null: an empty slot) by [new], which the Pokémon must be able to learn. */
    data class SetMove(val id: UUID, val old: String?, val new: String) : PcStep() {
        override fun ready(s: PcSession): Boolean {
            val m = s.movesOf(id) ?: return false
            return new !in m && (if (old == null) null in m else old in m)
        }
        override fun done(s: PcSession): Boolean {
            val m = s.movesOf(id) ?: return false
            return new in m && (old == null || old !in m)
        }
    }

    /** Swaps the moves in slots [a] and [b] (party Pokémon only: Cobblemon's own reorder request). */
    data class OrderMoves(val id: UUID, val a: Int, val b: Int, val nameA: String, val nameB: String) : PcStep() {
        override fun ready(s: PcSession): Boolean {
            val m = s.movesOf(id) ?: return false
            return s.placeOf(id)?.inParty == true && m[a] == nameA && m[b] == nameB
        }
        override fun done(s: PcSession): Boolean {
            val m = s.movesOf(id) ?: return false
            return m[a] == nameB && m[b] == nameA
        }
    }
}

/**
 * Runs steps one at a time: send, wait until the client storage shows the result (that is
 * the server's answer), pause a moment, send the next. Anything unexpected — the Pokémon
 * is not where the plan said, no answer within 5 s, the PC link dropped — stops the whole
 * batch: a plan built on a storage that changed under it must not carry on.
 */
class PcOps {
    enum class Kind { RELEASE, MOVE, ARRANGE, TEAM }
    enum class State { IDLE, RUNNING, DONE, CHANGED, TIMEOUT, CANCELLED }

    var kind = Kind.MOVE; private set
    var state = State.IDLE; private set
    var total = 0; private set
    var done = 0; private set
    private val queue = ArrayDeque<PcStep>()
    private var current: PcStep? = null
    private var sentAt = 0L
    private var nextAt = 0L
    private var stopRequested = false

    val running: Boolean get() = state == State.RUNNING
    val remaining: Int get() = queue.size + (if (current != null) 1 else 0)

    fun start(kind: Kind, steps: List<PcStep>) {
        if (running) return
        this.kind = kind
        queue.clear(); queue.addAll(steps)
        total = steps.size; done = 0; current = null; nextAt = 0L; stopRequested = false
        state = if (steps.isEmpty()) State.DONE else State.RUNNING
    }

    /** Stops after the request already sent is answered: that one cannot be called back. */
    fun stop() { if (running) { stopRequested = true; if (current == null) finish(State.CANCELLED) } }

    /** Back to idle once the outcome has been shown. */
    fun clear() { if (!running) state = State.IDLE }

    fun tick(s: PcSession) {
        if (!running) return
        val now = System.currentTimeMillis()
        if (!s.valid()) { finish(State.CANCELLED); return }
        val step = current
        if (step != null) {
            if (step.done(s) || s.offline) {
                done++; current = null; nextAt = now + GAP_MS
                if (queue.isEmpty()) finish(State.DONE) else if (stopRequested) finish(State.CANCELLED)
            } else if (now - sentAt > TIMEOUT_MS) {
                HunterBoard.LOGGER.warn("[PC] No answer from the server for $step, batch stopped")
                finish(State.TIMEOUT)
            }
            return
        }
        if (stopRequested) { finish(State.CANCELLED); return }
        if (now < nextAt) return
        val next = queue.removeFirstOrNull() ?: run { finish(State.DONE); return }
        if (!next.ready(s)) {
            HunterBoard.LOGGER.warn("[PC] Storage changed before $next, batch stopped")
            finish(State.CHANGED); return
        }
        s.send(next)
        current = next
        sentAt = now
    }

    private fun finish(st: State) { queue.clear(); current = null; state = st }

    companion object {
        const val GAP_MS = 120L
        const val TIMEOUT_MS = 5000L

        /**
         * Steps that put [order] (PC Pokémon, already sorted) one after another from the
         * first slot of [startBox], using only free slots and the slots of the Pokémon being
         * arranged: every other Pokémon stays exactly where it is. Null when there is not
         * enough room from that box on.
         */
        fun planArrangement(order: List<PcEntry>, s: PcSession, startBox: Int, prefs: PcPrefs? = null): List<PcStep>? {
            val ids = order.map { it.uuid }.toHashSet()
            fun usable(b: Int, slot: Int): Boolean { val p = s.at(PcPlace(b, slot)); return p == null || p.uuid in ids }
            val rules = prefs?.boxRules().orEmpty().filterKeys { it < s.boxCount }
            val finalOrder = ArrayList<UUID>(order.size)
            val targets = ArrayList<PcPlace>(order.size)
            // labelled boxes first: each takes the Pokémon matching its rule, in the chosen order
            val placed = HashSet<UUID>()
            if (prefs != null && rules.isNotEmpty()) {
                val byRule = order.groupBy { BoxRules.ruleFor(it, prefs) }
                for (rule in rules.entries.sortedBy { it.key }.map { it.value }.distinct()) {
                    val group = byRule[rule] ?: continue
                    val slots = rules.filterValues { it == rule }.keys.sorted()
                        .flatMap { b -> (0 until s.boxSize(b)).filter { usable(b, it) }.map { PcPlace(b, it) } }
                    // more than the labelled boxes hold: the rest goes with the others
                    for ((e, slot) in group.zip(slots)) { finalOrder += e.uuid; targets += slot; placed += e.uuid }
                }
            }
            val rest = order.filter { it.uuid !in placed }
            var found = 0
            outer@ for (b in startBox until s.boxCount) {
                if (b in rules) continue
                for (slot in 0 until s.boxSize(b)) {
                    if (found == rest.size) break@outer
                    if (usable(b, slot)) { finalOrder += rest[found].uuid; targets += PcPlace(b, slot); found++ }
                }
            }
            if (found < rest.size) return null
            return planAssign(finalOrder, targets, s)
        }

        /**
         * The Pokémon of [entries] whose label rule sends them to a box they are not in yet,
         * each moved to a free slot of a box carrying that rule. Nothing else moves. Those that
         * find no room are left where they are.
         */
        fun planRouting(entries: List<PcEntry>, s: PcSession, prefs: PcPrefs): List<PcStep> {
            val rules = prefs.boxRules().filterKeys { it < s.boxCount }
            if (rules.isEmpty()) return emptyList()
            val taken = HashSet<PcPlace>()
            val steps = mutableListOf<PcStep>()
            for (e in entries.filter { !it.place.inParty }.sortedWith(compareBy({ it.place.box }, { it.place.slot }))) {
                val rule = BoxRules.ruleFor(e, prefs) ?: continue
                val boxes = rules.filterValues { it == rule }.keys.sorted()
                if (e.place.box in boxes) continue
                val free = boxes.asSequence().flatMap { b -> (0 until s.boxSize(b)).asSequence().map { PcPlace(b, it) } }
                    .firstOrNull { it !in taken && s.at(it) == null } ?: continue
                taken += free
                steps += PcStep.Move(e.uuid, e.place, free)
            }
            return steps
        }

        /** What equipping a team will do: the steps, and what it cannot do (missing Pokémon, moves). */
        class TeamPlan(val steps: List<PcStep>, val problems: List<String>, val members: Int)

        /**
         * Puts [team]'s Pokémon in the party in its order (swapping with whoever is in the
         * way, the Pokémon that leave the party going to the PC), then gives each the moves
         * the team stores for it. Moves are set by Cobblemon's "swap with a benched move"
         * request, so only moves the Pokémon can relearn are possible.
         *
         * A Pokémon leaving the party whose box label ([labelled]: its labelled boxes) points
         * elsewhere is put away in a free slot of that box, as "arrange" would.
         */
        fun planTeam(team: PcTeam, s: PcSession, nameOf: (UUID) -> String, labelled: (UUID) -> List<Int> = { emptyList() }): TeamPlan {
            val pos = HashMap<UUID, PcPlace>()
            val occ = HashMap<PcPlace, UUID>()
            for (i in 0 until s.partySize) s.at(PcPlace.party(i))?.let { pos[it.uuid] = PcPlace.party(i); occ[PcPlace.party(i)] = it.uuid }
            for (b in 0 until s.boxCount) for (slot in 0 until s.boxSize(b)) {
                s.at(PcPlace(b, slot))?.let { pos[it.uuid] = PcPlace(b, slot); occ[PcPlace(b, slot)] = it.uuid }
            }
            val leaving = (0 until s.partySize).mapNotNullTo(LinkedHashSet()) { occ[PcPlace.party(it)] }
            val problems = mutableListOf<String>()
            val members = team.filled().mapNotNull { m ->
                val id = runCatching { UUID.fromString(m.uuid) }.getOrNull()
                if (id == null || id !in pos) { problems += L("A Pokémon of the team is no longer in the PC or the party.", "Un Pokémon de l'équipe n'est plus dans le PC ni dans l'équipe."); null }
                else id to m
            }.distinctBy { it.first }.take(6)
            members.forEach { leaving.remove(it.first) }
            val steps = mutableListOf<PcStep>()
            /** A free PC slot in [id]'s labelled boxes, else (when [anywhere]) the first free one. */
            fun freeFor(id: UUID, anywhere: Boolean): PcPlace? {
                val boxes = labelled(id).filter { it < s.boxCount }
                val pick = boxes.asSequence().flatMap { b -> (0 until s.boxSize(b)).asSequence().map { PcPlace(b, it) } }.firstOrNull { occ[it] == null }
                if (pick != null || !anywhere) return pick
                return (0 until s.boxCount).asSequence().flatMap { b -> (0 until s.boxSize(b)).asSequence().map { PcPlace(b, it) } }.firstOrNull { occ[it] == null }
            }
            // 1. the team into party slots 0..n-1, in order
            for ((i, pair) in members.withIndex()) {
                val id = pair.first
                val target = PcPlace.party(i)
                val here = occ[target]
                if (here == id) continue
                val from = pos.getValue(id)
                if (here == null) { steps += PcStep.Move(id, from, target); occ.remove(from) }
                else { steps += PcStep.Swap(id, from, here, target); occ[from] = here; pos[here] = from }
                occ[target] = id; pos[id] = target
            }
            // 2. whoever is still in the party beyond the team goes to the PC, its labelled box first
            if (members.isNotEmpty()) for (slot in members.size until s.partySize) {
                val here = occ[PcPlace.party(slot)] ?: continue
                val dest = freeFor(here, anywhere = true)
                if (dest == null) { problems += L("No free PC slot for ", "Aucune place libre dans le PC pour ") + nameOf(here); continue }
                steps += PcStep.Move(here, PcPlace.party(slot), dest)
                occ.remove(PcPlace.party(slot)); occ[dest] = here; pos[here] = dest
            }
            // 3. those swapped out of the party into a team member's old slot: to their labelled box
            for (id in leaving) {
                val at = pos[id] ?: continue
                if (at.inParty) continue
                val boxes = labelled(id)
                if (boxes.isEmpty() || at.box in boxes) continue
                val dest = freeFor(id, anywhere = false) ?: continue
                steps += PcStep.Move(id, at, dest)
                occ.remove(at); occ[dest] = id; pos[id] = dest
            }
            // 4. moves: replace what differs, then put them in the stored order
            for ((id, m) in members) {
                val wanted = m.moves.filter { it.isNotBlank() }.distinct().take(4)
                if (wanted.isEmpty()) continue
                val current = s.movesOf(id)?.toMutableList() ?: continue
                val pokemon = s.at(s.placeOf(id) ?: continue) ?: continue
                val learnable = runCatching { pokemon.allAccessibleMoves.map { it.name }.toSet() }.getOrDefault(emptySet())
                for (w in wanted) {
                    if (w in current) continue
                    if (w !in learnable) { problems += nameOf(id) + L(" cannot learn ", " ne peut pas apprendre ") + w; continue }
                    val slot = current.indexOfFirst { it == null }.takeIf { it >= 0 } ?: current.indexOfFirst { it !in wanted }
                    if (slot < 0) continue
                    steps += PcStep.SetMove(id, current[slot], w)
                    current[slot] = w
                }
                for ((i, w) in wanted.withIndex()) {
                    val j = current.indexOf(w)
                    if (j < 0 || j == i || current[i] == null) continue
                    steps += PcStep.OrderMoves(id, i, j, current[i]!!, w)
                    current[j] = current[i]; current[i] = w
                }
            }
            return TeamPlan(steps, problems.distinct(), members.size)
        }

        /** Steps bringing order[i] to targets[i] by moves and swaps, the PC simulated as they go. */
        fun planAssign(order: List<UUID>, targets: List<PcPlace>, s: PcSession): List<PcStep>? {
            val pos = HashMap<UUID, PcPlace>()
            val occ = HashMap<PcPlace, UUID>()
            for (b in 0 until s.boxCount) for (slot in 0 until s.boxSize(b)) {
                s.at(PcPlace(b, slot))?.let { pos[it.uuid] = PcPlace(b, slot); occ[PcPlace(b, slot)] = it.uuid }
            }
            val steps = mutableListOf<PcStep>()
            for ((i, want) in order.withIndex()) {
                val target = targets[i]
                val here = occ[target]
                if (here == want) continue
                val from = pos[want] ?: return null
                if (here == null) {
                    steps += PcStep.Move(want, from, target)
                    occ.remove(from)
                } else {
                    steps += PcStep.Swap(want, from, here, target)
                    occ[from] = here; pos[here] = from
                }
                occ[target] = want; pos[want] = target
            }
            return steps
        }

        /** Free PC slots from [startBox] on, then wrapping round to the first boxes. */
        fun freeSlots(s: PcSession, startBox: Int, wrap: Boolean = true): List<PcPlace> {
            val out = mutableListOf<PcPlace>()
            val order = (startBox until s.boxCount) + (if (wrap) (0 until startBox) else IntRange.EMPTY)
            for (b in order) for (slot in 0 until s.boxSize(b)) if (s.at(PcPlace(b, slot)) == null) out += PcPlace(b, slot)
            return out
        }
    }
}
