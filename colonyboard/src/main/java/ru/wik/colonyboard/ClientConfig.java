package ru.wik.colonyboard;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client config: config/colonyboard-client.toml */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue TOASTS;
    public static final ForgeConfigSpec.IntValue DURATION_MS, ACTION_DURATION_MS, MAX_VISIBLE;
    /** 1.7: J -> Избранное, quick actions pinned to the top of the dashboard (ids, max 4). */
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> FAVORITES;
    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("toasts");
        TOASTS = b.comment("Show glass toasts above the chat (bottom-left). false = put them into chat instead").define("enabled", true);
        DURATION_MS = b.comment("How long one toast stays, ms").defineInRange("durationMs", 3500, 1000, 15000);
        ACTION_DURATION_MS = b.comment("How long a toast with buttons (Confirm / Open request) stays, ms. Paused while chat (T) is open").defineInRange("actionDurationMs", 8000, 2000, 60000);
        MAX_VISIBLE = b.comment("Max toasts stacked at once").defineInRange("maxVisible", 4, 1, 10);
        b.pop();
        b.push("j");
        FAVORITES = b.comment("Quick actions pinned on J -> Сводка (max 4). Edit in game: J -> Избранное")
            .defineList("favorites", java.util.List.of("buy_stuck", "claim_tax", "auto_toggle", "market"), o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }
    private ClientConfig() {}
}
