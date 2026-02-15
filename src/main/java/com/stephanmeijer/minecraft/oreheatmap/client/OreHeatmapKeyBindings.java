package com.stephanmeijer.minecraft.oreheatmap.client;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapConfig;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import com.stephanmeijer.minecraft.oreheatmap.journeymap.OreHeatmapPlugin;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
    public static final String KEY_RELOAD_HEATMAP = "key." + OreHeatmapMod.MODID + ".reload_heatmap";
    public static final String KEY_CYCLE_PRESET = "key." + OreHeatmapMod.MODID + ".cycle_preset";

    public static KeyMapping toggleOverlayKey;
    public static KeyMapping reloadHeatmapKey;
    public static KeyMapping cyclePresetKey;

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

        reloadHeatmapKey = new KeyMapping(
                KEY_RELOAD_HEATMAP,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KEY_CATEGORY
        );
        event.register(reloadHeatmapKey);

        cyclePresetKey = new KeyMapping(
                KEY_CYCLE_PRESET,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KEY_CATEGORY
        );
        event.register(cyclePresetKey);

        NeoForge.EVENT_BUS.register(ClientTickHandler.class);
        OreHeatmapMod.LOGGER.info("Ore Heatmap keybindings registered");
    }

    public static class ClientTickHandler {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (toggleOverlayKey == null) {
                return;
            }

            // Old toggle (O key)
            while (toggleOverlayKey.consumeClick()) {
                boolean newState = !OreHeatmapConfig.ENABLED.get();
                OreHeatmapConfig.ENABLED.set(newState);
                if (!newState) {
                    OreHeatmapPlugin plugin = OreHeatmapPlugin.getInstance();
                    if (plugin != null && plugin.getOverlayManager() != null) {
                        plugin.getOverlayManager().clearAllOverlays();
                    }
                }
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    String msg = newState ? "message.journeymap_ore_heatmap.overlay_enabled" : "message.journeymap_ore_heatmap.overlay_disabled";
                    mc.player.displayClientMessage(Component.translatable(msg), true);
                }
            }

            // NEW: Reload key (R key)
            if (reloadHeatmapKey != null && reloadHeatmapKey.consumeClick()) {
                OreHeatmapPlugin plugin = OreHeatmapPlugin.getInstance();
                if (plugin != null && plugin.getOverlayManager() != null) {
                    plugin.getOverlayManager().reloadHeatmap();   // ← calls new method
                }
            }

            // Cycle preset (H key)
            while (cyclePresetKey.consumeClick()) {
                int current = OreHeatmapConfig.ACTIVE_PRESET_INDEX.get();
                @SuppressWarnings("unchecked") List<List<String>> presets = (List<List<String>>) OreHeatmapConfig.ORE_PRESETS.get();  // Add cast here
                int next = (current + 1) % presets.size();  // cycle through however many there are
                if (presets.isEmpty()) {
                    next = -1;
                }

                OreHeatmapConfig.ACTIVE_PRESET_INDEX.set(next);
                OreHeatmapPlugin plugin = OreHeatmapPlugin.getInstance();
                if (plugin != null && plugin.getOverlayManager() != null) {
                    plugin.getOverlayManager().reloadHeatmap();  // switch + rescan
                }

                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    String name = (next < 0 || next >= presets.size()) ? "default (trackedOres)" : presets.get(next).toString();
                    mc.player.displayClientMessage(Component.literal("§eSwitched to preset §a" + next + ": §f" + name), true);
                }
            }
        }
    }
}
