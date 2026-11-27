package com.hunterboard

import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.Perspective
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import org.joml.Vector3f
import java.io.File

/**
 * Development check of Tropimon's paintings held in hand: four armour stands holding one,
 * turned four ways (what the people around see), the painting in the first person, and the
 * player seen from the front — with Tropimon's own placement, then with HunterBoard's.
 * Writes `painting_*.png` in the tour folder.
 *
 * `-Puitour.painting=1` takes HunterBoard's placement as it is; `-Puitour.painting=try`
 * also takes the candidates of [tries], one picture each, to choose among.
 *
 * Started by UiTour; Tropifurnitures and its WebP library (both from Tropimon's client jar)
 * and GeckoLib must be in run/mods, and are taken out again afterwards.
 */
object PaintingCheck {
    private const val ITEM = "tropifurnitures:painting_2x2_10"
    private var phase = 0
    private var wait = 0
    private var shots = mutableListOf<Pair<String, (() -> Unit)?>>()
    /** One block of the painting placed for the video check. */
    private var paintingAt: net.minecraft.util.math.BlockPos? = null
    /** The body turned from the camera, for the side view. */
    private var bodyTurn = 0f

    private fun shot(client: MinecraftClient, out: File, name: String) {
        ScreenshotRecorder.takeScreenshot(client.framebuffer).use { it.writeTo(File(out, "painting_$name.png")) }
        HunterBoard.LOGGER.info("[PaintingCheck] painting_$name.png")
    }

    private fun d(rx: Float, ry: Float, rz: Float, tx: Float, ty: Float, tz: Float, size: Float) =
        TropiPaintings.Display(Vector3f(rx, ry, rz), Vector3f(tx, ty, tz), size)

    /** Candidates: (name, third person, first person). */
    private val tries = listOf(
        Triple("r15", d(75f, 0f, 0f, 4f, 0.5f, 0f, 0.5f), d(3f, 178f, 15f, -0.25f, 3.5f, 0f, 0.6f)),
        Triple("r10", d(75f, 0f, 0f, 4f, 0.5f, 0f, 0.5f), d(3f, 178f, 10f, -0.25f, 3.5f, 0f, 0.6f)),
        Triple("r5", d(75f, 0f, 0f, 4f, 0.5f, 0f, 0.5f), d(3f, 178f, 5f, -0.25f, 3.5f, 0f, 0.6f)),
    )

