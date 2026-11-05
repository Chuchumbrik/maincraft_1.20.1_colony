package ru.wik.colonyboard.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** 1.6.3: yes/no confirmation in the glass style (replaces vanilla ConfirmScreen in J / market). */
public class GlassAsk extends Screen {
    private final BooleanConsumer cb;
    private final Component msg;
    private final int acc;
    private List<FormattedCharSequence> lines = List.of();
    private int px, py, px2, py2;

    public GlassAsk(BooleanConsumer cb, Component title, Component msg) { this(cb, title, msg, Glass.AMBER); }

    public GlassAsk(BooleanConsumer cb, Component title, Component msg, int acc) {
        super(title); this.cb = cb; this.msg = msg; this.acc = acc;
    }

    @Override
    protected void init() {
        int w = Math.min(320, width - 40);
        lines = font.split(msg, w - 24);
        int h = 30 + lines.size() * 10 + 34;
        px = width / 2 - w / 2; px2 = px + w; py = height / 2 - h / 2; py2 = py + h;
        int bw = (w - 30) / 2;
        addRenderableWidget(Glass.btn(CommonComponents.GUI_YES, b -> cb.accept(true), px + 10, py2 - 26, bw, 18).accent(Glass.GREEN));
        addRenderableWidget(Glass.btn(CommonComponents.GUI_NO, b -> cb.accept(false), px2 - 10 - bw, py2 - 26, bw, 18));
    }

    @Override
    public void renderBackground(GuiGraphics g) { Glass.backdrop(g, width, height); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        Glass.panel(g, px, py, px2, py2, acc);
        Glass.header(g, font, title, px, px2, py + 7);
        int y = py + 26;
        for (FormattedCharSequence l : lines) { g.drawString(font, l, (px + px2 - font.width(l)) / 2, y, Glass.a(255, Glass.BODY), false); y += 10; }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void onClose() { cb.accept(false); }

    @Override
    public boolean isPauseScreen() { return false; }
}
