package com.hunterboard.mixin.controller;

import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Where a list's rows really are, scrolling included, for the controller's cursor (ScreenTargets). */
@Mixin(EntryListWidget.class)
public interface EntryListAccessor {
    @Invoker("getRowTop") int hunterboard$rowTop(int index);
    @Accessor("itemHeight") int hunterboard$itemHeight();
}
