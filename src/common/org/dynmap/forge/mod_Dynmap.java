package org.dynmap.forge;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.Init;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.Mod.PostInit;
import cpw.mods.fml.common.Mod.PreInit;
import cpw.mods.fml.common.Mod.ServerStarted;
import cpw.mods.fml.common.Mod.ServerStopping;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkMod;

@Mod(modid = "Dynmap", name = "Dynmap", version = mod_Dynmap.VERSION)
public class mod_Dynmap
{
	public static final String VERSION = "1.1-alpha-2";
    // The instance of your mod that Forge uses.
    @Instance("Generic")
    public static mod_Dynmap instance;

    // Says where the client and server 'proxy' code is loaded.
    @SidedProxy(clientSide = "org.dynmap.forge.ClientProxy", serverSide = "org.dynmap.forge.Proxy")
    public static Proxy proxy;
    
    public static DynmapPlugin plugin;

    @PreInit
    public void preInit(FMLPreInitializationEvent event)
    {
    }

    @Init
    public void load(FMLInitializationEvent event)
    {
    }

    @PostInit
    public void postInit(FMLPostInitializationEvent event)
    {
    }

    @ServerStarted
    public void serverStarted(FMLServerStartedEvent event)
    {
    	plugin = proxy.startServer();
    }
    @ServerStopping
    public void serverStopping(FMLServerStoppingEvent event)
    {
    	proxy.stopServer(plugin);
    }
}
