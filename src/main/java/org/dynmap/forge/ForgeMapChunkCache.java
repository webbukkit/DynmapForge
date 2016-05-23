package org.dynmap.forge;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.ChunkProviderServer;

import org.dynmap.DynmapChunk;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.common.BiomeMap;
import org.dynmap.forge.SnapshotCache.SnapshotRec;
import org.dynmap.hdmap.HDBlockModels;
import org.dynmap.renderer.RenderPatchFactory;
import org.dynmap.utils.DynIntHashMap;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.MapIterator;
import org.dynmap.utils.BlockStep;
import org.dynmap.utils.VisibilityLimit;

/**
 * Container for managing chunks - dependent upon using chunk snapshots, since rendering is off server thread
 */
public class ForgeMapChunkCache extends MapChunkCache
{
    private static boolean init = false;
    private static Field unloadqueue = null;
    private static Field currentchunkloader = null;
    private static Field updateEntityTick = null;
    /* AnvilChunkLoader fields */
    private static Field chunksToRemove = null; // Map
    private static Field pendingAnvilChunksCoordinates = null; // Set
    private static Method writechunktonbt = null; // writeChunkToNBT(Chunk c, World w, NBTTagCompound nbt)

    /* AnvilChunkLoaderPending fields */
    private static Field chunkCoord = null;
    private static Field nbtTag = null;
    
    private World w;
    private DynmapWorld dw;
    private ChunkProviderServer cps;
    private int nsect;
    private List<DynmapChunk> chunks;
    private ListIterator<DynmapChunk> iterator;
    private int x_min, x_max, z_min, z_max;
    private int x_dim;
    private boolean biome, biomeraw, highesty, blockdata;
    private HiddenChunkStyle hidestyle = HiddenChunkStyle.FILL_AIR;
    private List<VisibilityLimit> visible_limits = null;
    private List<VisibilityLimit> hidden_limits = null;
    private boolean isempty = true;
    private int snapcnt;
    private ChunkSnapshot[] snaparray; /* Index = (x-x_min) + ((z-z_min)*x_dim) */
    private DynIntHashMap[] snaptile;
    private byte[][] sameneighborbiomecnt;
    private BiomeMap[][] biomemap;
    private boolean[][] isSectionNotEmpty; /* Indexed by snapshot index, then by section index */
    private Set<?> queue = null;

    private static final BlockStep unstep[] = { BlockStep.X_MINUS, BlockStep.Y_MINUS, BlockStep.Z_MINUS,
            BlockStep.X_PLUS, BlockStep.Y_PLUS, BlockStep.Z_PLUS
                                              };

    private static BiomeMap[] biome_to_bmap;

    private static final int getIndexInChunk(int cx, int cy, int cz) {
        return (cy << 8) | (cz << 4) | cx;
    }

    /**
     * Iterator for traversing map chunk cache (base is for non-snapshot)
     */
    public class OurMapIterator implements MapIterator
    {
        private int x, y, z, chunkindex, bx, bz;
        private ChunkSnapshot snap;
        private BlockStep laststep;
        private int typeid = -1;
        private int blkdata = -1;
        private final int worldheight;
        private final int x_base;
        private final int z_base;

