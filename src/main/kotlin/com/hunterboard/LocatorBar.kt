package com.hunterboard

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Tropimon's radar bar, moved to the top of the screen and enlarged.
 *
 * This is deliberately not a reinterpretation. Tropimon's own `RadarBarRenderer` lays the
 * bar out like this, just above the hotbar:
 *
 * ```
 * x = width/2 - 91;  y = height - 29
 * drawGuiTexture(BACKGROUND, x, y - 5, 182, 10)
 * px = x + 91 + round(clamp(delta / 90, -1, 1) * 85)
 * drawGuiTexture(icon, px - 4, y - 2, 9, 9)
 * alpha = abs(delta) > 90 ? 0.5 : 1
 * ```
 *
 * Every one of those numbers is reproduced below, against the same sprites, so the bar is
 * their bar — including the four-frame animation baked into their background texture. The
 * only changes are where it sits and how big it is, and the size is applied as a matrix
 * scale around the native 182×10 layout rather than by stretching the texture to new
 * dimensions, which is what keeps it crisp instead of smeared.
 *
 * Nothing here talks to the server. The blips are read from a static field of Tropimon's
 * manager, so without that mod the bar simply never draws.
 */
object LocatorBar {

    private const val MANAGER = "fr.erusel.tropimodclient.client.hud.contextbar.ContextBarManager"
    private const val NAMESPACE = "tropimodclient"

    // --- Tropimon's layout, verbatim ----------------------------------------
    private const val BAR_WIDTH = 182
    private const val BAR_HEIGHT = 10
    private const val ICON_SIZE = 9
    private const val ICON_TOP = 3        // icon row, measured from the background's top
    private const val HALF_ANGLE = 90f
    private const val OUT_OF_RANGE_ALPHA = 0.5f

    /**
     * Where the background texture may be cut, measured from its own pixels.
     *
     * Columns 3–85 and 95–178 are each a single repeated column, in all four animation
     * frames; 0–2 and 179–181 are the end caps and 86–94 is the centre sight. Lengthening
     * the bar therefore means repeating the two uniform spans, never stretching them —
     * stretching distorted the caps and the sight along with everything else.
     */
    private const val CAP = 3             // end cap width
    private const val LEFT_SPAN_U = 3
    private const val LEFT_SPAN_W = 83    // columns 3..85
    private const val CENTRE_U = 86
    private const val CENTRE_W = 9        // columns 86..94, the sight
    private const val RIGHT_SPAN_U = 95
    private const val RIGHT_SPAN_W = 84   // columns 95..178
    private const val RIGHT_CAP_U = 179

    /** Distance the original keeps between the sight and the bar's end: 91 − 85. */
    private const val EDGE_MARGIN = 6

    private val BACKGROUND: Identifier = Identifier.of(NAMESPACE, "contextbar/radar/background")

    // --- reflective handles, resolved once -----------------------------------

    private var resolved = false
    private var available = false
    private var displayedField: Field? = null
    private var alphaField: Field? = null
    private var blipsMethod: Method? = null
    private var xM: Method? = null
    private var yM: Method? = null
    private var zM: Method? = null
    private var iconM: Method? = null
    private var entityIdM: Method? = null
    private var iconIdM: Method? = null

    /**
     * Binds to Tropimon's classes on first use.
     *
     * Everything is optional on purpose: a missing class means the server is not Tropimon,
     * which is not an error, so it is logged once and the bar stays quiet afterwards.
     */
    private fun resolve() {
        if (resolved) return
        resolved = true
        try {
            val manager = Class.forName(MANAGER)
            displayedField = manager.getDeclaredField("displayed").apply { isAccessible = true }
            alphaField = manager.getDeclaredField("alpha").apply { isAccessible = true }

            val data = Class.forName("fr.tropimon.mccore.contextbar.RadarBarData")
            blipsMethod = data.getMethod("blips")

            val blip = Class.forName("fr.tropimon.mccore.contextbar.RadarBarData\$Blip")
            xM = blip.getMethod("x"); yM = blip.getMethod("y"); zM = blip.getMethod("z")
            iconM = blip.getMethod("icon")
            entityIdM = blip.getMethod("entityId")

            val icon = Class.forName("fr.tropimon.mccore.contextbar.RadarIcons\$Icon")
            iconIdM = icon.getMethod("id")

            available = true
            HunterBoard.LOGGER.info("[LocatorBar] Tropimon radar found, wide bar available")
        } catch (_: ClassNotFoundException) {
            HunterBoard.LOGGER.info("[LocatorBar] Tropimon radar not present; wide bar disabled")
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("[LocatorBar] could not read Tropimon's radar: ${e.message}")
        }
    }

