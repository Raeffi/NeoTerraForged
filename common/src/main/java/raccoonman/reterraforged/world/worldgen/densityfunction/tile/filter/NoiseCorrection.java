package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import net.minecraft.core.QuartPos;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainCategory;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;

public record NoiseCorrection(Levels levels) implements Filter {

	@Override
	public void apply(Filterable map, int seedX, int seedZ, int iterations) {
		Size size = map.getBlockSize();
		int total = size.total();

		int quartTotal = QuartPos.fromBlock(total);
		for(int quartX = 0; quartX < quartTotal; quartX++) {
			for(int quartZ = 0; quartZ < quartTotal; quartZ++) {
				int startX = QuartPos.toBlock(quartX);
				int startZ = QuartPos.toBlock(quartZ);
				int endX = QuartPos.toBlock(quartX + 1);
				int endZ = QuartPos.toBlock(quartZ + 1);

				boolean touchesShore = false;

				beachTest:
				for(int x = startX; x < endX; x++) {
					for(int z = startZ; z < endZ; z++) {
						Cell cell = map.getCellRaw(x, z);
						if (cell.terrain == TerrainType.BEACH || cell.terrain == TerrainType.SHOAL) {
							touchesShore = true;
							break beachTest;
						}
					}
				}

				if (touchesShore) {
					for(int x = startX; x < endX; x++) {
						for(int z = startZ; z < endZ; z++) {
							Cell cell = map.getCellRaw(x, z);
							if (cell.terrain.overridesCoast() || cell.terrain.isWetland()) {
								continue;
							}
							if (cell.height <= this.levels.water) {
								cell.terrain = TerrainType.SHOAL;
							} else {
								cell.terrain = TerrainType.BEACH;
							}
						}
					}
				}
			}
		}
	}
}
