package raccoonman.reterraforged.world.worldgen.cell.continent.island;

import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.gen.GenWarp;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.util.Seed;

/**
 * Wraps an existing {@link Continent} and adds small islands inside the
 * ocean areas that the wrapped continent already reports.
 * 
 * This works with every {@code ContinentType}, because it only reads and
 * writes {@code cell.continentEdge}. The normal erosion terrain populators
 * (in {@code Heightmap}) key off that same field, so islands get the same
 * quality terrain as the mainland automatically, with no separate terrain
 * code needed here.
 * 
 * Islands never carry rivers or lakes: each island cell is tagged with a
 * {@code continentX}/{@code continentZ} pair far outside any real world
 * coordinate, and {@link #getRivermap(int, int)} recognizes that range and
 * returns an empty river map for it.
 * 
 * A small, separately-rolled fraction of islands are flagged as "rare
 * biome" islands. For those, once the normal terrain populator has run,
 * {@code Heightmap.applyTerrain} stamps {@code cell.terrain} to
 * {@code TerrainType.MUSHROOM_FIELDS}, which is the signal
 * {@code CellSampler.Field.CONTINENT} already looks for to push the
 * continentalness value into the narrow band Mushroom Fields (and similar
 * extremely-isolated biomes) need.
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
	private final float minRadius;
	private final float maxRadius;
	private final float chance;
	private final float rareBiomeChance;
	private final WorldSettings.ControlPoints controlPoints;

	public IslandContinent(Continent delegate, Seed seed, GeneratorContext context) {
		this.delegate = delegate;

		WorldSettings.Islands settings = context.preset.world().islands;
		this.enabled = settings.enabled;
		this.frequency = 1.0F / Math.max(1, settings.spacing);
		this.jitter = settings.jitter;
		this.minRadius = Math.max(8.0F, settings.minRadius);
		this.maxRadius = Math.max(this.minRadius, settings.maxRadius);
		this.chance = NoiseUtil.clamp(settings.chance, 0.0F, 1.0F);
		this.rareBiomeChance = NoiseUtil.clamp(settings.rareBiomeChance, 0.0F, 1.0F);
		this.controlPoints = context.preset.world().controlPoints;
		this.seed = seed.next();
	}

	@Override
	public void apply(Cell cell, float x, float z) {
		this.delegate.apply(cell, x, z);
		if (!this.enabled || cell.continentEdge >= this.controlPoints.shallowOcean) {
			// already coast/inland according to the real continent, leave it alone
			return;
		}
		IslandSample sample = this.sample(x, z);
		if (sample == null) {
			return;
		}
		float edge = NoiseUtil.lerp(cell.continentEdge, sample.edge, this.continentFade(cell.continentEdge));
		if (edge <= cell.continentEdge) {
			return;
		}
		cell.continentEdge = edge;
		cell.continentId = this.roll(4, sample.gridX, sample.gridZ);
		cell.continentX = ISLAND_MARKER + sample.gridX;
		cell.continentZ = ISLAND_MARKER + sample.gridZ;
		if (sample.mushroom && edge >= this.controlPoints.coast) {
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
		if (sample == null) {
			return base;
		}
		return Math.max(base, NoiseUtil.lerp(base, sample.edge, this.continentFade(base)));
	}

	@Override
	public float getLandValue(float x, float z) {
		float base = this.delegate.getLandValue(x, z);
		if (!this.enabled || base >= this.controlPoints.shallowOcean) {
			return base;
		}
		IslandSample sample = this.sample(x, z);
		if (sample == null) {
			return base;
		}
		return Math.max(base, NoiseUtil.lerp(base, sample.edge, this.continentFade(base)));
	}

	/**
	 * 1.0 out in open ocean, tapering smoothly to 0.0 as the wrapped continent's
	 * own {@code continentEdge} climbs from "deep ocean" up towards "coast".
	 * Without this, an island could sit right up against a real coastline with
	 * a visible seam where its footprint stops.
	 */
	private float continentFade(float baseEdge) {
		float fadeStart = this.controlPoints.deepOcean;
		float fadeEnd = this.controlPoints.shallowOcean;
		if (baseEdge <= fadeStart) {
			return 1.0F;
		}
		if (baseEdge >= fadeEnd) {
			return 0.0F;
		}
		return 1.0F - (baseEdge - fadeStart) / (fadeEnd - fadeStart);
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
		float px = x * this.frequency;
		float pz = z * this.frequency;
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

		float radius = NoiseUtil.lerp(this.minRadius, this.maxRadius, this.roll(2, gridX, gridZ));

		float worldX = centerX / this.frequency;
		float worldZ = centerZ / this.frequency;
		float dist = NoiseUtil.sqrt(NoiseUtil.dist2(worldX, worldZ, x, z));
		if (dist >= radius) {
			// (x, z) is outside this island's footprint
			return null;
		}

		float alpha = 1.0F - dist / radius;
		alpha = NoiseUtil.curve(alpha, 0.6F, 3.0F);
		float edge = NoiseUtil.lerp(this.controlPoints.shallowOcean, 1.0F, alpha);

		boolean mushroom = this.roll(3, gridX, gridZ) < this.rareBiomeChance;

		return new IslandSample(edge, mushroom, gridX, gridZ);
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
