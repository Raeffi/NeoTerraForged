package raccoonman.reterraforged.world.worldgen.cell.continent.island;

import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.continent.ContinentLerper2;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Populators;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
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
	private final float seaLevel;

	private final Domain warp;

	// Only blends Coast and Plains. The underwater slope is handled purely by geometric math now!
	private final CellPopulator terrainBlend;

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
		this.seaLevel = context.levels.waterY * context.levels.unit;

		int warpScale = Math.max(8, Math.round(this.maxRadius * 0.6F));
		this.warp = Domains.domainPerlin(seed.next(), warpScale, 2, this.maxRadius * 0.3F);
		this.radiusScale = 1.0F;

		Levels levels = context.levels;
		Seed islandSeed = seed.offset(473829);
		Noise ground = PresetNoiseData.getNoise(context.noiseLookup, PresetTerrainTypeNoise.GROUND);
		TerrainSettings.Terrain plainsSettings = context.preset.terrain().plains;
		float verticalScale = context.preset.terrain().general.globalVerticalScale;

		CellPopulator coastPopulator = Populators.makeCoast(levels);
		CellPopulator landPopulator = Populators.makePlains(islandSeed, ground, plainsSettings, verticalScale);

		this.terrainBlend = new ContinentLerper2(
				coastPopulator,
				landPopulator,
				this.controlPoints.coast,
				this.controlPoints.inland
		);
	}

	public static boolean isIsland(Cell cell) {
		return cell.continentX >= ISLAND_MARKER && cell.continentZ >= ISLAND_MARKER;
	}

	@Override
	public void apply(Cell cell, float x, float z) {
		// Allow the mainland generator to establish the baseline deep ocean floor
		this.delegate.apply(cell, x, z);
		float baseHeight = cell.height;
		float baseEdge = cell.continentEdge;
		Terrain baseTerrain = cell.terrain;

		if (!this.enabled || baseEdge >= this.controlPoints.shallowOcean) {
			return;
		}

		IslandSample sample = this.sample(x, z);
		if (sample == null || sample.alpha <= 0.0F) {
			return;
		}

		// Map the physical footprint cleanly to our biome edges
		float islandEdge = baseEdge + (1.0F - baseEdge) * sample.alpha;
		cell.continentEdge = islandEdge;

		// Calculate what the above-water land shape looks like at this edge
		this.terrainBlend.apply(cell, x, z);
		float landHeight = cell.height;
		Terrain landTerrain = cell.terrain;

		float finalHeight;
		Terrain finalTerrain;

		if (islandEdge >= this.controlPoints.coast) {
			// Above water (Beach & Inland)
			finalHeight = landHeight;
			finalTerrain = landTerrain;
		} else {
			// Underwater slope: bridge the ocean floor to the perfectly flat beach
			float range = this.controlPoints.coast - baseEdge;
			float t = (islandEdge - baseEdge) / Math.max(1.0E-5F, range);
			t = NoiseUtil.clamp(t, 0.0F, 1.0F);

			// Smoothstep to ensure zero-derivative at both the sea floor and the beach (no kinks!)
			t = t * t * (3.0F - 2.0F * t);

			finalHeight = NoiseUtil.lerp(baseHeight, landHeight, t);
			finalTerrain = baseTerrain; // Keep ocean terrain decor underwater
		}

		cell.height = finalHeight;
		cell.terrain = cell.height >= this.seaLevel ? landTerrain : finalTerrain;

		cell.continentId = this.roll(4, sample.gridX, sample.gridZ);
		cell.continentX = ISLAND_MARKER + sample.gridX;
		cell.continentZ = ISLAND_MARKER + sample.gridZ;
		if (sample.mushroom && cell.continentEdge >= this.controlPoints.coast) {
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
		return sample != null ? base + (1.0F - base) * sample.alpha : base;
	}

	@Override
	public float getLandValue(float x, float z) {
		float base = this.delegate.getLandValue(x, z);
		if (!this.enabled || base >= this.controlPoints.shallowOcean) {
			return base;
		}
		IslandSample sample = this.sample(x, z);
		return sample != null ? base + (1.0F - base) * sample.alpha : base;
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

		float maxAlpha = 0.0F;
		boolean isMushroom = false;
		int bestGridX = xr;
		int bestGridZ = zr;
		boolean found = false;

		// Search radius widened to account for the massive gentle slopes
		int searchRadius = Math.max(2, (int) Math.ceil(this.maxRadius * 5.0F * this.frequency));

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

				// Hard exclusion check: No more flattened "pancakes" near the mainland
				float bufferStart = this.controlPoints.shallowOcean - this.continentBuffer;
				if (continentEdgeAtCenter >= bufferStart) {
					continue;
				}

				// 4.5F multiplier drastically stretches the footprint, making slopes 4-5x less steep
				float baseRadius = NoiseUtil.lerp(this.minRadius, this.maxRadius, this.roll(2, cx, cz)) * this.radiusScale;
				float totalRadius = baseRadius * 4.5F;

				float dist = NoiseUtil.sqrt(NoiseUtil.dist2(worldX, worldZ, wx, wz));
				if (dist >= totalRadius) {
					continue;
				}

				float normDist = dist / totalRadius;
				float alpha = 1.0F - normDist;

				// Base presence curve
				alpha = alpha * alpha * (3.0F - 2.0F * alpha);

				if (alpha > maxAlpha) {
					maxAlpha = alpha;
					bestGridX = cx;
					bestGridZ = cz;
					isMushroom = this.roll(3, cx, cz) < this.rareBiomeChance;
					found = true;
				}
			}
		}

		if (!found || maxAlpha <= 0.0F) {
			return null;
		}

		return new IslandSample(maxAlpha, isMushroom, bestGridX, bestGridZ);
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