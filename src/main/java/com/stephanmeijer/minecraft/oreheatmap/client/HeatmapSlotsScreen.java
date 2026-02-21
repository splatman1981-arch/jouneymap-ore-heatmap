package com.stephanmeijer.minecraft.oreheatmap.client;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapConfig;
import com.stephanmeijer.minecraft.oreheatmap.journeymap.OreHeatmapOverlayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Path;

public class HeatmapSlotsScreen extends Screen {

    private static final int SLOT_COUNT = 5;
    private final EditBox[] trackedFields = new EditBox[SLOT_COUNT];
    private final Button[] resetButtons = new Button[SLOT_COUNT];
    private final OreHeatmapOverlayManager manager;
    private int selectedSlot = OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.get() - 1; // 0-based index
    private Button trackingToggleButton;
    private boolean trackingEnabled = OreHeatmapConfig.ENABLED.get();
    private final Button[] checkboxButtons = new Button[SLOT_COUNT];

    public HeatmapSlotsScreen(OreHeatmapOverlayManager manager) {
        super(Component.literal("Heatmap Slots"));
        this.manager = manager;
    }


    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = 60;          // Moved down a bit for title breathing room
        int rowHeight = 40;       // Increased from 30 → more space between rows

        this.clearWidgets();

        // 5 rows: label + text field
        for (int i = 0; i < SLOT_COUNT; i++) {
            int slotNum = i + 1;
            int y = startY + i * rowHeight;

            // Label above field (now with more vertical space)
            addRenderableOnly(new net.minecraft.client.gui.components.StringWidget(
                    centerX - 150, y - 15, 200, 10,
                    Component.literal("Overlay " + slotNum), this.font));

            // Text field
            EditBox field = new EditBox(this.font, centerX - 150, y, 280, 20, Component.literal("Overlay " + slotNum));
            field.setValue(getTrackedString(slotNum));
            field.setMaxLength(300);
            trackedFields[i] = field;
            addRenderableWidget(field);
        }
        // Global tracking toggle (top of GUI)
        trackingToggleButton = Button.builder(
                        Component.literal(OreHeatmapConfig.ENABLED.get() ? "Tracking: ON" : "Tracking: OFF"),
                        button -> {
                            boolean newState = !OreHeatmapConfig.ENABLED.get();
                            OreHeatmapConfig.ENABLED.set(newState);
                            trackingToggleButton.setMessage(Component.literal(newState ? "Tracking: ON" : "Tracking: OFF"));
                            if (!newState && manager != null) {
                                manager.clearAllOverlays();
                            }
                        })
                .bounds(centerX - 80, 20, 160, 20)   // Top center
                .build();
        addRenderableWidget(trackingToggleButton);
        // Bottom buttons (spaced out more)
        int buttonY = this.height - 50;   // Moved up slightly for breathing room
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

