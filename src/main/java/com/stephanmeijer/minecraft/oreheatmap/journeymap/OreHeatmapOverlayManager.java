package com.stephanmeijer.minecraft.oreheatmap.journeymap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapConfig;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.util.UIState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ore scanning and JourneyMap overlay rendering.
 * Uses event-based architecture: scans chunks when they load.
 */
public class OreHeatmapOverlayManager {

    private final IClientAPI jmAPI;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Persistent storage per dimension (dimension location string -> chunk data)
    private final Map<String, Map<String, Integer>> dimensionOreCounts = new ConcurrentHashMap<>();
    private final Map<String, PolygonOverlay> activeOverlays = new ConcurrentHashMap<>();

    // Tracked ores: specific block IDs and tags
    private final Set<ResourceLocation> trackedBlocks = new HashSet<>();
    private final Set<TagKey<Block>> trackedTags = new HashSet<>();

    // Dynamic color scaling
    private int maxOreCount = 1;

    // Heatmap colors
    private static final int COLOR_LOW = 0xFFFFE0;   // Light yellow/white
    private static final int COLOR_MID = 0xFF8C00;   // Dark orange
    private static final int COLOR_HIGH = 0x8B0000;  // Dark red

    private int tickCounter = 0;
    private int saveCounter = 0;
    private static final int SAVE_INTERVAL = 600; // Save every 30 seconds (600 ticks)

    private ResourceKey<Level> currentDimension = null;
    private String currentWorldId = null;
    private boolean wasEnabled = false;  // Track previous enabled state

    public OreHeatmapOverlayManager(IClientAPI jmAPI) {
        this.jmAPI = jmAPI;
        loadTrackedOres();
    }

    private void loadTrackedOres() {
        trackedBlocks.clear();
        trackedTags.clear();

        for (String entry : OreHeatmapConfig.TRACKED_ORES.get()) {
            String trimmed = entry.trim();

            if (trimmed.startsWith("#")) {
                // Tag format: #namespace:tag
                String tagId = trimmed.substring(1);
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(tagId));
                trackedTags.add(tag);
                OreHeatmapMod.LOGGER.debug("Tracking ore tag: {}", tagId);
            } else {
                // Block ID format: namespace:block_id
                trackedBlocks.add(ResourceLocation.parse(trimmed));
            }
        }

