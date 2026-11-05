package ru.wik.colonyboard.server;

import com.minecolonies.api.MinecoloniesAPIProxy;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBarracksTower;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import ru.wik.colonyboard.logic.BuildActions;
import ru.wik.colonyboard.logic.BuildActions.Act;
import ru.wik.colonyboard.logic.BuildActions.Deny;
import ru.wik.colonyboard.logic.BuildActions.Facts;
import ru.wik.colonyboard.logic.BuildActions.Gate;
import ru.wik.colonyboard.logic.BuildActions.Kind;
import ru.wik.colonyboard.logic.BuildActions.Native;
import ru.wik.colonyboard.logic.BuildActions.Offer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Placed huts the Build tab can start or upgrade.
 * The click calls {@link IBuilding#requestUpgrade} with {@link BlockPos#ZERO}, the same path as the
 * hut GUI when no builder is picked. Preview-only blueprints are not buildings and are not listed.
 */
public final class BuildHut {
    private static final Logger LOG = LogUtils.getLogger();
    public static final int MAX_ROWS = 160;

    public static final class Hut {
        public final long pos;
        public final String literal;
        public final String key;
        public final int level;
        public final int max;
        public final int target;
        public final int kind;
        public final int nativeType;
        public final String deny;
        public Hut(long pos, String literal, String key, int level, int max, int target, int kind, int nativeType, String deny) {
            this.pos = pos; this.literal = literal == null ? "" : literal; this.key = key == null ? "" : key;
            this.level = level; this.max = max; this.target = target; this.kind = kind; this.nativeType = nativeType;
            this.deny = deny == null ? "" : deny;
        }
    }

    public static final class Listing {
        public final boolean canManage;
        public final int unreadable;
        public final boolean truncated;
        public final List<Hut> huts;
        public Listing(boolean canManage, int unreadable, boolean truncated, List<Hut> huts) {
            this.canManage = canManage; this.unreadable = unreadable; this.truncated = truncated; this.huts = huts;
        }
    }

    public static final class Outcome {
        public final Deny deny;
        public final long pos;
        public final int level;
        public final int target;
        public final String literal;
        public final String key;
        public Outcome(Deny deny, long pos, int level, int target, String literal, String key) {
            this.deny = deny; this.pos = pos; this.level = level; this.target = target;
            this.literal = literal == null ? "" : literal; this.key = key == null ? "" : key;
        }
    }

    private BuildHut() {}

    public static Gate access(ServerPlayer p, int colonyId) {
        IColonyManager mgr = IColonyManager.getInstance();
        IColony here = null;
        try { here = mgr.getColonyByWorld(colonyId, p.level()); } catch (Throwable ignored) {}
        boolean member = false;
        try { member = here != null && here.getPermissions().isColonyMember(p); } catch (Throwable ignored) {}
        boolean elsewhere = false;
        if (here == null && p.getServer() != null) {
            for (ServerLevel level : p.getServer().getAllLevels()) {
                if (level == p.level()) continue;
                try {
                    if (mgr.getColonyByWorld(colonyId, level) != null) { elsewhere = true; break; }
                } catch (Throwable ignored) {}
            }
        }
        return BuildActions.gate(here != null, member, elsewhere);
    }

    public static IColony colonyIfAllowed(ServerPlayer p, int colonyId) {
        if (access(p, colonyId) != Gate.OK) return null;
        try { return IColonyManager.getInstance().getColonyByWorld(colonyId, p.level()); } catch (Throwable t) { return null; }
    }

    public static Listing list(ServerPlayer p, IColony c) {
        boolean manage = canManage(p, c);
        List<Hut> rows = new ArrayList<>();
        int bad = 0;
        boolean truncated = false;
        try {
            for (IBuilding building : c.getServerBuildingManager().getBuildings().values()) {
                if (rows.size() >= MAX_ROWS) { truncated = true; break; }
                try {
                    Hut hut = one(p, c, building, manage);
                    if (hut != null) rows.add(hut);
                } catch (Throwable t) { bad++; }
            }
        } catch (Throwable t) {
            LOG.warn("[colonyboard] build list unreadable for colony {}: {}", c.getID(), t.toString());
            return new Listing(manage, -1, false, List.of());
        }
        rows.sort(Comparator.comparing((Hut h) -> (h.literal.isEmpty() ? h.key : h.literal)).thenComparingLong(h -> h.pos));
        return new Listing(manage, bad, truncated, rows);
    }

    public static Outcome act(ServerPlayer p, IColony c, long posLong, int expectedLevel, int kindId) {
        Kind kind = kindId == Kind.UPGRADE.ordinal() ? Kind.UPGRADE : kindId == Kind.START.ordinal() ? Kind.START : null;
        BlockPos pos = BlockPos.of(posLong);
        IBuilding building = null;
        try { building = c.getServerBuildingManager().getBuilding(pos); } catch (Throwable ignored) {}
        if (!(building instanceof AbstractBuilding)) {
            return new Outcome(building == null ? Deny.NOT_PLACED : Deny.UNSUPPORTED, posLong, 0, 0, "", "");
        }
        Measured m = measure(c, building, canManage(p, c));
        Act decision = BuildActions.commit(m.facts, expectedLevel, kind, Gate.OK);
        String literal = m.literal, key = m.key;
        if (!decision.callNative()) {
            LOG.info("[colonyboard] {} build {} denied ({}) at {} colony {} {}", p.getGameProfile().getName(),
                kind, decision.deny(), pos.toShortString(), c.getID(), ColonyKey.dimension(c));
            return new Outcome(decision.deny(), posLong, m.facts.level, m.offer.target(), literal, key);
        }
        boolean queued;
        try {
            building.requestUpgrade(p, BlockPos.ZERO);
            queued = orderAt(c, pos);
        } catch (Throwable t) {
            queued = orderAt(c, pos);
            if (!queued) {
                LOG.warn("[colonyboard] requestUpgrade failed at {} colony {}: {}", pos.toShortString(), c.getID(), t.toString());
                return new Outcome(Deny.DATA, posLong, m.facts.level, m.offer.target(), literal, key);
            }
        }
        if (!queued) {
            LOG.info("[colonyboard] {} requestUpgrade added no order at {} colony {}", p.getGameProfile().getName(), pos.toShortString(), c.getID());
            return new Outcome(Deny.NATIVE_REFUSED, posLong, m.facts.level, m.offer.target(), literal, key);
        }
        LOG.info("[colonyboard] {} queued {} {}→{} at {} colony {} {}", p.getGameProfile().getName(),
            m.offer.nativeType(), m.facts.level, m.offer.target(), pos.toShortString(), c.getID(), ColonyKey.dimension(c));
        try { c.getPackageManager().setDirty(); } catch (Throwable ignored) {}
        return new Outcome(Deny.NONE, posLong, m.facts.level, m.offer.target(), literal, key);
    }

    private static Hut one(ServerPlayer p, IColony c, IBuilding building, boolean manage) {
        if (!(building instanceof AbstractBuilding) && building != null) {
            // still describe it, disabled
        }
        if (building == null || building.getID() == null) return null;
        Measured m = measure(c, building, manage);
        if (!m.offer.show()) return null;
        int nativeId = m.offer.nativeType() == null ? -1 : m.offer.nativeType().ordinal();
        return new Hut(building.getID().asLong(), m.literal, m.key, m.facts.level, m.facts.maxLevel,
            m.offer.target(), m.offer.kind().ordinal(), nativeId, BuildActions.lang(m.offer.deny()));
    }

    private static final class Measured {
        final Facts facts;
        final Offer offer;
        final String literal;
        final String key;
        Measured(Facts facts, Offer offer, String literal, String key) {
            this.facts = facts; this.offer = offer; this.literal = literal; this.key = key;
        }
    }

    private static Measured measure(IColony c, IBuilding building, boolean manage) {
        Facts f = new Facts();
        f.placed = true;
        f.manageHuts = manage;
        String literal = "";
        String key = "";
        try {
            String custom = building.getCustomName();
            if (custom != null && !custom.isBlank()) literal = custom;
        } catch (Throwable ignored) {}
        try { key = building.getBuildingType().getTranslationKey(); } catch (Throwable ignored) {}
        try { f.level = building.getBuildingLevel(); } catch (Throwable t) { f.level = 0; f.researchKnown = false; }
        try { f.maxLevel = Math.max(1, building.getMaxBuildingLevel()); } catch (Throwable t) { f.maxLevel = f.level; f.researchKnown = false; }
        if (building instanceof BuildingBarracksTower tower) {
            f.mode = BuildActions.Mode.BARRACKS_TOWER;
            BlockPos barracks = barracksPos(tower);
            IBuilding parent = barracks == null ? null : buildingAt(c, barracks);
            if (parent != null) {
                f.parentPresent = true;
                try { f.parentLevel = parent.getBuildingLevel(); } catch (Throwable ignored) {}
                try { f.parentMax = parent.getMaxBuildingLevel(); } catch (Throwable ignored) {}
            }
        } else if (building instanceof AbstractBuilding) {
            f.mode = BuildActions.Mode.HUT;
            try {
                if (building.hasParent()) {
                    IBuilding parent = buildingAt(c, building.getParent());
                    if (parent != null) {
                        f.parentPresent = true;
                        f.parentLevel = parent.getBuildingLevel();
                        f.parentMax = parent.getMaxBuildingLevel();
                    }
                }
            } catch (Throwable t) { f.researchKnown = false; }
            try {
                var block = building.getBuildingType().getBuildingBlock();
                ResourceLocation effect = c.getResearchManager().getResearchEffectIdFrom(block);
                if (effect != null && MinecoloniesAPIProxy.getInstance().getGlobalResearchTree().hasResearchEffect(effect)) {
                    f.hasResearch = true;
                    f.researchStrength = c.getResearchManager().getResearchEffects().getEffectStrength(effect);
                }
                f.researchKnown = true;
            } catch (Throwable t) { f.researchKnown = false; }
        } else {
            f.mode = BuildActions.Mode.UNSUPPORTED;
        }
        try { f.orderAtHut = building.isPendingConstruction(); }
        catch (Throwable t) { f.orderAtHut = false; }
        if (!f.orderAtHut) {
            try { f.orderAtHut = orderAt(c, building.getID()); }
            catch (Throwable t) { f.checksKnown = false; }
        }
        int probe = BuildActions.probeTarget(f);
        Level world = null;
        try { world = c.getWorld(); } catch (Throwable ignored) {}
        boolean loaded = false;
        try { loaded = world != null && building.getPosition() != null && world.isLoaded(building.getPosition()); }
        catch (Throwable t) { loaded = false; }
        f.chunkLoaded = loaded;
        if (probe >= 0) {
            try {
                Native structural = BuildActions.structuralNative(f);
                WorkOrderType type = structural == Native.BUILD ? WorkOrderType.BUILD : WorkOrderType.UPGRADE;
                WorkOrderBuilding tmp = WorkOrderBuilding.create(type, building);
                f.canBeBuiltByBuilder = building.canBeBuiltByBuilder(probe);
                f.canBeResolved = tmp.canBeResolved(c, probe);
                f.tooFar = tmp.tooFarFromAnyBuilder(c, probe);
                f.checksKnown = true;
            } catch (Throwable t) { f.checksKnown = false; }
            try {
                f.anchorInside = world != null && c.isCoordInColony(world, building.getID());
                f.anchorKnown = world != null;
            } catch (Throwable t) { f.anchorKnown = false; }
            if (loaded) {
                try {
                    var corners = building.getCorners();
                    BlockPos low = (BlockPos) corners.getA();
                    BlockPos high = (BlockPos) corners.getB();
                    int maxY = world.getMaxBuildHeight();
                    int minY = world.getMinBuildHeight();
                    f.tooHigh = low.getY() >= maxY || high.getY() >= maxY;
                    f.tooLow = building.getPosition().getY() <= minY;
                    f.heightKnown = true;
                    f.cornersKnown = true;
                } catch (Throwable t) {
                    f.heightKnown = false;
                    f.cornersKnown = false;
                }
            } else {
                f.heightKnown = false;
                f.cornersKnown = false;
            }
        }
        return new Measured(f, BuildActions.preview(f), literal, key == null ? "" : key);
    }

    private static BlockPos barracksPos(BuildingBarracksTower tower) {
        try {
            CompoundTag tag = tower.serializeNBT();
            if (tag != null && tag.contains("pos")) return NbtUtils.readBlockPos(tag.getCompound("pos"));
        } catch (Throwable ignored) {}
        return null;
    }

    private static IBuilding buildingAt(IColony c, BlockPos pos) {
        if (pos == null) return null;
        try {
            return c.getServerBuildingManager().getBuilding(pos);
        } catch (Throwable t) { return null; }
    }

    private static boolean orderAt(IColony c, BlockPos pos) {
        if (pos == null) return false;
        for (IServerWorkOrder order : c.getWorkManager().getWorkOrders().values()) {
            if (order instanceof WorkOrderBuilding && pos.equals(order.getLocation())) return true;
        }
        return false;
    }

    private static boolean canManage(ServerPlayer p, IColony c) {
        try { return c.getPermissions().hasPermission(p, Action.MANAGE_HUTS); } catch (Throwable t) { return false; }
    }

    public static Component label(String literal, String key) {
        if (literal != null && !literal.isBlank()) return Component.literal(literal);
        if (key != null && !key.isBlank()) return Component.translatable(key);
        return Component.translatable("colonyboard.build.noname");
    }
}
