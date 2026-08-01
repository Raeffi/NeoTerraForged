package raccoonman.reterraforged.data.worldgen.preset.settings;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import raccoonman.reterraforged.world.worldgen.cell.continent.IslandPopulator;
import raccoonman.reterraforged.world.worldgen.noise.function.DistanceFunction;
public class WorldSettings {
	public static final Codec<WorldSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Continent.CODEC.fieldOf("continent").forGetter((o) -> o.continent),
			ControlPoints.CODEC.fieldOf("controlPoints").forGetter((o) -> o.controlPoints),
			Properties.CODEC.fieldOf("properties").forGetter((o) -> o.properties),
			Islands.CODEC.optionalFieldOf("islands", Islands.makeDefault()).forGetter((o) -> o.islands)
	).apply(instance, WorldSettings::new));
	public Continent continent;
	public ControlPoints controlPoints;
	public Properties properties;
	public Islands islands;
	public WorldSettings(Continent continent, ControlPoints controlPoints, Properties properties) {
		this(continent, controlPoints, properties, Islands.makeDefault());
	}
	public WorldSettings(Continent continent, ControlPoints controlPoints, Properties properties, Islands islands) {
		this.continent = continent;
		this.controlPoints = controlPoints;
		this.properties = properties;
		this.islands = islands;
	}
	public WorldSettings copy() {
		return new WorldSettings(this.continent.copy(), this.controlPoints.copy(), this.properties.copy(), this.islands.copy());
	}
	public static class Continent {
		public static final Codec<Continent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				ContinentType.CODEC.fieldOf("continentType").forGetter((o) -> o.continentType),
				DistanceFunction.CODEC.optionalFieldOf("continentShape", DistanceFunction.EUCLIDEAN).forGetter((o) -> o.continentShape),
				Codec.INT.fieldOf("continentScale").forGetter((o) -> o.continentScale),
				Codec.FLOAT.fieldOf("continentJitter").forGetter((o) -> o.continentJitter),
				Codec.FLOAT.optionalFieldOf("continentSkipping", 0.25F).forGetter((o) -> o.continentSkipping),
				Codec.FLOAT.optionalFieldOf("continentSizeVariance", 0.25F).forGetter((o) -> o.continentSizeVariance),
				Codec.INT.optionalFieldOf("continentNoiseOctaves", 5).forGetter((o) -> o.continentNoiseOctaves),
				Codec.FLOAT.optionalFieldOf("continentNoiseGain", 0.26F).forGetter((o) -> o.continentNoiseGain),
				Codec.FLOAT.optionalFieldOf("continentNoiseLacunarity", 4.33F).forGetter((o) -> o.continentNoiseLacunarity)
		).apply(instance, Continent::new));
		public ContinentType continentType;
		public DistanceFunction continentShape;
		public int continentScale;
		public float continentJitter;
		public float continentSkipping;
		public float continentSizeVariance;
		public int continentNoiseOctaves;
		public float continentNoiseGain;
		public float continentNoiseLacunarity;
		public Continent(ContinentType continentType, DistanceFunction continentShape, int continentScale, float continentJitter, float continentSkipping, float continentSizeVariance, int continentNoiseOctaves, float continentNoiseGain, float continentNoiseLacunarity) {
			this.continentType = continentType;
			this.continentShape = continentShape;
			this.continentScale = continentScale;
			this.continentJitter = continentJitter;
			this.continentSkipping = continentSkipping;
			this.continentSizeVariance = continentSizeVariance;
			this.continentNoiseOctaves = continentNoiseOctaves;
			this.continentNoiseGain = continentNoiseGain;
			this.continentNoiseLacunarity = continentNoiseLacunarity;
		}
		public Continent copy() {
			return new Continent(this.continentType, this.continentShape, this.continentScale, this.continentJitter, this.continentSkipping, this.continentSizeVariance, this.continentNoiseOctaves, this.continentNoiseGain, this.continentNoiseLacunarity);
		}
	}
	public static class ControlPoints {
		public static final Codec<ControlPoints> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.optionalFieldOf("islandInland", IslandPopulator.DEFAULT_INLAND_POINT).forGetter((o) -> o.islandInland),
				Codec.FLOAT.optionalFieldOf("islandCoast", IslandPopulator.DEFAULT_COAST_POINT).forGetter((o) -> o.islandCoast),
				Codec.FLOAT.fieldOf("deepOcean").forGetter((o) -> o.deepOcean),
				Codec.FLOAT.fieldOf("shallowOcean").forGetter((o) -> o.shallowOcean),
				Codec.FLOAT.fieldOf("beach").forGetter((o) -> o.beach),
				Codec.FLOAT.fieldOf("coast").forGetter((o) -> o.coast),
				Codec.FLOAT.fieldOf("inland").forGetter((o) -> o.inland)
		).apply(instance, ControlPoints::new));
		public float islandInland;
		public float islandCoast;
		public float deepOcean;
		public float shallowOcean;
		public float beach;
		public float coast;
		public float inland;
		public ControlPoints(float islandInland, float islandCoast, float deepOcean, float shallowOcean, float beach, float coast, float inland) {
			this.islandInland = islandInland;
			this.islandCoast = islandCoast;
			this.deepOcean = deepOcean;
			this.shallowOcean = shallowOcean;
			this.beach = beach;
			this.coast = coast;
			this.inland = inland;
		}
		public float coastMarker() {
			return this.coast + (this.inland - this.coast) / 2.0F;
		}
		public ControlPoints copy() {
			return new ControlPoints(this.islandInland, this.islandCoast, this.deepOcean, this.shallowOcean, this.beach, this.coast, this.inland);
		}
	}
	public static class Properties {
		public static final Codec<Properties> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				SpawnType.CODEC.fieldOf("spawnType").forGetter((o) -> o.spawnType),
				Codec.INT.fieldOf("worldHeight").forGetter((o) -> o.worldHeight),
				Codec.INT.optionalFieldOf("worldDepth", 64).forGetter((o) -> o.worldDepth),
				Codec.INT.fieldOf("seaLevel").forGetter((o) -> o.seaLevel),
				Codec.INT.optionalFieldOf("lavaLevel", -54).forGetter((o) -> o.lavaLevel)
		).apply(instance, Properties::new));
		public SpawnType spawnType;
		public int worldHeight;
		public int worldDepth;
		public int seaLevel;
		public int lavaLevel;
		public Properties(SpawnType spawnType, int worldHeight, int worldDepth, int seaLevel, int lavaLevel) {
			this.spawnType = spawnType;
			this.worldHeight = worldHeight;
			this.worldDepth = worldDepth;
			this.seaLevel = seaLevel;
			this.lavaLevel = lavaLevel;
		}
		public Properties copy() {
			return new Properties(this.spawnType, this.worldHeight, this.worldDepth, this.seaLevel, this.lavaLevel);
		}
		@Deprecated
		public int terrainScaler() {
			return Math.min(this.worldHeight, 256);
		}
	}
	public static class Islands {
		public static final Codec<Islands> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("enabled", true).forGetter((o) -> o.enabled),
				Codec.INT.optionalFieldOf("spacing", 600).forGetter((o) -> o.spacing),
				Codec.FLOAT.optionalFieldOf("chance", 0.2F).forGetter((o) -> o.chance),
				Codec.FLOAT.optionalFieldOf("minRadius", 32.0F).forGetter((o) -> o.minRadius),
				Codec.FLOAT.optionalFieldOf("maxRadius", 128.0F).forGetter((o) -> o.maxRadius),
				Codec.FLOAT.optionalFieldOf("jitter", 0.7F).forGetter((o) -> o.jitter),
				Codec.FLOAT.optionalFieldOf("rareBiomeChance", 0.05F).forGetter((o) -> o.rareBiomeChance),
				Codec.FLOAT.optionalFieldOf("continentBuffer", 0.05F).forGetter((o) -> o.continentBuffer)
		).apply(instance, Islands::new));
		// true to scatter islands across the ocean at all
		public boolean enabled;
		// average distance, in blocks, between island grid points
		public int spacing;
		// chance, 0-1, that any one grid point actually holds an island
		public float chance;
		// smallest possible island radius, in blocks
		public float minRadius;
		// largest possible island radius, in blocks
		public float maxRadius;
		// how far an island's centre can drift from its grid point, 0-1
		public float jitter;
		// chance, 0-1, that an island is eligible to host an isolated biome such as Mushroom Fields
		public float rareBiomeChance;
		// minimum gap, in continentEdge units, kept between an island's centre and the
		// mainland coastline before the island is allowed to generate at all
		public float continentBuffer;
		public Islands(boolean enabled, int spacing, float chance, float minRadius, float maxRadius, float jitter, float rareBiomeChance, float continentBuffer) {
			this.enabled = enabled;
			this.spacing = spacing;
			this.chance = chance;
			this.minRadius = minRadius;
			this.maxRadius = maxRadius;
			this.jitter = jitter;
			this.rareBiomeChance = rareBiomeChance;
			this.continentBuffer = continentBuffer;
		}
		public static Islands makeDefault() {
			return new Islands(true, 600, 0.2F, 32.0F, 128.0F, 0.7F, 0.05F, 0.05F);
		}
		public Islands copy() {
			return new Islands(this.enabled, this.spacing, this.chance, this.minRadius, this.maxRadius, this.jitter, this.rareBiomeChance, this.continentBuffer);
		}
	}
}