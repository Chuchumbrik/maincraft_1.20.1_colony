package ru.wik.colonyboard.client;

import com.minecolonies.api.colony.IColonyView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import ru.wik.colonyboard.Net;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Colony market / auctions / warehouse stock (owner+officers). All checks are repeated on the server. */
public class MarketScreen extends Screen {
    private static final int MARKET = 0, AUCTION = 1, STOCK = 2, ROW_H = 22;
    private static final int LIST = 0, UNLIST = 1, BUY = 2, AUC = 3, BID = 4, CANCEL = 5, CLAIM = 6;
    private static int mode = MARKET;
    private static String fLots = "1", fBid = "", fLotSize = "64", fPrice = "10", fListLots = "1", fStep = "5", fMinutes = "30";

    private IColonyView colony;
    private int left, right, listTop, listBottom, ticks;
    private Glass.Layout lay;
    private double scroll;
    private final List<EditBox> boxes = new ArrayList<>();
    private Button claimBtn;

    public MarketScreen() { super(Component.translatable("colonyboard.mkt.title")); }

    /** 1.7: J -> Склад "На рынок": open the market straight on the warehouse stock page. */
    public static MarketScreen stock() { mode = STOCK; return new MarketScreen(); }

    private int colonyId() { return colony == null ? -1 : colony.getID(); }

    private void request() { if (ClientSetup.serverHasMod()) Net.CHANNEL.sendToServer(new Net.MarketRequest(colonyId())); }

    @Override
    protected void init() {
        colony = ClientSetup.findColony();
        request();
        lay = Glass.Layout.of(width, height, 7);
        left = lay.left(); right = lay.right();
        int w = right - left, sw = lay.sideR() - lay.sideL(), th = lay.step() - 2;
        String[] modes = {"colonyboard.mkt.mode.market", "colonyboard.mkt.mode.auction", "colonyboard.mkt.mode.stock"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            Glass.Btn b = addRenderableWidget(Glass.btn(Component.translatable(modes[i]), x -> { mode = m; scroll = 0; rebuildWidgets(); },
                lay.sideL(), lay.tabY(i), sw, th).accent(Glass.AMBER).side());
            b.active = mode != i; b.selected = mode == i;
        }
        claimBtn = addRenderableWidget(Glass.btn(Component.empty(), b -> act(CLAIM, 0, ItemStack.EMPTY, 0, 0, 0, 0), lay.sideL(), lay.tabY(3) + 4, sw, th).accent(Glass.GREEN).side());
        addRenderableWidget(Glass.btn(Component.translatable("colonyboard.refresh"), b -> request(), lay.sideL(), lay.tabY(4) + 4, sw, th).side());
        addRenderableWidget(Glass.btn(Component.translatable("colonyboard.mkt.back"), b -> minecraft.setScreen(new BoardScreen()), lay.sideL(), lay.tabY(5) + 8, sw, th).side());
        boxes.clear();
        if (mode == MARKET) box(left, 120, fLots, v -> fLots = v, "colonyboard.mkt.f.lots");
        else if (mode == AUCTION) box(left, 120, fBid, v -> fBid = v, "colonyboard.mkt.f.bid");
        else {
            int q = w / 5;
            box(left, q - 4, fLotSize, v -> fLotSize = v, "colonyboard.mkt.f.lotsize");
            box(left + q, q - 4, fPrice, v -> fPrice = v, "colonyboard.mkt.f.price");
            box(left + 2 * q, q - 4, fListLots, v -> fListLots = v, "colonyboard.mkt.f.listlots");
            box(left + 3 * q, q - 4, fStep, v -> fStep = v, "colonyboard.mkt.f.step");
            box(left + 4 * q, w - 4 * q - 4, fMinutes, v -> fMinutes = v, "colonyboard.mkt.f.minutes");
        }
        listTop = 68; listBottom = height - 24;
    }

    private void box(int x, int w, String val, java.util.function.Consumer<String> set, String label) {
        EditBox b = new Glass.Box(font, x + 1, 35, w, 16, Component.translatable(label));
        b.setMaxLength(9); b.setFilter(v -> v.matches("\\d{0,9}")); b.setValue(val); b.setResponder(set::accept);
        b.setTooltip(Tooltip.create(Component.translatable(label + ".tip")));
        addRenderableWidget(b); boxes.add(b);
    }

    private static int num(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }

    private void act(int op, long id, ItemStack item, int a, int b, int c, int d) {
        if (!ClientSetup.serverHasMod()) return;
        Net.CHANNEL.sendToServer(new Net.MarketAction(op, colonyId(), id, item, a, b, c, d));
    }

