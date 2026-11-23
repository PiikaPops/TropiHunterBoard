package com.hunterboard.devmixin;

import com.hunterboard.WikiAudit;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Development (WikiAudit): records the text drawn while a wiki page is read for the audit.
 * The two methods every other text-drawing call ends up in; idle otherwise.
 */
@Mixin(DrawContext.class)
public class DrawContextTextMixin {

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)I", at = @At("HEAD"))
    private void hunterboardAuditString(TextRenderer renderer, String text, int x, int y, int color, boolean shadow,
                                        CallbackInfoReturnable<Integer> cir) {
        if (WikiAudit.capturing) WikiAudit.capture(text);
    }

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)I", at = @At("HEAD"))
    private void hunterboardAuditOrdered(TextRenderer renderer, OrderedText text, int x, int y, int color, boolean shadow,
                                         CallbackInfoReturnable<Integer> cir) {
        if (WikiAudit.capturing) WikiAudit.capture(text);
    }
}
