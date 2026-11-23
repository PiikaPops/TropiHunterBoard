package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.OrderedText
import net.minecraft.text.Text
import java.io.File

/**
 * Development tool: reads every page of the wiki as a player sees it, and lists the text
 * that looks unfinished — raw identifiers, a lone "?", words left in English…
 *
 * Every Pokémon (each form, each move tab), move, ability, dropped item and habitat page
 * is drawn off to the side, scrolled from top to bottom, while [capture] records what is
 * written (`DrawContextTextMixin`). Run with
 * `gradlew runClient -Puitour=<folder> -Puitour.audit=files` (the data a Tropimon client
 * reads) or `=api` (a single-player world's own); the report lands in the folder.
 */
object WikiAudit {

    /** Set only while a wiki page is being drawn for the audit. */
    @JvmField var capturing = false

    private class Seen(var count: Int = 0, val where: MutableList<String> = mutableListOf())
    private val seen = LinkedHashMap<String, Seen>()
    private var where = ""
    private val failures = mutableListOf<String>()

    @JvmStatic
    fun capture(text: String?) {
        if (!capturing || text.isNullOrBlank()) return
        val s = seen.getOrPut(text.trim()) { Seen() }
        s.count++
        if (s.where.size < 4 && where !in s.where) s.where += where
    }

    @JvmStatic
    fun capture(text: OrderedText?) {
        if (!capturing || text == null) return
        val sb = StringBuilder()
        text.accept { _, _, cp -> sb.appendCodePoint(cp); true }
        capture(sb.toString())
    }

    // ── the pages ──────────────────────────────────────────────────────────────────

    private class Job(val where: String, val tabs: Int = 1, val make: () -> Screen?)

    private fun jobs(): List<Job> {
        val parent = PokemonSearchScreen()
        val list = mutableListOf<Job>()
        for (sp in PokemonSpecies.implemented.sortedBy { it.nationalPokedexNumber }) {
            val forms = listOf<String?>(null) + sp.forms.map { it.name }.filter { !it.equals("Normal", true) }.distinct()
            for (form in forms) {
                list += Job("Pokémon ${sp.name}${form?.let { " ($it)" } ?: ""}", tabs = 4) {
                    PokemonDetailScreen(sp, parent, null, form)
                }
            }
        }
        for (m in MoveData.getAllMoves()) list += Job("Capacité ${m.name}") { MoveDetailScreen(m, parent) }
        for (a in AbilityData.getAllAbilities()) list += Job("Talent ${a.name}") { AbilityDetailScreen(a, parent) }
        for (i in DropData.getAllDroppableItems()) list += Job("Objet $i") { ItemDropDetailScreen(i, parent) }
        for (h in HabitatData.allHabitats()) list += Job("Habitat ${h.habitatId}") { HabitatDetailScreen(h.habitatId, parent) }
        return list
    }

    /** The data a Tropimon client reads (Cobblemon's files + the server's), not the world's own. */
    private fun loadFromFiles() {
        for (target in listOf(SpawnData, HabitatData)) {
            try {
                target.javaClass.getDeclaredField("isLoaded").apply { isAccessible = true }.setBoolean(target, false)
                target.javaClass.getDeclaredMethod("loadFromResources").apply { isAccessible = true }.invoke(target)
            } catch (e: Exception) {
                failures += "load ${target.javaClass.simpleName} from files: $e"
            }
        }
    }

    fun screen(out: File, mode: String, onDone: () -> Unit): Screen {
        MoveData.buildReverseIndex()
        AbilityData.buildReverseIndex()
        DropData.buildReverseIndex()
        if (mode == "files") loadFromFiles() else { SpawnData.ensureLoaded(); HabitatData.ensureLoaded() }
        val all = jobs()
        HunterBoard.LOGGER.info("WikiAudit: ${all.size} pages to read ($mode)")
        return AuditScreen(all) {
            File(out, "wiki_audit_$mode.txt").writeText(report(mode, all.size))
            // everything, for what no rule catches
            File(out, "wiki_texts_$mode.txt").writeText(seen.entries.sortedByDescending { it.value.count }
                .joinToString("\n") { (text, s) -> "[${s.count}×] $text    ← ${s.where.firstOrNull() ?: ""}" })
            HunterBoard.LOGGER.info("WikiAudit: report written to ${File(out, "wiki_audit_$mode.txt").absolutePath}")
            onDone()
        }
    }

    private class AuditScreen(private val jobs: List<Job>, private val onDone: () -> Unit) : Screen(Text.literal("Wiki audit")) {
        private var next = 0
        private var finished = false

        override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            val until = System.nanoTime() + 45_000_000L
            while (next < jobs.size && System.nanoTime() < until) read(jobs[next++], context)
            context.fill(0, 0, width, height, 0xFF101010.toInt())
            context.drawText(textRenderer, "Wiki audit: $next / ${jobs.size}", 10, 10, -1, false)
            if (next >= jobs.size && !finished) { finished = true; onDone() }
        }

