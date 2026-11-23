package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * Detects TropiMod's PokeRadarScreen via ScreenEvents.AFTER_INIT,
 * extracts pool data via reflection, and replaces it with HunterRadarScreen.
 * Mixin stubs (recordRow, onRadarRenderHead, onRadarRenderTail) are kept
 * as no-ops to satisfy Java mixin compilation.
 */
object RadarEnhancer {

    // ── EV stat colors and labels (referenced by HunterRadarScreen) ──────────

    val EV_COLORS = mapOf(
        "hp"              to 0xFFFF88BB.toInt(),
        "attack"          to 0xFFFF4444.toInt(),
        "defence"         to 0xFFFFDD00.toInt(),
        "special_attack"  to 0xFF1155BB.toInt(),
        "special_defence" to 0xFF4499FF.toInt(),
        "speed"           to 0xFF44FF88.toInt()
    )

    val EV_LABELS = mapOf(
        "hp"              to "PV",
        "attack"          to "Att",
        "defence"         to "Déf",
        "special_attack"  to "Atq.Sp",
        "special_defence" to "Déf.Sp",
        "speed"           to "Vit"
    )

    val EV_ORDER = listOf("hp", "attack", "defence", "special_attack", "special_defence", "speed")

    val STAT_TO_EV_KEY = listOf(
        Stats.HP              to "hp",
        Stats.ATTACK          to "attack",
        Stats.DEFENCE         to "defence",
        Stats.SPECIAL_ATTACK  to "special_attack",
        Stats.SPECIAL_DEFENCE to "special_defence",
        Stats.SPEED           to "speed"
    )

    /** Prevents AFTER_INIT from firing multiple times for the same screen instance. */
    private val registeredScreenIds: MutableSet<Any> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /** Last extracted pool data — kept so TropiMod→HunterBoard switch can reuse it. */
    private var lastSlots: List<HunterRadarScreen.RadarSlot> = emptyList()
    private var lastTropiScreen: Any? = null

    /** The currently open HunterRadarScreen, if any — reused on refresh instead of reopening. */
    internal var currentHunterScreen: HunterRadarScreen? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Registration
    // ─────────────────────────────────────────────────────────────────────────

    fun register() {
        ScreenEvents.AFTER_INIT.register afterInit@{ _, screen, _, _ ->
            // Skip our own replacement screen to avoid infinite loop
            if (screen is HunterRadarScreen) return@afterInit

            val cls = screen.javaClass.name
            if (!cls.contains("PokeRadar")) return@afterInit
            if (!registeredScreenIds.add(screen)) return@afterInit

            HunterBoard.LOGGER.info("[RadarEnhancer] Radar screen detected: $cls")

            val slots = extractPoolData(screen)

            if (slots.isNotEmpty()) {
                openOrOverlay(slots, screen)
            } else {
                // Pool is empty at init time (e.g. moss block: TropiMod populates pool after screen opens).
                // Retry extraction on each tick for up to 5 seconds.
                HunterBoard.LOGGER.info("[RadarEnhancer] Pool empty at init for $cls — scheduling retry via tick")
                var ticksWaited = 0
                var applied = false
                ScreenEvents.afterTick(screen).register tickLoop@{ s ->
                    if (applied) return@tickLoop
                    ticksWaited++
                    if (ticksWaited > 100) return@tickLoop  // give up after ~5 s
                    val retried = extractPoolData(s)
                    if (retried.isNotEmpty()) {
                        HunterBoard.LOGGER.info("[RadarEnhancer] Pool populated after $ticksWaited tick(s) — applying")
                        applied = true
                        openOrOverlay(retried, s)
                    }
                }
            }
        }
        HunterBoard.LOGGER.info("[RadarEnhancer] registered")
    }

    /** Allows re-registering a screen (used when switching back from HunterRadarScreen to TropiMod). */
    fun clearRegistration(screen: Any) {
        registeredScreenIds.remove(screen)
    }

    // NOTE: the "open radar without holding the item" macro (G key) lives in the
    // standalone HunterBoard_Radar mod (com.hunterboard.radar.RadarTrigger).

