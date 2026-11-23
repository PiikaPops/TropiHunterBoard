package com.hunterboard.mixin.pc;

import com.hunterboard.pc.HunterPc;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the player still while HunterBoard's PC is open. Mods such as InvMove let players
 * walk with a screen open; walking away from the PC block would unlink it in the middle of
 * a batch of moves or releases.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class PcMovementMixin {

    @Inject(
        method = "tickMovement",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/input/Input;tick(ZF)V", shift = At.Shift.AFTER)
    )
    private void hunterboard$holdStill(CallbackInfo ci) {
        if (!HunterPc.holdsPlayer()) return;
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        Input in = self.input;
        in.movementForward = 0.0F;
        in.movementSideways = 0.0F;
        in.pressingForward = in.pressingBack = in.pressingLeft = in.pressingRight = false;
        in.jumping = false;
        in.sneaking = false;
        self.setSprinting(false);
    }
}
