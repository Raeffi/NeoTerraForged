package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.biome.BeachParameterCache;
import raccoonman.reterraforged.world.worldgen.biome.Erosion;
import raccoonman.reterraforged.world.worldgen.biome.Weirdness;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

public record BeachDetect(Levels levels, ControlPoints transition) implements Filter {

    public static final float SAFE_BEACH_EROSION = Erosion.LEVEL_3.mid();
    public static final float SAFE_BEACH_WEIRDNESS = Weirdness.VALLEY.mid();
    public static final float SAFE_STONY_EROSION = Erosion.LEVEL_0.mid();
    public static final float SAFE_STONY_WEIRDNESS = Weirdness.VALLEY.mid();

    // Adjusted for the physically accurate 8.0 block distance divisor
    private static final float STEEPNESS_THRESHOLD = 80e-7F;

    private float computeD2(Filterable map, Cell center, int x, int z) {
        Cell n = map.getCellRaw(x, z - 4);
        Cell s = map.getCellRaw(x, z + 4);
        Cell e = map.getCellRaw(x + 4, z);
        Cell w = map.getCellRaw(x - 4, z);

        float gx = this.gradSafe(e, w, center);
        float gz = this.gradSafe(s, n, center);

        return gx * gx + gz * gz;
    }

    private float gradSafe(Cell pos, Cell neg, Cell center) {
        boolean posValid = !pos.isAbsent();
        boolean negValid = !neg.isAbsent();

        if (posValid && negValid) {
            return (pos.height - neg.height) / 8.0F; // 8 block span
        } else if (posValid) {
            return (pos.height - center.height) / 4.0F; // 4 block span
        } else if (negValid) {
            return (center.height - neg.height) / 4.0F; // 4 block span
        }
        return 0.0F;
    }

    @Override
    public void apply(Filterable map, int seedX, int seedZ, int iterations) {
        Size size = map.getBlockSize();
        int total = size.total();

        float[] d2Buffer = new float[total * total];
        float[] blurX = new float[total * total];

        // Safely initialize with -1.0F to prevent 0.0F border drag
        for (int i = 0; i < d2Buffer.length; i++) {
            d2Buffer[i] = -1.0F;
            blurX[i] = -1.0F;
        }

        // --- PASS 1 ---
        for (int z = 0; z < total; z++) {
            for (int x = 0; x < total; x++) {
                Cell center = map.getCellRaw(x, z);
                if (center.isAbsent()) continue;
                d2Buffer[x + z * total] = this.computeD2(map, center, x, z);
            }
        }

        // --- PASS 2 ---
        for (int z = 0; z < total; z++) {
            for (int x = 0; x < total; x++) {
                float sum = 0;
                int count = 0;
                for (int dx = -4; dx <= 4; dx++) {
                    int nx = x + dx;
                    if (nx >= 0 && nx < total) {
                        float val = d2Buffer[nx + z * total];
                        if (val >= 0.0F) { // Ignore absent blocks!
                            sum += val;
                            count++;
                        }
                    }
                }
                blurX[x + z * total] = count > 0 ? sum / count : -1.0F;
            }
        }

        // --- PASS 3 ---
        for (int z = 0; z < total; z++) {
            for (int x = 0; x < total; x++) {
                Cell cell = map.getCellRaw(x, z);

                if (cell.terrain.overridesCoast() || cell.terrain.isWetland()
                        || cell.terrain.isRiver() || cell.terrain.isLake()) {
                    continue;
                }

                boolean inCoastBand = cell.continentEdge >= this.transition.shallowOcean
                        && cell.continentEdge <= this.transition.beach;

                if (!inCoastBand) {
                    continue;
                }

                float sum = 0;
                int count = 0;
                for (int dz = -4; dz <= 4; dz++) {
                    int nz = z + dz;
                    if (nz >= 0 && nz < total) {
                        float val = blurX[x + nz * total];
                        if (val >= 0.0F) { // Ignore absent blocks!
                            sum += val;
                            count++;
                        }
                    }
                }

                float finalD2 = count > 0 ? sum / count : 0.0F;
                boolean steep = finalD2 >= STEEPNESS_THRESHOLD;

                boolean underwater = cell.height <= this.levels.water;
                if (underwater) {
                    if (!(cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean())) {
                        continue;
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

                // Since we removed RNG, the seed parameter is arbitrary now
                float[] safe = BeachParameterCache.findClosestErosionWeirdness(
                        cell.temperature, cell.moisture, cell.continentEdge, cell.erosion, steep, 0L
                );

                if (safe != null) {
                    cell.erosion = safe[0];
                    cell.weirdness = safe[1];
                } else if (steep) {
                    cell.erosion = SAFE_STONY_EROSION;
                    cell.weirdness = SAFE_STONY_WEIRDNESS;
                } else {
                    cell.erosion = SAFE_BEACH_EROSION;
                    cell.weirdness = SAFE_BEACH_WEIRDNESS;
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