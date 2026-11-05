package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import ru.wik.colonyboard.BoardConfig;
import ru.wik.colonyboard.Net;

import java.util.*;

/**
 * 1.7: server facts for the J dashboard (stuck requests and their price, taxes, war readiness, warehouse shortages /
 * surplus) and its actions (buy all stuck, work order priority, buy everything for one build, auto-buy budget).
 * Every WarNTaxes / colonybuy call is wrapped: a missing mod or API drift only hides that line.
 */
public final class Dash {
    private static final EnumSet<RequestState> OPEN = EnumSet.of(RequestState.CREATED, RequestState.REPORTED,
        RequestState.ASSIGNING, RequestState.ASSIGNED, RequestState.IN_PROGRESS);
    private Dash() {}

    public static Net.DashData build(ServerPlayer p, IColony c) {
        Map<String, Long> n = new LinkedHashMap<>();
        Map<String, String> s = new LinkedHashMap<>();
        int id = c.getID();
        boolean manager = Fulfil.isManager(p, c);

        // ---- stuck requests: count, buyable count, price of the buyable ones
        Set<UUID> stuck = ReqWatch.stuckIds(c);
        List<Fulfil.Line> lines = new ArrayList<>();
        try { if (!stuck.isEmpty()) lines = Fulfil.scan(c, stuck, false); } catch (Throwable ignored) {}
        long cost = 0; int buyable = 0;
        for (Fulfil.Line l : lines) if (l.ok()) { buyable++; cost += l.cost(); }
        n.put("stuck", (long) lines.size());
        n.put("stuckBuyable", (long) buyable);
        n.put("stuckCost", cost);
        List<UUID> stuckIds = new ArrayList<>();
        for (Fulfil.Line l : lines) stuckIds.add(l.id);

        // ---- money / taxes (WarNTaxes)
        n.put("treasury", ColonyState.treasury(c));
        String danger = ColonyState.danger(c);
        if (danger != null) s.put("danger", danger);
        if (Finance.wnt()) {
            try { n.put("capacity", Finance.capacity(c)); } catch (Throwable ignored) {}
            try { n.put("tax", Finance.storedTax(c)); } catch (Throwable ignored) {}
            try { n.put("taxInterval", (long) net.machiavelli.minecolonytax.TaxConfig.TAX_INTERVAL_MINUTES.get()); } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field f = net.machiavelli.minecolonytax.TaxManager.class.getDeclaredField("lastTaxGenerationTime");
                f.setAccessible(true);
                long last = f.getLong(null);
                long iv = n.getOrDefault("taxInterval", 0L) * 60_000L;
                if (last > 0 && iv > 0) n.put("taxNext", Math.max(0, (last + iv - System.currentTimeMillis()) / 1000));
            } catch (Throwable ignored) {}
            try { n.put("debtCycles", (long) net.machiavelli.minecolonytax.TaxManager.getConsecutiveDebtCycles(id)); } catch (Throwable ignored) {}
            try { n.put("debtLimit", (long) net.machiavelli.minecolonytax.TaxConfig.DEBT_LIMIT.get()); } catch (Throwable ignored) {}
            try { n.put("debtAbandon", (long) net.machiavelli.minecolonytax.TaxConfig.DEBT_ABANDONMENT_CYCLES.get()); } catch (Throwable ignored) {}
            try { n.put("taxFrozen", net.machiavelli.minecolonytax.TaxManager.isGenerationDisabled(id) ? 1L : 0L); } catch (Throwable ignored) {}
            try { n.put("maxTax", (long) net.machiavelli.minecolonytax.TaxConfig.MAX_TAX_REVENUE.get()); } catch (Throwable ignored) {}
            try {
                Object pol = net.machiavelli.minecolonytax.economy.policy.TaxPolicyManager.getPolicy(id);
                if (pol != null) s.put("policy", String.valueOf(pol));
                n.put("policyMult", Math.round(net.machiavelli.minecolonytax.economy.policy.TaxPolicyManager.getRevenueMultiplier(id) * 1000));
            } catch (Throwable ignored) {}
            try { n.put("warTaxMult", Math.round(net.machiavelli.minecolonytax.economy.WarExhaustionManager.getTaxMultiplier(id) * 1000)); } catch (Throwable ignored) {}
            // ---- war readiness
            try { n.put("guards", (long) net.machiavelli.minecolonytax.WarSystem.countGuards(c)); } catch (Throwable ignored) {}
            try { n.put("towers", (long) net.machiavelli.minecolonytax.WarSystem.countGuardTowers(c)); } catch (Throwable ignored) {}
            try { n.put("minGuardsWar", (long) net.machiavelli.minecolonytax.TaxConfig.MIN_GUARDS_TO_WAGE_WAR.get()); } catch (Throwable ignored) {}
            try { n.put("minGuardsRaid", (long) net.machiavelli.minecolonytax.TaxConfig.MIN_GUARDS_TO_RAID.get()); } catch (Throwable ignored) {}
            try { n.put("towersBoost", (long) net.machiavelli.minecolonytax.TaxConfig.REQUIRED_GUARD_TOWERS_FOR_BOOST.get()); } catch (Throwable ignored) {}
            try { n.put("warRequired", (long) net.machiavelli.minecolonytax.economy.TreasuryManager.getRequiredTreasury(id)); } catch (Throwable ignored) {}
            try { n.put("immunityH", (long) net.machiavelli.minecolonytax.economy.WarExhaustionManager.getRemainingImmunityHours(id)); } catch (Throwable ignored) {}
            try { n.put("recoveryH", (long) net.machiavelli.minecolonytax.economy.WarExhaustionManager.getRemainingRecoveryHours(id)); } catch (Throwable ignored) {}
            try { n.put("reparationsH", (long) net.machiavelli.minecolonytax.economy.WarExhaustionManager.getRemainingReparationsHours(id)); } catch (Throwable ignored) {}
            try { n.put("losses", (long) net.machiavelli.minecolonytax.economy.WarExhaustionManager.getRecentLossCount(id)); } catch (Throwable ignored) {}
            try { n.put("debtBlocksWar", net.machiavelli.minecolonytax.TaxConfig.DEBT_BLOCKS_WAR.get() ? 1L : 0L); } catch (Throwable ignored) {}
        }
        if (AutoBuy.present()) try { n.put("autoBlocked", CbAuto.blocked(c) ? 1L : 0L); } catch (Throwable ignored) {}

