package raccoonman.reterraforged.world.worldgen.cell.continent.island;

import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domains;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class IslandContinent implements Continent {
	private static final int ISLAND_MARKER = 1 << 28;
	private static final Rivermap EMPTY_RIVERMAP = new Rivermap(0, 0, new Network[0], GenWarp.EMPTY);

	private final Continent delegate;
	private final boolean enabled;
	private final int seed;
	private final float frequency;
	private final float jitter;
	private float minRadius = 32.0f;
	private float maxRadius = 256.0f;
	private final WorldSettings.ControlPoints controlPoints;

	private final float chance;
	private final float rareBiomeChance;
	private final float continentBuffer;
	private final float radiusScale;

	private final Domain warp;
	private final Noise shapeNoise;

	public IslandContinent(Continent delegate, Seed seed, GeneratorContext context) {
		this.delegate = delegate;
		WorldSettings.Islands settings = context.preset.world().islands;
		this.enabled = settings.enabled;
		this.controlPoints = context.preset.world().controlPoints;
		this.frequency = 1.0F / Math.max(1, settings.spacing);
		this.jitter = settings.jitter;
		this.minRadius = Math.max(8.0F, settings.minRadius);

		float coastWeight = NoiseUtil.clamp(this.controlPoints.islandCoast, 0.0F, 1.0F);
		float oceanWeight = NoiseUtil.clamp(this.controlPoints.deepOcean, 0.0F, 1.0F);
		float deepOceanWeight = Math.max(0.001F, oceanWeight);
		float coastNormalized = NoiseUtil.clamp(coastWeight / deepOceanWeight, 0.0F, 1.0F);
		float radiusRange = 256.0F - this.minRadius;

		this.maxRadius = this.minRadius + radiusRange * coastNormalized;
		this.chance = NoiseUtil.clamp(settings.chance, 0.0F, 1.0F);
		this.rareBiomeChance = NoiseUtil.clamp(settings.rareBiomeChance, 0.0F, 1.0F);
		this.continentBuffer = Math.max(0.0F, settings.continentBuffer);
		this.seed = seed.next();

		// SHAPE UPGRADE: Massively increased warp scale (2.5x) and magnitude (4.0x)
		// to aggressively distort the underlying coordinate grid, breaking the circular macro-shape.
		int warpScale = Math.max(8, Math.round(this.maxRadius * 2.5F));
		this.warp = Domains.domainPerlin(seed.next(), warpScale, 2, this.maxRadius * 4.0F);

		this.shapeNoise = PresetNoiseData.getNoise(context.noiseLookup, PresetTerrainTypeNoise.GROUND);
		this.radiusScale = 1.0F;
	}

	public static boolean isIsland(Cell cell) {
		return cell.continentX >= ISLAND_MARKER && cell.continentZ >= ISLAND_MARKER;
	}

	private float alphaToEdge(float baseEdge, float alpha) {
		float shallow = this.controlPoints.shallowOcean;
		float coast = this.controlPoints.coast;
		float inland = this.controlPoints.inland;

		// 1. UNDERWATER SLOPE (0% to 30%): Deep to Shallow
		// Stretches the seabed climb over a massive distance, making it very gentle.
		if (alpha < 0.3F) {
			float t = alpha / 0.3F;
			return NoiseUtil.lerp(baseEdge, shallow, t);
		}
		// 2. THE COAST BAND (30% to 70%): Shallow to Coast
		// This forces a massive, artificially flattened plateau around sea level.
		// It guarantees wide, smooth sandy bays before the terrain is allowed to climb into cliffs.
		else if (alpha < 0.7F) {
			float t = (alpha - 0.3F) / 0.4F;
			t = t * t * (3.0F - 2.0F * t); // Smoothstep for extra flatness
			return NoiseUtil.lerp(shallow, coast, t);
		}
		// 3. INLAND CLIMB (70% to 100%): Coast to Inland
		// Only the very center of the island is allowed to reach inland height.
		else {
			float t = (alpha - 0.7F) / 0.3F;
			t = t * t * (3.0F - 2.0F * t);
			return NoiseUtil.lerp(coast, inland, t);
		}
	}

	@Override
	public void apply(Cell cell, float x, float z) {
		this.delegate.apply(cell, x, z);

		if (!this.enabled || cell.continentEdge >= this.controlPoints.shallowOcean) {
			return;
		}

		IslandSample sample = this.sample(x, z);
		if (sample == null || sample.alpha <= 0.0F) {
			return;
		}

		float islandEdge = this.alphaToEdge(cell.continentEdge, sample.alpha);
		cell.continentEdge = islandEdge;

		cell.continentId = this.roll(4, sample.gridX, sample.gridZ);
		cell.continentX = sample.gridX - ISLAND_MARKER;
		cell.continentZ = sample.gridZ - ISLAND_MARKER;

		if (sample.mushroom && islandEdge >= this.controlPoints.coast) {
			cell.mushroomIsland = true;
		}
	}

	@Override
	public float getEdgeValue(float x, float z) {
		float base = this.delegate.getEdgeValue(x, z);
		if (!this.enabled || base >= this.controlPoints.shallowOcean) {
			return base;
		}
		IslandSample sample = this.sample(x, z);
		if (sample == null || sample.alpha <= 0.0F) {
			return base;
		}
		return this.alphaToEdge(base, sample.alpha);
	}

	@Override
	public float getLandValue(float x, float z) {
		float base = this.delegate.getLandValue(x, z);
		if (!this.enabled || base >= this.controlPoints.shallowOcean) {
			return base;
		}
		IslandSample sample = this.sample(x, z);
		if (sample == null || sample.alpha <= 0.0F) {
			return base;
		}
		return this.alphaToEdge(base, sample.alpha);
	}

	@Override
	public long getNearestCenter(float x, float z) {
		return this.delegate.getNearestCenter(x, z);
	}

	@Override
	public Rivermap getRivermap(int x, int z) {
		if (x >= ISLAND_MARKER && z >= ISLAND_MARKER) {
			return EMPTY_RIVERMAP;
		}
		return this.delegate.getRivermap(x, z);
	}

	private IslandSample sample(float x, float z) {
		float wx = this.warp.getX(x, z, 0);
		float wz = this.warp.getZ(x, z, 0);
		float px = wx * this.frequency;
		float pz = wz * this.frequency;
		int xr = NoiseUtil.floor(px);
		int zr = NoiseUtil.floor(pz);

		float totalAlpha = 0.0F;
		float maxSingleAlpha = 0.0F;

		boolean isMushroom = false;
		int bestGridX = xr;
		int bestGridZ = zr;
		boolean found = false;

		// Maintained large search radius to account for the gentler, wider island spans
		int searchRadius = Math.max(3, (int) Math.ceil(this.maxRadius * 9.0F * this.frequency));

		for (int dz = -searchRadius; dz <= searchRadius; ++dz) {
			for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
				int cx = xr + dx;
				int cz = zr + dz;

				if (this.roll(1, cx, cz) > this.chance) {
					continue;
				}

				Vec2f cell = NoiseUtil.cell(this.seed, cx, cz);
				float cxf = cx + cell.x() * this.jitter;
				float czf = cz + cell.y() * this.jitter;

				float worldX = cxf / this.frequency;
				float worldZ = czf / this.frequency;

				float continentEdgeAtCenter = this.delegate.getEdgeValue(worldX, worldZ);
				float bufferStart = this.controlPoints.shallowOcean - this.continentBuffer;
				if (continentEdgeAtCenter >= bufferStart) {
					continue;
				}

				float baseRadius = NoiseUtil.lerp(this.minRadius, this.maxRadius, this.roll(2, cx, cz)) * this.radiusScale;

				// BAY SMOOTHING FIX: Lowered frequencies and removed the 3rd micro-octave entirely
				// to eliminate the weird pointy spikes and enforce wide, sweeping bays.
				float nx = wx * 0.0015F;
				float nz = wz * 0.0015F;

				float nVal0 = this.shapeNoise.compute(nx, nz, this.seed + 99);        // Macro (Base Structure)
				float nVal1 = this.shapeNoise.compute(nx * 2.5F, nz * 2.5F, this.seed + 100); // Mid (Bays/Inlets)

				float nMin = this.shapeNoise.minValue();
				float nMax = this.shapeNoise.maxValue();
				float range = nMax - nMin;

				float norm0 = range != 0 ? (nVal0 - nMin) / range : 0.5F;
				float norm1 = range != 0 ? (nVal1 - nMin) / range : 0.5F;

				float combinedNoise = (norm0 * 0.70F) + (norm1 * 0.30F);
				float shapeModifier = 0.5F + (combinedNoise * 1.5F);

				float totalRadius = baseRadius * 6.5F * shapeModifier;

				float dist = NoiseUtil.sqrt(NoiseUtil.dist2(worldX, worldZ, wx, wz));
				if (dist >= totalRadius) {
					continue;
				}

				float normDist = dist / totalRadius;
				float alpha = 1.0F - normDist;

				totalAlpha += alpha;

				if (alpha > maxSingleAlpha) {
					maxSingleAlpha = alpha;
					bestGridX = cx;
					bestGridZ = cz;
					isMushroom = this.roll(3, cx, cz) < this.rareBiomeChance;
					found = true;
				}
			}
		}

		if (!found || totalAlpha <= 0.0F) {
			return null;
		}

		totalAlpha = Math.min(1.0F, totalAlpha);

		return new IslandSample(totalAlpha, isMushroom, bestGridX, bestGridZ);
	}

	private float roll(int offset, int gridX, int gridZ) {
		float value = NoiseUtil.valCoord2D(this.seed + offset, gridX, gridZ);
		return NoiseUtil.map(value, -1.0F, 1.0F, 2.0F);
	}

	private static final class IslandSample {
		final float alpha;
		final boolean mushroom;
		final int gridX;
		final int gridZ;
		IslandSample(float alpha, boolean mushroom, int gridX, int gridZ) {
			this.alpha = alpha;
			this.mushroom = mushroom;
			this.gridX = gridX;
			this.gridZ = gridZ;
		}
	}
}