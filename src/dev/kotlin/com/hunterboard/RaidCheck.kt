package com.hunterboard

import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.InputUtil
import net.minecraft.client.util.ScreenshotRecorder
import org.lwjgl.glfw.GLFW
import java.io.File

/**
 * Development check of the raid card's triggers, without Tropimon: a made-up raid
 * notification in Tropimon's shape (a player's raid, then the same one again), the chat line
 * of a player's raid, the card's own key, and the relay's "raid in progress" for a client
 * that joins late (against a local relay — never the real one). Writes `raid_check.txt` and
 * a few pictures in the tour folder.
 *
 * Started by UiTour when `-Puitour.raid=<local relay url>` is given (tools/raid_relay.py
 * running there), before its pictures.
 */
object RaidCheck {
    private var phase = 0
    private var wait = 0
    private val log = StringBuilder()
    private var fails = 0
    private var latest: RaidRelay.Latest? = null
    private var firstId = 0

    private fun note(s: String) { log.appendLine(s); HunterBoard.LOGGER.info("[RaidCheck] $s") }
    private fun check(ok: Boolean, what: String) { if (!ok) fails++; note((if (ok) "PASS " else "FAIL ") + what) }
    private fun shot(client: MinecraftClient, out: File, name: String) =
        ScreenshotRecorder.takeScreenshot(client.framebuffer).use { it.writeTo(File(out, "raid_$name.png")) }

    // Tropimon's notification records, as TropiNotifications reads them (public accessors)
    class FakeArg(private val t: String) { fun text() = t; fun translatable() = false; fun fallback() = t }
    class FakeText(private val v: String, private val a: List<FakeArg>) { fun value() = v; fun args() = a }
    class FakeNotif(private val desc: String, private val who: String?) {
        fun id() = "raid-" + System.nanoTime()
        fun title() = FakeText("gui.tropimod.notification.raid.title", emptyList())
        fun description() = FakeText(desc, listOfNotNull(who?.let { FakeArg(it) }))
        fun rendererId() = ""
        fun data() = emptyMap<String, String>()
        fun stats() = emptyList<Any>()
        fun decline(): Any? = null
    }

    private fun startsIn() = ((DevAccess.get(RaidCard, "startsAt") as Long) - System.currentTimeMillis()) / 1000
    private fun raidId() = DevAccess.get(RaidCard, "raidId") as Int
    private fun press(client: MinecraftClient) {
        val key = InputUtil.fromKeyCode(GLFW.GLFW_KEY_DELETE, 0)
        net.minecraft.client.option.KeyBinding.onKeyPressed(key)
    }

    /** True once finished; the tour then goes on with its pictures. */
    fun tick(client: MinecraftClient, relay: String, out: File): Boolean {
        wait++
        when (phase) {
            0 -> {
                DevAccess.set(RaidRelay, "base", relay)
                DevAccess.set(ModConfig, "raidCard", true)
                DevAccess.set(ModConfig, "raidShare", true)
                client.options.hudHidden = false
                RaidCard.dismiss()
                // the chat line of a player's raid, and lines that must not count
                for ((line, want) in listOf(
                    " ToutatisYT a déclenché un Mega Raid ! (Clique pour te téléporter)" to true,
                    "Sparte_ a déclenché un Raid !" to false,
                    "Hysako a déclenché un raid !" to false,
                    "Bob a déclenché un Méga Raid !" to true,
                    "Bob triggered a Mega Raid!" to true,
                    "Bob has started a raid !" to false,
                    "ꑣ Popichu_: Bob a déclenché un raid !" to null,
                    "Popichu_: quelqu'un a déclenché un raid ?" to null,
                )) check(RaidCard.triggeredBy(line) == want, "chat line \"$line\" -> $want")
                firstId = raidId()
                TropiNotifications.dispatch(FakeNotif("gui.tropimod.notification.raid.triggered", "Sparte_"))
                phase = 1; wait = 0
            }
            1 -> if (wait > 20) {
                check(RaidCard.active, "a player's raid notification opens the card")
                check(startsIn() in 295..300, "counted five minutes from the announcement (${startsIn()} s)")
                check((DevAccess.get(RaidTimerOverlay, "notifDisplayUntil") as Long) > System.currentTimeMillis(), "the \"raid started\" banner shows")
                shot(client, out, "card_triggered")
                // the same raid again, as notification then chat line: nothing new
                TropiNotifications.dispatch(FakeNotif("gui.tropimod.notification.raid.triggered", "Sparte_"))
                phase = 2; wait = 0
            }
            2 -> if (wait > 5) {
                check(raidId() == firstId + 1, "the same raid heard twice counts once (raids: ${raidId() - firstId})")
                press(client)
                phase = 3; wait = 0
            }
            3 -> if (wait > 3) {
                check(!RaidCard.active, "Delete puts the card away")
                check(client.inGameHud.let { DevAccess.get(it, "overlayMessage") }?.toString()?.contains("Suppr") == true ||
                    client.inGameHud.let { DevAccess.get(it, "overlayMessage") }?.toString()?.contains("Delete") == true,
                    "and says how to bring it back (${DevAccess.get(client.inGameHud, "overlayMessage")})")
                shot(client, out, "card_hidden")
                press(client)
                phase = 4; wait = 0
            }
            4 -> if (wait > 3) {
                check(RaidCard.active, "Delete again brings it back")
                // a player who was not there: what the relay says of the raid just announced
                RaidRelay.latest { latest = it }
                phase = 5; wait = 0
            }
            5 -> if (latest != null || wait > 100) {
                val l = latest
                check(l != null && l.slot == DevAccess.get(RaidCard, "slot") as Long && l.ageMs in 0..10_000 && !l.mega,
                    "the relay knows of the raid just announced (slot ${l?.slot}, ${l?.ageMs} ms ago)")
                // the same answer reaching a client that has no raid ahead
                RaidCard.dismiss()
                check(!RaidCard.active, "a client that missed it has no card")
                if (l != null) DevAccess.set(RaidCard, "latest", RaidRelay.Latest(l.slot, 90_000L, true, "gengar", setOf("mega"), 2, 312_000L))
                phase = 6; wait = 0
            }
            6 -> if (wait > 25) {
                check(RaidCard.active, "the relay's raid opens the card")
                check(startsIn() in 215..222, "counted from the announcement the relay heard, with its measured start (${startsIn()} s)")
                check(DevAccess.get(RaidCard, "mega") == true, "a mega raid")
                shot(client, out, "card_from_relay")
                // a raid long past: nothing opens
                RaidCard.dismiss()
                DevAccess.set(RaidCard, "latest", RaidRelay.Latest(5L, 400_000L, false, null, emptySet(), 0, null))
                phase = 7; wait = 0
            }
            7 -> if (wait > 5) {
                check(!RaidCard.active, "a raid whose countdown is over opens nothing")
                RaidCard.dismiss()
                note("fails: $fails")
                File(out, "raid_check.txt").writeText(log.toString())
                return true
            }
        }
        return false
    }
}
