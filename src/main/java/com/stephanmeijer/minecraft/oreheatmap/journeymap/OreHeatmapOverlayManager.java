package com.stephanmeijer.minecraft.oreheatmap.journeymap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
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
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Manages ore scanning and JourneyMap overlay rendering.
 */
public class OreHeatmapOverlayManager {

    // ==================== Core State ====================
    public int activeOverlaySlot = OreHeatmapConfig.ACTIVE_OVERLAY_SLOT.get();
    public Map<String, Integer> currentOreCounts = new ConcurrentHashMap<>();

    private final IClientAPI jmAPI;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Per-slot data
    private final Map<Integer, Map<String, Integer>> slotOreCounts = new ConcurrentHashMap<>();
    private final Map<Integer, Set<ResourceLocation>> slotTrackedBlocks = new HashMap<>();
    private final Map<Integer, Set<TagKey<Block>>> slotTrackedTags = new HashMap<>();

    // Color scaling
    private final AtomicInteger maxOreCount = new AtomicInteger(1);
    private static final int COLOR_LOW = 0xFFFFE0;
    private static final int COLOR_MID = 0xFF8C00;
    private static final int COLOR_HIGH = 0x8B0000;

    // Overlay rendering
    private final Map<String, PolygonOverlay> activeOverlays = new ConcurrentHashMap<>();
    private static final int POLYGON_Y_LEVEL = 64;

    // World & cache
    public String currentWorldId;
    public Path cacheDirectory;
    private ResourceKey<Level> currentDimension;
    private boolean cacheLoadFailed;

    // Tick & save
    private int tickCounter;
    private int saveCounter;
    private static final int SAVE_INTERVAL = 1200; // 60 seconds

    // Background rescan
    private boolean isRescanning = false;
    private final Set<ChunkPos> pendingChunks = new HashSet<>();
    private int chunksScanned;
    private boolean wasEnabled;

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

    // ==================== Tracked Ores ====================

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
            if (trimmed.isEmpty()) {
                continue;
            }

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

    private boolean isSlotConfigured(int slot) {
        return !slotTrackedBlocks.getOrDefault(slot, Set.of()).isEmpty() ||
                !slotTrackedTags.getOrDefault(slot, Set.of()).isEmpty();
    }

    // ==================== World & Cache ====================

