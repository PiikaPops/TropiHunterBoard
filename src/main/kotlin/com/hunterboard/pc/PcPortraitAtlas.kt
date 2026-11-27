package com.hunterboard.pc

import com.cobblemon.mod.common.client.gui.ProfileTransformType
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.Pokemon
import com.hunterboard.HunterBoard
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.systems.VertexSorter
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.SimpleFramebuffer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.texture.AbstractTexture
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.resource.ResourceManager
import net.minecraft.util.Identifier
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil

/**
 * Still portraits for the PC grid, each drawn once into an off-screen atlas and then
 * copied like any picture.
 *
 * A live 3D model costs a full model render every frame: a PC view showing 12 boxes drew
 * 360 of them and fell from ~118 to ~18 frames per second. Here each look (species, form,
 * aspects such as shiny) is rendered once, a few per frame, whatever the number of copies.
 * If the off-screen buffer cannot be made, [available] turns false and the screen goes
 * back to live models.
 */
internal object PcPortraitAtlas {
    private const val TILE = 64
    private const val SIZE = 2048
    private const val PER_ROW = SIZE / TILE
    private const val CAPACITY = PER_ROW * PER_ROW
    private const val PER_FRAME = 12
    private val ID = Identifier.of("hunterboard", "pc_portrait_atlas")

    private var fb: SimpleFramebuffer? = null
    private val tiles = HashMap<String, Int>()
    private val pending = LinkedHashMap<String, Pokemon>()
    private var failed = false

    val available: Boolean get() = !failed

    private class AtlasTexture(private val buffer: SimpleFramebuffer) : AbstractTexture() {
        override fun load(manager: ResourceManager) {}
        override fun getGlId(): Int = buffer.colorAttachment
        override fun close() {}   // the framebuffer owns the texture
    }

    private fun key(p: Pokemon): String = p.species.resourceIdentifier.toString() + "|" + p.aspects.sorted().joinToString(",")

    private fun ensure(): SimpleFramebuffer? {
        fb?.let { return it }
        if (failed) return null
        return try {
            val f = SimpleFramebuffer(SIZE, SIZE, true, MinecraftClient.IS_SYSTEM_MAC)
            f.setTexFilter(GL11.GL_LINEAR)
            f.setClearColor(0f, 0f, 0f, 0f)
            f.clear(MinecraftClient.IS_SYSTEM_MAC)
            MinecraftClient.getInstance().framebuffer.beginWrite(true)
            MinecraftClient.getInstance().textureManager.registerTexture(ID, AtlasTexture(f))
            fb = f
            f
        } catch (e: Throwable) {
            HunterBoard.LOGGER.warn("[PC] Portrait atlas unavailable, live models instead: $e")
            failed = true
            null
        }
    }

    /**
     * Draws [pokemon]'s portrait if it is ready, otherwise queues it and returns false (the
     * cell stays empty for a frame or two).
     */
    fun draw(ctx: DrawContext, pokemon: Pokemon, x: Int, y: Int, size: Int): Boolean {
        if (failed) return false
        val k = key(pokemon)
        val t = tiles[k]
        if (t == null) { if (!pending.containsKey(k)) pending[k] = pokemon; return false }
        val u = (t % PER_ROW) * TILE
        val v = (t / PER_ROW) * TILE
        // the framebuffer is stored bottom-up: draw the tile flipped back
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        ctx.matrices.push()
        ctx.matrices.translate(x.toFloat(), (y + size).toFloat(), 0f)
        ctx.matrices.scale(1f, -1f, 1f)
        ctx.drawTexture(ID, 0, 0, size, size, u.toFloat(), v.toFloat(), TILE, TILE, SIZE, SIZE)
        ctx.matrices.pop()
        RenderSystem.enableCull()
        RenderSystem.disableBlend()
        return true
    }

    /**
     * Many portraits in one draw call: [queue] them while laying out a box, then [flush].
     * Drawing them one by one cost a draw call each, which a full PC view pays hundreds of
     * times per frame.
     */
    class Batch {
        internal val quads = ArrayList<FloatArray>(64)
        fun clear() = quads.clear()
    }

    /** Adds a portrait to [batch]; false when it is not rendered yet (queued for that). */
    fun queue(batch: Batch, pokemon: Pokemon, x: Int, y: Int, size: Int): Boolean {
        if (failed) return false
        val k = key(pokemon)
        val t = tiles[k]
        if (t == null) { if (!pending.containsKey(k)) pending[k] = pokemon; return false }
        val u1 = ((t % PER_ROW) * TILE).toFloat() / SIZE
        val u2 = u1 + TILE.toFloat() / SIZE
        val vBottom = ((t / PER_ROW) * TILE).toFloat() / SIZE
        val vTop = vBottom + TILE.toFloat() / SIZE
        batch.quads += floatArrayOf(x.toFloat(), y.toFloat(), (x + size).toFloat(), (y + size).toFloat(), u1, u2, vTop, vBottom)
        return true
    }

