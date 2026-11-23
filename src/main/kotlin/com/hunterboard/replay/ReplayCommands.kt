package com.hunterboard.replay

import com.hunterboard.ModConfig
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.nio.file.Files

/**
 * `/hbreplay` — the state of replay recording, and the switches worth flipping in game.
 *
 * Replays are written to disk and never leave the machine, so there is nothing here about
 * publishing them.
 */
object ReplayCommands {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("hbreplay")
                    .then(
                        ClientCommandManager.literal("mute").executes { ctx ->
                            ModConfig.toggleReplayMuted()
                            ctx.source.sendFeedback(
                                ok(
                                    if (ModConfig.replayMuted) "Les nouveaux replays démarreront muets."
                                    else "Les nouveaux replays démarreront avec le son."
                                )
                            )
                            ctx.source.sendFeedback(hint("Ne change que les replays créés ensuite."))
                            1
                        }
                    )
                    .then(
                        ClientCommandManager.literal("folder").executes { ctx ->
                            val dir = ReplayWriter.replayDir()
                            ctx.source.sendFeedback(
                                Text.literal("[HunterBoard] ").formatted(Formatting.GOLD)
                                    .append(
                                        Text.literal(dir.toString()).styled {
                                            it.withColor(0x55FFFF).withUnderline(true)
                                                .withClickEvent(
                                                    ClickEvent(ClickEvent.Action.OPEN_FILE, dir.toString())
                                                )
                                        }
                                    )
                            )
                            1
                        }
                    )
                    .executes { ctx -> status(ctx.source); 1 }
            )
        }
    }

    private fun status(source: FabricClientCommandSource) {
        source.sendFeedback(head("Replay PvP"))
        source.sendFeedback(line("enregistrement", if (ModConfig.pvpReplayEnabled) "activé" else "désactivé"))
        source.sendFeedback(line("son", if (ModConfig.replayMuted) "coupé au démarrage" else "actif"))

        val last = ReplayRecorder.lastReplay
        if (last != null && Files.exists(last)) {
            source.sendFeedback(
                Text.literal("  dernier : ").formatted(Formatting.GRAY).append(
                    Text.literal(last.fileName.toString()).styled {
                        it.withColor(0x55FFFF).withUnderline(true)
                            .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_FILE, last.toString()))
                    }
                )
            )
        } else {
            source.sendFeedback(hint("Aucun replay enregistré dans cette session."))
        }
        source.sendFeedback(hint("/hbreplay folder pour ouvrir le dossier"))
    }

    private fun head(s: String) = Text.literal("[HunterBoard] ").formatted(Formatting.GOLD)
        .append(Text.literal(s).formatted(Formatting.WHITE))

    private fun line(k: String, v: String) = Text.literal("  $k: ").formatted(Formatting.GRAY)
        .append(Text.literal(v).formatted(Formatting.AQUA))

    private fun ok(s: String) = Text.literal("[HunterBoard] ").formatted(Formatting.GOLD)
        .append(Text.literal(s).formatted(Formatting.GREEN))

    private fun hint(s: String) = Text.literal("  ").append(Text.literal(s).formatted(Formatting.YELLOW))
}
