package com.hunterboard

import net.minecraft.client.gui.DrawContext
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Tropimon's capture card, with everything on one page.
 *
 * The server's card shows the ability and the IV quality first, then swaps to the six IVs
 * once its duration bar passes 30%: the two pages share the same 18-pixel band of a
 * 232×43 card, so they cannot both fit. This version makes the card 18 pixels taller and
 * stacks them, in their original order:
 *
 *      rows  0–17   title band          (from Tropimon's IV card)
 *      rows 17–35   ability · quality   (the first page's band, drawn by Tropimon's own code)
 *      rows 35–61   the six IV cells    (the IV card's lower half, moved down 18 pixels)
 *
 * Nothing is redrawn from scratch. Each band is Tropimon's own texture clipped to its rows,
 * and every line of text comes from Tropimon's own methods (`drawSummary`, `drawBody`,
 * `drawRow`), so fonts, colours, rarity marks and the hidden-ability colour stay exactly
 * theirs. The extra height is safe because the notification HUD draws nothing below the
 * current card.
 *
 * Hooked from `CaptureCardMixin`, which hands every renderer Tropimon resolves to [wrap].
 * Everything here is reflective: the classes ship in a proprietary mod HunterBoard does not
 * compile against. Any failure falls back to Tropimon's own card for the rest of the session.
 */
object CaptureCard {

    private const val CAPTURE_RENDERER = "tropimon:pokemon_capture"
    private const val RENDERER_IFACE = "fr.erusel.tropimodclient.client.notification.render.NotificationRenderer"
    private const val LAYOUT_CLASS = "fr.tropimon.tropimoncore.data.notification.Notification\$Layout"

    /** Height of the band slotted in between the title and the IV cells. */
    private const val EXTRA = 18
    private const val TITLE_BOTTOM = 17
    private const val BAND_BOTTOM = 35
    private const val TEXTURE_HEIGHT = 44

    /** Set on the first failure; the original card is drawn from then on. */
    private var broken = false

    // ── wrapping ────────────────────────────────────────────────────────────────────────

    /** The proxies handed out, so a renderer already wrapped is never wrapped again. */
    private val ours = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /**
     * Called with every renderer Tropimon picks for a card. Returns a wrapper for the capture
     * card and the renderer unchanged for everything else. The wrapper reads the option on
     * every frame, so switching it applies to a card already on screen.
     */
    @JvmStatic
    fun wrap(renderer: Any?): Any? {
        if (renderer == null || broken || renderer in ours) return renderer
        val id = try { renderer.javaClass.getMethod("id").invoke(renderer) as? String } catch (_: Throwable) { null }
        if (id != CAPTURE_RENDERER) return renderer
        return try {
            val iface = Class.forName(RENDERER_IFACE, true, renderer.javaClass.classLoader)
            Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
                when {
                    method.name == "render" && args?.size == 1 -> { render(renderer, args[0]); null }
                    method.name == "equals" && args?.size == 1 -> proxy === args[0]
                    method.name == "hashCode" && args.isNullOrEmpty() -> System.identityHashCode(proxy)
                    method.name == "toString" && args.isNullOrEmpty() -> "HunterBoard combined capture card"
                    // id() and anything else the interface declares: the original answers.
                    else -> method.invoke(renderer, *(args ?: emptyArray()))
                }
            }.also { ours += it }
        } catch (t: Throwable) {
            fail("could not wrap the capture card", t)
            renderer
        }
    }

    private fun render(original: Any, ctx: Any) {
        if (!broken && ModConfig.combinedCaptureCard) {
            try {
                if (renderCombined(original, ctx)) return
            } catch (t: Throwable) {
                // Reflection reports the real cause one level down.
                fail("combined capture card failed", (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t)
            }
        }
        original.javaClass.getMethod("render", ctx.javaClass).invoke(original, ctx)
    }

    private fun fail(what: String, t: Throwable) {
        if (!broken) HunterBoard.LOGGER.warn("[CaptureCard] $what, using Tropimon's card: ${t.javaClass.simpleName} ${t.message}")
        broken = true
    }

    // ── drawing ─────────────────────────────────────────────────────────────────────────

    private class Api(ctxClass: Class<*>, rendererClass: Class<*>) {
        val layout: Class<*> = Class.forName(LAYOUT_CLASS, true, ctxClass.classLoader)
        val stats: Any = layout.getField("STATS").get(null)
        val large: Any = layout.getField("LARGE").get(null)

        val drawContext: Method = ctxClass.getMethod("drawContext")
        val x: Method = ctxClass.getMethod("x")
        val y: Method = ctxClass.getMethod("y")
        val notification: Method = ctxClass.getMethod("notification")
        val effectiveLayout: Method = ctxClass.getMethod("effectiveLayout")
        val drawCard: Method = ctxClass.getMethod("drawCard", layout)
        val drawBody: Method = ctxClass.getMethod("drawBody", layout)
        val drawDurationBar: Method = ctxClass.getMethod("drawDurationBar")
        val drawPortrait: Method = ctxClass.getMethod("drawPortrait")
        val titleBandWidth: Method = ctxClass.getMethod("titleBandWidth")
        val drawActionHints: Method = ctxClass.getMethod("drawActionHints", Int::class.javaPrimitiveType)

        // The capture renderer's own page drawers, private but stable statics.
        val drawSummary: Method = rendererClass.getDeclaredMethod("drawSummary", ctxClass).apply { isAccessible = true }
        val drawRow: Method = rendererClass.getDeclaredMethod("drawRow", ctxClass, Int::class.javaPrimitiveType).apply { isAccessible = true }
    }

    private var api: Api? = null

    /** @return false when this card has no IVs to show, so the original should draw it. */
    private fun renderCombined(original: Any, ctx: Any): Boolean {
        val a = api ?: Api(ctx.javaClass, original.javaClass).also { api = it }

        // A capture card without stats has nothing to merge: leave it to Tropimon.
        val notification = a.notification.invoke(ctx)
        val stats = notification.javaClass.getMethod("stats").invoke(notification) as? List<*>
        if (stats.isNullOrEmpty()) return false

        val dc = a.drawContext.invoke(ctx) as DrawContext
        val top = a.y.invoke(ctx) as Int
        val screenW = dc.scaledWindowWidth
        // The first page's card, as Tropimon picks it, so the band looks as it always did.
        val first = a.effectiveLayout.invoke(ctx).let { if (it == a.stats) a.large else it }

        // Title band, from the IV card.
        dc.enableScissor(0, top, screenW, top + TITLE_BOTTOM)
        a.drawCard.invoke(ctx, a.stats)
        dc.disableScissor()

        // Ability and quality band, from the first page's card.
        dc.enableScissor(0, top + TITLE_BOTTOM, screenW, top + BAND_BOTTOM)
        a.drawCard.invoke(ctx, first)
        dc.disableScissor()

        // IV cells and the bottom edge, from the IV card, moved down by the band just added.
        // The scissor takes screen coordinates and ignores the matrix, so it is offset by hand.
        val matrices = dc.matrices
        matrices.push()
        matrices.translate(0f, EXTRA.toFloat(), 0f)
        dc.enableScissor(0, top + BAND_BOTTOM, screenW, top + EXTRA + TEXTURE_HEIGHT)
        a.drawCard.invoke(ctx, a.stats)
        dc.disableScissor()
        a.drawBody.invoke(ctx, a.stats)
        a.drawDurationBar.invoke(ctx)
        matrices.pop()

        // Same order as Tropimon's own render: portrait, body text, then the title row.
        a.drawPortrait.invoke(ctx)
        a.drawSummary.invoke(null, ctx)
        val bandWidth = a.titleBandWidth.invoke(ctx) as Int
        val hints = a.drawActionHints.invoke(ctx, 8 + bandWidth) as Int
        a.drawRow.invoke(null, ctx, bandWidth - hints - 2)
        return true
    }
}
