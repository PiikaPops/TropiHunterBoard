package com.hunterboard.mixin.controller;

import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelGUI;
import com.hunterboard.controller.WheelAim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Draws Cobblemon's interaction wheel as if the mouse were where the controller's stick
 * points (see WheelAim), so its own highlight and tooltip follow the stick. Without an aim
 * set, the real mouse position goes through untouched.
 */
@Mixin(InteractWheelGUI.class)
public abstract class InteractWheelAimMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int hunterboard$aimX(int mouseX) {
        return WheelAim.mouseX(this, mouseX);
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int hunterboard$aimY(int mouseY) {
        return WheelAim.mouseY(this, mouseY);
    }
}
