package ru.wik.colonyboard.client;

import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import ru.wik.colonyboard.ClientConfig;
import ru.wik.colonyboard.Net;
import ru.wik.colonyboard.logic.TextWrap;
import ru.wik.colonyboard.model.Filter;
import ru.wik.colonyboard.model.Row;
import ru.wik.colonyboard.model.Search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * J menu. 1.7: dashboard ("what is on fire"), request search/kind filter, build queue (priority / buy for one build),
 * warehouse shortages / surplus, citizens, taxes, war readiness, favourites. Same glass look as 1.6.3.
 */
public class BoardScreen extends Screen {
    private static final String[] TABS = {"dash", "complaints", "requests", "construction", "stock", "citizens", "finance", "war", "favorites", "market"};
    private static final int DASH = 0, COMPL = 1, REQ = 2, BUILD = 3, STOCK = 4, CIT = 5, FIN = 6, WAR = 7, FAV = 8, MARKET = 9;
    private static final String[] SRC = {"wallet", "tax", "inventory"};
    private static int finSource = 0;
    private static String finAmount = "100", finTarget = "";
    private static int tab = DASH;
    private static boolean blockingOnly = false;
    private static BlockPos buildingFilter = null;
    private static String reqQuery = "", citQuery = "", stockQuery = "";
    private static int reqKind = 0, citCat = 0;
    /** Build tab: 0 queue, 1 placed and not started, 2 native upgrade. */
    private static int buildSec = 0;
    private static final Set<Long> BUILD_PENDING = new HashSet<>();
    private static final int ROW_H = 22;
    private static final int SEC_QUEUE = 0, SEC_PLACED = 1, SEC_UPGRADE = 2;
    /** 1.6: "Open request" from a toast -> Requests tab, scroll to this row and flash it. */
    private static java.util.UUID focusId;
    private static long focusUntil;
    private java.util.UUID flashId;
    private long flashUntil;

    public static void openRequest(java.util.UUID id) {
        tab = REQ; buildingFilter = null; blockingOnly = false; reqQuery = ""; reqKind = 0;
        focusId = id; focusUntil = System.currentTimeMillis() + 6000;
        Minecraft.getInstance().setScreen(new BoardScreen());
    }

    private IColonyView colony;
    private List<Row> rows = new ArrayList<>();
    private List<IBuildingView> buildings = new ArrayList<>();
    private double scroll;
    private int ticks;
    private Glass.Btn buildingBtn, blockingBtn, fulfilAllBtn, stuckBtn, kindBtn, catBtn, budgetBtn;
    private Glass.Btn autoBtn;
    private Glass.Btn tierModeBtn, tierFloorBtn;
    private Button finSrcBtn, finDepBtn, finWdBtn, finClaimBtn, finDebtBtn, finSendBtn;
    private EditBox finAmountBox, finTargetBox;
    private final List<Glass.Btn> favBtns = new ArrayList<>();
    private int listTop, listBottom, left, right;
    private Glass.Layout lay;
    private List<BuildLine> buildLines = List.of();
    private int buildContentH;

    public BoardScreen() { super(Component.translatable("colonyboard.tab.dash")); }

    private int cid() { return colony == null ? -1 : colony.getID(); }

