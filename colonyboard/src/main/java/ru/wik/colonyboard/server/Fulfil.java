package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Rank;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.requestable.StackList;
import com.minecolonies.api.util.InventoryUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import ru.wik.colonyboard.BoardConfig;
import ru.wik.colonyboard.logic.PayGuard;

import java.util.*;

/** Server side of the Fulfil buttons: quote and deliver open requests, free in creative, paid from SDM wallet in survival. */
public final class Fulfil {
    static final Logger LOG = LogUtils.getLogger();
    private static final EnumSet<RequestState> OPEN = EnumSet.of(RequestState.CREATED, RequestState.REPORTED,
        RequestState.ASSIGNING, RequestState.ASSIGNED, RequestState.IN_PROGRESS);
    /** Request ids already paid for. Stops a second packet from inserting again when overrule failed. */
    private static final Map<UUID, Long> SETTLED = new HashMap<>();
    private static final long SETTLED_MS = 30 * 60_000L;

    private Fulfil() {}

    public static final class Line {
        public final UUID id; public final IRequest<?> req; public final IBuilding building;
        public final ItemStack stack; public final int qty; public final double unit; public final String reason;
        Line(UUID id, IRequest<?> req, IBuilding b, ItemStack s, int qty, double unit, String reason) {
            this.id = id; this.req = req; this.building = b; this.stack = s; this.qty = qty; this.unit = unit; this.reason = reason;
        }
        public boolean ok() { return reason == null; }
        public long cost() { return Pricing.cost(unit, qty); }
    }

    public static boolean isManager(ServerPlayer p, IColony c) {
        try { Rank r = c.getPermissions().getRank(p); return r != null && r.isColonyManager(); } catch (Throwable t) { return false; }
    }

    public static final int SRC_TREASURY = 0, SRC_WALLET = 1;

    /** null if the player may use the Fulfil buttons with at least one pay source (or free in creative), else lang key */
    public static String denyReason(ServerPlayer p, IColony c) {
        if (!BoardConfig.FULFIL_ENABLED.get()) return "colonyboard.reason.disabled";
        if (p.isCreative() && isManager(p, c)) return BoardConfig.CREATIVE_FREE.get() ? null : "colonyboard.reason.disabled";
        String t = sourceDeny(p, c, SRC_TREASURY), w = sourceDeny(p, c, SRC_WALLET);
        if (t == null || w == null) return null;
        return "colonyboard.reason.rights".equals(t) && !"colonyboard.reason.rights".equals(w) ? w : t;
        // 1.4: no war lock - the economy stays open during war / siege / raid
    }

    /** Per pay source: null = allowed. Treasury: owner/officer only. Wallet: the player's own SDM money (members if enabled). */
    public static String sourceDeny(ServerPlayer p, IColony c, int src) {
        if (!BoardConfig.FULFIL_ENABLED.get() || !BoardConfig.SURVIVAL_PAID.get()) return "colonyboard.reason.disabled";
        if (src == SRC_TREASURY) {
            if (!BoardConfig.PAY_TREASURY.get() || !Finance.wnt()) return "colonyboard.reason.notreasury";
            if (!isManager(p, c)) return "colonyboard.reason.rights";
            return null;
        }
        if (!BoardConfig.PAY_WALLET.get() || !Wallet.available()) return "colonyboard.reason.nowallet";
        if (isManager(p, c)) return null;
        boolean member = false;
        try { member = c.getPermissions().isColonyMember(p); } catch (Throwable ignored) {}
        return member && BoardConfig.WALLET_MEMBERS.get() ? null : "colonyboard.reason.rights";
    }

    public static double balance(ServerPlayer p, IColony c, int src) {
        return src == SRC_TREASURY ? ColonyState.treasury(c) : Wallet.balance(p);
    }

    private static boolean charge(ServerPlayer p, IColony c, int src, long cost) {
        if (cost <= 0) return true;
        if (src == SRC_WALLET) return Wallet.take(p, cost);
        if (!PayGuard.fitsTreasuryInt(cost)) return false;
        try {
            int id = c.getID();
            int before = net.machiavelli.minecolonytax.economy.TreasuryManager.getTreasuryBalance(id);
            if (before < cost) return false;
            int after = net.machiavelli.minecolonytax.economy.TreasuryManager.deductFromTreasury(id, (int) cost);
            int removed = before - after;
            if (removed == cost) return true;
            if (removed > 0) {
                try { net.machiavelli.minecolonytax.economy.TreasuryManager.addToTreasury(id, removed); }
                catch (Throwable t) { LOG.error("[colonyboard] treasury rollback of {} failed for colony {}", removed, id, t); }
            }
            return false;
        } catch (Throwable t) { LOG.warn("[colonyboard] treasury charge failed: {}", t.toString()); return false; }
    }

