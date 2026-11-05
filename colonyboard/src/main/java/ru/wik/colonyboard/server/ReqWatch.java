package ru.wik.colonyboard.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import ru.wik.colonyboard.BoardConfig;
import ru.wik.colonyboard.Net;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 1.6.1: request toasts. Every few seconds looks at the open requests of every colony and sends ONE batched glass toast
 * (Confirm / Open request) listing every request that appeared since the last scan - any hut, any request type
 * (Stack, StackList, Tool, Food, Burnable, Tag, Domum...), buyable or not. Replaces colonybuy's builder-only prompt
 * with its 5 minute cooldown. Text is resolved on the server so 1.6.0 clients need no update.
 */
public final class ReqWatch {
    private static final EnumSet<RequestState> OPEN = EnumSet.of(RequestState.CREATED, RequestState.REPORTED,
        RequestState.ASSIGNING, RequestState.ASSIGNED, RequestState.IN_PROGRESS);
    private static final long FORGET_MS = 10 * 60_000L;   // a request that vanished and came back within 10 min is not "new"
    private static final int MAX_LIST = 3;

    private static final class St {
        final Map<UUID, Long> first = new HashMap<>();     // open since
        final Map<UUID, Long> told = new HashMap<>();      // already toasted -> last time seen open
        long lastRemind = System.currentTimeMillis();
    }
    private static final Map<String, St> STATE = new HashMap<>();
    private static final Map<UUID, Integer> LOGIN = new HashMap<>();   // player -> ticks left
    private static int ticks;

    private ReqWatch() {}

