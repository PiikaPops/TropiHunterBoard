package com.hunterboard.pc

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.pc.PCGUI
import com.hunterboard.HunterBoard
import com.hunterboard.ModConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.util.UUID

/**
 * HunterBoard's PC: a "HunterBoard view" button in Cobblemon's PC opens [HunterPcScreen]
 * on the same PC. Everything goes through Cobblemon's own storage packets; the server
 * sees exactly what the classic PC would send, one request at a time.
 */
object HunterPc {
    private var ticks = 0

    /** Tropimon Better PC (FastedCorsi), which swaps Cobblemon's PC for its own screen. */
    val betterPcInstalled: Boolean by lazy { FabricLoader.getInstance().isModLoaded("tropimon_better_pc") }

    /**
     * Better PC remembers the PC screen that just opened and replaces it at the end of the
     * next client tick. Emptying that note at the start of every tick keeps Cobblemon's PC
     * on screen; Better PC itself is untouched and opens from its button.
     */
    private val betterPcPending: java.lang.reflect.Field? by lazy {
        runCatching { Class.forName("fr.tropimon.betterpc.BetterPcClient").getDeclaredField("pendingPcScreen").apply { isAccessible = true } }
            .onFailure { HunterBoard.LOGGER.warn("[PC] Tropimon Better PC changed, cannot keep Cobblemon's PC first: $it") }
            .getOrNull()
    }

    fun register() {
        ClientTickEvents.START_CLIENT_TICK.register {
            if (betterPcInstalled && ModConfig.classicPcFirst) runCatching { betterPcPending?.set(null, null) }
        }

        ScreenEvents.AFTER_INIT.register { client, screen, w, h ->
            if (screen !is PCGUI) return@register
            // a PC opened to pick a Pokémon for something else (trade, daycare…) stays as it is
            if (runCatching { screen.configuration.selectOverride }.getOrNull() != null) return@register
            val top = (h - PCGUI.BASE_HEIGHT) / 2
            val y = if (top >= 18) top - 16 else 2
            var right = (w + PCGUI.BASE_WIDTH) / 2
            if (ModConfig.hunterPc) {
                val label = Text.literal("✦ " + L("HunterBoard view", "Vue HunterBoard"))
                val bw = client.textRenderer.getWidth(label) + 14
                Screens.getButtons(screen).add(ButtonWidget.builder(label) { open(screen) }.dimensions(right - bw, y, bw, 14).build())
                right -= bw + 4
            }
            if (betterPcInstalled && ModConfig.classicPcFirst) {
                val label = Text.literal("Better PC")
                val bw = client.textRenderer.getWidth(label) + 14
                Screens.getButtons(screen).add(ButtonWidget.builder(label) { openBetterPc(screen) }.dimensions(right - bw, y, bw, 14).build())
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.world == null) return@register
            if (++ticks % 20 != 0) return@register
            observe()
            PcPrefs.get().saveIfDue(System.currentTimeMillis())
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> PcPrefs.flush() }
    }

    /** Opens Better PC on this PC, the way it would have opened by itself. */
    private fun openBetterPc(gui: PCGUI) {
        try {
            val screen = Class.forName("fr.tropimon.betterpc.BetterPcScreen").getConstructor(PCGUI::class.java).newInstance(gui)
            MinecraftClient.getInstance().setScreen(screen as net.minecraft.client.gui.screen.Screen)
        } catch (e: Throwable) {
            HunterBoard.LOGGER.warn("[PC] Could not open Tropimon Better PC: $e")
        }
    }

    fun open(gui: PCGUI) {
        val client = MinecraftClient.getInstance()
        val party = gui.party
        client.setScreen(HunterPcScreen(PcSession(gui.pc, party, gui)))
    }

    /** Notes when each Pokémon in the party and the PCs this client knows was first seen. */
    private fun observe() {
        if (!ModConfig.hunterPc) return
        try {
            val prefs = PcPrefs.get()
            val now = System.currentTimeMillis()
            val storage = CobblemonClient.storage
            storage.party.let { party -> prefs.observe(party.uuid, party.slots.mapNotNull { it?.uuid }, now) }
            for (pc in storage.pcStores.values) {
                prefs.observe(pc.uuid, pc.boxes.flatMap { b -> b.slots.mapNotNull { it?.uuid } }, now)
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("[PC] observe failed: ${e.message}")
        }
    }

    /** Cobblemon's close packet only closes its own screen; this closes ours too. */
    @JvmStatic
    fun onServerClose(storeId: UUID?) {
        val screen = MinecraftClient.getInstance().currentScreen as? HunterPcScreen ?: return
        if (storeId == null || screen.session.pc.uuid == storeId) screen.closeFromServer()
    }

    /** Whether the player must stand still (InvMove lets them walk with a screen open). */
    @JvmStatic
    fun holdsPlayer(): Boolean = MinecraftClient.getInstance().currentScreen is HunterPcScreen
}
