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
 * Keybindings for the Ore Heatmap mod.
 * O = Open GUI
 * P = Reset current overlay (full rescan)
 * I = Cycle overlays (including OFF)
 */
@EventBusSubscriber(modid = OreHeatmapMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class OreHeatmapKeyBindings {

    public static final String KEY_CATEGORY = "key.categories." + OreHeatmapMod.MODID;

    public static final String KEY_OPEN_GUI = "key." + OreHeatmapMod.MODID + ".open_gui";
    public static final String KEY_RESET = "key." + OreHeatmapMod.MODID + ".reset_cache";
    public static final String KEY_CYCLE = "key." + OreHeatmapMod.MODID + ".cycle_overlay";

    public static KeyMapping openGuiKey;
    public static KeyMapping resetKey;
    public static KeyMapping cycleKey;

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        openGuiKey = new KeyMapping(KEY_OPEN_GUI, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KEY_CATEGORY);
        resetKey = new KeyMapping(KEY_RESET, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, KEY_CATEGORY);
        cycleKey = new KeyMapping(KEY_CYCLE, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, KEY_CATEGORY);

        event.register(openGuiKey);
        event.register(resetKey);
        event.register(cycleKey);

        NeoForge.EVENT_BUS.register(ClientTickHandler.class);

        OreHeatmapMod.LOGGER.info("Ore Heatmap keybindings registered");
    }

    public static class ClientTickHandler {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (openGuiKey == null || resetKey == null || cycleKey == null) {
                return;
            }

            while (openGuiKey.consumeClick()) {
                OreHeatmapPlugin plugin = OreHeatmapPlugin.getInstance();
                if (plugin != null && plugin.getOverlayManager() != null) {
                    Minecraft.getInstance().setScreen(new HeatmapSlotsScreen(plugin.getOverlayManager()));
                }
            }

            while (resetKey.consumeClick()) {
                OreHeatmapPlugin plugin = OreHeatmapPlugin.getInstance();
                if (plugin != null && plugin.getOverlayManager() != null) {
                    plugin.getOverlayManager().resetCache();
                }
            }

            while (cycleKey.consumeClick()) {
                OreHeatmapPlugin plugin = OreHeatmapPlugin.getInstance();
                if (plugin != null && plugin.getOverlayManager() != null) {
                    plugin.getOverlayManager().cycleOverlay();
                }
            }
        }
    }
}