    /** True once finished; the tour then goes on with its pictures. */
    fun tick(client: MinecraftClient, out: File, mode: String): Boolean {
        val player = client.player ?: return false
        wait++
        // the body square with the head, as when walking: seen from the front, the body faces the camera
        if (phase >= 1) {
            val body = player.yaw + bodyTurn
            player.bodyYaw = body; player.prevBodyYaw = body; player.headYaw = player.yaw; player.prevHeadYaw = player.yaw
        }
        if (mode == "video" && phase < 300) { phase = 300; wait = 0 }
        when (phase) {
            300 -> {
                // the painting that plays a video: two moments apart, then a click (sound on) and another (off)
                if (wait == 1) {
                    player.networkHandler.sendChatCommand("time set noon")
                    player.networkHandler.sendChatCommand("clear @s")
                    player.networkHandler.sendChatCommand("kill @e[type=armor_stand]")
                    player.networkHandler.sendChatCommand("tp @s ~ ~ ~ 180 5")
                    // placed as a player does, on a wall: the painting lays its other blocks out itself
                    player.networkHandler.sendChatCommand("fill ~-4 ~-1 ~-6 ~4 ~5 ~-6 minecraft:stone")
                    player.networkHandler.sendChatCommand("fill ~-4 ~ ~-5 ~4 ~5 ~-1 minecraft:air")
                    player.networkHandler.sendChatCommand("give @s tropifurnitures:painting_3x2_3")
                    player.inventory.selectedSlot = 0
                }
                if (wait == 30) {
                    val wall = player.blockPos.add(0, 1, -6)
                    val hit = net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(wall).add(0.0, 0.0, 0.5),
                        net.minecraft.util.math.Direction.SOUTH, wall, false)
                    HunterBoard.LOGGER.info("[PaintingCheck] place -> ${client.interactionManager!!.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hit)}")
                }
                if (wait == 40) {
                    val w = client.world!!
                    val found = (-3..3).flatMap { dx -> (0..4).map { dy -> player.blockPos.add(dx, dy, -5) } }
                        .filter { net.minecraft.registry.Registries.BLOCK.getId(w.getBlockState(it).block).path == "painting_3x2_3" }
                    HunterBoard.LOGGER.info("[PaintingCheck] painting blocks: $found")
                    paintingAt = found.firstOrNull()
                    player.networkHandler.sendChatCommand("clear @s")
                    client.options.hudHidden = false
                    client.options.perspective = Perspective.FIRST_PERSON
                    // Minecraft plays nothing at volume 0 (the dev run's): 1 % for the check, put back after
                    client.options.getSoundVolumeOption(net.minecraft.sound.SoundCategory.MASTER).value = 0.01
                    HunterBoard.LOGGER.info("[PaintingCheck] sound set: ${client.soundManager.get(net.minecraft.util.Identifier.of("hunterboard", "video.evasion")) != null}, " +
                        "ogg: ${client.resourceManager.getResource(net.minecraft.util.Identifier.of("hunterboard", "sounds/video/evasion.ogg")).isPresent}")
                }
                if (wait == 50) shot(client, out, "video_off_day")   // any other day: Tropimon's picture
                if (wait == 55) player.networkHandler.sendChatCommand("hbdebug april on")
                if (wait == 60) shot(client, out, "video_1")
                if (wait == 70) shot(client, out, "video_2")
                if (wait == 71 || wait == 90) {
                    val pos = paintingAt ?: player.blockPos.add(0, 1, -5)
                    val hit = net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(pos), net.minecraft.util.math.Direction.SOUTH, pos, false)
                    val r = client.interactionManager!!.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hit)
                    val p = DevAccess.get(VideoPaintings, "playing")
                    val sound = p?.let { DevAccess.get(it, "sound") as net.minecraft.client.sound.SoundInstance }
                    HunterBoard.LOGGER.info("[PaintingCheck] click -> $r, playing=${p != null}, sound=${sound?.id}")
                }
                if (wait == 80) {
                    val p = DevAccess.get(VideoPaintings, "playing")
                    val sound = p?.let { DevAccess.get(it, "sound") as net.minecraft.client.sound.SoundInstance }
                    HunterBoard.LOGGER.info("[PaintingCheck] after click: sound playing=${sound?.let { client.soundManager.isPlaying(it) }}")
                    shot(client, out, "video_on")
                }
                if (wait == 100) {
                    player.networkHandler.sendChatCommand("hbdebug april off")
                    client.options.getSoundVolumeOption(net.minecraft.sound.SoundCategory.MASTER).value = 0.0
                    HunterBoard.LOGGER.info("[PaintingCheck] after second click: playing=${DevAccess.get(VideoPaintings, "playing") != null}")
                    return true
                }
            }
            0 -> {
                if (!Registries.ITEM.containsId(Identifier.of(ITEM))) {
                    HunterBoard.LOGGER.warn("[PaintingCheck] $ITEM is not loaded: Tropifurnitures missing from run/mods")
                    return true
                }
                player.inventory.selectedSlot = 0
                player.networkHandler.sendChatCommand("clear @s")
                player.networkHandler.sendChatCommand("time set noon")
                player.networkHandler.sendChatCommand("kill @e[type=armor_stand]")
                player.networkHandler.sendChatCommand("tp @s ~ ~ ~ 180 12")
                for ((i, rot) in listOf(0, 90, 180, 270).withIndex()) {
                    player.networkHandler.sendChatCommand("summon armor_stand ~${-3 + i * 2} ~ ~-4 {ShowArms:1b,NoGravity:1b,Rotation:[${rot}f,0f]," +
                        "HandItems:[{id:\"$ITEM\",count:1},{}]}")
                }
                val keep = TropiPaintings.thirdPerson to TropiPaintings.firstPerson
                shots += "before" to { TropiPaintings.enabled = false }
                shots += "after" to { TropiPaintings.enabled = true; TropiPaintings.thirdPerson = keep.first; TropiPaintings.firstPerson = keep.second }
                if (mode == "try") for ((name, tp, fp) in tries) shots += "try_$name" to { TropiPaintings.enabled = true; TropiPaintings.thirdPerson = tp; TropiPaintings.firstPerson = fp }
                shots += "end" to { TropiPaintings.enabled = true; TropiPaintings.thirdPerson = keep.first; TropiPaintings.firstPerson = keep.second }
                phase = 1; wait = 0
            }
            1 -> {
                // the empty hand first: where Minecraft draws the arm, to line a drawing up with
                if (wait == 40) { client.options.perspective = Perspective.FIRST_PERSON; client.options.hudHidden = false }
                if (wait == 48) { shot(client, out, "arm"); player.networkHandler.sendChatCommand("give @s $ITEM") }
                if (wait > 80) { phase = 2; wait = 0 }
            }
            100 -> {
                // the three sizes side by side, all facing the camera, and the largest in hand
                if (wait == 1) {
                    player.networkHandler.sendChatCommand("kill @e[type=armor_stand]")
                    player.networkHandler.sendChatCommand("clear @s")
                    player.networkHandler.sendChatCommand("give @s tropifurnitures:painting_3x3_10")
                    player.networkHandler.sendChatCommand("item replace entity @s weapon.offhand with tropifurnitures:painting_3x2_10")
                    for ((i, id) in listOf("painting_2x2_10", "painting_3x2_10", "painting_3x3_10").withIndex()) {
                        player.networkHandler.sendChatCommand("summon armor_stand ~${-3 + i * 3} ~ ~-4 {ShowArms:1b,NoGravity:1b,Rotation:[0f,0f]," +
                            "HandItems:[{id:\"tropifurnitures:$id\",count:1},{}]}")
                    }
                    client.options.perspective = Perspective.FIRST_PERSON
                }
                if (wait == 40) shot(client, out, "sizes")
                if (wait == 41) { client.options.perspective = Perspective.THIRD_PERSON_FRONT }
                if (wait == 50) {
                    shot(client, out, "sizes_front")
                    client.options.perspective = Perspective.FIRST_PERSON
                    if (mode != "april") return true
                    phase = 200; wait = 0
                }
            }
            200 -> {
                // the 1st of April: the same paintings, in hand, on walls and in the inventory, two moments apart
                if (wait == 1) {
                    player.networkHandler.sendChatCommand("hbdebug april on")   // the player's own command
                    player.networkHandler.sendChatCommand("setblock ~-3 ~1 ~-7 tropifurnitures:painting_2x2_10[facing=south]")
                    player.networkHandler.sendChatCommand("setblock ~1 ~1 ~-7 tropifurnitures:painting_3x2_10[facing=south]")
                    player.networkHandler.sendChatCommand("setblock ~5 ~1 ~-7 tropifurnitures:painting_3x3_10[facing=north]")
                }
                if (wait == 29) HunterBoard.LOGGER.info("[PaintingCheck] /hbdebug april on -> simulated=${AprilPaintings.simulated}")
                if (wait == 30) shot(client, out, "april_1")
                if (wait == 36) shot(client, out, "april_2")
                if (wait == 37) client.setScreen(net.minecraft.client.gui.screen.ingame.InventoryScreen(player))
                if (wait == 46) { shot(client, out, "april_inventory"); client.setScreen(null) }
                if (wait == 47) client.options.perspective = Perspective.THIRD_PERSON_FRONT
                if (wait == 56) {
                    shot(client, out, "april_front")
                    player.networkHandler.sendChatCommand("hbdebug april off")
                    player.networkHandler.sendChatCommand("hbdebug april")
                    client.options.perspective = Perspective.FIRST_PERSON
                }
                if (wait == 70) {
                    HunterBoard.LOGGER.info("[PaintingCheck] /hbdebug april off -> simulated=${AprilPaintings.simulated}")
                    shot(client, out, "april_off")
                    return true
                }
            }
            else -> {
                val i = phase - 2
                if (i >= shots.size - 1) {
                    shots.last().second?.invoke()
                    phase = 100; wait = 0
                    return false
                }
                val (name, set) = shots[i]
                // the first person, then the holder from the front, from the side, and from behind and above
                when (wait) {
                    1 -> { set?.invoke(); client.options.perspective = Perspective.FIRST_PERSON }
                    8 -> shot(client, out, "${name}_stands")
                    9 -> client.options.perspective = Perspective.THIRD_PERSON_FRONT
                    16 -> { shot(client, out, "${name}_front"); bodyTurn = 90f }
                    23 -> { shot(client, out, "${name}_side"); bodyTurn = 0f; player.pitch = 50f; client.options.perspective = Perspective.THIRD_PERSON_BACK }
                    30 -> { shot(client, out, "${name}_above"); player.pitch = 12f; phase++; wait = 0 }
                }
            }
        }
        return false
    }
}
