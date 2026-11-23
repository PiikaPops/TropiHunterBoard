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
            while (it.hasNext() && done < PER_FRAME) {
                val (k, pokemon) = it.next()
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
                    val ms = MatrixStack()
                    ms.translate(TILE * 0.5, -TILE * OFFSET, 0.0)
                    ms.scale(TILE * SCALE, TILE * SCALE, TILE * SCALE)
                    val rotation = Quaternionf().rotationXYZ(Math.toRadians(13.0).toFloat(), Math.toRadians(325.0).toFloat(), 0f)
                    drawProfilePokemon(pokemon.asRenderablePokemon(), ms, rotation, PoseType.PROFILE, FloatingState(), 0f, 20f,
                        ProfileTransformType.SUMMARY, false, 1f, 1f, 1f, 1f, 0f, 0f, 15)
                    client.bufferBuilders.entityVertexConsumers.draw()
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

    /** Frees the atlas (16 MB of video memory) when the PC screen goes away. */
    fun release() {
        pending.clear()
        tiles.clear()
        fb?.let {
            MinecraftClient.getInstance().textureManager.destroyTexture(ID)
            it.delete()
        }
        fb = null
    }

    /** Same framing as the live portraits (see Portraits). */
    private const val SCALE = 0.038f
    private const val OFFSET = 0.42
}
