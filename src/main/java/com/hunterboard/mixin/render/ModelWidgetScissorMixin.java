package com.hunterboard.mixin.render;

import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cobblemon's {@link ModelWidget} clips its model to its own box with
 * {@code DrawContext.enableScissor}, which ignores the matrix stack: the four numbers become
 * a screen rectangle as they are. HunterBoard's screens draw through a scale matrix when the
 * Minecraft GUI scale is above HunterBoard's interface-scale cap (GUI scale 3, 4 or Auto,
 * cap 2): the model was drawn where the matrix puts it, its clip somewhere else, and the
 * model vanished (the radar's empty cells, the wiki's empty portraits). The box is now put
 * through the current matrix first, so the clip lands on the model. With no scale on the
 * stack (every Cobblemon screen, GUI scale at or below the cap) nothing changes.
 *
 * The class is Cobblemon's (remap = false: renderPKM keeps its name), the call it redirects is
 * Minecraft's (remap = true on the target: Loom writes its production name in the refmap).
 */
@Mixin(value = ModelWidget.class, remap = false)
public abstract class ModelWidgetScissorMixin {

    @Redirect(
        method = "renderPKM",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;enableScissor(IIII)V", remap = true),
        require = 1
    )
    private void hunterboard$clipWhereDrawn(DrawContext context, int x1, int y1, int x2, int y2) {
        Matrix4f m = context.getMatrices().peek().getPositionMatrix();
        Vector3f a = m.transformPosition(x1, y1, 0f, new Vector3f());
        Vector3f b = m.transformPosition(x2, y2, 0f, new Vector3f());
        context.enableScissor(
            (int) Math.floor(Math.min(a.x, b.x)), (int) Math.floor(Math.min(a.y, b.y)),
            (int) Math.ceil(Math.max(a.x, b.x)), (int) Math.ceil(Math.max(a.y, b.y)));
    }
}
