package org.dynmap.forge;
/**
 * Forge specific implementation of DynmapWorld
 */
import java.util.List;

import net.minecraft.src.ChunkCoordinates;
import net.minecraft.src.EnumSkyBlock;
import net.minecraft.src.World;
import net.minecraft.src.WorldProvider;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapLocation;
import org.dynmap.DynmapWorld;
import org.dynmap.utils.MapChunkCache;

public class ForgeWorld extends DynmapWorld
{
    private final World world;
    private final boolean skylight;
    private final boolean isnether;
    private final boolean istheend;
    private final String env;

    public static String getWorldName(World w) {
    	String n = "world"; // TODO: get right name
    	int dim = w.worldProvider.worldType;
    	switch(dim) {
    		case 0:
    			break;
    		case -1:
    			n += "_nether";
    			break;
    		case 1:
    			n += "_the_end";
    			break;
			default:
				n += "_" + dim;
				break;
    	}
        return n;
    }
    
    public ForgeWorld(World w)
    {
        super(getWorldName(w), w.getHeight(), 64);
        world = w;
        WorldProvider wp = w.worldProvider;
        isnether = (wp instanceof net.minecraft.src.WorldProviderHell);
        istheend = (wp instanceof net.minecraft.src.WorldProviderEnd);
        skylight = !(isnether || istheend);

        if (isnether)
        {
            env = "nether";
        }
        else if (istheend)
        {
            env = "the_end";
        }
        else
        {
            env = "normal";
        }
    }
    /* Test if world is nether */
    @Override
    public boolean isNether()
    {
        return isnether;
    }
    /* Get world spawn location */
    @Override
    public DynmapLocation getSpawnLocation()
    {
        DynmapLocation dloc = new DynmapLocation();
        ChunkCoordinates sloc = world.getSpawnPoint();
        dloc.x = sloc.posX;
        dloc.y = sloc.posY;
        dloc.z = sloc.posZ;
        dloc.world = this.getName();
        return dloc;
    }
    /* Get world time */
    @Override
    public long getTime()
    {
        return world.getWorldTime();
    }
    /* World is storming */
    @Override
    public boolean hasStorm()
    {
        return world.isRaining();
    }
    /* World is thundering */
    @Override
    public boolean isThundering()
    {
        return world.isThundering();
    }
    /* World is loaded */
    @Override
    public boolean isLoaded()
    {
        return (world != null);
    }
    /* Get light level of block */
    @Override
    public int getLightLevel(int x, int y, int z)
    {
        return world.getBlockLightValue(x,  y,  z);
    }
    /* Get highest Y coord of given location */
    @Override
    public int getHighestBlockYAt(int x, int z)
    {
        return world.getHeightValue(x,  z);
    }
    /* Test if sky light level is requestable */
    @Override
    public boolean canGetSkyLightLevel()
    {
        return skylight;
    }
    /* Return sky light level */
    @Override
    public int getSkyLightLevel(int x, int y, int z)
    {
    	return world.getSavedLightValue(EnumSkyBlock.Sky, x, y, z);
    }
    /**
     * Get world environment ID (lower case - normal, the_end, nether)
     */
    @Override
    public String getEnvironment()
    {
        return env;
    }
    /**
     * Get map chunk cache for world
     */
    @Override
    public MapChunkCache getChunkCache(List<DynmapChunk> chunks)
    {
        ForgeMapChunkCache c = new ForgeMapChunkCache();
        c.setChunks(this, chunks);
        return c;
    }

    public World getWorld()
    {
        return world;
    }
}