        OurMapIterator(int x0, int y0, int z0)
        {
            x_base = x_min << 4;
            z_base = z_min << 4;

            if (biome)
            {
                biomePrep();
            }

            initialize(x0, y0, z0);
            worldheight = w.getHeight();
        }
        @Override
        public final void initialize(int x0, int y0, int z0)
        {
            this.x = x0;
            this.y = y0;
            this.z = z0;
            this.chunkindex = ((x >> 4) - x_min) + (((z >> 4) - z_min) * x_dim);
            this.bx = x & 0xF;
            this.bz = z & 0xF;

            if((chunkindex >= snapcnt) || (chunkindex < 0)) {
                snap = EMPTY;
            }
            else {
                snap = snaparray[chunkindex];
            }

            laststep = BlockStep.Y_MINUS;

            if ((y >= 0) && (y < worldheight))
            {
                typeid = blkdata = -1;
            }
            else
            {
                typeid = blkdata = 0;
            }
        }
        @Override
        public final int getBlockTypeID()
        {
            if (typeid < 0)
            {
                typeid = snap.getBlockTypeId(bx, y, bz);
            }

            return typeid;
        }
        @Override
        public final int getBlockData()
        {
            if (blkdata < 0)
            {
                blkdata = snap.getBlockData(bx, y, bz);
            }

            return blkdata;
        }
        @Override
        public int getBlockSkyLight()
        {
            try
            {
                return snap.getBlockSkyLight(bx, y, bz);
            }
            catch (ArrayIndexOutOfBoundsException aioobx)
            {
                return 15;
            }
        }
        @Override
        public final int getBlockEmittedLight()
        {
            try
            {
                return snap.getBlockEmittedLight(bx, y, bz);
            }
            catch (ArrayIndexOutOfBoundsException aioobx)
            {
                return 0;
            }
        }
        private void biomePrep()
        {
            if (sameneighborbiomecnt != null)
            {
                return;
            }

            int x_size = x_dim << 4;
            int z_size = (z_max - z_min + 1) << 4;
            sameneighborbiomecnt = new byte[x_size][];
            biomemap = new BiomeMap[x_size][];

            for (int i = 0; i < x_size; i++)
            {
                sameneighborbiomecnt[i] = new byte[z_size];
                biomemap[i] = new BiomeMap[z_size];
            }

            for (int i = 0; i < x_size; i++)
            {
                for (int j = 0; j < z_size; j++)
                {
                    if (j == 0)
                        initialize(i + x_base, 64, z_base);
                    else
                        stepPosition(BlockStep.Z_PLUS);

                    int bb = snap.getBiome(bx, bz);
                    BiomeMap bm = BiomeMap.byBiomeID(bb);

                    biomemap[i][j] = bm;
                    int cnt = 0;

                    if (i > 0)
                    {
                        if (bm == biomemap[i - 1][j])  /* Same as one to left */
                        {
                            cnt++;
                            sameneighborbiomecnt[i - 1][j]++;
                        }

                        if ((j > 0) && (bm == biomemap[i - 1][j - 1]))
                        {
                            cnt++;
                            sameneighborbiomecnt[i - 1][j - 1]++;
                        }

                        if ((j < (z_size - 1)) && (bm == biomemap[i - 1][j + 1]))
                        {
                            cnt++;
                            sameneighborbiomecnt[i - 1][j + 1]++;
                        }
                    }

                    if ((j > 0) && (biomemap[i][j] == biomemap[i][j - 1]))  /* Same as one to above */
                    {
                        cnt++;
                        sameneighborbiomecnt[i][j - 1]++;
                    }

                    sameneighborbiomecnt[i][j] = (byte)cnt;
                }
            }
        }
        @Override
        public final BiomeMap getBiome()
        {
            try
            {
                return biomemap[x - x_base][z - z_base];
            }
            catch (Exception ex)
            {
                return BiomeMap.NULL;
            }
        }
        @Override
        public final int getSmoothGrassColorMultiplier(int[] colormap)
        {
            int mult = 0xFFFFFF;

            try
            {
                int rx = x - x_base;
                int rz = z - z_base;
                BiomeMap bm = biomemap[rx][rz];

                if (sameneighborbiomecnt[rx][rz] >= (byte)8)    /* All neighbors same? */
                {
                    mult = bm.getModifiedGrassMultiplier(colormap[bm.biomeLookup()]);
                }
                else
                {
                    int raccum = 0;
                    int gaccum = 0;
                    int baccum = 0;

                    for (int xoff = -1; xoff < 2; xoff++)
                    {
                        for (int zoff = -1; zoff < 2; zoff++)
                        {
                            bm = biomemap[rx + xoff][rz + zoff];
                            int rmult = bm.getModifiedGrassMultiplier(colormap[bm.biomeLookup()]);
                            raccum += (rmult >> 16) & 0xFF;
                            gaccum += (rmult >> 8) & 0xFF;
                            baccum += rmult & 0xFF;
                        }
                    }

                    mult = ((raccum / 9) << 16) | ((gaccum / 9) << 8) | (baccum / 9);
                }
            }
            catch (Exception x)
            {
                mult = 0xFFFFFF;
            }

            return mult;
        }
        @Override
        public final int getSmoothFoliageColorMultiplier(int[] colormap)
        {
            int mult = 0xFFFFFF;

            try
            {
                int rx = x - x_base;
                int rz = z - z_base;
                BiomeMap bm = biomemap[rx][rz];

                if (sameneighborbiomecnt[rx][rz] >= (byte)8)    /* All neighbors same? */
                {
                    mult = bm.getModifiedFoliageMultiplier(colormap[bm.biomeLookup()]);
                }
                else
                {
                    int raccum = 0;
                    int gaccum = 0;
                    int baccum = 0;

                    for (int xoff = -1; xoff < 2; xoff++)
                    {
                        for (int zoff = -1; zoff < 2; zoff++)
                        {
                            bm = biomemap[rx + xoff][rz + zoff];
                            int rmult = bm.getModifiedFoliageMultiplier(colormap[bm.biomeLookup()]);
                            raccum += (rmult >> 16) & 0xFF;
                            gaccum += (rmult >> 8) & 0xFF;
                            baccum += rmult & 0xFF;
                        }
                    }

                    mult = ((raccum / 9) << 16) | ((gaccum / 9) << 8) | (baccum / 9);
                }
            }
            catch (Exception x)
            {
                mult = 0xFFFFFF;
            }

            return mult;
        }
        @Override
        public final int getSmoothColorMultiplier(int[] colormap, int[] swampmap)
        {
            int mult = 0xFFFFFF;

            try
            {
                int rx = x - x_base;
                int rz = z - z_base;
                BiomeMap bm = biomemap[rx][rz];

                if (sameneighborbiomecnt[rx][rz] >= (byte)8)    /* All neighbors same? */
                {
                    if (bm == BiomeMap.SWAMPLAND)
                    {
                        mult = swampmap[bm.biomeLookup()];
                    }
                    else
                    {
                        mult = colormap[bm.biomeLookup()];
                    }
                }
                else
                {
                    int raccum = 0;
                    int gaccum = 0;
                    int baccum = 0;

                    for (int xoff = -1; xoff < 2; xoff++)
                    {
                        for (int zoff = -1; zoff < 2; zoff++)
                        {
                            bm = biomemap[rx + xoff][rz + zoff];
                            int rmult;

                            if (bm == BiomeMap.SWAMPLAND)
                            {
                                rmult = swampmap[bm.biomeLookup()];
                            }
                            else
                            {
                                rmult = colormap[bm.biomeLookup()];
                            }

                            raccum += (rmult >> 16) & 0xFF;
                            gaccum += (rmult >> 8) & 0xFF;
                            baccum += rmult & 0xFF;
                        }
                    }

                    mult = ((raccum / 9) << 16) | ((gaccum / 9) << 8) | (baccum / 9);
                }
            }
            catch (Exception x)
            {
                mult = 0xFFFFFF;
            }

            return mult;
        }
        @Override
        public final int getSmoothWaterColorMultiplier()
        {
            try
            {
                int rx = x - x_base;
                int rz = z - z_base;
                BiomeMap bm = biomemap[rx][rz];

                if (sameneighborbiomecnt[rx][rz] >= (byte)8)    /* All neighbors same? */
                {
                    return bm.getWaterColorMult();
                }

                int raccum = 0;
                int gaccum = 0;
                int baccum = 0;

                for (int xoff = -1; xoff < 2; xoff++)
                {
                    for (int zoff = -1; zoff < 2; zoff++)
                    {
                        bm = biomemap[rx + xoff][rz + zoff];
                        int mult = bm.getWaterColorMult();
                        raccum += (mult >> 16) & 0xFF;
                        gaccum += (mult >> 8) & 0xFF;
                        baccum += mult & 0xFF;
                    }
                }

                return ((raccum / 9) << 16) | ((gaccum / 9) << 8) | (baccum / 9);
            }
            catch (Exception x)
            {
                return 0xFFFFFF;
            }
        }
        @Override
        public final int getSmoothWaterColorMultiplier(int[] colormap)
        {
            int mult = 0xFFFFFF;

            try
            {
                int rx = x - x_base;
                int rz = z - z_base;
                BiomeMap bm = biomemap[rx][rz];

                if (sameneighborbiomecnt[rx][rz] >= (byte)8)    /* All neighbors same? */
                {
                    mult = colormap[bm.biomeLookup()];
                }
                else
                {
                    int raccum = 0;
                    int gaccum = 0;
                    int baccum = 0;

                    for (int xoff = -1; xoff < 2; xoff++)
                    {
                        for (int zoff = -1; zoff < 2; zoff++)
                        {
                            bm = biomemap[rx + xoff][rz + zoff];
                            int rmult = colormap[bm.biomeLookup()];
                            raccum += (rmult >> 16) & 0xFF;
                            gaccum += (rmult >> 8) & 0xFF;
                            baccum += rmult & 0xFF;
                        }
                    }

                    mult = ((raccum / 9) << 16) | ((gaccum / 9) << 8) | (baccum / 9);
                }
            }
            catch (Exception x)
            {
                mult = 0xFFFFFF;
            }

            return mult;
        }
        /**
         * Step current position in given direction
         */
        @Override
        public final void stepPosition(BlockStep step)
        {
            typeid = -1;
            blkdata = -1;

            switch (step.ordinal())
            {
                case 0:
                    x++;
                    bx++;

                    if (bx == 16)   /* Next chunk? */
                    {
                        bx = 0;
                        chunkindex++;
                        if((chunkindex >= snapcnt) || (chunkindex < 0)) {
                            snap = EMPTY;
                        }
                        else {
                            snap = snaparray[chunkindex];
                        }
                    }

                    break;

                case 1:
                    y++;

                    if (y >= worldheight)
                    {
                        typeid = blkdata = 0;
                    }

                    break;

                case 2:
                    z++;
                    bz++;

                    if (bz == 16)   /* Next chunk? */
                    {
                        bz = 0;
                        chunkindex += x_dim;
                        if((chunkindex >= snapcnt) || (chunkindex < 0)) {
                            snap = EMPTY;
                        }
                        else {
                            snap = snaparray[chunkindex];
                        }
                    }
                    break;

                case 3:
                    x--;
                    bx--;

                    if (bx == -1)   /* Next chunk? */
                    {
                        bx = 15;
                        chunkindex--;
                        if((chunkindex >= snapcnt) || (chunkindex < 0)) {
                            snap = EMPTY;
                        }
                        else {
                            snap = snaparray[chunkindex];
                        }
                    }

                    break;

                case 4:
                    y--;

                    if (y < 0)
                    {
                        typeid = blkdata = 0;
                    }

                    break;

                case 5:
                    z--;
                    bz--;

                    if (bz == -1)   /* Next chunk? */
                    {
                        bz = 15;
                        chunkindex -= x_dim;
                        if((chunkindex >= snapcnt) || (chunkindex < 0)) {
                            snap = EMPTY;
                        }
                        else {
                            snap = snaparray[chunkindex];
                        }
                    }
                    break;
            }

            laststep = step;
        }
        /**
         * Unstep current position to previous position
         */
        @Override
        public BlockStep unstepPosition()
        {
            BlockStep ls = laststep;
            stepPosition(unstep[ls.ordinal()]);
            return ls;
        }
        /**
         * Unstep current position in oppisite director of given step
         */
        @Override
        public void unstepPosition(BlockStep s)
        {
            stepPosition(unstep[s.ordinal()]);
        }
        @Override
        public final void setY(int y)
        {
            if (y > this.y)
            {
                laststep = BlockStep.Y_PLUS;
            }
            else
            {
                laststep = BlockStep.Y_MINUS;
            }

            this.y = y;

            if ((y < 0) || (y >= worldheight))
            {
                typeid = blkdata = 0;
            }
            else
            {
                typeid = blkdata = -1;
            }
        }
        @Override
        public final int getX()
        {
            return x;
        }
        @Override
        public final int getY()
        {
            return y;
        }
        @Override
        public final int getZ()
        {
            return z;
        }
        @Override
        public final int getBlockTypeIDAt(BlockStep s)
        {
            if (s == BlockStep.Y_MINUS)
            {
                if (y > 0)
                {
                    return snap.getBlockTypeId(bx, y - 1, bz);
                }
            }
            else if (s == BlockStep.Y_PLUS)
            {
                if (y < (worldheight - 1))
                {
                    return snap.getBlockTypeId(bx, y + 1, bz);
                }
            }
            else
            {
                BlockStep ls = laststep;
                stepPosition(s);
                int tid = snap.getBlockTypeId(bx, y, bz);
                unstepPosition();
                laststep = ls;
                return tid;
            }

            return 0;
        }
        @Override
        public BlockStep getLastStep()
        {
            return laststep;
        }
        @Override
        public int getWorldHeight()
        {
            return worldheight;
        }
        @Override
        public long getBlockKey()
        {
            return (((chunkindex * worldheight) + y) << 8) | (bx << 4) | bz;
        }
        @Override
        public final boolean isEmptySection()
        {
            try
            {
                return !isSectionNotEmpty[chunkindex][y >> 4];
            }
            catch (Exception x)
            {
                initSectionData(chunkindex);
                return !isSectionNotEmpty[chunkindex][y >> 4];
            }
        }
        @Override
        public RenderPatchFactory getPatchFactory() {
            return HDBlockModels.getPatchDefinitionFactory();
        }
        @Override
        public Object getBlockTileEntityField(String fieldId) {
            try {
                int idx = getIndexInChunk(bx,y,bz);
                Object[] vals = (Object[])snaptile[chunkindex].get(idx);
                for (int i = 0; i < vals.length; i += 2) {
                    if (vals[i].equals(fieldId)) {
                        return vals[i+1];
                    }
                }
            } catch (Exception x) {
            }
            return null;
        }
        @Override
        public int getBlockTypeIDAt(int xoff, int yoff, int zoff) {
            int xx = this.x + xoff;
            int yy = this.y + yoff;
            int zz = this.z + zoff;
            int idx = ((xx >> 4) - x_min) + (((zz >> 4) - z_min) * x_dim);
            try {
                return snaparray[idx].getBlockTypeId(xx & 0xF, yy, zz & 0xF);
            } catch (Exception x) {
                return 0;
            }
        }
        @Override
        public int getBlockDataAt(int xoff, int yoff, int zoff) {
            int xx = this.x + xoff;
            int yy = this.y + yoff;
            int zz = this.z + zoff;
            int idx = ((xx >> 4) - x_min) + (((zz >> 4) - z_min) * x_dim);
            try {
                return snaparray[idx].getBlockData(xx & 0xF, yy, zz & 0xF);
            } catch (Exception x) {
                return 0;
            }
        }
        @Override
        public Object getBlockTileEntityFieldAt(String fieldId, int xoff,
                int yoff, int zoff) {
            return null;
        }
        @Override
        public long getInhabitedTicks() {
            try {
                return snap.getInhabitedTicks();
            } catch (Exception x) {
                return 0;
            }
        }
    }

