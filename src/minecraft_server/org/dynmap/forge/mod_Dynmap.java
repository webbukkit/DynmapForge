package org.dynmap.forge;



import net.minecraft.server.MinecraftServer;
import net.minecraft.src.BaseMod;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.ICommandListener;
import net.minecraft.src.ModLoader;
import net.minecraft.src.Packet3Chat;
import cpw.mods.fml.common.IConsoleHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.Init;
import cpw.mods.fml.common.Mod.PostInit;
import cpw.mods.fml.common.Mod.PreInit;

public class mod_Dynmap extends BaseMod
{
	public static final String VERSION = "1.1";

    public static DynmapPlugin plugin;

	@Override
	public String getVersion() {
		return VERSION;
	}

	@Override
	public void load() {
		plugin = new DynmapPlugin();
		plugin.onLoad();
	}

	@Override
	public void modsLoaded() {
	}

	@Override
	public String getName() {
		return "Dynmap";
	}
    
    /**
     * Called when a chat message is received. Return true to stop further processing
     * 
     * @param source
     * @param chat
     * @return true if you want to consume the message so it is not available for further processing
     */
    public boolean onChatMessageReceived(EntityPlayer source, Packet3Chat chat)
    {
        return false;
    }
    /**
     * Called when a server command is received
     * @param command
     * @return true if you want to consume the message so it is not available for further processing
     */
    public boolean onServerCommand(String command, String sender, ICommandListener listener)
    {    	
    	if(plugin != null) {
    		return plugin.onServerCommand(command, sender, listener);
    	}
        return false;
    }

    /**
     * Called when a new client logs in.
     * 
     * @param player
     */
    public void onClientLogin(EntityPlayer player)
    {
    	if(plugin != null) {
    		plugin.onPlayerLogin(player);
    	}
    }

    /**
     * Called when a client logs out of the server.
     * 
     * @param player
     */
    public void onClientLogout(EntityPlayer player)
    {
    	if(plugin != null) {
    		plugin.onPlayerLogout(player);
    	}
    }
}