    fun flush(ctx: DrawContext, batch: Batch) {
        if (batch.quads.isEmpty() || fb == null) { batch.clear(); return }
        val m = ctx.matrices.peek().positionMatrix
        RenderSystem.setShaderTexture(0, ID)
        RenderSystem.setShader { net.minecraft.client.render.GameRenderer.getPositionTexProgram() }
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        val buf = net.minecraft.client.render.Tessellator.getInstance()
            .begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS, net.minecraft.client.render.VertexFormats.POSITION_TEXTURE)
        for (q in batch.quads) {
            // the framebuffer is stored bottom-up, hence top of the quad = top of the tile (vTop)
            buf.vertex(m, q[0], q[1], 0f).texture(q[4], q[6])
            buf.vertex(m, q[0], q[3], 0f).texture(q[4], q[7])
            buf.vertex(m, q[2], q[3], 0f).texture(q[5], q[7])
            buf.vertex(m, q[2], q[1], 0f).texture(q[5], q[6])
        }
        net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(buf.end())
        RenderSystem.disableBlend()
        batch.clear()
    }

    /** Renders a few queued portraits. Call at the start of a frame, before any scissor. */
    fun renderPending(ctx: DrawContext) {
        if (pending.isEmpty() || failed) return
        val f = ensure() ?: return
        ctx.draw()
        val client = MinecraftClient.getInstance()
        var done = 0
        val it = pending.entries.iterator()
        try {
            RenderSystem.backupProjectionMatrix()
            var measured = 0
            while (it.hasNext() && done < PER_FRAME) {
                val (k, pokemon) = it.next()
                // a look seen for the first time is measured first; a few per frame (each one waits for the GPU)
                val fit = fits[k] ?: (if (measured < MEASURE_PER_FRAME) measure(pokemon).also { fits[k] = it; measured++ } else null) ?: break
                it.remove()
                if (tiles.size >= CAPACITY) { tiles.clear(); f.clear(MinecraftClient.IS_SYSTEM_MAC) }
                val index = tiles.size
                val tx = (index % PER_ROW) * TILE
                val ty = (index / PER_ROW) * TILE
                f.beginWrite(false)
                RenderSystem.viewport(tx, ty, TILE, TILE)
                RenderSystem.enableScissor(tx, ty, TILE, TILE)
                RenderSystem.clearColor(0f, 0f, 0f, 0f)
                RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
                RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, TILE.toFloat(), TILE.toFloat(), 0f, 1000f, 21000f), VertexSorter.BY_Z)
                try {
                    drawModel(pokemon, fit)
                } catch (e: Throwable) {
                    HunterBoard.LOGGER.debug("[PC] portrait of $k failed: $e")
                }
                RenderSystem.disableScissor()
                tiles[k] = index
                done++
            }
        } finally {
            RenderSystem.restoreProjectionMatrix()
            client.framebuffer.beginWrite(true)
        }
    }

    /** Frees the atlas (16 MB of video memory) when the PC screen goes away; the measures stay. */
    fun release() {
        pending.clear()
        tiles.clear()
        fb?.let {
            MinecraftClient.getInstance().textureManager.destroyTexture(ID)
            it.delete()
        }
        fb = null
        probe?.delete()
        probe = null
    }

    /** Same framing as the live portraits (see Portraits). */
    private const val SCALE = 0.038f
    private const val OFFSET = 0.42

    // ── fitting each look in its cell ──────────────────────────────────────────────────
    //
    // Cobblemon's portrait framing (SCALE, OFFSET) is the same for every species, so the big
    // ones — Wailord, Steelix, Gyarados, Groudon… — came out cut at the cell's edge. Each look
    // is drawn once, smaller, in a probe buffer and the pixels it covers read back: one that
    // fits its tile keeps the framing exactly as it was, one that does not is shrunk about its
    // feet and slid in until it fits whole. Measured once per look for the session.

    /** Where a look's model goes in its tile (pixels, Y down) and its scale. */
    private class Fit(val x: Float, val y: Float, val scale: Float)

    private val DEFAULT = Fit(TILE * 0.5f, (-TILE * OFFSET).toFloat(), TILE * SCALE)
    private val fits = HashMap<String, Fit>()
    private const val PROBE = 256
    private const val MEASURE_PER_FRAME = 4
    /** Room left around a shrunk model, in tile pixels. */
    private const val MARGIN = 1f
    private var probe: SimpleFramebuffer? = null
    private var probeFailed = false

    private fun drawModel(pokemon: Pokemon, fit: Fit) {
        val ms = MatrixStack()
        ms.translate(fit.x.toDouble(), fit.y.toDouble(), 0.0)
        ms.scale(fit.scale, fit.scale, fit.scale)
        val rotation = Quaternionf().rotationXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(325.0).toFloat(), 0f)
        drawProfilePokemon(pokemon.asRenderablePokemon(), ms, rotation, PoseType.PROFILE, FloatingState(), 0f, 20f,
            ProfileTransformType.SUMMARY, false, 1f, 1f, 1f, 1f, 0f, 0f, 15)
        MinecraftClient.getInstance().bufferBuilders.entityVertexConsumers.draw()
    }

    private fun ensureProbe(): SimpleFramebuffer? {
        probe?.let { return it }
        if (probeFailed) return null
        return try {
            SimpleFramebuffer(PROBE, PROBE, true, MinecraftClient.IS_SYSTEM_MAC).also { it.setClearColor(0f, 0f, 0f, 0f); probe = it }
        } catch (e: Throwable) {
            HunterBoard.LOGGER.warn("[PC] Portrait probe unavailable, portraits keep Cobblemon's framing: $e")
            probeFailed = true
            null
        }
    }

    /**
     * The look drawn [shrink] times its usual size around the probe's centre (the probe is
     * [PROBE] / [TILE] times finer than a tile), its pixels read back, and its bounds turned
     * into tile pixels. Touching the probe's edge means it was cut: tried again smaller.
     */
    private fun measure(pokemon: Pokemon): Fit {
        val p = ensureProbe() ?: return DEFAULT
        val c = TILE / 2f
        val pc = PROBE / 2f
        var shrink = 0.35f
        repeat(3) {
            val f = PROBE.toFloat() / TILE * shrink
            p.beginWrite(true)
            RenderSystem.clearColor(0f, 0f, 0f, 0f)
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC)
            RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, PROBE.toFloat(), PROBE.toFloat(), 0f, 1000f, 21000f), VertexSorter.BY_Z)
            try {
                drawModel(pokemon, Fit(pc + f * (DEFAULT.x - c), pc + f * (DEFAULT.y - c), f * DEFAULT.scale))
            } catch (e: Throwable) {
                return DEFAULT
            }
            val box = readBox() ?: return DEFAULT   // nothing drawn: left as it is
            if (box[0] == 0 || box[1] == 0 || box[2] >= PROBE - 1 || box[3] >= PROBE - 1) { shrink *= 0.4f; return@repeat }
            // probe pixels → tile pixels; rows count from the bottom, the tile's Y from the top
            fun tile(v: Float) = c + (v - pc) / f
            return fitOf(tile(box[0].toFloat()), tile(PROBE - box[3] - 1f), tile(box[2] + 1f), tile(PROBE - box[1].toFloat()))
        }
        return DEFAULT
    }

    /** The covered pixels' bounds in the bound probe: [minCol, minRow, maxCol, maxRow], rows from the bottom; null if none. */
    private fun readBox(): IntArray? {
        val buf = MemoryUtil.memAlloc(PROBE * PROBE * 4)
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4)
            GL11.glReadPixels(0, 0, PROBE, PROBE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
            var c0 = PROBE; var r0 = PROBE; var c1 = -1; var r1 = -1
            for (r in 0 until PROBE) for (col in 0 until PROBE) {
                if ((buf.get((r * PROBE + col) * 4 + 3).toInt() and 0xFF) > 16) {
                    if (col < c0) c0 = col
                    if (col > c1) c1 = col
                    if (r < r0) r0 = r
                    if (r > r1) r1 = r
                }
            }
            return if (c1 < 0) null else intArrayOf(c0, r0, c1, r1)
        } finally {
            MemoryUtil.memFree(buf)
        }
    }

    /** The framing for a model whose usual bounds in its tile are ([x0], [y0])–([x1], [y1]). */
    private fun fitOf(x0: Float, y0: Float, x1: Float, y1: Float): Fit {
        val lo = MARGIN
        val hi = TILE - MARGIN
        if (x0 >= lo && x1 <= hi && y0 >= lo && y1 <= hi) return DEFAULT
        val k = minOf(1f, (hi - lo) / (x1 - x0), (hi - lo) / (y1 - y0))
        // shrunk about its feet (the bottom centre), so it stands where it stood, then slid inside
        val ax = (x0 + x1) / 2f
        val ay = y1
        val nx0 = ax + k * (x0 - ax)
        val nx1 = ax + k * (x1 - ax)
        val ny0 = ay + k * (y0 - ay)
        val dx = when { nx0 < lo -> lo - nx0; nx1 > hi -> hi - nx1; else -> 0f }
        val dy = when { ny0 < lo -> lo - ny0; ay > hi -> hi - ay; else -> 0f }
        return Fit(ax + dx + k * (DEFAULT.x - ax), ay + dy + k * (DEFAULT.y - ay), k * DEFAULT.scale)
    }
}