    private class Blip(val x: Double, val y: Double, val z: Double, val iconId: String)

    /**
     * The blips Tropimon is currently showing, with its own fade.
     *
     * Reading `alpha` as well as `displayed` keeps this bar appearing and disappearing in
     * step with theirs instead of on its own schedule.
     */
    private fun readBlips(): Pair<List<Blip>, Float>? {
        if (!available) return null
        return try {
            val data = displayedField?.get(null) ?: return null
            val alpha = (alphaField?.getFloat(null) ?: 1f).coerceIn(0f, 1f)
            if (alpha <= 0.01f) return null

            @Suppress("UNCHECKED_CAST")
            val raw = blipsMethod?.invoke(data) as? List<Any> ?: return null
            if (raw.isEmpty()) return null

            val world = MinecraftClient.getInstance()?.world
            val out = ArrayList<Blip>(raw.size)
            for (b in raw) {
                val icon = iconM?.invoke(b) ?: continue
                val id = iconIdM?.invoke(icon) as? String ?: continue

                // Tropimon prefers the live entity when the client can see it, because the
                // packet's position is a snapshot and a walking Pokemon would stutter.
                val entity = try {
                    (entityIdM?.invoke(b) as? Int)?.let { world?.getEntityById(it) }
                } catch (_: Exception) { null }

                if (entity != null) {
                    out.add(Blip(entity.x, entity.y, entity.z, id))
                } else {
                    val x = (xM?.invoke(b) as? Int)?.toDouble() ?: continue
                    val y = (yM?.invoke(b) as? Int)?.toDouble() ?: continue
                    val z = (zM?.invoke(b) as? Int)?.toDouble() ?: continue
                    out.add(Blip(x + 0.5, y, z + 0.5, id))
                }
            }
            if (out.isEmpty()) null else out to alpha
        } catch (e: Exception) {
            available = false
            HunterBoard.LOGGER.warn("[LocatorBar] radar shape changed, bar disabled: ${e.message}")
            null
        }
    }

    /**
     * The blips as of the last tick, already sorted far-to-near.
     *
     * Reading them is reflective and allocates a list, and they only change when the
     * server sends a radar packet — Tropimon's own fade is driven from the client tick
     * too, so there is nothing to gain from doing either per frame. The player's facing
     * is read per frame, which is what actually needs to be smooth.
     */
    private var cached: Pair<List<Blip>, Float>? = null

    fun register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register {
            cached = if (ModConfig.locatorBarEnabled) {
                resolve()
                readBlips()?.let { (list, fade) ->
                    val p = MinecraftClient.getInstance()?.player
                    val ordered = if (p == null) list else list.sortedByDescending { b ->
                        val dx = b.x - p.x; val dz = b.z - p.z
                        dx * dx + dz * dz
                    }
                    ordered to fade
                }
            } else null
        }

