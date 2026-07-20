package com.hunterboard.mixin;

import com.hunterboard.RadarEnhancer;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional mixin on TropiModCore's PokeRadarEntry (one row of 6 Pokémon).
 * Captures the exact rendered position (x, y, entryWidth, entryHeight) for each row,
 * allowing RadarEnhancer to compute per-cell hit boxes without guessing layout constants.
 * @Pseudo: does not fail if the target class is absent (TropiModCore not installed).
 */
@Pseudo
@Mixin(targets = "fr.erusel.tropimodclient.client.gui.pokeradar.widgets.PokeRadarEntry", remap = false)
public class PokeRadarEntryMixin {

    /**
     * Injected at the start of each row's render call.
     * Parameters match EntryListWidget.Entry.render():
     *   index        – row index in the list (0 = first visible row)
     *   y            – top y of this row in screen coordinates (already accounts for scroll)
     *   x            – left x of this row (= list content left)
     *   entryWidth   – full width of this row
     *   entryHeight  – row height (= itemHeight, typically 27)
     */
    /**
     * method_25343 = EntryListWidget.Entry.render(DrawContext,int,int,int,int,int,int,int,boolean,float)
     * Confirmed from PokeRadarEntry bytecode: method_25343(class_332,IIIIIIIZF)V
     */
    @Inject(method = "method_25343", at = @At("HEAD"), require = 0)
    private void onRender(DrawContext ctx,
                          int index, int y, int x,
                          int entryWidth, int entryHeight,
                          int mouseX, int mouseY,
                          boolean hovered, float tickDelta,
                          CallbackInfo ci) {
        RadarEnhancer.INSTANCE.recordRow(index, x, y, entryWidth, entryHeight, (Object) this);
    }
}
