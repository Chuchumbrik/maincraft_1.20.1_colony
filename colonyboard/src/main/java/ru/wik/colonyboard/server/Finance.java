package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.sixik.sdmeconomy.api.EconomyAPI;
import net.sixik.sdmeconomy.currencies.data.CurrencyPlayerData;
import org.slf4j.Logger;
import ru.wik.colonyboard.BoardConfig;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Money operations of the Finance tab. Every check is done here, on the server. */
public final class Finance {
    public static final int DEPOSIT = 0, WITHDRAW = 1, CLAIM = 2, DEBT = 3, TRANSFER = 4;
    public static final String[] SOURCES = {"wallet", "tax", "inventory"};
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<String, Deque<Component>> JOURNAL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private Finance() {}

    public static boolean wnt() { return ModList.get().isLoaded("minecolonytax"); }

    public static long storedTax(IColony c) {
        if (!wnt()) return Long.MIN_VALUE;
        try { return net.machiavelli.minecolonytax.TaxManager.getStoredTaxForColony(c); } catch (Throwable t) { return Long.MIN_VALUE; }
    }
    public static long capacity(IColony c) {
        if (!wnt()) return -1;
        try { return net.machiavelli.minecolonytax.economy.TreasuryManager.getEffectiveMaxCapacity(c.getID()); } catch (Throwable t) { return -1; }
    }
    public static List<Component> journal(IColony c) {
        Deque<Component> d = JOURNAL.get(ColonyKey.of(c));
        return d == null ? List.of() : new ArrayList<>(d);
    }

    private static void log(IColony c, ServerPlayer p, String key, Object... args) { journalAdd(c, p.getGameProfile().getName(), key, args); }

    /** Journal line "HH:mm who: ..." for colony c (also used by Market). */
    public static void journalAdd(IColony c, String who, String key, Object... args) {
        Object[] a = new Object[args.length + 2];
        a[0] = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        a[1] = who;
        System.arraycopy(args, 0, a, 2, args.length);
        Deque<Component> d = JOURNAL.computeIfAbsent(ColonyKey.of(c), k -> new ArrayDeque<>());
        synchronized (d) { d.addFirst(Component.translatable(key, a)); while (d.size() > BoardConfig.FIN_JOURNAL.get()) d.removeLast(); }
        LOG.info("[colonyboard/finance] colony {} {} {} {}", c.getID(), who, key, Arrays.toString(args));
    }

    private static net.machiavelli.minecolonytax.integration.CurrencyService.Source src(int i) {
        return switch (i) { case 1 -> net.machiavelli.minecolonytax.integration.CurrencyService.Source.TAX_BALANCE;
            case 2 -> net.machiavelli.minecolonytax.integration.CurrencyService.Source.INVENTORY;
            default -> net.machiavelli.minecolonytax.integration.CurrencyService.Source.WALLET; };
    }


    /** null if the op may be shown as available, else lang key. */
    public static String deny(ServerPlayer p, IColony c, int op) {
        if (!BoardConfig.FIN_ENABLED.get()) return "colonyboard.reason.disabled";
        boolean manager = Fulfil.isManager(p, c);
        switch (op) {
            case DEPOSIT -> {
                if (!BoardConfig.FIN_DEPOSIT.get() || !wnt()) return "colonyboard.reason.disabled";
                if (!manager && !BoardConfig.FIN_DEPOSIT_MEMBERS.get()) return "colonyboard.reason.rights";
            }
            case WITHDRAW -> {
                if (!BoardConfig.FIN_WITHDRAW.get() || !wnt()) return "colonyboard.reason.disabled";
                if (!manager) return "colonyboard.reason.rights";
            }
            case CLAIM, DEBT -> {
                if (!(op == CLAIM ? BoardConfig.FIN_CLAIM : BoardConfig.FIN_DEBT).get() || !wnt()) return "colonyboard.reason.disabled";
                if (!manager) return "colonyboard.reason.rights";
            }
            case TRANSFER -> {
                if (!BoardConfig.FIN_TRANSFER.get() || !Wallet.available()) return "colonyboard.reason.disabled";
            }
            default -> { return "colonyboard.reason.disabled"; }
        }
        // 1.4: no war lock - withdraw, claim and transfers stay open during war / siege / raid
        return null;
    }

