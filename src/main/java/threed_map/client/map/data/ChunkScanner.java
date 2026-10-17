package threed_map.client.map.data;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Reads the top visible block of every column of a loaded client chunk.
 * Water is see-through: the color blends the water tint with the bottom, darker with depth.
 * In dimensions with a ceiling (Nether) the scan starts below the roof.
 */
public final class ChunkScanner {
    /** Explored column with nothing in it (End void). */
    private static final int VOID_COLOR = 0xFF0B0B12;
    private static final int MAX_WATER_DEPTH = 24;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    /** Fetched per scan: the scanner is created during mod loading, before block colors exist. */
    private BlockColors blockColors;

    /** @return false if the target region is still loading from disk and the chunk should be retried. */
    public boolean scan(ClientLevel level, LevelChunk chunk, MapDimension dim) {
        blockColors = Minecraft.getInstance().getBlockColors();
        ChunkPos cp = chunk.getPos();
        MapRegion region = dim.regionForWrite(cp.getMinBlockX() >> MapRegion.SHIFT, cp.getMinBlockZ() >> MapRegion.SHIFT);
        if (region == null) {
            return false;
        }
        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinBuildHeight();
        int roofStart = minY + level.dimensionType().logicalHeight() - 1;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int wx = cp.getMinBlockX() + x;
                int wz = cp.getMinBlockZ() + z;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int startY = ceiling ? belowCeiling(chunk, wx, wz, Math.min(top, roofStart), minY) : top;
                if (startY == Integer.MIN_VALUE) {
                    continue; // solid to the bottom: nothing visible from above
                }
                scanColumn(level, chunk, region, dim, wx, wz, startY, minY);
            }
        }
        return true;
    }

    /** First air block below the roof, or MIN_VALUE if the column has no open space. */
    private int belowCeiling(LevelChunk chunk, int wx, int wz, int y, int minY) {
        while (y >= minY && !chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
            y--;
        }
        return y >= minY ? y : Integer.MIN_VALUE;
    }

    private void scanColumn(ClientLevel level, LevelChunk chunk, MapRegion region, MapDimension dim,
                            int wx, int wz, int y, int minY) {
        int waterTop = Integer.MIN_VALUE;
        int waterColor = 0;
        for (; y >= minY; y--) {
            BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
            if (state.isAir()) {
                continue;
            }
            if (state.getFluidState().is(FluidTags.WATER)) {
                if (waterTop == Integer.MIN_VALUE) {
                    waterTop = y;
                    waterColor = 0xFF000000 | blockColors.getColor(state.getFluidState().createLegacyBlock(), level, pos, 0);
                }
                if (waterTop - y < MAX_WATER_DEPTH) {
                    continue;
                }
                break;
            }
            MapColor mapColor = state.getMapColor(level, pos);
            if (mapColor == MapColor.NONE) {
                continue; // glass, barriers, light blocks...
            }
            int color = blockColor(state, mapColor, level);
            if (waterTop != Integer.MIN_VALUE) {
                color = water(waterColor, color, waterTop - y);
                dim.setColumn(region, wx & MapRegion.MASK, wz & MapRegion.MASK, color, waterTop);
            } else {
                dim.setColumn(region, wx & MapRegion.MASK, wz & MapRegion.MASK, color, y);
            }
            return;
        }
        if (waterTop != Integer.MIN_VALUE) {
            dim.setColumn(region, wx & MapRegion.MASK, wz & MapRegion.MASK, water(waterColor, waterColor, MAX_WATER_DEPTH), waterTop);
        } else {
            dim.setColumn(region, wx & MapRegion.MASK, wz & MapRegion.MASK, VOID_COLOR, minY);
        }
    }

    private int blockColor(BlockState state, MapColor mapColor, ClientLevel level) {
        // Biome tint (grass, leaves, vines) when the block has one; otherwise the vanilla map color.
        int tint = blockColors.getColor(state, level, pos, 0);
        return 0xFF000000 | (tint != -1 ? tint : mapColor.col);
    }

    private static int water(int waterColor, int bottom, int depth) {
        float t = Math.min(1f, 0.45f + depth * 0.07f);
        int mixed = MapShading.mix(bottom, waterColor, t);
        return MapShading.scale(mixed, 1f - Math.min(depth, MAX_WATER_DEPTH) * 0.012f);
    }
}
