package com.hunterboard.mixin;

import com.hunterboard.ModConfig;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses Tropimon's narrow radar strip while HunterBoard draws the wide one.
 *
 * Both bars show the same blips, so leaving the original in place means reading the same
 * information twice — once above the hotbar and once at the top of the screen. Cancelling
 * the draw is enough: the manager keeps ticking, keeps its fade, and keeps the blip list
 * that {@link com.hunterboard.LocatorBar} reads, so nothing about the radar itself changes.
 *
 * @Pseudo because the target belongs to a server-side mod that is simply absent elsewhere.
 * DrawContext is a vanilla type, so naming it here is safe; only the optional mod's own
 * types would have to be kept out of this signature.
 */
@Pseudo
@Mixin(targets = "fr.erusel.tropimodclient.client.hud.contextbar.ContextBarManager", remap = false)
public class ContextBarMixin {

    @Inject(method = "renderBar(Lnet/minecraft/class_332;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void hunterboard$hideNarrowBar(DrawContext context, CallbackInfoReturnable<Boolean> cir) {
        if (hidesOriginal()) {
            // false = nothing was drawn, so Tropimon lets the vanilla XP bar render instead.
            cir.setReturnValue(false);
        }
    }

    /**
     * Tropimon hides the player's level number whenever the radar is showing — a separate
     * check from {@code renderBar}, made on {@code isDisplayed()} by its InGameHud mixin.
     * With the narrow bar suppressed above, the XP bar came back but its level stayed hidden
     * for as long as the radar was active. This keeps the two answers consistent.
     *
     * Safe to answer "not displayed": that mixin is the method's only caller outside the
     * manager, and {@link com.hunterboard.LocatorBar} reads the {@code displayed} field
     * itself, so the wide bar still sees the radar.
     */
    @Inject(method = "isDisplayed()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void hunterboard$notDisplayedWhenHidden(CallbackInfoReturnable<Boolean> cir) {
        if (hidesOriginal()) {
            cir.setReturnValue(false);
        }
    }

    private static boolean hidesOriginal() {
        return ModConfig.INSTANCE.getLocatorBarEnabled() && ModConfig.INSTANCE.getLocatorBarHideOriginal();
    }
}
