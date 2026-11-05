package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.workerbuildings.ITownHall;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import ru.wik.colonyboard.Net;
import ru.wik.colonyboard.logic.DimensionColony;
import ru.wik.colonyboard.logic.ToolFloor;

import java.util.*;

/**
 * 1.7.1: min tool-tier floor for MineColonies dig-tool requests (pick/axe/shovel/hoe).
 * Raises only the {@code minLevel} on newly created {@code Tool} requests; does not strip held tools.
 * Stored per dimension + colony id. Default off. Hut rows whose building is gone are dropped.
 */
public final class ToolTier extends SavedData {
    public static final int MODE_COLONY = ToolFloor.MODE_COLONY, MODE_HUT = ToolFloor.MODE_HUT;
    public static final int FLOOR_OFF = ToolFloor.OFF, FLOOR_STONE = ToolFloor.STONE, FLOOR_IRON = ToolFloor.IRON, FLOOR_DIAMOND = ToolFloor.DIAMOND;
    private static final Logger LOG = LogUtils.getLogger();
    private static final String NAME = "colonyboard_tooltier";
    private static int ticks;

    public static final class Entry {
        public int mode = MODE_COLONY;
        public int colonyFloor = FLOOR_OFF;
        public final Map<Long, Integer> huts = new HashMap<>();
    }

