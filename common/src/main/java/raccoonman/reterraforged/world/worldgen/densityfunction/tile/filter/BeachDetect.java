package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.BeachParameterCache;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

public record BeachDetect(Levels levels, ControlPoints transition) implements Filter {

    public static final float SAFE_EROSION = 0.5F;
    public static final float SAFE_WEIRDNESS = 0.15F;
    private static final float STEEPNESS_THRESHOLD = 20e-7F;

    private boolean isSteep(Filterable map, Cell cell, int x, int z) {
        float sum = 0;
        int count = 0;

        for (int dz = -4; dz <= 4; dz += 2) {
            for (int dx = -4; dx <= 4; dx += 2) {
                Cell sample = map.getCellRaw(x + dx, z + dz);
                if (sample.isAbsent()) continue;

                float d2 = this.computeD2(map, sample, x + dx, z + dz);
                sum += d2;
                count++;
            }
        }

        if (count == 0) return false;

        return sum / count >= STEEPNESS_THRESHOLD;
    }

    private float computeD2(Filterable map, Cell cell, int x, int z) {
        Cell n = map.getCellRaw(x, z - 4);
        Cell s = map.getCellRaw(x, z + 4);
        Cell e = map.getCellRaw(x + 4, z);
        Cell w = map.getCellRaw(x - 4, z);

        float gx = this.grad(e, w, cell);
        float gz = this.grad(n, s, cell);

        return gx * gx + gz * gz;
    }

    private float grad(Cell a, Cell b, Cell def) {
        int distance = 16;

        if (a.isAbsent()) {
            a = def;
            distance -= 8;
        }

        if (b.isAbsent()) {
            b = def;
            distance -= 8;
        }

        return (a.height - b.height) / distance;
    }

    @Override
    public void apply(Filterable map, int seedX, int seedZ, int iterations) {
        Size size = map.getBlockSize();
        int total = size.total();

        for (int x = 0; x < total; x++) {
            for (int z = 0; z < total; z++) {
                Cell cell = map.getCellRaw(x, z);

                if (cell.terrain.overridesCoast() || cell.terrain.isWetland()
                        || cell.terrain.isRiver() || cell.terrain.isLake()) {
                    continue;
                }

                // purely continentEdge-driven — matches whatever band CellSampler
                // uses for COAST continentalness, no neighbor lookups at all
                boolean inCoastBand = cell.continentEdge >= this.transition.shallowOcean
                        && cell.continentEdge <= this.transition.beach;

                if (!inCoastBand) {
                    continue;
                }

                boolean underwater = cell.height <= this.levels.water;

                if (underwater) {
                    if (!(cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean())) {
                        continue; // don't touch river/lake cells that dip below sea level
                    }
                    int depthBlocks = this.levels.scale(this.levels.water) - this.levels.scale(cell.height);
                    if (depthBlocks <= 6) {
                        cell.terrain = TerrainType.SHOAL;
                    } else {
                        continue;
                    }
                } else {
                    if (!cell.terrain.isOverground()) {
                        continue;
                    }
                    cell.terrain = TerrainType.BEACH;
                }

                boolean steep = this.isSteep(map, cell, x, z);

                long cellSeed = PosUtil.pack(x, z); // or however you derive a stable per-position long elsewhere in this codebase
                float[] safe = BeachParameterCache.findClosestErosionWeirdness(
                        cell.temperature, cell.moisture, cell.continentEdge, cell.erosion, steep, cellSeed
                );

                if (safe != null) {
                    cell.erosion = safe[0];
                    cell.weirdness = safe[1];
                } else {
                    cell.erosion = SAFE_EROSION;
                    cell.weirdness = SAFE_WEIRDNESS;
                }
            }
        }
    }

    public static BeachDetect make(GeneratorContext ctx) {
        Levels levels = ctx.levels;
        ControlPoints transition = ctx.preset.world().controlPoints;
        return new BeachDetect(levels, transition);
    }
}