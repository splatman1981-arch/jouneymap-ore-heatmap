package com.stephanmeijer.minecraft.oreheatmap.journeymap;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapConfig;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public class OreHeatmapOverlayManager {

    private final IClientAPI jmAPI;

    // Persistent storage per dimension: dimension key -> (chunk key -> ore count)
    private final Map<String, Map<String, Integer>> dimensionOreCounts = new ConcurrentHashMap<>();

    // Active overlays: chunk key -> overlay
    private final Map<String, PolygonOverlay> activeOverlays = new ConcurrentHashMap<>();

    // Tracked ores
    private final Set<ResourceLocation> trackedBlocks = new HashSet<>();
    private final Set<TagKey<Block>> trackedTags = new HashSet<>();

    // Max ore count for color scaling
    private final AtomicInteger maxOreCount = new AtomicInteger(1);

    // Color constants
    private static final int COLOR_LOW = 0x00FF00;   // Green
    private static final int COLOR_MID = 0xFFFF00;   // Yellow
    private static final int COLOR_HIGH = 0xFF0000;  // Red

    private static final int POLYGON_Y_LEVEL = 0;

    public OreHeatmapOverlayManager(IClientAPI jmAPI) {
        this.jmAPI = jmAPI;
        loadTrackedOres();
    }

    @SuppressWarnings({"unchecked", "ReassignedVariable"})
    private void loadTrackedOres() {
        trackedBlocks.clear();
        trackedTags.clear();

        int presetIdx = OreHeatmapConfig.ACTIVE_PRESET_INDEX.get();
        List<List<String>> presets = (List<List<String>>) OreHeatmapConfig.ORE_PRESETS.get();

        List<String> oresToTrack;
        if (presetIdx >= 0 && presetIdx < presets.size()) {
            oresToTrack = presets.get(presetIdx);
            OreHeatmapMod.LOGGER.info("Loaded preset {}: {}", presetIdx, oresToTrack);
        } else {
            oresToTrack = (List<String>) OreHeatmapConfig.TRACKED_ORES.get();
        }

        for (String oreEntry : oresToTrack) {
            oreEntry = oreEntry.trim();
            if (oreEntry.startsWith("#")) {
                String tagId = oreEntry.substring(1);
                trackedTags.add(TagKey.create(Registries.BLOCK, ResourceLocation.parse(tagId)));
            } else {
                trackedBlocks.add(ResourceLocation.parse(oreEntry));
            }
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }

        LevelChunk chunk = (LevelChunk) event.getChunk();
        ClientLevel level = (ClientLevel) event.getLevel();
        ChunkPos chunkPos = chunk.getPos();

        String dimKey = level.dimension().location().toString();
        Map<String, Integer> oreCounts = dimensionOreCounts.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>());

        String chunkKey = chunkPos.x + "," + chunkPos.z;

        if (oreCounts.containsKey(chunkKey)) {
            return;
        }

        int count = scanChunk(level, chunkPos);

        oreCounts.put(chunkKey, count);
        maxOreCount.updateAndGet(max -> Math.max(max, count));

        updateOverlayForChunk(level, level.dimension(), oreCounts, chunkPos, count);
    }

    private int scanChunk(Level level, ChunkPos chunkPos) {
        int count = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    BlockPos pos = new BlockPos(chunkPos.getMinBlockX() + x, y, chunkPos.getMinBlockZ() + z);
                    BlockState state = level.getBlockState(pos);
                    if (isTrackedOre(state)) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    private boolean isTrackedOre(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());

        if (trackedBlocks.contains(id)) {
            return true;
        }

        for (TagKey<Block> tag : trackedTags) {
            if (state.is(tag)) {
                return true;
            }
        }

        return false;
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof LocalPlayer player) {
            if (OreHeatmapConfig.ENABLED.get() && player.tickCount % OreHeatmapConfig.UPDATE_INTERVAL_TICKS.get() == 0) {
                updateOverlays();
            }
        }
    }

    private void updateOverlays() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        ResourceKey<Level> dimension = mc.level.dimension();
        String dimKey = dimension.location().toString();
        Map<String, Integer> oreCounts = dimensionOreCounts.get(dimKey);
        if (oreCounts == null) {
            return;
        }

        int radius = OreHeatmapConfig.SCAN_RADIUS.get();
        ChunkPos playerChunk = new ChunkPos(mc.player.blockPosition());

        Set<String> visibleChunks = new HashSet<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = playerChunk.x + dx;
                int chunkZ = playerChunk.z + dz;
                String chunkKey = chunkX + "," + chunkZ;

                visibleChunks.add(chunkKey);

                if (oreCounts.containsKey(chunkKey)) {
                    int count = oreCounts.get(chunkKey);
                    ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                    updateOverlayForChunk(mc.level, dimension, oreCounts, chunkPos, count);
                }
            }
        }

        // Remove overlays for chunks no longer visible
        activeOverlays.entrySet().removeIf(entry -> !visibleChunks.contains(entry.getKey()));
    }

    private void updateOverlayForChunk(Level level, ResourceKey<Level> dimension, Map<String, Integer> oreCounts, ChunkPos chunkPos, int oreCount) {
        String chunkKey = chunkPos.x + "," + chunkPos.z;

        PolygonOverlay existingOverlay = activeOverlays.get(chunkKey);
        if (existingOverlay != null) {
            try {
                jmAPI.remove(existingOverlay);
            } catch (Exception e) {
                OreHeatmapMod.LOGGER.debug("Failed to remove existing overlay", e);
            }
            activeOverlays.remove(chunkKey);
        }

        if (oreCount == 0) {
            return;
        }

        int color = calculateHeatmapColor(oreCount);
        ShapeProperties shapeProps = new ShapeProperties()
                .setStrokeColor(0x000000).setStrokeOpacity(0.0f)
                .setFillColor(color).setFillOpacity(OreHeatmapConfig.OVERLAY_OPACITY.get().floatValue());

        MapPolygon polygon = createChunkPolygon(chunkPos);

        PolygonOverlay overlay = new PolygonOverlay(OreHeatmapMod.MODID, dimension, shapeProps, polygon);
        overlay.setOverlayGroupName("Ore Heatmap");

        if (OreHeatmapConfig.SHOW_OVERLAY_IN_CAVES.get()) {
            overlay.setDisplayOrder(1000);
        }

        try {
            jmAPI.show(overlay);
            activeOverlays.put(chunkKey, overlay);
        } catch (Exception e) {
            OreHeatmapMod.LOGGER.debug("Failed to show overlay", e);
        }
    }

    public void reloadHeatmap() {
        // Reload tracked ores from current config (preset or default)
        loadTrackedOres();

        // Clear all data and overlays
        dimensionOreCounts.clear();
        clearAllOverlays();
        maxOreCount.set(1);

        // Force rescan all currently loaded chunks
        Minecraft mc = Minecraft.getInstance();
        if (mc.level instanceof ClientLevel clientLevel) {
            ClientChunkCache chunkSource = clientLevel.getChunkSource();
            String dimKey = clientLevel.dimension().location().toString();
            Map<String, Integer> oreCounts = dimensionOreCounts.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>());

            int radius = 12;  // ~384 blocks - typical visible range
            ChunkPos playerChunk = new ChunkPos(mc.player.blockPosition());
            int countRescanned = 0;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int chunkX = playerChunk.x + dx;
                    int chunkZ = playerChunk.z + dz;
                    LevelChunk chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                    if (chunk != null && !chunk.isEmpty()) {
                        ChunkPos chunkPos = chunk.getPos();
                        int oreCount = scanChunk(clientLevel, chunkPos);
                        String chunkKey = chunkX + "," + chunkZ;
                        oreCounts.put(chunkKey, oreCount);
                        maxOreCount.updateAndGet(max -> Math.max(max, oreCount));
                        countRescanned++;
                    }
                }
            }

            OreHeatmapMod.LOGGER.info("Rescanned {} chunks after reload", countRescanned);
        }

        // Feedback
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("§aOre Heatmap reloaded! Tracked ores updated. §7(Rescanned nearby loaded chunks)"),
                    false
            );
        }
        OreHeatmapMod.LOGGER.info("Heatmap reload complete via keybind");
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
}
