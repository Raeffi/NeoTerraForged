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
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domains;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.util.Seed;

/**
 * Wraps an existing {@link Continent} and scatters small islands into the
 * open ocean the wrapped continent reports.
 *
 * Continents always take priority: an island is only ever considered for a
 * cell where the mainland itself still reports open ocean (below
 * {@code shallowOcean}), and an island's entire footprint is discarded up
 * front (at the grid-cell level) if its centre falls within
 * {@code continentBuffer} of the mainland coastline. Wherever an island is
 * placed it fully overwrites the cell using its own terrain populator chain;
 * wherever it isn't, the mainland's own result passes through untouched.
 *
 * {@link raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap}
 * must skip its own outer ocean/land populator for cells flagged as islands
 * (see {@link #isIsland(Cell)}), otherwise the mainland's own control points
 * and full terrain chain would immediately overwrite what this class sets.
 */
public class IslandContinent implements Continent {
	// Any continentX/continentZ at or above this is island space, never a real continent coordinate.
	private static final int ISLAND_MARKER = 1 << 28;
	private static final Rivermap EMPTY_RIVERMAP = new Rivermap(0, 0, new Network[0], GenWarp.EMPTY);

	private final Continent delegate;
	private final boolean enabled;
	private final int seed;
	private final float frequency;
	private final float jitter;
	private float minRadius = 8.0f; // Increased from 8.0f
	private float maxRadius = 32.0f; // Increased from 8.0f
	private final WorldSettings.ControlPoints controlPoints;

	private float scaleMultiplier = 2.0f; // Added scaling factor
	private final float chance;
	private final float rareBiomeChance;
	private final float continentBuffer;
	private final float radiusScale;

	private final Domain warp;
	private final CellPopulator terrainBlend;
	private final float seaLevel; // Added sea level variable

	public IslandContinent(Continent delegate, Seed seed, GeneratorContext context) {
		this.delegate = delegate;
		WorldSettings.Islands settings = context.preset.world().islands;
		this.enabled = settings.enabled;
		this.controlPoints = context.preset.world().controlPoints;
		this.scaleMultiplier = context.preset.world().controlPoints.islandCoast*10.0F;
		this.frequency = 1.0F / Math.max(1, settings.spacing);
		this.jitter = settings.jitter;
		this.minRadius = Math.max(8.0F, settings.minRadius);
		// Compute scale based on islandCoast control point
		float coastWeight = NoiseUtil.clamp(this.controlPoints.islandCoast, 0.0F, 1.0F);
		this.maxRadius = Math.max(this.minRadius, this.minRadius * (coastWeight + 1.5F));
		this.chance = NoiseUtil.clamp(settings.chance, 0.0F, 1.0F);
		this.rareBiomeChance = NoiseUtil.clamp(settings.rareBiomeChance, 0.0F, 1.0F);
		this.continentBuffer = Math.max(0.0F, settings.continentBuffer);
		this.seed = seed.next();
		this.seaLevel = context.levels.waterY * context.levels.unit;

		// distorts the sample point before the radius test, so islands get irregular,
		// continent-like coastlines instead of perfect circles
		int warpScale = Math.max(8, Math.round(this.maxRadius * 0.6F));
		this.warp = Domains.domainPerlin(seed.next(), warpScale, 2, this.maxRadius * 0.3F);

		// islandInland now also controls physical island SIZE, the same way a continent's
		// own control points affect how much of its noise field reads as land: a larger
		// islandInland value grows the island's core and, via this scale, its whole footprint.
		// Adjust radiusScale based on islandInland and islandCoast
		float inlandFraction = NoiseUtil.clamp(this.controlPoints.islandInland, 0.0F, 1.0F);
		this.radiusScale = NoiseUtil.lerp(0.5F, 2.0F * this.scaleMultiplier, inlandFraction);

		// islandCoast/islandInland define a band along the island's own radius (as alpha
		// fractions, 0 = outer edge, 1 = centre) that marks where the coast populator gives
		// way to the inland populator - mirrors how the mainland's own coast/inland control
		// points define a band along its continentEdge field.
		float coastAlpha = NoiseUtil.clamp(this.controlPoints.islandCoast, 0.0F, 0.9F);
		float inlandAlpha = NoiseUtil.clamp(Math.max(inlandFraction, coastAlpha + 0.05F), coastAlpha + 0.05F, 1.0F);
		float blendLower = this.alphaToEdge(coastAlpha);
		float blendUpper = this.alphaToEdge(inlandAlpha);

		Levels levels = context.levels;
		Seed islandSeed = seed.offset(473829);
		Noise ground = PresetNoiseData.getNoise(context.noiseLookup, PresetTerrainTypeNoise.GROUND);
		TerrainSettings.Terrain plainsSettings = context.preset.terrain().plains;
		float verticalScale = context.preset.terrain().general.globalVerticalScale;
		CellPopulator coastPopulator = Populators.makeCoast(levels);
		CellPopulator landPopulator = Populators.makePlains(islandSeed, ground, plainsSettings, verticalScale);
		float seaLevel = levels.waterY * levels.unit;

		// Adjust blending bounds by subtracting sea level
		this.terrainBlend = new ContinentLerper2(
				coastPopulator,
				landPopulator,
				blendLower - seaLevel,
				blendUpper - seaLevel
		);
	}

	/**
	 * True if this cell was generated as part of an island rather than the mainland.
	 * {@code Heightmap} uses this to skip re-applying its own ocean/land populator,
	 * which would otherwise immediately overwrite what {@link #apply} sets here.
	 */
	public static boolean isIsland(Cell cell) {
		return cell.continentX >= ISLAND_MARKER && cell.continentZ >= ISLAND_MARKER;
	}

