package org.dynmap.forge;

import java.io.File;
import java.util.List;

import org.dynmap.DynmapCommonAPI; 
import org.dynmap.DynmapCommonAPIListener;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent; 
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

@Mod(modid = "Dynmap", name = "Dynmap", version = Version.VER)
public class DynmapMod
{
    // The instance of your mod that Forge uses.
    @Instance("Dynmap")
    public static DynmapMod instance;

    // Says where the client and server 'proxy' code is loaded.
    @SidedProxy(clientSide = "org.dynmap.forge.ClientProxy", serverSide = "org.dynmap.forge.Proxy")
    public static Proxy proxy;
    
    public static DynmapPlugin plugin;
    public static File jarfile;

    public class APICallback extends DynmapCommonAPIListener {
        @Override
        public void apiListenerAdded() {
            if(plugin == null) {
                plugin = proxy.startServer();
            }
        }
        @Override
        public void apiEnabled(DynmapCommonAPI api) {
        }
    } 
    
    public class LoadingCallback implements net.minecraftforge.common.ForgeChunkManager.LoadingCallback {
        @Override
        public void ticketsLoaded(List<Ticket> tickets, World world) {
            if(tickets.size() > 0) {
                DynmapPlugin.setBusy(world, tickets.get(0));
                for(int i = 1; i < tickets.size(); i++) {
                    ForgeChunkManager.releaseTicket(tickets.get(i));
                }
            }
        }
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        jarfile = event.getSourceFile();
    }

    @EventHandler
    public void load(FMLInitializationEvent event)
    {
        /* Set up for chunk loading notice from chunk manager */
        ForgeChunkManager.setForcedChunkLoadingCallback(DynmapMod.instance, new LoadingCallback());
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event)
    {
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
    }
    
    @EventHandler
    public void serverStarted(FMLServerStartedEvent event)
    {
        DynmapCommonAPIListener.register(new APICallback()); 
        if(plugin == null)
            plugin = proxy.startServer();
        plugin.serverStarted();
    }
    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event)
    {
    	proxy.stopServer(plugin);
    	plugin = null;
    }
}
