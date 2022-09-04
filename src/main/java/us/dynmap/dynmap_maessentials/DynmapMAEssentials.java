package us.dynmap.dynmap_maessentials;

import com.maciej916.maessentials.common.capabilities.serializable.HashMapLocation;
import com.maciej916.maessentials.common.capabilities.serializable.Location;
import com.maciej916.maessentials.common.interfaces.IWorld;
import com.maciej916.maessentials.common.util.CapabilityUtil;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(DynmapMAEssentials.MOD_ID)
public class DynmapMAEssentials
{
	public static final String MOD_ID = "dynmap_maessentials";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    private DynmapCommonAPI api = null;
    private MarkerAPI markerAPI = null;
    private MarkerSet warpsSet = null;
    private MarkerIcon warpsIcon = null;
    private MinecraftServer server = null;
    private HashMap<String, HashSet<String>> warpMarkers = new HashMap<String, HashSet<String>>();
	public static Path modConfigPath;

	private static final String DEFICON = "portal";

	public static class Config {
		public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
		public static final ForgeConfigSpec SPEC;
		public static final ForgeConfigSpec.ConfigValue<String> warpsSetName;
		public static final ForgeConfigSpec.ConfigValue<String> warpsIcon;
		public static final ForgeConfigSpec.DoubleValue warpRefreshTime;
		public static final ForgeConfigSpec.IntValue warpLayerPriority;
		public static final ForgeConfigSpec.IntValue warpMinZoom;
		public static final ForgeConfigSpec.BooleanValue warpLayerHiddenByDefault;
		
		static {
			BUILDER.comment("Module options");
			warpsSetName = BUILDER.comment("Name for marker set for warps").define("warpSetName", "Warps");
			warpsIcon = BUILDER.comment("ID of marker icon to use for warps").define("warpIcon", DEFICON);
			warpRefreshTime = BUILDER.comment("Refresh period for warps, in minutes").defineInRange("warpRefreshTime", 5.0, 0.1, 60.0);
			warpLayerPriority = BUILDER.comment("Dynmap layer priority for warp markers").defineInRange("warpLayerPriority", 10, -100, 100);
			warpLayerHiddenByDefault = BUILDER.comment("Dynmap layer for warps hidden by default").define("warpLayerHidden", false);
			warpMinZoom = BUILDER.comment("Minimum zoom level for warp marker visibility").defineInRange("warpMinZoom", 0, 0, 100);
			SPEC = BUILDER.build();
		}
	}
	
