package com.hunterboard.controller;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * hunterboard.controlify.mixins.json reaches into Controlify itself: none of it is even
 * looked at unless Controlify is installed. The shulker box preview also needs
 * ShulkerBoxTooltip, the mod it changes.
 */
public final class ControlifyMixinPlugin implements IMixinConfigPlugin {
    private boolean present;
    private boolean shulkerTooltips;

    @Override public void onLoad(String mixinPackage) {
        present = FabricLoader.getInstance().isModLoaded("controlify");
        shulkerTooltips = FabricLoader.getInstance().isModLoaded("shulkerboxtooltip");
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".ShulkerPreviewMixin")) return present && shulkerTooltips;
        return present;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
