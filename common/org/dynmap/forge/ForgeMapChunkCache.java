package org.dynmap.forge;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import net.minecraft.src.AnvilChunkLoader;
import net.minecraft.src.BiomeGenBase;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCoordIntPair;
import net.minecraft.src.ChunkProviderServer;
import net.minecraft.src.CompressedStreamTools;
import net.minecraft.src.IChunkLoader;
import net.minecraft.src.IChunkProvider;
import net.minecraft.src.MinecraftException;
import net.minecraft.src.NBTBase;
import net.minecraft.src.NBTTagByte;
import net.minecraft.src.NBTTagByteArray;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.NBTTagDouble;
import net.minecraft.src.NBTTagFloat;
import net.minecraft.src.NBTTagInt;
import net.minecraft.src.NBTTagIntArray;
import net.minecraft.src.NBTTagList;
import net.minecraft.src.NBTTagLong;
import net.minecraft.src.NBTTagShort;
import net.minecraft.src.NBTTagString;
import net.minecraft.src.RegionFileCache;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;
import net.minecraft.src.WorldServer;

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
import org.dynmap.utils.MapChunkCache.HiddenChunkStyle;
import org.dynmap.utils.MapChunkCache.VisibilityLimit;

/**
 * Container for managing chunks - dependent upon using chunk snapshots, since rendering is off server thread
 */
public class ForgeMapChunkCache implements MapChunkCache
{
    private static boolean init = false;
    private static Field unloadqueue = null;
    private static Field currentchunkloader = null;
    private static Field updateEntityTick = null;
    /* AnvilChunkLoader fields */
    private static Field chunksToRemove = null; // List
    private static Field pendingAnvilChunksCoordinates = null; // Set
    private static Field syncLockObject = null; // Object
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
    private boolean do_generate = false;
    private boolean do_save = false;
    private boolean isempty = true;
    private ChunkSnapshot[] snaparray; /* Index = (x-x_min) + ((z-z_min)*x_dim) */
    private DynIntHashMap[] snaptile;
    private byte[][] sameneighborbiomecnt;
    private BiomeMap[][] biomemap;
    private boolean[][] isSectionNotEmpty; /* Indexed by snapshot index, then by section index */
    
    private int chunks_read;    /* Number of chunks actually loaded */
    private int chunks_attempted;   /* Number of chunks attempted to load */
    private long total_loadtime;    /* Total time loading chunks, in nanoseconds */

    private long exceptions;

    private static final BlockStep unstep[] = { BlockStep.X_MINUS, BlockStep.Y_MINUS, BlockStep.Z_MINUS,
            BlockStep.X_PLUS, BlockStep.Y_PLUS, BlockStep.Z_PLUS
                                              };

    private static BiomeMap[] biome_to_bmap;

    private static final int getIndexInChunk(int cx, int cy, int cz) {
        return (cy << 8) | (cz << 4) | cx;
    }

    private static class NoChunkFoundThrow extends Error {
    }
    
    private static class NoCreateChunkLoader implements IChunkLoader {
        IChunkLoader base;
        World ww;
        int xx, zz;
        @Override
        public Chunk loadChunk(World w, int x, int z)
                throws IOException {
            Chunk c = base.loadChunk(w, x, z);
            if((c == null) && (w == ww) && (x == xx) && (z == zz)) {
                throw new NoChunkFoundThrow();
            }
            return c;
        }
        @Override
        public void saveChunk(World var1, Chunk var2)
                throws MinecraftException, IOException {
            base.saveChunk(var1, var2);
        }

        @Override
        public void saveExtraChunkData(World var1, Chunk var2) {
            base.saveExtraChunkData(var1, var2);
        }

        @Override
        public void chunkTick() {
            base.chunkTick();
        }

        @Override
        public void saveExtraData() {
            base.saveExtraData();
        }
    }
    
    private static NoCreateChunkLoader noCreateLoader = new NoCreateChunkLoader();
    
