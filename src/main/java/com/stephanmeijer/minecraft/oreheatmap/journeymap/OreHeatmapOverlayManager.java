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

    // Background rescan state
    private boolean isRescanning = false;
    private ChunkPos rescanCenter;
    private int rescanRadius;
    private final Set<ChunkPos> pendingChunks = new HashSet<>();
    private int chunksScanned;
    private static final int CHUNKS_PER_TICK = 60; // ~1 second for typical radius on most PCs

    public OreHeatmapOverlayManager(IClientAPI jmAPI) {
        this.jmAPI = jmAPI;
        loadTrackedOres();
        initializeCacheDirectory();
    }

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
                String tagId = trimmed.substring(1);
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(tagId));
                trackedTags.add(tag);
                OreHeatmapMod.LOGGER.debug("Tracking ore tag: {}", tagId);
            } else {
                trackedBlocks.add(ResourceLocation.parse(trimmed));
            }
        }

        OreHeatmapMod.LOGGER.info("Loaded {} tracked blocks and {} tracked tags",
                trackedBlocks.size(), trackedTags.size());
    }

    private String getWorldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            Path worldPath = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            Path normalized = worldPath.toAbsolutePath().normalize();
            Path fileName = normalized.getFileName();
            if (fileName == null) return null;
            String folder = fileName.toString();
            return "local_" + folder.replaceAll("[^a-zA-Z0-9_()-]", "_");
        } else if (mc.getCurrentServer() != null) {
            return "server_" + mc.getCurrentServer().ip.replaceAll("[^a-zA-Z0-9]", "_");
        }
        return null;
    }

    private Path getCacheFilePath() {
        if (cacheDirectory == null || currentWorldId == null) return null;
        return cacheDirectory.resolve(currentWorldId + ".json");
    }

    private void loadCacheFromDisk() {
        cacheLoadFailed = false;
        Path cacheFile = getCacheFilePath();
        if (cacheFile == null || !Files.exists(cacheFile)) return;

        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            Type type = new TypeToken<Map<String, Map<String, Integer>>>(){}.getType();
            Map<String, Map<String, Integer>> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                dimensionOreCounts.clear();
                dimensionOreCounts.putAll(loaded);
                recalculateMaxOreCount();
                OreHeatmapMod.LOGGER.info("Loaded ore cache for world: {} ({} dimensions)", currentWorldId, dimensionOreCounts.size());
            }
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.error("Failed to load ore cache for world: {}", currentWorldId, e);
            cacheLoadFailed = true;
        }
    }

    private void saveCacheToDisk() {
        if (dimensionOreCounts.isEmpty()) return;
        Path cacheFile = getCacheFilePath();
        if (cacheFile == null) return;

        try (Writer writer = Files.newBufferedWriter(cacheFile)) {
            GSON.toJson(dimensionOreCounts, writer);
            OreHeatmapMod.LOGGER.debug("Saved ore cache for world: {}", currentWorldId);
        } catch (IOException e) {
            OreHeatmapMod.LOGGER.error("Failed to save ore cache", e);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        saveCacheToDisk();
        resetWorldState();
    }

    private void resetWorldState() {
        clearAllOverlays();
        dimensionOreCounts.clear();
        maxOreCount.set(1);
        currentWorldId = null;
        currentDimension = null;
        cacheLoadFailed = false;
        isRescanning = false;
    }

    private boolean ensureCorrectWorld() {
        String newWorldId = getWorldId();
        if (newWorldId == null) return false;

        if (currentWorldId == null) {
            currentWorldId = newWorldId;
            loadCacheFromDisk();
            OreHeatmapMod.LOGGER.info("Initialized ore heatmap for world: {}", currentWorldId);
            return true;
        }

        if (!currentWorldId.equals(newWorldId)) {
            OreHeatmapMod.LOGGER.info("World changed from {} to {} - switching data", currentWorldId, newWorldId);
            saveCacheToDisk();
            resetWorldState();
            currentWorldId = newWorldId;
            loadCacheFromDisk();
            return true;
        }

        return true;
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof Level level)) return;
        if (!ensureCorrectWorld()) return;

        LevelChunk chunk = (LevelChunk) event.getChunk();
        ChunkPos pos = chunk.getPos();

        String dimKey = level.dimension().location().toString();
        Map<String, Integer> counts = dimensionOreCounts.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>());

        String key = pos.x + "," + pos.z;
        if (!counts.containsKey(key)) {
            int count = scanChunk(level, pos);
            if (count > 0) {
                counts.put(key, count);
                maxOreCount.updateAndGet(current -> Math.max(current, count));
                OreHeatmapMod.LOGGER.debug("Scanned chunk {},{}: {} ores", pos.x, pos.z, count);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        if (!ensureCorrectWorld()) return;

        if (cacheLoadFailed) {
            OreHeatmapMod.LOGGER.warn("Ore heatmap cache corrupted for {} - starting fresh", currentWorldId);
            cacheLoadFailed = false;
        }

        Level level = player.level();
        ResourceKey<Level> dim = level.dimension();
        if (currentDimension == null || !currentDimension.equals(dim)) {
            currentDimension = dim;
            recalculateMaxOreCount();
            isRescanning = false;
        }

        tickCounter++;
        if (tickCounter >= OreHeatmapConfig.UPDATE_INTERVAL_TICKS.get()) {
            tickCounter = 0;

            saveCounter++;
            if (saveCounter >= Math.max(1, SAVE_INTERVAL / OreHeatmapConfig.UPDATE_INTERVAL_TICKS.get())) {
                saveCounter = 0;
                saveCacheToDisk();
            }

            boolean enabled = OreHeatmapConfig.ENABLED.get();
            String dimKey = dim.location().toString();
            Map<String, Integer> oreCounts = dimensionOreCounts.get(dimKey);

            if (enabled && oreCounts != null) {
                ChunkPos pChunk = new ChunkPos(player.blockPosition());
                int radius = calculateVisibleRadius();
                updateOverlays(level, dim, oreCounts, pChunk, radius);
            } else if (wasEnabled) {
                clearAllOverlays();
            }
            wasEnabled = enabled;
        }

        // Background rescan — runs every tick
        if (isRescanning) {
            processRescanBatch(level, dim);
        }
    }

    private void recalculateMaxOreCount() {
        int max = 1;
        for (Map<String, Integer> chunks : dimensionOreCounts.values()) {
            for (int count : chunks.values()) {
                max = Math.max(max, count);
            }
        }
        maxOreCount.set(max);
    }

    private int calculateVisibleRadius() {
        Minecraft mc = Minecraft.getInstance();
        int mcRadius = mc.options.renderDistance().get();

        try {
            UIState fs = jmAPI.getUIState(Context.UI.Fullscreen);
            if (fs != null && fs.active) {
                int zoom = fs.zoom;
                int blockRad = 128 >> zoom;
                return Math.max(4, (blockRad / 16) + 2);
            }

            UIState mm = jmAPI.getUIState(Context.UI.Minimap);
            if (mm != null) {
                int zoom = mm.zoom;
                int blockRad = 128 >> zoom;
                return Math.max(2, (blockRad / 16) + 1);
            }
        } catch (Exception ignored) {
            OreHeatmapMod.LOGGER.debug("Could not get JM UI state");
        }

        return mcRadius;
    }

    private int scanChunk(Level level, ChunkPos pos) {
        if (!level.hasChunk(pos.x, pos.z)) return -1;

        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        int count = 0;

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos bp = new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                    if (isTrackedOre(chunk.getBlockState(bp))) count++;
                }
            }
        }
        return count;
    }

    private boolean isTrackedOre(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (trackedBlocks.contains(id)) return true;
        for (TagKey<Block> tag : trackedTags) {
            if (state.is(tag)) return true;
        }
        return false;
    }

    private void updateOverlays(Level level, ResourceKey<Level> dim, Map<String, Integer> oreCounts,
                                ChunkPos center, int radius) {
        if (!OreHeatmapConfig.SHOW_OVERLAY_IN_CAVES.get()) {
            // Optional cave check
        }

        float maxOpacity = (float) (double) OreHeatmapConfig.OVERLAY_OPACITY.get();
        int currentMax = maxOreCount.get();

        Set<String> visible = new HashSet<>();
        List<Map.Entry<String, Integer>> snapshot = new ArrayList<>(oreCounts.entrySet());

        for (Map.Entry<String, Integer> entry : snapshot) {
            String key = entry.getKey();
            int ores = entry.getValue();

            String[] coords = key.split(",");
            if (coords.length != 2) continue;

            int cx, cz;
            try {
                cx = Integer.parseInt(coords[0]);
                cz = Integer.parseInt(coords[1]);
            } catch (NumberFormatException e) {
                OreHeatmapMod.LOGGER.warn("Invalid chunk key in cache: {}", key);
                continue;
            }

            ChunkPos cp = new ChunkPos(cx, cz);
            if (Math.max(Math.abs(cx - center.x), Math.abs(cz - center.z)) > radius) continue;

            visible.add(key);

            if (ores == 0) {
                PolygonOverlay ov = activeOverlays.remove(key);
                if (ov != null) jmAPI.remove(ov);
                continue;
            }

            int color = calculateHeatmapColor(ores);
            float density = Math.min(1.0f, ores / (float) Math.max(1, currentMax));
            float opacity = 0.2f + density * (maxOpacity - 0.2f);

            MapPolygon poly = createChunkPolygon(cp);

            ShapeProperties props = new ShapeProperties()
                    .setFillColor(color).setFillOpacity(opacity)
                    .setStrokeColor(color).setStrokeOpacity(Math.min(1.0f, opacity + 0.15f))
                    .setStrokeWidth(1.0f);

            PolygonOverlay overlay = activeOverlays.get(key);
            if (overlay == null) {
                overlay = new PolygonOverlay(OreHeatmapMod.MODID, dim, props, poly);
                overlay.setTitle("Ores: " + ores + " blocks");
                try {
                    jmAPI.show(overlay);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                activeOverlays.put(key, overlay);
            } else {
                overlay.setOuterArea(poly);
                overlay.setShapeProperties(props);
                overlay.setTitle("Ores: " + ores + " blocks");
                try {
                    jmAPI.show(overlay);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        activeOverlays.keySet().removeIf(k -> !visible.contains(k));
    }

    private int calculateHeatmapColor(int oreCount) {
        int max = maxOreCount.get();
        if (max <= 1) return COLOR_MID;

        float t = (float) oreCount / max;
        if (t < 0.5f) {
            return interpolateColor(COLOR_LOW, COLOR_MID, t * 2);
        } else {
            return interpolateColor(COLOR_MID, COLOR_HIGH, (t - 0.5f) * 2);
        }
    }

    private int interpolateColor(int c1, int c2, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((c1 >> 16) & 0xFF) + t * (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)));
        int g = (int) (((c1 >> 8) & 0xFF) + t * (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)));
        int b = (int) ((c1 & 0xFF) + t * ((c2 & 0xFF) - (c1 & 0xFF)));
        return (r << 16) | (g << 8) | b;
    }

    private MapPolygon createChunkPolygon(ChunkPos pos) {
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int maxX = pos.getMaxBlockX();
        int maxZ = pos.getMaxBlockZ();
        return new MapPolygon(
                new BlockPos(minX, POLYGON_Y_LEVEL, maxZ),
                new BlockPos(maxX + 1, POLYGON_Y_LEVEL, maxZ),
                new BlockPos(maxX + 1, POLYGON_Y_LEVEL, minZ),
                new BlockPos(minX, POLYGON_Y_LEVEL, minZ)
        );
    }

    public void clearAllOverlays() {
        for (PolygonOverlay ov : activeOverlays.values()) {
            try {
                jmAPI.remove(ov);
            } catch (Exception e) {
                OreHeatmapMod.LOGGER.debug("Failed to remove overlay during clear: {}", e.getMessage());
            }
        }
        activeOverlays.clear();
    }

    public void resetCache() {
        if (!ensureCorrectWorld()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        Level level = player.level();
        ResourceKey<Level> dim = level.dimension();
        String dimKey = dim.location().toString();

        loadTrackedOres();

        dimensionOreCounts.clear();
        clearAllOverlays();
        maxOreCount.set(1);

        Path cacheFile = getCacheFilePath();
        if (cacheFile != null && Files.exists(cacheFile)) {
            try {
                Files.delete(cacheFile);
                OreHeatmapMod.LOGGER.info("Deleted ore heatmap cache file for world: {}", currentWorldId);
            } catch (IOException e) {
                OreHeatmapMod.LOGGER.error("Failed to delete ore heatmap cache file", e);
            }
        }

        rescanRadius = calculateVisibleRadius() + 2;
        rescanCenter = new ChunkPos(player.blockPosition());
        pendingChunks.clear();

        for (int dx = -rescanRadius; dx <= rescanRadius; dx++) {
            for (int dz = -rescanRadius; dz <= rescanRadius; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= rescanRadius) {
                    pendingChunks.add(new ChunkPos(rescanCenter.x + dx, rescanCenter.z + dz));
                }
            }
        }

        chunksScanned = 0;
        isRescanning = true;

        player.displayClientMessage(Component.literal("Ore heatmap reset! Background circular rescan started (radius " + rescanRadius + ")..."), true);
        OreHeatmapMod.LOGGER.info("Cache reset; background rescan started ({} chunks queued)", pendingChunks.size());
    }

    private void processRescanBatch(Level level, ResourceKey<Level> dim) {
        String dimKey = dim.location().toString();
        Map<String, Integer> oreCounts = dimensionOreCounts.get(dimKey);
        if (oreCounts == null) {
            isRescanning = false;
            return;
        }

        int batchSize = Math.min(CHUNKS_PER_TICK, pendingChunks.size());
        List<ChunkPos> batch = new ArrayList<>(pendingChunks).subList(0, batchSize);
        pendingChunks.removeAll(batch);

        int batchScanned = 0;
        for (ChunkPos cp : batch) {
            if (level.hasChunk(cp.x, cp.z)) {
                int count = scanChunk(level, cp);
                if (count > 0) {
                    String key = cp.x + "," + cp.z;
                    oreCounts.put(key, count);
                    maxOreCount.updateAndGet(cur -> Math.max(cur, count));
                    batchScanned++;
                }
            }
        }

        chunksScanned += batchScanned;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            int progress = pendingChunks.isEmpty() ? 100 : (int) ((chunksScanned / (float) (chunksScanned + pendingChunks.size())) * 100);
            player.displayClientMessage(Component.literal("Rescan progress: " + progress + "%"), true);
        }

        if (pendingChunks.isEmpty()) {
            isRescanning = false;
            if (OreHeatmapConfig.ENABLED.get()) {
                updateOverlays(level, dim, oreCounts, rescanCenter, rescanRadius);
            }
            if (player != null) {
                player.displayClientMessage(Component.literal("Background rescan complete!"), true);
            }
            OreHeatmapMod.LOGGER.info("Background rescan finished");
        }
    }
}
