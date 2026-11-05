package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import ru.wik.colonyboard.BoardConfig;
import ru.wik.colonyboard.Net;
import ru.wik.colonyboard.logic.DimensionColony;
import ru.wik.colonyboard.logic.PayGuard;

import java.util.*;

/**
 * Colony market + auctions. Goods are moved OUT of the warehouse into escrow when listed / put up for auction, so a
 * sale never depends on the seller's chunks being loaded and couriers can not consume goods that are on sale.
 * Buyer money: SDM wallet -> colony treasury (WarNTaxes). Auction bids are escrowed from the bidder's wallet and
 * refunded when outbid. Everything runs on the server thread; state lives in world/data/colonyboard_market.dat.
 */
public final class Market extends SavedData {
    public static final int LIST = 0, UNLIST = 1, BUY = 2, AUCTION = 3, BID = 4, CANCEL = 5, CLAIM = 6;
    private static final Logger LOG = LogUtils.getLogger();
    private static final String NAME = "colonyboard_market";
    private static final Map<UUID, Long> LAST = new HashMap<>();
    private static int ticks;

    public static final class Listing {
        long id; int colony; String dim = ""; String colonyName = ""; UUID seller; String sellerName = "";
        ItemStack proto = ItemStack.EMPTY; int units, lotSize, lotPrice;
    }
    public static final class Lot {
        long id; int colony; String dim = ""; String colonyName = ""; UUID seller; String sellerName = "";
        ItemStack proto = ItemStack.EMPTY; int units, start, step, bid; long end;
        UUID bidder; String bidderName = "";
        int minNext() { return bidder == null ? start : (int) Math.min(Integer.MAX_VALUE, (long) bid + step); }
    }
    private record Credit(long added, long parked) {}
    private record Resolved(IColony colony, String dim, boolean gone, boolean ambiguous) {}
    private record Pending(ItemStack proto, int count) {}

    private long nextId = 1;
    private final Map<Long, Listing> listings = new LinkedHashMap<>();
    private final Map<Long, Lot> lots = new LinkedHashMap<>();
    private final Map<UUID, List<Pending>> pending = new HashMap<>();
    private final Map<Integer, Long> pendingTreasury = new HashMap<>();

    // ------------------------------------------------------------------ persistence
    public static Market get() {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        return srv.overworld().getDataStorage().computeIfAbsent(Market::load, Market::new, NAME);
    }

    private static CompoundTag item(ItemStack proto, int count) {
        CompoundTag t = new CompoundTag();
        t.put("item", proto.copyWithCount(1).save(new CompoundTag()));
        t.putInt("n", count);
        return t;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("nextId", nextId);
        ListTag ls = new ListTag();
        for (Listing l : listings.values()) {
            CompoundTag t = item(l.proto, l.units);
            t.putLong("id", l.id); t.putInt("colony", l.colony); t.putString("dim", l.dim == null ? "" : l.dim); t.putString("colonyName", l.colonyName);
            if (l.seller != null) t.putUUID("seller", l.seller);
            t.putString("sellerName", l.sellerName); t.putInt("lotSize", l.lotSize); t.putInt("lotPrice", l.lotPrice);
            ls.add(t);
        }
        tag.put("listings", ls);
        ListTag as = new ListTag();
        for (Lot a : lots.values()) {
            CompoundTag t = item(a.proto, a.units);
            t.putLong("id", a.id); t.putInt("colony", a.colony); t.putString("dim", a.dim == null ? "" : a.dim); t.putString("colonyName", a.colonyName);
            if (a.seller != null) t.putUUID("seller", a.seller);
            t.putString("sellerName", a.sellerName); t.putInt("start", a.start); t.putInt("step", a.step);
            t.putInt("bid", a.bid); t.putLong("end", a.end);
            if (a.bidder != null) { t.putUUID("bidder", a.bidder); t.putString("bidderName", a.bidderName); }
            as.add(t);
        }
        tag.put("auctions", as);
        ListTag ps = new ListTag();
        for (var e : pending.entrySet()) {
            for (Pending p : e.getValue()) { CompoundTag t = item(p.proto(), p.count()); t.putUUID("owner", e.getKey()); ps.add(t); }
        }
        tag.put("pending", ps);
        CompoundTag tr = new CompoundTag();
        for (var e : pendingTreasury.entrySet()) tr.putLong(String.valueOf(e.getKey()), e.getValue());
        tag.put("pendingTreasury", tr);
        return tag;
    }

