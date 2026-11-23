package com.hunterboard.mixin.pc;

import com.cobblemon.mod.common.client.net.storage.pc.ClosePCHandler;
import com.cobblemon.mod.common.net.messages.client.storage.pc.ClosePCPacket;
import com.hunterboard.pc.HunterPc;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cobblemon's close packet (the player walked away from the PC, the server shut it) only
 * closes Cobblemon's own PC screen. HunterBoard's PC must close as well: once unlinked, the
 * server refuses every move or release it would still send.
 *
 * Full descriptor because the Kotlin handler also has a bridge overload; MinecraftClient is
 * spelt class_310 since remap = false keeps the string as written.
 */
@Mixin(value = ClosePCHandler.class, remap = false)
public class ClosePcMixin {

    @Inject(
        method = "handle(Lcom/cobblemon/mod/common/net/messages/client/storage/pc/ClosePCPacket;Lnet/minecraft/class_310;)V",
        at = @At("HEAD")
    )
    private void hunterboard$closePc(ClosePCPacket packet, MinecraftClient client, CallbackInfo ci) {
        try {
            HunterPc.onServerClose(packet.getStoreID());
        } catch (Throwable ignored) {
        }
    }
}
