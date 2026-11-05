package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import net.minecraft.server.MinecraftServer;

/** Isolated so colonybuy classes are only loaded when colonybuy is present. */
final class CbAuto {
    private CbAuto() {}

    /** {on(1/0), daily budget, auto spent today} */
    static int[] get(MinecraftServer s, IColony c) {
        ru.wik.colonybuy.Store.Entry e = ru.wik.colonybuy.Store.get(s).entry(ru.wik.colonybuy.Core.dim(c), c.getID());
        return new int[]{e.auto ? 1 : 0, e.budget(), e.autoSpent};
    }

    static void set(MinecraftServer s, IColony c, boolean on) {
        ru.wik.colonybuy.Store st = ru.wik.colonybuy.Store.get(s);
        st.entry(ru.wik.colonybuy.Core.dim(c), c.getID()).auto = on;
        st.setDirty();
    }

    /** 1.7: daily auto-buy budget (same as /colonybuy auto budget N). */
    static void setBudget(MinecraftServer s, IColony c, int amount) {
        ru.wik.colonybuy.Store st = ru.wik.colonybuy.Store.get(s);
        st.entry(ru.wik.colonybuy.Core.dim(c), c.getID()).budget = Math.max(0, amount);
        st.setDirty();
    }

    /** 1.7: true if colonybuy refuses to buy right now because of war / siege / raid (its own config). */
    static boolean blocked(IColony c) { return ru.wik.colonybuy.War.blockReason(c) != null; }
}
