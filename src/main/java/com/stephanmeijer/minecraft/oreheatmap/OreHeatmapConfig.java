package com.stephanmeijer.minecraft.oreheatmap;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

public class OreHeatmapConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.DoubleValue RESCAN_RADIUS_MULTIPLIER;
    public static final ModConfigSpec.IntValue RESCAN_CHUNKS_PER_TICK;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue SCAN_RADIUS;
    public static final ModConfigSpec.IntValue UPDATE_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue OVERLAY_OPACITY;
    public static final ModConfigSpec.BooleanValue SHOW_OVERLAY_IN_CAVES;

    // Ore configurations
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRACKED_ORES;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("JourneyMap Ore Heatmap Configuration")
                .push("general");

        ENABLED = BUILDER
                .comment("Enable the ore heatmap overlay")
                .define("enabled", false);

        SCAN_RADIUS = BUILDER
                .comment("Chunk scan radius around the player (1-8)")
                .defineInRange("scanRadius", 3, 1, 8);

        UPDATE_INTERVAL_TICKS = BUILDER
                .comment("How often to update the overlay (in ticks, 20 = 1 second)")
                .defineInRange("updateIntervalTicks", 40, 20, 200);

        OVERLAY_OPACITY = BUILDER
                .comment("Opacity of the heatmap overlay (0.0 - 1.0)")
                .defineInRange("overlayOpacity", 0.6, 0.1, 1.0);

        SHOW_OVERLAY_IN_CAVES = BUILDER
                .comment("Show the overlay in cave/underground maps")
                .define("showInCaves", true);

        RESCAN_RADIUS_MULTIPLIER = BUILDER
                .comment("Re-Scan Radius Multiplier")
                .defineInRange("rescanRadiusMultiplier", 2.0, 1.0, 5.0);

        RESCAN_CHUNKS_PER_TICK = BUILDER
                .comment("Re-Scan Chunks per tick.")
                .defineInRange("rescanChunksPerTick", 1, 1, 200);

        BUILDER.pop();

        BUILDER.comment("Ore tracking configuration")
                .push("ores");

        TRACKED_ORES = BUILDER
                .comment("List of ores to track.",
                        "Supports both block IDs and tags:",
                        "  - Block ID: \"namespace:block_id\" (e.g., \"minecraft:diamond_ore\")",
                        "  - Tag: \"#namespace:tag\" (e.g., \"#c:ores\" for all conventional ores)",
                        "The #c:ores tag includes all ores from vanilla and most mods.")
                .defineListAllowEmpty("trackedOres", List.of(
                        "#c:ores"
                ), OreHeatmapConfig::validateOreEntry);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * Validates ore entry format.
     * Supported formats:
     * - Block ID: "namespace:block_id" (e.g., "minecraft:diamond_ore")
     * - Tag: "#namespace:tag" (e.g., "#c:ores", "#minecraft:diamond_ores")
     */
    private static boolean validateOreEntry(Object obj) {
        if (!(obj instanceof String str)) {
            return false;
        }
        str = str.trim();

        // Tag format: #namespace:tag
        if (str.startsWith("#")) {
            String tagPart = str.substring(1);
            return tagPart.contains(":") && tagPart.length() > 2;
        }

        // Block ID format: namespace:block_id
        return str.contains(":") && str.length() > 2;
    }
}
