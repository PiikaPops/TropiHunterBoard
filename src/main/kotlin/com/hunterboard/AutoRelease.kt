package com.hunterboard

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Releases the captures that fail their [CaptureFilters] filter.
 *
 * Tropimon announces every capture with a card that names the species, level, gender,
 * shininess, ability and the six IVs — everything a filter looks at. While that card is
 * on screen the player can answer it: Y keeps the Pokémon, N releases it, and the server
 * does the release wherever the Pokémon went, PC included.
 *
 * The filter is checked against the card, and when it fails, HunterBoard presses N on that
 * card itself ([TropiCards]), after the chosen delay but always before the card closes.
 * Until then the player can still press Y, or click [Cancel] in chat.
 *
 * The card is the only way HunterBoard releases. Tropimon offers "Release" only up to 90 %
 * IV and never for a shiny, legendary or mythical, so those are always kept.
 */
object AutoRelease {

    private const val CAPTURE_RENDERER = "tropimon:pokemon_capture"

    /** The card is answered at the latest this long before it closes on its own. */
    private const val CARD_MARGIN_MS = 1_500L
    /** The card slides in for 350 ms before it can be answered. */
    private const val CARD_ENTER_MS = 350L
    /** A card queued behind others may wait; past this, it is given up. */
    private const val CARD_WAIT_MS = 60_000L
    /** Time for the answer to come back through Tropimon's listeners. */
    private const val CARD_CONFIRM_MS = 5_000L

    private class Capture(
        /** Tropimon's notification id: the card to answer. */
        val card: String,
        val data: Map<String, String>,
        val stats: List<String>,
        /** The card offers "Release [N]". */
        val declinable: Boolean
    )

    private class Planned(
        val id: Int,
        val name: String,
        val species: String,
        val dueAt: Long,
        val card: String,
        /** What the card said, to check the filter again at the last moment. */
        val subject: CaptureFilters.Subject,
        val createdAt: Long,
        var sent: Boolean = false,
        var verifyAt: Long = 0
    )

    /** Notifications arrive on the network thread; everything else happens on the client tick. */
    private val inbox = ConcurrentLinkedQueue<Capture>()
    private val planned = mutableListOf<Planned>()
    private var nextId = 1