    public static void handle(ServerPlayer p, IColony c, int op, int amount, int source, String target) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(p.getUUID());
        if (last != null && now - last < 1000) { fail(p, "colonyboard.reason.tooFast"); return; }
        LAST.put(p.getUUID(), now);
        String d = deny(p, c, op);
        if (d != null) { fail(p, d); return; }
        if (op != CLAIM && (amount <= 0 || amount > BoardConfig.FIN_MAX.get())) { fail(p, "colonyboard.reason.amount"); return; }
        try {
            switch (op) {
                case DEPOSIT -> {
                    long before = ColonyState.treasury(c);
                    if (net.machiavelli.minecolonytax.economy.TreasuryManager.deposit(p, c.getID(), amount, src(source))) {
                        long got = ColonyState.treasury(c) - before;
                        log(c, p, "colonyboard.fin.log.deposit", got, Component.translatable("colonyboard.fin.src." + SOURCES[Math.floorMod(source, 3)]));
                        ok(p, Component.translatable("colonyboard.toast.deposit", got > 0 ? got : amount), null);
                    } else fail(p, "colonyboard.reason.wnt");
                }
                case WITHDRAW -> {
                    if (net.machiavelli.minecolonytax.economy.TreasuryManager.withdraw(p, c.getID(), amount, src(source))) {
                        log(c, p, "colonyboard.fin.log.withdraw", amount, Component.translatable("colonyboard.fin.src." + SOURCES[Math.floorMod(source, 3)]));
                        ok(p, Component.translatable("colonyboard.toast.withdraw", amount), null);
                    } else fail(p, "colonyboard.reason.wnt");
                }
                case CLAIM -> {
                    boolean owner = false, officer = false;
                    try { var perm = c.getPermissions(); var r = perm.getRank(p); owner = r != null && r.equals(perm.getRankOwner()); officer = owner || (r != null && r.equals(perm.getRankOfficer())); } catch (Throwable ignored) {}
                    if (!net.machiavelli.minecolonytax.permissions.TaxPermissionManager.canPlayerClaimTax(c.getID(), p.getUUID(), owner, officer)) { fail(p, "colonyboard.reason.rights"); return; }
                    if (storedTax(c) <= 0) { fail(p, "colonyboard.reason.notax"); return; }
                    int got = net.machiavelli.minecolonytax.TaxManager.claimTax(c, amount > 0 ? amount : -1);
                    if (got <= 0) { fail(p, "colonyboard.reason.notax"); return; }
                    if (net.machiavelli.minecolonytax.integration.CurrencyService.deliverClaimedTaxOrRefund(p, c, got)) {
                        ok(p, Component.translatable("colonyboard.toast.claim", got), Component.translatable("colonyboard.fin.claimed", got).withStyle(ChatFormatting.GREEN));
                        log(c, p, "colonyboard.fin.log.claim", got);
                    }
                }
                case DEBT -> {
                    long debt = -storedTax(c);
                    if (debt <= 0) { fail(p, "colonyboard.reason.nodebt"); return; }
                    int pay = (int) Math.min(amount, debt);
                    if (Wallet.balance(p) < pay) { fail(p, "colonyboard.reason.funds"); return; }
                    if (!Wallet.take(p, pay)) { fail(p, "colonyboard.reason.error"); return; }
                    try { net.machiavelli.minecolonytax.TaxManager.payTaxDebt(c, pay); }
                    catch (Throwable t) {
                        Wallet.give(p.getServer(), p.getUUID(), pay);
                        throw t;
                    }
                    ok(p, Component.translatable("colonyboard.toast.debt", pay, Math.max(0, debt - pay)), Component.translatable("colonyboard.fin.debtpaid", pay, Math.max(0, debt - pay)).withStyle(ChatFormatting.GREEN));
                    log(c, p, "colonyboard.fin.log.debt", pay);
                }
                case TRANSFER -> transfer(p, c, amount, target);
            }
        } catch (Throwable t) {
            LOG.error("[colonyboard/finance] op {} failed", op, t);
            fail(p, "colonyboard.reason.error");
        }
    }

    private static void transfer(ServerPlayer p, IColony c, int amount, String target) {
        ServerPlayer to = target == null ? null : p.getServer().getPlayerList().getPlayerByName(target.trim());
        if (to == null) { fail(p, "colonyboard.reason.noplayer"); return; }
        if (to.getUUID().equals(p.getUUID())) { fail(p, "colonyboard.reason.self"); return; }
        if (Wallet.balance(p) < amount) { fail(p, "colonyboard.reason.funds"); return; }
        String cur = BoardConfig.CURRENCY.get();
        if (!Wallet.take(p, amount)) { fail(p, "colonyboard.reason.error"); return; }
        boolean ok;
        try { var r = CurrencyPlayerData.SERVER.addCurrencyValue(to.getUUID(), cur, amount); ok = r == null || r.isSuccess(); }
        catch (Throwable t) { ok = false; }
        if (!ok) {
            try { CurrencyPlayerData.SERVER.addCurrencyValue(p.getUUID(), cur, amount); } catch (Throwable ignored) {}
            try { EconomyAPI.syncPlayer(p); } catch (Throwable ignored) {}
            fail(p, "colonyboard.reason.error"); return;
        }
        try { EconomyAPI.syncPlayer(to); } catch (Throwable ignored) {}
        ok(p, Component.translatable("colonyboard.toast.sent", amount, to.getGameProfile().getName()), Component.translatable("colonyboard.fin.sent", amount, to.getGameProfile().getName()).withStyle(ChatFormatting.GREEN));
        ok(to, Component.translatable("colonyboard.toast.received", amount, p.getGameProfile().getName()), Component.translatable("colonyboard.fin.received", amount, p.getGameProfile().getName()).withStyle(ChatFormatting.GREEN));
        log(c, p, "colonyboard.fin.log.transfer", amount, to.getGameProfile().getName());
    }

    /** Success: toast (if on), else chatFallback (may be null = nothing in chat, as in 1.2). */
    private static void ok(ServerPlayer p, Component toast, Component chatFallback) {
        if (BoardConfig.TOASTS_FINANCE.get()) ru.wik.colonyboard.Net.toast(p, ru.wik.colonyboard.Net.Toast.OK, Component.translatable("colonyboard.toast.t.finance"), toast, true, chatFallback);
        else if (chatFallback != null) p.sendSystemMessage(chatFallback);
    }

    private static void fail(ServerPlayer p, String key) {
        Component chat = Component.translatable("colonyboard.fin.fail", Component.translatable(key)).withStyle(ChatFormatting.RED);
        if (!BoardConfig.TOASTS_FINANCE.get()) { p.sendSystemMessage(chat); return; }
        Component title, t;
        if (key.equals("colonyboard.reason.rights")) { title = Component.translatable("colonyboard.toast.rights"); t = Component.translatable(key); }
        else if (key.equals("colonyboard.state.war")) { title = Component.translatable("colonyboard.toast.t.finfail"); t = Component.translatable("colonyboard.toast.warblock"); }
        else if (key.startsWith("colonyboard.state.")) { title = Component.translatable("colonyboard.toast.t.finfail"); t = Component.translatable("colonyboard.toast.dangerblock", Component.translatable(key)); }
        else { title = Component.translatable("colonyboard.toast.t.finfail"); t = Component.translatable(key); }
        ru.wik.colonyboard.Net.toast(p, ru.wik.colonyboard.Net.Toast.FAIL, title, t, false, chat);
    }
}
