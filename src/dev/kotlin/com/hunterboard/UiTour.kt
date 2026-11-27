package com.hunterboard

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.registry.RegistryKeys
import net.minecraft.resource.DataConfiguration
import net.minecraft.util.Identifier
import net.minecraft.world.Difficulty
import net.minecraft.world.GameMode
import net.minecraft.world.GameRules
import net.minecraft.world.gen.GeneratorOptions
import net.minecraft.world.gen.WorldPresets
import net.minecraft.world.level.LevelInfo
import java.io.File

/**
 * Development tool: photographs every screen, so a change to the interface can be looked
 * at as a whole instead of clicked through by hand.
 *
 * Inert unless the game is started with `-Dhunterboard.uitour=<folder>` (which
 * `gradlew runClient -Puitour=<folder>` does). It then opens a flat test world on its
 * own, walks through the screens below under each requested theme, saves one PNG per
 * screen into the folder, and quits.
 *
 *  - `-Dhunterboard.uitour.themes=a,b` — themes to photograph (default: the current one);
 *    `modern` stands for the textured interface.
 *  - `-Dhunterboard.uitour.only=a,b` — only the steps whose name contains one of these.
 */
object UiTour {

    private const val WORLD = "hb_uitour"
    /** Frames a screen is left alone before its picture: sprites and models load late. */
    private const val SETTLE_TICKS = 14

    private class Step(
        val name: String,
        /** Something done halfway through the wait, once the screen has drawn a few frames. */
        val then: ((MinecraftClient) -> Unit)? = null,
        val open: (MinecraftClient) -> Screen?
    )

    private var out: File? = null
    private var steps: List<Pair<String, Step>> = emptyList()
    private var index = -1
    private var wait = 0
    private var started = false
    private var joinedTicks = 0
    private var done = false
    private var auditing = false

