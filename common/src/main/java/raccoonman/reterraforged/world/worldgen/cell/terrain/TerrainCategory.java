package raccoonman.reterraforged.world.worldgen.cell.terrain;

public enum TerrainCategory implements ITerrain {
    NONE, 
    DEEP_OCEAN {
        @Override
        public boolean isDeepOcean() {
            return true;
        }
        
        @Override
        public boolean overridesRiver() {
            return true;
        }
        
        @Override
        public boolean isSubmerged() {
            return true;
        }
    }, 
    SHALLOW_OCEAN {
        @Override
        public boolean isShallowOcean() {
            return true;
        }
        
        @Override
        public boolean isSubmerged() {
            return true;
        }
        
        @Override
        public boolean overridesRiver() {
            return true;
        }
    }, 
    COAST {
        @Override
        public boolean isCoast() {
            return true;
        }
        
        @Override
        public boolean isOverground() {
            return true;
        }
        
        @Override
        public boolean overridesRiver() {
            return true;
        }
    }, 
    BEACH {
        @Override
        public boolean isCoast() {
            return true;
        }
        
        @Override
        public boolean isOverground() {
            return true;
        }
        
        @Override
        public boolean overridesRiver() {
            return true;
        }
    },
    SHOAL {
        @Override
        public boolean isSubmerged() {
            return true; // depth/fluid logic still treats this as underwater
        }

        @Override
        public boolean overridesRiver() {
            return true;
        }

        // deliberately NOT isShallowOcean() — we want SHOAL routed to the
        // COAST continentalness branch in CellSampler, not the ocean branch.
        // deliberately NOT isCoast() either — isCoast() is used elsewhere
        // (BeachDetect's own gating, ClimateModule) to mean "land coast";
        // SHOAL is checked explicitly by type instead, see CellSampler below.
    },
    RIVER {
        @Override
        public boolean isRiver() {
            return true;
        }
        
        @Override
        public boolean isSubmerged() {
            return true;
        }
    }, 
    LAKE {
        @Override
        public boolean isLake() {
            return true;
        }
        
        @Override
        public boolean isSubmerged() {
            return true;
        }
    }, 
    WETLAND {
        @Override
        public boolean isWetland() {
            return true;
        }
        
        @Override
        public boolean isOverground() {
            return true;
        }
    }, 
    FLATLAND {
        @Override
        public boolean isFlat() {
            return true;
        }
        
        @Override
        public boolean isOverground() {
            return true;
        }
    }, 
    LOWLAND {
        @Override
        public boolean isOverground() {
            return true;
        }
    }, 
    HIGHLAND {
        @Override
        public boolean isOverground() {
            return true;
        }
    },
    ISLAND {
        @Override
        public boolean isOverground() {
            return true;
        }
    };
    
    public TerrainCategory getDominant(TerrainCategory other) {
        if (this.ordinal() > other.ordinal()) {
            return this;
        }
        return other;
    }
}