    private class OurEndMapIterator extends OurMapIterator
    {
        OurEndMapIterator(int x0, int y0, int z0)
        {
            super(x0, y0, z0);
        }
        @Override
        public final int getBlockSkyLight()
        {
            return 15;
        }
    }
    /**
     * Chunk cache for representing unloaded chunk (or air)
     */
    private static class EmptyChunk extends ChunkSnapshot
    {
        public EmptyChunk()
        {
            super(256, 0, 0, 0, 0);
        }
        /* Need these for interface, but not used */
        @Override
        public int getX()
        {
            return 0;
        }
        @Override
        public int getZ()
        {
            return 0;
        }
        @Override
        public final int getBlockTypeId(int x, int y, int z)
        {
            return 0;
        }
        @Override
        public final int getBlockData(int x, int y, int z)
        {
            return 0;
        }
        @Override
        public final int getBlockSkyLight(int x, int y, int z)
        {
            return 15;
        }
        @Override
        public final int getBlockEmittedLight(int x, int y, int z)
        {
            return 0;
        }
        @Override
        public final int getHighestBlockYAt(int x, int z)
        {
            return 0;
        }
        @Override
        public int getBiome(int x, int z)
        {
            return -1;
        }
        @Override
        public boolean isSectionEmpty(int sy)
        {
            return true;
        }
    }