    private String getWorldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            Path worldPath = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            Path normalized = worldPath.toAbsolutePath().normalize();
            Path fileName = normalized.getFileName();
            if (fileName == null) {
                return null;
            }
            String folder = fileName.toString();
            return "local_" + folder.replaceAll("[^a-zA-Z0-9_()-]", "_");
        } else if (mc.getCurrentServer() != null) {
            return "server_" + mc.getCurrentServer().ip.replaceAll("[^a-zA-Z0-9]", "_");
        }
        return null;
    }

    private Path getCacheFilePath(int slot) {
        if (cacheDirectory == null || currentWorldId == null) {
            return null;
        }
        return cacheDirectory.resolve(currentWorldId).resolve("overlay" + slot + ".json");
    }

    public void loadCacheFromDisk() {
        cacheLoadFailed = false;
        Path cacheFile = getCacheFilePath(activeOverlaySlot);

        if (cacheFile == null || !Files.exists(cacheFile)) {
            currentOreCounts.clear();
            return;
        }

        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            Type type = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
            Map<String, Map<String, Integer>> loaded = GSON.fromJson(reader, type);

            if (loaded != null) {
                currentOreCounts.clear();
                String dimKey = Minecraft.getInstance().level.dimension().location().toString();
                currentOreCounts.putAll(loaded.getOrDefault(dimKey, new ConcurrentHashMap<>()));
                recalculateMaxOreCountForActiveSlot();
                OreHeatmapMod.LOGGER.info("Loaded cache for slot {}: {} chunks", activeOverlaySlot, currentOreCounts.size());
            }
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.error("Failed to load cache for slot {}", activeOverlaySlot, e);
            cacheLoadFailed = true;
            currentOreCounts.clear();
        }
    }

    private void saveCacheToDisk() {
        if (currentOreCounts.isEmpty()) {
            return;
        }

        Path cacheFile = getCacheFilePath(activeOverlaySlot);
        if (cacheFile == null) {
            return;
        }

        try {
            Files.createDirectories(cacheFile.getParent());
            String dimKey = currentDimension.location().toString();
            Map<String, Map<String, Integer>> toSave = Map.of(dimKey, new HashMap<>(currentOreCounts));

            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(toSave, writer);
            }
        } catch (IOException e) {
            OreHeatmapMod.LOGGER.error("Failed to save cache for slot {}", activeOverlaySlot, e);
        }
    }

    private boolean ensureCorrectWorld() {
        String newWorldId = getWorldId();
        if (newWorldId == null) {
            return false;
        }

        if (!newWorldId.equals(currentWorldId)) {
            if (currentWorldId != null) {
                saveCacheToDisk();
            }
            currentWorldId = newWorldId;
            loadCacheFromDisk();
            OreHeatmapMod.LOGGER.info("Switched to world: {}", currentWorldId);
        }
        return true;
    }

    // ==================== Scanning ====================

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!OreHeatmapConfig.ENABLED.get()) {
            return;
        }
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof Level level)) {
            return;
        }
        if (!ensureCorrectWorld()) {
            return;
        }

        LevelChunk chunk = (LevelChunk) event.getChunk();
        ChunkPos pos = chunk.getPos();
        String key = pos.x + "," + pos.z;

        for (int slot = 1; slot <= 5; slot++) {
            if (!isSlotConfigured(slot)) {
                continue;
            }

            int count = scanChunkForSlot(level, pos, slot);
            if (count > 0) {
                Map<String, Integer> cache = slotOreCounts.computeIfAbsent(slot, k -> new ConcurrentHashMap<>());
                cache.put(key, count);

                if (slot == activeOverlaySlot) {
                    currentOreCounts = cache;
                    maxOreCount.updateAndGet(cur -> Math.max(cur, count));
                }
            }
        }
    }

    private int scanChunkForSlot(Level level, ChunkPos pos, int slot) {
        if (!level.hasChunk(pos.x, pos.z)) {
            return 0;
        }

        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        Set<ResourceLocation> blocks = slotTrackedBlocks.get(slot);
        Set<TagKey<Block>> tags = slotTrackedTags.get(slot);

        int count = 0;
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

    // ==================== Tick & Rendering ====================

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }
        if (!OreHeatmapConfig.ENABLED.get()) {
            if (wasEnabled) {
                clearAllOverlays();
            }
            wasEnabled = false;
            return;
        }
        if (!ensureCorrectWorld()) {
            return;
        }

        if (cacheLoadFailed) {
            cacheLoadFailed = false;
        }

        Level level = player.level();
        ResourceKey<Level> dim = level.dimension();

        if (currentDimension == null || !currentDimension.equals(dim)) {
            currentDimension = dim;
            recalculateMaxOreCountForActiveSlot();
        }

        tickCounter++;
        if (tickCounter >= OreHeatmapConfig.UPDATE_INTERVAL_TICKS.get()) {
            tickCounter = 0;

            saveCounter++;
            if (saveCounter >= Math.max(1, SAVE_INTERVAL / OreHeatmapConfig.UPDATE_INTERVAL_TICKS.get())) {
                saveCounter = 0;
                saveCacheToDisk();
            }

            if (currentOreCounts != null) {
                updateOverlays(dim, currentOreCounts);
            }
            wasEnabled = true;
        }

        if (isRescanning) {
            processRescanBatch(level, dim);
        }
    }

    public void updateOverlays(ResourceKey<Level> dim, Map<String, Integer> oreCounts) {
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
                    jmAPI.remove(existing);
                }
                continue;
            }

            String[] parts = chunkKey.split(",");
            if (parts.length != 2) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));

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
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                activeOverlays.put(chunkKey, overlay);
            } else {
                overlay.setOuterArea(polygon);
                overlay.setShapeProperties(shapeProps);
                overlay.setTitle("Ores: " + totalOres + " blocks");
                try {
                    jmAPI.show(overlay);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // Remove out-of-range overlays
        activeOverlays.keySet().removeIf(key -> !visibleKeys.contains(key));
    }

    private int calculateHeatmapColor(int oreCount) {
        int currentMax = maxOreCount.get();
        if (currentMax <= 1) {
            return COLOR_MID;
        }

        float t = oreCount / (float) currentMax;
        return t < 0.5f ?
                interpolateColor(COLOR_LOW, COLOR_MID, t * 2) :
                interpolateColor(COLOR_MID, COLOR_HIGH, (t - 0.5f) * 2);
    }

    private int interpolateColor(int c1, int c2, float ratio) {
        float t = Math.max(0, Math.min(1, ratio));
        int r = (int) (((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
        int g = (int) (((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
        int b = (int) ((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
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

    public int calculateVisibleRadius() {
        int radius = Minecraft.getInstance().options.renderDistance().get();
        OreHeatmapMod.LOGGER.debug("calculateVisibleRadius: {}", radius);
        return radius;
    }

    // ==================== Public API ====================

    public void resetCacheForSlot(int slot) {
        Path cacheFile = getCacheFilePath(slot);
        if (cacheFile != null && Files.exists(cacheFile)) {
            try {
                Files.delete(cacheFile);
                OreHeatmapMod.LOGGER.info("Deleted cache for slot {}", slot);
            } catch (IOException e) {
                OreHeatmapMod.LOGGER.error("Failed to delete cache for slot {}", slot, e);
            }
        }
    }

    public void resetCache() {
        if (!ensureCorrectWorld()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        loadAllTrackedOres();
        currentOreCounts.clear();
        clearAllOverlays();
        maxOreCount.set(1);

        Path cacheFile = getCacheFilePath(activeOverlaySlot);
        if (cacheFile != null && Files.exists(cacheFile)) {
            try {
                Files.delete(cacheFile);
                OreHeatmapMod.LOGGER.info("Deleted cache file for slot {}: {}", activeOverlaySlot, cacheFile);
            } catch (IOException e) {
                OreHeatmapMod.LOGGER.error("Failed to delete cache file for slot {}", activeOverlaySlot, e);
            }
        }

        // Start background rescan
        int rescanRadius = calculateVisibleRadius() + 2;
        ChunkPos rescanCenter = new ChunkPos(player.blockPosition());
        pendingChunks.clear();

        for (int dx = -rescanRadius; dx <= rescanRadius; dx++) {
            for (int dz = -rescanRadius; dz <= rescanRadius; dz++) {
                if (Math.hypot(dx, dz) <= rescanRadius) {
                    pendingChunks.add(new ChunkPos(rescanCenter.x + dx, rescanCenter.z + dz));
                }
            }
        }

        chunksScanned = 0;
        isRescanning = true;

        player.displayClientMessage(Component.literal("Reset Overlay " + activeOverlaySlot + "! Background rescan started..."), true);
    }

    public void cycleOverlay() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

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
            ResourceKey<Level> dim;
            try (Level level = player.level()) {
                dim = level.dimension();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            updateOverlays(dim, currentOreCounts);

            player.displayClientMessage(Component.literal("Switched to Overlay " + activeOverlaySlot), true);
        }

        OreHeatmapMod.LOGGER.info("Cycled to slot {}", activeOverlaySlot);
    }

    public void recalculateMaxOreCountForActiveSlot() {
        int max = 1;
        for (int c : currentOreCounts.values()) {
            max = Math.max(max, c);
        }
        maxOreCount.set(max);
    }

    private void processRescanBatch(Level level, ResourceKey<Level> dimension) {
        if (!OreHeatmapConfig.ENABLED.get()) {
            isRescanning = false;
            return;
        }

        //  String dimKey = dimension.location().toString();
        Map<String, Integer> oreCounts = currentOreCounts;
        if (oreCounts == null) {
            OreHeatmapMod.LOGGER.warn("processRescanBatch: No oreCounts map - stopping");
            isRescanning = false;
            return;
        }

        if (pendingChunks.isEmpty()) {
            finishRescan(dimension);
            return;
        }

        int batchSize = Math.min(OreHeatmapConfig.RESCAN_CHUNKS_PER_TICK.get(), pendingChunks.size());
        List<ChunkPos> batch = new ArrayList<>(pendingChunks).subList(0, batchSize);
        pendingChunks.removeAll(batch);

        OreHeatmapMod.LOGGER.debug("processRescanBatch: Starting batch of {} chunks (remaining: {})", batchSize, pendingChunks.size());

        int batchScanned = 0;

        for (ChunkPos cp : batch) {
            String key = cp.x + "," + cp.z;

            if (level.hasChunk(cp.x, cp.z)) {
                // ← CHANGED: Use the correct per-slot scanner for the active overlay
                int count = scanChunkForSlot(level, cp, activeOverlaySlot);

                if (count > 0) {
                    oreCounts.put(key, count);
                    maxOreCount.updateAndGet(cur -> Math.max(cur, count));
                    batchScanned++;
                    OreHeatmapMod.LOGGER.debug("processRescanBatch: Scanned & saved chunk {},{} → {} ores (slot {})",
                            cp.x, cp.z, count, activeOverlaySlot);
                } else {
                    OreHeatmapMod.LOGGER.debug("processRescanBatch: Chunk {},{} loaded but 0 ores", cp.x, cp.z);
                }
            } else {
                OreHeatmapMod.LOGGER.debug("processRescanBatch: Chunk {},{} not loaded yet", cp.x, cp.z);
            }
        }

        chunksScanned += batchScanned;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            int progress = pendingChunks.isEmpty() ? 100 : (int) ((chunksScanned / (float) (chunksScanned + pendingChunks.size())) * 100);
            player.displayClientMessage(Component.literal("Rescan progress: " + progress + "% (" + chunksScanned + " done)"), true);
        }

        if (pendingChunks.isEmpty()) {
            finishRescan(dimension);
        }
    }

    private void finishRescan(ResourceKey<Level> dimension) {
        isRescanning = false;
        recalculateMaxOreCountForActiveSlot();  // Update color scale

        if (OreHeatmapConfig.ENABLED.get()) {
            updateOverlays(dimension, currentOreCounts);
            OreHeatmapMod.LOGGER.debug("finishRescan: Refreshed overlays for slot {}", activeOverlaySlot);
        } else {
            clearAllOverlays();
        }

        saveCacheToDisk();

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal("Background rescan complete!"), true);
        }

        OreHeatmapMod.LOGGER.info("Background rescan finished | total scanned: {} | slot: {}",
                chunksScanned, activeOverlaySlot);
    }
}
