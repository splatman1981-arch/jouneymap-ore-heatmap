package com.stephanmeijer.minecraft.oreheatmap.journeymap;

import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapConfig;
import com.stephanmeijer.minecraft.oreheatmap.OreHeatmapMod;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.JourneyMapPlugin;
import journeymap.api.v2.client.event.FullscreenDisplayEvent;
import journeymap.api.v2.client.fullscreen.ThemeButtonDisplay;
import journeymap.api.v2.common.event.ClientEventRegistry;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * JourneyMap plugin for the Ore Heatmap mod.
 * This class is discovered by JourneyMap via the @JourneyMapPlugin annotation.
 */
@ParametersAreNonnullByDefault
@JourneyMapPlugin(apiVersion = "2.0.0")
public class OreHeatmapPlugin implements IClientPlugin {

    private IClientAPI jmAPI;
    private OreHeatmapOverlayManager overlayManager;

    private static OreHeatmapPlugin INSTANCE;

    public OreHeatmapPlugin() {
        INSTANCE = this;
    }

    public static OreHeatmapPlugin getInstance() {
        return INSTANCE;
    }

    /**
     * Get the icon ResourceLocation for the toggle button.
     */
    private static ResourceLocation getIcon() {
        return ResourceLocation.fromNamespaceAndPath(
                OreHeatmapMod.MODID,
                "icon/ore_heatmap.png"
        );
    }

    @Override
    public void initialize(IClientAPI jmClientApi) {
        try {
            OreHeatmapMod.LOGGER.info("OreHeatmapPlugin.initialize() called");

            this.jmAPI = jmClientApi;
            OreHeatmapMod.LOGGER.info("Creating OreHeatmapOverlayManager...");
            this.overlayManager = new OreHeatmapOverlayManager(jmClientApi);
            OreHeatmapMod.LOGGER.info("OreHeatmapOverlayManager created");

            // Register the event listener for NeoForge events (player tick, chunk load, etc.)
            OreHeatmapMod.LOGGER.info("Registering NeoForge event bus...");
            NeoForge.EVENT_BUS.register(overlayManager);
            OreHeatmapMod.LOGGER.info("NeoForge event bus registered");

            // Register for JourneyMap fullscreen button display event
            OreHeatmapMod.LOGGER.info("Subscribing to ADDON_BUTTON_DISPLAY_EVENT...");
            ClientEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(
                    OreHeatmapMod.MODID, this::onAddonButtonDisplay);

            OreHeatmapMod.LOGGER.info("JourneyMap Ore Heatmap plugin initialized successfully");
        } catch (Throwable t) {
            OreHeatmapMod.LOGGER.error("Failed to initialize OreHeatmapPlugin", t);
        }
    }

    /**
     * Adds a toggle button to JourneyMap's fullscreen map sidebar.
     */
    private void onAddonButtonDisplay(FullscreenDisplayEvent.AddonButtonDisplayEvent event) {
        OreHeatmapMod.LOGGER.info("onAddonButtonDisplay() event fired!");

        ThemeButtonDisplay buttonDisplay = event.getThemeButtonDisplay();
        OreHeatmapMod.LOGGER.info("ThemeButtonDisplay: {}", buttonDisplay);

        boolean isEnabled = OreHeatmapConfig.ENABLED.get();
        OreHeatmapMod.LOGGER.info("Adding toggle button, enabled={}", isEnabled);

        buttonDisplay.addThemeToggleButton(
                "Ore Heatmap On",   // labelOn
                "Ore Heatmap Off",  // labelOff
                getIcon(),
                isEnabled,          // initial toggle state from config
                button -> {
                    // Toggle the button's visual state
                    button.toggle();

                    // Update the config to match
                    boolean newState = button.getToggled();
                    OreHeatmapConfig.ENABLED.set(newState);

                    // Clear overlays if disabled
                    if (!newState && overlayManager != null) {
                        overlayManager.clearAllOverlays();
                    }

                    OreHeatmapMod.LOGGER.debug("Ore Heatmap toggled via button: {}", newState);
                }
        );
    }

    @Override
    public String getModId() {
        return OreHeatmapMod.MODID;
    }

    public IClientAPI getAPI() {
        return jmAPI;
    }

    public OreHeatmapOverlayManager getOverlayManager() {
        return overlayManager;
    }
}
