package raccoonman.reterraforged.world.worldgen.biome;

import java.util.ArrayList;
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
            Climate.ParameterPoint point = stonyShorePoints.get(0);
            return toErosionWeirdness(point);
        }

        if (beachPoints.isEmpty()) {
            return null;
        }

        long bestDist = Long.MAX_VALUE;
        for (Climate.ParameterPoint point : beachPoints) {
            long dist = point.fitness(Climate.target(
                    temperature, humidity, continentalness, erosion, 0, 0
            ));
            if (dist < bestDist) {
                bestDist = dist;
            }
        }

        // gather every point within a tolerance band of the best match
        List<Climate.ParameterPoint> candidates = new ArrayList<>();
        long tolerance = bestDist + (bestDist / 4) + 1000; // ~25% tolerance, tune as needed
        for (Climate.ParameterPoint point : beachPoints) {
            long dist = point.fitness(Climate.target(
                    temperature, humidity, continentalness, erosion, 0, 0
            ));
            if (dist <= tolerance) {
                candidates.add(point);
            }
        }

        Climate.ParameterPoint chosen = candidates.get(
                new java.util.Random(randomSeed).nextInt(candidates.size())
        );
        return toErosionWeirdness(chosen);
    }

    private static float[] toErosionWeirdness(Climate.ParameterPoint point) {
        float e = ((point.erosion().min() + point.erosion().max()) / 2.0F) / QUANTIZATION;
        float w = ((point.weirdness().min() + point.weirdness().max()) / 2.0F) / QUANTIZATION;
        return new float[] { e, w };
    }
}