    private static Market load(CompoundTag tag) {
        Market m = new Market();
        m.nextId = Math.max(1, tag.getLong("nextId"));
        for (Tag x : tag.getList("listings", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) x; Listing l = new Listing();
            l.proto = ItemStack.of(t.getCompound("item")); l.units = t.getInt("n");
            l.id = t.getLong("id"); l.colony = t.getInt("colony"); l.dim = t.getString("dim"); l.colonyName = t.getString("colonyName");
            if (t.hasUUID("seller")) l.seller = t.getUUID("seller");
            l.sellerName = t.getString("sellerName"); l.lotSize = Math.max(1, t.getInt("lotSize")); l.lotPrice = t.getInt("lotPrice");
            if (!l.proto.isEmpty() && l.units > 0) m.listings.put(l.id, l);
        }
        for (Tag x : tag.getList("auctions", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) x; Lot a = new Lot();
            a.proto = ItemStack.of(t.getCompound("item")); a.units = t.getInt("n");
            a.id = t.getLong("id"); a.colony = t.getInt("colony"); a.dim = t.getString("dim"); a.colonyName = t.getString("colonyName");
            if (t.hasUUID("seller")) a.seller = t.getUUID("seller");
            a.sellerName = t.getString("sellerName"); a.start = t.getInt("start"); a.step = t.getInt("step");
            a.bid = t.getInt("bid"); a.end = t.getLong("end");
            if (t.hasUUID("bidder")) { a.bidder = t.getUUID("bidder"); a.bidderName = t.getString("bidderName"); }
            if (!a.proto.isEmpty() && a.units > 0) m.lots.put(a.id, a);
        }
        for (Tag x : tag.getList("pending", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) x;
            ItemStack s = ItemStack.of(t.getCompound("item"));
            if (!s.isEmpty() && t.getInt("n") > 0 && t.hasUUID("owner"))
                m.pending.computeIfAbsent(t.getUUID("owner"), k -> new ArrayList<>()).add(new Pending(s, t.getInt("n")));
        }
        CompoundTag tr = tag.getCompound("pendingTreasury");
        for (String k : tr.getAllKeys()) try { m.pendingTreasury.put(Integer.parseInt(k), tr.getLong(k)); } catch (NumberFormatException ignored) {}
        return m;
    }

    // ------------------------------------------------------------------ helpers
    public static boolean treasuryOn() {
        if (!ModList.get().isLoaded("minecolonytax")) return false;
        try { return net.machiavelli.minecolonytax.TaxConfig.isTreasuryEnabled(); } catch (Throwable t) { return false; }
    }

    /** Free space in the colony treasury (WarNTaxes caps the balance at its capacity). */
    public static long room(int colonyId) {
        try {
            long cap = net.machiavelli.minecolonytax.economy.TreasuryManager.getEffectiveMaxCapacity(colonyId);
            long bal = net.machiavelli.minecolonytax.economy.TreasuryManager.getTreasuryBalance(colonyId);
            return Math.max(0, cap - bal);
        } catch (Throwable t) { return 0; }
    }

    /**
     * Credits the treasury. Whatever does not fit is parked and retried.
     * The sum of {@code added + parked} equals {@code amount} unless the treasury call throws.
     */
    private Credit credit(int colonyId, long amount) {
        if (amount <= 0) return new Credit(0, 0);
        int before = net.machiavelli.minecolonytax.economy.TreasuryManager.getTreasuryBalance(colonyId);
        long put = Math.min(amount, Math.max(0, room(colonyId)));
        int after = before;
        if (put > 0) {
            int slice = (int) Math.min(Integer.MAX_VALUE, put);
            after = net.machiavelli.minecolonytax.economy.TreasuryManager.addToTreasury(colonyId, slice);
        }
        long added = Math.max(0, (long) after - before);
        long left = amount - added;
        if (left > 0) { pendingTreasury.merge(colonyId, left, Long::sum); setDirty(); }
        return new Credit(added, Math.max(0, left));
    }

