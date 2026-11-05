package ru.wik.colonyboard.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.sixik.sdmeconomy.api.EconomyAPI;
import net.sixik.sdmeconomy.currencies.data.CurrencyPlayerData;
import ru.wik.colonyboard.BoardConfig;

/** SDM Economy wallet. Only touches SDM classes when the mod is loaded. */
public final class Wallet {
    private Wallet() {}
    public static boolean available() { return ModList.get().isLoaded("sdmeconomy"); }

    public static double balance(ServerPlayer p) {
        if (!available()) return -1;
        try { Double v = CurrencyPlayerData.SERVER.getBalance(p.getUUID(), BoardConfig.CURRENCY.get()).value; return v == null ? 0 : v; }
        catch (Throwable t) { return -1; }
    }

    public static boolean take(ServerPlayer p, long amount) {
        try {
            var r = CurrencyPlayerData.SERVER.addCurrencyValue(p.getUUID(), BoardConfig.CURRENCY.get(), -amount);
            try { EconomyAPI.syncPlayer(p); } catch (Throwable ignored) {}
            return r == null || r.isSuccess();
        } catch (Throwable t) { return false; }
    }

    /** Adds money to any player by UUID (works offline); syncs if online. */
    public static boolean give(net.minecraft.server.MinecraftServer srv, java.util.UUID id, long amount) {
        try {
            var r = CurrencyPlayerData.SERVER.addCurrencyValue(id, BoardConfig.CURRENCY.get(), amount);
            ServerPlayer sp = srv == null ? null : srv.getPlayerList().getPlayer(id);
            if (sp != null) try { EconomyAPI.syncPlayer(sp); } catch (Throwable ignored) {}
            return r == null || r.isSuccess();
        } catch (Throwable t) { return false; }
    }
}