    public DynmapMAEssentials()
    {
        // Register the setup method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
        
		Path configPath = FMLPaths.CONFIGDIR.get();

		modConfigPath = Paths.get(configPath.toAbsolutePath().toString(), MOD_ID);

		// Create the config folder
		try {
			Files.createDirectory(modConfigPath);
		} catch (FileAlreadyExistsException e) {
			// Do nothing
		} catch (IOException e) {
			LOGGER.error("Failed to create dynmap-maessentials config directory", e);
		}
		ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, Config.SPEC,
				MOD_ID + "/" + MOD_ID + ".toml");
    }

    private void setup(final FMLCommonSetupEvent event)
    {
        // some preinit code
    }

    @SubscribeEvent
    public void onServerStarting(ServerAboutToStartEvent event) {
        server = event.getServer();
	}
    
    public class OurDynmapListener extends DynmapCommonAPIListener {
		@Override
		public void apiEnabled(DynmapCommonAPI dynmapAPI) {
	        LOGGER.info("Dynmap API enabled");
	        api = dynmapAPI;
	        markerAPI = api.getMarkerAPI();
	        
	        String icon = Config.warpsIcon.get();
			warpsIcon = markerAPI.getMarkerIcon(icon);
			if (warpsIcon == null) {
				LOGGER.info("Unable to load default icon '" + icon + "' - using default '" + DEFICON + "'");
				warpsIcon = markerAPI.getMarkerIcon(DEFICON);
			}
    		// If needed, setup marker set
	        warpsSet = markerAPI.getMarkerSet(MAESSENTIALS_WARPS);
			if (warpsSet == null) {
				warpsSet = markerAPI.createMarkerSet(MAESSENTIALS_WARPS, Config.warpsSetName.get(), null, false);
			}
			else {
				warpsSet.setMarkerSetLabel(Config.warpsSetName.get());
			}
			warpsSet.setLayerPriority(Config.warpLayerPriority.get());
			warpsSet.setHideByDefault(Config.warpLayerHiddenByDefault.get());    
			if (Config.warpMinZoom.get() > 0) {
				warpsSet.setMinZoom(Config.warpMinZoom.get());
			}
		}
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // Do something when the server starts
        //LOGGER.info("HELLO from server starting");
        DynmapCommonAPIListener.register(new OurDynmapListener());
    }
    
    private static final String MAESSENTIALS_WARPS = "maessentials.warps";
    private int tickcount = 0;
    private int warpCheckCountdown = 0;
    
    @SuppressWarnings("resource")
	public String getWorldName(ResourceKey<Level> w) {
    	String id = w.location().getNamespace() + "_" + w.location().getPath();
    	if (id.equals("minecraft_overworld")) {	// Overworld?
            return server.getLevel(w).serverLevelData.getLevelName();
    	}
    	else if (id.equals("minecraft_the_end")) {
    		return "DIM1";
    	}
    	else if (id.equals("minecraft_the_nether")) {
    		return "DIM-1";
    	}
    	else {
    		return id;
    	}
    }
    
    @SubscribeEvent
    public void onServerTickEvent(TickEvent.ServerTickEvent event) {
    	if (event.phase == TickEvent.Phase.START) {
    		tickcount++;
    		if (tickcount < 20) return;
    		tickcount -= 20;
    		// If no API yet, skip
    		if (warpsSet == null) return;
    		// If due for checking warps, do so
    		if (warpCheckCountdown > 0) { warpCheckCountdown--; return; }
    		//LOGGER.info("Update markers");
    		warpCheckCountdown = (int)(60.0 * Config.warpRefreshTime.get());
    		
    		// Loop over worlds
    		for (Level level : server.getAllLevels()) {
    			IWorld w = CapabilityUtil.getWorld(level);
    			if (w == null) continue;
    			String worldid = level.dimension().location().toString();
    			String worldname = getWorldName(level.dimension());
    			HashMapLocation warps = w.getWarps();
    			if (warps == null) continue;
    			Map<String, Location> warpmap = warps.getMap();
        		// Grab copy of warps
        		HashSet<String> newwarps = new HashSet<String>(warpmap.keySet());
        		// Go through new or existing - add/update as needed
        		for (String warpID : newwarps) {
        			String markerID = worldid + ":" + warpID;
        			Location pos = warpmap.get(warpID);
        			Marker m = warpsSet.findMarker(markerID);	// Get existing, if any
        			if (m == null) {	// If needed, create new
        				LOGGER.info("add " + markerID);
        				m = warpsSet.createMarker(markerID, warpID, worldname, pos.getX(), pos.getY(), pos.getZ(), warpsIcon, false);
        			}
        			else {
        				LOGGER.info("update " + markerID);
        				m.setLocation(worldname, pos.getX(), pos.getY(), pos.getZ());
        			}
        		}
        		// And check for ones to remove
        		HashSet<String> warpMarkerForWorld = warpMarkers.get(worldid);
        		if (warpMarkerForWorld != null) {
	        		for (String warpID: warpMarkerForWorld) {
	        			if (!newwarps.contains(warpID)) {	// If not in new set
	            			String markerID = worldid + ":" + warpID;
	        				Marker m = warpsSet.findMarker(markerID);
	        				if (m != null) {
	            				LOGGER.info("remove " + markerID);
	        					m.deleteMarker();
	        				}
	        			}
	        		}
        		}
        		warpMarkers.put(worldid, newwarps);	// And remember where we are now
    		}

    	}
    }
}