    fun register() {
        TropiCards.init()
        TropiNotifications.onNotification { notif ->
            if (notif.rendererId == CAPTURE_RENDERER) {
                inbox.add(Capture(notif.id, HashMap(notif.data), notif.stats, notif.declinable))
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            try { tick(client) } catch (e: Exception) {
                HunterBoard.LOGGER.warn("AutoRelease tick failed: ${e.message}")
            }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("hbfilter")
                    .executes { openScreen(); 1 }
                    .then(ClientCommandManager.literal("cancel")
                        .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                            .executes { cancel(IntegerArgumentType.getInteger(it, "id")); 1 }))
                    .then(ClientCommandManager.literal("cancelall").executes { cancelAll(); 1 })
                    // the "[Import]" link after a filter code in the chat
                    .then(ClientCommandManager.literal("import")
                        .then(ClientCommandManager.argument("code", StringArgumentType.greedyString())
                            .executes { openScreen(StringArgumentType.getString(it, "code")); 1 }))
            )
        }
    }

    private fun reset() {
        inbox.clear()
        planned.clear()
    }

    /** Releases announced and not yet sent, for the options screen. */
    fun pendingCount(): Int = planned.count { !it.sent }

    // ── tick ───────────────────────────────────────────────────────────────────────

    private fun tick(client: MinecraftClient) {
        if (client.player == null) return
        while (true) {
            val c = inbox.poll() ?: break
            if (watched(c.data["species"])) judge(c)
        }
        if (planned.isNotEmpty()) {
            val now = System.currentTimeMillis()
            for (pl in planned.toList()) run(pl, now)
        }
    }

    private fun watched(species: String?): Boolean {
        if (species.isNullOrBlank() || !ModConfig.captureFilterEnabled) return false
        return CaptureFilters.forSpecies(species) != null
    }

    // ── decisions ──────────────────────────────────────────────────────────────────

    private fun judge(c: Capture) {
        val species = c.data["species"] ?: return
        val filter = CaptureFilters.forSpecies(species) ?: return
        val subject = CaptureFilters.subjectOf(c.data, c.stats)
        val name = CaptureFilters.speciesName(species)
        val label = cardLabel(name, c.data, subject)
        when (CaptureFilters.verdict(filter, subject)) {
            CaptureFilters.Verdict.KEEP -> if (ModConfig.captureFilterNotifyKept) {
                say(label.append(Text.literal(t(" kept", " gardé")).formatted(Formatting.GREEN)))
            }
            CaptureFilters.Verdict.UNKNOWN -> say(label.append(Text.literal(
                t(" kept: the card lacks what its filter checks", " gardé : la carte ne montre pas ce que vérifie son filtre")
            ).formatted(Formatting.GRAY)))
            CaptureFilters.Verdict.RELEASE -> when {
                !c.declinable -> if (ModConfig.captureFilterNotifyKept) say(label.append(Text.literal(
                    t(" kept (", " gardé (") + unreleasable(c.data, subject) + ")").formatted(Formatting.GOLD)))
                ModConfig.captureFilterSimulate -> say(Text.literal("[Test] ").formatted(Formatting.AQUA)
                    .append(label).append(Text.literal(t(" would be released", " serait relâché")).formatted(Formatting.GRAY)))
                !TropiCards.available -> say(label.append(Text.literal(
                    t(": cannot release from here, press N", " : relâche impossible d'ici, appuyez sur N")
                ).formatted(Formatting.YELLOW)))
                else -> plan(c, species, name, label, subject)
            }
        }
    }

    /** Why the card offers no release: Tropimon's own rule (`PokemonCaptureNotification.releasable`). */
    private fun unreleasable(data: Map<String, String>, s: CaptureFilters.Subject): String = when {
        s.shiny == true -> "shiny"
        data["legendary"] == "true" -> t("legendary", "légendaire")
        data["mythical"] == "true" -> t("mythical", "fabuleux")
        (s.ivPercent ?: 0) > CaptureFilters.RELEASE_MAX_IV_PERCENT -> t("over 90 % IV", "plus de 90 % d'IV")
        else -> t("no release offered", "relâche non proposée")
    }

    private fun plan(c: Capture, species: String, name: String, label: MutableText, subject: CaptureFilters.Subject) {
        val id = nextId++
        val now = System.currentTimeMillis()
        // A card answers only while it is up: a longer delay is cut short just before it closes.
        val delay = minOf(ModConfig.captureFilterDelay * 1000L,
            (TropiCards.cardMillis() - CARD_ENTER_MS - CARD_MARGIN_MS).coerceAtLeast(0))
        planned.add(Planned(id, name, species, now + delay, c.card, subject, now))

        if (!ModConfig.captureFilterAnnounce) return
        val seconds = ((delay + 999) / 1000).toInt()
        val text = label.append(Text.literal(
            if (seconds == 0) t(" released", " relâché") else t(" released in $seconds s ", " relâché dans $seconds s ")
        ).formatted(Formatting.YELLOW))
        if (seconds > 0) text.append(link(t("[Cancel]", "[Annuler]"), "/hbfilter cancel $id",
            t("Keep this Pokémon", "Garder ce Pokémon"), Formatting.RED))
        say(text)
    }

    // ── releases ───────────────────────────────────────────────────────────────────

    /** Presses "Release" on the capture card once due, or just before the card closes. */
    private fun run(pl: Planned, now: Long) {
        val outcome = TropiCards.outcomeOf(pl.card)
        if (outcome != null) {
            planned.remove(pl)
            val name = Text.literal(pl.name).formatted(Formatting.WHITE)
            // DECLINED needs no line: the release was already announced.
            when {
                outcome == "DECLINED" || pl.sent -> {}
                outcome == "ACCEPTED" -> note(name.append(Text.literal(t(" kept", " gardé")).formatted(Formatting.GREEN)))
                else -> note(name.append(Text.literal(t(" kept (card closed)", " gardé (carte fermée)")).formatted(Formatting.GRAY)))
            }
            return
        }
        if (pl.sent) {
            if (now > pl.verifyAt) {
                planned.remove(pl)
                note(Text.literal(pl.name).formatted(Formatting.WHITE).append(Text.literal(
                    t(": release not confirmed", " : relâche non confirmée")).formatted(Formatting.GRAY)))
            }
            return
        }

        val shown = TropiCards.current()
        if (shown == null || shown.id != pl.card) {
            // Queued behind another card, most likely; it comes up on its own.
            if (!TropiCards.available || now - pl.createdAt > CARD_WAIT_MS) {
                planned.remove(pl)
                note(Text.literal(pl.name).formatted(Formatting.WHITE).append(Text.literal(
                    t(" kept (card not shown)", " gardé (carte non affichée)")).formatted(Formatting.GRAY)))
            }
            return
        }
        if (!shown.visible) return // sliding in, or already closing after the player's answer
        if (now < pl.dueAt && shown.remainingMs > CARD_MARGIN_MS) return

        val stop = whyNot(pl)
        if (stop != null) {
            planned.remove(pl)
            note(Text.literal(pl.name).formatted(Formatting.WHITE).append(Text.literal(stop).formatted(Formatting.GRAY)))
            return
        }
        if (TropiCards.decline(shown)) {
            HunterBoard.LOGGER.info("AutoRelease: answered the capture card of ${pl.name} with Release")
            pl.sent = true
            pl.verifyAt = now + CARD_CONFIRM_MS
        } else {
            planned.remove(pl)
            say(Text.literal(pl.name).formatted(Formatting.WHITE).append(Text.literal(
                t(": cannot release from here, press N", " : relâche impossible d'ici, appuyez sur N")).formatted(Formatting.YELLOW)))
        }
    }

    /** Everything is checked again at the last moment; returns why not, or null to go ahead. */
    private fun whyNot(pl: Planned): String? {
        if (!ModConfig.captureFilterEnabled) return t(" kept (filters off)", " gardé (filtres désactivés)")
        if (ModConfig.captureFilterSimulate) return t(" kept (test mode)", " gardé (mode test)")
        val filter = CaptureFilters.forSpecies(pl.species)
        if (filter == null || !filter.isActive() ||
            CaptureFilters.verdict(filter, pl.subject) != CaptureFilters.Verdict.RELEASE) {
            return t(" kept (filter changed)", " gardé (filtre modifié)")
        }
        return null
    }

    private fun cancel(id: Int) {
        val pl = planned.firstOrNull { it.id == id }
        when {
            pl == null -> say(Text.literal(t("Nothing to cancel.", "Rien à annuler.")).formatted(Formatting.GRAY))
            pl.sent -> say(Text.literal(t("Too late: ", "Trop tard : ")).formatted(Formatting.GRAY)
                .append(Text.literal(pl.name).formatted(Formatting.WHITE))
                .append(Text.literal(t(" is released.", " est relâché.")).formatted(Formatting.GRAY)))
            else -> {
                planned.remove(pl)
                say(Text.literal(pl.name).formatted(Formatting.WHITE)
                    .append(Text.literal(t(" kept", " gardé")).formatted(Formatting.GREEN)))
            }
        }
    }

    private fun cancelAll() {
        val n = planned.count { !it.sent }
        planned.removeAll { !it.sent }
        say(Text.literal(if (n == 0) t("No release pending.", "Aucun relâchement en attente.")
                         else t("$n release(s) cancelled.", "$n relâchement(s) annulé(s).")).formatted(Formatting.GREEN))
    }

    private fun openScreen(importCode: String? = null) {
        val client = MinecraftClient.getInstance()
        // A command runs from the chat screen, which closes right after: open next tick.
        client.send { client.setScreen(CaptureFilterScreen(null, importCode)) }
    }

    // ── chat ───────────────────────────────────────────────────────────────────────

    private fun t(en: String, fr: String) = if (Translations.isFrench()) fr else en

    /** "Pikachu Lv. 12 · IV 45 %", with the six IVs and the ability on hover. */
    private fun cardLabel(name: String, data: Map<String, String>, s: CaptureFilters.Subject): MutableText {
        val parts = mutableListOf(name)
        data["level"]?.toIntOrNull()?.let { parts[0] = "$name ${t("Lv.", "Nv")} $it" }
        s.ivPercent?.let { parts.add("IV $it %") }
        val hover = CaptureFilters.details(s)
        return Text.literal(parts.joinToString(" · ")).styled {
            it.withColor(Formatting.WHITE).let { st ->
                if (hover.isBlank()) st else st.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover)))
            }
        }
    }

    private fun link(label: String, command: String, hover: String, color: Formatting): MutableText =
        Text.literal(label).styled {
            it.withColor(color).withUnderline(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover)))
        }

    /** Follow-up of an announced release: silent when the announcements are off. */
    private fun note(message: Text) {
        if (ModConfig.captureFilterAnnounce) say(message)
    }

    private fun say(message: Text) {
        val client = MinecraftClient.getInstance()
        client.player?.sendMessage(
            Text.literal("[HunterBoard] ").formatted(Formatting.GOLD).append(message), false
        )
    }
}
