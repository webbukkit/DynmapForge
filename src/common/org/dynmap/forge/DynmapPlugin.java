package org.dynmap.forge;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.server.MinecraftServer;
import net.minecraft.src.BanList;
import net.minecraft.src.Chunk;
import net.minecraft.src.CommandBase;
import net.minecraft.src.CommandHandler;
import net.minecraft.src.Entity;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.ICommand;
import net.minecraft.src.ICommandManager;
import net.minecraft.src.ICommandSender;
import net.minecraft.src.IWorldAccess;
import net.minecraft.src.NetHandler;
import net.minecraft.src.Packet3Chat;
import net.minecraft.src.ServerCommandManager;
import net.minecraft.src.StringUtils;
import net.minecraft.src.World;
import net.minecraft.src.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapLocation;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapManager;
import org.dynmap.MapType;
import org.dynmap.PlayerList;
import org.dynmap.common.BiomeMap;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.common.DynmapServerInterface;
import org.dynmap.common.DynmapListenerManager.EventType;
import org.dynmap.hdmap.HDMap;
import org.dynmap.utils.MapChunkCache;

import cpw.mods.fml.common.IPlayerTracker;
import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.Side;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.network.IChatListener;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.server.FMLServerHandler;

public class DynmapPlugin
{
    private DynmapCore core;
    private String version;
    public SnapshotCache sscache;
    private boolean has_spout = false;
    public PlayerList playerList;
    private MapManager mapManager;
    private net.minecraft.server.MinecraftServer server;
    public static DynmapPlugin plugin;
    private ChatHandler chathandler;
    