    @Override
    protected void init() {
        colony = ClientSetup.findColony();
        ClientSetup.requestSync(cid());
        ClientSetup.requestDash(cid());
        ClientSetup.requestTier(cid());
        if (tab == BUILD) ClientSetup.requestBuild(cid());
        lay = Glass.Layout.of(width, height, TABS.length + 1);
        left = lay.left(); right = lay.right();
        int sw = lay.sideR() - lay.sideL(), th = lay.step() - 2;
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            Glass.Btn b = Glass.btn(Component.translatable("colonyboard.tab." + TABS[i]), btn -> {
                    if (t == MARKET) { minecraft.setScreen(new MarketScreen()); return; }
                    go(t); },
                lay.sideL(), lay.tabY(i), sw, th).side();
            b.active = tab != i; b.selected = tab == i;
            if (t == MARKET) b.accent(Glass.AMBER);
            if (t == DASH) b.accent(Glass.GREEN);
            addRenderableWidget(b);
        }
        addRenderableWidget(Glass.btn(Component.translatable("colonyboard.refresh"), btn -> {
            ClientSetup.requestSync(cid()); ClientSetup.requestDash(cid()); ClientSetup.requestBuild(cid()); refresh(); }, lay.sideL(), lay.tabY(TABS.length) + 4, sw, th));
        buildingBtn = blockingBtn = fulfilAllBtn = stuckBtn = kindBtn = catBtn = budgetBtn = autoBtn = tierModeBtn = tierFloorBtn = null;
        finAmountBox = finTargetBox = null; finSrcBtn = null;
        favBtns.clear();
        int w = right - left, third = w / 3, q = w / 4;
        listTop = 26;
        switch (tab) {
            case DASH -> {
                stuckBtn = add(Glass.btn(Component.empty(), b -> buyStuck(), left, 26, w, 18).accent(Glass.GREEN));
                List<String> favs = favorites();
                if (favs.isEmpty()) {
                    add(Glass.btn(Component.translatable("colonyboard.fav.empty"), b -> go(FAV), left, 48, w, 18).accent(Glass.AMBER));
                } else {
                    int n = favs.size(), bw = w / n;
                    for (int i = 0; i < n; i++) {
                        final String id = favs.get(i);
                        Glass.Btn fb = add(Glass.btn(Component.literal("★ ").append(Component.translatable("colonyboard.fav.a." + id)), b -> runAction(id),
                            left + i * bw, 48, i == n - 1 ? w - i * bw : bw - 2, 18).accent(Glass.AMBER));
                        favBtns.add(fb);
                    }
                }
                listTop = 72;
            }
            case COMPL -> { filterRow(w, third, false); listTop = 50; }
            case REQ -> {
                filterRow(w, third, true);
                Glass.Box sb = add(new Glass.Box(font, left + 1, 49, 2 * q - 4, 16, Component.translatable("colonyboard.search")));
                sb.setMaxLength(40); sb.setValue(reqQuery); sb.setHint(Component.translatable("colonyboard.search.hint"));
                sb.setResponder(v -> { reqQuery = v; refresh(); });
                kindBtn = add(Glass.btn(Component.empty(), b -> { reqKind = (reqKind + 1) % Collector.KINDS.length; refresh(); }, left + 2 * q, 48, q - 2, 18));
                stuckBtn = add(Glass.btn(Component.translatable("colonyboard.dash.buystuck.short"), b -> buyStuck(), left + 3 * q, 48, w - 3 * q, 18).accent(Glass.GREEN));
                listTop = 72;
            }
            case BUILD -> listTop = layBuildSections();
            case STOCK -> {
                Glass.Box sb = add(new Glass.Box(font, left + 1, 27, 2 * q - 4, 16, Component.translatable("colonyboard.search")));
                sb.setMaxLength(40); sb.setValue(stockQuery); sb.setHint(Component.translatable("colonyboard.search.hint"));
                sb.setResponder(v -> { stockQuery = v; refresh(); });
                add(Glass.btn(Component.translatable("colonyboard.stock.market"), b -> minecraft.setScreen(MarketScreen.stock()), left + 2 * q, 26, w - 2 * q, 18).accent(Glass.AMBER));
                listTop = 50;
            }
            case CIT -> {
                catBtn = add(Glass.btn(Component.empty(), b -> { citCat = (citCat + 1) % Collector.CAT_N; scroll = 0; refresh(); }, left, 26, 2 * q - 2, 18));
                Glass.Box sb = add(new Glass.Box(font, left + 2 * q + 1, 27, w - 2 * q - 2, 16, Component.translatable("colonyboard.search")));
                sb.setMaxLength(40); sb.setValue(citQuery); sb.setHint(Component.translatable("colonyboard.search.hint"));
                sb.setResponder(v -> { citQuery = v; refresh(); });
                listTop = 50;
            }
            case FIN -> { initFinance(); listTop = 138; }
            default -> listTop = 26;
        }
        listBottom = height - 24;
        refresh();
    }

    private <T extends AbstractWidget> T add(T w) { return addRenderableWidget(w); }

    private void go(int t) { tab = t; scroll = 0; if (t == STOCK || t == WAR || t == FIN || t == DASH || t == BUILD) ClientSetup.requestDash(cid()); if (t == FIN) ClientSetup.requestTier(cid()); if (t == BUILD) ClientSetup.requestBuild(cid()); rebuildWidgets(); }

    private int layBuildSections() {
        String[] keys = {"colonyboard.build.sec.queue", "colonyboard.build.sec.placed", "colonyboard.build.sec.upgrade"};
        int w = right - left, x = left, y = 26;
        for (int i = 0; i < keys.length; i++) {
            Component label = Component.translatable(keys[i]);
            int bw = Math.min(w, font.width(label) + 16);
            if (x > left && x + bw > right) { x = left; y += 20; }
            final int sec = i;
            Glass.Btn b = add(Glass.btn(label, btn -> { buildSec = sec; scroll = 0; refresh(); }, x, y, bw, 18)
                .accent(buildSec == i ? Glass.GREEN : Glass.CYAN));
            b.selected = buildSec == i;
            b.active = buildSec != i;
            x += bw + 4;
        }
        return y + 24;
    }

    private void filterRow(int w, int third, boolean withFulfil) {
        int bw = withFulfil ? third : w / 2;
        buildingBtn = add(Glass.btn(Component.empty(), btn -> cycleBuilding(), left, 26, bw - 2, 18));
        blockingBtn = add(Glass.btn(Component.empty(), btn -> { blockingOnly = !blockingOnly; refresh(); }, left + bw, 26, withFulfil ? bw - 2 : w - bw, 18));
        if (withFulfil) fulfilAllBtn = add(Glass.btn(Component.translatable("colonyboard.fulfil.all"), btn -> fulfil(visibleRequestIds()),
            left + 2 * third, 26, w - 2 * third, 18).accent(Glass.GREEN));
    }

    private void initFinance() {
        int w = right - left, q = w / 4;
        finAmountBox = add(new Glass.Box(font, left + 1, 27, q - 4, 16, Component.translatable("colonyboard.fin.amount")));
        finAmountBox.setMaxLength(10); finAmountBox.setValue(finAmount); finAmountBox.setFilter(v -> v.matches("\\d{0,10}"));
        finAmountBox.setResponder(v -> { finAmount = v; updateFinance(); });
        finSrcBtn = add(Glass.btn(Component.empty(), b -> { finSource = (finSource + 1) % 3; updateFinance(); }, left + q, 26, q - 2, 18));
        finDepBtn = add(Glass.btn(Component.translatable("colonyboard.fin.deposit"), b -> fin(0), left + 2 * q, 26, q - 2, 18).accent(Glass.GREEN));
        finWdBtn = add(Glass.btn(Component.translatable("colonyboard.fin.withdraw"), b -> fin(1), left + 3 * q, 26, w - 3 * q, 18).accent(Glass.AMBER));
        finClaimBtn = add(Glass.btn(Component.translatable("colonyboard.fin.claim"), b -> fin(2), left, 48, w / 2 - 2, 18).accent(Glass.GREEN));
        finDebtBtn = add(Glass.btn(Component.translatable("colonyboard.fin.debt"), b -> fin(3), left + w / 2, 48, w - w / 2, 18).accent(Glass.RED));
        finTargetBox = add(new Glass.Box(font, left + 1, 71, 2 * q - 4, 16, Component.translatable("colonyboard.fin.player")));
        finTargetBox.setMaxLength(16); finTargetBox.setValue(finTarget); finTargetBox.setResponder(v -> { finTarget = v; updateFinance(); });
        finTargetBox.setHint(Component.translatable("colonyboard.fin.player"));
        add(Glass.btn(Component.translatable("colonyboard.fin.next"), b -> cyclePlayer(), left + 2 * q, 70, q - 2, 18));
        finSendBtn = add(Glass.btn(Component.translatable("colonyboard.fin.send"), b -> fin(4), left + 3 * q, 70, w - 3 * q, 18).accent(Glass.AMBER));
        autoBtn = add(Glass.btn(Component.empty(), b -> toggleAuto(), left, 92, 3 * q - 2, 18));
        budgetBtn = add(Glass.btn(Component.empty(), b -> setBudget(), left + 3 * q, 92, w - 3 * q, 18).accent(Glass.AMBER));
        tierModeBtn = add(Glass.btn(Component.empty(), b -> cycleTierMode(), left, 114, 3 * q - 2, 18).accent(Glass.CYAN));
        tierFloorBtn = add(Glass.btn(Component.empty(), b -> cycleTierFloor(), left + 3 * q, 114, w - 3 * q, 18).accent(Glass.AMBER));
    }

    private void cyclePlayer() {
        var c = Minecraft.getInstance().getConnection();
        if (c == null || Minecraft.getInstance().player == null) return;
        String me = Minecraft.getInstance().player.getGameProfile().getName();
        List<String> names = new ArrayList<>();
        for (var i : c.getOnlinePlayers()) if (!i.getProfile().getName().equals(me)) names.add(i.getProfile().getName());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) return;
        int idx = names.indexOf(finTarget);
        finTarget = names.get((idx + 1) % names.size());
        finTargetBox.setValue(finTarget);
    }

    private static String finDeny(int op) {
        var st = ClientState.stats;
        if (!ClientSetup.serverHasMod() || st == null || st.finDeny().size() <= op) return "colonyboard.sum.noserver";
        return st.finDeny().get(op);
    }

    private int amount() { try { return Integer.parseInt(finAmount); } catch (Exception e) { return 0; } }

    private void fin(int op) {
        if (colony == null || !finDeny(op).isEmpty()) return;
        int amt = amount();
        if (op != 2 && amt <= 0) return;
        var msg = new Net.FinanceAction(colony.getID(), op, op == 2 ? 0 : amt, finSource, op == 4 ? finTarget.trim() : "");
        Minecraft mc = Minecraft.getInstance();
        if (op == 1 || op == 4) {
            Component q = op == 1 ? Component.translatable("colonyboard.fin.confirm.withdraw", amt, Component.translatable("colonyboard.fin.src." + SRC[finSource]))
                                  : Component.translatable("colonyboard.fin.confirm.send", amt, finTarget.trim());
            Screen self = this;
            mc.setScreen(new GlassAsk(yes -> {
                if (yes) Net.CHANNEL.sendToServer(msg);
                mc.setScreen(self);
            }, Component.translatable("colonyboard.fin.confirm"), q));
        } else Net.CHANNEL.sendToServer(msg);
    }

    private boolean autoOk() {
        var a = ClientState.auto;
        return colony != null && a != null && a.colonyId() == colony.getID() && a.available() && a.deny().isEmpty();
    }

    private void toggleAuto() {
        if (!autoOk()) return;
        Net.AUTO.sendToServer(new Net.AutoToggle(colony.getID(), !ClientState.auto.on()));
    }

    private void setBudget() {
        int amt = amount();
        if (!autoOk() || !ClientSetup.serverHasDash() || amt < 0) return;
        Minecraft mc = Minecraft.getInstance();
        Screen self = this;
        mc.setScreen(new GlassAsk(yes -> {
            if (yes) Net.DASH.sendToServer(new Net.DashAction(colony.getID(), Net.DashAction.BUDGET, amt, 0));
            mc.setScreen(self);
        }, Component.translatable("colonyboard.fin.confirm"), Component.translatable("colonyboard.budget.confirm", amt)));
    }

    private void updateAuto() {
        if (autoBtn == null) return;
        var a = ClientState.auto;
        boolean ok = a != null && colony != null && a.colonyId() == colony.getID() && a.available();
        if (!ok) {
            autoBtn.setMessage(Component.translatable("colonyboard.auto.unavailable"));
            autoBtn.accent = Glass.CYAN; autoBtn.selected = false;
            autoBtn.active = false;
            autoBtn.setTooltip(Tooltip.create(Component.translatable("colonyboard.auto.unavailable.hover")));
        } else {
            autoBtn.setMessage(a.on() ? Component.translatable("colonyboard.auto.on", a.spent(), a.budget())
                                      : Component.translatable("colonyboard.auto.off"));
            autoBtn.active = a.deny().isEmpty();
            autoBtn.accent = a.on() ? Glass.GREEN : Glass.CYAN; autoBtn.selected = a.on() && autoBtn.active;
            autoBtn.setTooltip(Tooltip.create(a.deny().isEmpty()
                ? Component.translatable(a.on() ? "colonyboard.auto.hover.on" : "colonyboard.auto.hover.off")
                : Component.translatable(a.deny())));
        }
        if (budgetBtn != null) {
            budgetBtn.setMessage(Component.translatable("colonyboard.budget.btn", amount()));
            budgetBtn.active = ok && autoOk() && ClientSetup.serverHasDash();
            budgetBtn.setTooltip(Tooltip.create(Component.translatable(!ClientSetup.serverHasDash() ? "colonyboard.dash.noserver"
                : ok && !a.deny().isEmpty() ? a.deny() : "colonyboard.budget.hover")));
        }
    }

    private Net.TierState tier() {
        var t = ClientState.tier;
        return t != null && colony != null && t.colonyId() == colony.getID() ? t : null;
    }

    private static String floorName(int f) {
        return switch (f) {
            case 1 -> "stone";
            case 2 -> "iron";
            case 3 -> "diamond";
            default -> "off";
        };
    }

    private void cycleTierMode() {
        var t = tier();
        if (colony == null || t == null || !t.manager() || !ClientSetup.serverHasTier()) return;
        int next = t.mode() == 0 ? 1 : 0;
        Net.TIER.sendToServer(new Net.TierSet(colony.getID(), Net.TierSet.MODE, next, 0L));
    }

    private void cycleTierFloor() {
        var t = tier();
        if (colony == null || t == null || !t.manager() || !ClientSetup.serverHasTier()) return;
        if (t.mode() != 0) return; // colony floor only in colony mode
        int next = (t.colonyFloor() + 1) % 4;
        Net.TIER.sendToServer(new Net.TierSet(colony.getID(), Net.TierSet.COLONY_FLOOR, next, 0L));
    }

    private void setHutFloor(long pos, int floor) {
        if (colony == null || !ClientSetup.serverHasTier()) return;
        var t = tier();
        if (t == null || !t.manager()) return;
        Net.TIER.sendToServer(new Net.TierSet(colony.getID(), Net.TierSet.HUT_FLOOR, floor, pos));
    }

    private void updateTier() {
        if (tierModeBtn == null) return;
        var t = tier();
        boolean ok = t != null && ClientSetup.serverHasTier();
        if (!ok) {
            tierModeBtn.setMessage(Component.translatable("colonyboard.tier.unavailable"));
            tierModeBtn.active = false;
            tierFloorBtn.setMessage(Component.translatable("colonyboard.tier.floor.off"));
            tierFloorBtn.active = false;
            tierModeBtn.setTooltip(Tooltip.create(Component.translatable("colonyboard.tier.unavailable.hover")));
            tierFloorBtn.setTooltip(Tooltip.create(Component.translatable("colonyboard.tier.unavailable.hover")));
            return;
        }
        boolean mgr = t.manager();
        tierModeBtn.setMessage(Component.translatable(t.mode() == 1 ? "colonyboard.tier.mode.hut" : "colonyboard.tier.mode.colony"));
        tierModeBtn.active = mgr;
        tierModeBtn.selected = t.mode() == 1;
        tierModeBtn.setTooltip(Tooltip.create(Component.translatable(mgr ? "colonyboard.tier.mode.hover" : "colonyboard.reason.rights")));
        if (t.mode() == 0) {
            tierFloorBtn.setMessage(Component.translatable("colonyboard.tier.colonyfloor", Component.translatable("colonyboard.tier.floor." + floorName(t.colonyFloor()))));
            tierFloorBtn.active = mgr;
            tierFloorBtn.setTooltip(Tooltip.create(Component.translatable("colonyboard.tier.floor.hover", t.thLevel())));
        } else {
            tierFloorBtn.setMessage(Component.translatable("colonyboard.tier.perhut.hint"));
            tierFloorBtn.active = false;
            tierFloorBtn.setTooltip(Tooltip.create(Component.translatable("colonyboard.tier.perhut.hover")));
        }
    }

    private void updateFinance() {
        if (finSrcBtn == null) return;
        updateAuto();
        updateTier();
        finSrcBtn.setMessage(Component.translatable("colonyboard.fin.source", Component.translatable("colonyboard.fin.src." + SRC[finSource])));
        int amt = amount();
        Button[] bs = {finDepBtn, finWdBtn, finClaimBtn, finDebtBtn, finSendBtn};
        var st = ClientState.stats;
        for (int op = 0; op < bs.length; op++) {
            String d = finDeny(op);
            boolean ok = d.isEmpty() && (op == 2 || amt > 0);
            if (op == 3 && st != null && st.tax() >= 0) ok = false;
            if (op == 4 && finTarget.isBlank()) ok = false;
            bs[op].active = ok;
            bs[op].setTooltip(d.isEmpty() ? null : Tooltip.create(Component.translatable(d)));
        }
    }

    static boolean canFulfil() {
        var st = ClientState.stats;
        return ClientSetup.serverHasMod() && st != null && st.deny().isEmpty();
    }

    private Net.DashData dash() {
        var d = ClientState.dash;
        return d != null && colony != null && d.colonyId() == colony.getID() ? d : null;
    }

    private List<java.util.UUID> visibleRequestIds() {
        List<java.util.UUID> ids = new ArrayList<>();
        for (Row r : rows) if (r.requestId() != null) ids.add(r.requestId());
        return ids;
    }

    private void fulfil(List<java.util.UUID> ids) {
        if (colony == null || ids.isEmpty() || !ClientSetup.serverHasMod()) return;
        Net.CHANNEL.sendToServer(new Net.QuoteRequest(colony.getID(), ids));
    }

    /** 2: one click -> server collects every stuck request, pay window shows total vs treasury / wallet, confirm buys the lot. */
    private void buyStuck() {
        if (colony == null || !canFulfil()) return;
        if (ClientSetup.serverHasDash()) Net.DASH.sendToServer(new Net.DashAction(colony.getID(), Net.DashAction.BUY_STUCK, 0, 0));
    }

    private void updateStuckBtn() {
        if (stuckBtn == null) return;
        var d = dash();
        boolean srv = ClientSetup.serverHasDash();
        long n = d == null ? -1 : d.n("stuck", 0);
        if (tab == DASH) stuckBtn.setMessage(n < 0 ? Component.translatable("colonyboard.dash.buystuck.unknown")
            : Component.translatable("colonyboard.dash.buystuck", n, d.n("stuckCost", 0)));
        stuckBtn.active = srv && canFulfil() && n != 0;
        stuckBtn.setTooltip(Tooltip.create(Component.translatable(!srv ? "colonyboard.dash.noserver"
            : !canFulfil() ? (ClientState.stats != null && !ClientState.stats.deny().isEmpty() ? ClientState.stats.deny() : "colonyboard.sum.noserver")
            : "colonyboard.dash.buystuck.hover")));
    }

    private void cycleBuilding() {
        if (buildings.isEmpty()) { buildingFilter = null; refresh(); return; }
        int idx = -1;
        for (int i = 0; i < buildings.size(); i++) if (buildings.get(i).getID().equals(buildingFilter)) idx = i;
        idx++;
        buildingFilter = idx >= buildings.size() ? null : buildings.get(idx).getID();
        refresh();
    }

    // ------------------------------------------------------------------ favourites
    static final String[] ACTIONS = {"buy_stuck", "claim_tax", "auto_toggle", "market", "market_stock", "tab_requests", "tab_construction",
        "tab_stock", "tab_citizens", "tab_finance", "tab_war", "glow_idle", "glow_guards", "refresh"};
    private static final int MAX_FAV = 4;

    static List<String> favorites() {
        List<String> out = new ArrayList<>();
        try { for (String s : ClientConfig.FAVORITES.get()) if (List.of(ACTIONS).contains(s) && !out.contains(s) && out.size() < MAX_FAV) out.add(s); }
        catch (Throwable ignored) {}
        return out;
    }

    private void toggleFav(String id) {
        List<String> f = favorites();
        if (f.contains(id)) f.remove(id);
        else if (f.size() < MAX_FAV) f.add(id);
        else { minecraft.player.displayClientMessage(Component.translatable("colonyboard.fav.full", MAX_FAV), true); return; }
        try { ClientConfig.FAVORITES.set(f); ClientConfig.FAVORITES.save(); } catch (Throwable ignored) {}
        refresh();
    }

    private void runAction(String id) {
        switch (id) {
            case "buy_stuck" -> buyStuck();
            case "claim_tax" -> { if (colony != null && finDeny(2).isEmpty()) Net.CHANNEL.sendToServer(new Net.FinanceAction(colony.getID(), 2, 0, 0, "")); }
            case "auto_toggle" -> toggleAuto();
            case "market" -> minecraft.setScreen(new MarketScreen());
            case "market_stock" -> minecraft.setScreen(MarketScreen.stock());
            case "tab_requests" -> go(REQ);
            case "tab_construction" -> go(BUILD);
            case "tab_stock" -> go(STOCK);
            case "tab_citizens" -> go(CIT);
            case "tab_finance" -> go(FIN);
            case "tab_war" -> go(WAR);
            case "glow_idle", "glow_guards" -> glowAll(id.equals("glow_guards"));
            default -> { ClientSetup.requestSync(cid()); ClientSetup.requestDash(cid()); refresh(); }
        }
    }

    private void glowAll(boolean guards) {
        if (colony == null || minecraft.level == null) return;
        int n = 0;
        for (ICitizenDataView c : colony.getCitizens().values()) {
            boolean pick;
            try {
                pick = guards ? Collector.isGuard(c)
                    : !c.isChild() && (c.getJob() == null || c.getJob().isEmpty()) && c.getWorkBuilding() == null;
            } catch (Throwable t) { pick = false; }
            if (!pick) continue;
            Entity e = minecraft.level.getEntity(c.getEntityId());
            if (e != null) { ClientSetup.GLOW.put(e.getId(), 300); n++; }
        }
        minecraft.player.displayClientMessage(Component.translatable(guards ? "colonyboard.fav.glow.guards" : "colonyboard.fav.glow.idle", n), false);
        onClose();
    }

    private List<Row> favRows() {
        List<Row> out = new ArrayList<>();
        List<String> f = favorites();
        out.add(new Row(Component.translatable("colonyboard.fav.head", f.size(), MAX_FAV), Component.translatable("colonyboard.fav.hint"), null, false, 0, -1, null));
        for (int i = 0; i < ACTIONS.length; i++) {
            boolean on = f.contains(ACTIONS[i]);
            out.add(new Row(Component.literal(on ? "★ " : "☆ ").append(Component.translatable("colonyboard.fav.a." + ACTIONS[i])),
                Component.translatable("colonyboard.fav.d." + ACTIONS[i]), null, false, 0, -1, null, null, -1, i));
        }
        return out;
    }

    // ------------------------------------------------------------------ rows
    private void refresh() {
        if (colony == null) colony = ClientSetup.findColony();
        List<Row> raw = new ArrayList<>();
        var st = ClientState.stats;
        if (colony != null) {
            buildings = Collector.buildings(colony);
            try {
                raw = switch (tab) {
                    case DASH -> Collector.dashboard(colony, st, dash());
                    case COMPL -> Collector.complaints(colony);
                    case REQ -> Collector.requests(colony);
                    case BUILD -> Collector.construction(colony);
                    case STOCK -> Collector.warehouse(colony, dash(), stockQuery);
                    case CIT -> Collector.citizens(colony, citCat, citQuery);
                    case FIN -> {
                        List<Row> l = Collector.finance(colony, st);
                        List<Row> tx = Collector.taxes(dash(), ClientState.auto, colony.getID());
                        l.addAll(Math.min(l.size(), 3), tx);
                        var ts = tier();
                        if (ts != null && ts.mode() == 1) {
                            l.add(new Row(Component.translatable("colonyboard.tier.huts.head"),
                                Component.translatable("colonyboard.tier.huts.detail"), null, false, -100, -1, null, null, -10, -1));
                            for (Net.TierHut h : ts.huts()) {
                                BlockPos pos = BlockPos.of(h.pos());
                                l.add(new Row(Component.literal(h.name()),
                                    Component.translatable("colonyboard.tier.hut.detail",
                                        Component.translatable("colonyboard.tier.floor." + floorName(h.floor())), h.maxEq()),
                                    pos, h.floor() > 0, -90, -1, null, null, -10, h.floor()));
                            }
                        } else if (ts != null) {
                            l.add(new Row(Component.translatable("colonyboard.tier.status"),
                                Component.translatable("colonyboard.tier.status.detail",
                                    Component.translatable("colonyboard.tier.floor." + floorName(ts.colonyFloor())), ts.thLevel()),
                                null, ts.colonyFloor() > 0, -100, -1, null));
                        }
                        yield l;
                    }
                    case WAR -> Collector.war(colony, st, dash());
                    case FAV -> favRows();
                    default -> new ArrayList<>();
                };
            } catch (Throwable t) { raw = new ArrayList<>(); }
        }
        switch (tab) {
            case COMPL -> rows = Filter.apply(raw, buildingFilter, blockingOnly);
            case REQ -> {
                List<Row> l = new ArrayList<>();
                for (Row r : Filter.apply(raw, buildingFilter, blockingOnly)) {
                    if (reqKind != 0 && r.ref() != reqKind) continue;
                    if (!Search.matches(reqQuery, r.title().getString(), r.detail().getString())) continue;
                    l.add(r);
                }
                rows = l;
            }
            case BUILD -> {
                if (buildSec == SEC_QUEUE) { rows = new ArrayList<>(raw); rows.sort((a, b) -> Integer.compare(b.sortKey(), a.sortKey())); }
                else rows = new ArrayList<>();
                buildLines = layoutBuild();
                buildContentH = 0;
                for (BuildLine line : buildLines) buildContentH += line.h;
            }
            case CIT -> rows = Filter.apply(raw, null, false);
            default -> rows = raw;
        }
        if (fulfilAllBtn != null) fulfilAllBtn.active = canFulfil() && !visibleRequestIds().isEmpty();
        updateFinance();
        updateStuckBtn();
        if (kindBtn != null) kindBtn.setMessage(Component.translatable("colonyboard.filter.kind", Component.translatable("colonyboard.kind." + Collector.KINDS[reqKind])));
        if (catBtn != null) catBtn.setMessage(Component.translatable("colonyboard.cit.cat", Component.translatable("colonyboard.cit.cat." + citCat)));
        for (int i = 0; i < favBtns.size(); i++) {
            String id = favorites().size() > i ? favorites().get(i) : "";
            Glass.Btn b = favBtns.get(i);
            b.active = switch (id) {
                case "buy_stuck" -> ClientSetup.serverHasDash() && canFulfil();
                case "claim_tax" -> colony != null && finDeny(2).isEmpty();
                case "auto_toggle" -> autoOk();
                default -> true;
            };
            if (id.equals("auto_toggle") && ClientState.auto != null) { b.selected = ClientState.auto.on() && b.active; b.accent = ClientState.auto.on() ? Glass.GREEN : Glass.AMBER; }
        }
        if (buildingBtn != null) {
            Component bname = Component.translatable("colonyboard.filter.all");
            for (IBuildingView b : buildings) if (b.getID().equals(buildingFilter)) bname = Collector.buildingName(b);
            buildingBtn.setMessage(Component.translatable("colonyboard.filter.building", bname));
        }
        if (blockingBtn != null) blockingBtn.setMessage(Component.translatable("colonyboard.filter.blocking",
            Component.translatable(blockingOnly ? "colonyboard.on" : "colonyboard.off")));
        int max = tab == BUILD ? Math.max(0, buildContentH - (listBottom - listTop)) : Math.max(0, rows.size() * ROW_H - (listBottom - listTop));
        if (focusId != null) {
            for (int i = 0; i < rows.size(); i++) if (focusId.equals(rows.get(i).requestId())) {
                scroll = i * ROW_H - (listBottom - listTop) / 2.0 + ROW_H / 2.0;
                flashId = focusId; flashUntil = System.currentTimeMillis() + 2500; focusId = null;
                break;
            }
            if (focusId != null && System.currentTimeMillis() > focusUntil) focusId = null;
        }
        scroll = Math.max(0, Math.min(scroll, max));
    }

    /** In-row button: label, accent, width, active, action. */
    private record RB(Component label, int acc, int w, boolean active, Runnable run) {}

    private List<RB> rowButtons(Row r) {
        List<RB> out = new ArrayList<>();
        switch (tab) {
            case BUILD -> {
                // Queue actions are drawn under the text by layoutBuild, sized to the label.
            }
            case STOCK -> { if (r.ref() >= 0) out.add(new RB(Component.translatable("colonyboard.stock.tomarket"), Glass.AMBER, 62, true, () -> minecraft.setScreen(MarketScreen.stock()))); }
            case FAV -> {
                if (r.ref() >= 0) {
                    String id = ACTIONS[r.ref()];
                    boolean on = favorites().contains(id);
                    out.add(new RB(Component.translatable(on ? "colonyboard.fav.unpin" : "colonyboard.fav.pin"), Glass.AMBER, 62, true, () -> toggleFav(id)));
                    out.add(new RB(Component.translatable("colonyboard.fav.run"), Glass.GREEN, 56, true, () -> runAction(id)));
                }
            }
            case DASH -> { if (r.jump() >= 0) out.add(new RB(Component.translatable("colonyboard.dash.open.btn"), r.blocking() ? Glass.RED : Glass.CYAN, 56, true, () -> go(r.jump()))); }
            case FIN -> {
                if (r.jump() == -10 && r.building() != null) {
                    var ts = tier();
                    boolean mgr = ts != null && ts.manager() && ClientSetup.serverHasTier();
                    int cur = Math.max(0, r.ref());
                    int next = (cur + 1) % 4;
                    out.add(new RB(Component.translatable("colonyboard.tier.floor." + floorName(next)), Glass.AMBER, 72, mgr,
                        () -> setHutFloor(r.building().asLong(), next)));
                }
            }
            default -> {
                if (r.canHighlight()) out.add(new RB(Component.translatable("colonyboard.highlight"), Glass.CYAN, 56, true, () -> highlight(r)));
                else if (r.requestId() != null && canFulfil()) out.add(new RB(Component.translatable("colonyboard.fulfil.one"), Glass.GREEN, 56, true, () -> fulfil(List.of(r.requestId()))));
            }
        }
        return out;
    }

    private void woAction(int op, int woId, int delta) {
        if (colony == null || !ClientSetup.serverHasDash()) return;
        Net.DASH.sendToServer(new Net.DashAction(colony.getID(), op, woId, delta));
        if (op == Net.DashAction.WO_PRIO) ClientSetup.requestSync(colony.getID());
    }

    @Override
    public void tick() {
        if (++ticks % 20 == 0) refresh();                                // views update live from network
        if (ticks % 200 == 0 && colony != null) { ClientSetup.requestSync(colony.getID()); ClientSetup.requestDash(colony.getID()); if (tab == FIN) ClientSetup.requestTier(colony.getID()); if (tab == BUILD) ClientSetup.requestBuild(colony.getID()); }
    }

    @Override
    public void renderBackground(GuiGraphics g) { Glass.backdrop(g, width, height); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        Component name = colony == null ? Component.literal("?") : Component.literal(colony.getName());
        lay.chrome(g, font, Component.translatable("colonyboard.title", name), Glass.CYAN, listBottom, height);
        if (listTop > 30) Glass.inset(g, left - 3, 23, right + 3, listTop - 3);
        Glass.inset(g, left - 1, listTop - 1, right + 1, listBottom + 1);
        super.render(g, mx, my, pt);
        var st = ClientState.stats;
        if (st != null && colony != null && st.colonyId() == colony.getID()) {
            StringBuilder sb = new StringBuilder();
            if (st.treasury() >= 0) sb.append(Component.translatable("colonyboard.sum.treasury").getString()).append(": ").append(st.treasury()).append(" ◎   ");
            if (st.wallet() >= 0) sb.append(Component.translatable("colonyboard.sum.wallet").getString()).append(": ").append(String.format(Locale.ROOT, "%.0f", st.wallet())).append(" ◎   ");
            if (!st.danger().isEmpty()) sb.append(Component.translatable(st.danger()).getString()).append("   ");
            if (st.deny().isEmpty()) sb.append(Component.translatable(st.creative() ? "colonyboard.mode.creative" : "colonyboard.mode.paid").getString());
            Glass.pill(g, font, sb.toString().trim(), (left + right) / 2, height - 19, st.danger().isEmpty() ? Glass.GREEN : Glass.RED);
        }
        if (colony == null) {
            g.drawCenteredString(font, Component.translatable("colonyboard.nocolony"), (left + right) / 2, listTop + 10, Glass.RED);
            return;
        }
        g.enableScissor(left, listTop, right, listBottom);
        if (tab == BUILD) { renderBuild(g, mx, my); g.disableScissor(); return; }
        if (rows.isEmpty()) g.drawCenteredString(font, Component.translatable("colonyboard.empty"), (left + right) / 2, listTop + 10, Glass.GREEN);
        for (int i = 0; i < rows.size(); i++) {
            int y = listTop + i * ROW_H - (int) scroll;
            if (y + ROW_H < listTop || y > listBottom) continue;
            Row r = rows.get(i);
            boolean rowHover = mx >= left && mx < right && my >= y && my < y + ROW_H - 1 && my >= listTop && my < listBottom;
            g.fill(left, y, right, y + ROW_H - 1, Glass.a(i % 2 == 0 ? 70 : 40, 0x16222F));
            if (rowHover) g.fill(left, y, right, y + ROW_H - 1, Glass.a(28, Glass.CYAN));
            g.fill(left + 3, y + ROW_H - 1, right - 3, y + ROW_H, Glass.a(28, Glass.CYAN));             // hairline divider
            if (r.blocking()) { g.fill(left, y, left + 2, y + ROW_H - 1, Glass.a(255, Glass.RED)); g.fill(left + 2, y, left + 5, y + ROW_H - 1, Glass.a(60, Glass.RED)); }
            else if (tab == DASH && r.jump() >= 0) { g.fill(left, y, left + 2, y + ROW_H - 1, Glass.a(200, Glass.GREEN)); }
            if (flashId != null && flashId.equals(r.requestId()) && System.currentTimeMillis() < flashUntil) {
                int fa = (int) (120 + 100 * Math.sin(System.currentTimeMillis() / 120.0));
                int fc = (Math.max(0, Math.min(255, fa)) << 24) | 0x4FE3FF;
                g.fill(left, y, right, y + 1, fc); g.fill(left, y + ROW_H - 2, right, y + ROW_H - 1, fc);
                g.fill(left, y, left + 1, y + ROW_H - 1, fc); g.fill(right - 1, y, right, y + ROW_H - 1, fc);
            }
            List<RB> bs = rowButtons(r);
            int bx = right - 4;
            for (RB b : bs) bx -= b.w() + 2;
            int textRight = bs.isEmpty() ? right - 4 : bx;
            g.drawString(font, font.plainSubstrByWidth(r.title().getString(), textRight - left - 8), left + 8, y + 2, r.blocking() ? 0xFF8080 : Glass.TEXT);
            g.drawString(font, font.plainSubstrByWidth(r.detail().getString(), textRight - left - 8), left + 8, y + 12, 0x8FA3B6);
            int x = bx + 2;
            for (RB b : bs) {
                boolean hover = mx >= x && mx < x + b.w() && my >= y + 3 && my < y + 18 && my >= listTop && my < listBottom;
                Glass.button(g, font, b.label(), x, y + 3, b.w(), 15, b.acc(), b.active(), hover && b.active(), false);
                x += b.w() + 2;
            }
        }
        g.disableScissor();
        if (st == null && !ClientSetup.serverHasMod() && colony != null && Minecraft.getInstance().player != null
            && !colony.isCoordInColony(Minecraft.getInstance().level, Minecraft.getInstance().player.blockPosition()))
            Glass.pill(g, font, Component.translatable("colonyboard.stale").getString(), (left + right) / 2, height - 19, Glass.AMBER);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (tab == BUILD && clickBuild(mx, my)) return true;
        if (my < listTop || my >= listBottom) return false;
        int i = (int) ((my - listTop + scroll) / ROW_H);
        if (i < 0 || i >= rows.size()) return false;
        Row r = rows.get(i);
        int y = listTop + i * ROW_H - (int) scroll;
        List<RB> bs = rowButtons(r);
        int x = right - 4;
        for (RB b : bs) x -= b.w() + 2;
        x += 2;
        for (RB b : bs) {
            if (mx >= x && mx < x + b.w() && my >= y + 3 && my < y + 18) {
                if (b.active()) { b.run().run(); playClick(); }
                return true;
            }
            x += b.w() + 2;
        }
        if (tab == DASH && r.jump() >= 0 && mx >= left && mx < right) { playClick(); go(r.jump()); return true; }
        return false;
    }

    private void playClick() {
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void highlight(Row r) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = r.citizenEntityId() >= 0 && mc.level != null ? mc.level.getEntity(r.citizenEntityId()) : null;
        BlockPos pos = e != null ? e.blockPosition() : r.citizenPos();
        String where = pos == null ? "?" : pos.toShortString();
        if (e != null) {
            ClientSetup.GLOW.put(e.getId(), 300);
            mc.player.displayClientMessage(Component.translatable("colonyboard.highlighted", r.title(), where), false);
        } else {
            mc.player.displayClientMessage(Component.translatable("colonyboard.faraway", r.title(), where), false);
        }
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int content = tab == BUILD ? buildContentH : rows.size() * ROW_H;
        int max = Math.max(0, content - (listBottom - listTop));
        scroll = Math.max(0, Math.min(max, scroll - delta * ROW_H));
        return true;
    }

    public void refreshFromNet() { refresh(); }

    public static void markBuildPending(long pos) { BUILD_PENDING.add(pos); }
    public static void clearBuildPending(long pos) { BUILD_PENDING.remove(pos); }
    public static boolean buildPending(long pos) { return BUILD_PENDING.contains(pos); }

    private record BuildBtn(Component label, int acc, boolean active, Runnable run, int w, int x, int y, int h) {}
    private record BuildLine(List<String> title, List<String> detail, List<BuildBtn> buttons, Runnable open, boolean blocking, int h) {}

    private List<BuildLine> layoutBuild() {
        List<BuildLine> out = new ArrayList<>();
        int textW = Math.max(40, right - left - 16);
        if (buildSec == SEC_QUEUE) {
            if (rows.isEmpty()) out.add(noteLine(Component.translatable("colonyboard.build.empty.queue").getString(), textW));
            else for (Row r : rows) out.add(queueLine(r, textW));
            return out;
        }
        if (!ClientSetup.serverHasBuild()) {
            out.add(noteLine(Component.translatable("colonyboard.build.noserver").getString(), textW));
            return out;
        }
        Net.BuildState st = ClientState.build;
        if (st == null || colony == null || st.colonyId() != colony.getID()) {
            out.add(noteLine(Component.translatable("colonyboard.build.loading").getString(), textW));
            return out;
        }
        if (st.unreadable() == -2) {
            out.add(noteLine(Component.translatable("colonyboard.build.deny.nocolony").getString(), textW));
            return out;
        }
        if (st.unreadable() < 0) {
            out.add(noteLine(Component.translatable("colonyboard.build.unreadable.all").getString(), textW));
            return out;
        }
        int want = buildSec == SEC_PLACED ? 0 : 1;
        List<Net.BuildHutRow> huts = new ArrayList<>();
        for (Net.BuildHutRow h : st.huts()) if (h.kind() == want) huts.add(h);
        if (huts.isEmpty()) out.add(noteLine(Component.translatable(buildSec == SEC_PLACED ? "colonyboard.build.empty.placed" : "colonyboard.build.empty.upgrade").getString(), textW));
        for (Net.BuildHutRow h : huts) out.add(hutLine(h, textW));
        if (st.unreadable() > 0) out.add(noteLine(Component.translatable("colonyboard.build.unreadable", st.unreadable()).getString(), textW));
        if (st.truncated()) out.add(noteLine(Component.translatable("colonyboard.build.truncated").getString(), textW));
        return out;
    }

    private BuildLine noteLine(String text, int textW) {
        List<String> lines = TextWrap.wrap(text, textW, font::width);
        if (lines.isEmpty()) lines = List.of(text);
        return new BuildLine(lines, List.of(), List.of(), null, false, 8 + lines.size() * 10);
    }

    private BuildLine queueLine(Row r, int textW) {
        List<String> title = TextWrap.wrap(r.title().getString(), textW, font::width);
        List<String> detail = TextWrap.wrap(r.detail().getString(), textW, font::width);
        if (title.isEmpty()) title = List.of("");
        List<BuildBtn> buttons = new ArrayList<>();
        if (r.ref() >= 0) {
            boolean mgr = dash() != null && dash().manager();
            boolean srv = ClientSetup.serverHasDash();
            buttons.add(btn(Component.translatable("colonyboard.build.earlier"), Glass.CYAN, srv && mgr, () -> woAction(Net.DashAction.WO_PRIO, r.ref(), 1)));
            buttons.add(btn(Component.translatable("colonyboard.build.later"), Glass.CYAN, srv && mgr, () -> woAction(Net.DashAction.WO_PRIO, r.ref(), -1)));
            buttons.add(btn(Component.translatable("colonyboard.build.materials"), Glass.GREEN, srv && canFulfil(), () -> woAction(Net.DashAction.WO_BUY, r.ref(), 0)));
        }
        if (r.canHighlight()) buttons.add(btn(Component.translatable("colonyboard.highlight"), Glass.CYAN, true, () -> highlight(r)));
        return packLine(title, detail, buttons, null, r.blocking(), textW);
    }

    private BuildLine hutLine(Net.BuildHutRow h, int textW) {
        Component name = h.literal() != null && !h.literal().isBlank() ? Component.literal(h.literal())
            : h.key() != null && !h.key().isBlank() ? Component.translatable(h.key()) : Component.translatable("colonyboard.build.noname");
        String title = name.getString() + " (" + h.level() + "→" + h.target() + ")";
        boolean pending = BUILD_PENDING.contains(h.pos());
        String status = h.kind() == 0 ? Component.translatable("colonyboard.build.placed").getString()
            : Component.translatable("colonyboard.build.upgrade.ready", h.level(), h.target()).getString();
        if (pending) status += " · " + Component.translatable("colonyboard.build.pending").getString();
        else if (h.deny() != null && !h.deny().isEmpty()) status += " · " + Component.translatable(h.deny()).getString();
        final String shown = status;
        List<String> titles = TextWrap.wrap(title, textW, font::width);
        List<String> detail = TextWrap.wrap(shown, textW, font::width);
        boolean enabled = !pending && (h.deny() == null || h.deny().isEmpty());
        Runnable open = () -> minecraft.setScreen(new BuildConfirmScreen(this, cid(), h));
        Component action = Component.translatable(h.kind() == 0 ? "colonyboard.build.start" : "colonyboard.build.upgrade", h.target());
        List<BuildBtn> buttons = new ArrayList<>();
        buttons.add(btn(action, enabled ? Glass.GREEN : Glass.AMBER, true, open));
        BlockPos pos = BlockPos.of(h.pos());
        buttons.add(btn(Component.translatable("colonyboard.highlight"), Glass.CYAN, true, () -> highlight(new Row(name, Component.literal(shown), pos, !enabled, 0, -1, pos))));
        return packLine(titles.isEmpty() ? List.of(title) : titles, detail, buttons, open, !enabled, textW);
    }

    private BuildBtn btn(Component label, int acc, boolean active, Runnable run) {
        int w = font.width(label) + 14;
        return new BuildBtn(label, acc, active, run, w, 0, 0, 16);
    }

    private BuildLine packLine(List<String> title, List<String> detail, List<BuildBtn> buttons, Runnable open, boolean blocking, int textW) {
        int[] widths = new int[buttons.size()];
        for (int i = 0; i < buttons.size(); i++) widths[i] = buttons.get(i).w;
        List<List<Integer>> packed = TextWrap.pack(widths, 4, textW);
        List<BuildBtn> placed = new ArrayList<>();
        int y = 4 + (title.size() + detail.size()) * 10 + 2;
        for (List<Integer> row : packed) {
            int x = 8;
            int rowH = 16;
            for (int idx : row) {
                BuildBtn src = buttons.get(idx);
                int w = Math.min(src.w, textW);
                placed.add(new BuildBtn(src.label, src.acc, src.active, src.run, w, x, y, rowH));
                x += w + 4;
            }
            y += rowH + 2;
        }
        int h = Math.max(22, y + 2);
        return new BuildLine(title, detail, placed, open, blocking, h);
    }

    private void renderBuild(GuiGraphics g, int mx, int my) {
        if (buildLines.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("colonyboard.empty"), (left + right) / 2, listTop + 10, Glass.GREEN);
            return;
        }
        int y = listTop - (int) scroll;
        for (BuildLine line : buildLines) {
            int top = y;
            if (top + line.h >= listTop && top <= listBottom) {
                g.fill(left, top, right, top + line.h - 1, Glass.a(40, 0x16222F));
                if (line.blocking) g.fill(left, top, left + 3, top + line.h - 1, Glass.a(200, Glass.RED));
                int ty = top + 3;
                for (String s : line.title) { g.drawString(font, s, left + 8, ty, line.blocking ? 0xFF8080 : Glass.TEXT, false); ty += 10; }
                for (String s : line.detail) { g.drawString(font, s, left + 8, ty, 0x8FA3B6, false); ty += 10; }
                for (BuildBtn b : line.buttons) {
                    if (top + b.y + b.h < listTop || top + b.y > listBottom) continue;
                    boolean hover = mx >= left + b.x && mx < left + b.x + b.w && my >= top + b.y && my < top + b.y + b.h && my >= listTop && my < listBottom;
                    String shown = font.plainSubstrByWidth(b.label.getString(), Math.max(1, b.w - 8));
                    boolean full = shown.equals(b.label.getString());
                    Glass.button(g, font, full ? b.label : Component.literal(shown), left + b.x, top + b.y, b.w, b.h, b.acc, b.active, hover && b.active, false);
                }
            }
            y += line.h;
        }
    }

    private boolean clickBuild(double mx, double my) {
        if (my < listTop || my >= listBottom) return false;
        int y = listTop - (int) scroll;
        for (BuildLine line : buildLines) {
            if (my >= y && my < y + line.h) {
                for (BuildBtn b : line.buttons) {
                    int bx = left + b.x, by = y + b.y;
                    if (mx >= bx && mx < bx + b.w && my >= by && my < by + b.h) {
                        if (b.active && b.run != null) { b.run.run(); playClick(); }
                        return true;
                    }
                }
                if (line.open != null && mx >= left && mx < right) { line.open.run(); playClick(); return true; }
                return true;
            }
            y += line.h;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        boolean typing = getFocused() instanceof EditBox eb && eb.isFocused();
        if (!typing && ClientSetup.OPEN.matches(key, sc)) { onClose(); return true; }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
