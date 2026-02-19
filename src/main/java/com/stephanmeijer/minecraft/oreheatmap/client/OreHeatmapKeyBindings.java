package com.stephanmeijer.minecraft.oreheatmap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import com.stephanmeijer.minecraft.oreheatmap.journeymap.OreHeatmapPlugin;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Handles keybinding registration and input for the Ore Heatmap mod.
 */
@EventBusSubscriber(modid = OreHeatmapMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class OreHeatmapKeyBindings {

    public static final String KEY_CATEGORY = "key.categories." + OreHeatmapMod.MODID;
    public static final String KEY_TOGGLE_OVERLAY = "key." + OreHeatmapMod.MODID + ".toggle_overlay";
    public static final String KEY_RESET_CACHE = "key." + OreHeatmapMod.MODID + ".reset_cache";
    public static final String KEY_CYCLE_OVERLAY = "key." + OreHeatmapMod.MODID + ".cycle_overlay";

    public static KeyMapping toggleOverlayKey;
    public static KeyMapping resetCacheKey;
    public static KeyMapping cycleOverlayKey;

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        toggleOverlayKey = new KeyMapping(
                KEY_TOGGLE_OVERLAY,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KEY_CATEGORY
        );
        event.register(toggleOverlayKey);

        resetCacheKey = new KeyMapping(
                KEY_RESET_CACHE,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KEY_CATEGORY
        );
        event.register(resetCacheKey);

        cycleOverlayKey = new KeyMapping(
                KEY_CYCLE_OVERLAY,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                KEY_CATEGORY
        );
        event.register(cycleOverlayKey);

        // Register the tick handler for key press detection
        NeoForge.EVENT_BUS.register(ClientTickHandler.class);

        OreHeatmapMod.LOGGER.info("Ore Heatmap keybindings registered");
    }

    /**
     * Handles client tick events to detect key presses.
     */
    public static class ClientTickHandler {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (toggleOverlayKey == null || resetCacheKey == null || cycleOverlayKey == null) {
                return;
            }

            while (toggleOverlayKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.setScreen(new HeatmapSlotsScreen());
                }
            }

            while (resetCacheKey.consumeClick()) {
                OreHeatmapPlugin plugin = OreHeatmapPlugin.getInstance();
                if (plugin != null && plugin.getOverlayManager() != null) {
                    plugin.getOverlayManager().resetCache();
                }
            }
            while (cycleOverlayKey.consumeClick()) {
                OreHeatmapPlugin plugin = OreHeatmapPlugin.getInstance();
                if (plugin != null && plugin.getOverlayManager() != null) {
                    plugin.getOverlayManager().cycleOverlay();
                }
            }
        }
    }
}
