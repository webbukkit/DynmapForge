package org.dynmap.forge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.network.NetServerHandler;
import net.minecraft.network.packet.NetHandler;
import net.minecraft.network.packet.Packet3Chat;
import net.minecraft.potion.Potion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.BanList;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.ChatMessageComponent;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.StringUtils;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.dynmap.ConfigurationNode;
import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapLocation;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapManager;
import org.dynmap.MapType;
import org.dynmap.PlayerList;
import org.dynmap.forge.DmapCommand;
import org.dynmap.forge.DmarkerCommand;
import org.dynmap.forge.DynmapCommand;
import org.dynmap.forge.DynmapMod;
import org.dynmap.forge.permissions.FilePermissions;
import org.dynmap.forge.permissions.OpPermissions;
import org.dynmap.forge.permissions.ForgeEssentialsPermissions;
import org.dynmap.forge.permissions.PermissionProvider;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.common.DynmapServerInterface;
import org.dynmap.common.DynmapListenerManager.EventType;
import org.dynmap.debug.Debug;
import org.dynmap.hdmap.HDMap;
import org.dynmap.permissions.PermissionsHandler;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.VisibilityLimit;

import cpw.mods.fml.common.IPlayerTracker;
import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.network.IChatListener;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.common.FMLLog;

public class DynmapPlugin
{
    private DynmapCore core;
    private PermissionProvider permissions;
    private boolean core_enabled;
    public SnapshotCache sscache;
    public PlayerList playerList;
    private MapManager mapManager;
    private net.minecraft.server.MinecraftServer server;
    public static DynmapPlugin plugin;
    private ChatHandler chathandler;
    private HashMap<String, Integer> sortWeights = new HashMap<String, Integer>(); 
    // Drop world load ticket after 30 seconds
    private long worldIdleTimeoutNS = 30 * 1000000000L;
    private HashMap<String, ForgeWorld> worlds = new HashMap<String, ForgeWorld>();
    private World last_world;
    private ForgeWorld last_fworld;
    private Map<String, ForgePlayer> players = new HashMap<String, ForgePlayer>();
    private ForgeMetrics metrics;
    private HashSet<String> modsused = new HashSet<String>();
    private ForgeServer fserver = new ForgeServer();
    private boolean tickregistered = false;
    // TPS calculator
    private double tps;
    private long lasttick;
    private long avgticklen;
    // Per tick limit, in nsec
    private long perTickLimit = (50000000); // 50 ms
    private boolean isMCPC = false;
    private boolean useSaveFolder = true;
    private Field displayName = null; // MCPC+ display name

    private static final String[] TRIGGER_DEFAULTS = { "blockupdate", "chunkpopulate", "chunkgenerate" };
    
    public static class BlockUpdateRec {
    	World w;
    	String wid;
    	int x, y, z;
    }
    ConcurrentLinkedQueue<BlockUpdateRec> blockupdatequeue = new ConcurrentLinkedQueue<BlockUpdateRec>();

    private ForgePlayer getOrAddPlayer(EntityPlayer p) {
    	ForgePlayer fp = players.get(p.username);
    	if(fp != null) {
    		fp.player = p;
    	}
    	else {
    		fp = new ForgePlayer(p);
    		players.put(p.username, fp);
    	}
    	return fp;
    }
    
    private static class TaskRecord implements Comparable<Object>
    {
        private long ticktorun;
        private long id;
        private FutureTask<?> future;
        @Override
        public int compareTo(Object o)
        {
            TaskRecord tr = (TaskRecord)o;

            if (this.ticktorun < tr.ticktorun)
            {
                return -1;
            }
            else if (this.ticktorun > tr.ticktorun)
            {
                return 1;
            }
            else if (this.id < tr.id)
            {
                return -1;
            }
            else if (this.id > tr.id)
            {
                return 1;
            }
            else
            {
                return 0;
            }
        }
    }

    private class ChatMessage {
    	String message;
    	EntityPlayer sender;
    }
    private ConcurrentLinkedQueue<ChatMessage> msgqueue = new ConcurrentLinkedQueue<ChatMessage>();
    
    private class ChatHandler implements IChatListener {

		@Override
		public Packet3Chat serverChat(NetHandler handler, Packet3Chat message) {
			if(!message.message.startsWith("/")) {
				ChatMessage cm = new ChatMessage();
				cm.message = message.message;
				cm.sender = handler.getPlayer();
				msgqueue.add(cm);
			}
			return message;
		}
		@Override
		public Packet3Chat clientChat(NetHandler handler, Packet3Chat message) {
			return message;
		}
    }
    
    private static class WorldBusyRecord {
        long last_ts;
        Ticket ticket;
    }
    private static HashMap<Integer, WorldBusyRecord> busy_worlds = new HashMap<Integer, WorldBusyRecord>();
    
    private void setBusy(World w) {
        setBusy(w, null);
    }
    static void setBusy(World w, Ticket t) {
        if(w == null) return;
        if (!DynmapMod.useforcedchunks) return;
        WorldBusyRecord wbr = busy_worlds.get(w.provider.dimensionId);
        if(wbr == null) {   /* Not busy, make ticket and keep spawn loaded */
            Debug.debug("World " + w.getWorldInfo().getWorldName() + "/"+ w.provider.getDimensionName() + " is busy");
            wbr = new WorldBusyRecord();
            if(t != null)
                wbr.ticket = t;
            else
                wbr.ticket = ForgeChunkManager.requestTicket(DynmapMod.instance, w, ForgeChunkManager.Type.NORMAL);
            if(wbr.ticket != null) {
                ChunkCoordinates cc = w.getSpawnPoint();
                ChunkCoordIntPair ccip = new ChunkCoordIntPair(cc.posX >> 4, cc.posZ >> 4);
                ForgeChunkManager.forceChunk(wbr.ticket, ccip);
                busy_worlds.put(w.provider.dimensionId, wbr);  // Add to busy list
            }
        }
        wbr.last_ts = System.nanoTime();
    }
    
    private void doIdleOutOfWorlds() {
        if (!DynmapMod.useforcedchunks) return;
        long ts = System.nanoTime() - worldIdleTimeoutNS;
        for(Iterator<WorldBusyRecord> itr = busy_worlds.values().iterator(); itr.hasNext();) {
            WorldBusyRecord wbr = itr.next();
            if(wbr.last_ts < ts) {
                World w = wbr.ticket.world;
                Debug.debug("World " + w.getWorldInfo().getWorldName() + "/" + wbr.ticket.world.provider.getDimensionName() + " is idle");
                ForgeChunkManager.releaseTicket(wbr.ticket);    // Release hold on world 
                itr.remove();
            }
        }
    }
    
    public DynmapPlugin()
    {
        plugin = this;
        Log.setLoggerParent(FMLLog.getLogger());      
        
        displayName = null;
        try {
            displayName = EntityPlayerMP.class.getField("displayName");
        } catch (SecurityException e) {
        } catch (NoSuchFieldException e) {
        }
    }

