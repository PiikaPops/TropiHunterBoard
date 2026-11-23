package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.storage.pc.PCPosition
import com.cobblemon.mod.common.client.gui.pc.PCGUI
import com.cobblemon.mod.common.net.messages.client.storage.pc.ClosePCPacket
import com.cobblemon.mod.common.util.pc
import com.cobblemon.mod.common.util.party
import com.hunterboard.pc.HunterPcScreen
import com.hunterboard.pc.PcEntry
import com.hunterboard.pc.PcOps
import com.hunterboard.pc.PcPlace
import com.hunterboard.pc.PcQuery
import com.hunterboard.pc.PcStep
import com.hunterboard.pc.PcFilterState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.server.network.ServerPlayerEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Development test of HunterBoard's PC against a real (integrated) Cobblemon server:
 * fills the player's PC, opens it with /pc, presses the "HunterBoard view" button, then
 * releases, moves, swaps and arranges through [PcOps] and checks the server's PC after
 * each. Results go to `pc_e2e.txt` in the tour folder, with a few pictures.
 *
 * Started by UiTour when `-Puitour.pce2e=1` is given.
 */
object PcE2E {
    private var out: File? = null
    private var onDone: (() -> Unit)? = null
    private var phase = 0
    private var wait = 0
    private val log = StringBuilder()
    private var fails = 0
    private var expectGone: List<UUID> = emptyList()
    private var expectAt: Map<UUID, PCPosition> = emptyMap()
    private var arranged: List<UUID> = emptyList()

    fun start(dir: File, done: () -> Unit) { out = dir; onDone = done; phase = 0; wait = 0 }

    private fun note(s: String) { log.appendLine(s); HunterBoard.LOGGER.info("[PcE2E] $s") }
    private fun check(ok: Boolean, what: String) { if (!ok) fails++; note((if (ok) "PASS " else "FAIL ") + what) }
    private fun shot(client: MinecraftClient, name: String) =
        ScreenshotRecorder.takeScreenshot(client.framebuffer).use { it.writeTo(File(out, "e2e_$name.png")) }

    private fun <T> onServer(client: MinecraftClient, f: (ServerPlayerEntity) -> T): T? {
        val server = client.server ?: return null
        val id = client.player?.uuid ?: return null
        return runCatching { server.submit<T> { f(server.playerManager.getPlayer(id)!!) }.get(5, TimeUnit.SECONDS) }
            .onFailure { note("server call failed: $it") }.getOrNull()
    }

    private val screen: HunterPcScreen? get() = MinecraftClient.getInstance().currentScreen as? HunterPcScreen
    private var baseFps = 0
    private var betterChecked: PCGUI? = null
    private var shinyId: UUID? = null
    private var swapCheck: Triple<UUID, String, String>? = null
    private var teamA: UUID? = null
    private var teamB: UUID? = null
    private var teamMoves: List<String> = emptyList()
    private var teamC: UUID? = null
    private var labelBox = -1

    /** An offline PC with every slot of 12 boxes taken, to measure the cost of the full view. */
    private fun fullPc(): HunterPcScreen {
        val names = listOf("pikachu", "eevee", "geodude", "zubat", "charizard", "gyarados", "machamp", "gengar", "dragonite", "lucario", "garchomp", "rattata")
        val pc = com.cobblemon.mod.common.client.storage.ClientPC(UUID.randomUUID(), 12)
        for (b in 0 until 12) for (s in 0 until 30) {
            pc.set(PCPosition(b, s), PokemonProperties.parse("${names[(b * 30 + s) % names.size]} level=30").create())
        }
        val party = com.cobblemon.mod.common.client.storage.ClientParty(UUID.randomUUID(), 6)
        return HunterPcScreen(com.hunterboard.pc.PcSession(pc, party, null, offline = true), com.hunterboard.pc.PcPrefs.detached())
    }

