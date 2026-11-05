package ru.wik.colonyboard.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.wik.colonyboard.Net;
import ru.wik.colonyboard.server.Fulfil;

import java.util.Locale;

/** Shows the server quote and lets the player choose who pays: colony treasury or own wallet (1.5). */
public class ConfirmScreen extends Screen {
    private final Screen parent;
    private final Net.Quote q;
    private double scroll;
    private int buyable;

    public ConfirmScreen(Screen parent, Net.Quote q) {
        super(Component.translatable("colonyboard.confirm.title"));
        this.parent = parent; this.q = q;
        for (Net.QuoteLine l : q.lines()) if (l.reason().isEmpty()) buyable++;
    }

    private static String money(double v) { return v < 0 ? "?" : String.format(Locale.ROOT, "%.0f", Math.floor(v)); }

    /** "" = this source can pay now, else lang key why not. */
    private String why(int src) {
        String d = src == Fulfil.SRC_TREASURY ? q.denyTreasury() : q.denyWallet();
        if (!d.isEmpty()) return d;
        if (q.ids().isEmpty()) return "colonyboard.confirm.nothing";
        double bal = src == Fulfil.SRC_TREASURY ? q.treasury() : q.wallet();
        if (bal < q.total()) return src == Fulfil.SRC_TREASURY ? "colonyboard.reason.funds.treasury" : "colonyboard.reason.funds.wallet";
        return "";
    }

    private void send(int src) {
        Net.CHANNEL.sendToServer(new Net.FulfilRequest(q.colonyId(), q.ids(), q.total(), src));
        onClose();
    }

    @Override
    protected void init() {
        int y = height - 30;
        if (q.free()) {
            Button yes = addRenderableWidget(Glass.btn(Component.translatable("colonyboard.confirm.free"), b -> send(Fulfil.SRC_TREASURY),
                width / 2 - 154, y, 150, 20).accent(Glass.GREEN));
            yes.active = !q.ids().isEmpty();
            addRenderableWidget(Glass.btn(Component.translatable("gui.cancel"), b -> onClose(), width / 2 + 4, y, 150, 20));
            return;
        }
        int bw = Math.min(150, (width - 24) / 3);
        int x0 = width / 2 - (bw * 3 + 8) / 2;
        Button t = addRenderableWidget(Glass.btn(Component.translatable("colonyboard.confirm.treasury", money(q.treasury())), b -> send(Fulfil.SRC_TREASURY),
            x0, y, bw, 20).accent(Glass.GREEN));
        Button w = addRenderableWidget(Glass.btn(Component.translatable("colonyboard.confirm.wallet", money(q.wallet())), b -> send(Fulfil.SRC_WALLET),
            x0 + bw + 4, y, bw, 20).accent(Glass.AMBER));
        addRenderableWidget(Glass.btn(Component.translatable("gui.cancel"), b -> onClose(), x0 + 2 * (bw + 4), y, bw, 20));
        String wt = why(Fulfil.SRC_TREASURY), ww = why(Fulfil.SRC_WALLET);
        t.active = wt.isEmpty(); w.active = ww.isEmpty();
        t.setTooltip(Tooltip.create(wt.isEmpty() ? Component.translatable("colonyboard.confirm.treasury.tip", q.total())
            : Component.translatable("colonyboard.confirm.cant", Component.translatable(wt))));
        w.setTooltip(Tooltip.create(ww.isEmpty() ? Component.translatable("colonyboard.confirm.wallet.tip", q.total())
            : Component.translatable("colonyboard.confirm.cant", Component.translatable(ww))));
    }

    @Override
    public void renderBackground(GuiGraphics g) { Glass.backdrop(g, width, height); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        int pl = Math.max(6, width / 2 - 236), pr = Math.min(width - 6, width / 2 + 236);
        Glass.panel(g, pl, 3, pr, height - 3, Glass.CYAN);
        g.drawCenteredString(font, title, width / 2, 7, 0xFFFFFF);
        Glass.rule(g, pl + 6, pr - 6, 17, Glass.CYAN, 150);
        Glass.inset(g, width / 2 - 204, 42, width / 2 + 204, height - 46);
        boolean anyPay = q.free() ? !q.ids().isEmpty() : (why(Fulfil.SRC_TREASURY).isEmpty() || why(Fulfil.SRC_WALLET).isEmpty());
        Component info;
        if (q.ids().isEmpty()) info = Component.translatable("colonyboard.confirm.nothing");
        else if (q.free()) info = Component.translatable("colonyboard.confirm.freeinfo", q.ids().size());
        else info = Component.translatable("colonyboard.confirm.info", q.ids().size(), q.total());
        g.drawCenteredString(font, info, width / 2, 21, anyPay ? Glass.GREEN : Glass.RED);
        if (!q.free()) {
            Component bal = Component.translatable("colonyboard.confirm.balances", money(q.treasury()), money(q.wallet()));
            g.drawCenteredString(font, bal, width / 2, 31, 0xFFD27A);
        }
        int skipped = q.lines().size() - buyable;
        int top = 45, bottom = height - 48, left = width / 2 - 200, right = width / 2 + 200;
        g.enableScissor(left, top, right, bottom);
        int y = top - (int) scroll;
        for (Net.QuoteLine l : q.lines()) {
            boolean ok = l.reason().isEmpty();
            String price = !ok ? Component.translatable(l.reason()).getString()
                : (q.free() ? Component.translatable("colonyboard.confirm.gratis").getString() : l.cost() + " ◎");
            int pw = font.width(price);
            g.drawString(font, font.plainSubstrByWidth(l.qty() + "× " + l.name().getString(), Math.max(60, right - left - pw - 8)), left, y, ok ? Glass.TEXT : 0x6F8396);
            g.drawString(font, price, right - pw, y, ok ? 0xFFD27A : 0xFF8060);
            y += 11;
        }
        g.disableScissor();
        Component foot = skipped > 0 ? Component.translatable("colonyboard.confirm.skipped", skipped)
            : Component.translatable(q.free() ? "colonyboard.confirm.hint.free" : "colonyboard.confirm.hint");
        g.drawCenteredString(font, foot, width / 2, height - 43, Glass.MUTED);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        scroll = Math.max(0, Math.min(Math.max(0, q.lines().size() * 11 - (height - 92)), scroll - d * 11));
        return true;
    }

    @Override
    public void onClose() { minecraft.setScreen(parent instanceof BoardScreen ? new BoardScreen() : parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