    /**
     * Iterator for traversing map chunk cache (base is for non-snapshot)
     */
    public class OurMapIterator implements MapIterator
    {
        private int x, y, z, chunkindex, bx, bz, off;
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
        public final void initialize(int x0, int y0, int z0)
        {
            this.x = x0;
            this.y = y0;
            this.z = z0;
            this.chunkindex = ((x >> 4) - x_min) + (((z >> 4) - z_min) * x_dim);
            this.bx = x & 0xF;
            this.bz = z & 0xF;
            this.off = bx + (bz << 4);

            try
            {
                snap = snaparray[chunkindex];
            }
            catch (ArrayIndexOutOfBoundsException aioobx)
            {
                snap = EMPTY;
                exceptions++;
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
        public final int getBlockTypeID()
        {
            if (typeid < 0)
            {
                typeid = snap.getBlockTypeId(bx, y, bz);
            }

            return typeid;
        }
        public final int getBlockData()
        {
            if (blkdata < 0)
            {
                blkdata = snap.getBlockData(bx, y, bz);
            }

            return blkdata;
        }
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
                initialize(i + x_base, 64, z_base);

                for (int j = 0; j < z_size; j++)
                {
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
                    stepPosition(BlockStep.Z_PLUS);
                }
            }
        }

        public final BiomeMap getBiome()
        {
            try
            {
                return biomemap[x - x_base][z - z_base];
            }
            catch (Exception ex)
            {
                exceptions++;
                return BiomeMap.NULL;
            }
        }