    /**
     * Chunk cache for representing generic stone chunk
     */
    private static class PlainChunk extends ChunkSnapshot
    {
        private int fillid;

        PlainChunk(int fillid)
        {
            super(256, 0, 0, 0, 0);
            this.fillid = fillid;
        }
        /* Need these for interface, but not used */
        @Override
        public int getX()
        {
            return 0;
        }
        @Override
        public int getZ()
        {
            return 0;
        }
        @Override
        public int getBiome(int x, int z)
        {
            return -1;
        }
        @Override
        public final int getBlockTypeId(int x, int y, int z)
        {
            if (y < 64)
            {
                return fillid;
            }

            return 0;
        }
        @Override
        public final int getBlockData(int x, int y, int z)
        {
            return 0;
        }
        @Override
        public final int getBlockSkyLight(int x, int y, int z)
        {
            if (y < 64)
            {
                return 0;
            }

            return 15;
        }
        @Override
        public final int getBlockEmittedLight(int x, int y, int z)
        {
            return 0;
        }
        @Override
        public final int getHighestBlockYAt(int x, int z)
        {
            return 64;
        }
        @Override
        public boolean isSectionEmpty(int sy)
        {
            return (sy < 4);
        }
    }

    private static final EmptyChunk EMPTY = new EmptyChunk();
    private static final PlainChunk STONE = new PlainChunk(1);
    private static final PlainChunk OCEAN = new PlainChunk(9);


