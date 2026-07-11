package raccoonman.reterraforged.world.worldgen.biome;

import java.util.List;

import net.minecraft.world.level.biome.Climate;

public class BeachParameterCache {
    private static List<Climate.ParameterPoint> beachPoints = List.of();
    private static List<Climate.ParameterPoint> stonyShorePoints = List.of();

    public static void set(List<Climate.ParameterPoint> beach, List<Climate.ParameterPoint> stonyShore) {
        beachPoints = List.copyOf(beach);
        stonyShorePoints = List.copyOf(stonyShore);
    }

    public static List<Climate.ParameterPoint> get() {
        return beachPoints;
    }

    private static final float QUANTIZATION = 10000.0F;

    // finds the closest cached beach parameter point to the given
    // temperature/humidity, returns its erosion/weirdness midpoints
    public static float[] findClosestErosionWeirdness(float temperature, float humidity) {
        if (beachPoints.isEmpty()) {
            return null;
        }
        Climate.ParameterPoint best = null;
        long bestDist = Long.MAX_VALUE;
        for (Climate.ParameterPoint point : beachPoints) {
            long dist = point.fitness(Climate.target(
                    temperature,
                    humidity,
                    point.continentalness().min(),
                    point.erosion().min(),
                    0,
                    point.weirdness().min()
            ));
            if (dist < bestDist) {
                bestDist = dist;
                best = point;
            }
        }
        if (best == null) {
            return null;
        }

        float erosion = ((best.erosion().min() + best.erosion().max()) / 2.0F) / QUANTIZATION;
        float weirdness = ((best.weirdness().min() + best.weirdness().max()) / 2.0F) / QUANTIZATION;

        return new float[] { erosion, weirdness };
    }
}