    /** Wallet is returned in full if the treasury credit throws. Items must not move when this returns false. */
    private boolean takeAndCredit(ServerPlayer p, IColony c, long cost) {
        if (!Wallet.take(p, cost)) { fail(p, "colonyboard.reason.error"); return false; }
        int id = c.getID();
        int before = net.machiavelli.minecolonytax.economy.TreasuryManager.getTreasuryBalance(id);
        long parkedBefore = pendingTreasury.getOrDefault(id, 0L);
        try {
            Credit cr = credit(id, cost);
            long gap = PayGuard.shortfall(cost, cr.added, cr.parked);
            if (gap > 0) throw new IllegalStateException("unaccounted " + gap);
            return true;
        } catch (Throwable t) {
            LOG.error("[colonyboard/market] credit of {} to colony {} failed", cost, id, t);
            try {
                int now = net.machiavelli.minecolonytax.economy.TreasuryManager.getTreasuryBalance(id);
                if (now > before) net.machiavelli.minecolonytax.economy.TreasuryManager.deductFromTreasury(id, now - before);
            } catch (Throwable t2) { LOG.error("[colonyboard/market] treasury restore failed", t2); }
            if (parkedBefore <= 0) pendingTreasury.remove(id); else pendingTreasury.put(id, parkedBefore);
            if (!Wallet.give(p.getServer(), p.getUUID(), cost))
                LOG.error("[colonyboard/market] wallet restore of {} to {} failed", cost, p.getGameProfile().getName());
            fail(p, "colonyboard.reason.error");
            return false;
        }
    }

