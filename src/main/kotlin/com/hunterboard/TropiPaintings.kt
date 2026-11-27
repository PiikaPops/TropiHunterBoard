package com.hunterboard

import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.model.BakedModel
import net.minecraft.client.render.model.json.ModelTransformationMode
import net.minecraft.client.render.model.json.Transformation
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.item.BlockItem
import net.minecraft.item.ItemStack
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import org.joml.Vector3f
import java.util.WeakHashMap

/**
 * Tropimon's paintings (Tropifurnitures) held in hand: the illustration turned toward the
 * people in front of the holder, and toward the holder in the first person.
 *
 * Their item is drawn by `PaintingItemRenderer`, under the `builtin/entity` model, which
 * places the painting with its block model's display settings. Those leave it half a block
 * off in a hand seen from outside — inside the holder's leg, so nobody saw it — and, once
 * put back, hold it with the back of the canvas outward. [PaintingItemMixin]
 * [com.hunterboard.mixin.PaintingItemMixin] places it here instead, for the hands only (seen
 * from outside and in the first person), then lets the renderer draw it untransformed: the
 * frame and the illustration together, as Tropimon draws them.
 *
 * The painting is centred on its own bounds, read from its block model, so the 2×2, 3×2 and
 * 3×3 ones all sit the same way, scaled to the same largest side.
 */
object TropiPaintings {

    /**
     * Where a painting goes in a hand: a turn (degrees, as in a model's "display"), a shift
     * (in pixels, sixteenths of a block) and the length of its largest side, in blocks.
     */
    class Display(val rotation: Vector3f, val shift: Vector3f, val size: Float) {
        internal val transformation = Transformation(rotation, Vector3f(shift).div(16f), Vector3f(1f, 1f, 1f))
    }

    /** Off only for the development check's "before" pictures: Tropimon's own placement. */
    @JvmField var enabled = true

    /**
     * Held at the side by its inner edge, the hand on the frame and the illustration facing
     * where the holder looks: attached to the hand from every side, the picture in full view.
     */
    @JvmField var thirdPerson = Display(Vector3f(75f, 0f, 0f), Vector3f(4f, 0.5f, 0f), 0.5f)

    /**
     * Held up in front of the hand, almost square to the holder and slightly tilted, large:
     * the frame's top corners in view, the bottom cut by the screen, as a large board held
     * in one hand is.
     */
    @JvmField var firstPerson = Display(Vector3f(3f, 178f, 12f), Vector3f(-0.25f, 3.5f, 0f), 0.6f)

    /** A hand, seen from outside or in the first person: the views placed here. */
    @JvmStatic
    fun handles(mode: ModelTransformationMode): Boolean = enabled && (
        mode == ModelTransformationMode.THIRD_PERSON_RIGHT_HAND || mode == ModelTransformationMode.THIRD_PERSON_LEFT_HAND ||
            mode == ModelTransformationMode.FIRST_PERSON_RIGHT_HAND || mode == ModelTransformationMode.FIRST_PERSON_LEFT_HAND)

    /** The painting's bounds in its block model, in blocks: min x, y, z, then max x, y, z. */
    private val bounds = WeakHashMap<BakedModel, FloatArray>()

    private fun boundsOf(state: BlockState): FloatArray? {
        val model = MinecraftClient.getInstance().blockRenderManager.getModel(state)
        return bounds.getOrPut(model) {
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            val random = Random.create(42L)
            for (side in Direction.entries.toList() + listOf(null)) for (quad in model.getQuads(state, side, random)) {
                val data = quad.vertexData
                val stride = data.size / 4
                for (v in 0 until 4) for (axis in 0 until 3) {
                    val p = java.lang.Float.intBitsToFloat(data[v * stride + axis])
                    b[axis] = minOf(b[axis], p)
                    b[axis + 3] = maxOf(b[axis + 3], p)
                }
            }
            b
        }.takeIf { it[0] <= it[3] }
    }

    /**
     * Places the painting in [stack] for this hand, in the frame Minecraft hands the
     * renderer (already moved half a block back). False when it cannot be measured: the
     * renderer then does as it always did.
     */
    @JvmStatic
    fun place(stack: ItemStack, mode: ModelTransformationMode, matrices: MatrixStack): Boolean {
        val block = (stack.item as? BlockItem)?.block ?: return false
        val b = boundsOf(block.defaultState) ?: return false
        val mine = mode == ModelTransformationMode.FIRST_PERSON_RIGHT_HAND || mode == ModelTransformationMode.FIRST_PERSON_LEFT_HAND
        val left = mode == ModelTransformationMode.THIRD_PERSON_LEFT_HAND || mode == ModelTransformationMode.FIRST_PERSON_LEFT_HAND
        val display = if (mine) firstPerson else thirdPerson
        val largest = maxOf(b[3] - b[0], b[4] - b[1]).coerceAtLeast(0.1f)
        val scale = display.size / largest
        matrices.translate(0.5f, 0.5f, 0.5f)          // back on the point a model is placed from
        display.transformation.apply(left, matrices)   // as a model's display would, mirrored for the left hand
        matrices.scale(scale, scale, scale)
        // the painting's centre on that point (the renderer then moves it half a block, as for any model)
        matrices.translate(0.5f - (b[0] + b[3]) / 2f, 0.5f - (b[1] + b[4]) / 2f, 0.5f - (b[2] + b[5]) / 2f)
        return true
    }
}
