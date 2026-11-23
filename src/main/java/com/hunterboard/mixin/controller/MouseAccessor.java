package com.hunterboard.mixin.controller;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The mouse movement waiting to turn the player: where a controller's stick is added while riding (RideLook). */
@Mixin(Mouse.class)
public interface MouseAccessor {
    @Accessor("cursorDeltaX") double hunterboard$getCursorDeltaX();
    @Accessor("cursorDeltaX") void hunterboard$setCursorDeltaX(double value);
    @Accessor("cursorDeltaY") double hunterboard$getCursorDeltaY();
    @Accessor("cursorDeltaY") void hunterboard$setCursorDeltaY(double value);
}