    private final Map<String, Entry> byKey = new HashMap<>();
    /** Rows saved by 1.7.1 before a dimension was stored. Not applied when the id exists in more than one dimension. */
    private final Map<Integer, Entry> legacy = new HashMap<>();
    private final Map<String, Long> purgedAt = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    public static ToolTier get() {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        return srv.overworld().getDataStorage().computeIfAbsent(ToolTier::load, ToolTier::new, NAME);
    }

    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++ticks % 600 != 0) return;
        if (ServerLifecycleHooks.getCurrentServer() == null) return;
        try {
            ToolTier store = get();
            for (IColony c : IColonyManager.getInstance().getAllColonies()) store.purge(c, true);
        } catch (Throwable t) { LOG.warn("[colonyboard] tool-tier cleanup failed: {}", t.toString()); }
    }

    private Entry view(IColony c) {
        Entry e = lookup(c, false);
        return e == null ? new Entry() : e;
    }

    private Entry edit(IColony c) {
        return lookup(c, true);
    }

    private Entry lookup(IColony c, boolean create) {
        String dim = ColonyKey.dimension(c);
        String key = DimensionColony.key(dim, c.getID());
        Entry e = byKey.get(key);
        if (e != null) return e;
        Entry leg = legacy.get(c.getID());
        if (leg != null) {
            int n = 0;
            try { for (IColony o : IColonyManager.getInstance().getAllColonies()) if (o != null && o.getID() == c.getID()) n++; }
            catch (Throwable t) { n = 2; }
            if (n <= 1) {
                legacy.remove(c.getID());
                byKey.put(key, leg);
                setDirty();
                return leg;
            }
            if (warned.add(key)) LOG.warn("[colonyboard] tool-tier for colony id {} has no dimension and that id exists in {} worlds; stored floor is not applied", c.getID(), n);
        }
        if (!create) return null;
        e = new Entry();
        byKey.put(key, e);
        return e;
    }

    public void purge(IColony c, boolean force) {
        String key = DimensionColony.key(ColonyKey.dimension(c), c.getID());
        Entry e = byKey.get(key);
        if (e == null || e.huts.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!force && now - purgedAt.getOrDefault(key, 0L) < 30_000L) return;
        purgedAt.put(key, now);
        Set<Long> live = new HashSet<>();
        try {
            for (IBuilding b : c.getServerBuildingManager().getBuildings().values()) if (b != null) live.add(b.getID().asLong());
        } catch (Throwable t) { return; }
        Map<Long, Integer> next = ToolFloor.purge(e.huts, live);
        if (!next.equals(e.huts)) {
            e.huts.clear();
            e.huts.putAll(next);
            setDirty();
        }
    }

    public static boolean isDig(EquipmentTypeEntry type) {
        if (type == null) return false;
        try {
            return type == ModEquipmentTypes.pickaxe.get()
                || type == ModEquipmentTypes.axe.get()
                || type == ModEquipmentTypes.shovel.get()
                || type == ModEquipmentTypes.hoe.get();
        } catch (Throwable t) { return false; }
    }

    /** Clamp a requested floor to hut max equipment level and the town-hall gate. */
    public static int clampFloor(IColony c, IBuilding b, int floor) {
        int max = Integer.MAX_VALUE;
        if (b != null) try { max = b.getMaxEquipmentLevel(); } catch (Throwable ignored) {}
        return ToolFloor.clamp(floor, townHallLevel(c), max);
    }

    public static int townHallLevel(IColony c) {
        try {
            Object th = c.getServerBuildingManager().getTownHall();
            if (th instanceof IBuilding b) return b.getBuildingLevel();
        } catch (Throwable ignored) {}
        return 0;
    }

    /** Effective min level for a new dig-tool request, or {@code requested} unchanged. */
    public static int raise(IBuilding building, EquipmentTypeEntry type, int requested) {
        if (building == null || !isDig(type)) return requested;
        if (ServerLifecycleHooks.getCurrentServer() == null) return requested;
        IColony c;
        try { c = building.getColony(); } catch (Throwable t) { return requested; }
        if (c == null) return requested;
        ToolTier store = get();
        store.purge(c, false);
        Entry e = store.view(c);
        int max = Integer.MAX_VALUE;
        try { max = building.getMaxEquipmentLevel(); } catch (Throwable ignored) {}
        Integer hut = e.huts.get(building.getID().asLong());
        return ToolFloor.raise(true, e.mode, e.colonyFloor, hut, requested, townHallLevel(c), max);
    }

    public static Net.TierState state(ServerPlayer p, IColony c) {
        ToolTier store = get();
        store.purge(c, true);
        Entry e = store.view(c);
        boolean mgr = Fulfil.isManager(p, c) || p.hasPermissions(2);
        List<Net.TierHut> huts = new ArrayList<>();
        try {
            for (IBuilding b : c.getServerBuildingManager().getBuildings().values()) {
                if (b == null || b.getBuildingLevel() <= 0) continue;
                if (b instanceof ITownHall) continue;
                int max;
                try { max = b.getMaxEquipmentLevel(); } catch (Throwable t) { continue; }
                if (max <= 0) continue;
                String name;
                try {
                    String custom = b.getCustomName();
                    name = (custom != null && !custom.isEmpty()) ? custom : Component.translatable(b.getBuildingDisplayName()).getString();
                } catch (Throwable t) { name = b.getID().toShortString(); }
                name = name + " L" + b.getBuildingLevel();
                long pos = b.getID().asLong();
                int floor = e.huts.getOrDefault(pos, FLOOR_OFF);
                huts.add(new Net.TierHut(pos, name, b.getBuildingLevel(), max >= Integer.MAX_VALUE / 4 ? 99 : max, floor));
            }
        } catch (Throwable t) { LOG.warn("[colonyboard] tier hut list failed: {}", t.toString()); }
        huts.sort(Comparator.comparing(Net.TierHut::name, String.CASE_INSENSITIVE_ORDER));
        if (huts.size() > 256) huts = huts.subList(0, 256);
        return new Net.TierState(c.getID(), mgr, e.mode, e.colonyFloor, townHallLevel(c), huts);
    }

    public static void apply(ServerPlayer p, IColony c, Net.TierSet msg) {
        if (!(Fulfil.isManager(p, c) || p.hasPermissions(2))) {
            Component m = Component.translatable("colonyboard.reason.rights");
            Net.toast(p, Net.Toast.FAIL, m, false, m);
            return;
        }
        ToolTier store = get();
        store.purge(c, true);
        Entry e = store.edit(c);
        switch (msg.op()) {
            case Net.TierSet.MODE -> {
                e.mode = ToolFloor.sanitizeMode(msg.a());
                toast(p, e.mode == MODE_HUT ? "colonyboard.tier.toast.mode.hut" : "colonyboard.tier.toast.mode.colony");
            }
            case Net.TierSet.COLONY_FLOOR -> {
                int f = ToolFloor.sanitizeFloor(msg.a());
                int clamped = clampFloor(c, null, f);
                e.colonyFloor = clamped;
                if (clamped < f) toast(p, "colonyboard.tier.toast.thgate", clamped);
                else toast(p, "colonyboard.tier.toast.floor", floorKey(e.colonyFloor));
            }
            case Net.TierSet.HUT_FLOOR -> {
                IBuilding b = null;
                try { b = c.getServerBuildingManager().getBuilding(BlockPos.of(msg.hutPos())); } catch (Throwable ignored) {}
                if (b == null) {
                    e.huts.remove(msg.hutPos());
                    store.setDirty();
                    toast(p, "colonyboard.tier.toast.nohut");
                    return;
                }
                int f = ToolFloor.sanitizeFloor(msg.a());
                int clamped = clampFloor(c, b, f);
                if (clamped <= FLOOR_OFF) e.huts.remove(msg.hutPos());
                else e.huts.put(msg.hutPos(), clamped);
                if (clamped < f) toast(p, "colonyboard.tier.toast.thgate", clamped);
                else toast(p, "colonyboard.tier.toast.hut", floorKey(clamped));
            }
            default -> { return; }
        }
        store.setDirty();
        Fulfil.LOG.info("[colonyboard] {} set tool-tier op={} a={} hut={} colony={} dim={}",
            p.getGameProfile().getName(), msg.op(), msg.a(), msg.hutPos(), c.getID(), ColonyKey.dimension(c));
    }

    private static String floorKey(int f) {
        return switch (f) {
            case FLOOR_STONE -> "stone";
            case FLOOR_IRON -> "iron";
            case FLOOR_DIAMOND -> "diamond";
            default -> "off";
        };
    }

    private static void toast(ServerPlayer p, String key, Object... args) {
        Object[] a = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object o = args[i];
            if (o instanceof String s && (s.equals("off") || s.equals("stone") || s.equals("iron") || s.equals("diamond")))
                a[i] = Component.translatable("colonyboard.tier.floor." + s);
            else if (o instanceof Integer n && n >= 0 && n <= 3)
                a[i] = Component.translatable("colonyboard.tier.floor." + floorKey(n));
            else a[i] = o;
        }
        Component m = Component.translatable(key, a);
        Net.toast(p, key.equals("colonyboard.tier.toast.nohut") ? Net.Toast.FAIL : Net.Toast.OK, m, false, m);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (var e : byKey.entrySet()) list.add(write(e.getKey(), e.getValue()));
        for (var e : legacy.entrySet()) {
            CompoundTag t = write("", e.getValue());
            t.putInt("id", e.getKey());
            t.putString("dim", "");
            list.add(t);
        }
        tag.put("colonies", list);
        return tag;
    }

    private static CompoundTag write(String key, Entry e) {
        CompoundTag t = new CompoundTag();
        int hash = key.indexOf('#');
        String dim = hash < 0 ? "" : key.substring(0, hash);
        int id = 0;
        if (hash >= 0) try { id = Integer.parseInt(key.substring(hash + 1)); } catch (NumberFormatException ignored) {}
        t.putString("dim", dim);
        t.putInt("id", id);
        t.putInt("mode", ToolFloor.sanitizeMode(e.mode));
        t.putInt("floor", ToolFloor.sanitizeFloor(e.colonyFloor));
        ListTag hs = new ListTag();
        for (var h : ToolFloor.purge(e.huts, e.huts.keySet()).entrySet()) {
            CompoundTag ht = new CompoundTag();
            ht.putLong("p", h.getKey());
            ht.putInt("f", h.getValue());
            hs.add(ht);
        }
        t.put("huts", hs);
        return t;
    }

    private static ToolTier load(CompoundTag tag) {
        ToolTier s = new ToolTier();
        for (Tag x : tag.getList("colonies", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) x;
            Entry e = new Entry();
            e.mode = ToolFloor.sanitizeMode(t.getInt("mode"));
            e.colonyFloor = ToolFloor.sanitizeFloor(t.getInt("floor"));
            for (Tag y : t.getList("huts", Tag.TAG_COMPOUND)) {
                CompoundTag ht = (CompoundTag) y;
                int f = ToolFloor.sanitizeFloor(ht.getInt("f"));
                if (f > FLOOR_OFF) e.huts.put(ht.getLong("p"), f);
            }
            String dim = t.getString("dim");
            int id = t.getInt("id");
            if (dim == null || dim.isEmpty()) s.legacy.put(id, e);
            else s.byKey.put(DimensionColony.key(dim, id), e);
        }
        return s;
    }
}
