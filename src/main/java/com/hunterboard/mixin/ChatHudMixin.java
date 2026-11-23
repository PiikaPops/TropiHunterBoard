package com.hunterboard.mixin;

import com.hunterboard.CaptureFilters;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every line the chat shows passes here, players' messages and the server's alike. A
 * capture filter code in one gets an "[Import]" link after it (the rest of chat
 * filtering goes through Fabric's ClientReceiveMessageEvents).
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {

    @ModifyVariable(
        method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        at = @At("HEAD"), argsOnly = true)
    private Text hunterboardFilterCode(Text message) {
        return CaptureFilters.withImportLink(message);
    }
}