    fun tick(client: MinecraftClient) {
        if (phase < 0) return
        wait++
        when (phase) {
            0 -> {   // empty then fill the PC, server side
                onServer(client) { p ->
                    val pc = p.pc()
                    pc.toList().forEach { pc.remove(it) }
                    val names = listOf("rattata", "zubat", "geodude", "rattata", "pidgey", "zubat", "magikarp", "rattata", "caterpie", "geodude", "eevee", "pikachu")
                    names.forEachIndexed { i, n ->
                        val shiny = if (i == 10) " shiny=true" else ""
                        pc.add(PokemonProperties.parse("$n level=${5 + i}$shiny").create())
                    }
                    // a legendary and a mythical: never offered for release, caught by the filter
                    pc.add(PokemonProperties.parse("mewtwo level=50").create())
                    pc.add(PokemonProperties.parse("mew level=30").create())
                    pc.count()
                }?.let { note("server PC filled: $it Pokémon") }
                phase = 1; wait = 0
            }
            1 -> if (wait > 10) {   // open the PC the way /pc does
                onServer(client) { p -> p.server.commandManager.executeWithPrefix(p.commandSource.withLevel(4), "pc") }
                phase = 2; wait = 0
            }
            2 -> {
                val gui = client.currentScreen as? PCGUI
                if (gui != null && wait > 10) {
                    shot(client, "1_classic_pc")
                    if (com.hunterboard.pc.HunterPc.betterPcInstalled && betterChecked == null) {
                        // Better PC would have swapped the screen by now: Cobblemon's must still be here
                        check(true, "with Tropimon Better PC installed, Cobblemon's PC stays open")
                        val better = gui.children().filterIsInstance<ButtonWidget>().firstOrNull { it.message.string == "Better PC" }
                        check(better != null, "the \"Better PC\" button is in Cobblemon's PC")
                        betterChecked = gui
                        better?.onPress()
                        phase = 20; wait = 0
                        return
                    }
                    val btn = gui.children().filterIsInstance<ButtonWidget>().firstOrNull { it.message.string.contains("HunterBoard") }
                    check(btn != null, "the \"HunterBoard view\" button is in Cobblemon's PC")
                    btn?.onPress()
                    phase = 3; wait = 0
                } else if (wait > 100) { check(false, "Cobblemon's PC opened"); phase = 99 }
            }
            20 -> if (wait > 15) {   // Better PC opened from its button, then back to Cobblemon's PC
                val name = client.currentScreen?.javaClass?.simpleName
                check(name == "BetterPcScreen", "the button opens Tropimon Better PC (screen: $name)")
                shot(client, "1b_better_pc")
                // back the way Better PC's own "classic PC" button goes: it keeps the PC link
                // (leaving its screen any other way unlinks the PC, as closing it does)
                client.currentScreen?.let { s -> runCatching { s.javaClass.getDeclaredField("keepLink").apply { isAccessible = true }.setBoolean(s, true) } }
                client.setScreen(betterChecked)
                phase = 2; wait = 0
            }
            3 -> if (wait > 15) {
                val s = screen
                check(s != null, "HunterBoard's PC opened from the button")
                if (s == null) { phase = 99; return }
                check(s.entries.count { !it.place.inParty } == 14, "it shows the 14 PC Pokémon (saw ${s.entries.count { !it.place.inParty }})")
                val mewtwo = s.entries.firstOrNull { it.speciesId == "mewtwo" }
                val mew = s.entries.firstOrNull { it.speciesId == "mew" }
                check(mewtwo?.legendary == true && mewtwo.special, "Mewtwo is seen as legendary")
                check(mew?.mythical == true && mew.special, "Mew is seen as mythical")
                val cands = PcQuery.candidates(s.entries, s.prefs)
                check(cands.none { it.special }, "no legendary or mythical among the release candidates")
                // rules: one species, keep none for being the best, keep only a Speed of 31 or more
                run {
                    val pcOnly = s.entries.filter { !it.place.inParty && !it.shiny && !it.special }
                    val line = pcOnly.groupBy { it.lineKey }.maxByOrNull { it.value.size }?.key
                    if (line == null) { check(false, "a species for the release rules"); return@run }
                    val before = Triple(s.prefs.keepPerSpecies, s.prefs.protectIvPct, s.prefs.protectHidden)
                    val rulesBefore = s.prefs.release
                    s.prefs.keepPerSpecies = 0; s.prefs.protectIvPct = 0; s.prefs.protectHidden = false
                    s.prefs.release = com.hunterboard.pc.ReleaseRules(scope = "species:$line", ivs = listOf(-1, -1, -1, -1, -1, 31))
                    val scope = PcQuery.scopeOf(s.prefs.release.scope, s.entries, s.selection, s.results)
                    val got = PcQuery.candidates(s.entries, s.prefs, scope).map { it.uuid }.toSet()
                    val want = s.entries.filter { !it.place.inParty && it.lineKey == line && it.ivs[5] < 31 &&
                        PcQuery.protection(it, s.prefs, s.prefs.release.guards) == null }.map { it.uuid }.toSet()
                    check(got == want && got.isNotEmpty(), "release rules: one species without 31 Speed (${got.size} candidates, expected ${want.size})")
                    // the same, every quality needed: also the ability of the first one
                    val ability = s.entries.first { it.lineKey == line }.abilityId
                    s.prefs.release = s.prefs.release.copy(ability = ability, matchAll = true)
                    val got2 = PcQuery.candidates(s.entries, s.prefs, scope).map { it.uuid }.toSet()
                    val want2 = s.entries.filter { !it.place.inParty && it.lineKey == line && !(it.ivs[5] >= 31 && it.abilityId == ability) &&
                        PcQuery.protection(it, s.prefs, s.prefs.release.guards) == null }.map { it.uuid }.toSet()
                    check(got2 == want2, "release rules: 31 Speed AND the ability (${got2.size} candidates, expected ${want2.size})")
                    // the best copy is kept, counted over the whole species
                    s.prefs.keepPerSpecies = 1
                    val got3 = PcQuery.candidates(s.entries, s.prefs, scope).map { it.uuid }.toSet()
                    val all = s.entries.count { !it.place.inParty && it.lineKey == line }
                    val dropped = got2.size - got3.size
                    check(got2.containsAll(got3) && dropped in 0..1 && (all != got2.size || dropped == 1),
                        "release rules: the best copy of the species stays (${got2.size} -> ${got3.size} of $all)")
                    s.prefs.keepPerSpecies = before.first; s.prefs.protectIvPct = before.second; s.prefs.protectHidden = before.third
                    s.prefs.release = rulesBefore
                }
                val legendFilter = PcQuery.filter(s.entries, PcFilterState(legendary = 1), s.prefs, System.currentTimeMillis())
                check(legendFilter.map { it.speciesId }.toSet() == setOf("mewtwo", "mew"), "the Legendary filter finds exactly Mewtwo and Mew (${legendFilter.map { it.speciesId }})")
                shot(client, "2_hunter_pc")
                // release three rattata-like plain ones, never the shiny
                val targets = s.entries.filter { !it.place.inParty && !it.shiny && it.speciesId in setOf("rattata", "caterpie") }.take(3)
                expectGone = targets.map { it.uuid }
                s.ops.start(PcOps.Kind.RELEASE, targets.map { PcStep.Release(it.uuid, it.place) })
                note("release of ${targets.size} started")
                phase = 4; wait = 0
            }
            4 -> {
                val s = screen ?: run { check(false, "screen still open during release"); phase = 99; return }
                if (s.ops.running && wait < 300) return
                val t1 = (s.javaClass.getDeclaredField("toast").apply { isAccessible = true }.get(s) as String?) ?: ""
                check(s.ops.state == PcOps.State.IDLE && t1.contains("relâché"), "release done, progress window gone by itself (\"$t1\")")
                shot(client, "3_after_release")
                s.ops.clear()
                val gone = onServer(client) { p -> expectGone.all { p.pc().get(it) == null } }
                check(gone == true, "the server no longer has the released Pokémon")
                val shinyKept = onServer(client) { p -> p.pc().any { it.shiny } }
                check(shinyKept == true, "the shiny is still there")
                phase = 5; wait = 0
            }
            5 -> if (wait > 5) {   // a move to box 2 and a swap in box 1
                val s = screen ?: run { phase = 99; return }
                val pcList = s.entries.filter { !it.place.inParty }.sortedBy { it.place.slot }
                val a = pcList[0]; val b = pcList[1]; val c = pcList[2]
                expectAt = mapOf(a.uuid to PCPosition(1, 0), b.uuid to c.place.let { PCPosition(it.box, it.slot) }, c.uuid to b.place.let { PCPosition(it.box, it.slot) })
                s.ops.start(PcOps.Kind.MOVE, listOf(
                    PcStep.Move(a.uuid, a.place, PcPlace(1, 0)),
                    PcStep.Swap(b.uuid, b.place, c.uuid, c.place),
                ))
                phase = 6; wait = 0
            }
            6 -> {
                val s = screen ?: run { phase = 99; return }
                if (s.ops.running && wait < 300) return
                val t2 = (s.javaClass.getDeclaredField("toast").apply { isAccessible = true }.get(s) as String?) ?: ""
                check(s.ops.state == PcOps.State.IDLE && t2.contains("déplacé"), "move + swap done (\"$t2\")")
                s.ops.clear()
                val ok = onServer(client) { p -> expectAt.all { (id, pos) -> p.pc().get(pos)?.uuid == id } }
                check(ok == true, "the server has the moved and swapped Pokémon where planned")
                phase = 7; wait = 0
            }
            7 -> if (wait > 5) {   // arrange the whole PC by Pokédex number from box 1
                val s = screen ?: run { phase = 99; return }
                val order: List<PcEntry> = PcQuery.sort(s.entries.filter { !it.place.inParty }, PcFilterState(sort = PcFilterState.SORT_DEX), s.prefs)
                val steps = PcOps.planArrangement(order, s.session, 0)
                check(steps != null, "arrangement planned (${steps?.size} steps)")
                arranged = order.map { it.uuid }
                s.ops.start(PcOps.Kind.ARRANGE, steps ?: emptyList())
                phase = 8; wait = 0
            }
            8 -> {
                val s = screen ?: run { phase = 99; return }
                if (s.ops.running && wait < 600) return
                val t3 = (s.javaClass.getDeclaredField("toast").apply { isAccessible = true }.get(s) as String?) ?: ""
                check(s.ops.state == PcOps.State.IDLE && t3.contains("rangé"), "arrangement done (\"$t3\")")
                s.ops.clear()
                val ok = onServer(client) { p -> arranged.withIndex().all { (i, id) -> p.pc().get(PCPosition(i / 30, i % 30))?.uuid == id } }
                check(ok == true, "the server's PC is in Pokédex order from box 1, slot 1")
                if (wait > 0) shot(client, "4_arranged")
                phase = 30; wait = 0
            }
            30 -> if (wait > 5) {   // rename box 1 through the dialog
                val s = screen ?: run { phase = 99; return }
                val d = com.hunterboard.pc.RenameBoxDialog(s, 0)
                s.dialog = d
                val f = d.javaClass.superclass.getDeclaredField("field").apply { isAccessible = true }.get(d) as net.minecraft.client.gui.widget.TextFieldWidget
                f.text = "Doublons à trier"
                shot(client, "4b_rename")
                d.submit()
                phase = 31; wait = 0
            }
            31 -> if (wait > 15) {
                val s = screen ?: run { phase = 99; return }
                val server = onServer(client) { p -> p.pc().boxes[0].name }
                check(server == "Doublons à trier", "the server's box 1 is renamed (\"$server\")")
                check(s.boxName(0) == "Doublons à trier", "HunterBoard's PC shows the new name (\"${s.boxName(0)}\")")
                shot(client, "4c_renamed")
                phase = 50; wait = 0
            }
            50 -> if (wait > 5) {   // a "shiny" label on box 3: the shiny goes there
                val s = screen ?: run { phase = 99; return }
                s.prefs.setBoxRule(2, "shiny")
                s.refilter()
                val steps = PcOps.planRouting(s.entries, s.session, s.prefs)
                check(steps.size == 1, "one Pokémon to send to the shiny box (${steps.size})")
                shinyId = s.entries.firstOrNull { it.shiny }?.uuid
                s.ops.start(PcOps.Kind.ARRANGE, steps)
                phase = 51; wait = 0
            }
            51 -> {
                val s = screen ?: run { phase = 99; return }
                if (s.ops.running && wait < 300) return
                val box = onServer(client) { p -> p.pc().boxes.indexOfFirst { b -> b.any { it?.uuid == shinyId } } }
                check(box == 2, "the server has the shiny in the labelled box 3 (box index $box)")
                s.prefs.setBoxRule(2, null); s.refilter()
                phase = 52; wait = 0
            }
            52 -> if (wait > 5) {   // swap an active move for one from the reserve, through the dialog
                val s = screen ?: run { phase = 99; return }
                val e = s.entries.filter { !it.place.inParty }.firstOrNull { en ->
                    val cur = en.pokemon.moveSet.getMoves().map { it.name }
                    cur.isNotEmpty() && en.pokemon.allAccessibleMoves.any { it.name !in cur }
                }
                if (e == null) { check(false, "a PC Pokémon with a move to swap in"); phase = 9; return }
                val old = e.pokemon.moveSet.getMoves().first().template
                val new = e.pokemon.allAccessibleMoves.first { t -> e.pokemon.moveSet.getMoves().none { it.name == t.name } }
                swapCheck = Triple(e.uuid, old.name, new.name)
                val d = com.hunterboard.pc.MovesDialog(s, e)
                s.dialog = d
                d.javaClass.getDeclaredMethod("swap", com.cobblemon.mod.common.api.moves.MoveTemplate::class.java, com.cobblemon.mod.common.api.moves.MoveTemplate::class.java)
                    .apply { isAccessible = true }.invoke(d, old, new)
                note("move swap sent: ${old.name} -> ${new.name}")
                phase = 53; wait = 0
            }
            53 -> if (wait > 20) {
                val (id, old, new) = swapCheck ?: run { phase = 9; return }
                val names = onServer(client) { p -> p.pc().get(id)?.moveSet?.getMoves()?.map { it.name } }
                check(names != null && new in names && old !in names, "the server swapped $old for $new ($names)")
                shot(client, "4d_moves")
                screen?.closeDialog()
                phase = 60; wait = 0
            }
            60 -> if (wait > 5) {   // a saved team: two PC Pokémon, the first with four chosen moves
                val s = screen ?: run { phase = 99; return }
                val pcs = s.entries.filter { !it.place.inParty && it.pokemon.allAccessibleMoves.size >= 5 }.sortedByDescending { it.level }
                if (pcs.size < 2) { check(false, "two PC Pokémon with enough moves for a team"); phase = 9; return }
                val a = pcs[0]; val b = pcs[1]
                val cur = a.pokemon.moveSet.getMoves().map { it.name }
                val extra = a.pokemon.allAccessibleMoves.map { it.name }.first { it !in cur }
                // a move it must relearn first, then its current ones in another order
                val wanted = (listOf(extra) + cur.reversed()).distinct().take(4)
                teamA = a.uuid; teamB = b.uuid; teamMoves = wanted
                val team = com.hunterboard.pc.PcTeam("Test", listOf(com.hunterboard.pc.PcTeamMember(a.uuid.toString(), wanted), com.hunterboard.pc.PcTeamMember(b.uuid.toString())))
                s.prefs.saveTeam(team)
                val plan = PcOps.planTeam(team, s.session, { "?" })
                note("team plan: ${plan.steps.size} steps, problems ${plan.problems}")
                s.ops.start(PcOps.Kind.TEAM, plan.steps)
                phase = 61; wait = 0
            }
            61 -> {
                val s = screen ?: run { phase = 99; return }
                if (s.ops.running && wait < 600) return
                val t = (s.javaClass.getDeclaredField("toast").apply { isAccessible = true }.get(s) as String?) ?: ""
                check(t.contains("Équipe équipée"), "team equipped (\"$t\")")
                val party = onServer(client) { p -> List(6) { p.party().get(it)?.uuid } }
                check(party?.get(0) == teamA && party?.get(1) == teamB, "the server's party starts with the team, in order")
                val moves = onServer(client) { p -> p.party().get(0)?.moveSet?.getMoves()?.map { it.name } }
                check(moves == teamMoves, "the first member has its chosen moves in order ($moves, wanted $teamMoves)")
                shot(client, "4e_team")
                // a second team of one: the other member goes back to the PC, into the box of its
                // tag — which wins over a "favourites" box placed before it
                val sess = s.session
                labelBox = (0 until sess.boxCount).last { b -> (0 until sess.boxSize(b)).any { sess.at(PcPlace(b, it)) == null } }
                val favBox = (0 until labelBox).first { b -> (0 until sess.boxSize(b)).any { sess.at(PcPlace(b, it)) == null } }
                s.prefs.setBoxRule(favBox, "favorite")
                s.prefs.setBoxRule(labelBox, "tag:pvp")
                s.prefs.setFavorite(teamA!!, true)
                s.prefs.setTag(teamA!!, "pvp", true)
                val aEntry = s.entry(teamA!!)
                check(aEntry != null && com.hunterboard.pc.BoxRules.ruleFor(aEntry, s.prefs) == "tag:pvp",
                    "a tag wins over a status label (favourite box ${favBox + 1} before the PvP box ${labelBox + 1})")
                s.prefs.saveTeam(com.hunterboard.pc.PcTeam("Solo", listOf(com.hunterboard.pc.PcTeamMember(teamB.toString()))))
                s.equipTeam(s.prefs.teams().lastIndex)
                phase = 62; wait = 0
            }
            62 -> {
                val s = screen ?: run { phase = 99; return }
                if (s.ops.running && wait < 600) return
                val party = onServer(client) { p -> List(6) { p.party().get(it)?.uuid } }
                val aBox = onServer(client) { p -> (p.pc().get(teamA!!)?.storeCoordinates?.get()?.position as? PCPosition)?.box }
                check(party != null && party[0] == teamB && party.drop(1).all { it == null } && aBox == labelBox,
                    "a team of one: the other Pokémon went back to the PC, into its tag's box (box ${aBox?.plus(1)}, wanted ${labelBox + 1})")
                // a team whose member comes from the PC: it swaps with the tagged one in the party,
                // which then goes on to its tag's box
                s.prefs.setTag(teamB!!, "pvp", true)
                val c = s.entries.firstOrNull { !it.place.inParty && it.place.box != labelBox && it.uuid != teamA }
                if (c == null) { check(false, "a PC Pokémon for the swap team"); phase = 63; return }
                teamC = c.uuid
                s.prefs.saveTeam(com.hunterboard.pc.PcTeam("Swap", listOf(com.hunterboard.pc.PcTeamMember(c.uuid.toString()))))
                s.equipTeam(s.prefs.teams().lastIndex)
                phase = 620; wait = 0
            }
            620 -> {
                val s = screen ?: run { phase = 99; return }
                if (s.ops.running && wait < 600) return
                val party = onServer(client) { p -> List(6) { p.party().get(it)?.uuid } }
                val bBox = onServer(client) { p -> (p.pc().get(teamB!!)?.storeCoordinates?.get()?.position as? PCPosition)?.box }
                check(party != null && party[0] == teamC && bBox == labelBox,
                    "swapped out of the party, a tagged Pokémon goes on to its tag's box (box ${bBox?.plus(1)}, wanted ${labelBox + 1})")
                shot(client, "4f_team_panel")
                // back as it was for what follows
                s.prefs.boxRules().keys.toList().forEach { s.prefs.setBoxRule(it, null) }
                listOf(teamA!!, teamB!!).forEach { s.prefs.setTag(it, "pvp", false) }
                s.prefs.setFavorite(teamA!!, false)
                teamB = teamC
                phase = 63; wait = 0
            }
            63 -> if (wait > 10) {   // the player may empty the party: deposit its last Pokémon, the way the menu does
                val s = screen ?: run { phase = 99; return }
                val inParty = s.entries.filter { it.place.inParty }
                check(inParty.size == 1, "one Pokémon left in the party before emptying it (${inParty.size})")
                s.depositInto(inParty, 0)
                check(s.ops.running, "depositing the last party Pokémon is not refused by HunterBoard")
                phase = 64; wait = 0
            }
            64 -> {
                val s = screen ?: run { phase = 99; return }
                if (s.ops.running && wait < 300) return
                val party = onServer(client) { p -> p.party().count() }
                val inPc = onServer(client) { p -> p.pc().get(teamB!!) != null }
                check(party == 0 && inPc == true, "the server's party is empty, its last Pokémon in the PC (party $party)")
                s.ops.clear()
                phase = 9; wait = 0
            }
            9 -> if (wait > 10) {   // the server closes the PC: our screen must follow
                onServer(client) { p -> ClosePCPacket(p.pc().uuid).sendToPlayer(p) }
                phase = 10; wait = 0
            }
            10 -> if (wait > 10) {
                check(client.currentScreen !is HunterPcScreen, "a close from the server closes HunterBoard's PC")
                client.setScreen(null)
                shot(client, "6_hud_shown")
                client.options.hudHidden = true
                phase = 40; wait = 0
            }
            40 -> if (wait > 10) {   // F1: HunterBoard's HUDs follow the hidden interface
                check(HudLayoutManager.hiddenByPlayer(), "with the interface hidden, HunterBoard's HUDs stand down")
                shot(client, "6_hud_hidden")
                client.options.hudHidden = false
                client.setScreen(net.minecraft.client.gui.screen.ChatScreen(""))
                phase = 11; wait = 0
            }
            // frame rate: a plain screen, then a full PC (12 boxes × 30 on screen)
            11 -> if (wait == 70) { baseFps = client.currentFps; client.setScreen(fullPc()); phase = 12; wait = 0 }
            12 -> if (wait == 70) {
                val fps = client.currentFps
                note("FPS: plain screen $baseFps, full PC on screen $fps")
                shot(client, "5_full_pc")
                phase = 99
            }
            99 -> {
                note(if (fails == 0) "ALL PASSED" else "$fails FAILED")
                runCatching { File(out, "pc_e2e.txt").writeText(log.toString()) }
                phase = -1
                onDone?.invoke()
            }
        }
    }
}
