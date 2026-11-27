package com.hunterboard

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.hud.ChatHudLine
import net.minecraft.client.util.ScreenshotRecorder
import java.io.File

/**
 * Development check of developer mode (DevMode, `-Dhunterboard.dev`):
 *
 *  - off (`-Pdev=false -Puitour.devmode=off`): `/hbdebug` is nowhere, not even in the
 *    suggestions, and nothing is announced;
 *  - on (`-Puitour.devmode=on`): it is announced in the chat, `/hbdebug` lists its commands,
 *    `christmas on|off|auto` puts the hats on a Pokémon in view and takes them off,
 *    `april on|off` arms and disarms the Électhor prank, `april prank` plays it through.
 *
 * Writes `devmode_check.txt` and a few pictures in the tour folder; before the tour's pictures.
 */
object DevModeCheck {
    private var phase = 0
    private var wait = 0
    private val log = StringBuilder()
    private var fails = 0
    private var chatMark = 0
    private var drawnBefore = 0L

    private fun note(s: String) { log.appendLine(s); HunterBoard.LOGGER.info("[DevModeCheck] $s") }
    private fun check(ok: Boolean, what: String) { if (!ok) fails++; note((if (ok) "PASS " else "FAIL ") + what) }
    private fun shot(client: MinecraftClient, out: File, name: String) =
        ScreenshotRecorder.takeScreenshot(client.framebuffer).use { it.writeTo(File(out, "devmode_$name.png")) }

    /** The chat, oldest first. */
    @Suppress("UNCHECKED_CAST")
    private fun chat(client: MinecraftClient): List<String> =
        (DevAccess.get(client.inGameHud.chatHud, "messages") as List<ChatHudLine>).map { it.content().string }.reversed()
    private fun chatSince(client: MinecraftClient) = chat(client).drop(chatMark)
    private fun mark(client: MinecraftClient) { chatMark = chat(client).size }

    private fun run(client: MinecraftClient, command: String) {
        mark(client)
        client.player!!.networkHandler.sendChatCommand(command)   // the player's own command
    }

    private fun fooled(client: MinecraftClient) = client.world!!.entities.count { e ->
        e is PokemonEntity && e.pokemon.nickname?.string == "April fool =)"
    }

