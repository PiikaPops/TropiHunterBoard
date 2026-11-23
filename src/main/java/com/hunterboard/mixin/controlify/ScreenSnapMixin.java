package com.hunterboard.mixin.controlify;

import com.hunterboard.controller.controlify.SnapPoints;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * The points Controlify's cursor jumps to on Tropimon's, Cobblemon's and HunterBoard's
 * screens: HunterBoard's own list (visible buttons only, list rows, the summary's chosen
 * few) instead of every widget. Applied after Controlify's mixin that adds the method.
 */
@Mixin(value = Screen.class, priority = 1500)
public abstract class ScreenSnapMixin {
    @Inject(method = "controlify$collectSnapPoints", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void hunterboard$snapPoints(Consumer<Object> consumer, CallbackInfo ci) {
        if (SnapPoints.collect((Screen) (Object) this, consumer)) ci.cancel();
    }
}