    public static boolean active() {
        try { return BoardConfig.TOASTS_ENABLED.get() && BoardConfig.REQ_TOASTS.get() && BoardConfig.REQ_REPLACE_CB.get(); } catch (Throwable t) { return false; }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) LOGIN.put(p.getUUID(), 200);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) { LOGIN.remove(e.getEntity().getUUID()); }

    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        boolean on;
        try { on = BoardConfig.TOASTS_ENABLED.get() && BoardConfig.REQ_TOASTS.get(); } catch (Throwable t) { return; }
        if (!on) return;
        if (!LOGIN.isEmpty()) loginTick(srv);
        if (++ticks < BoardConfig.REQ_SCAN_SEC.get() * 20) return;
        ticks = 0;
        List<IColony> colonies;
        try { colonies = new ArrayList<>(IColonyManager.getInstance().getAllColonies()); } catch (Throwable t) { return; }
        for (IColony c : colonies) {
            try { scan(srv, c); } catch (Throwable t) { Fulfil.LOG.debug("[colonyboard] request watch, colony {}: {}", c.getID(), t.toString()); }
        }
    }

    private static void loginTick(MinecraftServer srv) {
        Iterator<Map.Entry<UUID, Integer>> it = LOGIN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> en = it.next();
            if (en.getValue() > 1) { en.setValue(en.getValue() - 1); continue; }
            it.remove();
            if (!BoardConfig.REQ_ON_LOGIN.get()) continue;
            ServerPlayer p = srv.getPlayerList().getPlayer(en.getKey());
            if (p == null) continue;
            try {
                for (IColony c : IColonyManager.getInstance().getAllColonies()) {
                    if (!eligible(p, c)) continue;
                    St st = STATE.computeIfAbsent(ColonyKey.of(c), k -> new St());
                    Set<UUID> cur = openIds(c);
                    if (cur.isEmpty()) continue;
                    long now = System.currentTimeMillis();
                    for (UUID id : cur) { st.first.putIfAbsent(id, now); st.told.put(id, now); }
                    send(p, c, cur, true);
                }
            } catch (Throwable t) { Fulfil.LOG.debug("[colonyboard] login request toast: {}", t.toString()); }
        }
    }

    private static void scan(MinecraftServer srv, IColony c) {
        St st = STATE.computeIfAbsent(ColonyKey.of(c), k -> new St());
        long now = System.currentTimeMillis();
        Set<UUID> cur = openIds(c);
        st.first.keySet().retainAll(cur);
        for (UUID id : cur) st.first.putIfAbsent(id, now);
        st.told.entrySet().removeIf(x -> !cur.contains(x.getKey()) && now - x.getValue() > FORGET_MS);
        for (UUID id : cur) st.told.computeIfPresent(id, (k, v) -> now);

        long grace = BoardConfig.REQ_GRACE_SEC.get() * 1000L;
        Set<UUID> fresh = new LinkedHashSet<>();
        for (UUID id : cur) if (!st.told.containsKey(id) && now - st.first.get(id) >= grace) fresh.add(id);
        int remind = BoardConfig.REQ_REMIND_MIN.get();
        boolean doRemind = remind > 0 && !cur.isEmpty() && now - st.lastRemind >= remind * 60_000L;
        if (fresh.isEmpty() && !doRemind) return;

        List<ServerPlayer> to = new ArrayList<>();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) if (eligible(p, c) && Net.hasGlass(p)) to.add(p);
        if (to.isEmpty()) return;                          // nobody to tell: keep them "fresh" until someone is online
        if (doRemind) {
            st.lastRemind = now;
            for (UUID id : cur) st.told.put(id, now);
            for (ServerPlayer p : to) send(p, c, cur, true);
            return;
        }
        for (UUID id : fresh) st.told.put(id, now);
        for (ServerPlayer p : to) send(p, c, fresh, false);
    }

    /** Everyone in the colony who can spend money on requests: owner/officers (treasury + wallet) and, with walletByMembers=true, every non-hostile member (own wallet). */
    private static boolean eligible(ServerPlayer p, IColony c) {
        try {
            if (!c.getPermissions().isColonyMember(p)) return false;
            com.minecolonies.api.colony.permissions.Rank r = c.getPermissions().getRank(p);
            if (r == null || r.isHostile()) return false;   // isColonyMember is true for anyone on the list, even hostile
            return Fulfil.isManager(p, c) || Fulfil.denyReason(p, c) == null;
        } catch (Throwable t) { return false; }
    }

    /** Root ids of open requests: stuck only (player / retrying resolver) or every open request of every hut. */
    static Set<UUID> openIds(IColony c) {
        Set<UUID> out = new LinkedHashSet<>();
        IRequestManager rm = c.getRequestManager();
        if (rm == null) return out;
        if (BoardConfig.REQ_ONLY_STUCK.get()) return stuckIds(c);
        List<Integer> cids = new ArrayList<>();
        cids.add(-1);
        try { c.getCitizenManager().getCitizens().forEach(cd -> cids.add(cd.getId())); } catch (Throwable ignored) {}
        for (IBuilding b : c.getServerBuildingManager().getBuildings().values()) {
            for (int cid : cids) {
                try { for (IRequest<?> r : b.getOpenRequests(cid)) if (OPEN.contains(r.getState())) out.add(Fulfil.uuid(r)); }
                catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /** 1.7: root ids of requests nobody in the colony can deliver (player resolver / retrying resolver). */
    public static Set<UUID> stuckIds(IColony c) {
        Set<UUID> out = new LinkedHashSet<>();
        IRequestManager rm = c.getRequestManager();
        if (rm == null) return out;
        {
            Set<IToken<?>> stuck = new LinkedHashSet<>();
            try { stuck.addAll(rm.getPlayerResolver().getAllAssignedRequests()); } catch (Throwable ignored) {}
            try { stuck.addAll(rm.getRetryingRequestResolver().getAllAssignedRequests()); } catch (Throwable ignored) {}
            for (IToken<?> t : stuck) {
                try {
                    IRequest<?> r = rm.getRequestForToken(t);
                    int guard = 0;
                    while (r != null && r.hasParent() && guard++ < 32) {
                        IRequest<?> par = rm.getRequestForToken(r.getParent());
                        if (par == null) break;
                        r = par;
                    }
                    if (r != null && OPEN.contains(r.getState())) out.add(Fulfil.uuid(r));
                } catch (Throwable ignored) {}
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ toast
    private static void send(ServerPlayer p, IColony c, Set<UUID> ids, boolean summary) {
        boolean creative = p.isCreative() && Fulfil.isManager(p, c) && BoardConfig.CREATIVE_FREE.get();
        List<Fulfil.Line> lines = new ArrayList<>(Fulfil.scan(c, ids, creative));
        if (lines.isEmpty()) return;                       // closed in the meantime or not a hut request
        lines.sort(Comparator.comparing(l -> !l.ok()));    // buyable first: Confirm quotes them, Open jumps to the first
        String code = code(p);
        int n = lines.size();
        List<Fulfil.Line> ok = lines.stream().filter(Fulfil.Line::ok).toList();
        long total = 0; for (Fulfil.Line l : ok) total += creative ? 0 : l.cost();
        boolean canPay = !ok.isEmpty() && Fulfil.denyReason(p, c) == null;

        Component title = summary ? tr(code, "colonyboard.req.t.open", n)
            : n == 1 ? tr(code, lines.get(0).req.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.Tool
                ? "colonyboard.req.t.tool" : "colonyboard.req.t.one")
            : tr(code, "colonyboard.req.t.many", n);

        MutableComponent body = Component.empty();
        Set<IBuilding> huts = new LinkedHashSet<>();
        for (Fulfil.Line l : lines) huts.add(l.building);
        if (huts.size() == 1) body.append(building(huts.iterator().next())).append(": ");
        for (int i = 0; i < Math.min(MAX_LIST, n); i++) {
            Fulfil.Line l = lines.get(i);
            if (i > 0) body.append(", ");
            body.append(Fulfil.name(l));
            if (l.qty > 1) body.append(" ×" + l.qty);
        }
        if (n > MAX_LIST) body.append(tr(code, "colonyboard.req.more", n - MAX_LIST));
        if (ok.isEmpty()) body.append(tr(code, "colonyboard.req.nobuy", tr(code, lines.get(0).reason == null ? "colonyboard.reason.type" : lines.get(0).reason)));
        else if (creative) body.append(tr(code, "colonyboard.req.free"));
        else if (ok.size() == n) body.append(tr(code, "colonyboard.req.price", total));
        else body.append(tr(code, "colonyboard.req.pricepart", total, ok.size(), n));

        List<UUID> rid = new ArrayList<>();
        for (Fulfil.Line l : lines) rid.add(l.id);
        int act = Net.Glass.ACT_OPEN | (canPay ? Net.Glass.ACT_CONFIRM : 0);
        Net.glass(p, new Net.Glass(ok.isEmpty() ? Net.Toast.FAIL : Net.Toast.INFO, title, body, Component.empty(), c.getID(), rid, act), null);
    }

    private static Component building(IBuilding b) {
        try {
            String custom = b.getCustomName();
            if (custom != null && !custom.isEmpty()) return Component.literal(custom);
            return Component.translatable(b.getBuildingDisplayName());
        } catch (Throwable t) { return Component.literal("?"); }
    }

    // ------------------------------------------------------------------ server-side lang (clients 1.6.0 don't have the new keys)
    private static final Map<String, Map<String, String>> LANGS = new HashMap<>();

    private static String code(ServerPlayer p) {
        String l = null;
        try { l = p.getLanguage(); } catch (Throwable ignored) {}
        return l != null && l.toLowerCase(Locale.ROOT).startsWith("en") ? "en_us" : "ru_ru";
    }

    private static Map<String, String> load(String code) {
        return LANGS.computeIfAbsent(code, k -> {
            Map<String, String> m = new HashMap<>();
            try (InputStream in = ReqWatch.class.getResourceAsStream("/assets/colonyboard/lang/" + k + ".json")) {
                if (in != null) {
                    JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
                }
            } catch (Exception ignored) {}
            return m;
        });
    }

    static MutableComponent tr(String code, String key, Object... args) {
        String pat = load(code).get(key);
        if (pat == null) pat = load("ru_ru").getOrDefault(key, key);
        MutableComponent out = Component.empty();
        int ai = 0, i = 0;
        while (i < pat.length()) {
            int j = pat.indexOf("%s", i);
            if (j < 0) { out.append(Component.literal(pat.substring(i))); break; }
            if (j > i) out.append(Component.literal(pat.substring(i, j)));
            Object a = ai < args.length ? args[ai++] : "";
            out.append(a instanceof Component cc ? cc.copy() : Component.literal(String.valueOf(a)));
            i = j + 2;
        }
        return out;
    }
}