        OreHeatmapMod.LOGGER.info("Loaded {} tracked blocks and {} tracked tags",
                trackedBlocks.size(), trackedTags.size());
    }

    /**
     * Get a unique identifier for the current world/server.
     */
    private String getWorldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            // Single player - use world folder name
            return "local_" + mc.getSingleplayerServer().getWorldData().getLevelName()
                    .replaceAll("[^a-zA-Z0-9]", "_");
        } else if (mc.getCurrentServer() != null) {
            // Multiplayer - use server address
            return "server_" + mc.getCurrentServer().ip
                    .replaceAll("[^a-zA-Z0-9]", "_");
        }
        return "unknown";
    }

    private Path getCacheFilePath() {
        Path cacheDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("journeymap").resolve("ore_heatmap_cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            OreHeatmapMod.LOGGER.error("Failed to create cache directory", e);
        }
        return cacheDir.resolve(currentWorldId + ".json");
    }

    /**
     * Load cached ore data from disk.
     */
    private void loadCacheFromDisk() {
        if (currentWorldId == null) return;

        Path cacheFile = getCacheFilePath();
        if (!Files.exists(cacheFile)) {
            OreHeatmapMod.LOGGER.debug("No cache file found for world: {}", currentWorldId);
            return;
        }

        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            Type type = new TypeToken<Map<String, Map<String, Integer>>>(){}.getType();
            Map<String, Map<String, Integer>> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                dimensionOreCounts.clear();
                dimensionOreCounts.putAll(loaded);

                // Recalculate maxOreCount from loaded data
                maxOreCount = 1;
                for (Map<String, Integer> chunks : dimensionOreCounts.values()) {
                    for (int count : chunks.values()) {
                        if (count > maxOreCount) {
                            maxOreCount = count;
                        }
                    }
                }

                OreHeatmapMod.LOGGER.info("Loaded ore cache for world: {} ({} dimensions)",
                        currentWorldId, dimensionOreCounts.size());
            }
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.error("Failed to load ore cache", e);
        }
    }

    /**
     * Save ore data to disk.
     */
    private void saveCacheToDisk() {
        if (currentWorldId == null || dimensionOreCounts.isEmpty()) return;

        Path cacheFile = getCacheFilePath();
        try (Writer writer = Files.newBufferedWriter(cacheFile)) {
            GSON.toJson(dimensionOreCounts, writer);
            OreHeatmapMod.LOGGER.debug("Saved ore cache for world: {}", currentWorldId);
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.error("Failed to save ore cache", e);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Save cache when leaving world
        saveCacheToDisk();
        clearAllOverlays();
        dimensionOreCounts.clear();
        maxOreCount = 1;
        currentWorldId = null;
        currentDimension = null;
    }

    /**
     * Event-based chunk scanning: scan each chunk when it loads.
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            Level level = (Level) event.getLevel();

            // Initialize world ID if not set (chunks can load before player tick)
            if (currentWorldId == null) {
                currentWorldId = getWorldId();
                loadCacheFromDisk();
                OreHeatmapMod.LOGGER.info("Initialized ore heatmap for world: {} (from chunk load)", currentWorldId);
            }

            LevelChunk chunk = (LevelChunk) event.getChunk();
            ChunkPos chunkPos = chunk.getPos();

            String dimKey = level.dimension().location().toString();
            Map<String, Integer> oreCounts = dimensionOreCounts.computeIfAbsent(
                    dimKey, k -> new ConcurrentHashMap<>()
            );

            String chunkKey = chunkPos.x + "," + chunkPos.z;

            // Only scan if not already scanned (persisted data)
            if (!oreCounts.containsKey(chunkKey)) {
                int count = scanChunk(level, chunkPos);
                if (count >= 0) {
                    oreCounts.put(chunkKey, count);
                    if (count > maxOreCount) {
                        maxOreCount = count;
                    }
                    OreHeatmapMod.LOGGER.debug("Scanned chunk {},{}: {} ores", chunkPos.x, chunkPos.z, count);
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }

        // Initialize world ID on first tick
        if (currentWorldId == null) {
            currentWorldId = getWorldId();
            loadCacheFromDisk();
            OreHeatmapMod.LOGGER.info("Initialized ore heatmap for world: {}", currentWorldId);
        }

        Level level = player.level();
        if (level == null) {
            return;
        }

        // Check for dimension change
        ResourceKey<Level> dimension = level.dimension();
        if (currentDimension == null || !currentDimension.equals(dimension)) {
            currentDimension = dimension;
            recalculateMaxOreCount();
        }

        // Overlay updates and saves are throttled
        tickCounter++;
        if (tickCounter < OreHeatmapConfig.UPDATE_INTERVAL_TICKS.get()) {
            return;
        }
        tickCounter = 0;

        // Periodic save
        saveCounter++;
        if (saveCounter >= SAVE_INTERVAL / OreHeatmapConfig.UPDATE_INTERVAL_TICKS.get()) {
            saveCounter = 0;
            saveCacheToDisk();
        }

        // Handle overlay visibility based on enabled state
        boolean isEnabled = OreHeatmapConfig.ENABLED.get();

        String dimKey = dimension.location().toString();
        Map<String, Integer> oreCounts = dimensionOreCounts.get(dimKey);

        if (isEnabled && oreCounts != null) {
            ChunkPos playerChunk = new ChunkPos(player.blockPosition());
            int radius = calculateVisibleRadius();
            updateOverlays(dimension, oreCounts, playerChunk, radius);
        } else if (wasEnabled) {
            // Only clear once when transitioning from enabled to disabled
            clearAllOverlays();
        }

        wasEnabled = isEnabled;
    }

    private void recalculateMaxOreCount() {
        maxOreCount = 1;
        for (Map<String, Integer> chunks : dimensionOreCounts.values()) {
            for (int count : chunks.values()) {
                if (count > maxOreCount) {
                    maxOreCount = count;
                }
            }
        }
    }

    private int calculateVisibleRadius() {
        try {
            UIState minimapState = jmAPI.getUIState(Context.UI.Minimap);
            if (minimapState != null) {
                int zoom = minimapState.zoom;
                int blockRadius = 128 >> zoom;
                int chunkRadius = Math.max(2, (blockRadius / 16) + 1);
                return Math.min(chunkRadius, OreHeatmapConfig.SCAN_RADIUS.get() * 2);
            }
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.debug("Could not get minimap state: {}", e.getMessage());
        }
        return OreHeatmapConfig.SCAN_RADIUS.get();
    }

    private int scanChunk(Level level, ChunkPos chunkPos) {
        if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
            return -1;
        }

        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int totalCount = 0;

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos pos = new BlockPos(chunkPos.getMinBlockX() + x, y, chunkPos.getMinBlockZ() + z);
                    BlockState state = chunk.getBlockState(pos);

                    if (isTrackedOre(state)) {
                        totalCount++;
                    }
                }
            }
        }

        return totalCount;
    }

    /**
     * Check if a block state matches any tracked ore (by block ID or tag).
     */
    private boolean isTrackedOre(BlockState state) {
        // Check direct block ID match
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (trackedBlocks.contains(blockId)) {
            return true;
        }

        // Check tag matches
        for (TagKey<Block> tag : trackedTags) {
            if (state.is(tag)) {
                return true;
            }
        }

        return false;
    }

    private void updateOverlays(ResourceKey<Level> dimension, Map<String, Integer> oreCounts,
                                 ChunkPos playerChunk, int radius) {
        float maxOpacity = (float) (double) OreHeatmapConfig.OVERLAY_OPACITY.get();

        Set<String> visibleKeys = new HashSet<>();

        for (Map.Entry<String, Integer> entry : oreCounts.entrySet()) {
            String chunkKey = entry.getKey();
            int totalOres = entry.getValue();

            visibleKeys.add(chunkKey);

            if (totalOres == 0) {
                PolygonOverlay existing = activeOverlays.remove(chunkKey);
                if (existing != null) {
                    try {
                        jmAPI.remove(existing);
                    } catch (Exception e) {
                        OreHeatmapMod.LOGGER.debug("Failed to remove overlay: {}", e.getMessage());
                    }
                }
                continue;
            }

            // Parse chunk position from key
            String[] parts = chunkKey.split(",");
            ChunkPos chunkPos = new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));

            int color = calculateHeatmapColor(totalOres);

            float densityFactor = Math.min(1.0f, totalOres / (float) Math.max(1, maxOreCount));
            float fillOpacity = 0.2f + (densityFactor * (maxOpacity - 0.2f));

            MapPolygon polygon = createChunkPolygon(chunkPos);

            ShapeProperties shapeProps = new ShapeProperties()
                    .setFillColor(color)
                    .setFillOpacity(fillOpacity)
                    .setStrokeColor(color)
                    .setStrokeOpacity(Math.min(1.0f, fillOpacity + 0.15f))
                    .setStrokeWidth(1.0f);

            PolygonOverlay overlay = activeOverlays.get(chunkKey);

            if (overlay == null) {
                overlay = new PolygonOverlay(OreHeatmapMod.MODID, dimension, shapeProps, polygon);
                overlay.setTitle("Ores: " + totalOres + " blocks");

                try {
                    jmAPI.show(overlay);
                    activeOverlays.put(chunkKey, overlay);
                } catch (Exception e) {
                    OreHeatmapMod.LOGGER.error("Failed to show overlay: {}", e.getMessage());
                }
            } else {
                overlay.setOuterArea(polygon);
                overlay.setShapeProperties(shapeProps);
                overlay.setTitle("Ores: " + totalOres + " blocks");

                try {
                    jmAPI.show(overlay);
                } catch (Exception e) {
                    OreHeatmapMod.LOGGER.debug("Failed to update overlay: {}", e.getMessage());
                }
            }
        }

        Set<String> toRemove = new HashSet<>();
        for (String key : activeOverlays.keySet()) {
            if (!visibleKeys.contains(key)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            PolygonOverlay overlay = activeOverlays.remove(key);
            if (overlay != null) {
                try {
                    jmAPI.remove(overlay);
                } catch (Exception e) {
                    OreHeatmapMod.LOGGER.debug("Failed to remove overlay: {}", e.getMessage());
                }
            }
        }
    }

    private int calculateHeatmapColor(int oreCount) {
        if (maxOreCount <= 1) {
            return COLOR_MID;
        }

        float t = oreCount / (float) maxOreCount;

        if (t < 0.5f) {
            return interpolateColor(COLOR_LOW, COLOR_MID, t * 2);
        } else {
            return interpolateColor(COLOR_MID, COLOR_HIGH, (t - 0.5f) * 2);
        }
    }

    private int interpolateColor(int color1, int color2, float t) {
        t = Math.max(0, Math.min(1, t));

        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;

        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;

        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);

        return (r << 16) | (g << 8) | b;
    }

    private MapPolygon createChunkPolygon(ChunkPos chunkPos) {
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int maxX = chunkPos.getMaxBlockX();
        int maxZ = chunkPos.getMaxBlockZ();

        return new MapPolygon(
                new BlockPos(minX, 64, maxZ),
                new BlockPos(maxX + 1, 64, maxZ),
                new BlockPos(maxX + 1, 64, minZ),
                new BlockPos(minX, 64, minZ)
        );
    }

    public void clearAllOverlays() {
        for (PolygonOverlay overlay : activeOverlays.values()) {
            try {
                jmAPI.remove(overlay);
            } catch (Exception e) {
                OreHeatmapMod.LOGGER.debug("Failed to remove overlay during clear: {}", e.getMessage());
            }
        }
        activeOverlays.clear();
    }

}