    public static void init() {
    	if (!init)
    	{
    		Field[] f = ChunkProviderServer.class.getDeclaredFields();
    		
    		for(int i = 0; i < f.length; i++) {
    			if((unloadqueue == null) && f[i].getType().isAssignableFrom(java.util.Set.class)) {
    	    		unloadqueue = f[i];
    				//Log.info("Found unloadqueue - " + f[i].getName());
    				unloadqueue.setAccessible(true);
    			}
    			else if((currentchunkloader == null) && f[i].getType().isAssignableFrom(IChunkLoader.class)) {
    				currentchunkloader = f[i];
    				//Log.info("Found currentchunkprovider - " + f[i].getName());
    				currentchunkloader.setAccessible(true);
    			}
    		}
    		
    		f = WorldServer.class.getDeclaredFields();
    		for(int i = 0; i < f.length; i++) {
    			if((updateEntityTick == null) && f[i].getType().isAssignableFrom(int.class)) {
    				updateEntityTick = f[i];
    				//Log.info("Found updateEntityTick - " + f[i].getName());
    				updateEntityTick.setAccessible(true);
    			}
    		}

    		f = AnvilChunkLoader.class.getDeclaredFields();
    		for(int i = 0; i < f.length; i++) {
    		    if((chunksToRemove == null) && (f[i].getType().equals(Map.class))) {
    		        chunksToRemove = f[i];
    		        chunksToRemove.setAccessible(true);
    		    }
    		    else if((pendingAnvilChunksCoordinates == null) && (f[i].getType().equals(Set.class))) {
    		        pendingAnvilChunksCoordinates = f[i];
    		        pendingAnvilChunksCoordinates.setAccessible(true);
    		    }
    		}
    		// Get writeChunkToNBT method
    	    Method[] ma = AnvilChunkLoader.class.getDeclaredMethods();
    	    for (Method m : ma) {
    	        Class<?>[] p = m.getParameterTypes();
    	        if ((p.length == 3) && (p[0].equals(Chunk.class)) && (p[1].equals(World.class)) && (p[2].equals(NBTTagCompound.class))) {
    	            writechunktonbt = m;
    	            m.setAccessible(true);
    	            break;
    	        }
    	    }
    		
            if ((unloadqueue == null) || (currentchunkloader == null) || (writechunktonbt == null))
            {
    			Log.severe("ERROR: cannot find unload queue or chunk provider field - dynmap cannot load chunks");
    		}
			if (updateEntityTick == null) {
				Log.severe("ERROR: cannot find updateEntityTick - dynmap cannot drive entity cleanup when no players are active");
			}

    		init = true;
    	}
    }

    /**
     * Construct empty cache
     */
    public ForgeMapChunkCache()
    {
    	init();
    }
    
    public void setChunks(ForgeWorld dw, List<DynmapChunk> chunks)
    {
        this.dw = dw;
        this.w = dw.getWorld();
        if(dw.isLoaded()) {
        	/* Check if world's provider is ChunkProviderServer */
        	IChunkProvider cp = this.w.getChunkProvider();

        	if (cp instanceof ChunkProviderServer)
        	{
        		cps = (ChunkProviderServer)cp;
        	}
        	else
        	{
        		Log.severe("Error: world " + dw.getName() + " has unsupported chunk provider");
        	}
        }
        else {
        	chunks = new ArrayList<DynmapChunk>();
        }
        nsect = dw.worldheight >> 4;
        this.chunks = chunks;

        /* Compute range */
        if (chunks.size() == 0)
        {
            this.x_min = 0;
            this.x_max = 0;
            this.z_min = 0;
            this.z_max = 0;
            x_dim = 1;
        }
        else
        {
            x_min = x_max = chunks.get(0).x;
            z_min = z_max = chunks.get(0).z;

            for (DynmapChunk c : chunks)
            {
                if (c.x > x_max)
                {
                    x_max = c.x;
                }

                if (c.x < x_min)
                {
                    x_min = c.x;
                }

                if (c.z > z_max)
                {
                    z_max = c.z;
                }

                if (c.z < z_min)
                {
                    z_min = c.z;
                }
            }

            x_dim = x_max - x_min + 1;
        }

        snapcnt = x_dim * (z_max-z_min+1);
        snaparray = new ChunkSnapshot[snapcnt];
        snaptile = new DynIntHashMap[snapcnt];
        isSectionNotEmpty = new boolean[snapcnt][];
        
        try
        {
            if ((unloadqueue != null) && (cps != null))
            {
                queue = (Set<?>)unloadqueue.get(cps);
            }
        }
        catch (IllegalArgumentException iax)
        {
        }
        catch (IllegalAccessException e)
        {
        }

    }

    private static boolean didError = false;
    
