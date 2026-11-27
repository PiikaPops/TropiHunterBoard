package com.hunterboard.mixin.render;

import com.hunterboard.AprilPaintings;
import com.hunterboard.VideoPaintings;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Where Tropimon's paintings pick their picture: their block entity renderer asks this render
 * layer for {@code tropifurnitures:textures/paintings/<name>.webp}. On the 1st of April
 * {@link VideoPaintings} answers for the paintings that play a video, and {@link AprilPaintings}
 * for all the others; every other texture, and every other day, goes through untouched.
 *
 * Minecraft's method rather than Tropimon's renderer: it stays the same whatever Tropimon
 * renames, and only the painting textures' path matters.
 */
@Mixin(RenderLayer.class)
public abstract class AprilPaintingMixin {

    @ModifyVariable(method = "getEntityCutoutNoCull(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/render/RenderLayer;",
        at = @At("HEAD"), argsOnly = true, require = 1)
    private static Identifier hunterboard$aprilPicture(Identifier texture) {
        // the painting that plays a video first (it keeps its video that day), then the fish on all the others
        return AprilPaintings.texture(VideoPaintings.texture(texture));
    }
}
