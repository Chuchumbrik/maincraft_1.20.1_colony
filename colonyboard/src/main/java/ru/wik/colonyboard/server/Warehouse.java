package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.*;

/**
 * Direct access to the colony warehouse(s): the warehouse hut block itself plus every rack registered as its container.
 * Only loaded chunks are touched. Rack inventories are fetched fresh each call (not MineColonies' cached combined
 * handler) so an unloaded/reloaded rack can never be read through a stale handler.
 */
public final class Warehouse {
    private Warehouse() {}

    public record Stock(ItemStack proto, int count) {}

    /** Inventories of all loaded warehouse racks; empty if the colony has no warehouse or it is not loaded. */
    public static List<IItemHandler> handlers(IColony c) {
        List<IItemHandler> out = new ArrayList<>();
        Level w = c.getWorld();
        if (w == null) return out;
        Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IWareHouse> whs;
        try { whs = c.getServerBuildingManager().getWareHouses(); } catch (Throwable t) { return out; }
        for (IWareHouse wh : whs) {
            List<BlockPos> poss = new ArrayList<>();
            poss.add(wh.getID());
            try { poss.addAll(wh.getContainers()); } catch (Throwable ignored) {}
            for (BlockPos pos : poss) {
                if (!w.isLoaded(pos)) continue;
                BlockEntity te = w.getBlockEntity(pos);
                if (te instanceof AbstractTileEntityRack r && !te.isRemoved()) {
                    IItemHandler h = r.getInventory();
                    if (h != null && seen.add(h)) out.add(h);
                }
            }
        }
        return out;
    }

    public static boolean available(IColony c) { return !handlers(c).isEmpty(); }

    public static boolean same(ItemStack a, ItemStack b) { return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameTags(a, b); }

    /** Distinct items with total counts, biggest first. */
    public static List<Stock> stock(IColony c, int limit) {
        List<Stock> out = new ArrayList<>();
        Map<net.minecraft.world.item.Item, List<int[]>> idx = new HashMap<>();
        List<ItemStack> protos = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (IItemHandler h : handlers(c)) {
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack s = h.getStackInSlot(i);
                if (s.isEmpty()) continue;
                List<int[]> l = idx.computeIfAbsent(s.getItem(), k -> new ArrayList<>());
                int found = -1;
                for (int[] e : l) if (ItemStack.isSameItemSameTags(protos.get(e[0]), s)) { found = e[0]; break; }
                if (found < 0) { found = protos.size(); protos.add(s.copyWithCount(1)); counts.add(0); l.add(new int[]{found}); }
                counts.set(found, (int) Math.min(Integer.MAX_VALUE, (long) counts.get(found) + s.getCount()));
            }
        }
        for (int i = 0; i < protos.size(); i++) out.add(new Stock(protos.get(i), counts.get(i)));
        out.sort((x, y) -> Integer.compare(y.count(), x.count()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public static int count(IColony c, ItemStack proto) {
        long n = 0;
        for (IItemHandler h : handlers(c))
            for (int i = 0; i < h.getSlots(); i++) { ItemStack s = h.getStackInSlot(i); if (same(s, proto)) n += s.getCount(); }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** Removes up to n items matching proto; returns how many were actually removed. */
    public static int extract(IColony c, ItemStack proto, int n) {
        int got = 0;
        for (IItemHandler h : handlers(c)) {
            for (int i = 0; i < h.getSlots() && got < n; i++) {
                ItemStack s = h.getStackInSlot(i);
                if (!same(s, proto)) continue;
                ItemStack out = h.extractItem(i, Math.min(n - got, s.getCount()), false);
                if (same(out, proto)) got += out.getCount();
            }
            if (got >= n) break;
        }
        return got;
    }

    /** Puts n items of proto back into the warehouse; returns how many did NOT fit. */
    public static int insert(IColony c, ItemStack proto, int n) {
        int left = n, max = Math.max(1, proto.getMaxStackSize());
        for (IItemHandler h : handlers(c)) {
            while (left > 0) {
                int k = Math.min(max, left);
                ItemStack rest = ItemHandlerHelper.insertItemStacked(h, proto.copyWithCount(k), false);
                int ins = k - (rest.isEmpty() ? 0 : rest.getCount());
                left -= ins;
                if (ins < k) break;
            }
            if (left <= 0) break;
        }
        return left;
    }
}