    /** True once finished. */
    fun tick(client: MinecraftClient, out: File, wanted: Boolean): Boolean {
        wait++
        val player = client.player ?: return false
        when (phase) {
            0 -> {
                client.options.hudHidden = false
                val active = ClientCommandManager.getActiveDispatcher()?.root?.getChild("hbdebug") != null
                val suggested = player.networkHandler.commandDispatcher.root.getChild("hbdebug") != null
                note("hunterboard.dev=${System.getProperty("hunterboard.dev")} -> DevMode.enabled=${DevMode.enabled}")
                check(DevMode.enabled == wanted, "developer mode is ${if (wanted) "on" else "off"}")
                check(active == wanted, "/hbdebug is ${if (wanted) "" else "not "}a client command")
                check(suggested == wanted, "/hbdebug is ${if (wanted) "" else "not "}among the suggestions")
                val announced = chat(client).any { "Mode développeur" in it || "Developer mode" in it }
                check(announced == wanted, "developer mode is ${if (wanted) "" else "not "}announced in the chat")
                if (!wanted) return done(out)
                run(client, "hbdebug")
                phase = 1; wait = 0
            }
            1 -> if (wait > 5) {
                val lines = chatSince(client)
                note("/hbdebug: ${lines.joinToString(" | ")}")
                check(lines.any { "/hbdebug christmas on|off|auto" in it } && lines.any { "/hbdebug april on|off" in it } &&
                    lines.any { "/hbdebug april prank" in it }, "/hbdebug lists the developer commands")
                // a Pokémon in view, 4 blocks ahead
                player.networkHandler.sendChatCommand("pokespawn bulbasaur")
                player.networkHandler.sendChatCommand("tp @s ~ ~ ~-4 0 15")
                phase = 2; wait = 0
            }
            2 -> if (wait > 30) {
                check(!ChristmasHats.isActive(), "no hats in November (${ChristmasHats.mode})")
                run(client, "hbdebug christmas on")
                phase = 3; wait = 0
            }
            3 -> if (wait > 40) {
                note("/hbdebug christmas on: ${chatSince(client).joinToString(" | ")}")
                check(ChristmasHats.mode == ChristmasHats.Mode.ON && ChristmasHats.isActive(), "christmas on forces the hats")
                check(ChristmasHats.hookCalls > 0, "the render hook is reached (${ChristmasHats.hookCalls} calls)")
                check(ChristmasHats.hatsDrawn > 0, "hats are drawn (${ChristmasHats.hatsDrawn}), no error (${ChristmasHats.lastError})")
                shot(client, out, "christmas_on")
                run(client, "hbdebug christmas off")
                phase = 4; wait = 0
            }
            4 -> if (wait > 5) {
                if (wait == 6) drawnBefore = ChristmasHats.hatsDrawn
                if (wait < 30) return false
                check(!ChristmasHats.isActive() && ChristmasHats.hatsDrawn == drawnBefore, "christmas off: no more hats drawn")
                shot(client, out, "christmas_off")
                run(client, "hbdebug christmas auto")
                phase = 5; wait = 0
            }
            5 -> if (wait > 5) {
                check(ChristmasHats.mode == ChristmasHats.Mode.AUTO && ChristmasHats.isActive() == ChristmasHats.inSeason(),
                    "christmas auto follows the calendar again")
                run(client, "hbdebug april on")
                phase = 6; wait = 0
            }
            6 -> if (wait > 5) {
                val s = AprilFoolsPrank.secondsUntil()
                note("/hbdebug april on: ${chatSince(client).joinToString(" | ")}")
                check(AprilPaintings.simulated, "april on simulates the 1st of April")
                check(s != null && s in 1190..1200, "the Électhor prank is armed, as on the day ($s s to go)")
                run(client, "hbdebug april")
                phase = 7; wait = 0
            }
            7 -> if (wait > 5) {
                val lines = chatSince(client)
                note("/hbdebug april: ${lines.joinToString(" | ")}")
                check(lines.any { ("farce d'Électhor : dans" in it || "Électhor prank : in" in it) && " min " in it }, "the status tells the prank's wait")
                run(client, "hbdebug april off")
                phase = 8; wait = 0
            }
            8 -> if (wait > 5) {
                check(!AprilPaintings.simulated && AprilFoolsPrank.secondsUntil() == null, "april off: the prank is disarmed with the day")
                run(client, "hbdebug april prank")
                phase = 9; wait = 0
            }
            9 -> if (wait > 5) {
                check(AprilFoolsPrank.running, "april prank starts it now, whatever the date")
                phase = 10; wait = 0
            }
            10 -> if (wait > 30 * 20 + 20) {   // the Magikarp 30 s after the cry
                val n = fooled(client)
                check(n == 8, "the eight Magikarp are around the player ($n)")
                check(chat(client).any { "est apparu devant vous" in it }, "Électhor's line is in the chat")
                client.options.perspective = net.minecraft.client.option.Perspective.THIRD_PERSON_BACK
                phase = 11; wait = 0
            }
            11 -> if (wait == 10) {
                shot(client, out, "prank")
                client.options.perspective = net.minecraft.client.option.Perspective.FIRST_PERSON
            } else if (wait > 10 * 20 + 20) {   // the reveal 10 s later
                check(fooled(client) == 0 && !AprilFoolsPrank.running, "the reveal takes them away")
                check(chat(client).any { "Fooled by Popi" in it }, "the reveal line is in the chat")
                return done(out)
            }
        }
        return false
    }

    private fun done(out: File): Boolean {
        note(if (fails == 0) "ALL PASSED" else "$fails FAILED")
        File(out, "devmode_check.txt").writeText(log.toString())
        return true
    }
}