    // ─────────────────────────────────────────────────────────────────────────
    // Mixin stubs — no-ops required so Java mixins compile
    // ─────────────────────────────────────────────────────────────────────────

    fun onRadarRenderHead(@Suppress("UNUSED_PARAMETER") screen: Any) {}

    fun onRadarRenderTail(
        @Suppress("UNUSED_PARAMETER") ctx: DrawContext,
        @Suppress("UNUSED_PARAMETER") mx: Int,
        @Suppress("UNUSED_PARAMETER") my: Int
    ) {}

    fun recordRow(
        @Suppress("UNUSED_PARAMETER") rowIndex: Int,
        @Suppress("UNUSED_PARAMETER") x: Int,
        @Suppress("UNUSED_PARAMETER") y: Int,
        @Suppress("UNUSED_PARAMETER") entryW: Int,
        @Suppress("UNUSED_PARAMETER") entryH: Int,
        @Suppress("UNUSED_PARAMETER") entry: Any
    ) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Open HunterRadarScreen or attach overlay button, depending on config
    // ─────────────────────────────────────────────────────────────────────────

    private fun openOrOverlay(slots: List<HunterRadarScreen.RadarSlot>, screen: Any) {
        lastSlots = slots
        lastTropiScreen = screen
        HunterBoard.LOGGER.info("[RadarEnhancer] Extracted ${slots.size} slots — opening HunterRadarScreen")
        if (ModConfig.useHunterRadar) {
            val existing = currentHunterScreen
            MinecraftClient.getInstance().execute {
                if (existing != null) {
                    existing.tropiScreen = screen
                    existing.refreshData(slots)
                    MinecraftClient.getInstance().setScreen(existing)
                } else {
                    val newScreen = HunterRadarScreen(slots, screen)
                    currentHunterScreen = newScreen
                    MinecraftClient.getInstance().setScreen(newScreen)
                }
            }
        } else {
            ScreenEvents.afterRender(screen as net.minecraft.client.gui.screen.Screen).register { _, ctx, mx, my, _ ->
                drawSwitchToHBButton(ctx, mx, my)
            }
            ScreenMouseEvents.allowMouseClick(screen).register { _: net.minecraft.client.gui.screen.Screen, mx: Double, my: Double, button: Int ->
                if (button == 0 && isSwitchToHBButtonHovered(mx.toInt(), my.toInt())) {
                    ModConfig.setUseHunterRadar(true)
                    val s = lastSlots; val t = lastTropiScreen
                    MinecraftClient.getInstance().execute {
                        val newScreen = HunterRadarScreen(s, t)
                        currentHunterScreen = newScreen
                        MinecraftClient.getInstance().setScreen(newScreen)
                    }
                    false
                } else true
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pool data extraction via reflection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The radar's list, read exactly the way Tropimon's own screen reads it.
     *
     * The server sends a `RadarPool`: one map from a spawn id ("geodude", "farfetch'd",
     * "numel-herd-2", "abomasnow-alpha-2") to that spawn's chance **per spawn attempt**,
     * already in percent. The chances only add up to 100 where every bucket has something
     * to offer: in the deep dark, a cave or water the common bucket is empty, the rest of
     * the attempts produce nothing, and the total falls to 5–75 %. Tropimon shows the
     * values as they are; so does this — rescaling them to 100 % made every deep dark
     * spawn look up to twenty times likelier than Tropimon's radar said.
     *
     * Which entries are shown follows Tropimon too (`PokeRadarEntry.normalize`, then a
     * species lookup): an id that names no species, a herd or an alpha, is left out by
     * both radars.
     */
    fun extractPoolData(screen: Any): List<HunterRadarScreen.RadarSlot> {
        val pool = findPoolMap(screen) ?: return emptyList()
        val slots = mutableListOf<HunterRadarScreen.RadarSlot>()
        var total = 0.0
        val unresolved = mutableListOf<String>()
        for ((key, value) in pool) {
            val id = key as? String ?: continue
            val chance = (value as? Number)?.toFloat() ?: continue
            if (id.isEmpty() || chance <= 0f) continue
            total += chance
            val species = speciesForRadarId(id)
            if (species == null) { unresolved += id; continue }
            slots += HunterRadarScreen.RadarSlot(species, chance)
        }
        if (pool.isNotEmpty()) {
            HunterBoard.LOGGER.info(
                "[RadarEnhancer] Radar pool: ${slots.size} species, ${unresolved.size} other entries " +
                    "(herds, alphas, unknown ids), total ${"%.2f".format(java.util.Locale.ROOT, total)}%"
            )
        }
        return slots.sortedByDescending { it.pct }
    }

    /** The `RadarPool`'s map: through its getter, or the first map a "fr." field of the screen holds. */
    private fun findPoolMap(screen: Any): Map<*, *>? {
        var cls: Class<*>? = screen.javaClass
        while (cls != null && cls.name != "net.minecraft.class_437") {
            for (f in cls.declaredFields) {
                val obj = try { f.isAccessible = true; f.get(screen) } catch (_: Exception) { null } ?: continue
                if (!obj.javaClass.name.startsWith("fr.")) continue
                val viaGetter = try {
                    obj.javaClass.getMethod("getPool").invoke(obj) as? Map<*, *>
                } catch (_: Exception) { null }
                if (viaGetter != null) return viaGetter
                for (inner in obj.javaClass.declaredFields) {
                    val v = try { inner.isAccessible = true; inner.get(obj) } catch (_: Exception) { null }
                    if (v is Map<*, *> && v.isNotEmpty()) return v
                }
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * Tropimon's `PokeRadarEntry.normalize` — decomposed, lower case, the punctuation of
     * "farfetch'd" / "jangmo-o" / "mr. mime" and the gender signs dropped, anything else
     * that is not a-z0-9 removed — then looked up as a cobblemon: species id.
     */
    private fun speciesForRadarId(id: String): com.cobblemon.mod.common.pokemon.Species? {
        val normalized = java.text.Normalizer.normalize(id, java.text.Normalizer.Form.NFD)
            .lowercase(java.util.Locale.ROOT)
            .replace("-", "").replace(" ", "").replace(".", "").replace("'", "")
            .replace("♀", "").replace("♂", "").replace("⚥", "")
            .replace(Regex("[^a-z0-9]"), "")
        if (normalized.isEmpty()) return null
        return try {
            PokemonSpecies.getByIdentifier(net.minecraft.util.Identifier.of("cobblemon", normalized))
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TropiMod overlay — "→ HunterBoard" switch button
    // ─────────────────────────────────────────────────────────────────────────

    private val SWITCH_BTN_LABEL = "→ HunterBoard"
    private val SWITCH_BTN_PAD   = 5
    private val SWITCH_BTN_H     = 14

    private fun switchBtnBounds(): Triple<Int, Int, Int> {
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val w = tr.getWidth(SWITCH_BTN_LABEL) + SWITCH_BTN_PAD * 2
        return Triple(4, 4, w)   // x, y, w
    }

    private fun isSwitchToHBButtonHovered(mx: Int, my: Int): Boolean {
        val (bx, by, bw) = switchBtnBounds()
        return mx in bx..(bx + bw) && my in by..(by + SWITCH_BTN_H)
    }

    private fun drawSwitchToHBButton(ctx: DrawContext, mx: Int, my: Int) {
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val (bx, by, bw) = switchBtnBounds()
        val hov = isSwitchToHBButtonHovered(mx, my)
        ctx.fill(bx, by, bx + bw, by + SWITCH_BTN_H,
            if (hov) 0xCC334466.toInt() else 0xAA111122.toInt())
        ctx.fill(bx, by, bx + bw, by + 1, UiKit.accent())
        ctx.fill(bx, by + SWITCH_BTN_H - 1, bx + bw, by + SWITCH_BTN_H, UiKit.accent())
        ctx.fill(bx, by, bx + 1, by + SWITCH_BTN_H, UiKit.accent())
        ctx.fill(bx + bw - 1, by, bx + bw, by + SWITCH_BTN_H, UiKit.accent())
        ctx.drawText(tr, SWITCH_BTN_LABEL, bx + SWITCH_BTN_PAD, by + 3,
            if (hov) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt(), false)
    }
}
