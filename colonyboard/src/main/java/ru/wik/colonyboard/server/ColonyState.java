package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** WarNTaxes treasury + war/siege/raid flags, all by reflection so API drift never crashes. */
public final class ColonyState {
    private ColonyState() {}

    private static Object call(String cls, String m, int id) {
        try { Method mm = Class.forName(cls).getMethod(m, int.class); return mm.invoke(null, id); } catch (Throwable t) { return null; }
    }
    private static boolean flag(String cls, String m, int id) { return call(cls, m, id) instanceof Boolean b && b; }

    public static long treasury(IColony c) {
        if (!ModList.get().isLoaded("minecolonytax")) return -1;
        Object o = call("net.machiavelli.minecolonytax.economy.TreasuryManager", "getTreasuryBalance", c.getID());
        return o instanceof Number n ? n.longValue() : -1;
    }

    /** lang key of the current danger, or null */
    public static String danger(IColony c) {
        int id = c.getID();
        if (flag("net.machiavelli.minecolonytax.WarSystem", "isColonyInWar", id)
            || flag("net.machiavelli.minecolonytax.economy.WarExhaustionManager", "isAtWar", id)) return "colonyboard.state.war";
        if (flag("net.machiavelli.minecolonytax.besiege.BesiegeManager", "isColonyBesieged", id)
            || flag("net.machiavelli.minecolonytax.besiege.BesiegeManager", "isActiveRaidOnColony", id)) return "colonyboard.state.siege";
        if (flag("net.machiavelli.minecolonytax.raid.RaidManager", "isColonyUnderRaid", id)
            || flag("net.machiavelli.minecolonytax.raid.EntityRaidManager", "hasActiveEntityRaid", id)) return "colonyboard.state.raid";
        try { if (c.getRaiderManager() != null && c.getRaiderManager().isRaided()) return "colonyboard.state.mcraid"; } catch (Throwable ignored) {}
        return null;
    }
}
