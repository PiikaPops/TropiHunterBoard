package com.hunterboard.mixin.controlify;

import com.hunterboard.controller.controlify.ShulkerPreview;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;

/**
 * ShulkerBoxTooltip (in Tropimon's modpack) shows what a shulker box holds while Shift is
 * held; a controller has no Shift. With the controller, the preview opens as soon as the
 * cursor is on the box, and the hints naming keyboard keys (Alt, Ctrl) are left out.
 * Applied only with both Controlify and ShulkerBoxTooltip installed (ControlifyMixinPlugin).
 */
@Pseudo
@Mixin(targets = "com.misterpemodder.shulkerboxtooltip.ShulkerBoxTooltipClient", remap = false)
public abstract class ShulkerPreviewMixin {
    @Inject(method = "isPreviewKeyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private static void hunterboard$controllerPreview(CallbackInfoReturnable<Boolean> cir) {
        if (ShulkerPreview.open()) cir.setReturnValue(true);
    }

    @Inject(method = "getTooltipHints", at = @At("HEAD"), cancellable = true, require = 0)
    private static void hunterboard$noKeyboardHints(CallbackInfoReturnable<List<?>> cir) {
        if (ShulkerPreview.hideKeyHints()) cir.setReturnValue(Collections.emptyList());
    }
}