    private void confirm(Component q, Runnable yes) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new GlassAsk(ok -> { if (ok) yes.run(); mc.setScreen(new MarketScreen()); }, Component.translatable("colonyboard.fin.confirm"), q));
    }

    private static Component item(ItemStack s, int n) { return Component.literal(n + "× ").append(s.getHoverName()); }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
    }

    // one rendered/clickable row
    private record RowV(ItemStack icon, Component title, Component detail, Component b1, Runnable a1, Component b2, Runnable a2, int color) {}

    private List<RowV> rows() {
        List<RowV> out = new ArrayList<>();
        Net.MarketData m = ClientState.market;
        if (m == null) return out;
        if (mode == MARKET) {
            for (Net.MListing l : m.listings()) {
                int lots = l.units() / Math.max(1, l.lotSize());
                Component t = item(l.item(), l.lotSize()).copy().append(" — " + l.lotPrice() + " ◎");
                Component d = Component.translatable("colonyboard.mkt.row.listing", l.colonyName(), lots, l.units());
                if (l.mine()) out.add(new RowV(l.item(), t, d, Component.translatable("colonyboard.mkt.unlist"), () -> act(UNLIST, l.id(), ItemStack.EMPTY, 0, 0, 0, 0), null, null, 0xFFE080));
                else out.add(new RowV(l.item(), t, d, Component.translatable("colonyboard.mkt.buy"), () -> {
                    int n = Math.max(1, Math.min(num(fLots), lots));
                    long cost = (long) n * l.lotPrice();
                    confirm(Component.translatable("colonyboard.mkt.confirm.buy", item(l.item(), n * l.lotSize()), cost, l.colonyName()),
                        () -> act(BUY, l.id(), ItemStack.EMPTY, n, l.lotPrice(), 0, 0));
                }, null, null, 0xFFFFFF));
            }
        } else if (mode == AUCTION) {
            long age = System.currentTimeMillis() - ClientState.marketAt;
            for (Net.MLot l : m.lots()) {
                long left = l.msLeft() - age;
                Component t = item(l.item(), l.units()).copy().append(" — " + time(left));
                Component d = l.bid() > 0 ? Component.translatable("colonyboard.mkt.row.lot.bid", l.colonyName(), l.bid(), l.bidder(), l.minNext())
                                          : Component.translatable("colonyboard.mkt.row.lot.none", l.colonyName(), l.minNext());
                int color = l.leading() ? 0x80FF80 : (left < 60_000 ? 0xFF8060 : 0xFFFFFF);
                Component b1 = l.leading() ? null : Component.translatable("colonyboard.mkt.bid");
                Runnable a1 = l.leading() ? null : () -> {
                    int amt = Math.max(l.minNext(), num(fBid));
                    confirm(Component.translatable("colonyboard.mkt.confirm.bid", amt, item(l.item(), l.units()), l.colonyName()),
                        () -> act(BID, l.id(), ItemStack.EMPTY, amt, 0, 0, 0));
                };
                if (l.mine() && l.bid() == 0) out.add(new RowV(l.item(), t, d, b1, a1, Component.translatable("colonyboard.mkt.cancel"), () -> act(CANCEL, l.id(), ItemStack.EMPTY, 0, 0, 0, 0), color));
                else out.add(new RowV(l.item(), t, d, b1, a1, null, null, color));
            }
        } else {
            for (Net.MStock s : m.stock()) {
                Component t = s.item().getHoverName();
                Component d = Component.translatable("colonyboard.mkt.row.stock", s.count());
                out.add(new RowV(s.item(), t, d,
                    Component.translatable("colonyboard.mkt.sell"), () -> {
                        int size = num(fLotSize), lots = num(fListLots), price = num(fPrice);
                        if (size <= 0 || lots <= 0 || price <= 0) return;
                        confirm(Component.translatable("colonyboard.mkt.confirm.list", item(s.item(), size * lots), price, size),
                            () -> act(LIST, 0, s.item(), size, lots, price, 0));
                    },
                    Component.translatable("colonyboard.mkt.auction"), () -> {
                        int qty = num(fLotSize), start = num(fPrice), step = Math.max(1, num(fStep)), min = num(fMinutes);
                        if (qty <= 0 || start <= 0 || min <= 0) return;
                        confirm(Component.translatable("colonyboard.mkt.confirm.auction", item(s.item(), qty), start, step, min),
                            () -> act(AUC, 0, s.item(), qty, start, step, min));
                    }, 0xFFFFFF));
            }
        }
        return out;
    }

    @Override
    public void tick() {
        if (++ticks % 100 == 0) request();
    }

    private String deny(Net.MarketData m) {
        if (!ClientSetup.serverHasMod()) return "colonyboard.sum.noserver";
        if (m == null) return "colonyboard.mkt.loading";
        return mode == STOCK ? (m.sellDeny().isEmpty() ? m.deny() : m.sellDeny()) : (mode == AUCTION ? m.aucDeny() : m.deny());
    }

    @Override
    public void renderBackground(GuiGraphics g) { Glass.backdrop(g, width, height); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        lay.chrome(g, font, title, Glass.AMBER, listBottom, height);
        Glass.inset(g, left - 3, 22, right + 3, 66);
        Glass.inset(g, left - 1, listTop - 1, right + 1, listBottom + 1);
        Net.MarketData m = ClientState.market;
        claimBtn.setMessage(Component.translatable("colonyboard.mkt.claim", m == null ? 0 : m.pending()));
        claimBtn.active = m != null && m.pending() > 0;
        String help = Component.translatable(mode == MARKET ? "colonyboard.mkt.help.market" : mode == AUCTION ? "colonyboard.mkt.help.auction" : "colonyboard.mkt.help.stock").getString();
        g.drawString(font, font.plainSubstrByWidth(help, right - left), left, 56, Glass.MUTED);
        if (mode == STOCK) {
            String[] keys = {"lotsize", "price", "listlots", "step", "minutes"};
            for (int i = 0; i < boxes.size() && i < keys.length; i++)
                g.drawString(font, Component.translatable("colonyboard.mkt.f." + keys[i]), boxes.get(i).getX() - 4, 25, 0x9FB4C8);
        } else {
            String s = Component.translatable(mode == MARKET ? "colonyboard.mkt.f.lots" : "colonyboard.mkt.f.bid").getString();
            g.drawString(font, s, left + 128, 39, 0x9FB4C8);
        }
        super.render(g, mx, my, pt);
        String d = deny(m);
        List<RowV> rows = rows();
        g.enableScissor(left, listTop, right, listBottom);
        if (!d.isEmpty()) g.drawCenteredString(font, Component.translatable(d), (left + right) / 2, listTop + 6, Glass.RED);
        else if (rows.isEmpty()) g.drawCenteredString(font, Component.translatable("colonyboard.mkt.empty"), (left + right) / 2, listTop + 6, Glass.GREEN);
        ItemStack hover = ItemStack.EMPTY;
        int base = d.isEmpty() ? listTop : listTop + 18;
        for (int i = 0; i < rows.size(); i++) {
            int y = base + i * ROW_H - (int) scroll;
            if (y + ROW_H < listTop || y > listBottom) continue;
            RowV r = rows.get(i);
            g.fill(left, y, right, y + ROW_H - 1, Glass.a(i % 2 == 0 ? 70 : 40, 0x16222F));
            if (mx >= left && mx < right && my >= y && my < y + ROW_H - 1 && my >= listTop && my < listBottom) g.fill(left, y, right, y + ROW_H - 1, Glass.a(28, Glass.AMBER));
            g.fill(left + 3, y + ROW_H - 1, right - 3, y + ROW_H, Glass.a(28, Glass.CYAN));
            g.renderItem(r.icon(), left + 3, y + 3);
            if (mx >= left + 3 && mx < left + 19 && my >= y + 3 && my < y + 19 && my >= listTop && my < listBottom) hover = r.icon();
            int textRight = right - (r.b2() != null ? 126 : 64);
            g.drawString(font, font.plainSubstrByWidth(r.title().getString(), textRight - left - 24), left + 24, y + 2, r.color());
            g.drawString(font, font.plainSubstrByWidth(r.detail().getString(), textRight - left - 24), left + 24, y + 12, 0x8FA3B6);
            if (d.isEmpty()) {
                if (r.b1() != null) button(g, r.b1(), right - 60, y, mx, my, Glass.GREEN);
                if (r.b2() != null) button(g, r.b2(), right - 122, y, mx, my, Glass.AMBER);
            }
        }
        g.disableScissor();
        if (!hover.isEmpty()) g.renderTooltip(font, hover, mx, my);
        if (m != null) {
            StringBuilder sb = new StringBuilder();
            if (m.wallet() >= 0) sb.append(Component.translatable("colonyboard.sum.wallet").getString()).append(": ").append(String.format(Locale.ROOT, "%.0f", m.wallet())).append(" ◎   ");
            if (m.treasury() >= 0) sb.append(Component.translatable("colonyboard.sum.treasury").getString()).append(": ").append(m.treasury()).append(" ◎   ");
            sb.append(Component.translatable(m.manager() ? "colonyboard.fin.role.manager" : "colonyboard.mkt.role.buyer").getString());
            Glass.pill(g, font, sb.toString().trim(), (left + right) / 2, height - 19, Glass.GREEN);
        }
    }

    private void button(GuiGraphics g, Component text, int x, int y, int mx, int my, int acc) {
        boolean hov = mx >= x && mx < x + 56 && my >= y + 3 && my < y + 18 && my >= listTop && my < listBottom;
        Glass.button(g, font, text, x, y + 3, 56, 15, acc, true, hov, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (my < listTop || my >= listBottom || !deny(ClientState.market).isEmpty()) return false;
        List<RowV> rows = rows();
        int i = (int) ((my - listTop + scroll) / ROW_H);
        if (i < 0 || i >= rows.size()) return false;
        RowV r = rows.get(i);
        int y = listTop + i * ROW_H - (int) scroll;
        if (my < y + 3 || my >= y + 18) return false;
        if (r.a1() != null && mx >= right - 60 && mx < right - 4) { r.a1().run(); return true; }
        if (r.a2() != null && mx >= right - 122 && mx < right - 66) { r.a2().run(); return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int max = Math.max(0, rows().size() * ROW_H - (listBottom - listTop));
        scroll = Math.max(0, Math.min(max, scroll - delta * ROW_H));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        boolean typing = boxes.stream().anyMatch(EditBox::isFocused);
        if (!typing && ClientSetup.OPEN.matches(key, sc)) { onClose(); return true; }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
