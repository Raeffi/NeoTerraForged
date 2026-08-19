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
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domains;
import raccoonman.reterraforged.world.worldgen.util.Seed;

/**
 * Wraps an existing {@link Continent} and scatters small islands into the
 * open ocean the wrapped continent reports.
 *
 * An island is only ever considered for a cell where the mainland itself
 * reports deep ocean, and an island's footprint is discarded up front (at
 * the grid-cell level) if its centre falls within {@code continentBuffer}
 * of the mainland coastline. This buffer check is the ONLY point where an
 * island's existence depends on the mainland - once an island is allowed to
 * exist, its shape is generated entirely on its own terms and never blends
 * against the mainland's own (possibly irregularly warped) edge value, so
 * there is no seam where an island's falloff band happens to meet a patch
 * of mainland ocean.
 *
 * An island only ever overrides {@code continentEdge} (plus continentX/Z
 * and mushroomIsland). The value it writes spans the SAME deepOcean -> 1.0
 * range the mainland uses, so the shared terrain populator in
 * {@link raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap}
 * gives islands a real shallow-ocean ring, a beach band, and the same range
 * of terrain (hills, mountains, and so on) as the active continent - islands
 * read as small versions of it. Islands never carry rivers or lakes, see
 * {@link #getRivermap(int, int)}.
 *
 * Beyond an island's core radius, its edge value blends smoothly toward a
 * flat open-ocean plateau over a soft falloff band, so there is no hard
 * step at the island boundary. Where two islands' radii (plus their falloff
 * bands) overlap, all candidate grid points near a sample position are
 * evaluated and the highest edge value wins, so overlapping islands merge
 * into one landmass instead of cutting off at a hard line.
 *
 * The island's outline itself is shaped by five octaves of position
 * distortion at different scales (see {@link #shapeWarp}), from a large,
 * strong pass that can split an island into separate lobes down to a small,
 * weak pass that roughens the coastline, so islands read as organic
 * branching shapes rather than circles.
 */
public class IslandContinent implements Continent {
	// Any continentX/continentZ at or above this is island space, never a real continent coordinate.
	private static final int ISLAND_MARKER = 1 << 28;
	private static final Rivermap EMPTY_RIVERMAP = new Rivermap(0, 0, new Network[0], GenWarp.EMPTY);
	// how far past an island's own radius the soft blend into open ocean continues, as a multiple of that radius
	private static final float FALLOFF_SCALE = 1.35F;
	// flat edge value islands blend toward past their falloff band - a plateau, not the mainland's own value
	private static final float OPEN_OCEAN_EDGE = 0.0F;

	private final Continent delegate;
	private final boolean enabled;
	private final int seed;
	private final float frequency;
	private final float jitter;
	private final float minRadius;
	private final float maxRadius;
	private final float chance;
	private final float rareBiomeChance;
	private final float continentBuffer;
	private final float inlandFraction;
	private final float oceanThreshold;
	private final WorldSettings.ControlPoints controlPoints;
	private final Domain warp;
	private final Domain shapeWarp;