    public NBTTagCompound readChunk(int x, int z) {
        if((cps == null) || (!(cps.chunkLoader instanceof AnvilChunkLoader)) ||
                (((chunksToRemove == null) || (pendingAnvilChunksCoordinates == null)))) {
            if (!didError) {
                Log.severe("**** DYNMAP CANNOT READ CHUNKS (UNSUPPORTED CHUNK LOADER) ****");
                didError = true;
            }
            return null;
        }
        try {
            AnvilChunkLoader acl = (AnvilChunkLoader)cps.chunkLoader;
            Map<?,?> chunkstoremove = null;
            Set<?> pendingcoords = null;
            
            chunkstoremove = (Map<?,?>)chunksToRemove.get(acl);
            pendingcoords = (Set<?>)pendingAnvilChunksCoordinates.get(acl);

            NBTTagCompound rslt = null;
            ChunkPos coord = new ChunkPos(x, z);

            if (pendingcoords.contains(coord)) {
                for (Object o : chunkstoremove.values()) {
                    if (chunkCoord == null) {
                        Field[] f = o.getClass().getDeclaredFields();
                        for(Field ff : f) {
                            if((chunkCoord == null) && (ff.getType().equals(ChunkPos.class))) {
                                chunkCoord = ff;
                                chunkCoord.setAccessible(true);
                            }
                            else if((nbtTag == null) && (ff.getType().equals(NBTTagCompound.class))) {
                                nbtTag = ff;
                                nbtTag.setAccessible(true);
                            }
                        }
                        if ((chunkCoord == null) || (nbtTag == null)) {
                            Log.severe("Error getting chunkCoord and nbtTag for Forge");
                            return null;
                        }
                    }
                    ChunkPos occ = (ChunkPos)chunkCoord.get(o);

                    if (occ.equals(coord)) {
                        rslt = (NBTTagCompound)nbtTag.get(o);
                        break;
                    }
                }
            }

            if (rslt == null) {
                DataInputStream str = RegionFileCache.getChunkInputStream(acl.chunkSaveLocation, x, z);

                if (str == null) {
                    return null;
                }
                rslt = CompressedStreamTools.read(str);
            }
            if(rslt != null) 
                rslt = rslt.getCompoundTag("Level");
            return rslt;
        } catch (Exception exc) {
            Log.severe(String.format("Error reading chunk: %s,%d,%d", dw.getName(), x, z), exc);
            return null;
        }
    }
    
    private Object getNBTValue(NBTBase v) {
        Object val = null;
        switch(v.getId()) {
            case 1: // Byte
                val = Byte.valueOf(((NBTTagByte)v).getByte());
                break;
            case 2: // Short
                val = Short.valueOf(((NBTTagShort)v).getShort());
                break;
            case 3: // Int
                val = Integer.valueOf(((NBTTagInt)v).getInt());
                break;
            case 4: // Long
                val = Long.valueOf(((NBTTagLong)v).getLong());
                break;
            case 5: // Float
                val = Float.valueOf(((NBTTagFloat)v).getFloat());
                break;
            case 6: // Double
                val = Double.valueOf(((NBTTagDouble)v).getDouble());
                break;
            case 7: // Byte[]
                val = ((NBTTagByteArray)v).getByteArray();
                break;
            case 8: // String
                val = ((NBTTagString)v).getString();
                break;
            case 9: // List
                NBTTagList tl = (NBTTagList) v;
                ArrayList<Object> vlist = new ArrayList<Object>();
                int type = tl.getTagType();
                for (int i = 0; i < tl.tagCount(); i++) {
                    switch (type) {
                        case 5:
                            float fv = tl.getFloatAt(i);
                            vlist.add(fv);
                            break;
                        case 6:
                            double dv = tl.getDoubleAt(i);
                            vlist.add(dv);
                            break;
                        case 8:
                            String sv = tl.getStringTagAt(i);
                            vlist.add(sv);
                            break;
                        case 10:
                            NBTTagCompound tc = tl.getCompoundTagAt(i);
                            vlist.add(getNBTValue(tc));
                            break;
                        case 11:
                            int[] ia = tl.getIntArrayAt(i);
                            vlist.add(ia);
                            break;
                    }
                }
                val = vlist;
                break;
            case 10: // Map
                NBTTagCompound tc = (NBTTagCompound) v;
                HashMap<String, Object> vmap = new HashMap<String, Object>();
                for (Object t : tc.getKeySet()) {
                    String st = (String) t;
                    NBTBase tg = tc.getTag(st);
                    vmap.put(st, getNBTValue(tg));
                }
                val = vmap;
                break;
            case 11: // Int[]
                val = ((NBTTagIntArray)v).getIntArray();
                break;
        }
        return val;
    }
    
    private boolean isChunkVisible(DynmapChunk chunk) {
        boolean vis = true;
        if(visible_limits != null) {
            vis = false;
            for(VisibilityLimit limit : visible_limits) {
                if (limit.doIntersectChunk(chunk.x, chunk.z)) {
                    vis = true;
                    break;
                }
            }
        }
        if(vis && (hidden_limits != null)) {
            for(VisibilityLimit limit : hidden_limits) {
                if (limit.doIntersectChunk(chunk.x, chunk.z)) {
                    vis = false;
                    break;
                }
            }
        }
        return vis;
    }
    
    private boolean tryChunkCache(DynmapChunk chunk, boolean vis) {
        /* Check if cached chunk snapshot found */
        ChunkSnapshot ss = null;
        SnapshotRec ssr = DynmapPlugin.plugin.sscache.getSnapshot(dw.getName(), chunk.x, chunk.z, blockdata, biome, biomeraw, highesty); 
        if(ssr != null) {
            ss = ssr.ss;
            if (!vis)
            {
                if (hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN)
                {
                    ss = STONE;
                }
                else if (hidestyle == HiddenChunkStyle.FILL_OCEAN)
                {
                    ss = OCEAN;
                }
                else
                {
                    ss = EMPTY;
                }
            }
            int idx = (chunk.x-x_min) + (chunk.z - z_min)*x_dim;
            snaparray[idx] = ss;
            snaptile[idx] = ssr.tileData;
        }
        return (ssr != null);
    }
    
