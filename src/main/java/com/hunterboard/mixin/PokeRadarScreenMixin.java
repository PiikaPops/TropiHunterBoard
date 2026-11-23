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
    /*
     * The first parameter must be declared as DrawContext, not Object.
     *
     * Mixin matches the handler against the target's descriptor literally, so an Object
     * here does not mean "any type": it means the handler does not describe
     * method_25394(DrawContext, int, int, float) and is rejected. The rejection is an
     * InvalidInjectionException raised while applying the class, which require = 0 does
     * not soften — it governs how many targets an injector must find, not whether its
     * signature parses. One bad descriptor therefore took the whole mixin down, including
     * onRenderTail below, leaving the radar tooltips silently dead.
     *
     * Naming a vanilla type is safe even though the target class is optional: DrawContext
     * is always on the classpath, and it is only @Pseudo targets' own types that must be
     * kept out of these signatures.
     */
    @Inject(method = "method_25394", at = @At("HEAD"), require = 0)
    private void onRenderHead(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) {
        RadarEnhancer.INSTANCE.onRadarRenderHead((Object) this);
    }

    /** Called after all rendering: draw hover highlight and tooltip. */
    @Inject(method = "method_25394", at = @At("TAIL"), require = 0)
    private void onRenderTail(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) {
        RadarEnhancer.INSTANCE.onRadarRenderTail(ctx, mx, my);
    }
}