    private static boolean refund(ServerPlayer p, IColony c, int src, long amount) {
        if (amount <= 0) return true;
        if (src == SRC_WALLET) return Wallet.give(p.getServer(), p.getUUID(), amount);
        if (!PayGuard.fitsTreasuryInt(amount)) return false;
        try {
            int id = c.getID();
            int before = net.machiavelli.minecolonytax.economy.TreasuryManager.getTreasuryBalance(id);
            int after = net.machiavelli.minecolonytax.economy.TreasuryManager.addToTreasury(id, (int) amount);
            return after >= before;
        } catch (Throwable t) { LOG.error("[colonyboard] treasury refund of {} failed", amount, t); return false; }
    }

    private static boolean settled(UUID id) {
        long now = System.currentTimeMillis();
        if (SETTLED.size() > 4096) SETTLED.entrySet().removeIf(e -> now - e.getValue() > SETTLED_MS);
        Long at = SETTLED.get(id);
        return at != null && now - at < SETTLED_MS;
    }

    public static UUID uuid(IRequest<?> r) {
        Object o = r.getId().getIdentifier();
        return o instanceof UUID u ? u : UUID.nameUUIDFromBytes(String.valueOf(o).getBytes());
    }

    /** All open deliverable requests of all buildings; ids empty = all. */
    public static List<Line> scan(IColony colony, Set<UUID> ids, boolean creative) {
        List<Line> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<Integer> citizenIds = new ArrayList<>();
        citizenIds.add(-1);
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) citizenIds.add(cd.getId());
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            for (int cid : citizenIds) {
                Collection<IRequest<?>> reqs;
                try { reqs = b.getOpenRequests(cid); } catch (Throwable t) { continue; }
                if (reqs == null) continue;
                for (IRequest<?> r : reqs) {
                    try {
                        UUID id = uuid(r);
                        if (!seen.add(id) || (!ids.isEmpty() && !ids.contains(id))) continue;
                        if (!OPEN.contains(r.getState())) continue;
                        out.add(line(id, r, b, creative));
                    } catch (Throwable t) { LOG.debug("[colonyboard] scan: {}", t.toString()); }
                }
            }
        }
        return out;
    }

    private static Line line(UUID id, IRequest<?> r, IBuilding b, boolean creative) {
        Object rq = r.getRequest();
        if (!(rq instanceof IDeliverable d)) return new Line(id, r, b, ItemStack.EMPTY, 0, -1, "colonyboard.reason.type");
        int qty = Math.min(Math.max(1, d.getCount()), BoardConfig.MAX_PER_REQUEST.get());
        List<ItemStack> cands = new ArrayList<>();
        if (rq instanceof Stack s) cands.add(s.getStack());
        else if (rq instanceof StackList sl) cands.addAll(sl.getStacks());
        else try { for (ItemStack c : r.getDisplayStacks()) if (d.matches(c)) cands.add(c); } catch (Throwable ignored) {}
        ItemStack best = ItemStack.EMPTY; double bestUnit = -1; String reason = "colonyboard.reason.type";
        boolean tool = rq instanceof com.minecolonies.api.colony.requestsystem.requestable.Tool;
        for (int pass = tool ? 0 : 1; pass < 2 && bestUnit < 0; pass++)
        for (int i = 0; i < cands.size() && i < 64; i++) {
            ItemStack c = cands.get(i).copyWithCount(1);
            if (c.isEmpty()) continue;
            // tool display stacks carry only a Damage tag: treat as a plain new item
            if (c.hasTag() && (c.getTag().getAllKeys().stream().allMatch(k -> k.equals("Damage")) || (c.isDamageableItem() && !c.isEnchanted()))) c = new ItemStack(c.getItem());
            if (c.hasTag() && !creative) LOG.debug("[colonyboard] candidate {} tag {}", c, c.getTag());
            if (creative) { best = c; bestUnit = 0; reason = null; break; }
            Pricing.Quote q = tool ? Pricing.toolQuote(c, pass == 0) : Pricing.quote(c);
            if (!q.ok()) { if (best.isEmpty()) reason = q.reason(); continue; }
            if (bestUnit < 0 || q.unit() < bestUnit) { best = c; bestUnit = q.unit(); reason = null; }
        }
        if (best.isEmpty() && !cands.isEmpty()) best = cands.get(0).copyWithCount(1);
        return new Line(id, r, b, best, qty, bestUnit, reason);
    }

    public record Result(int requests, int items, long spent, String error) {}

    /** Re-quotes on the server; refuses if the price rose above what the player confirmed. src = SRC_TREASURY / SRC_WALLET. */
    public static Result execute(ServerPlayer p, IColony colony, Set<UUID> ids, long confirmedTotal, int src) {
        boolean creative = p.isCreative() && isManager(p, colony) && BoardConfig.CREATIVE_FREE.get() && BoardConfig.FULFIL_ENABLED.get();
        if (!creative) {
            String deny = sourceDeny(p, colony, src);
            if (deny != null) return new Result(0, 0, 0, deny);
        }
        List<Line> lines = scan(colony, ids, creative);
        long total = 0;
        for (Line l : lines) if (l.ok()) total += creative ? 0 : l.cost();
        if (!creative && total > confirmedTotal) return new Result(0, 0, 0, "colonyboard.reason.pricechanged");
        if (!creative && balance(p, colony, src) < total)
            return new Result(0, 0, 0, src == SRC_TREASURY ? "colonyboard.reason.funds.treasury" : "colonyboard.reason.funds.wallet");
        int reqs = 0, items = 0; long spent = 0;
        for (Line l : lines) {
            if (!l.ok()) continue;
            if (settled(l.id)) continue;
            boolean open = l.req != null && OPEN.contains(l.req.getState());
            BlockEntity te = null;
            boolean alive = false;
            try {
                te = l.building.getTileEntity();
                alive = te != null && !te.isRemoved() && colony.getWorld().isLoaded(l.building.getID());
            } catch (Throwable ignored) {}
            long est = creative ? 0 : l.cost();
            if (!creative && src == SRC_TREASURY && !PayGuard.fitsTreasuryInt(est)) {
                LOG.warn("[colonyboard] treasury cost {} does not fit an int, request {} skipped", est, l.id);
                continue;
            }
            if (est > 0 && balance(p, colony, src) < est) break;
            boolean charged = est <= 0 || charge(p, colony, src, est);
            if (!PayGuard.deliverAfterCharge(open, alive, charged)) {
                if (charged && est > 0 && !refund(p, colony, src, est))
                    LOG.error("[colonyboard] refund of {} failed after refusing delivery for {}", est, p.getName().getString());
                if (!charged && est > 0) break;
                continue;
            }
            int done = 0;
            try { done = insert(te, l.stack, l.qty); }
            catch (Throwable t) { LOG.warn("[colonyboard] insert failed: {}", t.toString()); }
            if (done <= 0) {
                if (est > 0 && !refund(p, colony, src, est))
                    LOG.error("[colonyboard] refund of {} failed after empty insert for {}", est, p.getName().getString());
                continue;
            }
            long actual = creative ? 0 : Pricing.cost(l.unit, done);
            long back = PayGuard.refundOf(est, actual);
            long kept = est;
            if (back > 0) {
                if (refund(p, colony, src, back)) kept = actual;
                else LOG.error("[colonyboard] partial refund of {} failed for {}", back, p.getName().getString());
            }
            spent += kept; items += done; reqs++;
            boolean overruled = false;
            try { colony.getRequestManager().overruleRequest(l.req.getId(), l.stack.copyWithCount(done)); overruled = true; }
            catch (Throwable t) { LOG.warn("[colonyboard] overrule failed: {}", t.toString()); }
            if (overruled || done >= l.qty) SETTLED.put(l.id, System.currentTimeMillis());
            LOG.info("[colonyboard] {} fulfilled {} x{} for {} in colony {} ({}), cost {}", p.getName().getString(),
                ForgeRegistries.ITEMS.getKey(l.stack.getItem()), done, l.building.getBuildingType().getRegistryName(), colony.getID(),
                creative ? "creative" : (src == SRC_TREASURY ? "treasury" : "wallet"), kept);
        }
        if (!creative && spent > 0) {
            if (src == SRC_TREASURY) {
                try { net.machiavelli.minecolonytax.economy.TreasuryManager.save(); } catch (Throwable t) { LOG.warn("[colonyboard] treasury save failed: {}", t.toString()); }
                Finance.journalAdd(colony, p.getGameProfile().getName(), "colonyboard.fin.log.fulfil.treasury", spent, items);
            } else Finance.journalAdd(colony, p.getGameProfile().getName(), "colonyboard.fin.log.fulfil.wallet", spent, items);
        }
        return new Result(reqs, items, spent, null);
    }

    private static int insert(BlockEntity te, ItemStack proto, int qty) {
        int done = 0, max = Math.max(1, proto.getMaxStackSize());
        while (done < qty) {
            int n = Math.min(max, qty - done);
            ItemStack rest = InventoryUtils.addItemStackToProviderWithResult(te, proto.copyWithCount(n));
            int ins = n - (rest == null || rest.isEmpty() ? 0 : rest.getCount());
            done += ins;
            if (ins < n) break;
        }
        if (done > 0) te.setChanged();
        return done;
    }

    public static Component name(Line l) {
        return l.stack.isEmpty() ? l.req.getShortDisplayString() : l.stack.getHoverName();
    }
}
