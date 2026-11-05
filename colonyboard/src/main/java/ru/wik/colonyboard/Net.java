package ru.wik.colonyboard;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import ru.wik.colonyboard.logic.ColonyTarget;
import ru.wik.colonyboard.logic.Protocol;
import ru.wik.colonyboard.server.ColonyState;
import ru.wik.colonyboard.server.Finance;
import ru.wik.colonyboard.server.Fulfil;
import ru.wik.colonyboard.server.Market;
import ru.wik.colonyboard.server.Wallet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class Net {
    public static final String VERSION = Protocol.MAIN;
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ColonyBoard.MODID, "main"), () -> VERSION,
        NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));
    /** 1.6: separate optional channel for glass toasts, so 1.5 clients (main protocol 6) still join and get the old toasts. */
    public static final String GLASS_VERSION = Protocol.GLASS;
    public static final SimpleChannel GLASS = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ColonyBoard.MODID, "glass"), () -> GLASS_VERSION,
        NetworkRegistry.acceptMissingOr(GLASS_VERSION), NetworkRegistry.acceptMissingOr(GLASS_VERSION));
    /** 1.6.2: optional channel for the colonybuy auto-buy toggle in J (older clients/servers simply lack it). */
    public static final String AUTO_VERSION = Protocol.AUTO;
    public static final SimpleChannel AUTO = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ColonyBoard.MODID, "auto"), () -> AUTO_VERSION,
        NetworkRegistry.acceptMissingOr(AUTO_VERSION), NetworkRegistry.acceptMissingOr(AUTO_VERSION));
    /** 1.7: optional channel for the J dashboard (taxes / war readiness / warehouse / build queue actions). */
    public static final String DASH_VERSION = Protocol.DASH;
    public static final SimpleChannel DASH = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ColonyBoard.MODID, "dash"), () -> DASH_VERSION,
        NetworkRegistry.acceptMissingOr(DASH_VERSION), NetworkRegistry.acceptMissingOr(DASH_VERSION));
    /** 1.7.1: optional channel for min dig-tool tier floor (colony-wide or per hut). */
    public static final String TIER_VERSION = Protocol.TIER;
    public static final SimpleChannel TIER = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ColonyBoard.MODID, "tier"), () -> TIER_VERSION,
        NetworkRegistry.acceptMissingOr(TIER_VERSION), NetworkRegistry.acceptMissingOr(TIER_VERSION));
    /** 1.7.3: optional channel for starting or upgrading a placed hut from J. Older clients simply lack it. */
    public static final String BUILD_VERSION = Protocol.BUILD;
    public static final SimpleChannel BUILD = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ColonyBoard.MODID, "build"), () -> BUILD_VERSION,
        NetworkRegistry.acceptMissingOr(BUILD_VERSION), NetworkRegistry.acceptMissingOr(BUILD_VERSION));
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_DASH = new ConcurrentHashMap<>(), LAST_DASH_ACT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_TIER = new ConcurrentHashMap<>(), LAST_TIER_ACT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_BUILD = new ConcurrentHashMap<>(), LAST_BUILD_ACT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_FULFIL = new ConcurrentHashMap<>();

    private Net() {}

    public static void init() {
        CHANNEL.messageBuilder(SyncRequest.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder(SyncRequest::encode).decoder(SyncRequest::decode).consumerMainThread(Net::onSync).add();
        CHANNEL.messageBuilder(Stats.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(Stats::encode).decoder(Stats::decode).consumerMainThread(Net::onStats).add();
        CHANNEL.messageBuilder(QuoteRequest.class, 2, NetworkDirection.PLAY_TO_SERVER).encoder(QuoteRequest::encode).decoder(QuoteRequest::decode).consumerMainThread(Net::onQuote).add();
        CHANNEL.messageBuilder(Quote.class, 3, NetworkDirection.PLAY_TO_CLIENT).encoder(Quote::encode).decoder(Quote::decode).consumerMainThread(Net::onQuoteReply).add();
        CHANNEL.messageBuilder(FinanceAction.class, 5, NetworkDirection.PLAY_TO_SERVER).encoder(FinanceAction::encode).decoder(FinanceAction::decode).consumerMainThread(Net::onFinance).add();
        CHANNEL.messageBuilder(FulfilRequest.class, 4, NetworkDirection.PLAY_TO_SERVER).encoder(FulfilRequest::encode).decoder(FulfilRequest::decode).consumerMainThread(Net::onFulfil).add();
        CHANNEL.messageBuilder(Toast.class, 6, NetworkDirection.PLAY_TO_CLIENT).encoder(Toast::encode).decoder(Toast::decode).consumerMainThread(Net::onToast).add();
        CHANNEL.messageBuilder(MarketRequest.class, 7, NetworkDirection.PLAY_TO_SERVER).encoder(MarketRequest::encode).decoder(MarketRequest::decode).consumerMainThread(Net::onMarketRequest).add();
        CHANNEL.messageBuilder(MarketData.class, 8, NetworkDirection.PLAY_TO_CLIENT).encoder(MarketData::encode).decoder(MarketData::decode).consumerMainThread(Net::onMarketData).add();
        GLASS.messageBuilder(Glass.class, 0, NetworkDirection.PLAY_TO_CLIENT).encoder(Glass::encode).decoder(Glass::decode).consumerMainThread(Net::onGlass).add();
        AUTO.messageBuilder(AutoState.class, 0, NetworkDirection.PLAY_TO_CLIENT).encoder(AutoState::encode).decoder(AutoState::decode).consumerMainThread(Net::onAutoState).add();
        AUTO.messageBuilder(AutoToggle.class, 1, NetworkDirection.PLAY_TO_SERVER).encoder(AutoToggle::encode).decoder(AutoToggle::decode).consumerMainThread(Net::onAutoToggle).add();
        DASH.messageBuilder(DashRequest.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder(DashRequest::encode).decoder(DashRequest::decode).consumerMainThread(Net::onDashRequest).add();
        DASH.messageBuilder(DashData.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(DashData::encode).decoder(DashData::decode).consumerMainThread(Net::onDashData).add();
        DASH.messageBuilder(DashAction.class, 2, NetworkDirection.PLAY_TO_SERVER).encoder(DashAction::encode).decoder(DashAction::decode).consumerMainThread(Net::onDashAction).add();
        TIER.messageBuilder(TierRequest.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder(TierRequest::encode).decoder(TierRequest::decode).consumerMainThread(Net::onTierRequest).add();
        TIER.messageBuilder(TierState.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(TierState::encode).decoder(TierState::decode).consumerMainThread(Net::onTierState).add();
        TIER.messageBuilder(TierSet.class, 2, NetworkDirection.PLAY_TO_SERVER).encoder(TierSet::encode).decoder(TierSet::decode).consumerMainThread(Net::onTierSet).add();
        BUILD.messageBuilder(BuildRequest.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder(BuildRequest::encode).decoder(BuildRequest::decode).consumerMainThread(Net::onBuildRequest).add();
        BUILD.messageBuilder(BuildState.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(BuildState::encode).decoder(BuildState::decode).consumerMainThread(Net::onBuildState).add();
        BUILD.messageBuilder(BuildAct.class, 2, NetworkDirection.PLAY_TO_SERVER).encoder(BuildAct::encode).decoder(BuildAct::decode).consumerMainThread(Net::onBuildAct).add();
        BUILD.messageBuilder(BuildResult.class, 3, NetworkDirection.PLAY_TO_CLIENT).encoder(BuildResult::encode).decoder(BuildResult::decode).consumerMainThread(Net::onBuildResult).add();
        CHANNEL.messageBuilder(MarketAction.class, 9, NetworkDirection.PLAY_TO_SERVER).encoder(MarketAction::encode).decoder(MarketAction::decode).consumerMainThread(Net::onMarketAction).add();
    }

    /** kind: 0 ok, 1 fail, 2 war/danger, 3 info. hint = show "J -> Финансы" history hint. */
    public record Toast(int kind, Component text, boolean hint) {
        public static final int OK = 0, FAIL = 1, WAR = 2, INFO = 3;
        void encode(FriendlyByteBuf b) { b.writeVarInt(kind); b.writeComponent(text); b.writeBoolean(hint); }
        static Toast decode(FriendlyByteBuf b) { return new Toast(b.readVarInt(), b.readComponent(), b.readBoolean()); }
    }

    /**
     * 1.6 glass toast. title/body: shown as is; hint: small grey line ("J -> Финансы"), empty = none.
     * actions: ACT_CONFIRM (re-quote these request ids -> pay window), ACT_OPEN (J -> Запросы, scroll to ids[0]).
     */
    public record Glass(int kind, Component title, Component body, Component hint, int colonyId, List<UUID> ids, int actions) {
        public static final int ACT_CONFIRM = 1, ACT_OPEN = 2;
        void encode(FriendlyByteBuf b) {
            b.writeVarInt(kind); b.writeComponent(title); b.writeComponent(body); b.writeComponent(hint);
            b.writeInt(colonyId); b.writeCollection(ids.size() > 256 ? ids.subList(0, 256) : ids, FriendlyByteBuf::writeUUID); b.writeVarInt(actions);
        }
        static Glass decode(FriendlyByteBuf b) {
            return new Glass(b.readVarInt(), b.readComponent(), b.readComponent(), b.readComponent(), b.readInt(), b.readList(FriendlyByteBuf::readUUID), b.readVarInt());
        }
    }

    public static boolean hasGlass(ServerPlayer p) {
        try { return BoardConfig.TOASTS_ENABLED.get() && GLASS.isRemotePresent(p.connection.connection); } catch (Throwable t) { return false; }
    }

    private static boolean hasOld(ServerPlayer p) {
        try { return BoardConfig.TOASTS_ENABLED.get() && CHANNEL.isRemotePresent(p.connection.connection); } catch (Throwable t) { return false; }
    }

    public static Component defaultTitle(int kind) {
        return Component.translatable(switch (kind) { case Toast.OK -> "colonyboard.toast.t.ok"; case Toast.FAIL -> "colonyboard.toast.t.fail";
            case Toast.WAR -> "colonyboard.toast.t.war"; default -> "colonyboard.toast.t.info"; });
    }

    /** Glass toast (1.6 client) > old toast (1.3-1.5 client) > chatFallback (may be null). true = some toast was shown. */
    public static boolean glass(ServerPlayer p, Glass g, Component chatFallback) {
        if (hasGlass(p)) { GLASS.send(PacketDistributor.PLAYER.with(() -> p), g); return true; }
        if (hasOld(p)) {
            // 1.5 clients don't know the 1.6 lang keys: give them the old (chat) wording
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Toast(g.kind(), chatFallback != null ? chatFallback : g.body(), !g.hint().getString().isEmpty()));
            return true;
        }
        if (chatFallback != null) p.sendSystemMessage(chatFallback);
        return false;
    }

    public static void toast(ServerPlayer p, int kind, Component title, Component text, boolean hint, Component chatFallback) {
        glass(p, new Glass(kind, title == null ? defaultTitle(kind) : title, text,
            hint ? Component.translatable("colonyboard.toast.hint") : Component.empty(), -1, List.of(), 0), chatFallback);
    }

    /** Toast to the player if toasts are on and the client has colonyboard 1.3+, else chat line. */
    public static void toast(ServerPlayer p, int kind, Component text, boolean hint, Component chatFallback) {
        toast(p, kind, null, text, hint, chatFallback);
    }

    // ------------------------------------------------------------------ 1.7 dashboard
    public record DashRequest(int colonyId) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); }
        static DashRequest decode(FriendlyByteBuf b) { return new DashRequest(b.readInt()); }
    }

    /** One warehouse line: item, how many open stuck requests need, how many the warehouse has. */
    public record DItem(ItemStack item, int need, int have) {}

    /**
     * Server-only facts for the J dashboard. num/str are open key maps (missing key = unknown), so new facts never
     * need a protocol bump. Keys: see server.Dash.
     */
    public record DashData(int colonyId, boolean manager, Map<String, Long> num, Map<String, String> str,
                           List<DItem> shortages, List<DItem> surplus, List<UUID> stuck) {
        void encode(FriendlyByteBuf b) {
            b.writeInt(colonyId); b.writeBoolean(manager);
            b.writeMap(num, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeLong);
            b.writeMap(str, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeUtf);
            for (List<DItem> l : List.of(shortages, surplus))
                b.writeCollection(l.size() > 64 ? l.subList(0, 64) : l, (x, d) -> { x.writeItem(d.item()); x.writeVarInt(d.need()); x.writeVarInt(d.have()); });
            b.writeCollection(stuck.size() > 512 ? stuck.subList(0, 512) : stuck, FriendlyByteBuf::writeUUID);
        }
        static DashData decode(FriendlyByteBuf b) {
            int id = b.readInt(); boolean m = b.readBoolean();
            Map<String, Long> n = b.readMap(FriendlyByteBuf::readUtf, FriendlyByteBuf::readLong);
            Map<String, String> s = b.readMap(FriendlyByteBuf::readUtf, FriendlyByteBuf::readUtf);
            List<DItem> sh = b.readList(x -> new DItem(x.readItem(), x.readVarInt(), x.readVarInt()));
            List<DItem> su = b.readList(x -> new DItem(x.readItem(), x.readVarInt(), x.readVarInt()));
            return new DashData(id, m, n, s, sh, su, b.readList(FriendlyByteBuf::readUUID));
        }
        public long n(String k, long def) { Long v = num.get(k); return v == null ? def : v; }
        public String s(String k) { String v = str.get(k); return v == null ? "" : v; }
        public boolean has(String k) { return num.containsKey(k) || str.containsKey(k); }
    }

    /** op: BUY_STUCK (quote all stuck requests), WO_PRIO (a = work order id, b = delta), WO_BUY (a = work order id), BUDGET (a = daily auto-buy budget). */
    public record DashAction(int colonyId, int op, int a, int b) {
        public static final int BUY_STUCK = 0, WO_PRIO = 1, WO_BUY = 2, BUDGET = 3;
        void encode(FriendlyByteBuf x) { x.writeInt(colonyId); x.writeVarInt(op); x.writeInt(a); x.writeInt(b); }
        static DashAction decode(FriendlyByteBuf x) { return new DashAction(x.readInt(), x.readVarInt(), x.readInt(), x.readInt()); }
    }

    public static boolean hasDash(ServerPlayer p) {
        try { return DASH.isRemotePresent(p.connection.connection); } catch (Throwable t) { return false; }
    }

    private static void sendDash(ServerPlayer p, IColony c) {
        if (!hasDash(p)) return;
        DASH.send(PacketDistributor.PLAYER.with(() -> p), ru.wik.colonyboard.server.Dash.build(p, c));
    }

    private static void onDashRequest(DashRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_DASH.get(p.getUUID());
        if (last != null && now - last < 1000) return;
        LAST_DASH.put(p.getUUID(), now);
        IColony c = colony(p, msg.colonyId());
        if (c != null) sendDash(p, c);
    }

    private static void onDashAction(DashAction msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_DASH_ACT.get(p.getUUID());
        if (last != null && now - last < 300) return;
        LAST_DASH_ACT.put(p.getUUID(), now);
        IColony c = colony(p, msg.colonyId());
        if (c == null) return;
        List<UUID> quoteIds = ru.wik.colonyboard.server.Dash.handle(p, c, msg);
        if (quoteIds != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), quote(p, c, quoteIds));
        sendStats(p, c);
        sendDash(p, c);
    }

    private static void onDashData(DashData msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ClientState.onDash(msg));
    }

    // ------------------------------------------------------------------ 1.7.1 tool tier
    public record TierHut(long pos, String name, int level, int maxEq, int floor) {}

    public record TierRequest(int colonyId) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); }
        static TierRequest decode(FriendlyByteBuf b) { return new TierRequest(b.readInt()); }
    }

    public record TierState(int colonyId, boolean manager, int mode, int colonyFloor, int thLevel, List<TierHut> huts) {
        void encode(FriendlyByteBuf b) {
            b.writeInt(colonyId); b.writeBoolean(manager); b.writeVarInt(mode); b.writeVarInt(colonyFloor); b.writeVarInt(thLevel);
            List<TierHut> list = huts.size() > 256 ? huts.subList(0, 256) : huts;
            b.writeCollection(list, (x, h) -> { x.writeLong(h.pos()); x.writeUtf(h.name(), 128); x.writeVarInt(h.level()); x.writeVarInt(h.maxEq()); x.writeVarInt(h.floor()); });
        }
        static TierState decode(FriendlyByteBuf b) {
            return new TierState(b.readInt(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                b.readList(x -> new TierHut(x.readLong(), x.readUtf(128), x.readVarInt(), x.readVarInt(), x.readVarInt())));
        }
    }

    /** op: MODE (a=0 colony / 1 per-hut), COLONY_FLOOR (a=0..3), HUT_FLOOR (a=0..3, hutPos). */
    public record TierSet(int colonyId, int op, int a, long hutPos) {
        public static final int MODE = 0, COLONY_FLOOR = 1, HUT_FLOOR = 2;
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); b.writeVarInt(op); b.writeVarInt(a); b.writeLong(hutPos); }
        static TierSet decode(FriendlyByteBuf b) { return new TierSet(b.readInt(), b.readVarInt(), b.readVarInt(), b.readLong()); }
    }

    public static boolean hasTier(ServerPlayer p) {
        try { return TIER.isRemotePresent(p.connection.connection); } catch (Throwable t) { return false; }
    }

    private static void sendTier(ServerPlayer p, IColony c) {
        if (!hasTier(p)) return;
        TIER.send(PacketDistributor.PLAYER.with(() -> p), ru.wik.colonyboard.server.ToolTier.state(p, c));
    }

    private static void onTierRequest(TierRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_TIER.get(p.getUUID());
        if (last != null && now - last < 1000) return;
        LAST_TIER.put(p.getUUID(), now);
        IColony c = colony(p, msg.colonyId());
        if (c != null) sendTier(p, c);
    }

    private static void onTierSet(TierSet msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_TIER_ACT.get(p.getUUID());
        if (last != null && now - last < 300) return;
        LAST_TIER_ACT.put(p.getUUID(), now);
        IColony c = colony(p, msg.colonyId());
        if (c == null) return;
        ru.wik.colonyboard.server.ToolTier.apply(p, c, msg);
        sendTier(p, c);
    }

    private static void onTierState(TierState msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ClientState.onTier(msg));
    }

    // ------------------------------------------------------------------ 1.7.3 placed hut build / upgrade
    /** kind: 0 start, 1 upgrade. nativeType: 0 BUILD, 1 UPGRADE, -1 unsupported. deny: lang key or "". */
    public record BuildHutRow(long pos, String literal, String key, int level, int max, int target, int kind, int nativeType, String deny) {}

    public record BuildRequest(int colonyId) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); }
        static BuildRequest decode(FriendlyByteBuf b) { return new BuildRequest(b.readInt()); }
    }

    public record BuildState(int colonyId, boolean canManage, int unreadable, boolean truncated, List<BuildHutRow> huts) {
        void encode(FriendlyByteBuf b) {
            b.writeInt(colonyId); b.writeBoolean(canManage); b.writeVarInt(unreadable); b.writeBoolean(truncated);
            List<BuildHutRow> list = huts.size() > ru.wik.colonyboard.server.BuildHut.MAX_ROWS ? huts.subList(0, ru.wik.colonyboard.server.BuildHut.MAX_ROWS) : huts;
            b.writeCollection(list, (x, h) -> {
                x.writeLong(h.pos()); x.writeUtf(h.literal(), 128); x.writeUtf(h.key(), 128);
                x.writeVarInt(h.level()); x.writeVarInt(h.max()); x.writeVarInt(h.target());
                x.writeVarInt(h.kind()); x.writeVarInt(h.nativeType()); x.writeUtf(h.deny(), 80);
            });
        }
        static BuildState decode(FriendlyByteBuf b) {
            return new BuildState(b.readInt(), b.readBoolean(), b.readVarInt(), b.readBoolean(),
                b.readList(x -> new BuildHutRow(x.readLong(), x.readUtf(128), x.readUtf(128), x.readVarInt(), x.readVarInt(),
                    x.readVarInt(), x.readVarInt(), x.readVarInt(), x.readUtf(80))));
        }
    }

    /** expectedLevel + kind are the snapshot the player confirmed. The server recomputes both. */
    public record BuildAct(int colonyId, long pos, int expectedLevel, int kind) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); b.writeLong(pos); b.writeVarInt(expectedLevel); b.writeVarInt(kind); }
        static BuildAct decode(FriendlyByteBuf b) { return new BuildAct(b.readInt(), b.readLong(), b.readVarInt(), b.readVarInt()); }
    }

    public record BuildResult(int colonyId, long pos, String deny, int level, int target, Component name) {
        void encode(FriendlyByteBuf b) {
            b.writeInt(colonyId); b.writeLong(pos); b.writeUtf(deny, 80); b.writeVarInt(level); b.writeVarInt(target); b.writeComponent(name);
        }
        static BuildResult decode(FriendlyByteBuf b) {
            return new BuildResult(b.readInt(), b.readLong(), b.readUtf(80), b.readVarInt(), b.readVarInt(), b.readComponent());
        }
    }

    public static boolean hasBuild(ServerPlayer p) {
        try { return BUILD.isRemotePresent(p.connection.connection); } catch (Throwable t) { return false; }
    }

    private static void sendBuild(ServerPlayer p, IColony c) {
        if (!hasBuild(p) || c == null) return;
        ru.wik.colonyboard.server.BuildHut.Listing list = ru.wik.colonyboard.server.BuildHut.list(p, c);
        List<BuildHutRow> rows = new ArrayList<>();
        for (ru.wik.colonyboard.server.BuildHut.Hut h : list.huts) {
            rows.add(new BuildHutRow(h.pos, h.literal, h.key, h.level, h.max, h.target, h.kind, h.nativeType, h.deny));
        }
        BUILD.send(PacketDistributor.PLAYER.with(() -> p), new BuildState(c.getID(), list.canManage, list.unreadable, list.truncated, rows));
    }

    private static void onBuildRequest(BuildRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_BUILD.get(p.getUUID());
        if (last != null && now - last < 700) return;
        LAST_BUILD.put(p.getUUID(), now);
        IColony c = colony(p, msg.colonyId());
        if (c == null) {
            if (hasBuild(p)) BUILD.send(PacketDistributor.PLAYER.with(() -> p), new BuildState(msg.colonyId(), false, -2, false, List.of()));
            return;
        }
        sendBuild(p, c);
    }

    private static void onBuildAct(BuildAct msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST_BUILD_ACT.get(p.getUUID());
        if (last != null && now - last < 400) {
            replyBuild(p, msg, ru.wik.colonyboard.logic.BuildActions.Deny.BUSY, msg.expectedLevel(), msg.expectedLevel(), Component.empty());
            return;
        }
        LAST_BUILD_ACT.put(p.getUUID(), now);
        ru.wik.colonyboard.logic.BuildActions.Gate gate = ru.wik.colonyboard.server.BuildHut.access(p, msg.colonyId());
        IColony c = gate == ru.wik.colonyboard.logic.BuildActions.Gate.OK ? ru.wik.colonyboard.server.BuildHut.colonyIfAllowed(p, msg.colonyId()) : null;
        if (c == null) {
            ru.wik.colonyboard.logic.BuildActions.Deny deny = switch (gate) {
                case WRONG_DIMENSION -> ru.wik.colonyboard.logic.BuildActions.Deny.WRONG_DIMENSION;
                case FOREIGN -> ru.wik.colonyboard.logic.BuildActions.Deny.FOREIGN;
                default -> ru.wik.colonyboard.logic.BuildActions.Deny.NO_COLONY;
            };
            replyBuild(p, msg, deny, msg.expectedLevel(), msg.expectedLevel(), Component.empty());
            return;
        }
        ru.wik.colonyboard.server.BuildHut.Outcome out = ru.wik.colonyboard.server.BuildHut.act(p, c, msg.pos(), msg.expectedLevel(), msg.kind());
        Component name = ru.wik.colonyboard.server.BuildHut.label(out.literal, out.key);
        replyBuild(p, msg, out.deny, out.level, out.target, name);
        if (out.deny == ru.wik.colonyboard.logic.BuildActions.Deny.NONE) {
            c.getPackageManager().removeCloseSubscriber(p);
            c.getPackageManager().addCloseSubscriber(p);
        }
        sendBuild(p, c);
    }

    private static void replyBuild(ServerPlayer p, BuildAct msg, ru.wik.colonyboard.logic.BuildActions.Deny deny, int level, int target, Component name) {
        String key = ru.wik.colonyboard.logic.BuildActions.lang(deny);
        Component shown = name == null ? Component.empty() : name;
        BUILD.send(PacketDistributor.PLAYER.with(() -> p), new BuildResult(msg.colonyId(), msg.pos(), key, level, target, shown));
        Component body = deny == ru.wik.colonyboard.logic.BuildActions.Deny.NONE
            ? Component.translatable("colonyboard.build.ok", shown, level, target)
            : Component.translatable("colonyboard.build.fail", Component.translatable(key));
        int toast = deny == ru.wik.colonyboard.logic.BuildActions.Deny.NONE ? Toast.OK : Toast.FAIL;
        glass(p, new Glass(toast, Component.translatable(deny == ru.wik.colonyboard.logic.BuildActions.Deny.NONE
            ? "colonyboard.build.ok.title" : "colonyboard.toast.t.fail"), body, Component.empty(), msg.colonyId(), List.of(), 0), body);
    }

    private static void onBuildState(BuildState msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ClientState.onBuild(msg));
    }

    private static void onBuildResult(BuildResult msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ClientState.onBuildResult(msg));
    }

    // ------------------------------------------------------------------ messages
    public record SyncRequest(int colonyId) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); }
        static SyncRequest decode(FriendlyByteBuf b) { return new SyncRequest(b.readInt()); }
    }

    /** treasury/wallet: -1 = unknown. danger/deny: lang key or "". */
    /** tax: stored colony tax (negative = debt, Long.MIN_VALUE = unknown); capacity -1 = unknown; finDeny: per Finance op, "" = allowed. */
    public record Stats(int colonyId, long treasury, double wallet, String danger, String deny, boolean creative,
                        long tax, long capacity, boolean manager, List<String> finDeny, List<Component> journal) {
        void encode(FriendlyByteBuf b) {
            b.writeInt(colonyId); b.writeLong(treasury); b.writeDouble(wallet); b.writeUtf(danger); b.writeUtf(deny); b.writeBoolean(creative);
            b.writeLong(tax); b.writeLong(capacity); b.writeBoolean(manager);
            b.writeCollection(finDeny, FriendlyByteBuf::writeUtf); b.writeCollection(journal, FriendlyByteBuf::writeComponent);
        }
        static Stats decode(FriendlyByteBuf b) {
            return new Stats(b.readInt(), b.readLong(), b.readDouble(), b.readUtf(), b.readUtf(), b.readBoolean(),
                b.readLong(), b.readLong(), b.readBoolean(), b.readList(FriendlyByteBuf::readUtf), b.readList(FriendlyByteBuf::readComponent));
        }
    }

    /** available=false: colonybuy missing. deny: lang key or "" (may toggle). */
    public record AutoState(int colonyId, boolean available, boolean on, int budget, int spent, String deny) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); b.writeBoolean(available); b.writeBoolean(on); b.writeInt(budget); b.writeInt(spent); b.writeUtf(deny); }
        static AutoState decode(FriendlyByteBuf b) { return new AutoState(b.readInt(), b.readBoolean(), b.readBoolean(), b.readInt(), b.readInt(), b.readUtf()); }
    }
    public record AutoToggle(int colonyId, boolean on) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); b.writeBoolean(on); }
        static AutoToggle decode(FriendlyByteBuf b) { return new AutoToggle(b.readInt(), b.readBoolean()); }
    }

    public record FinanceAction(int colonyId, int op, int amount, int source, String target) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); b.writeVarInt(op); b.writeInt(amount); b.writeVarInt(source); b.writeUtf(target, 32); }
        static FinanceAction decode(FriendlyByteBuf b) { return new FinanceAction(b.readInt(), b.readVarInt(), b.readInt(), b.readVarInt(), b.readUtf(32)); }
    }

    public record QuoteRequest(int colonyId, List<UUID> ids) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); b.writeCollection(ids, FriendlyByteBuf::writeUUID); }
        static QuoteRequest decode(FriendlyByteBuf b) { return new QuoteRequest(b.readInt(), b.readList(FriendlyByteBuf::readUUID)); }
    }

    public record QuoteLine(Component name, int qty, long cost, String reason) {}

    /** 1.5 (protocol 6): both pay sources. treasury -1 = unknown; denyTreasury/denyWallet "" = that source may be used. */
    public record Quote(int colonyId, List<UUID> ids, List<QuoteLine> lines, long total, boolean free, double wallet, String deny,
                        long treasury, String denyTreasury, String denyWallet) {
        void encode(FriendlyByteBuf b) {
            b.writeInt(colonyId); b.writeCollection(ids, FriendlyByteBuf::writeUUID);
            b.writeCollection(lines, (bb, l) -> { bb.writeComponent(l.name()); bb.writeVarInt(l.qty()); bb.writeLong(l.cost()); bb.writeUtf(l.reason()); });
            b.writeLong(total); b.writeBoolean(free); b.writeDouble(wallet); b.writeUtf(deny);
            b.writeLong(treasury); b.writeUtf(denyTreasury); b.writeUtf(denyWallet);
        }
        static Quote decode(FriendlyByteBuf b) {
            return new Quote(b.readInt(), b.readList(FriendlyByteBuf::readUUID),
                b.readList(bb -> new QuoteLine(bb.readComponent(), bb.readVarInt(), bb.readLong(), bb.readUtf())),
                b.readLong(), b.readBoolean(), b.readDouble(), b.readUtf(), b.readLong(), b.readUtf(), b.readUtf());
        }
    }

    /** source: Fulfil.SRC_TREASURY (0) / SRC_WALLET (1) */
    public record FulfilRequest(int colonyId, List<UUID> ids, long confirmedTotal, int source) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); b.writeCollection(ids, FriendlyByteBuf::writeUUID); b.writeLong(confirmedTotal); b.writeVarInt(source); }
        static FulfilRequest decode(FriendlyByteBuf b) { return new FulfilRequest(b.readInt(), b.readList(FriendlyByteBuf::readUUID), b.readLong(), b.readVarInt()); }
    }

    // ------------------------------------------------------------------ market (1.4, protocol 5)
    public record MarketRequest(int colonyId) {
        void encode(FriendlyByteBuf b) { b.writeInt(colonyId); }
        static MarketRequest decode(FriendlyByteBuf b) { return new MarketRequest(b.readInt()); }
    }

    /** op: Market.LIST(a=lotSize,b=lots,c=lotPrice) UNLIST(id) BUY(id,a=lots,b=expected lotPrice) AUCTION(a=qty,b=start,c=step,d=minutes) BID(id,a=amount) CANCEL(id) CLAIM */
    public record MarketAction(int op, int colonyId, long id, ItemStack item, int a, int b, int c, int d) {
        void encode(FriendlyByteBuf x) { x.writeVarInt(op); x.writeInt(colonyId); x.writeLong(id); x.writeItem(item.copyWithCount(1)); x.writeInt(a); x.writeInt(b); x.writeInt(c); x.writeInt(d); }
        static MarketAction decode(FriendlyByteBuf x) { return new MarketAction(x.readVarInt(), x.readInt(), x.readLong(), x.readItem(), x.readInt(), x.readInt(), x.readInt(), x.readInt()); }
    }

    public record MListing(long id, int colony, String colonyName, ItemStack item, int units, int lotSize, int lotPrice, boolean mine) {}
    public record MLot(long id, int colony, String colonyName, ItemStack item, int units, int minNext, int bid, String bidder, long msLeft, boolean mine, boolean leading) {}
    public record MStock(ItemStack item, int count) {}

    public record MarketData(int colonyId, boolean manager, double wallet, long treasury, String deny, String aucDeny, String sellDeny,
                             int pending, int aucMin, int aucMax, List<MListing> listings, List<MLot> lots, List<MStock> stock) {
        void encode(FriendlyByteBuf b) {
            b.writeInt(colonyId); b.writeBoolean(manager); b.writeDouble(wallet); b.writeLong(treasury);
            b.writeUtf(deny); b.writeUtf(aucDeny); b.writeUtf(sellDeny); b.writeVarInt(pending); b.writeVarInt(aucMin); b.writeVarInt(aucMax);
            b.writeCollection(listings, (x, l) -> { x.writeLong(l.id()); x.writeInt(l.colony()); x.writeUtf(l.colonyName()); x.writeItem(l.item().copyWithCount(1));
                x.writeVarInt(l.units()); x.writeVarInt(l.lotSize()); x.writeVarInt(l.lotPrice()); x.writeBoolean(l.mine()); });
            b.writeCollection(lots, (x, l) -> { x.writeLong(l.id()); x.writeInt(l.colony()); x.writeUtf(l.colonyName()); x.writeItem(l.item().copyWithCount(1));
                x.writeVarInt(l.units()); x.writeVarInt(l.minNext()); x.writeVarInt(l.bid()); x.writeUtf(l.bidder()); x.writeLong(l.msLeft()); x.writeBoolean(l.mine()); x.writeBoolean(l.leading()); });
            b.writeCollection(stock, (x, s) -> { x.writeItem(s.item().copyWithCount(1)); x.writeVarInt(s.count()); });
        }
        static MarketData decode(FriendlyByteBuf b) {
            return new MarketData(b.readInt(), b.readBoolean(), b.readDouble(), b.readLong(), b.readUtf(), b.readUtf(), b.readUtf(),
                b.readVarInt(), b.readVarInt(), b.readVarInt(),
                b.readList(x -> new MListing(x.readLong(), x.readInt(), x.readUtf(), x.readItem(), x.readVarInt(), x.readVarInt(), x.readVarInt(), x.readBoolean())),
                b.readList(x -> new MLot(x.readLong(), x.readInt(), x.readUtf(), x.readItem(), x.readVarInt(), x.readVarInt(), x.readVarInt(), x.readUtf(), x.readLong(), x.readBoolean(), x.readBoolean())),
                b.readList(x -> new MStock(x.readItem(), x.readVarInt())));
        }
    }

    // ------------------------------------------------------------------ server
    /**
     * Explicit id: that colony in the player's current dimension, and only if they are a member.
     * A stale or cross-dimension id is rejected — it must not operate the colony they own instead.
     * Id &lt; 0 (market opened with no colony view): the colony they own in this dimension.
     */
    private static IColony colony(ServerPlayer p, int id) {
        IColonyManager mgr = IColonyManager.getInstance();
        if (id >= 0) {
            IColony found = mgr.getColonyByWorld(id, p.level());
            boolean member = found != null && found.getPermissions().isColonyMember(p);
            return ColonyTarget.resolve(true, found != null, member, false, false) == ColonyTarget.Kind.EXPLICIT ? found : null;
        }
        IColony owned = mgr.getIColonyByOwner(p.level(), p);
        boolean member = owned != null && owned.getPermissions().isColonyMember(p);
        return ColonyTarget.resolve(false, false, false, owned != null, member) == ColonyTarget.Kind.OWNED ? owned : null;
    }

    private static boolean rate(ServerPlayer p, long ms) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(p.getUUID());
        if (last != null && now - last < ms) return false;
        LAST.put(p.getUUID(), now);
        return true;
    }

    private static void onSync(SyncRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null || !rate(p, 2000)) return;
        IColony c = colony(p, msg.colonyId());
        if (c == null) return;
        c.getPackageManager().removeCloseSubscriber(p);
        c.getPackageManager().addCloseSubscriber(p);
        sendStats(p, c);
    }

    private static void sendStats(ServerPlayer p, IColony c) {
        String danger = ColonyState.danger(c);
        String deny = Fulfil.denyReason(p, c);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Stats(c.getID(), ColonyState.treasury(c),
            Wallet.balance(p), danger == null ? "" : danger, deny == null ? "" : deny, p.isCreative(),
            Finance.storedTax(c), Finance.capacity(c), Fulfil.isManager(p, c), finDeny(p, c), Finance.journal(c)));
        sendAuto(p, c);
    }

    private static void sendAuto(ServerPlayer p, IColony c) {
        try { if (!AUTO.isRemotePresent(p.connection.connection)) return; } catch (Throwable t) { return; }
        AUTO.send(PacketDistributor.PLAYER.with(() -> p), ru.wik.colonyboard.server.AutoBuy.state(p, c));
    }

    private static void onAutoToggle(AutoToggle msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null || !rate(p, 500)) return;
        IColony c = colony(p, msg.colonyId());
        if (c == null) return;
        ru.wik.colonyboard.server.AutoBuy.toggle(p, c, msg.on());
        sendAuto(p, c);
    }

    private static void onAutoState(AutoState msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ClientState.auto = msg);
    }

    private static List<String> finDeny(ServerPlayer p, IColony c) {
        List<String> l = new ArrayList<>();
        for (int op = 0; op <= Finance.TRANSFER; op++) { String d = Finance.deny(p, c, op); l.add(d == null ? "" : d); }
        return l;
    }

    private static void sendMarket(ServerPlayer p, IColony own) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), Market.get().view(p, own));
    }

    private static void onMarketRequest(MarketRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null || !rate(p, 500)) return;
        sendMarket(p, colony(p, msg.colonyId()));
    }

    private static void onMarketAction(MarketAction msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        IColony own = colony(p, msg.colonyId());
        Market.handle(p, own, msg);
        sendMarket(p, own);
    }

    private static void onFinance(FinanceAction msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        IColony c = colony(p, msg.colonyId());
        if (c == null) return;
        Finance.handle(p, c, msg.op(), msg.amount(), msg.source(), msg.target());
        sendStats(p, c);
    }

    private static Quote quote(ServerPlayer p, IColony c, List<UUID> ids) {
        String deny = Fulfil.denyReason(p, c);
        boolean creative = p.isCreative() && Fulfil.isManager(p, c) && BoardConfig.CREATIVE_FREE.get();
        List<QuoteLine> lines = new ArrayList<>();
        List<UUID> okIds = new ArrayList<>();
        long total = 0;
        for (Fulfil.Line l : Fulfil.scan(c, new HashSet<>(ids), creative)) {
            long cost = creative ? 0 : (l.ok() ? l.cost() : -1);
            lines.add(new QuoteLine(Fulfil.name(l), l.qty, cost, l.reason == null ? "" : l.reason));
            if (l.ok()) { okIds.add(l.id); total += Math.max(0, cost); }
            if (lines.size() >= 200) break;
        }
        String dt = Fulfil.sourceDeny(p, c, Fulfil.SRC_TREASURY), dw = Fulfil.sourceDeny(p, c, Fulfil.SRC_WALLET);
        return new Quote(c.getID(), okIds, lines, total, creative, Wallet.balance(p), deny == null ? "" : deny,
            ColonyState.treasury(c), dt == null ? "" : dt, dw == null ? "" : dw);
    }

    private static void onQuote(QuoteRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null) return;
        IColony c = colony(p, msg.colonyId());
        if (c == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), quote(p, c, msg.ids()));
    }

    private static void onFulfil(FulfilRequest msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer p = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (p == null || msg.ids().isEmpty()) return;
        long now = System.currentTimeMillis();
        Long last = LAST_FULFIL.get(p.getUUID());
        if (last != null && now - last < 400) return;
        LAST_FULFIL.put(p.getUUID(), now);
        IColony c = colony(p, msg.colonyId());
        if (c == null) return;
        int src = msg.source() == Fulfil.SRC_WALLET ? Fulfil.SRC_WALLET : Fulfil.SRC_TREASURY;
        Fulfil.Result r = Fulfil.execute(p, c, new HashSet<>(msg.ids()), msg.confirmedTotal(), src);
        List<UUID> ids = msg.ids();
        if (r.error() != null) {
            Component chat = Component.translatable("colonyboard.fulfil.fail", Component.translatable(r.error())).withStyle(ChatFormatting.RED);
            Component title = Component.translatable(r.error().equals("colonyboard.reason.rights") ? "colonyboard.toast.rights" : "colonyboard.toast.t.payfail");
            glass(p, new Glass(Toast.FAIL, title, Component.translatable(r.error()), Component.empty(), c.getID(), ids,
                Glass.ACT_CONFIRM | Glass.ACT_OPEN), chat);
        } else if (r.requests() <= 0) {
            glass(p, new Glass(Toast.FAIL, Component.translatable("colonyboard.toast.t.payfail"), Component.translatable("colonyboard.toast.fulfil.none"),
                Component.empty(), c.getID(), ids, Glass.ACT_OPEN), Component.translatable("colonyboard.toast.fulfil.none").withStyle(ChatFormatting.YELLOW));
        } else if (r.spent() <= 0) {
            glass(p, new Glass(Toast.OK, Component.translatable("colonyboard.toast.t.payfree"), Component.translatable("colonyboard.toast.fulfil.free", r.items(), r.requests()),
                Component.empty(), c.getID(), List.of(), Glass.ACT_OPEN),
                Component.translatable("colonyboard.fulfil.okfree", r.requests(), r.items()).withStyle(ChatFormatting.GREEN));
        } else {
            boolean tr = src == Fulfil.SRC_TREASURY;
            long left = (long) Math.floor(Fulfil.balance(p, c, src));
            glass(p, new Glass(Toast.OK, Component.translatable(tr ? "colonyboard.toast.t.paytreasury" : "colonyboard.toast.t.paywallet"),
                Component.translatable(tr ? "colonyboard.toast.fulfil.treasury" : "colonyboard.toast.fulfil.wallet", r.items(), r.requests(), r.spent(), left),
                Component.translatable("colonyboard.toast.hint"), c.getID(), List.of(), Glass.ACT_OPEN),
                Component.translatable(tr ? "colonyboard.fulfil.ok.treasury" : "colonyboard.fulfil.ok.wallet", r.requests(), r.items(), r.spent()).withStyle(ChatFormatting.GREEN));
        }
        c.getPackageManager().removeCloseSubscriber(p);
        c.getPackageManager().addCloseSubscriber(p);
        sendStats(p, c);
    }

    // ------------------------------------------------------------------ client
    private static void onStats(Stats msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ClientState.stats = msg);
    }

    private static void onMarketData(MarketData msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ClientState.onMarket(msg));
    }

    private static void onToast(Toast msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ToastOverlay.push(msg));
    }

    private static void onGlass(Glass msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ToastOverlay.push(msg));
    }

    private static void onQuoteReply(Quote msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ru.wik.colonyboard.client.ClientState.onQuote(msg));
    }
}
