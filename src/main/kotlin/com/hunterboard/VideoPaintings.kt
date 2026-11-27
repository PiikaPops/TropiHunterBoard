package com.hunterboard

import com.google.gson.JsonParser
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random

/**
 * Tropimon paintings that play a video instead of their picture, with its sound on a click —
 * on the 1st of April only, with the April Fools fish on the other paintings
 * ([AprilPaintings]; `/hbdebug april on` shows both on any day).
 *
 * The picture is swapped where Tropimon picks it (`AprilPaintingMixin`, as for April Fools):
 * the painting's texture becomes one of HunterBoard's, redrawn with the video's next frame as
 * time passes. The video loops silently; a right click on the painting (any part of it, empty
 * hand or not, without sneaking) starts the sound from the beginning — the video starts over
 * with it, so the two stay together — and another click stops it. The sound comes from the
 * painting and fades with distance (Jukebox/Note Blocks volume); it stops when the painting is
 * gone or far away, and on leaving the server.
 *
 * Only this client changes: other players see Tropimon's picture, or the video too if they
 * have HunterBoard, and only hear the sound if they click themselves.
 *
 * A video is a folder of frames (PNG) with a `video.json`, and a mono OGG; made from any video
 * file by `tools/video_painting.py`. Frames are read one at a time as they are shown, so
 * nothing is kept in memory but the painting's own texture.
 */
object VideoPaintings {

    /** Which painting plays which video (the name `tools/video_painting.py` was given). */
    private val VIDEOS = mapOf("painting_3x2_3" to "evasion")   // L'Évasion sous-marine

    private const val PAINTINGS = "textures/paintings/"
    private val SIZE = Regex("""(\d)x(\d)""")

    private class Video(val name: String, val frames: Int, val frameMs: Long, val width: Int, val height: Int,
                        val durationMs: Long, val sound: Identifier)

    private class Screen(val id: Identifier, val texture: NativeImageBackedTexture, val x: Int, val y: Int) {
        var frame = -1
    }

    private val videos = HashMap<String, Video?>()
    private val screens = HashMap<String, Screen>()
    /** Where each video's time starts: the click that started its sound, or 0 (the clock itself). */
    private val origin = HashMap<String, Long>()
    private val broken = HashSet<String>()

    private class Playing(val video: String, val pos: BlockPos, val at: Vec3d, var sound: SoundInstance)
    private var playing: Playing? = null
    private var lastClick = 0L
    private var ticks = 0

