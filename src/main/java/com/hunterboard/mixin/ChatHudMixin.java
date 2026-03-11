package com.hunterboard.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin — chat filtering is handled via Fabric ClientReceiveMessageEvents API.
 * Kept to satisfy mixins.json declaration.
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {
}
