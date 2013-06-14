package org.dynmap.forge.permissions;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;

import org.dynmap.Log;
import com.ForgeEssentials.api.permissions.PermissionsAPI;
import com.ForgeEssentials.api.permissions.query.PermQueryPlayer;

import cpw.mods.fml.common.Loader;

import org.dynmap.forge.DynmapPlugin;


public class ForgeEssentialsPermissions implements PermissionProvider {
    protected String name;

    public static ForgeEssentialsPermissions create(String name) throws NoClassDefFoundError {
        if(!Loader.isModLoaded("ForgeEssentials")) {
            return null;
        }        
        Log.info("Using ForgeEssentials Permissions for access control");
        Log.info("Web interface permissions only available for online users");
        return new ForgeEssentialsPermissions(name);
    }

    public ForgeEssentialsPermissions(String name) {
        this.name = name;
    }

    @Override
    public Set<String> hasOfflinePermissions(String player, Set<String> perms) {
        EntityPlayer p = MinecraftServer.getServer().getConfigurationManager().getPlayerForUsername(player);
        HashSet<String> hasperms = null;
        if (p != null) {
            hasperms = new HashSet<String>();
            for(String perm : perms) {
                if(PermissionsAPI.checkPermAllowed(new PermQueryPlayer(p, name + "." + perm))) {
                    hasperms.add(perm);
                }
            }
        }
        return hasperms;
    }

    @Override
    public boolean hasOfflinePermission(String player, String perm) {
        EntityPlayer p = MinecraftServer.getServer().getConfigurationManager().getPlayerForUsername(player);
        if (p != null) {
            boolean rslt = PermissionsAPI.checkPermAllowed(new PermQueryPlayer(p, name + "." + perm));
            return rslt;
        }
        else if (DynmapPlugin.plugin.isOp(player)){
            return true;
        }
        else {
            return false;
        }
    }

    @Override
    public boolean has(ICommandSender sender, String permission) {
        EntityPlayer player = sender instanceof EntityPlayer ? (EntityPlayer) sender : null;
        boolean rslt = player != null
                ? PermissionsAPI.checkPermAllowed(new PermQueryPlayer(player, name + "." + permission)) ||
                        PermissionsAPI.checkPermAllowed(new PermQueryPlayer(player, name + ".*"))
                    : true;
        return rslt;
    }
    @Override
    public boolean hasPermissionNode(ICommandSender sender, String permission) {
        EntityPlayer player = sender instanceof EntityPlayer ? (EntityPlayer) sender : null;
        return (player != null) ? PermissionsAPI.checkPermAllowed(new PermQueryPlayer(player, permission)) : true;
    } 
}
