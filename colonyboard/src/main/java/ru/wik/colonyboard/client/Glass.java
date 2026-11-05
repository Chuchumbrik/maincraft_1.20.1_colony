package ru.wik.colonyboard.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 1.6.3: shared "glass" HUD look (style B, same as the toasts) for the J menu, market and pay window:
 * dark translucent panels, thin cyan glow border, top sheen, flat glass buttons with an accent per role.
 */
public final class Glass {
    private Glass() {}

    public static final int CYAN = 0x4FE3FF, GREEN = 0x5CFF8A, AMBER = 0xFFB238, RED = 0xFF5A5A, BLUE = 0x7FDBFF;
    public static final int TEXT = 0xFFFFFF, BODY = 0xD2DEE8, MUTED = 0x7F93A6, DIM = 0x56677A;
    public static final int BG_TOP = 0x1A2433, BG_BOT = 0x0B1119;

    public static int a(int alpha, int rgb) { return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF); }

    /** Darkened, slightly blue-tinted world behind the menu (replaces vanilla grey gradient). */
    public static void backdrop(GuiGraphics g, int w, int h) {
        g.fillGradient(0, 0, w, h, a(150, 0x05080D), a(190, 0x02040A));
    }

    public static void frame(GuiGraphics g, int x, int y, int x2, int y2, int col, int top, int side, int bot) {
        g.fill(x, y, x2, y + 1, a(top, col));
        g.fill(x, y2 - 1, x2, y2, a(bot, col));
        g.fill(x, y + 1, x + 1, y2 - 1, a(side, col));
        g.fill(x2 - 1, y + 1, x2, y2 - 1, a(side, col));
    }

    /** Main glass panel: outer glow, gradient body, sheen, 1px cyan border. */
    public static void panel(GuiGraphics g, int x, int y, int x2, int y2, int acc) {
        g.fill(x - 3, y - 3, x2 + 3, y2 + 3, a(10, acc));
        g.fill(x - 2, y - 2, x2 + 2, y2 + 2, a(18, acc));
        g.fill(x - 1, y - 1, x2 + 1, y2 + 1, a(40, acc));
        g.fillGradient(x, y, x2, y2, a(215, BG_TOP), a(228, BG_BOT));
        g.fill(x + 1, y + 1, x2 - 1, y + 2, a(45, 0xFFFFFF));
        frame(g, x, y, x2, y2, acc, 170, 120, 90);
    }

    /** Inner sub-panel (list area, input strip): lighter, no glow. */
    public static void inset(GuiGraphics g, int x, int y, int x2, int y2) {
        g.fill(x, y, x2, y2, a(110, 0x050A10));
        frame(g, x, y, x2, y2, CYAN, 45, 30, 30);
    }

    /** Thin horizontal accent line that fades to both ends. */
    public static void rule(GuiGraphics g, int x, int x2, int y, int acc, int alpha) {
        int w = x2 - x, seg = Math.max(1, w / 8);
        for (int i = 0; i < 8; i++) {
            float f = 1f - Math.abs(i - 3.5f) / 4f;
            g.fill(x + i * seg, y, i == 7 ? x2 : x + (i + 1) * seg, y + 1, a((int) (alpha * f), acc));
        }
    }

    /** Title bar: bold white title + small cyan diamond + fading rule. */
    public static void header(GuiGraphics g, Font font, Component title, int x, int x2, int y) {
        Component t = title.copy().withStyle(net.minecraft.ChatFormatting.BOLD);
        int tw = font.width(t), cx = (x + x2) / 2;
        g.drawString(font, "◆", cx - tw / 2 - 10, y, a(255, CYAN), false);
        g.drawString(font, t, cx - tw / 2, y, a(255, TEXT), false);
        g.drawString(font, "◆", cx + tw / 2 + 4, y, a(255, CYAN), false);
        rule(g, x + 6, x2 - 6, y + 12, CYAN, 150);
    }

    /** Status pill at the bottom (balances, mode). */
    public static void pill(GuiGraphics g, Font font, String text, int cx, int y, int col) {
        if (text.isEmpty()) return;
        int w = font.width(text) + 16, x = cx - w / 2;
        g.fill(x, y, x + w, y + 13, a(170, BG_BOT));
        frame(g, x, y, x + w, y + 13, col, 110, 80, 70);
        g.drawString(font, text, x + 8, y + 3, a(255, col), false);
    }

    /** Glass button body (also used for in-list row buttons). */
    public static void button(GuiGraphics g, Font font, Component text, int x, int y, int w, int h, int acc,
                              boolean active, boolean hover, boolean selected) {
        button(g, font, text, x, y, w, h, acc, active, hover, selected, false);
    }

    /** {@code side} = vertical sidebar tab: left-aligned text, lit bar on the left edge when selected. */
    public static void button(GuiGraphics g, Font font, Component text, int x, int y, int w, int h, int acc,
                              boolean active, boolean hover, boolean selected, boolean side) {
        int fill = !active && !selected ? 18 : selected ? 70 : hover ? 95 : 38;
        int col = !active && !selected ? DIM : acc;
        g.fill(x, y, x + w, y + h, a(150, 0x0A1018));
        g.fill(x, y, x + w, y + h, a(fill, col));
        frame(g, x, y, x + w, y + h, col, selected ? 230 : hover ? 220 : 150, selected ? 150 : 110, selected ? 150 : 90);
        if (selected && side) { g.fill(x + 1, y + 1, x + 3, y + h - 1, a(255, acc)); g.fill(x + 3, y + 1, x + 8, y + h - 1, a(50, acc)); }
        else if (selected) g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, a(255, acc));     // active-tab underline
        if (hover && active) g.fill(x + 1, y + 1, x + w - 1, y + 2, a(70, 0xFFFFFF));
        int tc = !active && !selected ? 0x6F8396 : TEXT;
        String s = font.plainSubstrByWidth(text.getString(), w - (side ? 14 : 6));
        int tx = side ? x + (selected ? 10 : 8) : x + (w - font.width(s)) / 2;
        g.drawString(font, s, tx, y + (h - 8) / 2, a(255, tc), false);
    }

    /** Vanilla Button with glass rendering. {@code selected} = current tab (drawn lit, not greyed). */
    public static final class Btn extends Button {
        public int accent = CYAN;
        public boolean selected, side;

        public Btn(int x, int y, int w, int h, Component msg, OnPress press) { super(x, y, w, h, msg, press, DEFAULT_NARRATION); }

        public Btn accent(int c) { accent = c; return this; }
        public Btn side() { side = true; return this; }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            button(g, Minecraft.getInstance().font, getMessage(), getX(), getY(), width, height, accent, active, isHovered() && active, selected, side);
        }
    }

    /** J-menu layout: wide panel, tab sidebar on the left, content on the right. */
    public record Layout(int panelL, int panelR, int sideL, int sideR, int left, int right, int step) {
        public static final int TOP = 26;
        public static Layout of(int width, int height, int tabs) {
            int pl = Math.max(6, width / 2 - 245), pr = Math.min(width - 6, width / 2 + 245);
            int sw = Mth.clamp((pr - pl) / 5, 70, 96);
            int sl = pl + 7, sr = sl + sw;
            int step = Mth.clamp((height - TOP - 26) / Math.max(1, tabs), 15, 21);
            return new Layout(pl, pr, sl, sr, sr + 10, pr - 7, step);
        }
        public int tabY(int i) { return TOP + i * step; }
        public void chrome(GuiGraphics g, Font font, Component title, int acc, int listBottom, int height) {
            panel(g, panelL, 3, panelR, height - 3, acc);
            header(g, font, title, panelL, panelR, 7);
            g.fill(sideL - 3, TOP - 3, sideR + 3, listBottom + 1, a(90, 0x050A10));
            g.fill(sideR + 4, TOP - 2, sideR + 5, listBottom, a(70, CYAN));                    // sidebar divider
        }
    }

    public static Btn btn(Component msg, Button.OnPress press, int x, int y, int w, int h) { return new Btn(x, y, w, h, msg, press); }

    /** EditBox inside a glass frame. Rect passed = the visible frame (same as old bordered box). */
    public static final class Box extends EditBox {
        private final int fh, pad;
        public Box(Font font, int x, int y, int w, int h, Component label) {
            super(font, x + 4, y + (h - 8) / 2, w - 8, h - (h - 8) / 2, label);
            fh = h; pad = (h - 8) / 2;
            setBordered(false);
            setTextColor(BODY);
        }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            if (!isVisible()) return;
            int x = getX() - 4, y = getY() - pad, x2 = getX() + width + 4, y2 = y + fh;
            g.fill(x, y, x2, y2, a(170, 0x060B11));
            boolean f = isFocused();
            frame(g, x, y, x2, y2, CYAN, f ? 230 : 90, f ? 170 : 60, f ? 170 : 60);
            if (f) g.fill(x + 1, y2 - 2, x2 - 1, y2 - 1, a(120, CYAN));
            super.renderWidget(g, mx, my, pt);
        }
    }
}
