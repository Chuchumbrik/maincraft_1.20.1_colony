package ru.wik.colonybuildspeed;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(ColonyBuildSpeed.ID)
public class ColonyBuildSpeed {
    public static final String ID = "colonybuildspeed";

    public ColonyBuildSpeed() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }
}
