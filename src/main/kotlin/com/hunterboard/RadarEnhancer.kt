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

    fun extractPoolData(screen: Any): List<HunterRadarScreen.RadarSlot> {
        var cls: Class<*>? = screen.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                try {
                    f.isAccessible = true
                    val obj = f.get(screen) ?: continue
                    val objCls = obj.javaClass.name
                    if (!objCls.startsWith("fr.")) continue
                    HunterBoard.LOGGER.info("[RadarEnhancer] DEBUG field '${f.name}': $objCls")
                    for (innerF in obj.javaClass.declaredFields) {
                        try {
                            innerF.isAccessible = true
                            val v = innerF.get(obj)
                            HunterBoard.LOGGER.info("[RadarEnhancer] DEBUG  inner '${innerF.name}': ${v?.javaClass?.name} = $v")
                            if (v !is Map<*, *> || v.isEmpty()) continue
                            val weighted: List<Pair<String, Double>> = try {
                                v.entries.mapNotNull { entry ->
                                    val id = cleanSpeciesId(entry.key) ?: return@mapNotNull null
                                    val weight = (entry.value as? Number)?.toDouble() ?: 0.0
                                    if (weight <= 0.0) return@mapNotNull null
                                    Pair(id, weight)
                                }.sortedByDescending { it.second }
                            } catch (e: Exception) {
                                HunterBoard.LOGGER.warn("[RadarEnhancer] Exception building weighted list: ${e::class.java.name}: ${e.message}")
                                continue
                            }
                            if (weighted.isEmpty()) continue
                            val total = weighted.sumOf { it.second }
                            val slots = weighted.mapNotNull { pair ->
                                val species = try {
                                    PokemonSpecies.getByName(pair.first)
                                } catch (e: Exception) {
                                    HunterBoard.LOGGER.warn("[RadarEnhancer] getByName threw for '${pair.first}': ${e.message}")
                                    null
                                } ?: return@mapNotNull null
                                val pct = if (total > 0.0) (pair.second / total * 100).toFloat() else 0f
                                HunterRadarScreen.RadarSlot(species, pct)
                            }
                            HunterBoard.LOGGER.info("[RadarEnhancer] DEBUG  weighted=${weighted.size} slots=${slots.size} first='${weighted.firstOrNull()?.first}'")
                            if (slots.isNotEmpty()) return slots
                        } catch (e: Exception) {
                            HunterBoard.LOGGER.warn("[RadarEnhancer] Exception in inner field loop: ${e::class.java.name}: ${e.message}")
                        }
                    }
                } catch (_: Exception) {}
            }
            cls = cls.superclass ?: break
            if (cls.name == "net.minecraft.class_437") break
        }
        return emptyList()
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

    private fun cleanSpeciesId(value: Any?): String? {
        val raw: String = when (value) {
            is String -> value
            null -> return null
            else -> {
                val s = value.toString()
                if (s.length > 60 || s.contains('\n') || s.contains('[')) return null
                s
            }
        }
        val candidate = raw.substringAfter(':').lowercase()
            .replace('-', '_').replace(' ', '_').trim()
        if (candidate.length < 3 || candidate.length > 40) return null
        if (candidate.any { !it.isLetterOrDigit() && it != '_' }) return null
        return candidate
    }

    private fun valueToSpeciesId(value: Any?): String? {
        val raw: String = when (value) {
            is String -> value
            null      -> return null
            else      -> {
                val s = value.toString()
                if (s.length > 60 || s.contains('\n') || s.contains('[')) return null
                s
            }
        }
        val candidate = raw.substringAfter(':').lowercase()
            .replace('-', '_').replace(' ', '_').trim()
        if (candidate.length < 3 || candidate.length > 40) return null
        if (candidate.any { !it.isLetterOrDigit() && it != '_' }) return null
        return if (PokemonSpecies.getByName(candidate) != null) candidate else null
    }
}