        private fun read(job: Job, context: DrawContext) {
            val client = MinecraftClient.getInstance()
            val screen = try { job.make() } catch (e: Throwable) { failures += "${job.where}: $e"; null } ?: return
            try { screen.init(client, width, height) } catch (e: Throwable) { failures += "${job.where} init: $e"; return }
            for (tab in 0 until job.tabs) {
                if (job.tabs > 1) set(screen, "selectedTab", tab)
                var offset = 0
                for (step in 0 until 60) {
                    set(screen, "scrollOffset", offset)
                    where = job.where + if (job.tabs > 1) " [onglet ${tab + 1}]" else ""
                    context.matrices.push()
                    capturing = true
                    try { screen.render(context, -1000, -1000, 0f) }
                    catch (e: Throwable) { if (failures.size < 200) failures += "$where: $e" }
                    finally { capturing = false; context.matrices.pop() }
                    offset += 180
                    if (offset > (get(screen, "contentHeight") ?: 0)) break
                }
            }
            try { screen.removed() } catch (_: Throwable) {}
        }

        override fun shouldCloseOnEsc() = false
    }

    private fun set(target: Any, name: String, value: Int) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try { c.getDeclaredField(name).apply { isAccessible = true }.setInt(target, value); return } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
    }

    private fun get(target: Any, name: String): Int? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.getInt(target) } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        return null
    }

    // ── what looks unfinished ──────────────────────────────────────────────────────

    private class Rule(val name: String, val test: (String) -> Boolean)

    /** Words a French page should not show; each one has a translation somewhere. */
    private val ENGLISH = Regex("""\b(Any|Day|Night|Dawn|Dusk|Twilight|Morning|Noon|Midnight|Rain|Thunder|Clear|Weather|Weight|Height|Light|SkyLight|Moon|Lure|Bait|Rod|Filler|Common|Uncommon|Hidden|Level|Lv|Nearby|Excluded|Sky|Submerged|Seafloor|Grounded|Fishing|Herd|Leader|Item|Held|Hold|Trade|Friendship|Move|Moves|Not|Outside|Walk|blocks|Party|Defeat|Damage|Recoil|Attack|Defense|Speed|Special|Power|Accuracy|Category|Physical|Status|Ability|Abilities|Egg|Tutor|Evolution|Drops?|Spawn|Spawns|Context|Depth|Space|Fluid|Markers?|Slime|Chunk|Natural|Forest|Plains|Ocean|River|Mountain|Desert|Swamp|Beach|Snowy|Cave|Island|Tropical|Overworld|Frozen|Warm|Cold|Lukewarm|Loading|None|Unknown|Yes|No|Jigsaw|Bias)\b""")

    private val RULES = listOf(
        // a "?" after anything but a word ("Confirmer ?" is French punctuation, "×2 ?" is not)
        Rule("« ? » affiché") { Regex("""(^|[^\p{L}\s])\s*\?""").containsMatchIn(it) },
        Rule("Identifiant brut (namespace:chemin)") { Regex("""\b[a-z0-9_.-]+:[a-z0-9_/.-]*[a-z_/][a-z0-9_/.-]*""").containsMatchIn(it) },
        Rule("Tiret bas (_)") { Regex("""\w_\w""").containsMatchIn(it) },
        Rule("Tag (#…)") { Regex("""#[a-z]""").containsMatchIn(it) },
        Rule("Clé de traduction") { Regex("""\b[a-z0-9_]+\.[a-z0-9_]+\.[a-z0-9_.]+\b""").containsMatchIn(it) },
        Rule("Mot technique (null, true, negate…)") { Regex("""\b(null|NaN|true|false|negate|undefined|Infinity|any)\b""").containsMatchIn(it) },
        // a comparison that lost what it compares: "Nv. 20,  =" (Tyrogue, before 1.8 field names were read)
        Rule("Opérateur orphelin") { Regex("""(^|,)\s*[=<>≤≥]\s*($|,)""").containsMatchIn(it) },
        Rule("Mot isolé en minuscules") { Regex("""^[a-z][a-z0-9_:/.-]{2,}$""").matches(it) },
        Rule("Mot anglais sur une page française") { Translations.isFrench() && ENGLISH.containsMatchIn(it) },
    )

    private fun report(mode: String, pages: Int): String = buildString {
        appendLine("Wiki audit ($mode) — $pages pages, ${seen.size} distinct texts, language ${if (Translations.isFrench()) "fr" else "en"}")
        appendLine()
        for (rule in RULES) {
            val hits = seen.entries.filter { (text, _) -> try { rule.test(text) } catch (_: Exception) { false } }
                .sortedByDescending { it.value.count }
            appendLine("== ${rule.name} : ${hits.size}")
            for ((text, s) in hits.take(400)) appendLine("  [${s.count}×] $text    ← ${s.where.joinToString(" | ")}")
            appendLine()
        }
        if (failures.isNotEmpty()) {
            appendLine("== Pages qui ont échoué : ${failures.size}")
            failures.take(200).forEach { appendLine("  $it") }
        }
    }
}
