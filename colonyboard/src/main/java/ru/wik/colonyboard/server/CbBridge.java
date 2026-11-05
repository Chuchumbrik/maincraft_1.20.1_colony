package ru.wik.colonyboard.server;

import net.minecraft.world.item.ItemStack;

/** Isolated so colonybuy classes are only loaded when colonybuy is present. */
final class CbBridge {
    private static final ThreadLocal<String> LAST = new ThreadLocal<>();
    private CbBridge() {}

    /** colonyboard lang key for the reason of the last refused unit() call on this thread, or null. */
    static String lastReason() { return LAST.get(); }

    /** >0 price, -1 unpriced, -2 refused (blacklist / nbt / other) */
    static double unit(ItemStack s) {
        LAST.remove();
        ItemStack one = s.copyWithCount(1);
        if (one.hasTag() && !Pricing.realTag(one)) one.setTag(null);
        ru.wik.colonybuy.Prices.Quote q = ru.wik.colonybuy.Prices.quote(one);
        if (q.ok()) return q.unit();
        LAST.set(map(q.reason()));
        return "colonybuy.reason.unpriced".equals(q.reason()) ? -1 : -2;
    }

    private static String map(String r) {
        if (r == null) return null;
        return switch (r) {
            case "colonybuy.reason.unpriced" -> "colonyboard.reason.unpriced";
            case "colonybuy.reason.blacklist" -> "colonyboard.reason.blacklist";
            case "colonybuy.reason.nbt" -> "colonyboard.reason.nbt";
            case "colonybuy.reason.domum_off" -> "colonyboard.reason.domum_off";
            case "colonybuy.reason.domum_nodata", "colonybuy.reason.domum_component" -> "colonyboard.reason.domum_component";
            default -> "colonyboard.reason.blacklist";
        };
    }
}
