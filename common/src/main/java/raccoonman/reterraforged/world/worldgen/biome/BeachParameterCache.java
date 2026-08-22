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

    public static float[] findClosestErosionWeirdness(float temperature, float humidity, float continentalness, float erosion, boolean steep, long randomSeed) {
        if (steep && !stonyShorePoints.isEmpty()) {
            return toErosionWeirdness(stonyShorePoints.get(0));
        }

        if (beachPoints.isEmpty()) {
            return null;
        }

        long bestDist = Long.MAX_VALUE;
        Climate.ParameterPoint bestPoint = beachPoints.get(0);

        // Deterministically find the absolute best match (No RNG)
        for (Climate.ParameterPoint point : beachPoints) {
            long dist = point.fitness(Climate.target(
                    temperature, humidity, continentalness, erosion, 0, 0
            ));
            if (dist < bestDist) {
                bestDist = dist;
                bestPoint = point;
            }
        }

        return toErosionWeirdness(bestPoint);
    }

    private static float[] toErosionWeirdness(Climate.ParameterPoint point) {
        float e = ((point.erosion().min() + point.erosion().max()) / 2.0F) / QUANTIZATION;
        float w = ((point.weirdness().min() + point.weirdness().max()) / 2.0F) / QUANTIZATION;
        return new float[] { e, w };
    }
}