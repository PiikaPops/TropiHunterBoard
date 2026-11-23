package com.hunterboard

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * A screen laid out in its own pixels rather than Minecraft's.
 *
 * Every screen here keeps using `width`, `height` and plain coordinates as before; what
 * changes is that those are now *virtual* pixels, and the whole screen is drawn through a
 * matrix scaled by [UiScale.factor]. The layout is unaware of it, which is the point —
 * fourteen screens and some ten thousand lines of coordinate arithmetic did not have to
 * be touched.
 *
 * Two things do not go through the matrix and are handled here:
 *
 *  - **Sizes.** `Screen.init(client, width, height)` is final. It assigns the real `width`
 *    and `height`, then calls `init()` the first time a screen is shown, and
 *    `initTabNavigation()` → `clearAndInit()` → `init()` every later time (back from a
 *    sub-screen, window resize). Both paths end in `init()`, which every screen here
 *    begins with `super.init()`: the virtual dimensions are substituted there, before any
 *    layout runs. They are worked out from the window each time, never from `width`, so a
 *    second pass cannot shrink them twice. (Doing it in `clearAndInit()` alone missed the
 *    first showing: a screen opened at Minecraft's scale and switched to the capped one
 *    when it was come back to.)
 *  - **Mouse input.** Coordinates arrive in real GUI pixels and are converted before the
 *    screen sees them, so hit-tests match what was drawn. Subclasses override the
 *    `*Scaled` variants; the originals are final so a conversion cannot be forgotten.
 *
 * Scissor rectangles are the third case, and cannot be handled transparently:
 * `DrawContext.enableScissor` builds its `ScreenRect` from the raw integers without
 * consulting the matrix stack. Use [scissor] instead of calling it directly.
 */
abstract class ScaledScreen(title: Text) : Screen(title) {

    /**
     * Virtual pixels → real GUI pixels. 1.0 when the interface is not being capped. Public for
     * the controller support, whose cursor works in real GUI pixels.
     */
    var uiFactor = 1f
        private set

    override fun init() {
        uiFactor = UiScale.factor()
        val window = client?.window ?: return
        // round up: a panel anchored to the right edge should never land off-screen
        width = if (uiFactor < 1f) kotlin.math.ceil(window.scaledWidth / uiFactor).toInt() else window.scaledWidth
        height = if (uiFactor < 1f) kotlin.math.ceil(window.scaledHeight / uiFactor).toInt() else window.scaledHeight
        super.init()
    }

    // ── rendering ────────────────────────────────────────────────────────────────

    final override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        if (uiFactor >= 1f) {
            renderScaled(context, mouseX, mouseY, delta)
            return
        }
        context.matrices.push()
        context.matrices.scale(uiFactor, uiFactor, 1f)
        renderScaled(context, toVirtual(mouseX), toVirtual(mouseY), delta)
        context.matrices.pop()
    }

    protected abstract fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float)

    /** What `super.render` used to do: draw the vanilla widgets (text fields, buttons). */
    protected fun renderWidgets(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    }

    /**
     * Clip to a rectangle given in virtual pixels.
     *
     * `DrawContext.enableScissor` ignores the matrix stack — its bytecode passes the four
     * integers straight into a `ScreenRect` — so the rectangle is converted back to real
     * GUI pixels here. Pair it with `context.disableScissor()` as usual.
     */
    protected fun scissor(context: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int) {
        if (uiFactor >= 1f) {
            context.enableScissor(x1, y1, x2, y2)
        } else {
            context.enableScissor(
                kotlin.math.floor(x1 * uiFactor).toInt(),
                kotlin.math.floor(y1 * uiFactor).toInt(),
                kotlin.math.ceil(x2 * uiFactor).toInt(),
                kotlin.math.ceil(y2 * uiFactor).toInt()
            )
        }
    }

    // ── mouse ────────────────────────────────────────────────────────────────────

    private fun toVirtual(v: Int): Int = if (uiFactor >= 1f) v else (v / uiFactor).toInt()
    private fun toVirtual(v: Double): Double = if (uiFactor >= 1f) v else v / uiFactor

    final override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean =
        mouseClickedScaled(toVirtual(mouseX), toVirtual(mouseY), button)

    final override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean =
        mouseReleasedScaled(toVirtual(mouseX), toVirtual(mouseY), button)

    final override fun mouseDragged(
        mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double
    ): Boolean = mouseDraggedScaled(
        toVirtual(mouseX), toVirtual(mouseY), button, toVirtual(deltaX), toVirtual(deltaY)
    )

    final override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean = mouseScrolledScaled(
        // the wheel amount is a number of notches, not a distance: it is not scaled
        toVirtual(mouseX), toVirtual(mouseY), horizontalAmount, verticalAmount
    )

    protected open fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean =
        super.mouseClicked(mouseX, mouseY, button)

    protected open fun mouseReleasedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean =
        super.mouseReleased(mouseX, mouseY, button)

    protected open fun mouseDraggedScaled(
        mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double
    ): Boolean = super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)

    protected open fun mouseScrolledScaled(
        mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double
    ): Boolean = super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
}
