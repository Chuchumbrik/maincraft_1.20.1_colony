package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import ru.wik.colonyboard.Net;

/** 1.6.2: colonybuy per-colony auto-buy switch (same flag as /colonybuy auto on|off). Only managers (owner/officers) may flip it. */
public final class AutoBuy {
    private AutoBuy() {}

    static boolean present() { return ModList.get().isLoaded("colonybuy"); }

    public static Net.AutoState state(ServerPlayer p, IColony c) {
        if (!present()) return new Net.AutoState(c.getID(), false, false, 0, 0, "");
        try {
            int[] s = CbAuto.get(p.server, c);
            String deny = Fulfil.isManager(p, c) || p.hasPermissions(2) ? "" : "colonyboard.auto.rights";
            return new Net.AutoState(c.getID(), true, s[0] == 1, s[1], s[2], deny);
        } catch (Throwable t) { return new Net.AutoState(c.getID(), false, false, 0, 0, ""); }
    }

    public static void toggle(ServerPlayer p, IColony c, boolean on) {
        if (!present() || !(Fulfil.isManager(p, c) || p.hasPermissions(2))) return;
        try {
            CbAuto.set(p.server, c, on);
            Fulfil.LOG.info("[colonyboard] {} switched colonybuy auto-buy {} for colony {}", p.getGameProfile().getName(), on ? "ON" : "OFF", c.getID());
            Net.toast(p, on ? Net.Toast.OK : Net.Toast.INFO,
                Component.translatable(on ? "colonyboard.auto.toast.on" : "colonyboard.auto.toast.off", c.getName()), false,
                Component.translatable(on ? "colonyboard.auto.toast.on" : "colonyboard.auto.toast.off", c.getName()));
        } catch (Throwable t) { Fulfil.LOG.warn("[colonyboard] auto-buy toggle failed: {}", t.toString()); }
    }
}
