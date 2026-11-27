package com.hunterboard

import com.google.gson.JsonParser
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import java.time.LocalDate
import java.time.Month

/**
 * April Fools easter egg: on the 1st of April, every Tropimon painting shows His Majesty the
 * sturgeon, turning on himself — on the walls, in hand and in the inventory.
 *
 * Tropimon draws a painting's picture with `tropifurnitures:textures/paintings/<name>.webp`,
 * handed to `RenderLayer.getEntityCutoutNoCull` (see `AprilPaintingMixin`). On that day the
 * texture is swapped for one of HunterBoard's, whose picture moves on to the next frame of
 * the animation as time passes: the same frame on every painting at once, as a GIF would.
 *
 * The animation ships as one PNG strip, its frames stacked top to bottom, and a JSON with
 * their size and timing (`tools/april_painting.py` makes both from a GIF): decoded by
 * Minecraft's own PNG reader, nothing depends on Java's GIF support. Each frame is set in the
 * painting's own proportions — square for 2×2 and 3×3, wide for 3×2 — on its background
 * colour, so the picture is never stretched.
 *
 * Only the player's own client changes; on any other day nothing is loaded at all.
 */
object AprilPaintings {

    private const val PAINTINGS = "textures/paintings/"
    private val STRIP = Identifier.of(HunterBoard.MOD_ID, "textures/april/fish.png")
    private val META = Identifier.of(HunterBoard.MOD_ID, "textures/april/fish.json")
    private val SIZE = Regex("""(\d)x(\d)""")

    /** The 1st of April pretended, for this session only (`/hbdebug april`, the development check). */
    var simulated = false
        private set
    private var activeCache = false
    private var activeCheckedAt = 0L
    /** One failure (a missing or broken strip) and the paintings stay as Tropimon drew them. */
    private var broken = false

    private class Animation(val strip: NativeImage, val width: Int, val height: Int, val frames: Int, val frameMs: Long, val background: Int)
    private var animation: Animation? = null

    /** The picture laid out in one painting's proportions, redrawn when the frame changes. */
    private class Canvas(val id: Identifier, val texture: NativeImageBackedTexture, val x: Int, val y: Int) {
        var frame = -1
    }
    private val canvases = HashMap<Boolean, Canvas>()

    fun isAprilFools(date: LocalDate = LocalDate.now()): Boolean = date.month == Month.APRIL && date.dayOfMonth == 1

    /** Pretends today is the 1st of April, or stops; nothing is saved, a restart forgets it. */
    fun simulate(on: Boolean) {
        simulated = on
        broken = false          // a fresh try, should the last one have failed
        activeCheckedAt = 0L    // seen from the next frame on, not a second later
    }

    /** The 1st of April, real or pretended: the paintings' surprises are on. */
    @JvmStatic
    fun isOn(): Boolean = active()

    private fun active(): Boolean {
        // asked for every textured part of every entity drawn: the date needs a look once a second
        val now = System.currentTimeMillis()
        if (now - activeCheckedAt > 1_000L) {
            activeCheckedAt = now
            activeCache = !broken && (simulated || isAprilFools())
        }
        return activeCache
    }

    /** The texture to draw instead of [id]: a painting's picture on the 1st of April, [id] otherwise. */
    @JvmStatic
    fun texture(id: Identifier): Identifier {
        if (!active() || id.namespace != "tropifurnitures" || !id.path.startsWith(PAINTINGS)) return id
        if (!RenderSystem.isOnRenderThread()) return id
        return try {
            // 3×2 is wide, 2×2 and 3×3 are square (the picture's own area: 2.75 × 1.75 blocks, or square)
            val size = SIZE.find(id.path.substring(PAINTINGS.length))
            val wide = size != null && size.groupValues[1] != size.groupValues[2]
            canvas(wide)?.id ?: id
        } catch (e: Exception) {
            broken = true
            activeCache = false
            HunterBoard.LOGGER.warn("April paintings: off (${e.javaClass.simpleName}: ${e.message})")
            id
        }
    }

    private fun load(): Animation {
        animation?.let { return it }
        val resources = MinecraftClient.getInstance().resourceManager
        val meta = resources.getResource(META).orElseThrow().inputStream.use {
            JsonParser.parseReader(it.reader()).asJsonObject
        }
        val strip = resources.getResource(STRIP).orElseThrow().inputStream.use { NativeImage.read(it) }
        val width = meta.get("width").asInt
        val height = meta.get("height").asInt
        val frames = meta.get("frames").asInt.coerceAtMost(strip.height / height)
        require(frames > 0 && strip.width >= width) { "strip ${strip.width}x${strip.height} for $frames frames of ${width}x$height" }
        return Animation(strip, width, height, frames, meta.get("frameMs").asLong.coerceAtLeast(20L), strip.getColor(0, 0))
            .also { animation = it }
    }

    private fun canvas(wide: Boolean): Canvas? {
        val a = load()
        val c = canvases.getOrPut(wide) {
            // the frame fitted whole into the painting's proportions, the rest in its background colour
            val ratio = if (wide) 2.75f / 1.75f else 1f
            val (w, h) = if (a.width.toFloat() / a.height > ratio) a.width to Math.round(a.width / ratio)
                         else Math.round(a.height * ratio) to a.height
            val image = NativeImage(w, h, false)
            image.fillRect(0, 0, w, h, a.background)
            val texture = NativeImageBackedTexture(image)
            val id = Identifier.of(HunterBoard.MOD_ID, "april/painting_" + if (wide) "wide" else "square")
            MinecraftClient.getInstance().textureManager.registerTexture(id, texture)
            Canvas(id, texture, (w - a.width) / 2, (h - a.height) / 2)
        }
        val frame = ((System.currentTimeMillis() / a.frameMs) % a.frames).toInt()
        if (frame != c.frame) {
            c.frame = frame
            val image = c.texture.image ?: return null
            // from the frame's place in the strip to the canvas's centre (the two last positions are where it lands)
            a.strip.copyRect(image, 0, frame * a.height, c.x, c.y, a.width, a.height, false, false)
            c.texture.upload()
        }
        return c
    }
}
