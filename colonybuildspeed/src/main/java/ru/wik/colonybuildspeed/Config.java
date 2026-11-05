package ru.wik.colonybuildspeed;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue BUILDERS_ONLY;
    public static final ForgeConfigSpec.BooleanValue DECORATE;
    public static final ForgeConfigSpec.BooleanValue DEMOLISH;
    public static final ForgeConfigSpec.IntValue BLOCKS_PER_CYCLE;
    public static final ForgeConfigSpec.DoubleValue REACH;

    private Config() {
    }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment(
            "Builders place several blocks per work cycle instead of one.",
            "Only plain blocks: no block entities (chests, furnaces...), no blueprint entities, no doors/beds/double plants,",
            "no structurize placeholders/substitutions. Each extra block must be within 'reach' of the citizen.",
            "If the next block does not qualify, the cycle ends as in vanilla and it is placed normally next cycle.",
            "CLEAR/REMOVE uses the same blocksPerCycle and reach for ordinary solids. Default reach is "
                + ConfigDefaults.REACH + "; the maximum is " + ConfigDefaults.REACH_MAX
                + ". A live server file may set 10 without changing this default.",
            "blocksPerCycle is a maximum, not a guarantee that every cycle breaks or places that many."
        ).push("general");
        ENABLED = b.define("enabled", ConfigDefaults.ENABLED);
        BLOCKS_PER_CYCLE = b.comment("Max blocks placed or mined per work cycle (1 = vanilla)")
            .defineInRange("blocksPerCycle", ConfigDefaults.BLOCKS, ConfigDefaults.BLOCKS_MIN, ConfigDefaults.BLOCKS_MAX);
        REACH = b.comment("Max distance (blocks) from the citizen to every extra block. Default 5, max 10.")
            .defineInRange("reach", ConfigDefaults.REACH, ConfigDefaults.REACH_MIN, ConfigDefaults.REACH_MAX);
        BUILDERS_ONLY = b.comment("true = only Builder's huts (not miner shafts, recruiters, etc.)")
            .define("buildersOnly", ConfigDefaults.BUILDERS_ONLY);
        DECORATE = b.comment("Also chain in the DECORATE stage (torches, slabs, glass panes...). Off = only BUILD_SOLID/WEAK_SOLID")
            .define("includeDecorate", ConfigDefaults.DECORATE);
        DEMOLISH = b.comment("During CLEAR and REMOVE, mine up to blocksPerCycle ordinary solids in one builder cycle. Off leaves breaking vanilla.")
            .define("includeDemolish", ConfigDefaults.DEMOLISH);
        b.pop();
        SPEC = b.build();
    }
}