    private boolean isChunkUnloadPending(DynmapChunk chunk) {
        boolean isunloadpending = false;
        
        if (queue != null)
        {
            long coord = ChunkPos.chunkXZ2Int(chunk.x, chunk.z);
            isunloadpending = queue.contains(Long.valueOf(coord));
        }
        return isunloadpending;
    }

    // Prep snapshot and add to cache
    private SnapshotRec prepChunkSnapshot(DynmapChunk chunk, NBTTagCompound nbt) {
        ChunkSnapshot ss = new ChunkSnapshot(nbt, dw.worldheight);
        DynIntHashMap tileData = new DynIntHashMap();

        NBTTagList tiles = nbt.getTagList("TileEntities", 10);
        if(tiles == null) tiles = new NBTTagList();
        /* Get tile entity data */
        List<Object> vals = new ArrayList<Object>();
        for(int tid = 0; tid < tiles.tagCount(); tid++) {
            NBTTagCompound tc = tiles.getCompoundTagAt(tid);
            int tx = tc.getInteger("x");
            int ty = tc.getInteger("y");
            int tz = tc.getInteger("z");
            int cx = tx & 0xF;
            int cz = tz & 0xF;
            int blkid = ss.getBlockTypeId(cx, ty, cz);
            int blkdat = ss.getBlockData(cx, ty, cz);
            String[] te_fields = HDBlockModels.getTileEntityFieldsNeeded(blkid,  blkdat);
            if(te_fields != null) {
                vals.clear();
                for(String id: te_fields) {
                    NBTBase v = tc.getTag(id);  /* Get field */
                    if(v != null) {
                        Object val = getNBTValue(v);
                        if(val != null) {
                            vals.add(id);
                            vals.add(val);
                        }
                    }
                }
                if(vals.size() > 0) {
                    Object[] vlist = vals.toArray(new Object[vals.size()]);
                    tileData.put(getIndexInChunk(cx, ty, cz), vlist);
                }
            }
        }
        SnapshotRec ssr = new SnapshotRec();
        ssr.ss = ss;
        ssr.tileData = tileData;
        DynmapPlugin.plugin.sscache.putSnapshot(dw.getName(), chunk.x, chunk.z, ssr, blockdata, biome, biomeraw, highesty);
        
        return ssr;
    }
    
    /** 
     * Read NBT data from loaded chunks - needs to be called from server/world thread to be safe
     * @returns number loaded
     */
    public int getLoadedChunks() {
        int cnt = 0;
        if(!dw.isLoaded()) {
            isempty = true;
            unloadChunks();
            return 0;
        }
        ListIterator<DynmapChunk> iter = chunks.listIterator();
        while (iter.hasNext()) {
            long startTime = System.nanoTime();
            DynmapChunk chunk = iter.next();
            int chunkindex = (chunk.x-x_min) + (chunk.z - z_min)*x_dim;
            if (snaparray[chunkindex] != null) continue;    // Skip if already processed
            
            boolean vis = isChunkVisible(chunk);

            /* Check if cached chunk snapshot found */
            if (tryChunkCache(chunk, vis)) {
                endChunkLoad(startTime, ChunkStats.CACHED_SNAPSHOT_HIT);
                cnt++;
            }
            // If chunk is loaded and not being unloaded, we're grabbing its NBT data
            else if (cps.chunkExists(chunk.x, chunk.z) && (!isChunkUnloadPending(chunk))) {
                ChunkSnapshot ss;
                DynIntHashMap tileData;
                if (vis) {  // If visible 
                    NBTTagCompound nbt = new NBTTagCompound();
                    try {
                        writechunktonbt.invoke(cps.chunkLoader, cps.loadChunk(chunk.x, chunk.z), w, nbt);
                    } catch (IllegalAccessException e) {
                    } catch (IllegalArgumentException e) {
                    } catch (InvocationTargetException e) {
                    }                
                    SnapshotRec ssr = prepChunkSnapshot(chunk, nbt);
                    ss = ssr.ss;
                    tileData = ssr.tileData;
                }
                else {
                    if (hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN) {
                        ss = STONE;
                    }
                    else if (hidestyle == HiddenChunkStyle.FILL_OCEAN) {
                        ss = OCEAN;
                    }
                    else {
                        ss = EMPTY;
                    }
                    tileData = new DynIntHashMap();
                }
                snaparray[chunkindex] = ss;
                snaptile[chunkindex] = tileData;
                endChunkLoad(startTime, ChunkStats.LOADED_CHUNKS);
                cnt++;
            }
        }
        return cnt;
    }

    @Override
    public int loadChunks(int max_to_load)
    {
        return getLoadedChunks() + readChunks(max_to_load);
        
    }
    