        // We'll add Disable Scanning toggle in next step
    }
    private List<String> normalizeTrackedList(String input) {
        if (input == null || input.trim().isEmpty()) {
            return List.of();           // True empty list — this is what we want
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
    private void updateCheckboxes() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            checkboxButtons[i].setMessage(Component.literal(selectedSlot == i ? "☑" : "☐"));
        }

        // Switch the active overlay in the manager
        if (selectedSlot >= 0) {
            manager.setActiveSlot(selectedSlot + 1);   // we'll add this method next
        }
    }
    private void updateSlot(int slot) {
        // Save current text field to config
        List<String> newList = Arrays.asList(trackedFields[slot - 1].getValue().trim().split(","));
        switch (slot) {
            case 1 -> OreHeatmapConfig.TRACKED_ORES.set(newList);
            case 2 -> OreHeatmapConfig.TRACKED_ORES2.set(newList);
            case 3 -> OreHeatmapConfig.TRACKED_ORES3.set(newList);
            case 4 -> OreHeatmapConfig.TRACKED_ORES4.set(newList);
            case 5 -> OreHeatmapConfig.TRACKED_ORES5.set(newList);
        }

        // Force save to disk
        OreHeatmapConfig.SPEC.save();

        // Delete old cache file
        manager.resetCacheForSlot(slot);

        // Reload tracked ores
        manager.loadAllTrackedOres();

        // If this is the active slot, refresh display immediately
        if (slot == OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.get() && OreHeatmapConfig.ENABLED.get()) {
            manager.loadCacheFromDisk();
            manager.recalculateMaxOreCountForActiveSlot();

            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player != null) {
                Level level = player.level();
                ResourceKey<Level> dim = level.dimension();
                ChunkPos pChunk = new ChunkPos(player.blockPosition());
                int radius = manager.calculateVisibleRadius();
                manager.updateOverlays(level, dim, manager.currentOreCounts, pChunk, radius);
            }
        }

        Minecraft.getInstance().player.displayClientMessage(Component.literal("Updated & rescanned Overlay " + slot), true);
        OreHeatmapMod.LOGGER.info("Updated and rescanned slot {}", slot);
    }
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

    private void saveAllSlots() {
        // Save text field values to config
        OreHeatmapConfig.TRACKED_ORES.set(normalizeTrackedList(trackedFields[0].getValue()));
        OreHeatmapConfig.TRACKED_ORES2.set(normalizeTrackedList(trackedFields[1].getValue()));
        OreHeatmapConfig.TRACKED_ORES3.set(normalizeTrackedList(trackedFields[2].getValue()));
        OreHeatmapConfig.TRACKED_ORES4.set(normalizeTrackedList(trackedFields[3].getValue()));
        OreHeatmapConfig.TRACKED_ORES5.set(normalizeTrackedList(trackedFields[4].getValue()));

        // Save active slot
        OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.set(selectedSlot + 1);

        // CRITICAL: Actually write the config to disk
        OreHeatmapConfig.SPEC.save();

        // Reload everything through the manager
        manager.loadAllTrackedOres();
        manager.loadCacheFromDisk();
        manager.recalculateMaxOreCountForActiveSlot();

        // Refresh the display if enabled
        if (OreHeatmapConfig.ENABLED.get()) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player != null) {
                Level level = player.level();
                ResourceKey<Level> dim = level.dimension();
                ChunkPos pChunk = new ChunkPos(player.blockPosition());
                int radius = manager.calculateVisibleRadius();
                manager.updateOverlays(level, dim, manager.currentOreCounts, pChunk, radius);
            }
        }

        OreHeatmapMod.LOGGER.info("Saved and applied heatmap slots config");
    }

    private void saveAllSlotsAndRescanAll() {
        // Save with proper normalization
        OreHeatmapConfig.TRACKED_ORES.set(normalizeTrackedList(trackedFields[0].getValue()));
        OreHeatmapConfig.TRACKED_ORES2.set(normalizeTrackedList(trackedFields[1].getValue()));
        OreHeatmapConfig.TRACKED_ORES3.set(normalizeTrackedList(trackedFields[2].getValue()));
        OreHeatmapConfig.TRACKED_ORES4.set(normalizeTrackedList(trackedFields[3].getValue()));
        OreHeatmapConfig.TRACKED_ORES5.set(normalizeTrackedList(trackedFields[4].getValue()));

        OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.set(selectedSlot + 1);

        // Force save to disk
        OreHeatmapConfig.SPEC.save();

        // Reload tracked ores into manager
        manager.loadAllTrackedOres();

        // Clear ALL cache files
        for (int slot = 1; slot <= 5; slot++) {
            manager.resetCacheForSlot(slot);
        }

        // Start full rescan
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && OreHeatmapConfig.ENABLED.get()) {
            manager.resetCache();
            player.displayClientMessage(Component.literal("Saved & started full rescan for all overlays"), true);
        } else {
            player.displayClientMessage(Component.literal("Saved config (scanning disabled)"), true);
        }

        OreHeatmapMod.LOGGER.info("Saved config and initiated full rescan");
    }

    private void resetSlot(int slot) {
        // Clear config entry
        switch (slot) {
            case 1 -> OreHeatmapConfig.TRACKED_ORES.set(List.of(""));
            case 2 -> OreHeatmapConfig.TRACKED_ORES2.set(List.of(""));
            case 3 -> OreHeatmapConfig.TRACKED_ORES3.set(List.of(""));
            case 4 -> OreHeatmapConfig.TRACKED_ORES4.set(List.of(""));
            case 5 -> OreHeatmapConfig.TRACKED_ORES5.set(List.of(""));
        }

        trackedFields[slot - 1].setValue("");

        manager.resetCacheForSlot(slot);

        Minecraft.getInstance().player.displayClientMessage(Component.literal("Reset Overlay " + slot), true);
    }

    // Helper - get path for any slot
    private Path getCacheFilePathForSlot(int slot) {
        if (manager.cacheDirectory == null || manager.currentWorldId == null) return null;
        String fileName = "overlay" + slot + ".json";
        return manager.cacheDirectory.resolve(manager.currentWorldId).resolve(fileName);
    }

    private void resetAllSlots() {
        for (int i = 1; i <= 5; i++) {
            resetSlot(i);
        }
        selectedSlot = 1;
        updateCheckboxes();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
