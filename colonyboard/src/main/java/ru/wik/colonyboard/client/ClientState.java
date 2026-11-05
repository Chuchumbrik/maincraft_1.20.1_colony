package ru.wik.colonyboard.client;

import net.minecraft.client.Minecraft;
import ru.wik.colonyboard.Net;

public final class ClientState {
    public static volatile Net.Stats stats;
    public static volatile Net.MarketData market;
    public static volatile Net.AutoState auto;
    public static volatile long marketAt;
    /** 1.7: server dashboard facts (null = server without colonyboard 1.7). */
    public static volatile Net.DashData dash;
    public static volatile long dashAt;
    /** 1.7.1: dig-tool min tier floor. */
    public static volatile Net.TierState tier;
    public static volatile long tierAt;
    public static void onDash(Net.DashData d) { dash = d; dashAt = System.currentTimeMillis(); }
    public static void onTier(Net.TierState t) { tier = t; tierAt = System.currentTimeMillis(); }
    private ClientState() {}
    public static void onQuote(Net.Quote q) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen ? null : mc.screen, q));
    }
    public static void onMarket(Net.MarketData m) { market = m; marketAt = System.currentTimeMillis(); }
}
