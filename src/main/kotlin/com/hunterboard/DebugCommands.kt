package com.hunterboard

import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.text.ClickEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * `/hbdebug`: the developer commands, registered only in developer mode ([DevMode], the JVM
 * argument `-Dhunterboard.dev=true`). In a normal game the command does not exist.
 *
 *  - `/hbdebug christmas [on|off|auto]` — the Santa hats ([ChristmasHats]) forced on, off, or
 *    back to the calendar (20–25 December), with what the render hook has seen;
 *  - `/hbdebug april [on|off]` — pretends it is the 1st of April, or stops: Tropimon's paintings
 *    show the April Fools animation ([AprilPaintings]), L'Évasion sous-marine its video, and the
 *    Électhor prank ([AprilFoolsPrank]) is armed as on the day;
 *  - `/hbdebug april prank` — the Électhor prank now, rather than 20 minutes on.
 *
 * Client-side only, on the player's own screen, and for the session: nothing is saved, so a
 * surprise forced on and forgotten cannot still be showing in July.
 */
object DebugCommands {

    private fun t(en: String, fr: String) = if (Translations.isFrench()) fr else en

    /** Developer mode is announced once, in the first world joined. */
    private var announced = false

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("hbdebug")
                    .then(
                        ClientCommandManager.literal("christmas")
                            .then(ClientCommandManager.literal("on").executes { christmas(it, ChristmasHats.Mode.ON); 1 })
                            .then(ClientCommandManager.literal("off").executes { christmas(it, ChristmasHats.Mode.OFF); 1 })
                            .then(ClientCommandManager.literal("auto").executes { christmas(it, ChristmasHats.Mode.AUTO); 1 })
                            .executes { christmasStatus(it.source); 1 }
                    )
                    .then(
                        ClientCommandManager.literal("april")
                            .then(ClientCommandManager.literal("on").executes { april(it, true); 1 })
                            .then(ClientCommandManager.literal("off").executes { april(it, false); 1 })
                            .then(ClientCommandManager.literal("prank").executes { prank(it.source); 1 })
                            .executes { aprilStatus(it.source); 1 }
                    )
                    .executes { help(it.source); 1 }
            )
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            if (!announced) client.execute {
                announced = true
                client.player?.sendMessage(head(t("Developer mode", "Mode développeur"))
                    .append(Text.literal(" — ").formatted(Formatting.GRAY))
                    .append(Text.literal("/hbdebug").styled {
                        it.withColor(Formatting.AQUA).withUnderline(true)
                            .withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/hbdebug "))
                    })
                    .append(Text.literal(t(" : Christmas hats, 1st of April", " : bonnets de Noël, 1er avril")).formatted(Formatting.GRAY)), false)
            }
        }
    }

    private fun help(source: FabricClientCommandSource) {
        source.sendFeedback(head(t("Developer commands", "Commandes développeur")))
        source.sendFeedback(hint(t("/hbdebug christmas on|off|auto — Santa hats forced, off, or by the calendar",
            "/hbdebug christmas on|off|auto — bonnets de Noël forcés, coupés ou selon le calendrier")))
        source.sendFeedback(hint(t("/hbdebug april on|off — pretend it is the 1st of April (paintings, video, Électhor prank)",
            "/hbdebug april on|off — simuler le 1er avril (tableaux, vidéo, farce d'Électhor)")))
        source.sendFeedback(hint(t("/hbdebug april prank — the Électhor prank, right now",
            "/hbdebug april prank — la farce d'Électhor, tout de suite")))
        source.sendFeedback(hint(t("Session settings, never saved.", "Réglages de session, jamais sauvegardés.")))
    }

    // ── Christmas ──────────────────────────────────────────────────────────────────────

    private fun christmas(ctx: CommandContext<FabricClientCommandSource>, mode: ChristmasHats.Mode) {
        ChristmasHats.mode = mode
        christmasStatus(ctx.source)
    }

    private fun christmasStatus(source: FabricClientCommandSource) {
        source.sendFeedback(head(t("Christmas hats", "Bonnets de Noël")))
        val mode = when (ChristmasHats.mode) {
            ChristmasHats.Mode.ON -> t("forced", "forcés")
            ChristmasHats.Mode.OFF -> t("off", "coupés")
            ChristmasHats.Mode.AUTO -> t("by the calendar (20–25 December)", "selon le calendrier (20–25 décembre)")
        }
        source.sendFeedback(line(t("mode", "mode"), mode))
        source.sendFeedback(line(t("now", "actuellement"),
            if (ChristmasHats.isActive()) t("showing", "visibles") else t("hidden", "invisibles")))
        // Tells "hook not in place" (no calls) from "hook runs, nothing drawn" (calls, no hats)
        // from an outright failure: the three look the same from outside.
        source.sendFeedback(line(t("renders hooked", "rendus interceptés"), ChristmasHats.hookCalls.toString() +
            t(" · hats drawn: ", " · bonnets dessinés : ") + ChristmasHats.hatsDrawn))
        ChristmasHats.lastError?.let { source.sendFeedback(Text.literal("  " + t("error: ", "erreur : ") + it).formatted(Formatting.RED)) }
        source.sendFeedback(hint("/hbdebug christmas on|off|auto"))
    }

    // ── 1st of April ───────────────────────────────────────────────────────────────────

    private fun april(ctx: CommandContext<FabricClientCommandSource>, on: Boolean) {
        AprilPaintings.simulate(on)
        val source = ctx.source
        if (on) {
            source.sendFeedback(head(t("1st of April simulated", "1er avril simulé")))
            source.sendFeedback(hint(t("Tropimon's paintings show the April Fools animation (and L'Évasion sous-marine its video, sound on a click); the Électhor prank comes in 20 minutes (/hbdebug april prank: right away). On your screen only, until /hbdebug april off or a restart.",
                "Les tableaux Tropimon affichent l'animation du poisson d'avril (et L'Évasion sous-marine sa vidéo, son au clic) ; la farce d'Électhor arrive dans 20 minutes (/hbdebug april prank : tout de suite). Sur ton écran seulement, jusqu'à /hbdebug april off ou un redémarrage.")))
        } else {
            source.sendFeedback(head(t("1st of April simulation stopped", "Simulation du 1er avril arrêtée")))
            if (AprilPaintings.isAprilFools()) source.sendFeedback(hint(t("It really is the 1st of April: everything stays as it is today.",
                "On est vraiment le 1er avril : tout reste comme aujourd'hui.")))
        }
    }

    private fun prank(source: FabricClientCommandSource) {
        if (AprilFoolsPrank.trigger()) {
            source.sendFeedback(head(t("Électhor prank started", "Farce d'Électhor lancée")))
            source.sendFeedback(hint(t("The cry now (Tropifurnitures' sound), the Magikarp in 30 s, the reveal 10 s later.",
                "Le cri maintenant (son de Tropifurnitures), les Magicarpe dans 30 s, la révélation 10 s après.")))
        } else {
            source.sendFeedback(head(t("Not in a world", "Pas dans un monde")))
        }
    }

    private fun aprilStatus(source: FabricClientCommandSource) {
        source.sendFeedback(head(t("1st of April", "1er avril")))
        val state = when {
            AprilPaintings.isAprilFools() -> t("on (it is the 1st of April)", "actif (on est le 1er avril)")
            AprilPaintings.simulated -> t("on (simulated)", "actif (simulé)")
            else -> t("off", "inactif")
        }
        source.sendFeedback(line(t("paintings", "tableaux"), state))
        val prank = when (val s = AprilFoolsPrank.secondsUntil()) {
            null -> if (AprilFoolsPrank.running) t("under way", "en cours") else t("not waiting", "pas en attente")
            else -> t("in ${s / 60} min ${s % 60} s", "dans ${s / 60} min ${s % 60} s")
        }
        source.sendFeedback(line(t("Électhor prank", "farce d'Électhor"), prank))
        source.sendFeedback(hint("/hbdebug april on|off|prank"))
    }

    private fun head(s: String): MutableText = Text.literal("[HunterBoard] ").formatted(Formatting.GOLD).append(Text.literal(s).formatted(Formatting.YELLOW))
    private fun line(k: String, v: String): Text = Text.literal("  $k : ").formatted(Formatting.GRAY).append(Text.literal(v).formatted(Formatting.WHITE))
    private fun hint(s: String): Text = Text.literal("  $s").formatted(Formatting.DARK_GRAY)
}
