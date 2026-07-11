package raccoonman.reterraforged.mixin.terrablender;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.world.worldgen.biome.BeachParameterCache;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.terrablender.TBTargetPoint;
import terrablender.api.RegionType;
import terrablender.api.Regions;

import java.util.ArrayList;
import java.util.List;

@Mixin(
	value = Climate.ParameterList.class,
	priority = 1001
)
class MixinParameterList<T> {
	private int maxIndex;

	@Inject(
		at = @At("HEAD"),
		method = "initializeForTerraBlender",
		require = 1	
	)
    public void initializeForTerraBlender(RegistryAccess registryAccess, RegionType regionType, long seed, CallbackInfo callback) {
    	this.maxIndex = Regions.getCount(regionType) - 1;
//
//    	registryAccess.lookup(RTFRegistries.PRESET).flatMap((registry) -> {
//    		return registry.get(Preset.KEY);
//    	}).ifPresent((holder) -> {
//    		Preset preset = holder.value();
//        	TBCompat.setSurfaceRules(preset, (defaultRules) -> {
//        		return RTFSurfaceRuleData.overworld(preset, registryAccess.lookupOrThrow(Registries.DENSITY_FUNCTION), registryAccess.lookupOrThrow(RTFRegistries.NOISE), defaultRules);
//            });
//    	});

		if (regionType == RegionType.OVERWORLD) {
			List<Climate.ParameterPoint> beachPoints = new ArrayList<>();
			List<Climate.ParameterPoint> stonyShorePoints = new ArrayList<>();
			for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : ((Climate.ParameterList<Holder<Biome>>) (Object) this).values()) {
				boolean isTaggedBeach = entry.getSecond().is(TagKey.create(Registries.BIOME, new ResourceLocation("minecraft", "is_beach")));
				boolean isStonyShore = entry.getSecond().unwrapKey()
						.map(key -> key.location().getPath().equals("stony_shore"))
						.orElse(false);

				if (isTaggedBeach || isStonyShore) {
					beachPoints.add(entry.getFirst());
					if (isStonyShore) {
						stonyShorePoints.add(entry.getFirst());
					}
				}
			}
			BeachParameterCache.set(beachPoints, stonyShorePoints);
		}
    }

	@Redirect(
		method = "findValuePositional",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Climate$ParameterList;getUniqueness(III)I"
		),
		require = 0	
	)
    public int getUniqueness(Climate.ParameterList<T> parameterList, int x, int y, int z, Climate.TargetPoint targetPoint) {
		if((Object) targetPoint instanceof TBTargetPoint tbTargetPoint) {
			double uniqueness = tbTargetPoint.getUniqueness();
			if(Double.isNaN(uniqueness)) {
				return this.getUniqueness(x, y, z);
			}
			return NoiseUtil.round(this.maxIndex * (float) uniqueness);
		} else {
			throw new IllegalStateException();
		}
    }

	@Shadow
    public int getUniqueness(int x, int y, int z) {
    	throw new UnsupportedOperationException();
    }
}
