package com.hunterboard.mixin;

import com.hunterboard.TropiNotifications;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taps the server's notification channel.
 *
 * Tropimon's announcements — hunt completions, miracles, item clears — travel on
 * `tropimon:show_notification_packet` instead of the chat, which is why three detectors
 * went quiet at once. The channel itself cannot be registered twice: Fabric allows one
 * receiver per payload id and TropiModClient claims it first.
 *
 * Two details decide where this has to hook, and both were learned the hard way:
 *
 *  - **Which method.** There are two `onCustomPayload` overloads. Fabric's networking API
 *    injects into `ClientCommonNetworkHandler.onCustomPayload(CustomPayloadS2CPacket)` —
 *    the one taking the *packet* — and **cancels** it whenever a receiver is registered for
 *    that id. The other overload, taking the bare `CustomPayload`, is only reached when
 *    Fabric did *not* claim the payload, so hooking it never sees a Tropimon notification.
 *
 *  - **Which priority.** Mixin runs HEAD callbacks in priority order and stops as soon as
 *    one cancels, so a default-priority injection here would still sit behind Fabric's.
 *    500 puts this first; Fabric then cancels as usual and its own routing is untouched.
 *
 * Only the channel name is borrowed from Tropimon; the payload is read reflectively,
 * because the classes ship inside a proprietary mod's nested jars.
 */
@Mixin(value = ClientCommonNetworkHandler.class, priority = 500)
public class CustomPayloadMixin {

    private static final String NOTIFICATION_CHANNEL = "tropimon:show_notification_packet";

    @Inject(method = "onCustomPayload(Lnet/minecraft/network/packet/s2c/common/CustomPayloadS2CPacket;)V",
            at = @At("HEAD"))
    private void hunterboardOnCustomPayload(CustomPayloadS2CPacket packet, CallbackInfo ci) {
        if (packet == null) return;

        CustomPayload payload;
        String channel;
        try {
            payload = packet.payload();
            if (payload == null) return;
            channel = payload.getId().id().toString();
        } catch (Throwable ignored) {
            return;
        }

        TropiNotifications.noteChannel(channel);
        if (!NOTIFICATION_CHANNEL.equals(channel)) return;

        // getNotification() on Tropimon's ShowNotificationPayload
        Object notification = null;
        try {
            notification = payload.getClass().getMethod("getNotification").invoke(payload);
        } catch (Throwable t) {
            TropiNotifications.noteUnreadablePayload(payload.getClass().getName(), t);
        }
        if (notification != null) {
            TropiNotifications.dispatch(notification);
        }
    }
}
