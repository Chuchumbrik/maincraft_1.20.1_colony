package ru.wik.colonyboard.server;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.fml.ModList;
import ru.wik.colonyboard.BoardConfig;

/** Unit price for paid fulfilment. unit < 0 means refused; reason is a lang key. */
public final class Pricing {
    public record Quote(double unit, String reason) { public boolean ok() { return unit >= 0; } }
    private Pricing() {}

    /** Tool requests: tier based price, ignores colonybuy blacklist (enchanted tools still refused). */
    public static Quote toolQuote(ItemStack s) { return toolQuote(s, false); }

    /** strict = also refuse tools that colonybuy blacklists (diamond/netherite/golden...), used first so cheap safe tools win. */
    public static Quote toolQuote(ItemStack s, boolean strict) {
        if (s.isEnchanted()) return new Quote(-1, "colonyboard.reason.enchanted");
        Quote q = quote(s);
        if (q.ok()) return q;
        if (strict && "colonyboard.reason.blacklist".equals(q.reason())) return q;
        int tier = s.getItem() instanceof net.minecraft.world.item.TieredItem t ? Math.max(0, t.getTier().getLevel()) : 1;
        return new Quote(BoardConfig.TOOL_BASE.get() * Math.pow(BoardConfig.TOOL_TIER_FACTOR.get(), tier) * BoardConfig.MULTIPLIER.get(), null);
    }

    public static Quote quote(ItemStack s) {
        if (s.isEmpty()) return new Quote(-1, "colonyboard.reason.type");
        if (realTag(s) && !BoardConfig.ALLOW_NBT.get() && !isDomum(s)) return new Quote(-1, "colonyboard.reason.nbt");
        double mult = BoardConfig.MULTIPLIER.get();
        if (BoardConfig.USE_COLONYBUY_PRICES.get() && ModList.get().isLoaded("colonybuy")) {
            try {
                double u = CbBridge.unit(s);
                if (u > 0) return new Quote(u * mult, null);
                if (u == -2 || !BoardConfig.ALLOW_FALLBACK.get()) {
                    String why = CbBridge.lastReason();
                    return new Quote(-1, why != null ? why : (u == -2 ? "colonyboard.reason.blacklist" : "colonyboard.reason.unpriced"));
                }
            } catch (Throwable ignored) {}
        } else if (!BoardConfig.ALLOW_FALLBACK.get()) return new Quote(-1, "colonyboard.reason.unpriced");
        return new Quote(fallback(s.getRarity(), BoardConfig.FALLBACK_BASE.get(), BoardConfig.RARITY_UNCOMMON.get(),
            BoardConfig.RARITY_RARE.get(), BoardConfig.RARITY_EPIC.get()) * mult, null);
    }

    public static final String DOMUM_NS = "domum_ornamentum";

    /** Domum Ornamentum block item with material data (shingles, framed, pillars...). Item.toString() is only the path
     *  ("shingle"), so the namespace must come from the registry key. */
    public static boolean isDomum(ItemStack s) {
        if (s.isEmpty()) return false;
        net.minecraft.resources.ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem());
        if (rl == null || !DOMUM_NS.equals(rl.getNamespace())) return false;
        return s.hasTag() && s.getTag().contains("textureData", net.minecraft.nbt.Tag.TAG_COMPOUND) && !s.getTag().getCompound("textureData").isEmpty();
    }

    /** Fresh damageable items carry {Damage:0}; that is not "real" NBT. */
    public static boolean realTag(ItemStack s) {
        if (!s.hasTag()) return false;
        for (String k : s.getTag().getAllKeys()) if (!(k.equals("Damage") && s.getTag().getInt("Damage") == 0)) return true;
        return false;
    }

    public static double fallback(Rarity r, double base, double unc, double rare, double epic) {
        return switch (r) { case UNCOMMON -> base * unc; case RARE -> base * rare; case EPIC -> base * epic; default -> base; };
    }

    public static long cost(double unit, int qty) { return qty <= 0 || unit <= 0 ? 0 : (long) Math.max(1, Math.ceil(unit * qty - 1e-9)); }
}
