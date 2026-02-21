package com.stephanmeijer.minecraft.oreheatmap.client;

import java.util.ArrayList;
import java.util.List;

import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapConfig;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import com.stephanmeijer.minecraft.oreheatmap.journeymap.OreHeatmapOverlayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

public class HeatmapSlotsScreen extends Screen {

    private static final int SLOT_COUNT = 5;

    private final OreHeatmapOverlayManager manager;
    private final EditBox[] trackedFields = new EditBox[SLOT_COUNT];
    private Button trackingToggleButton;

    public HeatmapSlotsScreen(OreHeatmapOverlayManager manager) {
        super(Component.literal("Heatmap Slots"));
        this.manager = manager;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = 60;
        int rowHeight = 40;

        this.clearWidgets();

        // === Text fields with labels ===
        for (int i = 0; i < SLOT_COUNT; i++) {
            int slotNum = i + 1;
            int y = startY + i * rowHeight;

            // Label
            addRenderableOnly(new net.minecraft.client.gui.components.StringWidget(
                    centerX - 150, y - 18, 280, 10,
                    Component.literal("Overlay " + slotNum), this.font));

            // Editable field
            EditBox field = new EditBox(this.font, centerX - 150, y, 280, 20,
                    Component.literal("Overlay " + slotNum));
            field.setValue(getTrackedString(slotNum));
            field.setMaxLength(300);
            trackedFields[i] = field;
            addRenderableWidget(field);
        }

        // === Global tracking toggle (top) ===
        trackingToggleButton = Button.builder(
                        Component.literal(OreHeatmapConfig.ENABLED.get() ? "Tracking: ON" : "Tracking: OFF"),
                        button -> {
                            boolean newState = !OreHeatmapConfig.ENABLED.get();
                            OreHeatmapConfig.ENABLED.set(newState);
                            trackingToggleButton.setMessage(Component.literal(newState ? "Tracking: ON" : "Tracking: OFF"));
                            manager.activeOverlaySlot = 1;
                            if (!newState) {
                                manager.clearAllOverlays();
                                manager.activeOverlaySlot = 0;
                            }
                        })
                .bounds(centerX - 80, 20, 160, 20)
                .build();
        addRenderableWidget(trackingToggleButton);

        // === Bottom action buttons ===
        int buttonY = this.height - 50;

        addRenderableWidget(Button.builder(
                        Component.literal("Save and Re-scan"),
                        button -> {
                            saveAllSlotsAndRescanAll();
                            this.onClose();
                        })
                .bounds(centerX - 160, buttonY, 140, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Close"),
                        button -> this.onClose())
                .bounds(centerX + 20, buttonY, 100, 20)
                .build());
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private String getTrackedString(int slot) {
        return switch (slot) {
            case 1 -> String.join(",", OreHeatmapConfig.TRACKED_ORES.get());
            case 2 -> String.join(",", OreHeatmapConfig.TRACKED_ORES2.get());
            case 3 -> String.join(",", OreHeatmapConfig.TRACKED_ORES3.get());
            case 4 -> String.join(",", OreHeatmapConfig.TRACKED_ORES4.get());
            case 5 -> String.join(",", OreHeatmapConfig.TRACKED_ORES5.get());
            default -> "";
        };
    }

    private List<String> normalizeTrackedList(String input) {
        if (input == null || input.trim().isEmpty()) {
            return List.of();
        }
        String[] parts = input.trim().split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list.isEmpty() ? List.of() : list;
    }

    private void saveAllSlotsAndRescanAll() {
        OreHeatmapConfig.TRACKED_ORES.set(normalizeTrackedList(trackedFields[0].getValue()));
        OreHeatmapConfig.TRACKED_ORES2.set(normalizeTrackedList(trackedFields[1].getValue()));
        OreHeatmapConfig.TRACKED_ORES3.set(normalizeTrackedList(trackedFields[2].getValue()));
        OreHeatmapConfig.TRACKED_ORES4.set(normalizeTrackedList(trackedFields[3].getValue()));
        OreHeatmapConfig.TRACKED_ORES5.set(normalizeTrackedList(trackedFields[4].getValue()));

        OreHeatmapConfig.SPEC.save();

        manager.loadAllTrackedOres();

        // Clear every cache file
        for (int slot = 1; slot <= 5; slot++) {
            manager.resetCacheForSlot(slot);
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && OreHeatmapConfig.ENABLED.get()) {
            manager.resetCache();
            player.displayClientMessage(Component.literal("Saved & started full rescan for all overlays"), true);
        } else if (player != null) {
            player.displayClientMessage(Component.literal("Saved config (scanning disabled)"), true);
        }

        OreHeatmapMod.LOGGER.info("Saved config and initiated full rescan");
    }

    // ===================================================================
    // Render
    // ===================================================================

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
