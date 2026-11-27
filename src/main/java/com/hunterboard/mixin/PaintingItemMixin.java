package com.hunterboard.mixin;

import com.hunterboard.TropiPaintings;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds Tropimon's paintings in hand with the illustration showing (see {@link TropiPaintings}).
 *
 * For a hand, the painting is placed here, then the renderer is called again with no view
 * ({@code NONE}): it draws the frame and the illustration as usual, without moving them. Any
 * other view (inventory, ground, item frame) is left to it.
 *
 * The method is named without its descriptor, and the class is not remapped: Tropifurnitures'
 * renderer has a single {@code render}, Fabric's {@code DynamicItemRenderer} method, whose
 * name never changes; its arguments are Minecraft's, which the handler below names and which
 * Loom writes in production names like the rest of this class. So the same mixin finds it in
 * the development run and in the game.
 */
@Pseudo
@Mixin(targets = "fr.tropimon.tropifurnitures.item.client.renderer.PaintingItemRenderer", remap = false)
public abstract class PaintingItemMixin {

    /** Drawing the placed painting: the inner call goes through untouched. */
    @Unique private boolean hunterboard$placing;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void hunterboard$inHand(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices,
                                    VertexConsumerProvider vertexConsumers, int light, int overlay, CallbackInfo ci) {
        if (hunterboard$placing || !TropiPaintings.handles(mode)) return;
        matrices.push();
        try {
            if (!TropiPaintings.place(stack, mode, matrices)) return;
            ci.cancel();
            hunterboard$placing = true;
            ((BuiltinItemRendererRegistry.DynamicItemRenderer) (Object) this)
                .render(stack, ModelTransformationMode.NONE, matrices, vertexConsumers, light, overlay);
        } finally {
            hunterboard$placing = false;
            matrices.pop();
        }
    }
}