	public IslandContinent(Continent delegate, Seed seed, GeneratorContext context) {
		this.delegate = delegate;
		WorldSettings.Islands settings = context.preset.world().islands;
		this.controlPoints = context.preset.world().controlPoints;
		this.enabled = settings.enabled;
		this.frequency = 1.0F / Math.max(1, settings.spacing);
		this.jitter = settings.jitter;
		this.chance = NoiseUtil.clamp(settings.chance, 0.0F, 1.0F);
		this.rareBiomeChance = NoiseUtil.clamp(settings.rareBiomeChance, 0.0F, 1.0F);
		this.continentBuffer = Math.max(0.0F, settings.continentBuffer);
		this.oceanThreshold = this.controlPoints.deepOcean;
		this.seed = seed.next();

		// islandCoast (0-1) sets overall island size. At 0, islands cap out at the
		// preset's configured maxRadius; at 1 they can grow until they'd start
		// touching a neighbouring grid cell's island (half the grid spacing)
		this.minRadius = Math.max(8.0F, settings.minRadius);
		float configuredMax = Math.max(this.minRadius, settings.maxRadius);
		float spacingCap = Math.max(configuredMax, settings.spacing * 0.5F);
		float sizeFraction = NoiseUtil.clamp(this.controlPoints.islandCoast, 0.0F, 1.0F);
		this.maxRadius = NoiseUtil.lerp(configuredMax, spacingCap, sizeFraction);

		// islandInland sets how much of that radius reads as inland core versus coastal fringe
		this.inlandFraction = NoiseUtil.clamp(this.controlPoints.islandInland, 0.0F, 1.0F);

		// macro warp: distorts the sample point before the grid search, so
		// islands scatter at irregular offsets from their grid points
		int warpScale = Math.max(8, Math.round(this.maxRadius * 0.6F));
		this.warp = Domains.domainPerlin(seed.next(), warpScale, 2, this.maxRadius * 0.3F);

		// shape warp: FIVE octaves of position distortion stacked together, from
		// an extra-large/strong pass (can split an island into separate lobes,
		// producing the branching multi-lobed shapes seen on real coastlines)
		// down to an extra-small/weak pass (fine coastline roughness). A single
		// octave only ever wobbles a circle; the range of scales is what
		// produces organic shapes instead.
		float avgRadius = (this.minRadius + this.maxRadius) * 0.5F;
		int extraLargeScale = Math.max(10, Math.round(avgRadius * 1.8F));
		int largeScale = Math.max(8, Math.round(avgRadius * 0.9F));
		int mediumScale = Math.max(6, Math.round(avgRadius * 0.4F));
		int smallScale = Math.max(4, Math.round(avgRadius * 0.15F));
		int extraSmallScale = Math.max(2, Math.round(avgRadius * 0.06F));
		Domain shapeWarp = Domains.domainPerlin(seed.next(), extraLargeScale, 2, avgRadius * 0.7F);
		shapeWarp = Domains.add(shapeWarp, Domains.domainPerlin(seed.next(), largeScale, 2, avgRadius * 0.45F));
		shapeWarp = Domains.add(shapeWarp, Domains.domainPerlin(seed.next(), mediumScale, 2, avgRadius * 0.25F));
		shapeWarp = Domains.add(shapeWarp, Domains.domainPerlin(seed.next(), smallScale, 1, avgRadius * 0.12F));
		shapeWarp = Domains.add(shapeWarp, Domains.domainPerlin(seed.next(), extraSmallScale, 1, avgRadius * 0.04F));
		this.shapeWarp = shapeWarp;
	}

	@Override
	public void apply(Cell cell, float x, float z) {
		this.delegate.apply(cell, x, z);
		if (!this.enabled || cell.continentEdge >= this.oceanThreshold) {
			return;
		}
		IslandSample sample = this.sample(x, z);
		if (sample == null) {
			return;
		}
		cell.continentEdge = sample.edge;
		cell.continentId = this.roll(4, sample.gridX, sample.gridZ);
		// use the absolute grid index so the marker stays intact in the
		// negative half of the world too, otherwise getRivermap below would
		// miss the cell and let mainland rivers cut into the island
		cell.continentX = ISLAND_MARKER + Math.abs(sample.gridX);
		cell.continentZ = ISLAND_MARKER + Math.abs(sample.gridZ);
		if (sample.mushroom) {
			cell.mushroomIsland = true;
		}
	}

	@Override
	public float getEdgeValue(float x, float z) {
		float base = this.delegate.getEdgeValue(x, z);
		if (!this.enabled || base >= this.oceanThreshold) {
			return base;
		}
		IslandSample sample = this.sample(x, z);
		return sample != null ? sample.edge : base;
	}

