package com.hunterboard.mixin.controlify;

import com.hunterboard.controller.RideLook;
import dev.isxander.controlify.ingame.InGameInputHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Controlify turns the camera by calling the player's turn directly, past the mouse code
 * where Cobblemon steers a ridden Pokémon (pitch, roll, free look). On a Cobblemon mount,
 * the stick's turn goes through the mouse instead (RideLook), so it steers like the mouse.
 */
@Mixin(InGameInputHandler.class)
public abstract class ControlifyLookMixin {
    @Redirect(method = "processPlayerLook", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void hunterboard$rideLook(ClientPlayerEntity player, double dx, double dy) {
        if (!RideLook.toMouse(player, dx, dy)) player.changeLookDirection(dx, dy);
    }
}