        // ---- warehouse: what the stuck requests need vs what is in the racks; big piles nobody asks for
        List<Net.DItem> shortages = new ArrayList<>(), surplus = new ArrayList<>();
        boolean wh = false;
        try { wh = Warehouse.available(c); } catch (Throwable ignored) {}
        n.put("warehouse", wh ? 1L : 0L);
        List<ItemStack> keys = new ArrayList<>(); List<Integer> need = new ArrayList<>();
        for (Fulfil.Line l : lines) {
            if (l.stack == null || l.stack.isEmpty()) continue;
            int k = -1;
            for (int i = 0; i < keys.size(); i++) if (Warehouse.same(keys.get(i), l.stack)) { k = i; break; }
            if (k < 0) { keys.add(l.stack.copyWithCount(1)); need.add(0); k = keys.size() - 1; }
            need.set(k, need.get(k) + Math.max(1, l.qty));
        }
        for (int i = 0; i < keys.size() && i < 64; i++) {
            int have = 0;
            if (wh) try { have = Warehouse.count(c, keys.get(i)); } catch (Throwable ignored) {}
            shortages.add(new Net.DItem(keys.get(i), need.get(i), have));
        }
        shortages.sort((a, b) -> Integer.compare(b.need() - b.have(), a.need() - a.have()));
        if (wh) {
            int min = BoardConfig.DASH_SURPLUS.get();
            n.put("surplusMin", (long) min);
            try {
                for (Warehouse.Stock st : Warehouse.stock(c, 400)) {
                    if (st.count() < min) break;
                    boolean wanted = false;
                    for (ItemStack k : keys) if (Warehouse.same(k, st.proto())) { wanted = true; break; }
                    if (!wanted) surplus.add(new Net.DItem(st.proto(), 0, st.count()));
                    if (surplus.size() >= 24) break;
                }
            } catch (Throwable ignored) {}
        }
        return new Net.DashData(id, manager, n, s, shortages, surplus, stuckIds);
    }

    /** Returns request ids to quote (pay window), or null when nothing to quote. */
    public static List<UUID> handle(ServerPlayer p, IColony c, Net.DashAction a) {
        switch (a.op()) {
            case Net.DashAction.BUY_STUCK -> {
                Set<UUID> ids = ReqWatch.stuckIds(c);
                if (ids.isEmpty()) { info(p, "colonyboard.dash.toast.nostuck"); return null; }
                return new ArrayList<>(ids);
            }
            case Net.DashAction.WO_BUY -> {
                IServerWorkOrder wo = c.getWorkManager().getWorkOrder(a.a());
                if (wo == null) return null;
                if (!wo.isClaimed()) { info(p, "colonyboard.dash.toast.wo.unclaimed"); return null; }
                IBuilding b = c.getServerBuildingManager().getBuilding(wo.getClaimedBy());
                List<UUID> ids = new ArrayList<>();
                if (b != null) {
                    List<Integer> cids = new ArrayList<>(); cids.add(-1);
                    try { c.getCitizenManager().getCitizens().forEach(cd -> cids.add(cd.getId())); } catch (Throwable ignored) {}
                    Set<UUID> seen = new HashSet<>();
                    for (int cid : cids) {
                        try { for (IRequest<?> r : b.getOpenRequests(cid)) if (OPEN.contains(r.getState()) && seen.add(Fulfil.uuid(r))) ids.add(Fulfil.uuid(r)); }
                        catch (Throwable ignored) {}
                    }
                }
                if (ids.isEmpty()) { info(p, "colonyboard.dash.toast.wo.noreq"); return null; }
                return ids;
            }
            case Net.DashAction.WO_PRIO -> {
                if (!Fulfil.isManager(p, c) && !p.hasPermissions(2)) { deny(p); return null; }
                IServerWorkOrder wo = c.getWorkManager().getWorkOrder(a.a());
                if (wo == null) return null;
                // same as the town hall up/down arrows: jump just above / below the neighbour in build order (higher = sooner)
                List<IServerWorkOrder> all = new ArrayList<>(c.getWorkManager().getWorkOrders().values());
                all.sort(Comparator.comparingInt(IServerWorkOrder::getPriority).reversed());
                int i = all.indexOf(wo), np;
                if (a.b() > 0) { if (i <= 0) return null; np = all.get(i - 1).getPriority() + 1; }
                else { if (i < 0 || i >= all.size() - 1) return null; np = all.get(i + 1).getPriority() - 1; }
                np = Math.max(-10000, Math.min(10000, np));
                wo.setPriority(np);
                c.getWorkManager().setDirty(true);
                try { c.markDirty(); } catch (Throwable ignored) {}
                Fulfil.LOG.info("[colonyboard] {} set work order {} priority {} in colony {}", p.getGameProfile().getName(), wo.getID(), np, c.getID());
                return null;
            }
            case Net.DashAction.BUDGET -> {
                if (!AutoBuy.present()) return null;
                if (!Fulfil.isManager(p, c) && !p.hasPermissions(2)) { deny(p); return null; }
                int amt = Math.max(0, a.a());
                try {
                    CbAuto.setBudget(p.server, c, amt);
                    Finance.journalAdd(c, p.getGameProfile().getName(), "colonyboard.fin.log.budget", amt);
                    Component m = Component.translatable("colonyboard.dash.toast.budget", amt);
                    Net.toast(p, Net.Toast.OK, m, false, m);
                } catch (Throwable t) { Fulfil.LOG.warn("[colonyboard] budget set failed: {}", t.toString()); }
                return null;
            }
            default -> { return null; }
        }
    }

    private static void info(ServerPlayer p, String key) { Component m = Component.translatable(key); Net.toast(p, Net.Toast.INFO, m, false, m); }
    private static void deny(ServerPlayer p) { Component m = Component.translatable("colonyboard.reason.rights"); Net.toast(p, Net.Toast.FAIL, m, false, m); }
}