    /**
     * Resolve a stored colony id. Legacy rows without a dimension bind only when exactly one loaded
     * colony has that id. Several matches are ambiguous and must not move goods. A loaded dimension
     * with no such colony means the colony is gone.
     */
    private Resolved resolve(String storedDim, int colonyId) {
        java.util.ArrayList<String> found = new java.util.ArrayList<>();
        try {
            for (IColony c : IColonyManager.getInstance().getAllColonies())
                if (c != null && c.getID() == colonyId) found.add(ColonyKey.dimension(c));
        } catch (Throwable t) { return new Resolved(null, storedDim, false, false); }
        DimensionColony.Bind bind = DimensionColony.bind(storedDim, found);
        if (bind.status() == DimensionColony.Status.AMBIGUOUS) return new Resolved(null, null, false, true);
        if (!bind.bound()) return new Resolved(null, null, false, false);
        ResourceLocation rl = ResourceLocation.tryParse(bind.dimension());
        if (rl == null) return new Resolved(null, null, false, false);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, rl);
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = srv == null ? null : srv.getLevel(key);
        IColony c = null;
        try { c = IColonyManager.getInstance().getColonyByDimension(colonyId, key); } catch (Throwable ignored) {}
        if (c != null) return new Resolved(c, bind.dimension(), false, false);
        return new Resolved(null, bind.dimension(), DimensionColony.confirmedGone(level != null, false), false);
    }

    private void remember(String stored, int colonyId, java.util.function.Consumer<String> setDim) {
        if (stored != null && !stored.isEmpty()) return;
        Resolved r = resolve(stored, colonyId);
        if (r.dim != null && !r.dim.isEmpty() && !r.ambiguous) { setDim.accept(r.dim); setDirty(); }
    }

    private boolean sameColony(String dim, int id, IColony c) {
        return c != null && c.getID() == id && dim != null && dim.equals(ColonyKey.dimension(c));
    }

    private static boolean manager(ServerPlayer p, IColony c) { return c != null && c.getPermissions().isColonyMember(p) && Fulfil.isManager(p, c); }

    private static void managers(MinecraftServer srv, IColony c, Component msg) {
        if (c == null) return;
        for (ServerPlayer o : srv.getPlayerList().getPlayers()) if (manager(o, c)) Net.toast(o, Net.Toast.INFO, MKT, msg, false, msg);
    }

    private static final Component MKT = Component.translatable("colonyboard.toast.t.market");
    private static void ok(ServerPlayer p, Component msg) { Net.toast(p, Net.Toast.OK, MKT, msg, false, msg.copy().withStyle(ChatFormatting.GREEN)); }

    private static void fail(ServerPlayer p, String key) {
        Component chat = Component.translatable("colonyboard.fin.fail", Component.translatable(key)).withStyle(ChatFormatting.RED);
        Net.toast(p, Net.Toast.FAIL, Component.translatable("colonyboard.toast.t.mktfail"), Component.translatable(key), false, chat);
    }

    private static Component name(ItemStack proto, int n) { return Component.literal(n + "× ").append(proto.getHoverName()); }

    private void addPending(UUID owner, ItemStack proto, int n) {
        if (owner == null || n <= 0) return;
        pending.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Pending(proto.copyWithCount(1), n));
        setDirty();
    }

    public int pendingCount(UUID id) {
        long n = 0; for (Pending p : pending.getOrDefault(id, List.of())) n += p.count();
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** Moves pending items into the player's inventory as far as they fit; returns items delivered. */
    private int deliver(ServerPlayer p) {
        List<Pending> l = pending.get(p.getUUID());
        if (l == null || l.isEmpty()) return 0;
        int done = 0;
        List<Pending> rest = new ArrayList<>();
        boolean full = false;
        for (Pending e : l) {
            int left = e.count(), max = Math.max(1, e.proto().getMaxStackSize());
            while (left > 0 && !full) {
                int k = Math.min(max, left);
                ItemStack s = e.proto().copyWithCount(k);
                p.getInventory().add(s);
                int ins = k - s.getCount();
                left -= ins; done += ins;
                if (ins < k) full = true;
            }
            if (left > 0) rest.add(new Pending(e.proto(), left));
        }
        if (rest.isEmpty()) pending.remove(p.getUUID()); else pending.put(p.getUUID(), rest);
        if (done > 0) { p.containerMenu.broadcastChanges(); setDirty(); }
        return done;
    }

    /** Back to the colony warehouse; what does not fit (or no warehouse loaded) goes to the owner's claim box. */
    private void giveBack(IColony c, UUID owner, ItemStack proto, int n) {
        int left = c == null ? n : Warehouse.insert(c, proto, n);
        if (left > 0) addPending(owner, proto, left);
    }

    private static boolean rate(ServerPlayer p, long ms) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(p.getUUID());
        if (last != null && now - last < ms) return false;
        LAST.put(p.getUUID(), now);
        return true;
    }

    // ------------------------------------------------------------------ denies (lang key or null)
    public static String marketDeny() {
        if (!BoardConfig.MKT_ENABLED.get()) return "colonyboard.reason.disabled";
        if (!Wallet.available()) return "colonyboard.reason.nowallet";
        if (!treasuryOn()) return "colonyboard.mkt.notreasury";
        return null;
    }
    public static String auctionDeny() {
        if (!BoardConfig.AUC_ENABLED.get()) return "colonyboard.reason.disabled";
        return marketDeny();
    }
    /** May p put goods of colony c on sale? */
    public static String sellDeny(ServerPlayer p, IColony c) {
        if (c == null) return "colonyboard.nocolony";
        if (!manager(p, c)) return "colonyboard.reason.rights";
        if (!Warehouse.available(c)) return "colonyboard.mkt.nowarehouse";
        return null;
    }
    private static String buyDeny(ServerPlayer p, IColony seller) {
        if (BoardConfig.MKT_OWN_BUY.get()) return null;
        return seller != null && seller.getPermissions().isColonyMember(p) ? "colonyboard.mkt.own" : null;
    }

    private boolean countsToward(String dim, int id, IColony c) {
        if (c == null || c.getID() != id) return false;
        if (dim == null || dim.isEmpty()) return true;
        return dim.equals(ColonyKey.dimension(c));
    }

    private void bindLoose() {
        for (Listing l : listings.values()) remember(l.dim, l.colony, d -> l.dim = d);
        for (Lot l : lots.values()) remember(l.dim, l.colony, d -> l.dim = d);
    }

    // ------------------------------------------------------------------ actions
    public static void handle(ServerPlayer p, IColony own, Net.MarketAction a) {
        if (!rate(p, 400)) { fail(p, "colonyboard.reason.tooFast"); return; }
        Market m = get();
        try {
            switch (a.op()) {
                case LIST -> m.list(p, own, a);
                case UNLIST -> m.unlist(p, a.id());
                case BUY -> m.buy(p, a.id(), a.a(), a.b());
                case AUCTION -> m.auction(p, own, a);
                case BID -> m.bid(p, a.id(), a.a());
                case CANCEL -> m.cancel(p, a.id());
                case CLAIM -> {
                    int n = m.deliver(p);
                    if (n > 0) ok(p, Component.translatable("colonyboard.mkt.claimed", n, m.pendingCount(p.getUUID())));
                    else fail(p, m.pendingCount(p.getUUID()) > 0 ? "colonyboard.mkt.invfull" : "colonyboard.mkt.nothing");
                }
                default -> fail(p, "colonyboard.reason.error");
            }
        } catch (Throwable t) {
            LOG.error("[colonyboard/market] op {} failed", a.op(), t);
            fail(p, "colonyboard.reason.error");
        }
    }

    private int take(ServerPlayer p, IColony c, ItemStack proto, int units) {
        String d = sellDeny(p, c);
        if (d != null) { fail(p, d); return -1; }
        if (proto.isEmpty() || units <= 0 || units > BoardConfig.MKT_MAX_UNITS.get()) { fail(p, "colonyboard.reason.amount"); return -1; }
        if (Warehouse.count(c, proto) < units) { fail(p, "colonyboard.mkt.nostock"); return -1; }
        int got = Warehouse.extract(c, proto, units);
        if (got < units) { giveBack(c, p.getUUID(), proto, got); fail(p, "colonyboard.mkt.nostock"); return -1; }
        return got;
    }

    private void list(ServerPlayer p, IColony c, Net.MarketAction a) {
        String d = marketDeny();
        if (d != null) { fail(p, d); return; }
        int lotSize = a.a(), lotsN = a.b(), price = a.c();
        if (lotSize <= 0 || lotsN <= 0 || price <= 0 || price > BoardConfig.FIN_MAX.get()
            || (long) lotSize * lotsN > BoardConfig.MKT_MAX_UNITS.get()) { fail(p, "colonyboard.reason.amount"); return; }
        bindLoose();
        if (c != null && listings.values().stream().filter(l -> countsToward(l.dim, l.colony, c)).count() >= BoardConfig.MKT_MAX_LISTINGS.get()) { fail(p, "colonyboard.mkt.limit"); return; }
        ItemStack proto = a.item().copyWithCount(1);
        int units = lotSize * lotsN;
        if (take(p, c, proto, units) < 0) return;
        Listing l = new Listing();
        l.id = nextId++; l.colony = c.getID(); l.dim = ColonyKey.dimension(c); l.colonyName = c.getName(); l.seller = p.getUUID(); l.sellerName = p.getGameProfile().getName();
        l.proto = proto; l.units = units; l.lotSize = lotSize; l.lotPrice = price;
        listings.put(l.id, l); setDirty();
        Finance.journalAdd(c, l.sellerName, "colonyboard.mkt.log.list", name(proto, units), price, lotSize);
        ok(p, Component.translatable("colonyboard.mkt.listed", name(proto, units), price, lotSize));
    }

    private void unlist(ServerPlayer p, long id) {
        Listing l = listings.get(id);
        if (l == null) { fail(p, "colonyboard.mkt.gone"); return; }
        remember(l.dim, l.colony, d -> l.dim = d);
        Resolved r = resolve(l.dim, l.colony);
        if (r.ambiguous) { fail(p, "colonyboard.mkt.ambiguous"); return; }
        IColony c = r.colony;
        if (c == null) { fail(p, "colonyboard.mkt.nocolony"); return; }
        if (!manager(p, c)) { fail(p, "colonyboard.reason.rights"); return; }
        listings.remove(id); setDirty();
        giveBack(c, p.getUUID(), l.proto, l.units);
        Finance.journalAdd(c, p.getGameProfile().getName(), "colonyboard.mkt.log.unlist", name(l.proto, l.units));
        ok(p, Component.translatable("colonyboard.mkt.unlisted", name(l.proto, l.units)));
    }

    private void buy(ServerPlayer p, long id, int lotsN, int expectedPrice) {
        String d = marketDeny();
        if (d != null) { fail(p, d); return; }
        Listing l = listings.get(id);
        if (l == null) { fail(p, "colonyboard.mkt.gone"); return; }
        if (l.lotPrice != expectedPrice) { fail(p, "colonyboard.reason.pricechanged"); return; }
        remember(l.dim, l.colony, dim -> l.dim = dim);
        Resolved r = resolve(l.dim, l.colony);
        if (r.ambiguous) { fail(p, "colonyboard.mkt.ambiguous"); return; }
        IColony c = r.colony;
        if (c == null) { fail(p, "colonyboard.mkt.nocolony"); return; }
        if ((d = buyDeny(p, c)) != null) { fail(p, d); return; }
        long units = (long) lotsN * l.lotSize, cost = (long) lotsN * l.lotPrice;
        if (lotsN <= 0 || units > l.units) { fail(p, "colonyboard.mkt.nostock"); return; }
        if (cost > BoardConfig.FIN_MAX.get() || cost > Integer.MAX_VALUE) { fail(p, "colonyboard.reason.amount"); return; }
        if (Wallet.balance(p) < cost) { fail(p, "colonyboard.reason.funds"); return; }
        if (room(c.getID()) < cost) { fail(p, "colonyboard.mkt.treasuryfull"); return; }
        if (!takeAndCredit(p, c, cost)) return;
        l.units -= (int) units;
        if (l.units <= 0) listings.remove(id);
        setDirty();
        addPending(p.getUUID(), l.proto, (int) units);
        deliver(p);
        int left = pendingCount(p.getUUID());
        String who = p.getGameProfile().getName();
        Finance.journalAdd(c, who, "colonyboard.mkt.log.sold", name(l.proto, (int) units), cost);
        ok(p, Component.translatable(left > 0 ? "colonyboard.mkt.bought.box" : "colonyboard.mkt.bought", name(l.proto, (int) units), cost, c.getName()));
        managers(p.getServer(), c, Component.translatable("colonyboard.mkt.sold", who, name(l.proto, (int) units), cost));
        LOG.info("[colonyboard/market] {} bought {} x{} from colony {} for {}", who, l.proto.getItem(), units, c.getID(), cost);
    }

    private void auction(ServerPlayer p, IColony c, Net.MarketAction a) {
        String d = auctionDeny();
        if (d != null) { fail(p, d); return; }
        int units = a.a(), start = a.b(), step = a.c(), minutes = a.d();
        if (start <= 0 || step <= 0 || start > BoardConfig.FIN_MAX.get() || step > BoardConfig.FIN_MAX.get()) { fail(p, "colonyboard.reason.amount"); return; }
        if (minutes < BoardConfig.AUC_MIN_MIN.get() || minutes > BoardConfig.AUC_MAX_MIN.get()) { fail(p, "colonyboard.mkt.duration"); return; }
        bindLoose();
        if (c != null && lots.values().stream().filter(l -> countsToward(l.dim, l.colony, c)).count() >= BoardConfig.AUC_MAX_LOTS.get()) { fail(p, "colonyboard.mkt.limit"); return; }
        ItemStack proto = a.item().copyWithCount(1);
        if (take(p, c, proto, units) < 0) return;
        Lot l = new Lot();
        l.id = nextId++; l.colony = c.getID(); l.dim = ColonyKey.dimension(c); l.colonyName = c.getName(); l.seller = p.getUUID(); l.sellerName = p.getGameProfile().getName();
        l.proto = proto; l.units = units; l.start = start; l.step = step; l.end = System.currentTimeMillis() + minutes * 60_000L;
        lots.put(l.id, l); setDirty();
        Finance.journalAdd(c, l.sellerName, "colonyboard.mkt.log.auction", name(proto, units), start, minutes);
        ok(p, Component.translatable("colonyboard.mkt.auctioned", name(proto, units), start, minutes));
    }

    private void bid(ServerPlayer p, long id, int amount) {
        String d = auctionDeny();
        if (d != null) { fail(p, d); return; }
        Lot l = lots.get(id);
        long now = System.currentTimeMillis();
        if (l == null || now >= l.end) { fail(p, "colonyboard.mkt.gone"); return; }
        remember(l.dim, l.colony, dim -> l.dim = dim);
        Resolved r = resolve(l.dim, l.colony);
        if (r.ambiguous) { fail(p, "colonyboard.mkt.ambiguous"); return; }
        if (r.gone) { fail(p, "colonyboard.mkt.nocolony"); return; }
        if ((d = buyDeny(p, r.colony)) != null) { fail(p, d); return; }
        if (p.getUUID().equals(l.bidder)) { fail(p, "colonyboard.mkt.leading"); return; }
        if (amount < l.minNext()) { fail(p, "colonyboard.mkt.lowbid"); return; }
        if (amount > BoardConfig.FIN_MAX.get()) { fail(p, "colonyboard.reason.amount"); return; }
        if (Wallet.balance(p) < amount) { fail(p, "colonyboard.reason.funds"); return; }
        if (!Wallet.take(p, amount)) { fail(p, "colonyboard.reason.error"); return; }
        UUID prev = l.bidder; int prevBid = l.bid;
        boolean refunded = prev == null || Wallet.give(p.getServer(), prev, prevBid);
        if (!PayGuard.commitBid(prev != null, refunded)) {
            if (!Wallet.give(p.getServer(), p.getUUID(), amount))
                LOG.error("[colonyboard/market] returning bid {} to {} failed", amount, p.getGameProfile().getName());
            LOG.error("[colonyboard/market] refund of {} to {} for auction {} failed; new bid rolled back", prevBid, prev, id);
            fail(p, "colonyboard.reason.error");
            return;
        }
        l.bidder = p.getUUID(); l.bidderName = p.getGameProfile().getName(); l.bid = amount;
        int snipe = BoardConfig.AUC_SNIPE_SEC.get();
        if (snipe > 0 && l.end - now < snipe * 1000L) l.end = now + snipe * 1000L;
        setDirty();
        ok(p, Component.translatable("colonyboard.mkt.bidok", amount, name(l.proto, l.units)));
        if (prev != null) {
            ServerPlayer o = p.getServer().getPlayerList().getPlayer(prev);
            if (o != null) Net.toast(o, Net.Toast.WAR, MKT, Component.translatable("colonyboard.mkt.outbid", name(l.proto, l.units), amount, prevBid), false,
                Component.translatable("colonyboard.mkt.outbid", name(l.proto, l.units), amount, prevBid).withStyle(ChatFormatting.GOLD));
        }
        if (r.colony != null) Finance.journalAdd(r.colony, l.bidderName, "colonyboard.mkt.log.bid", amount, name(l.proto, l.units));
        LOG.info("[colonyboard/market] {} bid {} on auction {} (refunded {} to {})", l.bidderName, amount, id, prevBid, prev);
    }

    private void cancel(ServerPlayer p, long id) {
        Lot l = lots.get(id);
        if (l == null) { fail(p, "colonyboard.mkt.gone"); return; }
        remember(l.dim, l.colony, dim -> l.dim = dim);
        Resolved r = resolve(l.dim, l.colony);
        if (r.ambiguous) { fail(p, "colonyboard.mkt.ambiguous"); return; }
        IColony c = r.colony;
        if (c == null) { fail(p, "colonyboard.mkt.nocolony"); return; }
        if (!manager(p, c)) { fail(p, "colonyboard.reason.rights"); return; }
        if (l.bidder != null) { fail(p, "colonyboard.mkt.hasbids"); return; }
        lots.remove(id); setDirty();
        giveBack(c, p.getUUID(), l.proto, l.units);
        Finance.journalAdd(c, p.getGameProfile().getName(), "colonyboard.mkt.log.cancel", name(l.proto, l.units));
        ok(p, Component.translatable("colonyboard.mkt.cancelled", name(l.proto, l.units)));
    }

    // ------------------------------------------------------------------ tick: end auctions, retry treasury, orphan cleanup
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        try { get().resolve(srv, ticks % 1200 == 0); } catch (Throwable t) { LOG.error("[colonyboard/market] tick failed", t); }
    }

    private void resolve(MinecraftServer srv, boolean minute) {
        long now = System.currentTimeMillis();
        for (Iterator<Lot> it = lots.values().iterator(); it.hasNext(); ) {
            Lot l = it.next();
            Resolved r = resolve(l.dim, l.colony);
            if (!r.ambiguous && r.dim != null && (l.dim == null || l.dim.isEmpty())) { l.dim = r.dim; setDirty(); }
            if (r.ambiguous) {
                if (minute) LOG.warn("[colonyboard/market] auction {} colony {} matches several dimensions; left untouched", l.id, l.colony);
                continue;
            }
            if (r.colony == null && !r.gone) continue;
            if (now < l.end && r.colony != null) continue;
            if (r.colony == null) {
                it.remove(); setDirty();
                if (l.bidder != null) Wallet.give(srv, l.bidder, l.bid);
                addPending(l.seller, l.proto, l.units);
                continue;
            }
            if (l.bidder != null) {
                try { credit(r.colony.getID(), l.bid); }
                catch (Throwable t) { LOG.error("[colonyboard/market] auction {} payout failed; will retry", l.id, t); continue; }
            }
            it.remove(); setDirty();
            if (l.bidder != null) {
                addPending(l.bidder, l.proto, l.units);
                ServerPlayer w = srv.getPlayerList().getPlayer(l.bidder);
                if (w != null) {
                    deliver(w);
                    Component msg = Component.translatable("colonyboard.mkt.won", name(l.proto, l.units), l.bid, r.colony.getName());
                    Net.toast(w, Net.Toast.OK, MKT, msg, false, msg.copy().withStyle(ChatFormatting.GREEN));
                }
                Finance.journalAdd(r.colony, l.bidderName, "colonyboard.mkt.log.won", name(l.proto, l.units), l.bid);
                managers(srv, r.colony, Component.translatable("colonyboard.mkt.aucsold", name(l.proto, l.units), l.bid, l.bidderName));
                LOG.info("[colonyboard/market] auction {} won by {} for {} (colony {})", l.id, l.bidderName, l.bid, r.colony.getID());
            } else {
                giveBack(r.colony, l.seller, l.proto, l.units);
                Finance.journalAdd(r.colony, l.sellerName, "colonyboard.mkt.log.nobids", name(l.proto, l.units));
                managers(srv, r.colony, Component.translatable("colonyboard.mkt.nobids", name(l.proto, l.units)));
            }
        }
        if (!minute) return;
        for (Iterator<Listing> it = listings.values().iterator(); it.hasNext(); ) {
            Listing l = it.next();
            Resolved r = resolve(l.dim, l.colony);
            if (!r.ambiguous && r.dim != null && (l.dim == null || l.dim.isEmpty())) { l.dim = r.dim; setDirty(); }
            if (!r.gone) continue;
            it.remove(); addPending(l.seller, l.proto, l.units); setDirty();
        }
        for (Iterator<Map.Entry<Integer, Long>> it = pendingTreasury.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            boolean seen = false;
            try { for (IColony c : IColonyManager.getInstance().getAllColonies()) if (c != null && c.getID() == en.getKey()) seen = true; }
            catch (Throwable ignored) {}
            if (!seen) continue; // dimension may be unloaded; do not drop escrowed money
            long put = Math.min(en.getValue(), room(en.getKey()));
            if (put <= 0) continue;
            int before = net.machiavelli.minecolonytax.economy.TreasuryManager.getTreasuryBalance(en.getKey());
            int after = net.machiavelli.minecolonytax.economy.TreasuryManager.addToTreasury(en.getKey(), (int) Math.min(Integer.MAX_VALUE, put));
            long added = Math.max(0, (long) after - before);
            if (added <= 0) continue;
            if (added >= en.getValue()) it.remove(); else en.setValue(en.getValue() - added);
            setDirty();
        }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        try {
            int n = get().pendingCount(p.getUUID());
            if (n > 0) Net.toast(p, Net.Toast.INFO, MKT, Component.translatable("colonyboard.mkt.waiting", n), false,
                Component.translatable("colonyboard.mkt.waiting", n).withStyle(ChatFormatting.AQUA));
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ view
    public Net.MarketData view(ServerPlayer p, IColony own) {
        bindLoose();
        int ownId = own == null ? -1 : own.getID();
        boolean mgr = manager(p, own);
        long now = System.currentTimeMillis();
        List<Net.MListing> ls = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (ls.size() >= 300) break;
            ls.add(new Net.MListing(l.id, l.colony, l.colonyName, l.proto, l.units, l.lotSize, l.lotPrice, mgr && sameColony(l.dim, l.colony, own)));
        }
        ls.sort(Comparator.comparing((Net.MListing x) -> x.colony() != ownId).thenComparing(x -> x.item().getHoverName().getString()));
        List<Net.MLot> as = new ArrayList<>();
        for (Lot l : lots.values()) {
            if (as.size() >= 200) break;
            as.add(new Net.MLot(l.id, l.colony, l.colonyName, l.proto, l.units, l.minNext(), l.bidder == null ? 0 : l.bid,
                l.bidder == null ? "" : l.bidderName, Math.max(0, l.end - now), mgr && sameColony(l.dim, l.colony, own), p.getUUID().equals(l.bidder)));
        }
        as.sort(Comparator.comparingLong(Net.MLot::msLeft));
        String sd = own == null ? "colonyboard.nocolony" : sellDeny(p, own);
        List<Net.MStock> st = new ArrayList<>();
        if (sd == null) for (Warehouse.Stock s : Warehouse.stock(own, 400)) st.add(new Net.MStock(s.proto(), s.count()));
        String md = marketDeny(), ad = auctionDeny();
        return new Net.MarketData(ownId, mgr, Wallet.balance(p), own == null ? -1 : ColonyState.treasury(own),
            md == null ? "" : md, ad == null ? "" : ad, sd == null ? "" : sd, pendingCount(p.getUUID()),
            BoardConfig.AUC_MIN_MIN.get(), BoardConfig.AUC_MAX_MIN.get(), ls, as, st);
    }
}