    public boolean isOp(String player) {
    	player = player.toLowerCase();
    	return server.getConfigurationManager().getOps().contains(player) ||
    			(server.isSinglePlayer() && player.equalsIgnoreCase(server.getServerOwner()));
    }
    
    private boolean hasPerm(ICommandSender sender, String permission) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if(ph != null) {
            if((sender instanceof EntityPlayer) && ph.hasPermission(sender.getCommandSenderName(), permission)) {
                return true;
            }
        }
        return permissions.has(sender, permission);
    }
    
    private boolean hasPermNode(ICommandSender sender, String permission) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if(ph != null) {
            if((sender instanceof EntityPlayer) && ph.hasPermissionNode(sender.getCommandSenderName(), permission)) {
                return true;
            }
        }
        return permissions.hasPermissionNode(sender, permission);
    } 

    private Set<String> hasOfflinePermissions(String player, Set<String> perms) {
        Set<String> rslt = null;
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if(ph != null) {
            rslt = ph.hasOfflinePermissions(player, perms);
        }
        Set<String> rslt2 = hasOfflinePermissions(player, perms);
        if((rslt != null) && (rslt2 != null)) {
            Set<String> newrslt = new HashSet<String>(rslt);
            newrslt.addAll(rslt2);
            rslt = newrslt;
        }
        else if(rslt2 != null) {
            rslt = rslt2;
        }
        return rslt;
    }
    private boolean hasOfflinePermission(String player, String perm) {
        PermissionsHandler ph = PermissionsHandler.getHandler();
        if(ph != null) {
            if(ph.hasOfflinePermission(player, perm)) {
                return true;
            }
        }
        return permissions.hasOfflinePermission(player, perm);
    }

    /**
     * Server access abstraction class
     */
    public class ForgeServer extends DynmapServerInterface implements ITickHandler
    {
        /* Chunk load handling */
        private Object loadlock = new Object();
        private int chunks_in_cur_tick = 0;
        /* Server thread scheduler */
        private Object schedlock = new Object();
        private long cur_tick;
        private long next_id;
        private long cur_tick_starttime;
        private PriorityQueue<TaskRecord> runqueue = new PriorityQueue<TaskRecord>();

        public ForgeServer() {
        }
        
        @Override
        public int getBlockIDAt(String wname, int x, int y, int z) {
        	DynmapWorld dw = this.getWorldByName(wname);
        	if (dw != null) {
        		World w = ((ForgeWorld)dw).getWorld();
        		if((w != null) && (w.getChunkProvider().chunkExists(x >> 4,  z >> 4))) {
        			return w.getBlockId(x,  y,  z);
        		}
        	}
            return -1;
        }

        @Override
        public void scheduleServerTask(Runnable run, long delay)
        {
            TaskRecord tr = new TaskRecord();
            tr.future = new FutureTask<Object>(run, null);

            /* Add task record to queue */
            synchronized (schedlock)
            {
                tr.id = next_id++;
                tr.ticktorun = cur_tick + delay;
                runqueue.add(tr);
            }
        }
        @Override
        public DynmapPlayer[] getOnlinePlayers()
        {
            if(server.getConfigurationManager() == null)
                return new DynmapPlayer[0];
            List<?> playlist = server.getConfigurationManager().playerEntityList;
            int pcnt = playlist.size();
            DynmapPlayer[] dplay = new DynmapPlayer[pcnt];

            for (int i = 0; i < pcnt; i++)
            {
                EntityPlayer p = (EntityPlayer)playlist.get(i);
                dplay[i] = getOrAddPlayer(p);
            }

            return dplay;
        }
        @Override
        public void reload()
        {
            plugin.onDisable();
            plugin.onEnable();
        }
        @Override
        public DynmapPlayer getPlayer(String name)
        {
            List<?> players = server.getConfigurationManager().playerEntityList;

            for (Object o : players)
            {
                EntityPlayer p = (EntityPlayer)o;

                if (p.getEntityName().equalsIgnoreCase(name))
                {
                    return getOrAddPlayer(p);
                }
            }

            return null;
        }
        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getIPBans()
        {
            BanList bl = server.getConfigurationManager().getBannedIPs();
            Set<String> ips = new HashSet<String>();

            if (bl.isListActive())
            {
                ips = bl.getBannedList().keySet();
            }

            return ips;
        }
        @Override
        public <T> Future<T> callSyncMethod(Callable<T> task) {
        	return callSyncMethod(task, 0);
        }
        public <T> Future<T> callSyncMethod(Callable<T> task, long delay)
        {
            TaskRecord tr = new TaskRecord();
            FutureTask<T> ft = new FutureTask<T>(task);
            tr.future = ft;

            /* Add task record to queue */
            synchronized (schedlock)
            {
                tr.id = next_id++;
                tr.ticktorun = cur_tick + delay;
                runqueue.add(tr);
            }

            return ft;
        }
        @Override
        public String getServerName()
        {
        	String sn = server.getServerHostname();
        	if(sn == null) sn = "Unknown Server";
        	return sn;
        }
        @Override
        public boolean isPlayerBanned(String pid)
        {
            BanList bl = server.getConfigurationManager().getBannedPlayers();
            return bl.isBanned(pid);
        }
        @Override
        public String stripChatColor(String s)
        {
            return StringUtils.stripControlCodes(s);
        }
        private Set<EventType> registered = new HashSet<EventType>();
        @Override
        public boolean requestEventNotification(EventType type)
        {
            if (registered.contains(type))
            {
                return true;
            }

            switch (type)
            {
                case WORLD_LOAD:
                case WORLD_UNLOAD:
                    /* Already called for normal world activation/deactivation */
                    break;

                case WORLD_SPAWN_CHANGE:
                    /*TODO
                    pm.registerEvents(new Listener() {
                        @EventHandler(priority=EventPriority.MONITOR)
                        public void onSpawnChange(SpawnChangeEvent evt) {
                            DynmapWorld w = new BukkitWorld(evt.getWorld());
                            core.listenerManager.processWorldEvent(EventType.WORLD_SPAWN_CHANGE, w);
                        }
                    }, DynmapPlugin.this);
                    */
                    break;

                case PLAYER_JOIN:
                case PLAYER_QUIT:
                    /* Already handled */
                    break;

                case PLAYER_BED_LEAVE:
                    /*TODO
                    pm.registerEvents(new Listener() {
                        @EventHandler(priority=EventPriority.MONITOR)
                        public void onPlayerBedLeave(PlayerBedLeaveEvent evt) {
                            DynmapPlayer p = new BukkitPlayer(evt.getPlayer());
                            core.listenerManager.processPlayerEvent(EventType.PLAYER_BED_LEAVE, p);
                        }
                    }, DynmapPlugin.this);
                    */
                    break;

                case PLAYER_CHAT:
                	if (chathandler == null) {
                		chathandler = new ChatHandler();
                    	NetworkRegistry.instance().registerChatListener(chathandler);
                	}
                    break;

                case BLOCK_BREAK:
                    /*TODO
                    pm.registerEvents(new Listener() {
                        @EventHandler(priority=EventPriority.MONITOR)
                        public void onBlockBreak(BlockBreakEvent evt) {
                            if(evt.isCancelled()) return;
                            Block b = evt.getBlock();
                            if(b == null) return;
                            Location l = b.getLocation();
                            core.listenerManager.processBlockEvent(EventType.BLOCK_BREAK, b.getType().getId(),
                                    BukkitWorld.normalizeWorldName(l.getWorld().getName()), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                        }
                    }, DynmapPlugin.this);
                    */
                    break;

                case SIGN_CHANGE:
                    /*TODO
                    pm.registerEvents(new Listener() {
                        @EventHandler(priority=EventPriority.MONITOR)
                        public void onSignChange(SignChangeEvent evt) {
                            if(evt.isCancelled()) return;
                            Block b = evt.getBlock();
                            Location l = b.getLocation();
                            String[] lines = evt.getLines();
                            DynmapPlayer dp = null;
                            Player p = evt.getPlayer();
                            if(p != null) dp = new BukkitPlayer(p);
                            core.listenerManager.processSignChangeEvent(EventType.SIGN_CHANGE, b.getType().getId(),
                                    BukkitWorld.normalizeWorldName(l.getWorld().getName()), l.getBlockX(), l.getBlockY(), l.getBlockZ(), lines, dp);
                        }
                    }, DynmapPlugin.this);
                    */
                    break;

                default:
                    Log.severe("Unhandled event type: " + type);
                    return false;
            }

            registered.add(type);
            return true;
        }
        @Override
        public boolean sendWebChatEvent(String source, String name, String msg)
        {
            /*TODO
            DynmapWebChatEvent evt = new DynmapWebChatEvent(source, name, msg);
            getServer().getPluginManager().callEvent(evt);
            return ((evt.isCancelled() == false) && (evt.isProcessed() == false));
            */
            return true;
        }
        @Override
        public void broadcastMessage(String msg)
        {
            MinecraftServer.getServer().getConfigurationManager().sendPacketToAllPlayers(new Packet3Chat(ChatMessageComponent.createFromText(msg)));
            Log.info(StringUtils.stripControlCodes(msg));
        }
        @Override
        public String[] getBiomeIDs()
        {
            BiomeMap[] b = BiomeMap.values();
            String[] bname = new String[b.length];

            for (int i = 0; i < bname.length; i++)
            {
                bname[i] = b[i].toString();
            }

            return bname;
        }
        @Override
        public double getCacheHitRate()
        {
            return sscache.getHitRate();
        }
        @Override
        public void resetCacheStats()
        {
            sscache.resetStats();
        }
        @Override
        public DynmapWorld getWorldByName(String wname)
        {
        	return DynmapPlugin.this.getWorldByName(wname);
        }
        @Override
        public DynmapPlayer getOfflinePlayer(String name)
        {
            /*TODO
            OfflinePlayer op = getServer().getOfflinePlayer(name);
            if(op != null) {
                return new BukkitPlayer(op);
            }
            */
            return null;
        }
        @Override
        public Set<String> checkPlayerPermissions(String player, Set<String> perms)
        {
            ServerConfigurationManager scm = MinecraftServer.getServer().getConfigurationManager();
            if (scm == null) return Collections.emptySet();
            BanList bl = scm.getBannedPlayers();
            if (bl == null) return Collections.emptySet();
            if(bl.isBanned(player)) {
                return Collections.emptySet();
            }
            Set<String> rslt = hasOfflinePermissions(player, perms);
            if (rslt == null) {
                rslt = new HashSet<String>();
                if(plugin.isOp(player)) {
                    rslt.addAll(perms);
                }
            }
            return rslt;
        }
        @Override
        public boolean checkPlayerPermission(String player, String perm)
        {
            ServerConfigurationManager scm = MinecraftServer.getServer().getConfigurationManager();
            if (scm == null) return false;
            BanList bl = scm.getBannedPlayers();
            if (bl == null) return false;
            if(bl.isBanned(player)) {
                return false;
            }
            return hasOfflinePermission(player, perm);
        }
        /**
         * Render processor helper - used by code running on render threads to request chunk snapshot cache from server/sync thread
         */
        @Override
        public MapChunkCache createMapChunkCache(DynmapWorld w, List<DynmapChunk> chunks,
                boolean blockdata, boolean highesty, boolean biome, boolean rawbiome)
        {
            MapChunkCache c = w.getChunkCache(chunks);
            if(c == null) {
            	return null;
            }
            if (w.visibility_limits != null)
            {
                for (VisibilityLimit limit: w.visibility_limits)
                {
                    c.setVisibleRange(limit);
                }

                c.setHiddenFillStyle(w.hiddenchunkstyle);
            }

            if (w.hidden_limits != null)
            {
                for (VisibilityLimit limit: w.hidden_limits)
                {
                    c.setHiddenRange(limit);
                }

                c.setHiddenFillStyle(w.hiddenchunkstyle);
            }

            if (c.setChunkDataTypes(blockdata, biome, highesty, rawbiome) == false)
            {
                Log.severe("CraftBukkit build does not support biome APIs");
            }

            if (chunks.size() == 0)     /* No chunks to get? */
            {
                c.loadChunks(0);
                return c;
            }
            
            final MapChunkCache cc = c;
            long delay = 0;

            while (!cc.isDoneLoading())
            {
                Future<Boolean> f = this.callSyncMethod(new Callable<Boolean>()
                {
                    public Boolean call() throws Exception
                    {
                        boolean exhausted = true;

                        synchronized (loadlock)
                        {
                            if (chunks_in_cur_tick > 0)
                            {
                                // Update busy state on world
                                ForgeWorld fw = (ForgeWorld)cc.getWorld();
                                setBusy(fw.getWorld());
                                boolean done = false;
                                while (!done) {
                                    int cnt = chunks_in_cur_tick;
                                    if (cnt > 5) cnt = 5;
                                    chunks_in_cur_tick -= cc.loadChunks(cnt);
                                    exhausted = (chunks_in_cur_tick == 0) || ((System.nanoTime() - cur_tick_starttime) > perTickLimit);
                                    done = exhausted || cc.isDoneLoading();
                                }
                            }
                        }

                        return exhausted;
                    }
                }, delay);
                Boolean needdelay;

                try
                {
                    needdelay = f.get();
                }
                catch (CancellationException cx)
                {
                    return null;
                }
                catch (ExecutionException xx) {
                    Log.severe("Exception while loading chunks", xx.getCause());
                    return null;
                }
                catch (Exception ix)
                {
                    Log.severe(ix);
                    return null;
                }

                if ((needdelay != null) && needdelay.booleanValue())
                {
                	delay = 1;
                }
                else {
                	delay = 0;
                }
            }
            if(w.isLoaded() == false) {
            	return null;
            }
            return c;
        }
        @Override
        public int getMaxPlayers()
        {
            if(server.getConfigurationManager() != null) 
                return server.getMaxPlayers();
            else
                return 0;
        }
        @Override
        public int getCurrentPlayers()
        {
            if(server.getConfigurationManager() != null) 
                return server.getConfigurationManager().playerEntityList.size();
            else
                return 0;
        }

		@Override
		public void tickStart(EnumSet<TickType> type, Object... tickData) {
		}

		@Override
		public void tickEnd(EnumSet<TickType> type, Object... tickData) {
			if (type.contains(TickType.SERVER)) {
                cur_tick_starttime = System.nanoTime();
                long elapsed = cur_tick_starttime - lasttick;
                lasttick = cur_tick_starttime;
                avgticklen = ((avgticklen * 99) / 100) + (elapsed / 100);
                tps = (double)1E9 / (double)avgticklen;
                // Tick core
                if (core != null) {
                    core.serverTick(tps);
                }

                boolean done = false;
                TaskRecord tr = null;

				while(!blockupdatequeue.isEmpty()) {
					BlockUpdateRec r = blockupdatequeue.remove();
					int id = 0;
					int meta = 0;
					if((r.w != null) && r.w.getChunkProvider().chunkExists(r.x >> 4,  r.z >> 4)) {
						id = r.w.getBlockId(r.x, r.y, r.z);
						meta = r.w.getBlockMetadata(r.x, r.y, r.z);
					}
					if(!org.dynmap.hdmap.HDBlockModels.isChangeIgnoredBlock(id,  meta)) {
						if(onblockchange_with_id)
							mapManager.touch(r.wid, r.x, r.y, r.z, "blockchange[" + id + ":" + meta + "]");
						else
							mapManager.touch(r.wid, r.x, r.y, r.z, "blockchange");
					}
				}

                long now;
                
				synchronized(schedlock) {
					cur_tick++;
                    now = System.nanoTime();
					tr = runqueue.peek();
					/* Nothing due to run */
                    if((tr == null) || (tr.ticktorun > cur_tick) || ((now - cur_tick_starttime) > perTickLimit)) {
						done = true;
					}
					else {
						tr = runqueue.poll();
					}
				}
                synchronized (loadlock) {
                	chunks_in_cur_tick = mapManager.getMaxChunkLoadsPerTick();
				}
				while (!done) {
					tr.future.run();

                    synchronized(schedlock) {
                        tr = runqueue.peek();
                        now = System.nanoTime();
                        /* Nothing due to run */
                        if((tr == null) || (tr.ticktorun > cur_tick) || ((now - cur_tick_starttime) > perTickLimit)) {
                            done = true;
                        }
                        else {
                            tr = runqueue.poll();
                        }
					}
				}
				while(!msgqueue.isEmpty()) {
					ChatMessage cm = msgqueue.poll();
                    DynmapPlayer dp = null;
                    if(cm.sender != null)
                		dp = getOrAddPlayer(cm.sender);
                    else
                    	dp = new ForgePlayer(null);
                    
                    core.listenerManager.processChatEvent(EventType.PLAYER_CHAT, dp, cm.message);
				}
				/* Check for idle worlds */
				if((cur_tick % 20) == 0) {
				    doIdleOutOfWorlds();
				}
			}
		}

		private final EnumSet<TickType> ticktype = EnumSet.of(TickType.SERVER);
		
		@Override
		public EnumSet<TickType> ticks() {
			return ticktype;
		}

		@Override
		public String getLabel() {
			return "Dynmap";
		}

		@Override
		public boolean isModLoaded(String name) {
			boolean loaded = Loader.isModLoaded(name);
			if (loaded) {
                modsused.add(name);
			}
			return loaded;
		}
		@Override
		public String getModVersion(String name) {
	          ModContainer mod = Loader.instance().getIndexedModList().get(name);
	          if (mod == null) return null;
	          return mod.getVersion();
		}

        @Override
        public double getServerTPS() {
            return tps;
        }
        
        @Override
        public String getServerIP() {
            return server.getServerHostname();
        }
        @Override
        public File getModContainerFile(String name) {
            ModContainer mod = Loader.instance().getIndexedModList().get(name);
            if (mod == null) return null;
            return mod.getSource();
        }
        @Override
        public List<String> getModList() {
            return new ArrayList<String>(Loader.instance().getIndexedModList().keySet());
        }

        @Override
        public Map<Integer, String> getBlockIDMap() {
            Map<Integer, String> map = new HashMap<Integer, String>();
            for (int i = 0; i < Block.blocksList.length; i++) {
                Block b = Block.blocksList[i];
                if (b == null) continue;
                UniqueIdentifier ui = GameRegistry.findUniqueIdentifierFor(b);
                if (ui != null) {
                    map.put(i, ui.modId + ":" + ui.name);
                }
            }
            return map;
        }

        @Override
        public InputStream openResource(String modid, String rname) {
            if (modid != null) {
                ModContainer mc = Loader.instance().getIndexedModList().get(modid);
                Object mod = mc.getMod();
                if (mod != null) {
                    InputStream is = mod.getClass().getClassLoader().getResourceAsStream(rname);
                    if (is != null) {
                        return is;
                    }
                }
            }
            List<ModContainer> mcl = Loader.instance().getModList();
            for (ModContainer mc : mcl) {
                Object mod = mc.getMod();
                if (mod == null) continue;
                InputStream is = mod.getClass().getClassLoader().getResourceAsStream(rname);
                if (is != null) {
                    return is;
                }
            }
            return null;
        }
        /**
         * Get block unique ID map (module:blockid)
         */
        @Override
        public Map<String, Integer> getBlockUniqueIDMap() {
            HashMap<String, Integer> map = new HashMap<String, Integer>();
            for (int i = 0; i < Block.blocksList.length; i++) {
                Block b = Block.blocksList[i];
                if (b == null) continue;
                UniqueIdentifier ui = GameRegistry.findUniqueIdentifierFor(b);
                if (ui != null) {
                    map.put(ui.modId + ":" + ui.name, i);
                }
            }
            return map;
        }
        /**
         * Get item unique ID map (module:itemid)
         */
        @Override
        public Map<String, Integer> getItemUniqueIDMap() {
            HashMap<String, Integer> map = new HashMap<String, Integer>();
            for (int i = 0; i < Item.itemsList.length; i++) {
                Item itm = Item.itemsList[i];
                if (itm == null) continue;
                UniqueIdentifier ui = GameRegistry.findUniqueIdentifierFor(itm);
                if (ui != null) {
                    map.put(ui.modId + ":" + ui.name, i - 256);
                }
            }
            return map;
        }
    }
    /**
     * Player access abstraction class
     */
    public class ForgePlayer extends ForgeCommandSender implements DynmapPlayer
    {
        private EntityPlayer player;

        public ForgePlayer(EntityPlayer p)
        {
            player = p;
        }
        @Override
        public boolean isConnected()
        {
            return true;
        }
        @Override
        public String getName()
        {
        	if(player != null)
        		return player.getEntityName();
        	else
        		return "[Server]";
        }
        @Override
        public String getDisplayName()
        {
        	if(player != null) {
        	    if (displayName != null) {
        	        try {
                        return (String) displayName.get(player);
                    } catch (IllegalArgumentException e) {
                    } catch (IllegalAccessException e) {
                    }
        	    }
        		return player.getDisplayName();
        	}
        	else
        		return "[Server]";
        }
        @Override
        public boolean isOnline()
        {
            return true;
        }
        @Override
        public DynmapLocation getLocation()
        {
            if (player == null)
            {
                return null;
            }

            return toLoc(player.worldObj, player.posX, player.posY, player.posZ);
        }
        @Override
        public String getWorld()
        {
            if (player == null)
            {
                return null;
            }

            if (player.worldObj != null)
            {
                return DynmapPlugin.this.getWorld(player.worldObj).getName();
            }

            return null;
        }
        @Override
        public InetSocketAddress getAddress()
        {
            if((player != null) && (player instanceof EntityPlayerMP)) {
            	NetServerHandler nsh = ((EntityPlayerMP)player).playerNetServerHandler;
            	if((nsh != null) && (nsh.netManager != null)) {
            		SocketAddress sa = nsh.netManager.getSocketAddress();
            		if(sa instanceof InetSocketAddress) {
            			return (InetSocketAddress)sa;
            		}
            	}
            }
            return null;
        }
        @Override
        public boolean isSneaking()
        {
            if (player != null)
            {
                return player.isSneaking();
            }

            return false;
        }
        @Override
        public int getHealth()
        {
            if (player != null)
            {
                int h = (int)player.getHealth();
                if(h > 20) h = 20;
                return h;  // Scale to 20 range
            }
            else
            {
                return 0;
            }
        }
        @Override
        public int getArmorPoints()
        {
            if (player != null)
            {
                return player.getTotalArmorValue();
            }
            else
            {
                return 0;
            }
        }
        @Override
        public DynmapLocation getBedSpawnLocation()
        {
            /*TODO
            Location loc = offplayer.getBedSpawnLocation();
            if(loc != null) {
                return toLoc(loc);
            }
            */
            return null;
        }
        @Override
        public long getLastLoginTime()
        {
            return 0;
        }
        @Override
        public long getFirstLoginTime()
        {
            return 0;
        }
        @Override
        public boolean hasPrivilege(String privid)
        {
            if(player != null)
                return hasPerm(player, privid);
            return false;
        }
        @Override
        public boolean isOp()
        {
        	return DynmapPlugin.this.isOp(player.username);
    	}
        @Override
        public void sendMessage(String msg)
        {
            player.addChatMessage(msg);
        }
        @Override
        public boolean isInvisible() {
        	if(player != null) {
        		return player.isPotionActive(Potion.invisibility);
        	}
        	return false;
        }
        @Override
        public int getSortWeight() {
            Integer wt = sortWeights.get(getName());
            if (wt != null)
                return wt;
            return 0;
        }
        @Override
        public void setSortWeight(int wt) {
            if (wt == 0) {
                sortWeights.remove(getName());
            }
            else {
                sortWeights.put(getName(), wt);
            }
        }
        @Override
        public boolean hasPermissionNode(String node) {
            if(player != null)
                return hasPermNode(player, node);
            return false;
        }
    }
    /* Handler for generic console command sender */
    public class ForgeCommandSender implements DynmapCommandSender
    {
        private ICommandSender sender;

        protected ForgeCommandSender() {
        	sender = null;
        }

        public ForgeCommandSender(ICommandSender send)
        {
            sender = send;
        }

        @Override
        public boolean hasPrivilege(String privid)
        {
        	return true;
        }

        @Override
        public void sendMessage(String msg)
        {
        	if(sender != null) {
        	    sender.sendChatToPlayer(ChatMessageComponent.createFromText(msg));
        	}
        }

        @Override
        public boolean isConnected()
        {
            return false;
        }
        @Override
        public boolean isOp()
        {
            return true;
        }
        @Override
        public boolean hasPermissionNode(String node) {
            return true;
        } 
    }

    public void loadExtraBiomes() {
    	int cnt = 0;
    	
        for(int i = BiomeMap.LAST_WELL_KNOWN+1; i < BiomeGenBase.biomeList.length; i++) {
            BiomeGenBase bb = BiomeGenBase.biomeList[i];
            if(bb != null) {
                String id = bb.biomeName;
                float tmp = bb.temperature, hum = bb.rainfall;
                BiomeMap m = new BiomeMap(i, id, tmp, hum);
                Log.verboseinfo("Add custom biome [" + m.toString() + "] (" + i + ")");
                cnt++;
            }
        }
        if(cnt > 0)
        	Log.info("Added " + cnt + " custom biome mappings");
    }

    private String[] getBiomeNames() {
        String[] lst = new String[BiomeGenBase.biomeList.length];
        for(int i = 0; i < BiomeGenBase.biomeList.length; i++) {
            BiomeGenBase bb = BiomeGenBase.biomeList[i];
            if (bb != null) {
                lst[i] = bb.biomeName;
            }
        }
        return lst;
    }
    
    private String[] getBlockNames() {
        String[] lst = new String[Block.blocksList.length];
        for(int i = 0; i < Block.blocksList.length; i++) {
            Block b = Block.blocksList[i];
            if(b != null) {
                lst[i] = b.getUnlocalizedName();
                if(lst[i].startsWith("tile.")) {
                    lst[i] = lst[i].substring(5);
                }
            }
        }
        return lst;
    }

    private int[] getBlockMaterialMap() {
        int[] map = new int[Block.blocksList.length];
        ArrayList<Material> mats = new ArrayList<Material>();
        for (int i = 0; i < map.length; i++) {
            Block b = Block.blocksList[i];
            if(b != null) {
                Material mat = b.blockMaterial;
                if (mat != null) {
                    map[i] = mats.indexOf(mat);
                    if (map[i] < 0) {
                        map[i] = mats.size();
                        mats.add(mat);
                    }
                }
                else {
                    map[i] = -1;
                }
            }
        }
        return map;
    }

    public void onEnable()
    {
        server = MinecraftServer.getServer();

        /* Load extra biomes */
        loadExtraBiomes();
        /* Set up player login/quit event handler */
        registerPlayerLoginListener();
        /* Initialize permissions handler */
        permissions = FilePermissions.create();
        if(permissions == null) {
            try {
                permissions = ForgeEssentialsPermissions.create("dynmap");
            } catch (NoClassDefFoundError cnfx) {}
        }
        if(permissions == null) {
            permissions = new OpPermissions(new String[] { "webchat", "marker.icons", "marker.list", "webregister", "stats", "hide.self", "show.self" });
        }
        /* Get and initialize data folder */
        File dataDirectory = new File("dynmap");

        if (dataDirectory.exists() == false)
        {
            dataDirectory.mkdirs();
        }

        /* Get MC version */
        String mcver = server.getMinecraftVersion();

        /* Instantiate core */
        if (core == null)
        {
            core = new DynmapCore();
        }

        /* Inject dependencies */
        core.setPluginJarFile(DynmapMod.jarfile);
        core.setPluginVersion(Version.VER);
        core.setMinecraftVersion(mcver);
        core.setDataFolder(dataDirectory);
        core.setServer(fserver);
        ForgeMapChunkCache.init();
        core.setTriggerDefault(TRIGGER_DEFAULTS);
        core.setBiomeNames(getBiomeNames());
        core.setBlockNames(getBlockNames());
        core.setBlockMaterialMap(getBlockMaterialMap());

        if(!core.initConfiguration(null))
        {
        	return;
        }
        /* Enable core */
        if (!core.enableCore(null))
        {
            return;
        }
        core_enabled = true;
        VersionCheck.runCheck(core);
        // Get per tick time limit
        perTickLimit = core.getMaxTickUseMS() * 1000000;
        // Prep TPS
        lasttick = System.nanoTime();
        tps = 20.0;
        
        /* Register tick handler */
        if(!tickregistered) {
            TickRegistry.registerTickHandler(fserver, Side.SERVER);
            tickregistered = true;
        }

        playerList = core.playerList;
        sscache = new SnapshotCache(core.getSnapShotCacheSize(), core.useSoftRefInSnapShotCache());
        /* Get map manager from core */
        mapManager = core.getMapManager();

        /* Load saved world definitions */
        loadWorlds();
        
        /* Initialized the currently loaded worlds */
        if(server.worldServers != null) { 
            for (WorldServer world : server.worldServers) {
                ForgeWorld w = this.getWorld(world);
                if(DimensionManager.getWorld(world.provider.dimensionId) == null) { /* If not loaded */
                    w.setWorldUnloaded();
                }
            }
        }
        for(ForgeWorld w : worlds.values()) {
            if (core.processWorldLoad(w)) {   /* Have core process load first - fire event listeners if good load after */
                if(w.isLoaded()) {
                    core.listenerManager.processWorldEvent(EventType.WORLD_LOAD, w);
                }
            }
        }
        core.updateConfigHashcode();

        /* Register our update trigger events */
        registerEvents();
        /* Register command hander */
        ICommandManager cm = server.getCommandManager();

        if(cm instanceof CommandHandler) {
        	CommandHandler scm = (CommandHandler)cm;
            scm.registerCommand(new DynmapCommand(this));
            scm.registerCommand(new DmapCommand(this));
            scm.registerCommand(new DmarkerCommand(this));
        }
        /* Submit metrics to mcstats.org */
        initMetrics();

        DynmapCommonAPIListener.apiInitialized(core);

        Log.info("Enabled");
    }

    public void onDisable()
    {
        DynmapCommonAPIListener.apiTerminated();

    	if (metrics != null) {
    		metrics.stop();
    		metrics = null;
    	}
    	/* Save worlds */
        saveWorlds();

        /* Purge tick queue */
        fserver.runqueue.clear();
        
        /* Disable core */
        core.disableCore();
        core_enabled = false;

        if (sscache != null)
        {
            sscache.cleanup();
            sscache = null;
        }
        
        Log.info("Disabled");
    }

    void onCommand(ICommandSender sender, String cmd, String[] args)
    {
        DynmapCommandSender dsender;

        if (sender instanceof EntityPlayer)
        {
            dsender = getOrAddPlayer((EntityPlayer)sender);
        }
        else
        {
            dsender = new ForgeCommandSender(sender);
        }

        core.processCommand(dsender, cmd, cmd, args);
    }

    private DynmapLocation toLoc(World worldObj, double x, double y, double z)
    {
        return new DynmapLocation(DynmapPlugin.this.getWorld(worldObj).getName(), x, y, z);
    }

    private class PlayerTracker implements IPlayerTracker {
		@Override
		public void onPlayerLogin(EntityPlayer player) {			
			if(!core_enabled) return;
            DynmapPlayer dp = getOrAddPlayer(player);
            core.listenerManager.processPlayerEvent(EventType.PLAYER_JOIN, dp);
		}
		@Override
		public void onPlayerLogout(EntityPlayer player) {
			if(!core_enabled) return;
			DynmapPlayer dp = getOrAddPlayer(player);
            core.listenerManager.processPlayerEvent(EventType.PLAYER_QUIT, dp);
            players.remove(player.username);
		}
		@Override
		public void onPlayerChangedDimension(EntityPlayer player) {
            getOrAddPlayer(player);	// Freshen player object reference
		}
		@Override
		public void onPlayerRespawn(EntityPlayer player) {
            getOrAddPlayer(player);	// Freshen player object reference
		}
    }
    private PlayerTracker playerTracker = null;
    
    private void registerPlayerLoginListener()
    {
    	if (playerTracker == null) {
    		playerTracker = new PlayerTracker();
    		GameRegistry.registerPlayerTracker(playerTracker);
    	}
    }

    public class WorldTracker {
    	@ForgeSubscribe
    	public void handleWorldLoad(WorldEvent.Load event) {
			if(!core_enabled) return;
			if(!(event.world instanceof WorldServer)) return;
            final ForgeWorld w = getWorld(event.world);
            /* This event can be called from off server thread, so push processing there */
            core.getServer().scheduleServerTask(new Runnable() {
            	public void run() {
            		if(core.processWorldLoad(w))    /* Have core process load first - fire event listeners if good load after */
            			core.listenerManager.processWorldEvent(EventType.WORLD_LOAD, w);
            	}
            }, 0);
    	}
    	@ForgeSubscribe
    	public void handleWorldUnload(WorldEvent.Unload event) {
			if(!core_enabled) return;
            if(!(event.world instanceof WorldServer)) return;
            final ForgeWorld fw = getWorld(event.world);
            if(fw != null) {
                /* This event can be called from off server thread, so push processing there */
                core.getServer().scheduleServerTask(new Runnable() {
                	public void run() {
                		core.listenerManager.processWorldEvent(EventType.WORLD_UNLOAD, fw);
                		core.processWorldUnload(fw);
                	}
                }, 0);
                /* Set world unloaded (needs to be immediate, since it may be invalid after event) */
                fw.setWorldUnloaded();
                /* Clean up tracker */
                WorldUpdateTracker wut = updateTrackers.remove(fw.getName());
                if(wut != null) wut.world = null;
            }
        }
    	@ForgeSubscribe
    	public void handleChunkLoad(ChunkEvent.Load event) {
			if(!core_enabled) return;
			if(!onchunkgenerate) return;
            if(!(event.world instanceof WorldServer)) return;
			Chunk c = event.getChunk();
			if((c != null) && (c.lastSaveTime == 0)) {	// If new chunk?
				ForgeWorld fw = getWorld(event.world, false);
				if(fw == null) {
					return;
				}
				int ymax = 0;
				ExtendedBlockStorage[] sections = c.getBlockStorageArray();
				for(int i = 0; i < sections.length; i++) {
					if((sections[i] != null) && (sections[i].isEmpty() == false)) {
						ymax = 16*(i+1);
					}
				}
				int x = c.xPosition << 4;
				int z = c.zPosition << 4;
				if(ymax > 0) {
					mapManager.touchVolume(fw.getName(), x, 0, z, x+15, ymax, z+16, "chunkgenerate");
				}
			}
    	}

    	@ForgeSubscribe
    	public void handleChunkPopulate(PopulateChunkEvent.Post event) {
			if(!core_enabled) return;
			if(!onchunkpopulate) return;
            if(!(event.world instanceof WorldServer)) return;
			Chunk c = event.chunkProvider.loadChunk(event.chunkX, event.chunkZ);
			int ymin = 0, ymax = 0;
			if(c != null) {
                ForgeWorld fw = getWorld(event.world, false);
                if (fw == null) return;

                ExtendedBlockStorage[] sections = c.getBlockStorageArray();
				for(int i = 0; i < sections.length; i++) {
					if((sections[i] != null) && (sections[i].isEmpty() == false)) {
						ymax = 16*(i+1);
					}
				}
				int x = c.xPosition << 4;
				int z = c.zPosition << 4;
				if(ymax > 0)
					mapManager.touchVolume(fw.getName(), x, ymin, z, x+15, ymax, z+16, "chunkpopulate");
			}
    	}
    	@ForgeSubscribe
    	public void handleCommandEvent(CommandEvent event) {
    		if(event.isCanceled()) return;
    		if(event.command.getCommandName().equals("say")) {
    			String s = "";
    			for(String p : event.parameters) {
    				s += p + " ";
    			}
    			s = s.trim();
				ChatMessage cm = new ChatMessage();
				cm.message = s;
				cm.sender = null;
				msgqueue.add(cm);
    		}
    	}
    }
    
    private boolean onblockchange = false;
    private boolean onlightingchange = false;
    private boolean onchunkpopulate = false;
    private boolean onchunkgenerate = false;
    private boolean onblockchange_with_id = false;
    
    
    public class WorldUpdateTracker implements IWorldAccess {
    	String worldid;
    	World world;
		@Override
		public void markBlockForUpdate(int x, int y, int z) {
            sscache.invalidateSnapshot(worldid, x, y, z);
            if(onblockchange) {
            	BlockUpdateRec r = new BlockUpdateRec();
            	r.w = world;
            	r.wid = worldid;
            	r.x = x; r.y = y; r.z = z;
            	blockupdatequeue.add(r);
            }
		}
		@Override
		public void markBlockForRenderUpdate(int x, int y, int z) {
            sscache.invalidateSnapshot(worldid, x, y, z);
            if(onlightingchange) {
            	mapManager.touch(worldid, x, y, z, "lightingchange");
            }
		}
		@Override
		public void markBlockRangeForRenderUpdate(int x1, int y1, int z1,
				int x2, int y2, int z2) {
		}
		@Override
		public void playSound(String var1, double var2, double var4,
				double var6, float var8, float var9) {
		}
        @Override
	    public void playSoundToNearExcept(EntityPlayer entityplayer, String s, double d0, double d1, double d2, float f, float f1) {
        }

		@Override
		public void spawnParticle(String var1, double var2, double var4,
				double var6, double var8, double var10, double var12) {
		}
        @Override
	    public void onEntityCreate(Entity entity) {
        }
        @Override
        public void onEntityDestroy(Entity entity) {
        }
        @Override
        public void playRecord(String s, int i, int j, int k) {
        }
        @Override
        public void broadcastSound(int i, int j, int k, int l, int i1) {
        }
        @Override
        public void playAuxSFX(EntityPlayer entityplayer, int i, int j, int k, int l, int i1) {
        }
        @Override
        public void destroyBlockPartially(int i, int j, int k, int l, int i1) {
        }
    }
    
    private WorldTracker worldTracker = null;
    private HashMap<String, WorldUpdateTracker> updateTrackers = new HashMap<String, WorldUpdateTracker>();
    
    private void registerEvents()
    {
    	if(worldTracker == null) {
    		worldTracker = new WorldTracker();
    		MinecraftForge.EVENT_BUS.register(worldTracker);
    	}
        // To trigger rendering.
        onblockchange = core.isTrigger("blockupdate");
        onlightingchange = core.isTrigger("lightingupdate");
        onchunkpopulate = core.isTrigger("chunkpopulate");
        onchunkgenerate = core.isTrigger("chunkgenerate");
        onblockchange_with_id = core.isTrigger("blockupdate-with-id");
        if(onblockchange_with_id)
        	onblockchange = true;
    }

    private ForgeWorld getWorldByName(String name) {
    	return worlds.get(name);
    }
    
    private ForgeWorld getWorld(World w) {
    	return getWorld(w, true);
    }
    
    private ForgeWorld getWorld(World w, boolean add_if_not_found) {
    	if(last_world == w) {
    		return last_fworld;
    	}
    	String wname = ForgeWorld.getWorldName(w);
    	
    	for(ForgeWorld fw : worlds.values()) {
			if(fw.getRawName().equals(wname)) {
				last_world = w;
	           	last_fworld = fw;
           		if(fw.isLoaded() == false) {
       				fw.setWorldLoaded(w);
       				// Add tracker
       	    		WorldUpdateTracker wit = new WorldUpdateTracker();
       	    		wit.worldid = fw.getName();
       	    		wit.world = w;
       	    		updateTrackers.put(fw.getName(), wit);
       	    		w.addWorldAccess(wit);
           		}
    			return fw;
    		}
    	}
    	ForgeWorld fw = null;
    	if(add_if_not_found) {
    		/* Add to list if not found */
    		fw = new ForgeWorld(w);
    		worlds.put(fw.getName(), fw);
    		// Add tracker
    		WorldUpdateTracker wit = new WorldUpdateTracker();
    		wit.worldid = fw.getName();
    		wit.world = w;
    		updateTrackers.put(fw.getName(), wit);
    		w.addWorldAccess(wit);
    	}
		last_world = w;
		last_fworld = fw;
    	return fw;
    }

    /*
    private void removeWorld(ForgeWorld fw) {
    	WorldUpdateTracker wit = updateTrackers.remove(fw.getName());
    	if(wit != null) {
    		//fw.getWorld().removeWorldAccess(wit);
    	}
    	worlds.remove(fw.getName());
    	if(last_fworld == fw) {
			last_world = null;
			last_fworld = null;
    	}
    }
    */

    private void initMetrics() {
        try {
        	Mod m = DynmapMod.class.getAnnotation(Mod.class);
            metrics = new ForgeMetrics(m.name(), m.version());
            ;
            ForgeMetrics.Graph features = metrics.createGraph("Features Used");
            
            features.addPlotter(new ForgeMetrics.Plotter("Internal Web Server") {
                @Override
                public int getValue() {
                    if (!core.configuration.getBoolean("disable-webserver", false))
                        return 1;
                    return 0;
                }
            });
            features.addPlotter(new ForgeMetrics.Plotter("Login Security") {
                @Override
                public int getValue() {
                    if(core.configuration.getBoolean("login-enabled", false))
                        return 1;
                    return 0;
                }
            });
            features.addPlotter(new ForgeMetrics.Plotter("Player Info Protected") {
                @Override
                public int getValue() {
                    if(core.player_info_protected)
                        return 1;
                    return 0;
                }
            });
            
            ForgeMetrics.Graph maps = metrics.createGraph("Map Data");
            maps.addPlotter(new ForgeMetrics.Plotter("Worlds") {
                @Override
                public int getValue() {
                    if(core.mapManager != null)
                        return core.mapManager.getWorlds().size();
                    return 0;
                }
            });
            maps.addPlotter(new ForgeMetrics.Plotter("Maps") {
                @Override
                public int getValue() {
                    int cnt = 0;
                    if(core.mapManager != null) {
                        for(DynmapWorld w :core.mapManager.getWorlds()) {
                            cnt += w.maps.size();
                        }
                    }
                    return cnt;
                }
            });
            maps.addPlotter(new ForgeMetrics.Plotter("HD Maps") {
                @Override
                public int getValue() {
                    int cnt = 0;
                    if(core.mapManager != null) {
                        for(DynmapWorld w :core.mapManager.getWorlds()) {
                            for(MapType mt : w.maps) {
                                if(mt instanceof HDMap) {
                                    cnt++;
                                }
                            }
                        }
                    }
                    return cnt;
                }
            });
            for (String mod : modsused) {
                features.addPlotter(new ForgeMetrics.Plotter(mod + " Blocks") {
                    @Override
                    public int getValue() {
                        return 1;
                    }
                });
            }
            
            metrics.start();
        } catch (IOException e) {
            // Failed to submit the stats :-(
        }
    }

    private void saveWorlds() {
        File f = new File(core.getDataFolder(), "forgeworlds.yml");
        ConfigurationNode cn = new ConfigurationNode(f);
        ArrayList<HashMap<String,Object>> lst = new ArrayList<HashMap<String,Object>>();
        for(DynmapWorld fw : core.mapManager.getWorlds()) {
            HashMap<String, Object> vals = new HashMap<String, Object>();
            vals.put("name", fw.getRawName());
            vals.put("height",  fw.worldheight);
            vals.put("sealevel", fw.sealevel);
            vals.put("nether",  fw.isNether());
            vals.put("the_end",  ((ForgeWorld)fw).isTheEnd());
            vals.put("title", fw.getTitle());
            lst.add(vals);
        }
        cn.put("worlds", lst);
        cn.put("isMCPC", isMCPC);
        cn.put("useSaveFolderAsName", useSaveFolder);
        cn.put("maxWorldHeight", ForgeWorld.getMaxWorldHeight());

        cn.save();
    }
    private void loadWorlds() {
        isMCPC = MinecraftServer.getServer().getServerModName().contains("mcpc");
        File f = new File(core.getDataFolder(), "forgeworlds.yml");
        if(f.canRead() == false) {
            useSaveFolder = true;
            if (isMCPC) {
                ForgeWorld.setMCPCMapping();
            }
            else {
                ForgeWorld.setSaveFolderMapping();
            }
            return;
        }
        ConfigurationNode cn = new ConfigurationNode(f);
        cn.load();
        // If defined, use maxWorldHeight
        ForgeWorld.setMaxWorldHeight(cn.getInteger("maxWorldHeight", 256));
        
        // If existing, only switch to save folder if MCPC+
        useSaveFolder = isMCPC;
        // If setting defined, use it 
        if (cn.containsKey("useSaveFolderAsName")) {
            useSaveFolder = cn.getBoolean("useSaveFolderAsName", useSaveFolder);
        }
        if (isMCPC) {
            ForgeWorld.setMCPCMapping();
        }
        else if (useSaveFolder) {
            ForgeWorld.setSaveFolderMapping();
        }
        // If inconsistent between MCPC and non-MCPC
        if (isMCPC != cn.getBoolean("isMCPC", false)) {
            return;
        }
        List<Map<String,Object>> lst = cn.getMapList("worlds");
        if(lst == null) {
            Log.warning("Discarding bad forgeworlds.yml");
            return;
        }
        
        for(Map<String,Object> world : lst) {
            try {
                String name = (String)world.get("name");
                int height = (Integer)world.get("height");
                int sealevel = (Integer)world.get("sealevel");
                boolean nether = (Boolean)world.get("nether");
                boolean theend = (Boolean)world.get("the_end");
                String title = (String)world.get("title");
                if(name != null) {
                    ForgeWorld fw = new ForgeWorld(name, height, sealevel, nether, theend, title);
                    fw.setWorldUnloaded();
                    core.processWorldLoad(fw);
                    worlds.put(fw.getName(), fw);
                }
            } catch (Exception x) {
                Log.warning("Unable to load saved worlds from forgeworlds.yml");
                return;
            }
        }
    }
    public void serverStarted() {
        if (core != null) {
            core.serverStarted();
        }
    }
}

class DynmapCommandHandler extends CommandBase
{
    private String cmd;
    private DynmapPlugin plugin;

    public DynmapCommandHandler(String cmd, DynmapPlugin p)
    {
        this.cmd = cmd;
        this.plugin = p;
    }

    public String getCommandName()
    {
        return cmd;
    }

    public void processCommand(ICommandSender sender, String[] args)
    {
        plugin.onCommand(sender, cmd, args);
    }
    
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public String getCommandUsage(ICommandSender icommandsender) {
        return "Run /" + cmd + " help for details on using command";
    }
}

class DynmapCommand extends DynmapCommandHandler {
    DynmapCommand(DynmapPlugin p) {
        super("dynmap", p);
    }
}
class DmapCommand extends DynmapCommandHandler {
    DmapCommand(DynmapPlugin p) {
        super("dmap", p);
    }
}
class DmarkerCommand extends DynmapCommandHandler {
    DmarkerCommand(DynmapPlugin p) {
        super("dmarker", p);
    }
}

