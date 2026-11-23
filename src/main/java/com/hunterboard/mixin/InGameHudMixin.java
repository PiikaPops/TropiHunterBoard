package com.hunterboard.mixin;

import com.hunterboard.RaidCard;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads what the server writes over the screen — the action bar and the titles — for the
 * raid countdown shown in the arena ("Le raid commence dans 16s"). It never reaches the
 * chat, and it is the only clock that matches the server's own to the second.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Inject(method = "setOverlayMessage(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"))
    private void hunterboardOverlay(Text message, boolean tinted, CallbackInfo ci) {
        RaidCard.onServerText(message);
    }

    @Inject(method = "setTitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void hunterboardTitle(Text title, CallbackInfo ci) {
        RaidCard.onServerText(title);
    }

    @Inject(method = "setSubtitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void hunterboardSubtitle(Text subtitle, CallbackInfo ci) {
        RaidCard.onServerText(subtitle);
    }
}
