package com.hunterboard.mixin;

import com.hunterboard.CaptureCard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets {@link CaptureCard} swap in its one-page capture card.
 *
 * {@code NotificationRenderers.of} picks the renderer for every card Tropimon shows, once,
 * when the card is queued. Its result is handed to {@link CaptureCard#wrap}, which returns
 * a wrapper for the capture card and the renderer unchanged for every other card.
 *
 * The handler takes only the callback: the method's argument and return type are Tropimon
 * classes, and naming them here would mean compiling against a proprietary jar. Mixin
 * accepts a handler that omits all the target's arguments, and the return value travels as
 * Object — the injected code casts it back on return, which the wrapper, a proxy of the
 * renderer interface, satisfies.
 */
@Pseudo
@Mixin(targets = "fr.erusel.tropimodclient.client.notification.render.NotificationRenderers", remap = false)
public class CaptureCardMixin {

    @Inject(
        method = "of(Lfr/tropimon/tropimoncore/data/notification/Notification;)Lfr/erusel/tropimodclient/client/notification/render/NotificationRenderer;",
        at = @At("RETURN"), cancellable = true, require = 0
    )
    private static void hunterboard$combinedCaptureCard(CallbackInfoReturnable<Object> cir) {
        try {
            Object original = cir.getReturnValue();
            Object wrapped = CaptureCard.wrap(original);
            if (wrapped != original) cir.setReturnValue(wrapped);
        } catch (Throwable ignored) {
            // Tropimon's own renderer stays in place.
        }
    }
}
