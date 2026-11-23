package com.hunterboard.mixin.render;

import com.cobblemon.mod.common.client.render.models.blockbench.PosableModel;
import com.cobblemon.mod.common.client.render.models.blockbench.repository.RenderContext;
import com.hunterboard.ChristmasHats;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Christmas hats while the model is still posed — see {@link ChristmasHats}.
 *
 * TAIL of {@code PosableModel.render}, because the caller resets every bone to its rest
 * pose right after this returns. {@code bufferProvider} is the layer buffer Cobblemon
 * stores through {@code setLayerContext}; only world renderers set it, which is what keeps
 * hats off GUI portraits.
 *
 * As in the battle mixins, remap = false: the descriptor is spelled with intermediary names
 * (class_4587 MatrixStack, class_4588 VertexConsumer) and the field is matched by its own
 * Kotlin name, while the Java types below are remapped by Loom like any other reference.
 */
@Mixin(value = PosableModel.class, remap = false)
public abstract class PosableModelHatMixin {

    @Shadow
    private VertexConsumerProvider bufferProvider;

    @Inject(
        method = "render(Lcom/cobblemon/mod/common/client/render/models/blockbench/repository/RenderContext;Lnet/minecraft/class_4587;Lnet/minecraft/class_4588;III)V",
        at = @At("TAIL")
    )
    private void hunterboard$christmasHats(RenderContext context, MatrixStack matrices, VertexConsumer buffer,
                                           int light, int overlay, int color, CallbackInfo ci) {
        // A hat must never cost a Pokémon its render.
        try {
            ChristmasHats.render((PosableModel) (Object) this, context, matrices, light, overlay, color, bufferProvider);
        } catch (Throwable ignored) {
        }
    }
}
