package com.stephanmeijer.minecraft.oreheatmap;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public class OreHeatmapConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue SCAN_RADIUS;
    public static final ModConfigSpec.IntValue UPDATE_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue OVERLAY_OPACITY;
    public static final ModConfigSpec.BooleanValue SHOW_OVERLAY_IN_CAVES;
    public static final ModConfigSpec.IntValue RESCAN_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue ACTIVE_OVERLAY_SLOT;

    // Ore tracking lists (empty = disabled)
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRACKED_ORES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRACKED_ORES2;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRACKED_ORES3;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRACKED_ORES4;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRACKED_ORES5;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("JourneyMap Ore Heatmap Configuration").push("general");

        ENABLED = BUILDER
                .comment("Enable the ore heatmap overlay (false = completely disabled, no scanning)")
                .define("enabled", false);

        SCAN_RADIUS = BUILDER
                .comment("Chunk scan radius around the player (1-8)")
                .defineInRange("scanRadius", 3, 1, 8);

        UPDATE_INTERVAL_TICKS = BUILDER
                .comment("How often to update the overlay (ticks, 20 = 1 second)")
                .defineInRange("updateIntervalTicks", 40, 20, 200);

        OVERLAY_OPACITY = BUILDER
                .comment("Opacity of the heatmap overlay (0.0 - 1.0)")
                .defineInRange("overlayOpacity", 0.6, 0.1, 1.0);

        SHOW_OVERLAY_IN_CAVES = BUILDER
                .comment("Show the overlay in cave/underground maps")
                .define("showInCaves", true);

        RESCAN_CHUNKS_PER_TICK = BUILDER
                .comment("Maximum chunks to scan per tick during background rescan")
                .defineInRange("rescanChunksPerTick", 1, 1, 200);

        ACTIVE_OVERLAY_SLOT = BUILDER
                .comment("Currently active overlay slot (1-5)")
                .defineInRange("activeOverlaySlot", 1, 1, 5);

        BUILDER.pop();

        BUILDER.comment("Ore tracking configuration (one list per overlay)").push("ores");

        TRACKED_ORES = BUILDER
                .comment("Overlay 1 - List of ores/tags (empty = disabled)")
                .defineListAllowEmpty("trackedOres", List.of("#c:ores"), OreHeatmapConfig::validateOreEntry);

        TRACKED_ORES2 = BUILDER
                .comment("Overlay 2")
                .defineListAllowEmpty("trackedOres2", List.of(), OreHeatmapConfig::validateOreEntry);

        TRACKED_ORES3 = BUILDER
                .comment("Overlay 3")
                .defineListAllowEmpty("trackedOres3", List.of(), OreHeatmapConfig::validateOreEntry);

        TRACKED_ORES4 = BUILDER
                .comment("Overlay 4")
                .defineListAllowEmpty("trackedOres4", List.of(), OreHeatmapConfig::validateOreEntry);

        TRACKED_ORES5 = BUILDER
                .comment("Overlay 5")
                .defineListAllowEmpty("trackedOres5", List.of(), OreHeatmapConfig::validateOreEntry);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * Validates ore entry format.
     * Supports: "namespace:block_id" or "#namespace:tag"
     */
    private static boolean validateOreEntry(Object obj) {
        if (!(obj instanceof String str)) return false;
        str = str.trim();
        if (str.isEmpty()) return true; // allow empty entries

        if (str.startsWith("#")) {
            String tag = str.substring(1);
            return tag.contains(":") && tag.length() > 2;
        }
        return str.contains(":") && str.length() > 2;
    }
}