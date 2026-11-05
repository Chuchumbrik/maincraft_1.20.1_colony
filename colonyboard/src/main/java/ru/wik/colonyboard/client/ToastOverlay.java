package ru.wik.colonyboard.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import ru.wik.colonyboard.ClientConfig;
import ru.wik.colonyboard.Net;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 1.6 "glass" toasts (style B): dark translucent panel, thin cyan glow border, round status icon,
 * Russian title + body, stacked upward above the chat (bottom-left). Request toasts carry buttons:
 * Confirm (re-quote -> pay window: treasury / wallet) and Open request (J -> Запросы, row highlighted).
 * Buttons: click while chat (T) is open, or hotkeys (default Ctrl+Y / Ctrl+U). Time is paused while chat is open.
 */
public final class ToastOverlay {
    private static final class Entry {
        final Net.Glass t; final long life; long age; final String key;
        Entry(Net.Glass t, long life) { this.t = t; this.life = life; this.key = t.title().getString() + "\n" + t.body().getString(); }
        boolean has(int a) { return (t.actions() & a) != 0; }
    }
    private record Hit(int x1, int y1, int x2, int y2, Entry e, int action) {
        boolean in(double x, double y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    private static final Deque<Entry> Q = new ArrayDeque<>();
    private static final List<Hit> HITS = new ArrayList<>();
    private static long lastFrame;

    // accent per kind: ok / fail (caution) / war / info
    private static final int[] ACCENT = {0x4FE3FF, 0xFFB238, 0xFF5A5A, 0x4FE3FF};
    private static final int[] ICON_COL = {0x5CFF8A, 0xFFB238, 0xFF5A5A, 0x7FDBFF};
    private static final String[] ICON = {"✔", "⚠", "⚔", "i"};

    private ToastOverlay() {}

    /** Old (1.3-1.5 server) toast packet. */
    public static void push(Net.Toast t) {
        push(new Net.Glass(t.kind(), Net.defaultTitle(t.kind()), t.text(),
            t.hint() ? Component.translatable("colonyboard.toast.hint") : Component.empty(), -1, List.of(), 0));
    }

    public static void push(Net.Glass t) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientConfig.TOASTS.get()) {
            if (mc.player != null) mc.player.displayClientMessage(Component.empty().append(t.title().copy().withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(t.body()), false);
            return;
        }
        Entry e = new Entry(t, t.actions() != 0 ? ClientConfig.ACTION_DURATION_MS.get() : ClientConfig.DURATION_MS.get());
        Q.removeIf(x -> x.key.equals(e.key));               // one toast per action
        Q.addLast(e);
        while (Q.size() > ClientConfig.MAX_VISIBLE.get()) Q.removeFirst();
    }

    public static void register(RegisterGuiOverlaysEvent e) {
        e.registerAbove(VanillaGuiOverlay.CHAT_PANEL.id(), "toasts", (gui, g, pt, w, h) -> render(g, w, h));
    }

    // ------------------------------------------------------------------ actions
    private static Entry newest(int action) {
        Iterator<Entry> it = Q.descendingIterator();
        while (it.hasNext()) { Entry e = it.next(); if (e.has(action)) return e; }
        return null;
    }

    /** Hotkey: act on the newest toast that has this button. */
    public static boolean hotkey(int action) {
        Entry e = newest(action);
        if (e == null) return false;
        act(e, action);
        return true;
    }

    private static void act(Entry e, int action) {
        Q.remove(e);
        Net.Glass t = e.t;
        Minecraft mc = Minecraft.getInstance();
        if (action == Net.Glass.ACT_CONFIRM) {
            if (!ClientSetup.serverHasMod()) return;
            int id = t.colonyId();
            if (id < 0) { var v = ClientSetup.findColony(); id = v == null ? -1 : v.getID(); }
            Net.CHANNEL.sendToServer(new Net.QuoteRequest(id, t.ids()));
        } else if (action == Net.Glass.ACT_OPEN) {
            BoardScreen.openRequest(t.ids().isEmpty() ? null : t.ids().get(0));
        }
    }