	@Override
	public void apply(Cell cell, float x, float z) {
		this.delegate.apply(cell, x, z);
		if (!this.enabled || cell.continentEdge >= this.controlPoints.shallowOcean) {
			return;
		}
		IslandSample sample = this.sample(x, z);
		if (sample == null) {
			return;
		}
		// Adjust edge by sea level
		cell.continentEdge = sample.edge - seaLevel;
		this.terrainBlend.apply(cell, x, z);
		cell.continentId = this.roll(4, sample.gridX, sample.gridZ);
		cell.continentX = ISLAND_MARKER + sample.gridX;
		cell.continentZ = ISLAND_MARKER + sample.gridZ;
		if (sample.mushroom && sample.edge >= this.controlPoints.coast) {
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
		return sample != null ? sample.edge - seaLevel : base; // Adjust edge by sea level
	}

	@Override
	public float getLandValue(float x, float z) {
		float base = this.delegate.getLandValue(x, z);
		if (!this.enabled || base >= this.controlPoints.shallowOcean) {
			return base;
		}
		IslandSample sample = this.sample(x, z);
		return sample != null ? sample.edge - seaLevel : base; // Adjust edge by sea level
	}

	@Override
	public long getNearestCenter(float x, float z) {
		// spawn search and similar callers should still resolve to a real continent, not a remote island
		return this.delegate.getNearestCenter(x, z);
	}

	@Override
	public Rivermap getRivermap(int x, int z) {
		if (x >= ISLAND_MARKER && z >= ISLAND_MARKER) {
			return EMPTY_RIVERMAP;
		}
		return this.delegate.getRivermap(x, z);
	}

	/**
	 * Worley/cellular search for the nearest island grid point, then works out
	 * whether that point actually holds an island, how big it is, and how far
	 * (x, z) is from its centre. Returns null wherever there is no island.
	 */
	private IslandSample sample(float x, float z) {
		float wx = this.warp.getX(x, z, 0);
		float wz = this.warp.getZ(x, z, 0);
		float px = wx * this.frequency;
		float pz = wz * this.frequency;
		int xr = NoiseUtil.floor(px);
		int zr = NoiseUtil.floor(pz);
		int gridX = xr;
		int gridZ = zr;
		float centerX = px;
		float centerZ = pz;
		float nearest = Float.MAX_VALUE;
		for (int dz = -1; dz <= 1; ++dz) {
			for (int dx = -1; dx <= 1; ++dx) {
				int cx = xr + dx;
				int cz = zr + dz;
				Vec2f offset = NoiseUtil.cell(this.seed, cx, cz);
				float cxf = cx + offset.x() * this.jitter;
				float czf = cz + offset.y() * this.jitter;
				float dist = NoiseUtil.dist2(cxf, czf, px, pz);
				if (dist < nearest) {
					nearest = dist;
					gridX = cx;
					gridZ = cz;
					centerX = cxf;
					centerZ = czf;
				}
			}
		}
		if (this.roll(1, gridX, gridZ) > this.chance) {
			// this grid point rolled "no island"
			return null;
		}
		float worldX = centerX / this.frequency;
		float worldZ = centerZ / this.frequency;
		// grid-cell level exclusion: reject island footprints too close to the
		// mainland before doing any per-block work
		float continentEdgeAtCenter = this.delegate.getEdgeValue(worldX, worldZ);
		if (continentEdgeAtCenter >= this.controlPoints.shallowOcean - this.continentBuffer) {
			return null;
		}
		float baseRadius = NoiseUtil.lerp(this.minRadius, this.maxRadius, this.roll(2, gridX, gridZ));
		float radius = baseRadius * this.radiusScale;
		float dist = NoiseUtil.sqrt(NoiseUtil.dist2(worldX, worldZ, wx, wz));
		if (dist >= radius) {
			// (x, z) is outside this island's warped footprint
			return null;
		}
		float alpha = 1.0F - dist / radius;
		float edge = this.alphaToEdge(alpha);
		boolean mushroom = this.roll(3, gridX, gridZ) < this.rareBiomeChance;
		return new IslandSample(edge, mushroom, gridX, gridZ);
	}

	/**
	 * Converts a radius fraction (1 = island centre, 0 = outer edge) into an
	 * edge value in the same units as {@code cell.continentEdge}, using the
	 * same curve shape used for the sample's own alpha->edge conversion, so
	 * the coast/inland thresholds computed in the constructor line up exactly
	 * with the values produced per-cell in {@link #sample}.
	 */
	private float alphaToEdge(float alpha) {
		float curved = NoiseUtil.curve(alpha, 0.6F, 3.0F);
		return NoiseUtil.lerp(this.controlPoints.shallowOcean, 1.0F, curved);
	}

	/**
	 * A deterministic pseudo-random value in [0, 1] for one island grid cell.
	 * Same pattern as {@code ContinentGenerator.cellIdentity}.
	 */
	private float roll(int offset, int gridX, int gridZ) {
		float value = NoiseUtil.valCoord2D(this.seed + offset, gridX, gridZ);
		return NoiseUtil.map(value, -1.0F, 1.0F, 2.0F);
	}

	private static final class IslandSample {
		final float edge;
		final boolean mushroom;
		final int gridX;
		final int gridZ;
		IslandSample(float edge, boolean mushroom, int gridX, int gridZ) {
			this.edge = edge;
			this.mushroom = mushroom;
			this.gridX = gridX;
			this.gridZ = gridZ;
		}
	}
}