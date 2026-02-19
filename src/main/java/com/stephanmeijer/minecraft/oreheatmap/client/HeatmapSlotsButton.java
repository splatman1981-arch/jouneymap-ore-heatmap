package com.stephanmeijer.minecraft.oreheatmap.client;

import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Displayable;
import journeymap.api.v2.client.display.GuiButton;
import journeymap.api.v2.client.display.GuiContainer;
import journeymap.api.v2.client.event.MiniMapOverlayEvent;
import journeymap.api.v2.client.event.FullScreenOverlayEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = OreHeatmapMod.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class HeatmapSlotsButton {

    private static final Component BUTTON_TEXT = Component.literal("Heatmap Slots");

    @SubscribeEvent
    public static void onMiniMapOverlay(MiniMapOverlayEvent event) {
        addButton(event.getGuiContainer());
    }

    @SubscribeEvent
    public static void onFullScreenOverlay(FullScreenOverlayEvent event) {
        addButton(event.getGuiContainer());
    }

    private static void addButton(GuiContainer container) {
        GuiButton button = new GuiButton(
                BUTTON_TEXT,
                10, 10,  // x, y position (top-left corner, adjust as needed)
                120, 20, // width, height
                () -> {
                    // Open our GUI when clicked
                    Minecraft.getInstance().setScreen(new HeatmapSlotsScreen());
                }
        );

        container.add(button);
        OreHeatmapMod.LOGGER.debug("Added Heatmap Slots button to JourneyMap UI");
    }
}