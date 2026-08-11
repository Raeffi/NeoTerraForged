package raccoonman.reterraforged.world.worldgen.cell.continent.island;

import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.continent.ContinentLerper3;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Populators;
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

		int warpScale = Math.max(8, Math.round(this.maxRadius * 0.6F));
		this.warp = Domains.domainPerlin(seed.next(), warpScale, 2, this.maxRadius * 0.3F);
		this.shapeNoise = PresetNoiseData.getNoise(context.noiseLookup, PresetTerrainTypeNoise.GROUND);
		this.radiusScale = 1.0F;

		Levels levels = context.levels;
		Seed islandSeed = seed.offset(473829);
		Noise ground = PresetNoiseData.getNoise(context.noiseLookup, PresetTerrainTypeNoise.GROUND);
		TerrainSettings.Terrain plainsSettings = context.preset.terrain().plains;
		float verticalScale = context.preset.terrain().general.globalVerticalScale;

		CellPopulator seaPopulator = (seaCell, seaX, seaZ) -> {};
		CellPopulator coastPopulator = Populators.makeCoast(levels);
		CellPopulator landPopulator = Populators.makePlains(islandSeed, ground, plainsSettings, verticalScale);

		this.terrainBlend = new ContinentLerper3(
				seaPopulator,
				coastPopulator,
				landPopulator,
				this.controlPoints.shallowOcean,
				this.controlPoints.coast,
				this.controlPoints.inland
		);
	}

	public static boolean isIsland(Cell cell) {
		return cell.continentX >= ISLAND_MARKER && cell.continentZ >= ISLAND_MARKER;
	}

	private float alphaToEdge(float alpha) {
		float curved = NoiseUtil.curve(alpha, 0.6F, 3.0F);
		return NoiseUtil.lerp(this.controlPoints.shallowOcean, 1.0F, curved);
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

		float baseEdge = cell.continentEdge;
		float islandEdge = this.alphaToEdge(sample.alpha);

		// 1. Smoothly blend the edge values natively to avoid sharp cutoffs.
		// By blending the edge (instead of the height directly), terrainBlend
		// accurately assigns the COAST biome without dunking beaches underwater.
		float blendMargin = 0.15F; // Maps to approx 10-30 blocks width
		if (sample.alpha < blendMargin) {
			float t = sample.alpha / blendMargin;
			t = t * t * (3.0F - 2.0F * t); // Smoothstep curve
			islandEdge = NoiseUtil.lerp(baseEdge, islandEdge, t);
		} else {
			islandEdge = Math.max(baseEdge, islandEdge);
		}

		cell.continentEdge = islandEdge;

		// 2. Let the mainland lerper assign COAST terrain using the smoothed edge
		this.terrainBlend.apply(cell, x, z);

		// 3. Keep unique island shapes/biomes...
		cell.continentId = this.roll(4, sample.gridX, sample.gridZ);

		// 4. FIX FOR BEACHES: Purposefully assign coordinates well below ISLAND_MARKER.
		// This forces isIsland() to evaluate to 'false' everywhere, successfully tricking
		// TerraForged into allowing mainland sandy beach biomes around the islands just
		// like in the originally "bugged" quadrants.
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

		float islandEdge = this.alphaToEdge(sample.alpha);
		float blendMargin = 0.15F;

		// 5. Must strictly mirror the apply() blending here so chunks match flawlessly
		if (sample.alpha < blendMargin) {
			float t = sample.alpha / blendMargin;
			t = t * t * (3.0F - 2.0F * t);
			return NoiseUtil.lerp(base, islandEdge, t);
		}
		return Math.max(base, islandEdge);
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

		float islandEdge = this.alphaToEdge(sample.alpha);
		float blendMargin = 0.15F;

		// 5. Must strictly mirror the apply() blending here so chunks match flawlessly
		if (sample.alpha < blendMargin) {
			float t = sample.alpha / blendMargin;
			t = t * t * (3.0F - 2.0F * t);
			return NoiseUtil.lerp(base, islandEdge, t);
		}
		return Math.max(base, islandEdge);
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
				float bufferStart = this.controlPoints.shallowOcean - this.continentBuffer;
				if (continentEdgeAtCenter >= bufferStart) {
					continue;
				}

				float baseRadius = NoiseUtil.lerp(this.minRadius, this.maxRadius, this.roll(2, cx, cz)) * this.radiusScale;

				float nVal = this.shapeNoise.compute(wx * 0.02F, wz * 0.02F, this.seed + 100);
				float nMin = this.shapeNoise.minValue();
				float nMax = this.shapeNoise.maxValue();
				float normNoise = (nMax != nMin) ? (nVal - nMin) / (nMax - nMin) : 0.5F;

				float shapeModifier = 0.65F + (normNoise * 0.70F);
				float totalRadius = baseRadius * 4.5F * shapeModifier;

				float dist = NoiseUtil.sqrt(NoiseUtil.dist2(worldX, worldZ, wx, wz));
				if (dist >= totalRadius) {
					continue;
				}

				float normDist = dist / totalRadius;
				float alpha = 1.0F - normDist;

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