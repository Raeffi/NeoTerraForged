package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public record BeachDetect(Levels levels, ControlPoints transition) implements Filter {

    // maximum values, used when slider is fully toward COAST
    private static final int MAX_SEARCH_RADIUS = 12;
    private static final int MAX_SHALLOW_DEPTH = 6;
    private static final int MAX_DILATE_RADIUS = 8;
    private static final int STEP = 4;

    private static final float SAFE_EROSION = 0.5F;
    private static final float SAFE_WEIRDNESS = 0.0F;

    // 0 = slider at shallowOcean (no beach width), 1 = slider at coast (max width)
    private float widthRatio() {
        float shallowOcean = this.transition.shallowOcean;
        float coast = this.transition.coast;
        float beach = this.transition.beach;
        if (coast == shallowOcean) {
            return 0.0F;
        }
        float ratio = (beach - shallowOcean) / (coast - shallowOcean);
        return NoiseUtil.clamp(ratio, 0.0F, 1.0F);
    }

    @Override
    public void apply(Filterable map, int seedX, int seedZ, int iterations) {
        Size size = map.getBlockSize();
        int total = size.total();

        float ratio = this.widthRatio();
        int searchRadius = Math.round(MAX_SEARCH_RADIUS * ratio);
        int shallowDepth = Math.round(MAX_SHALLOW_DEPTH * ratio);
        int dilateRadius = Math.round(MAX_DILATE_RADIUS * ratio);

        if (ratio <= 0.0F) {
            return; // slider at shallowOcean: no beach generation at all
        }

        // pass 1: primary classification
        for (int x = 0; x < total; x++) {
            for (int z = 0; z < total; z++) {
                Cell cell = map.getCellRaw(x, z);

                if (cell.terrain.overridesCoast() || cell.terrain.isWetland()) {
                    continue;
                }

                boolean underwater = cell.height <= this.levels.water;

                if (underwater) {
                    int depthBlocks = this.levels.scale(this.levels.water) - this.levels.scale(cell.height);
                    if (depthBlocks <= shallowDepth) {
                        Cell target = map.getCellRaw(x, z);
                        target.terrain = TerrainType.SHOAL;
                        this.forceSafeParameters(target);
                    }
                } else {
                    if (cell.terrain.isOverground() && this.isNearWater(map, x, z, searchRadius)) {
                        Cell target = map.getCellRaw(x, z);
                        target.terrain = TerrainType.BEACH;
                        this.forceSafeParameters(target);
                    }
                }
            }
        }

        // pass 2: dilation to catch stray quart-cell sampling gaps
        for (int x = 0; x < total; x++) {
            for (int z = 0; z < total; z++) {
                Cell cell = map.getCellRaw(x, z);

                if (cell.terrain == TerrainType.BEACH || cell.terrain == TerrainType.SHOAL) {
                    continue;
                }
                if (cell.terrain.overridesCoast() || cell.terrain.isWetland()) {
                    continue;
                }

                if (this.isNearBeachOrShoal(map, x, z, dilateRadius)) {
                    boolean underwater = cell.height <= this.levels.water;
                    Cell target = map.getCellRaw(x, z);
                    target.terrain = underwater ? TerrainType.SHOAL : TerrainType.BEACH;
                    this.forceSafeParameters(target);
                }
            }
        }
    }

    private boolean isNearBeachOrShoal(Filterable map, int x, int z, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dz == 0) continue;
                Cell neighbor = map.getCellRaw(x + dx, z + dz);
                if (neighbor.isAbsent()) continue;
                if (neighbor.terrain == TerrainType.BEACH || neighbor.terrain == TerrainType.SHOAL) {
                    return true;
                }
            }
        }
        return false;
    }

    private void forceSafeParameters(Cell cell) {
        cell.erosion = SAFE_EROSION;
        cell.weirdness = SAFE_WEIRDNESS;
    }

    private boolean isNearWater(Filterable map, int x, int z, int radius) {
        for (int dz = -radius; dz <= radius; dz += STEP) {
            for (int dx = -radius; dx <= radius; dx += STEP) {
                if (dx == 0 && dz == 0) continue;
                Cell neighbor = map.getCellRaw(x + dx, z + dz);
                if (neighbor.isAbsent()) continue;
                if (neighbor.height <= this.levels.water) {
                    return true;
                }
            }
        }
        return false;
    }

    public static BeachDetect make(GeneratorContext ctx) {
        Levels levels = ctx.levels;
        ControlPoints transition = ctx.preset.world().controlPoints;
        return new BeachDetect(levels, transition);
    }
}