        HudRenderCallback.EVENT.register { context, _ ->
            try {
                if (ModConfig.locatorBarEnabled) render(context)
            } catch (_: Exception) {
                // Never let a HUD overlay break the rest of the frame.
            }
        }
    }

    private fun render(context: DrawContext) {
        val client = MinecraftClient.getInstance() ?: return
        val player = client.player ?: return
        if (client.options.hudHidden) return
        if (client.currentScreen != null && ModConfig.locatorBarHideInMenus) return

        // No radar contact normally means no bar, exactly as Tropimon's behaves. Keeping an
        // empty bar on screen is a preference, so it is offered rather than assumed.
        val read = cached
        if (read == null && !ModConfig.locatorBarShowEmpty) return
        val blips = read?.first ?: emptyList()
        val fade = read?.second ?: 1f

        // One uniform scale, so nothing is ever distorted. Length is handled in native
        // pixels instead — the bar is built wider out of the texture's own repeating
        // columns, rather than the whole image being pulled sideways.
        val scale = ModConfig.locatorBarScale.coerceIn(0.5f, 6f)
        val nativeW = (BAR_WIDTH * ModConfig.locatorBarLength.coerceIn(1f, 4f)).toInt()
            .coerceAtLeast(BAR_WIDTH)
        val originX = (client.window.scaledWidth - nativeW * scale) / 2f
        val originY = if (ModConfig.locatorBarAboveArmor) {
            // Vanilla stacks, from the hotbar up: XP bar, hearts (one row per 20 HP,
            // absorption included, rows squeezing together past two), then armour.
            val rows = kotlin.math.ceil((player.maxHealth + player.absorptionAmount) / 20.0).toInt().coerceAtLeast(1)
            val rowGap = (10 - (rows - 2)).coerceIn(3, 10)
            val armorTop = client.window.scaledHeight - 39 - (rows - 1) * rowGap - 10
            (armorTop - 2 - (ICON_TOP + ICON_SIZE) * scale).coerceAtLeast(0f)
        } else ModConfig.locatorBarY.coerceAtLeast(0).toFloat()

        val matrices = context.matrices
        matrices.push()
        matrices.translate(originX, originY, 0f)
        matrices.scale(scale, scale, 1f)
        try {
            drawNative(context, player.yaw, blips, fade, nativeW)
        } finally {
            matrices.pop()
        }
    }

    /**
     * Draws the bar in Tropimon's own coordinates, with the background's top-left at 0,0.
     *
     * Working in native units is what makes the enlargement faithful: the caller has
     * already scaled the matrix, so every offset here is the number their renderer uses.
     */
    private fun drawNative(
        context: DrawContext, yaw: Float, blips: List<Blip>, fade: Float, nativeW: Int,
    ) {
        val player = MinecraftClient.getInstance()?.player ?: return

        context.setShaderColor(1f, 1f, 1f, fade)
        drawBackground(context, nativeW)

        val halfSpan = nativeW / 2 - EDGE_MARGIN

        // Already ordered far-to-near when it was read, so a close blip is never hidden.
        for (b in blips) {
            val bearing = Math.toDegrees(Math.atan2(b.z - player.z, b.x - player.x)).toFloat() - 90f
            val delta = MathHelper.wrapDegrees(bearing - yaw)
            val offset = Math.round(MathHelper.clamp(delta / HALF_ANGLE, -1f, 1f) * halfSpan)
            val px = nativeW / 2 + offset

            val alpha = fade * if (Math.abs(delta) > HALF_ANGLE) OUT_OF_RANGE_ALPHA else 1f
            context.setShaderColor(1f, 1f, 1f, alpha)
            context.drawGuiTexture(iconOf(b.iconId), px - ICON_SIZE / 2, ICON_TOP, ICON_SIZE, ICON_SIZE)
        }

        context.setShaderColor(1f, 1f, 1f, 1f)
    }

    /**
     * Lays the background out at any width, from the texture's own columns.
     *
     * Caps and the centre sight are drawn once at their true size; the two uniform spans
     * are repeated to fill whatever is left. Every piece is drawn one-to-one, so the
     * animation keeps playing and nothing is squashed or smeared — which is what pulling
     * the whole 182-pixel image sideways used to do, icons included.
     */
    private fun drawBackground(context: DrawContext, nativeW: Int) {
        if (nativeW <= BAR_WIDTH) {
            context.drawGuiTexture(BACKGROUND, 0, 0, BAR_WIDTH, BAR_HEIGHT)
            return
        }

        val fixed = CAP * 2 + CENTRE_W
        val fill = nativeW - fixed
        // Split the way the texture does, so the sight stays centred.
        val leftW = fill * LEFT_SPAN_W / (LEFT_SPAN_W + RIGHT_SPAN_W)
        val rightW = fill - leftW

        slice(context, 0, CAP, 0)
        tile(context, LEFT_SPAN_U, LEFT_SPAN_W, CAP, leftW)
        slice(context, CENTRE_U, CENTRE_W, CAP + leftW)
        tile(context, RIGHT_SPAN_U, RIGHT_SPAN_W, CAP + leftW + CENTRE_W, rightW)
        slice(context, RIGHT_CAP_U, CAP, nativeW - CAP)
    }

    /** One piece of the texture, at its true size. */
    private fun slice(context: DrawContext, u: Int, w: Int, dx: Int) {
        context.drawGuiTexture(BACKGROUND, BAR_WIDTH, BAR_HEIGHT, u, 0, dx, 0, w, BAR_HEIGHT)
    }

    /** Repeats a uniform span until [width] is covered, trimming the last piece. */
    private fun tile(context: DrawContext, u: Int, spanW: Int, dx: Int, width: Int) {
        var done = 0
        while (done < width) {
            val w = minOf(spanW, width - done)
            slice(context, u, w, dx + done)
            done += w
        }
    }

    private val iconCache = HashMap<String, Identifier>()

    private fun iconOf(id: String): Identifier =
        iconCache.getOrPut(id) { Identifier.of(NAMESPACE, "contextbar/radar/icons/$id") }
}
