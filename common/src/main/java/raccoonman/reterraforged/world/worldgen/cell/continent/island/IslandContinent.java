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
	private final Domain detailWarp;
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

		int warpScale = Math.max(8, Math.round(this.maxRadius * 2.5F));
		this.warp = Domains.domainPerlin(seed.next(), warpScale, 2, this.maxRadius * 4.0F);
		this.detailWarp = Domains.domainPerlin(seed.next(), 24, 2, 18.0F);
		this.shapeNoise = PresetNoiseData.getNoise(context.noiseLookup, PresetTerrainTypeNoise.GROUND);
		this.radiusScale = 1.0F;
	}

	public static boolean isIsland(Cell cell) {
		return Math.abs(cell.continentX) >= ISLAND_MARKER && Math.abs(cell.continentZ) >= ISLAND_MARKER;
	}

	private float alphaToEdge(float baseEdge, float alpha, float steepness) {
		float shallow = this.controlPoints.shallowOcean;
		float coast = this.controlPoints.coast;
		float inland = this.controlPoints.inland;

		// Smooth proportional horizontal zones (alpha from 0.0 to 1.0)
		float shallowEnd = 0.35F;
		float coastEnd = 0.60F;

		// Compress the beach transition where steepness is high to form cliffs
		float actualCoastEnd = NoiseUtil.lerp(coastEnd, shallowEnd + 0.05F, NoiseUtil.clamp(steepness, 0.0F, 1.0F));

		if (alpha < shallowEnd) {
			float t = shallowEnd > 0.0F ? NoiseUtil.clamp(alpha / shallowEnd, 0.0F, 1.0F) : 1.0F;
			t = t * t * (3.0F - 2.0F * t);
			return NoiseUtil.lerp(baseEdge, shallow, t);
		}
		else if (alpha < actualCoastEnd) {
			float width = actualCoastEnd - shallowEnd;
			float t = width > 0.0F ? NoiseUtil.clamp((alpha - shallowEnd) / width, 0.0F, 1.0F) : 1.0F;
			t = t * t * (3.0F - 2.0F * t);
			return NoiseUtil.lerp(shallow, coast, t);
		}
		else {
			float width = 1.0F - actualCoastEnd;
			float t = width > 0.0F ? NoiseUtil.clamp((alpha - actualCoastEnd) / width, 0.0F, 1.0F) : 1.0F;
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

		float bufferMul = this.pointBufferMultiplier(cell.continentEdge);
		if (bufferMul <= 0.0F) {
			return;
		}

		float islandEdge = this.alphaToEdge(cell.continentEdge, sample.alpha, sample.steepness);
		cell.continentEdge = NoiseUtil.lerp(cell.continentEdge, islandEdge, bufferMul);
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
		float bufferMul = this.pointBufferMultiplier(base);
		if (bufferMul <= 0.0F) {
			return base;
		}
		float islandEdge = this.alphaToEdge(base, sample.alpha, sample.steepness);
		return NoiseUtil.lerp(base, islandEdge, bufferMul);
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
		float bufferMul = this.pointBufferMultiplier(base);
		if (bufferMul <= 0.0F) {
			return base;
		}
		float islandEdge = this.alphaToEdge(base, sample.alpha, sample.steepness);
		return NoiseUtil.lerp(base, islandEdge, bufferMul);
	}

	@Override
	public long getNearestCenter(float x, float z) {
		return this.delegate.getNearestCenter(x, z);
	}

	@Override
	public Rivermap getRivermap(int x, int z) {
		if (Math.abs(x) >= ISLAND_MARKER && Math.abs(z) >= ISLAND_MARKER) {
			return EMPTY_RIVERMAP;
		}
		return this.delegate.getRivermap(x, z);
	}

	private IslandSample sample(float x, float z) {
		float wx = this.warp.getX(x, z, 0);
		float wz = this.warp.getZ(x, z, 0);
		float dwx = this.detailWarp.getX(wx, wz, 0);
		float dwz = this.detailWarp.getZ(wx, wz, 0);
		float px = wx * this.frequency;
		float pz = wz * this.frequency;
		int xr = NoiseUtil.floor(px);
		int zr = NoiseUtil.floor(pz);

		float maxSingleAlpha = 0.0F;
		float bestSteepness = 0.0F;
		boolean isMushroom = false;
		int bestGridX = xr;
		int bestGridZ = zr;
		boolean found = false;

		float maxPossibleRadius = (this.maxRadius * 6.5F * 2.5F) + (this.maxRadius * 4.0F);
		int searchRadius = Math.max(6, (int) Math.ceil(maxPossibleRadius * this.frequency) + 2);

		for (int dz = -searchRadius; dz <= searchRadius; ++dz) {
			for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
				int cx = xr + dx;
				int cz = zr + dz;

				if (this.roll(1, cx, cz) > this.chance) continue;

				Vec2f cell = NoiseUtil.cell(this.seed, cx, cz);
				float cxf = cx + cell.x() * this.jitter;
				float czf = cz + cell.y() * this.jitter;
				float worldX = cxf / this.frequency;
				float worldZ = czf / this.frequency;

				float continentEdgeAtCenter = this.delegate.getEdgeValue(worldX, worldZ);
				float bufferStart = this.controlPoints.shallowOcean - this.continentBuffer;
				float bufferEnd = this.controlPoints.shallowOcean;
				if (continentEdgeAtCenter >= bufferEnd) continue;

				float bufferMul = 1.0F;
				if (this.continentBuffer > 0.0F && continentEdgeAtCenter > bufferStart) {
					float t = NoiseUtil.clamp((continentEdgeAtCenter - bufferStart) / this.continentBuffer, 0.0F, 1.0F);
					t = t * t * (3.0F - 2.0F * t);
					bufferMul = 1.0F - t;
				}

				float baseRadius = NoiseUtil.lerp(this.minRadius, this.maxRadius, this.roll(2, cx, cz)) * this.radiusScale;
				float nx = dwx * 0.0015F;
				float nz = dwz * 0.0015F;

				float nVal0 = this.shapeNoise.compute(nx, nz, this.seed + 99);
				float nVal1 = this.shapeNoise.compute(nx * 2.5F, nz * 2.5F, this.seed + 100);
				float nVal2 = this.shapeNoise.compute(nx * 6.0F, nz * 6.0F, this.seed + 101);
				float nVal3 = this.shapeNoise.compute(dwx * 0.030F, dwz * 0.030F, this.seed + 102);
				float nValRipple = this.shapeNoise.compute(dwx * 0.0833F, dwz * 0.0833F, this.seed + 103);
				float nValMod = this.shapeNoise.compute(nx * 4.0F, nz * 4.0F, this.seed + 104);

				float nMin = this.shapeNoise.minValue();
				float nMax = this.shapeNoise.maxValue();
				float range = nMax - nMin;

				float norm0 = range != 0 ? (nVal0 - nMin) / range : 0.5F;
				float norm1 = range != 0 ? (nVal1 - nMin) / range : 0.5F;
				float norm2 = range != 0 ? (nVal2 - nMin) / range : 0.5F;
				float norm3 = range != 0 ? (nVal3 - nMin) / range : 0.5F;
				float normRipple = range != 0 ? (nValRipple - nMin) / range : 0.5F;
				float normMod = range != 0 ? (nValMod - nMin) / range : 0.5F;

				float steepness = NoiseUtil.clamp((norm0 * 0.60F + norm1 * 0.40F), 0.0F, 1.0F);
				float coastalWeight = 0.05F + (steepness * 0.17F);
				float combinedNoise = (norm0 * 0.55F) + (norm1 * 0.35F) + ((norm2 - 0.5F) * coastalWeight);

				float totalRadius = baseRadius * 6.5F * (0.5F + (NoiseUtil.clamp(combinedNoise, 0.0F, 1.0F) * 1.5F));
				float dist = NoiseUtil.sqrt(NoiseUtil.dist2(worldX, worldZ, dwx, dwz));
				if (dist >= totalRadius) continue;

				float angle = (float) Math.atan2(worldX - dwx, worldZ - dwz);
				float lobePhase = this.roll(5, cx, cz) * 6.2831855F;
				int lobeCount = 2 + (int) (this.roll(6, cx, cz) * 3.0F);
				float lobe = 0.72F + 0.28F * NoiseUtil.cos(angle * lobeCount + lobePhase);

				float outlineNoise = this.shapeNoise.compute(
						dwx * 0.06F + NoiseUtil.cos(angle) * 8.0F,
						dwz * 0.06F + NoiseUtil.sin(angle) * 8.0F,
						this.seed + 106
				);
				float outlineNorm = range != 0 ? (outlineNoise - nMin) / range : 0.5F;
				float outlineDistort = 1.0F + (outlineNorm - 0.5F) * 0.28F;

				float lobedRadius = totalRadius * lobe * outlineDistort;
				float transitionWidth = Math.max(24.0F, this.minRadius * 0.5F);
				float baseAlpha = NoiseUtil.clamp((lobedRadius - dist) / transitionWidth, 0.0F, 1.0F);
				baseAlpha = baseAlpha * baseAlpha * (3.0F - 2.0F * baseAlpha);
				float alpha = NoiseUtil.clamp(baseAlpha + ((norm3 - 0.5F) * 0.08F * baseAlpha) + ((normRipple - 0.5F) * (0.015F + (normMod * 0.09F)) * baseAlpha), 0.0F, 1.0F);
				alpha *= bufferMul;

				if (alpha > maxSingleAlpha) {
					maxSingleAlpha = alpha;
					bestSteepness = steepness;
					bestGridX = cx;
					bestGridZ = cz;
					isMushroom = this.roll(3, cx, cz) < this.rareBiomeChance;
					found = true;
				}
			}
		}

		return !found || maxSingleAlpha <= 0.0F ? null : new IslandSample(Math.min(1.0F, maxSingleAlpha), bestSteepness, isMushroom, bestGridX, bestGridZ);
	}

	private float roll(int offset, int gridX, int gridZ) {
		return NoiseUtil.map(NoiseUtil.valCoord2D(this.seed + offset, gridX, gridZ), -1.0F, 1.0F, 2.0F);
	}

	private float pointBufferMultiplier(float pointContinentEdge) {
		if (this.continentBuffer <= 0.0F) {
			return 1.0F;
		}
		float bufferStart = this.controlPoints.shallowOcean - this.continentBuffer;
		if (pointContinentEdge <= bufferStart) {
			return 1.0F;
		}
		float t = NoiseUtil.clamp((pointContinentEdge - bufferStart) / this.continentBuffer, 0.0F, 1.0F);
		t = t * t * (3.0F - 2.0F * t);
		return 1.0F - t;
	}

	private static final class IslandSample {
		final float alpha, steepness;
		final boolean mushroom;
		final int gridX, gridZ;
		IslandSample(float alpha, float steepness, boolean mushroom, int gridX, int gridZ) {
			this.alpha = alpha; this.steepness = steepness; this.mushroom = mushroom; this.gridX = gridX; this.gridZ = gridZ;
		}
	}
}