	@Override
	public float getLandValue(float x, float z) {
		float base = this.delegate.getLandValue(x, z);
		if (!this.enabled || base >= this.oceanThreshold) {
			return base;
		}
		IslandSample sample = this.sample(x, z);
		return sample != null ? sample.edge : base;
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
	 * Checks every island grid point near (x, z), including neighbours whose
	 * radius plus falloff band could still reach this position. Returns the
	 * highest edge value found, so overlapping islands merge instead of
	 * cutting off at whichever grid point is nearest. Returns null if no
	 * island reaches this position at all, meaning the mainland's own value
	 * applies unchanged. Deliberately never reads the mainland's edge value
	 * except for the one-time per-grid-point buffer check below - the shape
	 * itself is fully self-contained, so there is no seam against the
	 * mainland's own (possibly irregular) coastline.
	 */
	private IslandSample sample(float x, float z) {
		float wx = this.warp.getX(x, z, 0);
		float wz = this.warp.getZ(x, z, 0);
		float px = wx * this.frequency;
		float pz = wz * this.frequency;
		int xr = NoiseUtil.floor(px);
		int zr = NoiseUtil.floor(pz);

		float sx = this.shapeWarp.getX(x, z, 0);
		float sz = this.shapeWarp.getZ(x, z, 0);

		float bestEdge = OPEN_OCEAN_EDGE;
		int bestGridX = 0;
		int bestGridZ = 0;
		boolean placed = false;

		for (int dz = -1; dz <= 1; ++dz) {
			for (int dx = -1; dx <= 1; ++dx) {
				int gx = xr + dx;
				int gz = zr + dz;

				if (this.roll(1, gx, gz) > this.chance) {
					// this grid point rolled "no island"
					continue;
				}

				Vec2f offset = NoiseUtil.cell(this.seed, gx, gz);
				float cxf = gx + offset.x() * this.jitter;
				float czf = gz + offset.y() * this.jitter;
				float worldX = cxf / this.frequency;
				float worldZ = czf / this.frequency;

				// one-time grid-cell level exclusion: reject island footprints too
				// close to the mainland before doing any per-block shape work -
				// this is the only place the mainland's shape can prevent an
				// island from existing at all
				float continentEdgeAtCenter = this.delegate.getEdgeValue(worldX, worldZ);
				if (continentEdgeAtCenter >= this.controlPoints.shallowOcean - this.continentBuffer) {
					continue;
				}

				float radius = NoiseUtil.lerp(this.minRadius, this.maxRadius, this.roll(2, gx, gz));
				float falloffRadius = radius * FALLOFF_SCALE;

				float dist = NoiseUtil.sqrt(NoiseUtil.dist2(worldX, worldZ, sx, sz));
				if (dist >= falloffRadius) {
					// too far away for even the soft falloff band to reach
					continue;
				}

				float t = NoiseUtil.clamp(dist / radius, 0.0F, 1.0F);
				float coreEdge = this.radialEdge(t);

				float edge;
				if (dist <= radius) {
					edge = coreEdge;
				} else {
					// soft blend from the island's own boundary value down to the
					// flat open-ocean plateau, so there is no hard step and no
					// dependency on the mainland's own edge value at this pixel
					float falloffT = (dist - radius) / (falloffRadius - radius);
					edge = NoiseUtil.lerp(coreEdge, OPEN_OCEAN_EDGE, falloffT);
				}

				if (!placed || edge > bestEdge) {
					placed = true;
					bestEdge = edge;
					bestGridX = gx;
					bestGridZ = gz;
				}
			}
		}

		if (!placed) {
			return null;
		}
		boolean mushroom = this.roll(3, bestGridX, bestGridZ) < this.rareBiomeChance;
		return new IslandSample(bestEdge, mushroom, bestGridX, bestGridZ);
	}

	/**
	 * Converts a radial distance fraction (0 at the island's centre, 1 at its
	 * core radius) into a continentEdge value spanning the full deepOcean ->
	 * 1.0 range - the SAME range and control points the mainland's own
	 * terrain populator reads. This is what gives islands a real
	 * shallow-ocean ring and a beach band. inlandFraction shifts the balance
	 * point of the curve, controlling how much of the radius reads as inland
	 * core versus coastal fringe.
	 *
	 * EXPERIMENTAL: an extra flattening step is applied around a fixed point
	 * in the curve approximating where the coast/beach band sits. This
	 * compresses more of the physical radius into a narrow band of edge
	 * values right at that point, which stretches the land-to-ocean-floor
	 * slope out near the surface instead of dropping straight down, reading
	 * as a small beach shelf. The exact position ({@code shelfCenter} /
	 * {@code shelfWidth}) is a rough guess and will likely need retuning
	 * once seen in-game.
	 */
	private float radialEdge(float t) {
		float shelfCenter = 0.78F;
		float shelfWidth = 0.12F;
		float dist = Math.abs(t - shelfCenter);
		if (dist < shelfWidth) {
			float local = dist / shelfWidth;
			float ease = local * local * (3.0F - 2.0F * local);
			t = shelfCenter + (t - shelfCenter) * ease;
		}

		float invT = 1.0F - t;
		float mid = NoiseUtil.lerp(0.75F, 0.25F, this.inlandFraction);
		float curved = NoiseUtil.curve(invT, mid, 1.8F);
		return NoiseUtil.lerp(this.controlPoints.deepOcean, 1.0F, curved);
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