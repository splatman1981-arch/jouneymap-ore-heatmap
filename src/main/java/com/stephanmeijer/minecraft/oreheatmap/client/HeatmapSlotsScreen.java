package com.stephanmeijer.minecraft.oreheatmap.client;

import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapConfig;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Arrays;
import java.util.List;
public class HeatmapSlotsScreen extends Screen {

    private static final int SLOT_COUNT = 5;
    private final EditBox[] trackedFields = new EditBox[SLOT_COUNT];
    private final Button[] resetButtons = new Button[SLOT_COUNT];
    private int selectedSlot = OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.get() - 1; // 0-based index

    public HeatmapSlotsScreen() {
        super(Component.literal("Heatmap Slots"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = 40;
        int rowHeight = 30;

        for (int i = 0; i < SLOT_COUNT; i++) {
            int slotNum = i + 1;
            int y = startY + i * rowHeight;

            // Checkbox (active slot)
            int finalI = i;
            addRenderableWidget(Button.builder(
                            Component.literal(selectedSlot + 1 == slotNum ? "☑" : "☐"),
                            button -> {
                                selectedSlot = finalI;
                                updateCheckboxes();
                                OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.set(slotNum);
                            })
                    .bounds(centerX - 180, y, 20, 20)
                    .build());

            // Text field for tracked ores
            EditBox field = new EditBox(this.font, centerX - 150, y, 200, 20, Component.literal("Overlay " + slotNum));
            field.setValue(getTrackedString(slotNum));
            field.setMaxLength(200);
            trackedFields[i] = field;
            addRenderableWidget(field);

            // Reset button
            addRenderableWidget(Button.builder(
                            Component.literal("Reset"),
                            button -> {
                                resetSlot(slotNum);
                                field.setValue("");
                            })
                    .bounds(centerX + 60, y, 80, 20)
                    .build());
        }

        // Bottom buttons
        addRenderableWidget(Button.builder(
                        Component.literal("Reset All"),
                        button -> resetAllSlots())
                .bounds(centerX - 140, this.height - 40, 120, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Save & Close"),
                        button -> {
                            saveAllSlots();
                            this.onClose();
                        })
                .bounds(centerX + 20, this.height - 40, 120, 20)
                .build());
    }

    private void updateCheckboxes() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            Button btn = (Button) this.renderables.get(i * 3); // rough index - adjust if needed
            btn.setMessage(Component.literal(selectedSlot == i ? "☑" : "☐"));
        }
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
        OreHeatmapConfig.TRACKED_ORES.set(List.of(trackedFields[0].getValue().split(",")));
        OreHeatmapConfig.TRACKED_ORES2.set(List.of(trackedFields[1].getValue().split(",")));
        OreHeatmapConfig.TRACKED_ORES3.set(List.of(trackedFields[2].getValue().split(",")));
        OreHeatmapConfig.TRACKED_ORES4.set(List.of(trackedFields[3].getValue().split(",")));
        OreHeatmapConfig.TRACKED_ORES5.set(List.of(trackedFields[4].getValue().split(",")));

        OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.set(selectedSlot + 1);
        OreHeatmapMod.LOGGER.info("Saved heatmap slots config");
    }

    private void resetSlot(int slot) {
        switch (slot) {
            case 1 -> OreHeatmapConfig.TRACKED_ORES.set(List.of(""));
            case 2 -> OreHeatmapConfig.TRACKED_ORES2.set(List.of(""));
            case 3 -> OreHeatmapConfig.TRACKED_ORES3.set(List.of(""));
            case 4 -> OreHeatmapConfig.TRACKED_ORES4.set(List.of(""));
            case 5 -> OreHeatmapConfig.TRACKED_ORES5.set(List.of(""));
        }
        trackedFields[slot - 1].setValue("");
        if (slot == OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.get()) {
            this.minecraft.player.displayClientMessage(Component.literal("Reset Overlay " + slot), true);
        }
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