    public static void onMouse(ScreenEvent.MouseButtonPressed.Pre ev) {
        if (!(ev.getScreen() instanceof ChatScreen) || ev.getButton() != 0) return;
        for (Hit h : HITS) if (h.in(ev.getMouseX(), ev.getMouseY())) {
            if (h.action() != 0) act(h.e(), h.action());
            else Q.remove(h.e());                           // click on the panel itself = dismiss
            ev.setCanceled(true);
            return;
        }
    }

    // ------------------------------------------------------------------ render
    private static int a(int alpha, int rgb) { return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF); }

    private static Component label(Entry e, int action) {
        var km = action == Net.Glass.ACT_CONFIRM ? ClientSetup.CONFIRM : ClientSetup.OPEN_REQ;
        String key = km.isUnbound() ? "" : " " + km.getTranslatedKeyMessage().getString();
        Component base = Component.translatable(action == Net.Glass.ACT_CONFIRM
            ? (e.t.kind() == Net.Toast.FAIL ? "colonyboard.toast.act.retry" : "colonyboard.toast.act.confirm")
            : (e.t.ids().isEmpty() ? "colonyboard.toast.act.openj" : "colonyboard.toast.act.open"));
        return key.isEmpty() ? base : Component.empty().append(base).append(Component.literal(key).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void render(GuiGraphics g, int sw, int sh) {
        HITS.clear();
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        long dt = lastFrame == 0 ? 0 : Math.min(250, now - lastFrame);
        lastFrame = now;
        if (Q.isEmpty() || mc.options.hideGui) return;
        boolean chatOpen = mc.screen instanceof ChatScreen;
        for (Entry e : Q) if (!chatOpen || e.age < 300) e.age += dt;    // paused while the player reads/clicks
        Q.removeIf(e -> e.age > e.life);
        if (Q.isEmpty()) return;

        Font font = mc.font;
        double scale = mc.options.chatScale().get();
        int chatH = chatOpen ? (int) Math.ceil(mc.gui.getChat().getHeight() * scale) : (int) Math.ceil(20 * 9 * scale * 0.5);
        int y = sh - 48 - chatH;
        int maxW = Math.max(160, Math.min(300, sw / 2 - 8));
        double mx = mc.mouseHandler.xpos() * sw / Math.max(1, mc.getWindow().getScreenWidth());
        double my = mc.mouseHandler.ypos() * sh / Math.max(1, mc.getWindow().getScreenHeight());

        Iterator<Entry> it = Q.descendingIterator();
        while (it.hasNext() && y > 20) {
            Entry e = it.next();
            Net.Glass t = e.t;
            int kind = Math.floorMod(t.kind(), 4);
            int acc = ACCENT[kind];
            float in = Math.min(1f, e.age / 200f), out = e.age > e.life - 400 ? Math.max(0f, (e.life - e.age) / 400f) : 1f;
            float ease = 1f - (1f - in) * (1f - in) * (1f - in);
            int alpha = Math.max(10, (int) (Math.min(in * 1.5f, 1f) * out * 255));
            float k = alpha / 255f;

            Component title = t.title().copy().withStyle(ChatFormatting.BOLD);
            Component hint = t.hint().getString().isEmpty() ? null : t.hint();
            List<Component> btns = new ArrayList<>();
            List<Integer> acts = new ArrayList<>();
            if (e.has(Net.Glass.ACT_CONFIRM)) { btns.add(label(e, Net.Glass.ACT_CONFIRM)); acts.add(Net.Glass.ACT_CONFIRM); }
            if (e.has(Net.Glass.ACT_OPEN)) { btns.add(label(e, Net.Glass.ACT_OPEN)); acts.add(Net.Glass.ACT_OPEN); }
            int btnW = 0; for (Component b : btns) btnW += font.width(b) + 12 + 4;
            int textX = 26;
            int want = Math.max(font.width(title) + textX + 10, Math.max(font.width(t.body()) + textX + 10, btnW + textX + 6));
            int w = Mth.clamp(want, 170, maxW);
            List<FormattedCharSequence> body = font.split(t.body(), w - textX - 8);
            if (body.size() > 3) body = body.subList(0, 3);
            int hgt = 6 + 10 + body.size() * 10 + (hint != null ? 10 : 0) + (btns.isEmpty() ? 0 : 16) + 5;

            y -= hgt + 4;
            int x = 4 - (int) ((w + 12) * (1f - ease));
            int x2 = x + w, y2 = y + hgt;

            // glow + glass
            g.fill(x - 2, y - 2, x2 + 2, y2 + 2, a((int) (18 * k), acc));
            g.fill(x - 1, y - 1, x2 + 1, y2 + 1, a((int) (40 * k), acc));
            g.fillGradient(x, y, x2, y2, a((int) (205 * k), 0x1A2433), a((int) (215 * k), 0x0B1119));
            g.fill(x + 1, y + 1, x2 - 1, y + 2, a((int) (45 * k), 0xFFFFFF));                 // top sheen
            // 1px border
            g.fill(x, y, x2, y + 1, a((int) (170 * k), acc));
            g.fill(x, y2 - 1, x2, y2, a((int) (90 * k), acc));
            g.fill(x, y, x + 1, y2, a((int) (130 * k), acc));
            g.fill(x2 - 1, y, x2, y2, a((int) (130 * k), acc));
            // icon badge
            int ic = ICON_COL[kind];
            int bx = x + 7, by = y + 6;
            g.fill(bx + 1, by, bx + 13, by + 14, a((int) (60 * k), ic));
            g.fill(bx, by + 1, bx + 14, by + 13, a((int) (60 * k), ic));
            g.fill(bx + 1, by + 1, bx + 13, by + 13, a((int) (35 * k), ic));
            String glyph = ICON[kind];
            g.drawString(font, glyph, bx + 7 - font.width(glyph) / 2, by + 3, a(alpha, ic), false);
            // text
            int ty = y + 6;
            g.drawString(font, title, x + textX, ty, a(alpha, 0xFFFFFF), false);
            ty += 11;
            for (FormattedCharSequence line : body) { g.drawString(font, line, x + textX, ty, a(alpha, 0xD2DEE8), false); ty += 10; }
            if (hint != null) { g.drawString(font, hint, x + textX, ty, a(alpha, 0x7F93A6), false); ty += 10; }
            // buttons
            if (!btns.isEmpty()) {
                int bxp = x + textX;
                int byp = ty + 2;
                for (int i = 0; i < btns.size(); i++) {
                    Component b = btns.get(i);
                    int bw = font.width(b) + 12;
                    boolean primary = acts.get(i) == Net.Glass.ACT_CONFIRM;
                    boolean hover = chatOpen && mx >= bxp && mx < bxp + bw && my >= byp && my < byp + 13;
                    int bc = primary ? (kind == Net.Toast.FAIL ? 0xFFB238 : 0x5CFF8A) : acc;
                    g.fill(bxp, byp, bxp + bw, byp + 13, a((int) ((hover ? 110 : 45) * k), bc));
                    g.fill(bxp, byp, bxp + bw, byp + 1, a((int) (160 * k), bc));
                    g.fill(bxp, byp + 12, bxp + bw, byp + 13, a((int) (110 * k), bc));
                    g.fill(bxp, byp, bxp + 1, byp + 13, a((int) (140 * k), bc));
                    g.fill(bxp + bw - 1, byp, bxp + bw, byp + 13, a((int) (140 * k), bc));
                    g.drawString(font, b, bxp + 6, byp + 3, a(alpha, 0xFFFFFF), false);
                    HITS.add(new Hit(bxp, byp, bxp + bw, byp + 13, e, acts.get(i)));
                    bxp += bw + 4;
                }
                if (!chatOpen) {
                    Component tip = Component.translatable("colonyboard.toast.clickhint");
                    int tw = font.width(tip);
                    if (bxp + tw + 4 < x2 - 4) g.drawString(font, tip, x2 - 6 - tw, byp + 3, a((int) (alpha * 0.8f), 0x6F8396), false);
                }
            }
            // time left
            float left = 1f - Math.min(1f, e.age / (float) e.life);
            g.fill(x + 1, y2 - 2, x + 1 + (int) ((w - 2) * left), y2 - 1, a((int) (150 * k), acc));
            HITS.add(new Hit(x, y, x2, y2, e, 0));
        }
    }
}
