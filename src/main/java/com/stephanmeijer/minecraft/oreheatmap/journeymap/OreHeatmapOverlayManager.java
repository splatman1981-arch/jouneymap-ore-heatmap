package com.stephanmeijer.minecraft.oreheatmap.journeymap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

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

    // Dynamic color scaling - thread-safe for access from chunk load and player tick events
    private final AtomicInteger maxOreCount = new AtomicInteger(1);

    // Heatmap colors
    private static final int COLOR_LOW = 0xFFFFE0;   // Light yellow/white
    private static final int COLOR_MID = 0xFF8C00;   // Dark orange
    private static final int COLOR_HIGH = 0x8B0000;  // Dark red

    // Y-coordinate for overlay polygon plane
    private static final int POLYGON_Y_LEVEL = 64;

    private int tickCounter;
    private int saveCounter;
    private static final int SAVE_INTERVAL = 600; // Save every 30 seconds (600 ticks)

    private ResourceKey<Level> currentDimension;
    private String currentWorldId;
    private boolean wasEnabled;  // Track previous enabled state
    private boolean cacheLoadFailed;  // Track if cache failed to load
    private Path cacheDirectory;  // Cached directory path

    public OreHeatmapOverlayManager(IClientAPI jmAPI) {
        this.jmAPI = jmAPI;
        loadTrackedOres();
        initializeCacheDirectory();
    }

    /**
     * Initialize the cache directory once during construction.
     */
    private void initializeCacheDirectory() {
        try {
            cacheDirectory = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("journeymap").resolve("ore_heatmap_cache");
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            OreHeatmapMod.LOGGER.error("Failed to create cache directory", e);
            cacheDirectory = null;
        }
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
     * Uses the world's save folder name (not display name) for uniqueness.
     *
     * @return the world ID, or null if it cannot be determined
     */
    private String getWorldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            // Single player - use world save folder name (not display name!)
            // getWorldPath gives us the full path, we normalize it to resolve "." components
            // then extract just the folder name
            Path worldPath = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            Path normalizedPath = worldPath.toAbsolutePath().normalize();
            Path fileName = normalizedPath.getFileName();
            if (fileName == null) {
                OreHeatmapMod.LOGGER.warn("Could not get world folder name from path: {}", worldPath);
                return null;
            }
            String folderName = fileName.toString();
            return "local_" + folderName.replaceAll("[^a-zA-Z0-9_()-]", "_");
        } else if (mc.getCurrentServer() != null) {
            // Multiplayer - use server address
            return "server_" + mc.getCurrentServer().ip
                    .replaceAll("[^a-zA-Z0-9]", "_");
        }
        return null;
    }

    /**
     * Get the cache file path for the current world.
     *
     * @return the cache file path, or null if cache directory or world ID is unavailable
     */
    private Path getCacheFilePath() {
        if (cacheDirectory == null || currentWorldId == null) {
            return null;
        }
        return cacheDirectory.resolve(currentWorldId + ".json");
    }

    /**
     * Load cached ore data from disk.
     */
    private void loadCacheFromDisk() {
        cacheLoadFailed = false;

        Path cacheFile = getCacheFilePath();
        if (cacheFile == null) {
            OreHeatmapMod.LOGGER.debug("Cache file path unavailable");
            return;
        }

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
                recalculateMaxOreCount();

                OreHeatmapMod.LOGGER.info("Loaded ore cache for world: {} ({} dimensions)",
                        currentWorldId, dimensionOreCounts.size());
            }
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.error("Failed to load ore cache for world: {} - cache may be corrupted",
                    currentWorldId, e);
            cacheLoadFailed = true;
        }
    }

    /**
     * Save ore data to disk.
     */
    private void saveCacheToDisk() {
        if (dimensionOreCounts.isEmpty()) {
            return;
        }

        Path cacheFile = getCacheFilePath();
        if (cacheFile == null) {
            OreHeatmapMod.LOGGER.debug("Cannot save cache: file path unavailable");
            return;
        }

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
        resetWorldState();
    }

    /**
     * Reset all world-specific state. Called when leaving a world or switching worlds.
     */
    private void resetWorldState() {
        clearAllOverlays();
        dimensionOreCounts.clear();
        maxOreCount.set(1);
        currentWorldId = null;
        currentDimension = null;
        cacheLoadFailed = false;
    }

    /**
     * Check if the world has changed and handle the transition.
     * Returns true if we're ready to process (world ID is valid), false otherwise.
     */
    private boolean ensureCorrectWorld() {
        String newWorldId = getWorldId();
        if (newWorldId == null) {
            // Cannot determine world ID yet
            return false;
        }

        if (currentWorldId == null) {
            // First time initialization
            currentWorldId = newWorldId;
            loadCacheFromDisk();
            OreHeatmapMod.LOGGER.info("Initialized ore heatmap for world: {}", currentWorldId);
            return true;
        }

        if (!currentWorldId.equals(newWorldId)) {
            // World has changed - save old data and switch
            OreHeatmapMod.LOGGER.info("World changed from {} to {} - switching data", currentWorldId, newWorldId);
            saveCacheToDisk();
            resetWorldState();
            currentWorldId = newWorldId;
            loadCacheFromDisk();
            return true;
        }

        // Same world, continue normally
        return true;
    }

    /**
     * Event-based chunk scanning: scan each chunk when it loads.
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }

        if (!(event.getLevel() instanceof Level level)) {
            return;
        }

        // Verify we're in the correct world (handles world switches)
        if (!ensureCorrectWorld()) {
            return;
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
                // Thread-safe update of maxOreCount
                maxOreCount.updateAndGet(current -> Math.max(current, count));
                OreHeatmapMod.LOGGER.debug("Scanned chunk {},{}: {} ores", chunkPos.x, chunkPos.z, count);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }

        // Verify we're in the correct world (handles world switches)
        if (!ensureCorrectWorld()) {
            return;
        }

        // Notify user if cache was corrupted (only once per world load)
        if (cacheLoadFailed) {
            OreHeatmapMod.LOGGER.warn("Ore heatmap cache was corrupted for world: {} - starting fresh scan",
                    currentWorldId);
            cacheLoadFailed = false;  // Only warn once
        }

        Level level = player.level();

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
        int saveTickInterval = Math.max(1, SAVE_INTERVAL / OreHeatmapConfig.UPDATE_INTERVAL_TICKS.get());
        if (saveCounter >= saveTickInterval) {
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
            updateOverlays(level, dimension, oreCounts, playerChunk, radius);
        } else if (wasEnabled) {
            // Only clear once when transitioning from enabled to disabled
            clearAllOverlays();
        }

        wasEnabled = isEnabled;
    }

    private void recalculateMaxOreCount() {
        int max = 1;
        for (Map<String, Integer> chunks : dimensionOreCounts.values()) {
            for (int count : chunks.values()) {
                if (count > max) {
                    max = count;
                }
            }
        }
        maxOreCount.set(max);
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

    private void updateOverlays(Level level, ResourceKey<Level> dimension, Map<String, Integer> oreCounts,
                                ChunkPos playerChunk, int radius) {
        // Check cave display setting - skip if underground and setting is disabled
        if (!OreHeatmapConfig.SHOW_OVERLAY_IN_CAVES.get()) {
            try {
                UIState fullscreenState = jmAPI.getUIState(Context.UI.Fullscreen);
                if (fullscreenState != null && fullscreenState.dimension.equals(dimension)) {
                    // Check if viewing underground/cave map (mapType indicates this)
                    // For now, we allow all views since JourneyMap API doesn't expose mapType clearly
                }
            } catch (Exception e) {
                OreHeatmapMod.LOGGER.debug("Could not check map state: {}", e.getMessage());
            }
        }

        float maxOpacity = (float) (double) OreHeatmapConfig.OVERLAY_OPACITY.get();
        int currentMax = maxOreCount.get();

        Set<String> visibleKeys = new HashSet<>();

        // Create snapshot to avoid ConcurrentModificationException during iteration
        List<Map.Entry<String, Integer>> snapshot = new ArrayList<>(oreCounts.entrySet());

        for (Map.Entry<String, Integer> entry : snapshot) {
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

            // Parse chunk position from key with error handling
            String[] parts = chunkKey.split(",");
            if (parts.length != 2) {
                OreHeatmapMod.LOGGER.warn("Invalid chunk key format: {}", chunkKey);
                continue;
            }

            ChunkPos chunkPos;
            try {
                chunkPos = new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                OreHeatmapMod.LOGGER.warn("Invalid chunk coordinates in key: {}", chunkKey);
                continue;
            }

            int color = calculateHeatmapColor(totalOres);

            float densityFactor = Math.min(1.0f, totalOres / (float) Math.max(1, currentMax));
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
        int currentMax = maxOreCount.get();
        if (currentMax <= 1) {
            return COLOR_MID;
        }

        float t = oreCount / (float) currentMax;

        if (t < 0.5f) {
            return interpolateColor(COLOR_LOW, COLOR_MID, t * 2);
        } else {
            return interpolateColor(COLOR_MID, COLOR_HIGH, (t - 0.5f) * 2);
        }
    }

    private int interpolateColor(int color1, int color2, float ratio) {
        float t = Math.max(0, Math.min(1, ratio));

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
                new BlockPos(minX, POLYGON_Y_LEVEL, maxZ),
                new BlockPos(maxX + 1, POLYGON_Y_LEVEL, maxZ),
                new BlockPos(maxX + 1, POLYGON_Y_LEVEL, minZ),
                new BlockPos(minX, POLYGON_Y_LEVEL, minZ)
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

    /**
     * Reset the cache, reload tracked ores, delete cache file, and force rescan nearby chunks.
     */
    public void resetCache() {
        if (!ensureCorrectWorld()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        Level level = player.level();
        ResourceKey<Level> dimension = level.dimension();
        String dimKey = dimension.location().toString();

        // Reload tracked ores from config
        loadTrackedOres();

        // Clear internal caches
        dimensionOreCounts.clear();
        clearAllOverlays();
        maxOreCount.set(1);

        // Delete cache file
        Path cacheFile = getCacheFilePath();
        if (cacheFile != null && Files.exists(cacheFile)) {
            try {
                Files.delete(cacheFile);
                OreHeatmapMod.LOGGER.info("Deleted ore heatmap cache file for world: {}", currentWorldId);
            } catch (IOException e) {
                OreHeatmapMod.LOGGER.error("Failed to delete ore heatmap cache file", e);
            }
        }

        // Force rescan nearby chunks
        ChunkPos playerChunk = new ChunkPos(player.blockPosition());
        int radius = OreHeatmapConfig.SCAN_RADIUS.get();
        Map<String, Integer> oreCounts = dimensionOreCounts.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>());

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos cp = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                if (level.hasChunk(cp.x, cp.z)) {
                    int count = scanChunk(level, cp);
                    if (count >= 0) {
                        String key = cp.x + "," + cp.z;
                        oreCounts.put(key, count);
                        maxOreCount.updateAndGet(cur -> Math.max(cur, count));
                        OreHeatmapMod.LOGGER.debug("Rescanned chunk {},{}: {} ores", cp.x, cp.z, count);
                    }
                }
            }
        }

        // Immediately update overlays if enabled
        if (OreHeatmapConfig.ENABLED.get()) {
            updateOverlays(level, dimension, oreCounts, playerChunk, radius);
        }

        // Show feedback message
        if (player != null) {
            player.displayClientMessage(Component.literal("Ore heatmap cache reset and nearby chunks rescanned"), true);
        }

        OreHeatmapMod.LOGGER.info("Ore heatmap cache reset completed");
    }

}
