package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.BeachParameterCache;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public record BeachDetect(Levels levels, ControlPoints transition) implements Filter {

    // maximum values, used when slider is fully toward COAST
    private static final int MAX_SEARCH_RADIUS = 4;
    private static final int MAX_SHALLOW_DEPTH = 6;
    private static final int MAX_DILATE_RADIUS = 2;
    private static final int STEP = 4;

    private static final float SAFE_EROSION = 0.5F;
    private static final float SAFE_WEIRDNESS = 0.0F;

    private static final float STEEPNESS_THRESHOLD = 0.06F; // tune based on testing, same scale as your original d2 check


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
                        boolean steep = this.computeSteepness(map, x, z) >= STEEPNESS_THRESHOLD;
                        target.terrain = TerrainType.SHOAL; // or BEACH
                        this.forceSafeParameters(target, steep);
                    }
                } else {
                    if (cell.terrain.isOverground() && this.isNearWater(map, x, z, searchRadius)) {
                        Cell target = map.getCellRaw(x, z);
                        boolean steep = this.computeSteepness(map, x, z) >= STEEPNESS_THRESHOLD;
                        target.terrain = TerrainType.BEACH; // or SHOAL
                        this.forceSafeParameters(target, steep);
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
                    boolean steep = this.computeSteepness(map, x, z) >= STEEPNESS_THRESHOLD;
                    this.forceSafeParameters(target, steep);
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

    // vanilla's registered stony_shore parameter point (approximate, from overworld.json)
    private static final Climate.ParameterPoint STONY_SHORE_POINT = new Climate.ParameterPoint(
            Climate.Parameter.span(-0.19F, -0.11F),  // continentalness (COAST band)
            Climate.Parameter.span(0.55F, 1.0F),     // erosion (high erosion, matches vanilla's rocky/steep coast placement)
            Climate.Parameter.span(-1.0F, 1.0F),     // temperature (any)
            Climate.Parameter.span(-1.0F, 1.0F),     // humidity (any)
            Climate.Parameter.span(-1.0F, 1.0F),     // weirdness (any)
            Climate.Parameter.span(-1.0F, 1.0F),
            0L
    );

    private float computeSteepness(Filterable map, int x, int z) {
        Cell n = map.getCellRaw(x, z - 8);
        Cell s = map.getCellRaw(x, z + 8);
        Cell e = map.getCellRaw(x + 8, z);
        Cell w = map.getCellRaw(x - 8, z);
        float gx = this.grad(e, w, map.getCellRaw(x, z));
        float gz = this.grad(n, s, map.getCellRaw(x, z));
        return gx * gx + gz * gz;
    }

    private float grad(Cell a, Cell b, Cell def) {
        int distance = 17;
        if (a.isAbsent()) { a = def; distance -= 8; }
        if (b.isAbsent()) { b = def; distance -= 8; }
        return (a.height - b.height) / distance;
    }

    private void forceSafeParameters(Cell cell, boolean steep) {
        float[] safe = BeachParameterCache.findClosestErosionWeirdness(cell.temperature, cell.moisture);
        if (safe != null) {
            cell.erosion = safe[0];
            cell.weirdness = safe[1];
        } else {
            cell.erosion = SAFE_EROSION;
            cell.weirdness = SAFE_WEIRDNESS;
        }
        if (steep) {
            cell.erosion = (STONY_SHORE_POINT.erosion().min() + STONY_SHORE_POINT.erosion().max()) / 2.0F;
            cell.weirdness = SAFE_WEIRDNESS; // keep your existing safe weirdness, avoid VALLEY
            return;
        }
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