        public final int getSmoothGrassColorMultiplier(int[] colormap, int width)
        {
            int mult = 0xFFFFFF;

            try
            {
                int rx = x - x_base;
                int rz = z - z_base;
                BiomeMap bm = biomemap[rx][rz];

                if (sameneighborbiomecnt[rx][rz] >= (byte)8)    /* All neighbors same? */
                {
                    mult = bm.getModifiedGrassMultiplier(colormap[bm.biomeLookup(width)]);
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
                            int rmult = bm.getModifiedGrassMultiplier(colormap[bm.biomeLookup(width)]);
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
                exceptions++;
                mult = 0xFFFFFF;
            }

            return mult;
        }
        public final int getSmoothFoliageColorMultiplier(int[] colormap, int width)
        {
            int mult = 0xFFFFFF;

            try
            {
                int rx = x - x_base;
                int rz = z - z_base;
                BiomeMap bm = biomemap[rx][rz];

                if (sameneighborbiomecnt[rx][rz] >= (byte)8)    /* All neighbors same? */
                {
                    mult = bm.getModifiedFoliageMultiplier(colormap[bm.biomeLookup(width)]);
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
                            int rmult = bm.getModifiedFoliageMultiplier(colormap[bm.biomeLookup(width)]);
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
                exceptions++;
                mult = 0xFFFFFF;
            }

            return mult;
        }
        public final int getSmoothColorMultiplier(int[] colormap, int width, int[] swampmap, int swampwidth)
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
                        mult = swampmap[bm.biomeLookup(swampwidth)];
                    }
                    else
                    {
                        mult = colormap[bm.biomeLookup(width)];
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
                                rmult = swampmap[bm.biomeLookup(swampwidth)];
                            }
                            else
                            {
                                rmult = colormap[bm.biomeLookup(width)];
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
                exceptions++;
                mult = 0xFFFFFF;
            }

            return mult;
        }

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
                exceptions++;
                return 0xFFFFFF;
            }
        }

        public final int getSmoothWaterColorMultiplier(int[] colormap, int width)
        {
            int mult = 0xFFFFFF;

            try
            {
                int rx = x - x_base;
                int rz = z - z_base;
                BiomeMap bm = biomemap[rx][rz];

                if (sameneighborbiomecnt[rx][rz] >= (byte)8)    /* All neighbors same? */
                {
                    mult = colormap[bm.biomeLookup(width)];
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
                            int rmult = colormap[bm.biomeLookup(width)];
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
                exceptions++;
                mult = 0xFFFFFF;
            }

            return mult;
        }
        /**
         * Step current position in given direction
         */
        public final void stepPosition(BlockStep step)
        {
            typeid = -1;
            blkdata = -1;

            switch (step.ordinal())
            {
                case 0:
                    x++;
                    bx++;
                    off++;

                    if (bx == 16)   /* Next chunk? */
                    {
                        try
                        {
                            bx = 0;
                            off -= 16;
                            chunkindex++;
                            snap = snaparray[chunkindex];
                        }
                        catch (ArrayIndexOutOfBoundsException aioobx)
                        {
                            snap = EMPTY;
                            exceptions++;
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
                    off += 16;

                    if (bz == 16)   /* Next chunk? */
                    {
                        try
                        {
                            bz = 0;
                            off -= 256;
                            chunkindex += x_dim;
                            snap = snaparray[chunkindex];
                        }
                        catch (ArrayIndexOutOfBoundsException aioobx)
                        {
                            snap = EMPTY;
                            exceptions++;
                        }
                    }

                    break;

                case 3:
                    x--;
                    bx--;
                    off--;

                    if (bx == -1)   /* Next chunk? */
                    {
                        try
                        {
                            bx = 15;
                            off += 16;
                            chunkindex--;
                            snap = snaparray[chunkindex];
                        }
                        catch (ArrayIndexOutOfBoundsException aioobx)
                        {
                            snap = EMPTY;
                            exceptions++;
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
                    off -= 16;

                    if (bz == -1)   /* Next chunk? */
                    {
                        try
                        {
                            bz = 15;
                            off += 256;
                            chunkindex -= x_dim;
                            snap = snaparray[chunkindex];
                        }
                        catch (ArrayIndexOutOfBoundsException aioobx)
                        {
                            snap = EMPTY;
                            exceptions++;
                        }
                    }

                    break;
            }

            laststep = step;
        }
        /**
         * Unstep current position to previous position
         */
        public BlockStep unstepPosition()
        {
            BlockStep ls = laststep;
            stepPosition(unstep[ls.ordinal()]);
            return ls;
        }
        /**
         * Unstep current position in oppisite director of given step
         */
        public void unstepPosition(BlockStep s)
        {
            stepPosition(unstep[s.ordinal()]);
        }
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
        public final int getX()
        {
            return x;
        }
        public final int getY()
        {
            return y;
        }
        public final int getZ()
        {
            return z;
        }
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
        public double getRawBiomeRainfall()
        {
            return 0;
        }
        @Override
        public double getRawBiomeTemperature()
        {
            return 0;
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
    }

    private class OurEndMapIterator extends OurMapIterator
    {
        OurEndMapIterator(int x0, int y0, int z0)
        {
            super(x0, y0, z0);
        }
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
            super(256, 0, 0, 0);
        }
        /* Need these for interface, but not used */
        public int getX()
        {
            return 0;
        }
        public int getZ()
        {
            return 0;
        }

        public final int getBlockTypeId(int x, int y, int z)
        {
            return 0;
        }
        public final int getBlockData(int x, int y, int z)
        {
            return 0;
        }
        public final int getBlockSkyLight(int x, int y, int z)
        {
            return 15;
        }
        public final int getBlockEmittedLight(int x, int y, int z)
        {
            return 0;
        }
        public final int getHighestBlockYAt(int x, int z)
        {
            return 0;
        }
        public int getBiome(int x, int z)
        {
            return -1;
        }
        public double getRawBiomeTemperature(int x, int z)
        {
            return 0.0;
        }
        public double getRawBiomeRainfall(int x, int z)
        {
            return 0.0;
        }
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
            super(256, 0, 0, 0);
            this.fillid = fillid;
        }
        /* Need these for interface, but not used */
        public int getX()
        {
            return 0;
        }
        public int getZ()
        {
            return 0;
        }
        public int getBiome(int x, int z)
        {
            return -1;
        }

        public final int getBlockTypeId(int x, int y, int z)
        {
            if (y < 64)
            {
                return fillid;
            }

            return 0;
        }
        public final int getBlockData(int x, int y, int z)
        {
            return 0;
        }
        public final int getBlockSkyLight(int x, int y, int z)
        {
            if (y < 64)
            {
                return 0;
            }

            return 15;
        }
        public final int getBlockEmittedLight(int x, int y, int z)
        {
            return 0;
        }
        public final int getHighestBlockYAt(int x, int z)
        {
            return 64;
        }
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
                if((chunksToRemove == null) && (f[i].getType().equals(List.class))) {
                    chunksToRemove = f[i];
                    chunksToRemove.setAccessible(true);
                }
                else if((pendingAnvilChunksCoordinates == null) && (f[i].getType().equals(Set.class))) {
                    pendingAnvilChunksCoordinates = f[i];
                    pendingAnvilChunksCoordinates.setAccessible(true);
                }
                else if((syncLockObject == null) && (f[i].getType().equals(Object.class))) {
                    syncLockObject = f[i];
                    syncLockObject.setAccessible(true);
                }
            }

            if ((unloadqueue == null) || (currentchunkloader == null))
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

        int snapcnt = x_dim * (z_max-z_min+1);
        snaparray = new ChunkSnapshot[snapcnt];
        snaptile = new DynIntHashMap[snapcnt];
        isSectionNotEmpty = new boolean[snapcnt][];
    }

    private Chunk loadChunkNoGenerate(int x, int z)
    {
        if ((cps == null) || (currentchunkloader == null))
        {
            return null;
        }

        Chunk c = null;

        try
        {
            IChunkLoader cur_ccl = null;
            /* Get current chunk loader - save value */
            cur_ccl = (IChunkLoader)currentchunkloader.get(cps);
            /* Set current chunk loader to no-create loader - prevents generate on miss */
            currentchunkloader.set(cps,  noCreateLoader);
            /* Set loader to prevent create of target chunk */
            noCreateLoader.base = cur_ccl;
            noCreateLoader.ww = w;
            noCreateLoader.xx = x;
            noCreateLoader.zz = z;
            
            try
            {
                /* Now, try to load chunk - throws IllegalArgumentException if doesn't exist */
                c = cps.loadChunk(x,  z);
            } catch (NoChunkFoundThrow ncft) {
                c = null;
            }
            finally
            {
                /* And restore current chunk loader */
                currentchunkloader.set(cps,  cur_ccl);
                noCreateLoader.ww = null;
            }
        }
        catch (IllegalArgumentException iax)
        {
            c = null;
        }
        catch (IllegalAccessException iaxx)
        {
            c = null;
        }
        catch (NullPointerException npx)
        {
            c = null;
        }

        return c;
    }
    
    public NBTTagCompound readChunk(int x, int z) {
        if((cps == null) ||
                (chunksToRemove == null) || (pendingAnvilChunksCoordinates == null) ||
                (syncLockObject == null) || (currentchunkloader == null)) {
            return null;
        }
        try {
            Object ccl = currentchunkloader.get(cps);   /* Get chunk loader */
            if(!(ccl instanceof AnvilChunkLoader)) {
                return null;
            }
            AnvilChunkLoader acl = (AnvilChunkLoader)ccl;
            List chunkstoremove = (List)chunksToRemove.get(acl);
            Set pendingcoords = (Set)pendingAnvilChunksCoordinates.get(acl);
            Object synclock = syncLockObject.get(acl);

            NBTTagCompound rslt = null;
            ChunkCoordIntPair coord = new ChunkCoordIntPair(x, z);

            synchronized (synclock) {
                if (pendingcoords.contains(coord)) {
                    for (int i = 0; i < chunkstoremove.size(); i++) {
                        Object o = chunkstoremove.get(i);
                        if (chunkCoord == null) {
                            Field[] f = o.getClass().getDeclaredFields();
                            for(Field ff : f) {
                                if((chunkCoord == null) && (ff.getType().equals(ChunkCoordIntPair.class))) {
                                    chunkCoord = ff;
                                }
                                else if((nbtTag == null) && (ff.getType().equals(NBTTagCompound.class))) {
                                    nbtTag = ff;
                                }
                            }
                        }
                        ChunkCoordIntPair occ = (ChunkCoordIntPair)chunkCoord.get(o);
                        
                        if (occ.equals(coord)) {
                            rslt = (NBTTagCompound)nbtTag.get(o);
                            break;
                        }
                    }
                }
            }

            if (rslt == null) {
                DataInputStream str = RegionFileCache.getChunkInputStream(((WorldServer)w).getChunkSaveLocation(), x, z);

                if (str == null) {
                    return null;
                }
                rslt = CompressedStreamTools.read(str);
            }
            if(rslt != null) 
                rslt = rslt.getCompoundTag("Level");
            return rslt;
        } catch (Exception exc) {
            return null;
        }
    }

    public int loadChunks(int max_to_load)
    {
        long t0 = System.nanoTime();
        if(!dw.isLoaded()) {
            isempty = true;
            unloadChunks();
            return 0;
        }
        Set queue = null;
        IChunkProvider cp = w.getChunkProvider();

        try
        {
            if ((unloadqueue != null) && (cps != null))
            {
                queue = (Set)unloadqueue.get(cps);
            }
        }
        catch (IllegalArgumentException iax)
        {
        }
        catch (IllegalAccessException e)
        {
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
            DynmapChunk chunk = iterator.next();
            boolean vis = true;

            if (visible_limits != null)
            {
                vis = false;

                for (VisibilityLimit limit : visible_limits)
                {
                    if ((chunk.x >= limit.x0) && (chunk.x <= limit.x1) && (chunk.z >= limit.z0) && (chunk.z <= limit.z1))
                    {
                        vis = true;
                        break;
                    }
                }
            }

            if (vis && (hidden_limits != null))
            {
                for (VisibilityLimit limit : hidden_limits)
                {
                    if ((chunk.x >= limit.x0) && (chunk.x <= limit.x1) && (chunk.z >= limit.z0) && (chunk.z <= limit.z1))
                    {
                        vis = false;
                        break;
                    }
                }
            }

            /* Check if cached chunk snapshot found */
            ChunkSnapshot ss = null;
            DynIntHashMap tileData = null;
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

                continue;
            }

            chunks_attempted++;
            boolean wasLoaded = cps.chunkExists(chunk.x, chunk.z);
            boolean didload = false;
            boolean isunloadpending = false;

            if (queue != null)
            {
                long coord = ChunkCoordIntPair.chunkXZ2Int(chunk.x, chunk.z);
                isunloadpending = queue.contains(Long.valueOf(coord));
            }

            Chunk c = null;

            if (isunloadpending)    /* Workaround: can't be pending if not loaded */
            {
                wasLoaded = true;
            }

            NBTTagCompound nbt = null;
            
            if (!wasLoaded)
            {
                nbt = readChunk(chunk.x, chunk.z);
                if(nbt == null) {
                    c = loadChunkNoGenerate(chunk.x, chunk.z);
                }
                didload = (c != null) || (nbt != null);
            }
            else    /* If already was loaded, no need to load */
            {
                c = cp.loadChunk(chunk.x, chunk.z);
                didload = true;
            }

            boolean didgenerate = false;

            /* If we didn't load, and we're supposed to generate, do it */
            if ((!didload) && do_generate && vis)
            {
                c = cp.loadChunk(chunk.x, chunk.z);
                didgenerate = didload = (c != null);
            }

            /* If it did load, make cache of it */
            if (didload)
            {
                tileData = new DynIntHashMap();

                /* Test if chunk isn't populated */
                boolean populated = true;

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
                else if (!populated)    /* If not populated, treat as empty */
                {
                    ss = EMPTY;
                }
                else
                {
                    if(nbt != null) {
                        ss = new ChunkSnapshot(nbt);
                        
                        NBTTagList tiles = nbt.getTagList("TileEntities");
                        if(tiles == null) tiles = new NBTTagList();
                        /* Get tile entity data */
                        List<Object> vals = new ArrayList<Object>();
                        for(int tid = 0; tid < tiles.tagCount(); tid++) {
                            NBTTagCompound tc = (NBTTagCompound)tiles.tagAt(tid);
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
                                        Object val = null;
                                        switch(v.getId()) {
                                            case 1: // Byte
                                                val = Byte.valueOf(((NBTTagByte)v).data);
                                                break;
                                            case 2: // Short
                                                val = Short.valueOf(((NBTTagShort)v).data);
                                                break;
                                            case 3: // Int
                                                val = Integer.valueOf(((NBTTagInt)v).data);
                                                break;
                                            case 4: // Long
                                                val = Long.valueOf(((NBTTagLong)v).data);
                                                break;
                                            case 5: // Float
                                                val = Float.valueOf(((NBTTagFloat)v).data);
                                                break;
                                            case 6: // Double
                                                val = Double.valueOf(((NBTTagDouble)v).data);
                                                break;
                                            case 7: // Byte[]
                                                val = ((NBTTagByteArray)v).byteArray;
                                                break;
                                            case 8: // String
                                                val = ((NBTTagString)v).data;
                                                break;
                                            case 11: // Int[]
                                                val = ((NBTTagIntArray)v).intArray;
                                                break;
                                        }
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
                        ssr = new SnapshotRec();
                        ssr.ss = ss;
                        ssr.tileData = tileData;
                        DynmapPlugin.plugin.sscache.putSnapshot(dw.getName(), chunk.x, chunk.z, ssr, blockdata, biome, biomeraw, highesty);
                    }
                    else {
                        ss = new ChunkSnapshot(c);
                        /* Get tile entity data */
                        List<Object> vals = new ArrayList<Object>();
                        for(Object t : c.chunkTileEntityMap.values()) {
                            TileEntity te = (TileEntity)t;
                            int cx = te.xCoord & 0xF;
                            int cz = te.zCoord & 0xF;
                            int blkid = ss.getBlockTypeId(cx, te.yCoord, cz);
                            int blkdat = ss.getBlockData(cx, te.yCoord, cz);
                            String[] te_fields = HDBlockModels.getTileEntityFieldsNeeded(blkid,  blkdat);
                            if(te_fields != null) {
                                NBTTagCompound tc = new NBTTagCompound();
                                try {
                                    te.writeToNBT(tc);
                                } catch (Exception x) {
                                }
                                vals.clear();
                                for(String id: te_fields) {
                                    NBTBase v = tc.getTag(id);  /* Get field */
                                    if(v != null) {
                                        Object val = null;
                                        switch(v.getId()) {
                                            case 1: // Byte
                                                val = Byte.valueOf(((NBTTagByte)v).data);
                                                break;
                                            case 2: // Short
                                                val = Short.valueOf(((NBTTagShort)v).data);
                                                break;
                                            case 3: // Int
                                                val = Integer.valueOf(((NBTTagInt)v).data);
                                                break;
                                            case 4: // Long
                                                val = Long.valueOf(((NBTTagLong)v).data);
                                                break;
                                            case 5: // Float
                                                val = Float.valueOf(((NBTTagFloat)v).data);
                                                break;
                                            case 6: // Double
                                                val = Double.valueOf(((NBTTagDouble)v).data);
                                                break;
                                            case 7: // Byte[]
                                                val = ((NBTTagByteArray)v).byteArray;
                                                break;
                                            case 8: // String
                                                val = ((NBTTagString)v).data;
                                                break;
                                            case 11: // Int[]
                                                val = ((NBTTagIntArray)v).intArray;
                                                break;
                                        }
                                        if(val != null) {
                                            vals.add(id);
                                            vals.add(val);
                                        }
                                    }
                                }
                                if(vals.size() > 0) {
                                    Object[] vlist = vals.toArray(new Object[vals.size()]);
                                    tileData.put(getIndexInChunk(cx,te.yCoord,cz), vlist);
                                }
                            }
                        }
                        ssr = new SnapshotRec();
                        ssr.ss = ss;
                        ssr.tileData = tileData;
                        DynmapPlugin.plugin.sscache.putSnapshot(dw.getName(), chunk.x, chunk.z, ssr, blockdata, biome, biomeraw, highesty);
                    }
                }

                snaparray[(chunk.x - x_min) + (chunk.z - z_min) * x_dim] = ss;
                snaptile[(chunk.x-x_min) + (chunk.z - z_min)*x_dim] = tileData;

                /* If wasn't loaded before, we need to do unload */
                if (nbt != null) {
                    /* No unload needed if we fetched NBT */
                    chunks_read++;
                }
                else if (!wasLoaded)
                {
                    chunks_read++;

                    if (cps != null)
                    {
                        cps.unloadChunksIfNotNearSpawn(chunk.x,  chunk.z);
                    }
                }
                else if (isunloadpending)   /* Else, if loaded and unload is pending */
                {
                    if (cps != null)
                    {
                        cps.unloadChunksIfNotNearSpawn(chunk.x,  chunk.z);
                    }
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
            if(updateEntityTick != null) {
                try {
                    /* Clear updateEntityTick - prevents problems due to entities not being cleaned up when no players are online */
                    updateEntityTick.set(w, 0);
                } catch (Exception x) {
                    Log.severe("Cannot update updateEntityTick on world - " + x.getMessage());
                }
            }
        }

        total_loadtime += System.nanoTime() - t0;
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
     * Set autogenerate - must be done after at least one visible range has been set
     */
    public void setAutoGenerateVisbileRanges(DynmapWorld.AutoGenerateOption generateopt)
    {
        if ((generateopt != DynmapWorld.AutoGenerateOption.NONE) && ((visible_limits == null) || (visible_limits.size() == 0)))
        {
            Log.severe("Cannot setAutoGenerateVisibleRanges() without visible ranges defined");
            return;
        }

        this.do_generate = (generateopt != DynmapWorld.AutoGenerateOption.NONE);
        this.do_save = (generateopt == DynmapWorld.AutoGenerateOption.PERMANENT);
    }
    /**
     * Add visible area limit - can be called more than once
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setVisibleRange(VisibilityLimit lim)
    {
        VisibilityLimit limit = new VisibilityLimit();

        if (lim.x0 > lim.x1)
        {
            limit.x0 = (lim.x1 >> 4);
            limit.x1 = ((lim.x0 + 15) >> 4);
        }
        else
        {
            limit.x0 = (lim.x0 >> 4);
            limit.x1 = ((lim.x1 + 15) >> 4);
        }

        if (lim.z0 > lim.z1)
        {
            limit.z0 = (lim.z1 >> 4);
            limit.z1 = ((lim.z0 + 15) >> 4);
        }
        else
        {
            limit.z0 = (lim.z0 >> 4);
            limit.z1 = ((lim.z1 + 15) >> 4);
        }

        if (visible_limits == null)
        {
            visible_limits = new ArrayList<VisibilityLimit>();
        }

        visible_limits.add(limit);
    }
    /**
     * Add hidden area limit - can be called more than once
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setHiddenRange(VisibilityLimit lim)
    {
        VisibilityLimit limit = new VisibilityLimit();

        if (lim.x0 > lim.x1)
        {
            limit.x0 = (lim.x1 >> 4);
            limit.x1 = ((lim.x0 + 15) >> 4);
        }
        else
        {
            limit.x0 = (lim.x0 >> 4);
            limit.x1 = ((lim.x1 + 15) >> 4);
        }

        if (lim.z0 > lim.z1)
        {
            limit.z0 = (lim.z1 >> 4);
            limit.z1 = ((lim.z0 + 15) >> 4);
        }
        else
        {
            limit.z0 = (lim.z0 >> 4);
            limit.z1 = ((lim.z1 + 15) >> 4);
        }

        if (hidden_limits == null)
        {
            hidden_limits = new ArrayList<VisibilityLimit>();
        }

        hidden_limits.add(limit);
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
    @Override
    public int getChunksLoaded()
    {
        return chunks_read;
    }
    @Override
    public int getChunkLoadsAttempted()
    {
        return chunks_attempted;
    }
    @Override
    public long getTotalRuntimeNanos()
    {
        return total_loadtime;
    }
    @Override
    public long getExceptionCount()
    {
        return exceptions;
    }

    static
    {
        BiomeGenBase b[] = BiomeGenBase.biomeList;
        BiomeMap[] bm = BiomeMap.values();
        biome_to_bmap = new BiomeMap[256];

        for (int i = 0; i < biome_to_bmap.length; i++)
        {
            biome_to_bmap[i] = BiomeMap.NULL;
        }

        for (int i = 0; i < b.length; i++)
        {
            if(b[i] == null) continue;
            
            String bs = b[i].biomeName;

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