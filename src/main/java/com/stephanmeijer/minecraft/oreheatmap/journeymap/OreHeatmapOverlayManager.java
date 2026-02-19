package com.stephanmeijer.minecraft.oreheatmap.journeymap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
    private int activeOverlaySlot = OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.get();
    public Map<String, Integer> currentOreCounts = new ConcurrentHashMap<>();
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

    // === NEW: Per-slot tracking & caches (this fixes all 3 issues) ===
    private final Map<Integer, Map<String, Integer>> slotOreCounts = new ConcurrentHashMap<>();
    private final Map<Integer, Set<ResourceLocation>> slotTrackedBlocks = new HashMap<>();
    private final Map<Integer, Set<TagKey<Block>>> slotTrackedTags = new HashMap<>();

    // Y-coordinate for overlay polygon plane
    private static final int POLYGON_Y_LEVEL = 64;

    private int tickCounter;
    private int saveCounter;
    private static final int SAVE_INTERVAL = 600; // Save every 30 seconds (600 ticks)

    private ResourceKey<Level> currentDimension;
    public String currentWorldId;
    private boolean wasEnabled;  // Track previous enabled state
    private boolean cacheLoadFailed;  // Track if cache failed to load
    public Path cacheDirectory;  // Cached directory path

    // Background rescan state
    private boolean isRescanning = false;
    private ChunkPos rescanCenter;
    private int rescanRadius;
    private final Set<ChunkPos> pendingChunks = new HashSet<>();
    private int chunksScanned;

    public OreHeatmapOverlayManager(IClientAPI jmAPI) {
        this.jmAPI = jmAPI;
        loadAllTrackedOres();
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

    public void loadAllTrackedOres() {
        slotTrackedBlocks.clear();
        slotTrackedTags.clear();

        loadTrackedForSlot(1, OreHeatmapConfig.TRACKED_ORES.get());
        loadTrackedForSlot(2, OreHeatmapConfig.TRACKED_ORES2.get());
        loadTrackedForSlot(3, OreHeatmapConfig.TRACKED_ORES3.get());
        loadTrackedForSlot(4, OreHeatmapConfig.TRACKED_ORES4.get());
        loadTrackedForSlot(5, OreHeatmapConfig.TRACKED_ORES5.get());

        OreHeatmapMod.LOGGER.info("Loaded tracked ores for all 5 slots");
    }

    private void loadTrackedForSlot(int slot, List<? extends String> list) {
        Set<ResourceLocation> blocks = new HashSet<>();
        Set<TagKey<Block>> tags = new HashSet<>();

        for (String entry : list) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("#")) {
                String tagId = trimmed.substring(1);
                tags.add(TagKey.create(Registries.BLOCK, ResourceLocation.parse(tagId)));
            } else {
                blocks.add(ResourceLocation.parse(trimmed));
            }
        }

        slotTrackedBlocks.put(slot, blocks);
        slotTrackedTags.put(slot, tags);
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
        if (cacheDirectory == null || currentWorldId == null) {
            return null;
        }
        // overlay1.json, overlay2.json, etc.
        String fileName = "overlay" + activeOverlaySlot + ".json";
        return cacheDirectory.resolve(currentWorldId).resolve(fileName);
    }

    public void loadCacheFromDisk() {
        cacheLoadFailed = false;

        Path cacheFile = getCacheFilePath();
        if (cacheFile == null) {
            OreHeatmapMod.LOGGER.debug("Cache file path unavailable for slot {}", activeOverlaySlot);
            currentOreCounts.clear();
            return;
        }

        try {
            Files.createDirectories(cacheFile.getParent());
        } catch (IOException e) {
            OreHeatmapMod.LOGGER.error("Failed to create world cache directory", e);
            cacheLoadFailed = true;
            currentOreCounts.clear();
            return;
        }

        if (!Files.exists(cacheFile)) {
            OreHeatmapMod.LOGGER.debug("No cache file found for slot {}: {}", activeOverlaySlot, cacheFile);
            currentOreCounts.clear();
            return;
        }

        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            Type type = new TypeToken<Map<String, Map<String, Integer>>>(){}.getType();
            Map<String, Map<String, Integer>> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                currentOreCounts.clear();
                String dimKey = Minecraft.getInstance().level.dimension().location().toString();
                currentOreCounts.putAll(loaded.getOrDefault(dimKey, new ConcurrentHashMap<>()));
                recalculateMaxOreCount();
                OreHeatmapMod.LOGGER.info("Loaded cache for slot {}: {} chunks", activeOverlaySlot, currentOreCounts.size());
            }
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.error("Failed to load cache for slot {}: {}", activeOverlaySlot, cacheFile, e);
            cacheLoadFailed = true;
            currentOreCounts.clear();
        }
    }

    private void saveCacheToDisk() {
        if (currentOreCounts.isEmpty()) return;

        Path cacheFile = getCacheFilePath();
        if (cacheFile == null) {
            OreHeatmapMod.LOGGER.debug("Cannot save cache: file path unavailable for slot {}", activeOverlaySlot);
            return;
        }

        // Ensure parent directory exists
        try {
            Files.createDirectories(cacheFile.getParent());
        } catch (IOException e) {
            OreHeatmapMod.LOGGER.error("Failed to create world cache directory for save", e);
            return;
        }

        // Wrap current dimension data
        String dimKey = currentDimension.location().toString();
        Map<String, Map<String, Integer>> toSave = new HashMap<>();
        toSave.put(dimKey, new HashMap<>(currentOreCounts));

        try (Writer writer = Files.newBufferedWriter(cacheFile)) {
            GSON.toJson(toSave, writer);
            OreHeatmapMod.LOGGER.debug("Saved cache for slot {}: {} chunks", activeOverlaySlot, currentOreCounts.size());
        } catch (IOException e) {
            OreHeatmapMod.LOGGER.error("Failed to save cache for slot {}: {}", activeOverlaySlot, cacheFile, e);
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
        pendingChunks.clear();
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
        String key = pos.x + "," + pos.z;

        // Scan once, then distribute count to every configured slot
        for (int slot = 1; slot <= 5; slot++) {
            if (slotTrackedBlocks.get(slot).isEmpty() && slotTrackedTags.get(slot).isEmpty()) continue; // skip blank slots

            int count = scanChunkForSlot(level, pos, slot);
            if (count > 0) {
                Map<String, Integer> cache = slotOreCounts.computeIfAbsent(slot, k -> new ConcurrentHashMap<>());
                cache.put(key, count);

                // If this is the currently displayed slot, update live max
                if (slot == activeOverlaySlot) {
                    currentOreCounts = cache;
                    maxOreCount.updateAndGet(cur -> Math.max(cur, count));
                }

                OreHeatmapMod.LOGGER.debug("onChunkLoad: Slot {} chunk {},{} → {} ores", slot, pos.x, pos.z, count);
            }
        }
    }

    private int scanChunkForSlot(Level level, ChunkPos pos, int slot) {
        if (!level.hasChunk(pos.x, pos.z)) return 0;

        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        int count = 0;

        Set<ResourceLocation> blocks = slotTrackedBlocks.get(slot);
        Set<TagKey<Block>> tags = slotTrackedTags.get(slot);

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos bp = new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                    BlockState state = chunk.getBlockState(bp);

                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if (blocks.contains(id)) {
                        count++;
                        continue;
                    }
                    for (TagKey<Block> tag : tags) {
                        if (state.is(tag)) {
                            count++;
                            break;
                        }
                    }
                }
            }
        }
        return count;
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
            Map<String, Integer> oreCounts = currentOreCounts;

            if (enabled && oreCounts != null) {
                ChunkPos pChunk = new ChunkPos(player.blockPosition());
                int radius = calculateVisibleRadius();
                updateOverlays(level, dim, oreCounts, pChunk, radius);
            } else if (wasEnabled) {
                clearAllOverlays();
            }
            wasEnabled = enabled;
        }

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

    public int calculateVisibleRadius() {
        Minecraft mc = Minecraft.getInstance();
        int mcRadius = mc.options.renderDistance().get();

        try {
            UIState mm = jmAPI.getUIState(Context.UI.Minimap);
            if (mm != null) {
                int zoom = mm.zoom;
                int blockRad = 128 >> zoom;
                int radius = Math.max(2, (blockRad / 16) + 1);
                OreHeatmapMod.LOGGER.debug("calculateVisibleRadius: Using minimap JM radius: {}", radius);
                return radius;
            }
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.debug("Could not get minimap state - falling back to MC render distance", e);
        }

        OreHeatmapMod.LOGGER.debug("calculateVisibleRadius: Using MC render distance: {}", mcRadius);
        return mcRadius;
    }

    private int scanChunk(Level level, ChunkPos pos) {
        if (!level.hasChunk(pos.x, pos.z)) {
            OreHeatmapMod.LOGGER.debug("scanChunk: Chunk {},{} not loaded - skipped", pos.x, pos.z);
            return -1;
        }

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

        OreHeatmapMod.LOGGER.debug("scanChunk: Scanned chunk {},{} → {} ores found", pos.x, pos.z, count);
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

    public void updateOverlays(Level level, ResourceKey<Level> dim, Map<String, Integer> oreCounts,
                               ChunkPos center, int radius) {
        float maxOpacity = (float) (double) OreHeatmapConfig.OVERLAY_OPACITY.get();
        int currentMax = maxOreCount.get();

        Set<String> visibleKeys = new HashSet<>();

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
                        OreHeatmapMod.LOGGER.debug("updateOverlays: Failed to remove 0-ore overlay: {}", e.getMessage());
                    }
                }
                continue;
            }

            String[] parts = chunkKey.split(",");
            if (parts.length != 2) {
                OreHeatmapMod.LOGGER.warn("updateOverlays: Invalid chunk key format: {}", chunkKey);
                continue;
            }

            ChunkPos chunkPos;
            try {
                chunkPos = new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                OreHeatmapMod.LOGGER.warn("updateOverlays: Invalid chunk coordinates in key: {}", chunkKey);
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
                overlay = new PolygonOverlay(OreHeatmapMod.MODID, dim, shapeProps, polygon);
                overlay.setTitle("Ores: " + totalOres + " blocks");

                try {
                    jmAPI.show(overlay);
                    activeOverlays.put(chunkKey, overlay);
                } catch (Exception e) {
                    OreHeatmapMod.LOGGER.error("updateOverlays: Failed to show overlay: {}", e.getMessage());
                }
            } else {
                overlay.setOuterArea(polygon);
                overlay.setShapeProperties(shapeProps);
                overlay.setTitle("Ores: " + totalOres + " blocks");

                try {
                    jmAPI.show(overlay);
                } catch (Exception e) {
                    OreHeatmapMod.LOGGER.debug("updateOverlays: Failed to update overlay: {}", e.getMessage());
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
                    OreHeatmapMod.LOGGER.debug("updateOverlays: Failed to remove overlay: {}", e.getMessage());
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
                OreHeatmapMod.LOGGER.debug("clearAllOverlays: Failed to remove overlay: {}", e.getMessage());
            }
        }
        activeOverlays.clear();
    }
    public void resetCacheForSlot(int slot) {
        Path cacheFile = getCacheFilePathForSlot(slot);   // we'll make this method public too
        if (cacheFile != null && Files.exists(cacheFile)) {
            try {
                Files.delete(cacheFile);
                OreHeatmapMod.LOGGER.info("Deleted cache for slot {}", slot);
            } catch (IOException e) {
                OreHeatmapMod.LOGGER.error("Failed to delete cache for slot {}", slot, e);
            }
        }
    }

    // Make this one public too
    public Path getCacheFilePathForSlot(int slot) {
        if (cacheDirectory == null || currentWorldId == null) return null;
        String fileName = "overlay" + slot + ".json";
        return cacheDirectory.resolve(currentWorldId).resolve(fileName);
    }
    public void resetCache() {
        if (!ensureCorrectWorld()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        Level level = player.level();
        ResourceKey<Level> dimension = level.dimension();
        String dimKey = dimension.location().toString();

        loadAllTrackedOres();

        // Clear only current slot's data
        currentOreCounts.clear();
        clearAllOverlays();
        maxOreCount.set(1);

        Path cacheFile = getCacheFilePath();
        if (cacheFile != null && Files.exists(cacheFile)) {
            try {
                Files.delete(cacheFile);
                OreHeatmapMod.LOGGER.info("Deleted cache file for slot {}: {}", activeOverlaySlot, cacheFile);
            } catch (IOException e) {
                OreHeatmapMod.LOGGER.error("Failed to delete cache file for slot {}", activeOverlaySlot, e);
            }
        }

        // Start background rescan for current slot
        rescanRadius = calculateVisibleRadius() + 2;
        rescanCenter = new ChunkPos(player.blockPosition());
        pendingChunks.clear();

        int queued = 0;
        for (int dx = -rescanRadius; dx <= rescanRadius; dx++) {
            for (int dz = -rescanRadius; dz <= rescanRadius; dz++) {
                if (Math.hypot(dx, dz) <= rescanRadius) {
                    ChunkPos cp = new ChunkPos(rescanCenter.x + dx, rescanCenter.z + dz);
                    pendingChunks.add(cp);
                    queued++;
                }
            }
        }

        chunksScanned = 0;
        isRescanning = true;

        player.displayClientMessage(Component.literal("Reset Overlay " + activeOverlaySlot + "! Background rescan started..."), true);
        OreHeatmapMod.LOGGER.info("ResetCache: Started for slot {} | radius={} | queued={} chunks", activeOverlaySlot, rescanRadius, queued);
    }
    public void cycleOverlay() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        int originalSlot = activeOverlaySlot;

        // Cycle: 0 (off) → 1 → 2 → 3 → 4 → 5 → 0 …
        do {
            activeOverlaySlot++;
            if (activeOverlaySlot > 5) {
                activeOverlaySlot = 0;
            }

            // Safety: prevent infinite loop if somehow broken
            if (activeOverlaySlot == originalSlot && activeOverlaySlot != 0) {
                activeOverlaySlot = 0;
                break;
            }

            // If we landed on slot 0 (off) or a configured slot → stop cycling
            if (activeOverlaySlot == 0 || isSlotConfigured(activeOverlaySlot)) {
                break;
            }
        } while (true);

        OreHeatmapMod.LOGGER.debug("Cycling to slot {}", activeOverlaySlot);

        if (activeOverlaySlot == 0) {
            // === OFF STATE ===
            OreHeatmapConfig.ENABLED.set(false);
            clearAllOverlays();
            player.displayClientMessage(Component.literal("Heatmap disabled"), true);
        } else {
            // === NORMAL SLOT ===
            OreHeatmapConfig.ENABLED.set(true);

            currentOreCounts = slotOreCounts.computeIfAbsent(activeOverlaySlot, k -> new ConcurrentHashMap<>());
            recalculateMaxOreCountForActiveSlot();

            // Rebuild the visible heatmap
            Level level = player.level();
            ResourceKey<Level> dim = level.dimension();
            ChunkPos pChunk = new ChunkPos(player.blockPosition());
            int radius = calculateVisibleRadius();
            updateOverlays(level, dim, currentOreCounts, pChunk, radius);

            player.displayClientMessage(Component.literal("Switched to Overlay " + activeOverlaySlot), true);
        }

        OreHeatmapMod.LOGGER.info("Cycled to slot {}", activeOverlaySlot);
    }

    private boolean isSlotConfigured(int slot) {
        Set<ResourceLocation> blocks = slotTrackedBlocks.getOrDefault(slot, Set.of());
        Set<TagKey<Block>> tags   = slotTrackedTags.getOrDefault(slot, Set.of());
        return !blocks.isEmpty() || !tags.isEmpty();
    }
    public void recalculateMaxOreCountForActiveSlot() {
        int max = 1;
        for (int c : currentOreCounts.values()) {
            max = Math.max(max, c);
        }
        maxOreCount.set(max);
    }
    private void processRescanBatch(Level level, ResourceKey<Level> dimension) {
        String dimKey = dimension.location().toString();
        Map<String, Integer> oreCounts = currentOreCounts;
        if (oreCounts == null) {
            OreHeatmapMod.LOGGER.warn("processRescanBatch: No oreCounts map for dimension {} - stopping", dimKey);
            isRescanning = false;
            return;
        }

        if (pendingChunks.isEmpty()) {
            finishRescan(level, dimension);
            return;
        }

        int batchSize = Math.min(OreHeatmapConfig.RESCAN_CHUNKS_PER_TICK.get(), pendingChunks.size());
        List<ChunkPos> batch = new ArrayList<>(pendingChunks).subList(0, batchSize);
        pendingChunks.removeAll(batch);

        OreHeatmapMod.LOGGER.debug("processRescanBatch: Starting batch of {} chunks (remaining: {})", batchSize, pendingChunks.size());

        int batchScanned = 0;
        int loadedZero = 0;
        int notLoaded = 0;

        for (ChunkPos cp : batch) {
            String key = cp.x + "," + cp.z;

            if (level.hasChunk(cp.x, cp.z)) {
                int count = scanChunk(level, cp);
                if (count > 0) {
                    oreCounts.put(key, count);
                    maxOreCount.updateAndGet(cur -> Math.max(cur, count));
                    batchScanned++;
                    OreHeatmapMod.LOGGER.debug("processRescanBatch: Scanned & saved chunk {},{} → {} ores", cp.x, cp.z, count);
                } else {
                    loadedZero++;
                    OreHeatmapMod.LOGGER.debug("processRescanBatch: Chunk {},{} loaded but 0 ores - not cached", cp.x, cp.z);
                }
            } else {
                notLoaded++;
                OreHeatmapMod.LOGGER.debug("processRescanBatch: Chunk {},{} not loaded yet - still pending", cp.x, cp.z);
            }
        }

        chunksScanned += batchScanned;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            int progress = pendingChunks.isEmpty() ? 100 : (int) ((chunksScanned / (float) (chunksScanned + pendingChunks.size())) * 100);
            player.displayClientMessage(Component.literal("Rescan progress: " + progress + "% (" + chunksScanned + " done)"), true);
        }

        OreHeatmapMod.LOGGER.debug("processRescanBatch: Batch complete | scanned={} | loaded-zero={} | not-loaded={} | remaining={}", batchScanned, loadedZero, notLoaded, pendingChunks.size());

        if (pendingChunks.isEmpty()) {
            finishRescan(level, dimension);
        }
    }

    private void finishRescan(Level level, ResourceKey<Level> dimension) {
        isRescanning = false;

        if (OreHeatmapConfig.ENABLED.get()) {
            ChunkPos playerChunk = new ChunkPos(Minecraft.getInstance().player.blockPosition());
            int visibleRadius = calculateVisibleRadius();
            updateOverlays(level, dimension, currentOreCounts, playerChunk, visibleRadius);
        }

        saveCacheToDisk();

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal("Background rescan complete!"), true);
        }

        OreHeatmapMod.LOGGER.info("Background rescan finished | total scanned: {}", chunksScanned);
    }
}
