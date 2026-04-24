package com.hunterboard.mixin;

import com.hunterboard.RadarEnhancer;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional mixin on TropiModCore's PokeRadarScreen.
 * Intercepts render() to draw tooltips and mouseClicked() to open the wiki.
 * @Pseudo: does not fail if the target class is absent (TropiModCore not installed).
 */
@Pseudo
@Mixin(targets = "fr.erusel.tropimodclient.client.gui.pokeradar.PokeRadarScreen", remap = false)
public class PokeRadarScreenMixin {

    /**
     * Called before any rendering: load pool species and clear per-frame cell list.
     * method_25394 = Screen.render(DrawContext, int, int, float) in intermediary (MC 1.21.1)
     */
    @Inject(method = "method_25394", at = @At("HEAD"), require = 0)
    private void onRenderHead(Object ctx, int mx, int my, float delta, CallbackInfo ci) {
        RadarEnhancer.INSTANCE.onRadarRenderHead((Object) this);
    }

    /** Called after all rendering: draw hover highlight and tooltip. */
    @Inject(method = "method_25394", at = @At("TAIL"), require = 0)
    private void onRenderTail(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) {
        RadarEnhancer.INSTANCE.onRadarRenderTail(ctx, mx, my);
    }
}
