package com.stephanmeijer.minecraft.oreheatmap;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(OreHeatmapMod.MODID)
public class OreHeatmapMod {
    public static final String MODID = "journeymap_ore_heatmap";
    public static final Logger LOGGER = LogUtils.getLogger();

    public OreHeatmapMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register config
        modContainer.registerConfig(ModConfig.Type.CLIENT, OreHeatmapConfig.SPEC);

        LOGGER.info("JourneyMap Ore Heatmap initialized");
    }
}
