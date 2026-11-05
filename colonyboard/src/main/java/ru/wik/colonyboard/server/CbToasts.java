package ru.wik.colonyboard.server;

import net.minecraft.network.chat.Component;
import ru.wik.colonyboard.Net;

/** Isolated: only loaded when colonybuy 1.0.3+ is present. colonybuy prompts/results become glass toasts for 1.6 clients. */
public final class CbToasts {
    private CbToasts() {}

    public static void install() {
        ru.wik.colonybuy.Notify.SINK = (p, kind, title, body, colonyId, ids, actions) -> {
            if (!Net.hasGlass(p)) return false;      // old clients keep colonybuy's clickable chat lines
            // 1.6.1: colonybuy's stuck-request prompt (builder only, 5 min cooldown) is replaced by ReqWatch (every request, every hut)
            if (kind == ru.wik.colonybuy.Notify.INFO && actions == (ru.wik.colonybuy.Notify.ACT_CONFIRM | ru.wik.colonybuy.Notify.ACT_OPEN)
                && ReqWatch.active()) return true;
            int a = 0;
            if ((actions & ru.wik.colonybuy.Notify.ACT_CONFIRM) != 0) a |= Net.Glass.ACT_CONFIRM;
            if ((actions & ru.wik.colonybuy.Notify.ACT_OPEN) != 0) a |= Net.Glass.ACT_OPEN;
            return Net.glass(p, new Net.Glass(kind, title, body, Component.empty(), colonyId, ids, a), null);
        };
    }
}