    fun register() {
        UseBlockCallback.EVENT.register { player, world, hand, hit ->
            if (!world.isClient || hand != Hand.MAIN_HAND || player.isSneaking || !AprilPaintings.isOn()) return@register ActionResult.PASS
            val block = Registries.BLOCK.getId(world.getBlockState(hit.blockPos).block)
            val video = if (block.namespace == "tropifurnitures") VIDEOS[block.path] else null
            if (video == null || video in broken) return@register ActionResult.PASS
            // holding the button repeats the click every few ticks: one toggle per press
            val now = System.currentTimeMillis()
            if (now - lastClick > 350L) toggle(video, hit.blockPos, hit.pos)
            lastClick = now
            ActionResult.FAIL   // handled here: nothing sent, nothing placed against the painting
        }
        ClientTickEvents.END_CLIENT_TICK.register { client -> try { tick(client) } catch (_: Exception) {} }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> MinecraftClient.getInstance().execute { stop(false) } }
    }

    private fun t(en: String, fr: String) = if (Translations.isFrench()) fr else en

    private fun toggle(name: String, pos: BlockPos, at: Vec3d) {
        val p = playing
        // the same painting again: off; another one (or none playing): on, from that one
        if (p != null && p.video == name && p.pos.isWithinDistance(pos, 4.0)) { stop(true); return }
        stop(false)
        val video = load(name) ?: return
        origin[name] = System.currentTimeMillis()
        playing = Playing(name, pos, at, play(video, at))
        MinecraftClient.getInstance().inGameHud.setOverlayMessage(Text.literal("♪ " + t("Painting sound on", "Son du tableau activé")), false)
    }

    private fun play(video: Video, at: Vec3d): SoundInstance {
        val sound = PositionedSoundInstance(video.sound, SoundCategory.RECORDS, 1f, 1f, Random.create(), false, 0,
            SoundInstance.AttenuationType.LINEAR, at.x, at.y, at.z, false)
        MinecraftClient.getInstance().soundManager.play(sound)
        return sound
    }

    private fun stop(say: Boolean) {
        val p = playing ?: return
        playing = null
        MinecraftClient.getInstance().soundManager.stop(p.sound)
        if (say) MinecraftClient.getInstance().inGameHud.setOverlayMessage(Text.literal("♪ " + t("Painting sound off", "Son du tableau coupé")), false)
    }

    private fun tick(client: MinecraftClient) {
        val p = playing ?: return
        if (!AprilPaintings.isOn()) { stop(false); return }   // the day over, or the simulation stopped
        val video = videos[p.video] ?: return
        val now = System.currentTimeMillis()
        // the end of the video: both start over together
        if (now - (origin[p.video] ?: now) >= video.durationMs) {
            origin[p.video] = now
            client.soundManager.stop(p.sound)
            p.sound = play(video, p.at)
        }
        if (++ticks % 20 != 0) return
        // the painting broken, or left far behind: silence
        val world = client.world
        val player = client.player
        val here = world?.let { Registries.BLOCK.getId(it.getBlockState(p.pos).block) }
        if (world == null || player == null || here?.path != VIDEOS.entries.firstOrNull { it.value == p.video }?.key ||
            player.pos.squaredDistanceTo(p.at) > 64.0 * 64.0) stop(false)
    }

    // ── the picture ──────────────────────────────────────────────────────────────────────

    /** The texture to draw instead of [id]: the video's current frame for a video painting, [id] otherwise. */
    @JvmStatic
    fun texture(id: Identifier): Identifier {
        if (id.namespace != "tropifurnitures" || !id.path.startsWith(PAINTINGS) || !AprilPaintings.isOn()) return id
        val block = id.path.substring(PAINTINGS.length).substringBeforeLast('.')
        val name = VIDEOS[block] ?: return id
        if (name in broken || !RenderSystem.isOnRenderThread()) return id
        return try {
            screen(name, block)?.id ?: id
        } catch (e: Exception) {
            broken += name
            HunterBoard.LOGGER.warn("Video painting $name: off (${e.javaClass.simpleName}: ${e.message})")
            id
        }
    }

    private fun load(name: String): Video? = videos.getOrPut(name) {
        try {
            val json = MinecraftClient.getInstance().resourceManager
                .getResource(Identifier.of(HunterBoard.MOD_ID, "textures/video/$name/video.json")).orElseThrow()
                .inputStream.use { JsonParser.parseReader(it.reader()).asJsonObject }
            Video(name, json.get("frames").asInt, json.get("frameMs").asLong.coerceAtLeast(20L), json.get("width").asInt,
                json.get("height").asInt, json.get("durationMs").asLong, Identifier.of(json.get("sound").asString))
        } catch (e: Exception) {
            broken += name
            HunterBoard.LOGGER.warn("Video painting $name: cannot be read (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    private fun screen(name: String, block: String): Screen? {
        val video = load(name) ?: return null
        val s = screens.getOrPut(name) {
            // the video whole in the painting's proportions (3×2: 2.75 × 1.75 blocks), black around it
            val size = SIZE.find(block)
            val ratio = if (size != null && size.groupValues[1] != size.groupValues[2]) 2.75f / 1.75f else 1f
            val (w, h) = if (video.width.toFloat() / video.height > ratio) video.width to Math.round(video.width / ratio)
                         else Math.round(video.height * ratio) to video.height
            val image = NativeImage(w, h, false)
            image.fillRect(0, 0, w, h, 0xFF000000.toInt())
            val texture = NativeImageBackedTexture(image)
            val id = Identifier.of(HunterBoard.MOD_ID, "video/$name")
            MinecraftClient.getInstance().textureManager.registerTexture(id, texture)
            Screen(id, texture, (w - video.width) / 2, (h - video.height) / 2)
        }
        val frame = (((System.currentTimeMillis() - (origin[name] ?: 0L)) / video.frameMs) % video.frames).toInt()
        if (frame != s.frame) {
            s.frame = frame
            val target = s.texture.image ?: return null
            MinecraftClient.getInstance().resourceManager
                .getResource(Identifier.of(HunterBoard.MOD_ID, "textures/video/$name/%04d.png".format(frame))).orElseThrow()
                .inputStream.use { NativeImage.read(it) }
                .use { it.copyRect(target, 0, 0, s.x, s.y, video.width, video.height, false, false) }
            s.texture.upload()
        }
        return s
    }
}
