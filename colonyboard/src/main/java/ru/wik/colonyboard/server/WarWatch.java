package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import ru.wik.colonyboard.BoardConfig;
import ru.wik.colonyboard.Net;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Polls ColonyState.danger every 5 s and toasts online members when war/siege/raid starts or ends. */
public final class WarWatch {
    private static final Map<String, String> LAST = new HashMap<>();
    private static int ticks;
    private WarWatch() {}

    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++ticks < 100) return;
        ticks = 0;
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        try {
            for (IColony c : IColonyManager.getInstance().getAllColonies()) {
                String now = ColonyState.danger(c);
                String key = ColonyKey.of(c);
                if (!LAST.containsKey(key)) { LAST.put(key, now); continue; }   // first look: no toast
                String was = LAST.put(key, now);
                if (Objects.equals(was, now) || !BoardConfig.TOASTS_ENABLED.get() || !BoardConfig.TOASTS_WAR.get()) continue;
                Component msg = now != null
                    ? Component.translatable(now.equals("colonyboard.state.war") ? "colonyboard.toast.war.start" : "colonyboard.toast.danger.start", c.getName(), Component.translatable(now))
                    : Component.translatable(was.equals("colonyboard.state.war") ? "colonyboard.toast.war.end" : "colonyboard.toast.danger.end", c.getName(), Component.translatable(was));
                for (ServerPlayer p : srv.getPlayerList().getPlayers())
                    if (c.getPermissions().isColonyMember(p)) Net.toast(p, now != null ? Net.Toast.WAR : Net.Toast.INFO,
                        Component.translatable(now != null ? (now.equals("colonyboard.state.war") ? "colonyboard.toast.t.war.start" : "colonyboard.toast.t.danger")
                            : "colonyboard.toast.t.peace"), msg, false, null);
            }
        } catch (Throwable ignored) {}
    }
}
