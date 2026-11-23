package com.hunterboard.mixin.battle;

import com.cobblemon.mod.common.client.net.battle.BattleHealthChangeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import com.hunterboard.replay.ReplayRecorder;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds onHealth to {@link ReplayRecorder}.
 *
 * The full descriptor is spelled out because these Kotlin handlers each carry a synthetic
 * bridge overload taking NetworkPacket, and naming the method alone would be ambiguous.
 * MinecraftClient is written here under its Yarn name but appears in the descriptor as
 * class_310, since remap = false leaves the string exactly as given.
 */
@Mixin(value = BattleHealthChangeHandler.class, remap = false)
public class BattleHealthChangeMixin {

    @Inject(
        method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleHealthChangePacket;Lnet/minecraft/class_310;)V",
        at = @At("HEAD")
    )
    private void hunterboard$record(BattleHealthChangePacket packet, MinecraftClient client, CallbackInfo ci) {
        // A fault while recording must never take the battle screen with it: the replay is
        // a convenience, the battle is the game.
        try {
            ReplayRecorder.INSTANCE.onHealth(packet);
        } catch (Throwable ignored) {
        }
    }
}