    fun register() {
        val dir = System.getProperty("hunterboard.uitour") ?: return
        out = File(dir).also { it.mkdirs() }
        HunterBoard.LOGGER.info("UiTour: screenshots will go to ${out?.absolutePath}")
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            try { tick(client) } catch (e: Throwable) {
                HunterBoard.LOGGER.error("UiTour failed", e)
                finish(client)
            }
        }
    }

    private fun tick(client: MinecraftClient) {
        if (done) return
        client.options.pauseOnLostFocus = false
        client.toastManager.clear()   // the starter prompt would cover the top-right corner

        if (client.player == null) {
            if (!started && client.currentScreen is TitleScreen && client.overlay == null) {
                started = true
                startWorld(client)
            }
            return
        }
        // In the world: let the species arrive and the first chunks draw.
        if (index < 0) {
            if (PokemonSpecies.implemented.isEmpty()) return
            if (++joinedTicks < 60) return
            // the habitat feed against a local relay, before the pictures
            System.getProperty("hunterboard.uitour.habitatfeed")?.let { url ->
                if (!FeedCheck.tick(client, url, out!!)) return
            }
            // the Controlify support, before the pictures (Controlify and YACL in run/mods)
            if (System.getProperty("hunterboard.uitour.controlify") != null) {
                if (!ControlifyCheck.tick(client, out!!)) return
            }
            // the raid card's triggers, against a local relay, before the pictures
            System.getProperty("hunterboard.uitour.raid")?.let { url ->
                if (!RaidCheck.tick(client, url, out!!)) return
            }
            // Tropimon's paintings held in hand (Tropifurnitures and GeckoLib in run/mods)
            if (System.getProperty("hunterboard.uitour.painting") != null) {
                if (!PaintingCheck.tick(client, out!!, System.getProperty("hunterboard.uitour.painting") ?: "")) return
            }
            // developer mode: /hbdebug there or not, and what it switches (on: -Pdev, the default)
            System.getProperty("hunterboard.uitour.devmode")?.let { want ->
                if (!DevModeCheck.tick(client, out!!, want == "on")) return
            }
            // the PC's end-to-end test against the integrated server instead of the pictures
            if (System.getProperty("hunterboard.uitour.pce2e") != null) {
                if (!auditing) { auditing = true; PcE2E.start(out!!) { finish(client) } }
                PcE2E.tick(client)
                return
            }
            // the wiki audit instead of the pictures (WikiAudit)
            System.getProperty("hunterboard.uitour.audit")?.let { mode ->
                if (!auditing) {
                    auditing = true
                    client.setScreen(WikiAudit.screen(out!!, mode) { finish(client) })
                }
                return
            }
            steps = plan()
            HunterBoard.LOGGER.info("UiTour: ${steps.size} pictures to take")
            index = 0
            wait = 0
        }
        if (index >= steps.size) { finish(client); return }

        val (theme, step) = steps[index]
        if (wait == 0) {
            applyTheme(theme)
            val screen = try { step.open(client) } catch (e: Throwable) {
                HunterBoard.LOGGER.warn("UiTour: ${step.name} could not open: $e"); null
            }
            if (screen == null) { index++; return }
            if (client.currentScreen !== screen) client.setScreen(screen)
            wait = 1
            return
        }
        if (++wait == SETTLE_TICKS / 2) step.then?.invoke(client)
        if (wait < SETTLE_TICKS) return
        val file = File(out, "${theme}_${step.name}.png")
        ScreenshotRecorder.takeScreenshot(client.framebuffer).use { it.writeTo(file) }
        index++
        wait = 0
    }

    private fun finish(client: MinecraftClient) {
        done = true
        HunterBoard.LOGGER.info("UiTour: done")
        client.scheduleStop()
    }

    private fun startWorld(client: MinecraftClient) {
        val loader = client.createIntegratedServerLoader()
        if (client.levelStorage.levelExists(WORLD)) {
            loader.start(WORLD) { client.setScreen(TitleScreen()) }
            return
        }
        val info = LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
            GameRules(), DataConfiguration.SAFE_MODE)
        loader.createAndStart(WORLD, info, GeneratorOptions(0L, false, false), { registries ->
            registries.get(RegistryKeys.WORLD_PRESET).entryOf(WorldPresets.FLAT).value().createDimensionsRegistryHolder()
        }, TitleScreen())
    }

    private fun applyTheme(theme: String) {
        if (theme == "modern") DevAccess.previewLook(null, true) else DevAccess.previewLook(theme, false)
    }

    // ── what gets photographed ──────────────────────────────────────────────────────

    private fun plan(): List<Pair<String, Step>> {
        val themes = (System.getProperty("hunterboard.uitour.themes") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(if (ModConfig.modernUi) "modern" else ModConfig.uiTheme) }
        val only = System.getProperty("hunterboard.uitour.only")?.split(',')?.filter { it.isNotBlank() }
        val all = allSteps().filter { s -> only == null || only.any { it in s.name } }
        return themes.flatMap { th -> all.map { th to it } }
    }

    private fun species(name: String) = PokemonSpecies.getByName(name)

    private fun allSteps(): List<Step> {
        val list = mutableListOf<Step>()
        fun step(name: String, then: ((MinecraftClient) -> Unit)? = null, open: (MinecraftClient) -> Screen?) {
            list += Step(name, then, open)
        }

        step("01_search") { PokemonSearchScreen() }
        step("02_search_typed") { PokemonSearchScreen().also { s -> it.setScreen(s); "drag".forEach { c -> s.charTyped(c, 0) } } }
        step("03_detail_charizard") { species("charizard")?.let { PokemonDetailScreen(it, PokemonSearchScreen()) } }
        for ((i, off) in listOf(330, 760, 99999).withIndex()) step("03_detail_scroll$i") {
            species("charizard")?.let { sp ->
                PokemonDetailScreen(sp, PokemonSearchScreen()).also { s -> it.setScreen(s); setField(s, "scrollOffset", off) }
            }
        }
        // stepping to the next Pokémon keeps the block being read
        step("03_detail_sibling", then = { c -> c.currentScreen?.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0) }) {
            val line = listOfNotNull(species("charmander"), species("charmeleon"), species("charizard"))
            PokemonDetailScreen(line[1], PokemonSearchScreen(), line).also { s -> it.setScreen(s); setField(s, "scrollOffset", 760) }
        }
        step("04_detail_eevee") { species("eevee")?.let { PokemonDetailScreen(it, PokemonSearchScreen()) } }
        step("05_moves") { MoveSearchScreen() }
        step("06_move_detail") { Moves.getByName("flamethrower")?.let { MoveDetailScreen(it, MoveSearchScreen()) } }
        step("07_abilities") { AbilitySearchScreen() }
        step("08_ability_detail") { Abilities.get("intimidate")?.let { AbilityDetailScreen(it, AbilitySearchScreen()) } }
        step("09_items") { ItemDropSearchScreen() }
        step("10_item_detail") { ItemDropDetailScreen(Identifier.of("minecraft", "bone"), ItemDropSearchScreen()) }
        step("11_habitats") { HabitatSearchScreen.savedQuery = ""; HabitatSearchScreen.savedScrollOffset = 0; HabitatSearchScreen() }
        // a search that only matches through a Pokémon names it on the tile
        step("11_habitats_typed") {
            HabitatSearchScreen.savedQuery = "terhal"; HabitatSearchScreen.savedScrollOffset = 0; HabitatSearchScreen()
        }
        step("11_habitats_scrolled") {
            HabitatSearchScreen.savedQuery = ""; HabitatSearchScreen.savedScrollOffset = 330; HabitatSearchScreen()
        }
        // the cursor parked on the first tile, for its tooltip
        step("11_habitats_hover", then = { c -> DevAccess.set(c.mouse, "x", 330.0); DevAccess.set(c.mouse, "y", 110.0) }) {
            HabitatSearchScreen.savedQuery = ""; HabitatSearchScreen.savedScrollOffset = 0; HabitatSearchScreen()
        }
        // one habitat per way the world places them: a structure, a feature, one added to villages
        for ((tag, id) in listOf("berry" to "cobblemon:berry_patch", "chorus" to "cobblemon:chorus_briar",
                "village" to "cobblemon:abandoned_village_house", "geode" to "cobblemon:exposed_geode")) {
            // the cursor out of the way, so no card's tooltip covers the page
            step("11_habitat_$tag", then = { c -> DevAccess.set(c.mouse, "x", 2.0); DevAccess.set(c.mouse, "y", 2.0) }) {
                HabitatDetailScreen(id, HabitatSearchScreen())
            }
        }
        // the third of Chorus Briar's phases, reached with the arrow keys
        step("11_habitat_chorus_phase3", then = { c ->
            DevAccess.set(c.mouse, "x", 2.0); DevAccess.set(c.mouse, "y", 2.0)
            repeat(2) { c.currentScreen?.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0) }
        }) { HabitatDetailScreen("cobblemon:chorus_briar", HabitatSearchScreen()) }
        // the cursor on "Technical details", for its tooltip
        step("11_habitat_berry_tech", then = { c -> DevAccess.set(c.mouse, "x", 520.0); DevAccess.set(c.mouse, "y", 144.0) }) {
            HabitatDetailScreen("cobblemon:berry_patch", HabitatSearchScreen())
        }
        step("12_spawn_info") { SpawnInfoScreen() }
        // HunterBoard's radar on a cave-like pool. It wears Tropimon's frame when that texture is
        // loaded (resource pack run/resourcepacks/tropiradar_dev, never shipped), the theme otherwise.
        val cave = listOf("zubat", "geodude", "roggenrola", "woobat", "diglett", "onix", "rattata", "sandshrew",
            "nosepass", "aron", "makuhita", "machop", "drilbur", "timburr", "mawile", "sableye", "carbink", "noibat",
            "rolycoly", "nacli", "tinkatink", "cufant", "larvitar", "gible", "axew")
        fun radar(n: Int): HunterRadarScreen {
            val slots = (0 until n).mapNotNull { i ->
                species(cave[i % cave.size])?.let { HunterRadarScreen.RadarSlot(it, 11.31f / (1f + i * 0.45f)) }
            }
            return HunterRadarScreen(slots, null)
        }
        val away: (MinecraftClient) -> Unit = { c -> DevAccess.set(c.mouse, "x", 2.0); DevAccess.set(c.mouse, "y", 300.0) }
        step("60_radar", then = away) { radar(25) }
        step("60_radar_hover", then = { c -> DevAccess.set(c.mouse, "x", 300.0); DevAccess.set(c.mouse, "y", 230.0) }) { radar(25) }
        step("60_radar_many", then = away) { radar(90) }
        // a click on a cell opens the wiki page, and Escape there comes back to the radar
        step("60_radar_wiki_back", then = away) { c ->
            val r = radar(25)
            c.setScreen(r)
            DevAccess.set(r, "hoveredSlot", 0)
            r.mouseClicked(c.window.scaledWidth - 2.0, c.window.scaledHeight - 2.0, 0)
            val opened = c.currentScreen
            opened?.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0)
            HunterBoard.LOGGER.info("UiTour: radar click opened ${opened?.javaClass?.simpleName}, Escape went back to ${c.currentScreen?.javaClass?.simpleName} (same radar: ${c.currentScreen === r})")
            r
        }
        // HunterBoard's PC on a made-up PC: 12 boxes, duplicates, a few shinies, a party
        fun fakePc(only: List<String>? = null): com.hunterboard.pc.HunterPcScreen {
            val rnd = java.util.Random(7)
            val pool = only ?: (cave + listOf("pikachu", "eevee", "bulbasaur", "charmander", "squirtle", "gastly", "magikarp", "ralts", "riolu", "dratini"))
            fun mon(i: Int): com.cobblemon.mod.common.pokemon.Pokemon? = runCatching {
                val name = pool[(i * 7 + rnd.nextInt(5)) % pool.size]
                val shiny = if (rnd.nextInt(14) == 0) " shiny=true" else ""
                com.cobblemon.mod.common.api.pokemon.PokemonProperties.parse("$name level=${5 + rnd.nextInt(60)}$shiny").create()
            }.getOrNull()
            val pc = com.cobblemon.mod.common.client.storage.ClientPC(java.util.UUID.randomUUID(), 12)
            var n = 0
            for (b in 0 until 12) for (s in 0 until 30) {
                val fill = when (b) { in 0..3 -> 0.95f; in 4..7 -> 0.6f; 8 -> 0.2f; else -> 0f }
                if (rnd.nextFloat() < fill) mon(n++)?.let { pc.set(com.cobblemon.mod.common.api.storage.pc.PCPosition(b, s), it) }
            }
            val party = com.cobblemon.mod.common.client.storage.ClientParty(java.util.UUID.randomUUID(), 6)
            for (i in 0 until 5) mon(n++)?.let { party.set(com.cobblemon.mod.common.api.storage.party.PartyPosition(i), it) }
            // a few marks on the first Pokémon, to see them in the detail panel
            runCatching {
                val marks = com.cobblemon.mod.common.api.mark.Marks.all().take(7)
                pc.boxes[0].slots.firstOrNull { it != null }?.let { p -> marks.forEach { m -> p.exchangeMark(m, true) }; p.activeMark = marks.firstOrNull() }
            }
            val prefs = com.hunterboard.pc.PcPrefs.detached()
            prefs.setBoxRule(8, "shiny")
            return com.hunterboard.pc.HunterPcScreen(com.hunterboard.pc.PcSession(pc, party, null, offline = true), prefs)
        }
        fun dialog(s: com.hunterboard.pc.HunterPcScreen, cls: String, vararg args: Any) {
            val k = Class.forName("com.hunterboard.pc.$cls")
            val c = k.declaredConstructors.first { it.parameterCount == args.size + 1 }
            c.isAccessible = true
            setField(s, "dialog", c.newInstance(s, *args))
        }
        // mouse coordinates are screen pixels: scale them with the window (uitour.size)
        val mouseK = MinecraftClient.getInstance().window.width / 960.0
        val pcAway: (MinecraftClient) -> Unit = { c -> DevAccess.set(c.mouse, "x", 940.0); DevAccess.set(c.mouse, "y", 520.0) }
        step("70_pc_boxes", then = pcAway) { fakePc() }
        // tall, long and wide Pokémon: each portrait must fit its cell whole
        step("70_pc_big", then = pcAway) {
            fakePc(listOf("wailord", "steelix", "onix", "rayquaza", "gyarados", "lugia", "eternatus", "dialga", "groudon",
                "kyogre", "snorlax", "mewtwo", "giratina", "arceus", "aggron", "tyranitar", "metagross", "milotic",
                "exeggutor alolan", "dragonite", "charizard", "wailmer", "seviper", "dhelmise", "centiskorch", "palkia",
                "zygarde", "regigigas", "hoopa unbound", "yveltal"))
        }
        step("70_pc_hover", then = { c -> DevAccess.set(c.mouse, "x", 196.0); DevAccess.set(c.mouse, "y", 80.0) }) { fakePc() }
        step("70_pc_list", then = pcAway) { fakePc().also { s -> it.setScreen(s); setField(s, "view", "details") } }
        step("70_pc_details_hover", then = { c -> DevAccess.set(c.mouse, "x", 200.0); DevAccess.set(c.mouse, "y", 80.0) }) {
            fakePc().also { s -> it.setScreen(s); setField(s, "view", "details") }
        }
        // the cursor on a move tile of the detail panel, for its tooltip
        step("70_pc_move_tip", then = { c -> DevAccess.set(c.mouse, "x", 780.0 * mouseK); DevAccess.set(c.mouse, "y", 300.0 * mouseK) }) {
            fakePc().also { s -> it.setScreen(s); setField(s, "focus", s.entries.first { e -> !e.place.inParty }.uuid) }
        }
        step("70_pc_box_menu", then = pcAway) {
            fakePc().also { s ->
                it.setScreen(s)
                s.javaClass.getDeclaredMethod("openBoxMenu", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(s, 8, 300, 330)
            }
        }
        step("70_pc_teams", then = pcAway) {
            fakePc().also { s ->
                it.setScreen(s)
                val party = s.entries.filter { e -> e.place.inParty }
                s.prefs.saveTeam(com.hunterboard.pc.PcTeam("Ligue", party.map { e -> com.hunterboard.pc.PcTeamMember(e.uuid.toString(), e.pokemon.moveSet.getMoves().map { m -> m.name }) }))
                s.prefs.saveTeam(com.hunterboard.pc.PcTeam("Raid", party.take(3).map { e -> com.hunterboard.pc.PcTeamMember(e.uuid.toString()) }))
                s.prefs.saveTeam(com.hunterboard.pc.PcTeam("Nouvelle"))
            }
        }
        // the cursor on a team member: its moves for that team, the detail column shows it
        step("70_pc_team_tip", then = { c -> DevAccess.set(c.mouse, "x", 21.0 * mouseK); DevAccess.set(c.mouse, "y", 391.0 * mouseK) }) {
            fakePc().also { s ->
                it.setScreen(s)
                val party = s.entries.filter { e -> e.place.inParty }
                val first = party.first()
                val other = first.pokemon.allAccessibleMoves.map { m -> m.name }.firstOrNull { n -> n !in first.pokemon.moveSet.getMoves().map { m -> m.name } }
                val moves = listOfNotNull(other) + first.pokemon.moveSet.getMoves().map { m -> m.name }
                s.prefs.saveTeam(com.hunterboard.pc.PcTeam("Ligue").withSlots(listOf(com.hunterboard.pc.PcTeamMember(first.uuid.toString(), moves.take(4)), null) +
                    party.drop(1).take(3).map { e -> com.hunterboard.pc.PcTeamMember(e.uuid.toString()) }))
            }
        }
        step("70_pc_team_edit", then = pcAway) {
            fakePc().also { s -> it.setScreen(s); setField(s, "dialog", com.hunterboard.pc.TeamEditDialog.fromParty(s)) }
        }
        step("70_pc_moves", then = { c -> DevAccess.set(c.mouse, "x", 600.0 * mouseK); DevAccess.set(c.mouse, "y", 230.0 * mouseK) }) {
            fakePc().also { s -> it.setScreen(s); dialog(s, "MovesDialog", s.entries.filter { e -> !e.place.inParty }.maxBy { e -> e.level }) }
        }
        step("70_pc_sorted_details", then = pcAway) {
            fakePc().also { s -> it.setScreen(s); setField(s, "view", "details"); s.setFilter(s.filter.copy(sort = com.hunterboard.pc.PcFilterState.SORT_IV)) }
        }
        step("70_pc_selected", then = pcAway) {
            fakePc().also { s ->
                it.setScreen(s)
                s.setFilter(s.filter.copy(duplicates = true, sort = com.hunterboard.pc.PcFilterState.SORT_IV))
                s.selectAllResults()
            }
        }
        step("70_pc_release", then = pcAway) {
            fakePc().also { s ->
                it.setScreen(s)
                s.setFilter(s.filter.copy(query = "zubat"))
                s.selectAllResults()
                dialog(s, "ReleaseDialog", s.results)
            }
        }
        step("70_pc_candidates", then = pcAway) { fakePc().also { s -> it.setScreen(s); dialog(s, "CandidatesDialog") } }
        // rules: one species, a Speed of 31 and a nature, all needed
        step("70_pc_candidates_rules", then = pcAway) {
            fakePc().also { s ->
                it.setScreen(s)
                val line = s.entries.filter { e -> !e.place.inParty }.groupBy { e -> e.lineKey }.maxBy { g -> g.value.size }
                s.prefs.release = com.hunterboard.pc.ReleaseRules(scope = "species:${line.key}", matchAll = true,
                    ivs = listOf(-1, 0, -1, -1, -1, 31), nature = line.value.first().natureId)
                dialog(s, "CandidatesDialog")
            }
        }
        step("70_pc_arrange", then = pcAway) { fakePc().also { s -> it.setScreen(s); dialog(s, "ArrangeDialog") } }
        step("70_pc_compare", then = pcAway) {
            fakePc().also { s -> it.setScreen(s); dialog(s, "CompareDialog", s.entries[6], s.entries[9]) }
        }
        step("70_pc_tags", then = pcAway) { fakePc().also { s -> it.setScreen(s); dialog(s, "TagManagerDialog") } }
        step("60_radar_filter", then = away) {
            radar(25).also { s ->
                it.setScreen(s); setField(s, "evFilter", 1)
                s.javaClass.getDeclaredMethod("recomputeDisplay").apply { isAccessible = true }.invoke(s)
            }
        }
        for (tab in 0..7) step("20_options_tab$tab") {
            OptionsScreen(PokemonSearchScreen()).also { s -> it.setScreen(s); setField(s, "tab", tab) }
        }
        step("30_capture_filters") {
            species("eevee")?.let { CaptureFilters.create(it) }
            species("gible")?.let { CaptureFilters.create(it) }
            CaptureFilterScreen(null)
        }
        step("31_species_picker") { SpeciesPickerScreen(CaptureFilterScreen(null)) {} }
        step("32_sound_picker") { SoundPickerScreen(OptionsScreen(PokemonSearchScreen()), ModConfig.miracleSound) {} }
        step("33_donors") { DonorsScreen(PokemonSearchScreen()) }
        step("35_filters_scopes") {
            val eevee = species("eevee")?.let { CaptureFilters.create(it) }
            eevee?.let { f -> species("eevee")?.let { sp -> CaptureFilters.family(sp).forEach { CaptureFilters.addSpecies(f, it) } } }
            CaptureFilters.createGlobal()
            CaptureFilterScreen(null).also { s -> eevee?.let { setField(s, "selectedId", it.id) } }
        }
        // a family asking for Drizzle: Wingull's Hydration becomes Drizzle in Pelipper
        step("36_filter_evo_ability") {
            val f = species("wingull")?.let { CaptureFilters.create(it) } ?: return@step null
            species("pelipper")?.let { CaptureFilters.addSpecies(f, it.resourceIdentifier.path) }
            f.groups.clear()
            f.groups.add(CaptureFilters.Group(mutableListOf(
                CaptureFilters.Condition(CaptureFilters.Field.ABILITY.id, "=", 0, "drizzle"))))
            CaptureFilters.changed()
            for (a in listOf("hydration", "keeneye", "raindish", "drizzle")) {
                val s = CaptureFilters.Subject(null, null, null, a, null, null, "wingull")
                HunterBoard.LOGGER.info("UiTour: wingull $a -> ${CaptureFilters.verdict(f, s)}")
            }
            HunterBoard.LOGGER.info("UiTour: carried ${CaptureFilters.carriedCases(f).map { "${it.species}:${it.ability}->${it.wanted}@${it.into}" }}")
            CaptureFilterScreen(null).also { s -> setField(s, "selectedId", f.id) }
        }
        // evolution conditions read from every kind of requirement
        // Minecraft's GUI scale above HunterBoard's cap: the list keeps the capped size when
        // opened for the first time and when come back to from a detail page. (The steps
        // above open their screen through setScreen twice, which hid a first showing at
        // Minecraft's own scale.) The window here allows GUI scale 2 at most, so the cap is
        // lowered to 1 for these steps.
        step("50_scale_first_open") { c ->
            c.options.guiScale.setValue(2); c.onResolutionChanged()
            DevAccess.set(ModConfig, "uiScaleCap", 1)
            PokemonSearchScreen().also { s ->
                c.setScreen(s)
                HunterBoard.LOGGER.info("UiTour: scale first open width=${s.width} window=${c.window.scaledWidth}")
            }
        }
        step("51_scale_back") { c ->
            val list = PokemonSearchScreen()
            c.setScreen(list)
            val first = list.width
            species("charizard")?.let { c.setScreen(PokemonDetailScreen(it, list)) }
            val detail = c.currentScreen?.width
            c.setScreen(list)
            HunterBoard.LOGGER.info("UiTour: scale list first=$first detail=$detail back=${list.width} window=${c.window.scaledWidth}")
            list
        }
        // the same capped scale: Cobblemon's model widgets clip themselves outside the scale
        // matrix (ModelWidgetScissorMixin), so the radar's and the wiki's models must show
        step("53_scale_radar", then = away) { radar(25) }
        step("53_scale_detail") { species("charizard")?.let { PokemonDetailScreen(it, PokemonSearchScreen()) } }
        // as in a player's video: the radar, a click to its wiki page, back to the game, the
        // radar used again (the same screen comes back, refreshed)
        step("53_scale_radar_reopen", then = away) { c ->
            val r = radar(25)
            c.setScreen(r)
            species("zubat")?.let { c.setScreen(PokemonDetailScreen(it, null)) }
            c.setScreen(null)
            r.refreshData(radar(25).let { fresh -> DevAccess.get(fresh, "slots") as List<HunterRadarScreen.RadarSlot> })
            r
        }
        step("52_scale_restore") { c ->
            c.options.guiScale.setValue(1); c.onResolutionChanged()
            DevAccess.set(ModConfig, "uiScaleCap", 2)
            null
        }
        // a code made of every filter, read back, shown in the import window
        step("39_filter_share") {
            val all = CaptureFilters.allForExport()
            val code = CaptureFilters.exportCode(all)
            val back = CaptureFilters.decodeCode(code)
            HunterBoard.LOGGER.info("UiTour: share code (${code.length} chars, ${all.size} filters) $code")
            HunterBoard.LOGGER.info("UiTour: share decoded error=${back.error} same=${CaptureFilters.exportCode(back.filters) == code}")
            for (f in back.filters) HunterBoard.LOGGER.info("UiTour: share   ${CaptureFilters.displayName(f)} : ${CaptureFilters.describe(f)}")
            all.firstOrNull { !it.all }?.let { one ->
                val c1 = CaptureFilters.exportCode(listOf(one))
                HunterBoard.LOGGER.info("UiTour: share one (${c1.length} chars) $c1")
                HunterBoard.LOGGER.info("UiTour: share chat '${CaptureFilters.withImportLink(net.minecraft.text.Text.literal("voilà : $c1 merci"))?.string}'")
            }
            for (bad in listOf("bonjour", "HBF1:abc", "HBF9:abcd", code.dropLast(6)))
                HunterBoard.LOGGER.info("UiTour: share bad '${bad.take(20)}' -> ${CaptureFilters.decodeCode(bad).error}")
            CaptureFilterScreen(null, code)
        }
        step("39_filter_share_error") { CaptureFilterScreen(null, "HBF1:abc") }
        step("38_evo_requirements") {
            for (name in listOf("tyrogue", "kirlia", "farfetchd", "ursaring", "spewpa", "toxel", "inkay",
                                "wurmple", "gimmighoul", "burmy", "espurr", "mantyke", "pancham")) {
                val sp = species(name) ?: continue
                for (form in listOf<String?>(null) + sp.forms.map { it.name.lowercase() }) {
                    val root = EvolutionData.getEvolutionTree(sp, form) ?: continue
                    fun walk(n: EvoNode) {
                        if (n.condition.isNotEmpty()) HunterBoard.LOGGER.info("UiTour: evo $name${form?.let { "/$it" } ?: ""} -> " +
                            "${n.speciesName}${n.formAspect?.let { "($it)" } ?: ""} '${n.condition}' => '${Translations.formatEvoCondition(n.condition)}'")
                        n.children.forEach { walk(it) }
                    }
                    walk(root)
                }
            }
            null
        }
        // evolution conditions with a biome and a NOT (Cobblemon's "negate")
        for ((i, off) in listOf(0, 400).withIndex()) step("37_evo_conditions$i") {
            val sp = species("pikachu") ?: return@step null
            val form = sp.forms.firstOrNull { "alola" in it.name.lowercase() }?.name
            if (i == 0) EvolutionData.getEvolutionTree(sp, form?.lowercase())?.let { root ->
                fun walk(n: EvoNode) {
                    HunterBoard.LOGGER.info("UiTour: evo ${n.speciesName} '${n.condition}' -> '${Translations.formatEvoCondition(n.condition)}'")
                    n.children.forEach { walk(it) }
                }
                walk(root)
            }
            PokemonDetailScreen(sp, PokemonSearchScreen(), null, form).also { s -> it.setScreen(s); setField(s, "scrollOffset", off) }
        }
        step("34_hud_position") { HudPositionScreen(OptionsScreen(PokemonSearchScreen())) }
        // with a hunting board: the HUD (behind an empty chat) and the spawn page
        fun board() = BoardState.updateBoard(listOf(
            HuntTarget("Dracaufeu", "charizard", emptySet(), "Hyper Ball", "ultra_ball", false, 900, "legendary"),
            HuntTarget("Évoli", "eevee", emptySet(), "Poké Ball", "poke_ball", true, 300, "common"),
            HuntTarget("Griknot", "gible", emptySet(), "Super Ball", "great_ball", false, 500, "rare"),
            HuntTarget("Minidraco", "dratini", emptySet(), "Sombre Ball", "dusk_ball", false, 400, "rare"),
        ))
        step("40_hud") { board(); net.minecraft.client.gui.screen.ChatScreen("") }
        step("43_raid_card") { DevAccess.previewRaid("gallade", true); net.minecraft.client.gui.screen.ChatScreen("") }
        step("44_raid_card_unknown") { DevAccess.previewRaid(null, false); net.minecraft.client.gui.screen.ChatScreen("") }
        step("45_hud_done") {
            BoardState.updateBoard(listOf(HuntTarget("Évoli", "eevee", emptySet(), "Poké Ball", "poke_ball", true, 300, "common")))
            BoardState.showBravo = true
            BoardState.bravoStartTime = System.currentTimeMillis()
            net.minecraft.client.gui.screen.ChatScreen("")
        }
        step("46_abilities_sorted") { AbilitySearchScreen().also { s -> it.setScreen(s); setField(s, "scrollOffset", 16 * 95) } }
        step("41_spawn_info_data") { board(); SpawnInfoScreen() }
        step("42_spawn_info_scrolled") { board(); SpawnInfoScreen().also { s -> it.setScreen(s); setField(s, "scrollOffset", 560) } }
        return list
    }

    /** Sets a private field by name, for the states a click would normally produce. */
    private fun setField(target: Any, name: String, value: Any) {
        try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("UiTour: no field $name on ${target.javaClass.simpleName}")
        }
    }
}