    private HashMap<String, ForgeWorld> worlds = new HashMap<String, ForgeWorld>();
    private World last_world;
    private ForgeWorld last_fworld;
    
    
    private static class TaskRecord implements Comparable
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
			if(handler.isServerHandler() && (!message.message.startsWith("/"))) {
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
    
    public DynmapPlugin()
    {
        plugin = this;
    }

    /**
     * Server access abstraction class
     */
    public class ForgeServer implements DynmapServerInterface, ITickHandler
    {
        /* Chunk load handling */
        private Object loadlock = new Object();
        private int chunks_in_cur_tick = 0;
        private int last_tick;
        /* Server thread scheduler */
        private Object schedlock = new Object();
        private long cur_tick;
        private long next_id;
        private PriorityQueue<TaskRecord> runqueue = new PriorityQueue<TaskRecord>();

        public ForgeServer() {
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
            List players = server.getConfigurationManager().playerEntityList;
            int pcnt = players.size();
            DynmapPlayer[] dplay = new DynmapPlayer[pcnt];

            for (int i = 0; i < pcnt; i++)
            {
                EntityPlayer p = (EntityPlayer)players.get(i);
                dplay[i] = new ForgePlayer(p);
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
            List players = server.getConfigurationManager().playerEntityList;

            for (Object o : players)
            {
                EntityPlayer p = (EntityPlayer)o;

                if (p.getEntityName().equalsIgnoreCase(name))
                {
                    return new ForgePlayer(p);
                }
            }

            return null;
        }
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
        public <T> Future<T> callSyncMethod(Callable<T> task)
        {
            TaskRecord tr = new TaskRecord();
            FutureTask<T> ft = new FutureTask<T>(task);
            tr.future = ft;

            /* Add task record to queue */
            synchronized (schedlock)
            {
                tr.id = next_id++;
                tr.ticktorun = cur_tick;
                runqueue.add(tr);
            }

            return ft;
        }
        @Override
        public String getServerName()
        {
            return server.getServerHostname();
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
            server.sendChatToPlayer(msg);
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
            /*TODO
            OfflinePlayer p = getServer().getOfflinePlayer(player);
            if(p.isBanned())
                return new HashSet<String>();
            Set<String> rslt = permissions.hasOfflinePermissions(player, perms);
            if (rslt == null) {
                rslt = new HashSet<String>();
                if(p.isOp()) {
                    rslt.addAll(perms);
                }
            }
            */
            Set<String> rslt = new HashSet<String>();
            Set ops = server.getConfigurationManager().getOps();

            if (ops.contains(player))
            {
                rslt.addAll(perms);
            }

            return rslt;
        }
        @Override
        public boolean checkPlayerPermission(String player, String perm)
        {
            /*TODO
            OfflinePlayer p = getServer().getOfflinePlayer(player);
            if(p.isBanned())
                return false;
            return permissions.hasOfflinePermission(player, perm);
            */
            Set ops = server.getConfigurationManager().getOps();
            return ops.contains(player);
        }
        /**
         * Render processor helper - used by code running on render threads to request chunk snapshot cache from server/sync thread
         */
        @Override
        public MapChunkCache createMapChunkCache(DynmapWorld w, List<DynmapChunk> chunks,
                boolean blockdata, boolean highesty, boolean biome, boolean rawbiome)
        {
            MapChunkCache c = w.getChunkCache(chunks);

            if (w.visibility_limits != null)
            {
                for (MapChunkCache.VisibilityLimit limit: w.visibility_limits)
                {
                    c.setVisibleRange(limit);
                }

                c.setHiddenFillStyle(w.hiddenchunkstyle);
                c.setAutoGenerateVisbileRanges(w.do_autogenerate);
            }

            if (w.hidden_limits != null)
            {
                for (MapChunkCache.VisibilityLimit limit: w.hidden_limits)
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

            while (!cc.isDoneLoading())
            {
                synchronized (loadlock)
                {
                	int tick = server.getTickCounter();
                    if (last_tick != tick) /* New tick? */
                    {
                        chunks_in_cur_tick = mapManager.getMaxChunkLoadsPerTick();
                        last_tick = tick;
                    }
                }

                Future<Boolean> f = core.getServer().callSyncMethod(new Callable<Boolean>()
                {
                    public Boolean call() throws Exception
                    {
                        boolean exhausted;

                        synchronized (loadlock)
                        {
                            if (chunks_in_cur_tick > 0)
                            {
                                chunks_in_cur_tick -= cc.loadChunks(chunks_in_cur_tick);
                            }

                            exhausted = (chunks_in_cur_tick == 0);
                        }

                        return exhausted;
                    }
                });
                Boolean delay;

                try
                {
                    delay = f.get();
                }
                catch (CancellationException cx)
                {
                    return null;
                }
                catch (Exception ix)
                {
                    Log.severe(ix);
                    return null;
                }

                if ((delay != null) && delay.booleanValue())
                {
                    try
                    {
                        Thread.sleep(25);
                    }
                    catch (InterruptedException ix) {}
                }
            }

            return c;
        }
        @Override
        public int getMaxPlayers()
        {
            return server.getMaxPlayers();
        }
        @Override
        public int getCurrentPlayers()
        {
            return server.getConfigurationManager().playerEntityList.size();
        }

		@Override
		public void tickStart(EnumSet<TickType> type, Object... tickData) {
		}

		@Override
		public void tickEnd(EnumSet<TickType> type, Object... tickData) {
			if (type.contains(TickType.SERVER)) {
				boolean done = false;
				TaskRecord tr = null;
				
				synchronized(schedlock) {
					cur_tick++;
					tr = runqueue.peek();
					/* Nothing due to run */
					if((tr == null) || (tr.ticktorun > cur_tick)) {
						done = true;
					}
					else {
						tr = runqueue.poll();
					}
				}
				while (!done) {
					tr.future.run();
					synchronized(schedlock) {
						tr = runqueue.peek();
						/* Nothing due to run */
						if((tr == null) || (tr.ticktorun > cur_tick)) {
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
                		dp = new ForgePlayer(cm.sender);
                    core.listenerManager.processChatEvent(EventType.PLAYER_CHAT, dp, cm.message);
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
			return Loader.isModLoaded(name);
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
            return player.getEntityName();
        }
        @Override
        public String getDisplayName()
        {
            return player.getEntityName();
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
            /*TODO
            if(player != null)
                return player.getAddress();
                */
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
                return player.getHealth();
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
        	return server.getConfigurationManager().getOps().contains(player.username);
        }
        @Override
        public boolean isOp()
        {
        	return server.getConfigurationManager().getOps().contains(player.username);
    	}
        @Override
        public void sendMessage(String msg)
        {
        	player.sendChatToPlayer(msg);
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
        		sender.sendChatToPlayer(msg);
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
    }

    public void onEnable()
    {
        server = MinecraftServer.getServer();
        /* Set up player login/quit event handler */
        registerPlayerLoginListener();
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
        core.setPluginVersion(mod_Dynmap.VERSION);
        core.setMinecraftVersion(mcver);
        core.setDataFolder(dataDirectory);
        ForgeServer fserver = new ForgeServer();
        core.setServer(fserver);
        ForgeMapChunkCache.init();

        if(!core.initConfiguration(null))
        {
        	return;
        }
        /* Enable core */
        if (!core.enableCore(null))
        {
            return;
        }
        /* Register tick handler */
        TickRegistry.registerTickHandler(fserver, Side.SERVER);

        playerList = core.playerList;
        sscache = new SnapshotCache(core.getSnapShotCacheSize());
        /* Get map manager from core */
        mapManager = core.getMapManager();

        /* Initialized the currently loaded worlds */
        for (WorldServer world : server.worldServers)
        {
            ForgeWorld w = this.getWorld(world);

            if (core.processWorldLoad(w))   /* Have core process load first - fire event listeners if good load after */
            {
                core.listenerManager.processWorldEvent(EventType.WORLD_LOAD, w);
            }
        }

        /* Register our update trigger events */
        registerEvents();
        /* Register command hander */
        ICommandManager cm = server.getCommandManager();

        if(cm instanceof CommandHandler) {
        	CommandHandler scm = (CommandHandler)cm;
        	scm.registerCommand(new DynmapCommandHandler("dynmap"));
        	scm.registerCommand(new DynmapCommandHandler("dmap"));
        	scm.registerCommand(new DynmapCommandHandler("dmarker"));
        }
        Log.info("Enabled");
    }

    public void onDisable()
    {
        /* Disable core */
        core.disableCore();

        if (sscache != null)
        {
            sscache.cleanup();
            sscache = null;
        }

        Log.info("Disabled");
    }

    private class DynmapCommandHandler extends CommandBase
    {
        private String cmd;

        public DynmapCommandHandler(String cmd)
        {
            this.cmd = cmd;
        }

        public String getCommandName()
        {
            return cmd;
        }

        public void processCommand(ICommandSender sender, String[] args)
        {
            onCommand(sender, cmd, args);
        }
    }

    private void onCommand(ICommandSender sender, String cmd, String[] args)
    {
        DynmapCommandSender dsender;

        if (sender instanceof EntityPlayer)
        {
            dsender = new ForgePlayer((EntityPlayer)sender);
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
            DynmapPlayer dp = new ForgePlayer(player);
            core.listenerManager.processPlayerEvent(EventType.PLAYER_JOIN, dp);
		}
		@Override
		public void onPlayerLogout(EntityPlayer player) {
            DynmapPlayer dp = new ForgePlayer(player);
            core.listenerManager.processPlayerEvent(EventType.PLAYER_QUIT, dp);
		}
		@Override
		public void onPlayerChangedDimension(EntityPlayer player) {
		}
		@Override
		public void onPlayerRespawn(EntityPlayer player) {
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
            core.updateConfigHashcode();
            ForgeWorld w = getWorld(event.world);
            if(core.processWorldLoad(w))    /* Have core process load first - fire event listeners if good load after */
                core.listenerManager.processWorldEvent(EventType.WORLD_LOAD, w);
    	}
    	@ForgeSubscribe
    	public void handleWorldUnload(WorldEvent.Unload event) {
            core.updateConfigHashcode();
            ForgeWorld fw = getWorld(event.world);
            DynmapWorld w = core.getWorld(fw.getName());
            if(w != null)
                core.listenerManager.processWorldEvent(EventType.WORLD_UNLOAD, w);
            removeWorld(fw);
    	}
    	@ForgeSubscribe
    	public void handleChunkLoad(ChunkEvent event) {
    		if(event instanceof ChunkEvent.Load) { 
    			Chunk c = event.getChunk();
    			if((c != null) && (c.lastSaveTime == 0)) {	// If new chunk?
    				ForgeWorld fw = getWorld(event.world, false);
    				if(fw == null) {
    					return;
    				}
    				int x = c.xPosition << 4;
    				int z = c.zPosition << 4;
    				mapManager.touchVolume(fw.getName(), x, 0, z, x+15, 128, z+16, "chunkpopulate");
    			}
    		}
    		
    	}
    }
    
    public class WorldUpdateTracker implements IWorldAccess {
    	String worldid;
		@Override
		public void markBlockNeedsUpdate(int x, int y, int z) {
            sscache.invalidateSnapshot(worldid, x, y, z);
            mapManager.touch(worldid, x, y, z, "blockupdate");
		}
		@Override
		public void markBlockNeedsUpdate2(int x, int y, int z) {
            sscache.invalidateSnapshot(worldid, x, y, z);
            mapManager.touch(worldid, x, y, z, "blockupdate2");
		}
		@Override
		public void markBlockRangeNeedsUpdate(int x1, int y1, int z1,
				int x2, int y2, int z2) {
            sscache.invalidateSnapshot(worldid, x1, y1, z1, x2, y2, z2);
            mapManager.touchVolume(worldid, x1, y1, z1, x2, y2, z2, "rangeupdate");
		}
		@Override
		public void playSound(String var1, double var2, double var4,
				double var6, float var8, float var9) {
		}
		@Override
		public void spawnParticle(String var1, double var2, double var4,
				double var6, double var8, double var10, double var12) {
		}
		@Override
		public void obtainEntitySkin(Entity var1) {
		}
		@Override
		public void releaseEntitySkin(Entity var1) {
		}
		@Override
		public void playRecord(String var1, int var2, int var3, int var4) {
		}
		@Override
		public void playAuxSFX(EntityPlayer var1, int var2, int var3, int var4,
				int var5, int var6) {			
		}
		@Override
		public void destroyBlockPartially(int var1, int var2, int var3,
				int var4, int var5) {
		}
		@Override
		public void func_82746_a(int var1, int var2, int var3, int var4,
				int var5) {
		}
		@Override
		public void func_85102_a(EntityPlayer var1, String var2, double var3,
				double var5, double var7, float var9, float var10) {
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
    	for(ForgeWorld fw : worlds.values()) {
    		if(fw.getWorld() == w) {
    			last_world = w;
    			last_fworld = fw;
    			return fw;
    		}
    	}
    	ForgeWorld fw = null;
    	if(add_if_not_found) {
    		/* Add to list if not found */
    		fw = new ForgeWorld(w);
    		worlds.put(fw.getName(), fw);
    		WorldUpdateTracker wit = new WorldUpdateTracker();
    		wit.worldid = fw.getName();
    		updateTrackers.put(fw.getName(), wit);
    		w.addWorldAccess(wit);
    	}
		last_world = w;
		last_fworld = fw;
    	return fw;
    }
    
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

}
