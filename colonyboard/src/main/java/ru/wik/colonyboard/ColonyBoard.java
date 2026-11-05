package ru.wik.colonyboard;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkConstants;

@Mod(ColonyBoard.MODID)
public class ColonyBoard {
    public static final String MODID = "colonyboard";

    public ColonyBoard() {
        // Optional on either side: vanilla/other servers and clients without the mod stay compatible.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, BoardConfig.SPEC);
        Net.init();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ru.wik.colonyboard.server.WarWatch::tick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ru.wik.colonyboard.server.Market::tick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ru.wik.colonyboard.server.ToolTier::tick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ru.wik.colonyboard.server.Market::onLogin);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ru.wik.colonyboard.server.ReqWatch::tick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ru.wik.colonyboard.server.ReqWatch::onLogin);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ru.wik.colonyboard.server.ReqWatch::onLogout);
        if (net.minecraftforge.fml.ModList.get().isLoaded("colonybuy")) {
            try { ru.wik.colonyboard.server.CbToasts.install(); }
            catch (Throwable t) { org.slf4j.LoggerFactory.getLogger("colonyboard").info("[colonyboard] colonybuy < 1.0.3: its messages stay in chat"); }
        }
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ru.wik.colonyboard.client.ClientSetup::init);
    }
}