    public int readChunks(int max_to_load)
    {
        if(!dw.isLoaded()) {
        	isempty = true;
        	unloadChunks();
        	return 0;
        }

        int cnt = 0;

        if (iterator == null)
        {
            iterator = chunks.listIterator();
        }

        DynmapCore.setIgnoreChunkLoads(true);

        // Load the required chunks.
        while ((cnt < max_to_load) && iterator.hasNext())
        {
            long startTime = System.nanoTime();

            DynmapChunk chunk = iterator.next();

            int chunkindex = (chunk.x-x_min) + (chunk.z - z_min)*x_dim;

            if (snaparray[chunkindex] != null) continue;    // Skip if already processed

            boolean vis = isChunkVisible(chunk);

            /* Check if cached chunk snapshot found */
            if (tryChunkCache(chunk, vis)) {
                endChunkLoad(startTime, ChunkStats.CACHED_SNAPSHOT_HIT);
            }
            else {
                NBTTagCompound nbt = readChunk(chunk.x, chunk.z);
                // If read was good
                if (nbt != null) {
                    ChunkSnapshot ss;
                    DynIntHashMap tileData;
                    // If hidden
                    if (!vis) {
                        if (hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN) {
                            ss = STONE;
                        }
                        else if (hidestyle == HiddenChunkStyle.FILL_OCEAN) {
                            ss = OCEAN;
                        }
                        else {
                            ss = EMPTY;
                        }
                        tileData = new DynIntHashMap();
                    }
                    else {
                        // Prep snapshot
                        SnapshotRec ssr = prepChunkSnapshot(chunk, nbt);
                        ss = ssr.ss;
                        tileData = ssr.tileData;
                    }
                    snaparray[chunkindex] = ss;
                    snaptile[chunkindex] = tileData;
                    endChunkLoad(startTime, ChunkStats.UNLOADED_CHUNKS);
                }
                else {
                    endChunkLoad(startTime, ChunkStats.UNGENERATED_CHUNKS);
                }
            }
            cnt++;
        }

        DynmapCore.setIgnoreChunkLoads(false);

        if (iterator.hasNext() == false)    /* If we're done */
        {
            isempty = true;

            /* Fill missing chunks with empty dummy chunk */
            for (int i = 0; i < snaparray.length; i++)
            {
                if (snaparray[i] == null)
                {
                    snaparray[i] = EMPTY;
                }
                else if (snaparray[i] != EMPTY)
                {
                    isempty = false;
                }
            }
        }
        return cnt;
    }
    /**
     * Test if done loading
     */
    public boolean isDoneLoading()
    {
    	if(!dw.isLoaded()) {
    		return true;
    	}
        if (iterator != null)
        {
            return !iterator.hasNext();
        }

        return false;
    }
    /**
     * Test if all empty blocks
     */
    public boolean isEmpty()
    {
        return isempty;
    }
    /**
     * Unload chunks
     */
    public void unloadChunks()
    {
        if (snaparray != null)
        {
            for (int i = 0; i < snaparray.length; i++)
            {
                snaparray[i] = null;
            }

            snaparray = null;
        }
    }
    private void initSectionData(int idx)
    {
        isSectionNotEmpty[idx] = new boolean[nsect + 1];

        if (snaparray[idx] != EMPTY)
        {
            for (int i = 0; i < nsect; i++)
            {
                if (snaparray[idx].isSectionEmpty(i) == false)
                {
                    isSectionNotEmpty[idx][i] = true;
                }
            }
        }
    }
    public boolean isEmptySection(int sx, int sy, int sz)
    {
        int idx = (sx - x_min) + (sz - z_min) * x_dim;

        if (isSectionNotEmpty[idx] == null)
        {
            initSectionData(idx);
        }

        return !isSectionNotEmpty[idx][sy];
    }

    /**
     * Get cache iterator
     */
    public MapIterator getIterator(int x, int y, int z)
    {
        if (dw.getEnvironment().equals("the_end"))
        {
            return new OurEndMapIterator(x, y, z);
        }

        return new OurMapIterator(x, y, z);
    }
    /**
     * Set hidden chunk style (default is FILL_AIR)
     */
    public void setHiddenFillStyle(HiddenChunkStyle style)
    {
        this.hidestyle = style;
    }
    /**
     * Add visible area limit - can be called more than once 
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setVisibleRange(VisibilityLimit lim) {
        if(visible_limits == null)
            visible_limits = new ArrayList<VisibilityLimit>();
        visible_limits.add(lim);
    }
    /**
     * Add hidden area limit - can be called more than once 
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setHiddenRange(VisibilityLimit lim) {
        if(hidden_limits == null)
            hidden_limits = new ArrayList<VisibilityLimit>();
        hidden_limits.add(lim);
    }
    @Override
    public boolean setChunkDataTypes(boolean blockdata, boolean biome, boolean highestblocky, boolean rawbiome)
    {
        this.biome = biome;
        this.biomeraw = rawbiome;
        this.highesty = highestblocky;
        this.blockdata = blockdata;
        return true;
    }
    @Override
    public DynmapWorld getWorld()
    {
        return dw;
    }

    static
    {
        Biome b[] = DynmapPlugin.getBiomeList();
        BiomeMap[] bm = BiomeMap.values();
        biome_to_bmap = new BiomeMap[256];

        for (int i = 0; i < biome_to_bmap.length; i++)
        {
            biome_to_bmap[i] = BiomeMap.NULL;
        }

        for (int i = 0; i < b.length; i++)
        {
        	if(b[i] == null) continue;
        	
            String bs = b[i].getBiomeName();

            for (int j = 0; j < bm.length; j++)
            {
                if (bm[j].toString().equals(bs))
                {
                    biome_to_bmap[i] = bm[j];
                    break;
                }
            }
        }
    }
}
