package raccoonman.reterraforged.world.worldgen.biome;

import java.util.List;

import net.minecraft.world.level.biome.Climate;

public class BeachParameterCache {
    private static List<Climate.ParameterPoint> beachPoints = List.of();
    private static List<Climate.ParameterPoint> stonyShorePoints = List.of();

    private static final float QUANTIZATION = 10000.0F;

    public static void set(List<Climate.ParameterPoint> beach, List<Climate.ParameterPoint> stonyShore) {
        beachPoints = List.copyOf(beach);
        stonyShorePoints = List.copyOf(stonyShore);
    }

    public static float[] findClosestErosionWeirdness(float temperature, float humidity, float continentalness, float erosion, boolean steep) {
        // steep terrain always gets stony_shore, no search needed
        if (steep && !stonyShorePoints.isEmpty()) {
            Climate.ParameterPoint point = stonyShorePoints.get(0);
            return toErosionWeirdness(point);
        }

        if (beachPoints.isEmpty()) {
            return null;
        }
        Climate.ParameterPoint best = null;
        long bestDist = Long.MAX_VALUE;
        for (Climate.ParameterPoint point : beachPoints) {
            long dist = point.fitness(Climate.target(
                    temperature,
                    humidity,
                    continentalness,
                    erosion,
                    0,
                    0
            ));
            if (dist < bestDist) {
                bestDist = dist;
                best = point;
            }
        }
        if (best == null) {
            return null;
        }
        return toErosionWeirdness(best);
    }

    private static float[] toErosionWeirdness(Climate.ParameterPoint point) {
        float e = ((point.erosion().min() + point.erosion().max()) / 2.0F) / QUANTIZATION;
        float w = ((point.weirdness().min() + point.weirdness().max()) / 2.0F) / QUANTIZATION;
        return new float[] { e, w };
    }
}