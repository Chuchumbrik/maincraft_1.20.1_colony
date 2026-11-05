package ru.wik.colonyboard;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server config: world/serverconfig/colonyboard-server.toml */
public final class BoardConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue FULFIL_ENABLED, CREATIVE_FREE, SURVIVAL_PAID, USE_COLONYBUY_PRICES,
        ALLOW_FALLBACK, ALLOW_NBT, PAY_TREASURY, PAY_WALLET, WALLET_MEMBERS;
    public static final ForgeConfigSpec.DoubleValue MULTIPLIER, FALLBACK_BASE, RARITY_UNCOMMON, RARITY_RARE, RARITY_EPIC, TOOL_BASE, TOOL_TIER_FACTOR;
    public static final ForgeConfigSpec.IntValue MAX_PER_REQUEST;
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY;
    public static final ForgeConfigSpec.BooleanValue FIN_ENABLED, FIN_DEPOSIT, FIN_DEPOSIT_MEMBERS, FIN_WITHDRAW, FIN_CLAIM, FIN_DEBT, FIN_TRANSFER;
    public static final ForgeConfigSpec.IntValue FIN_MAX, FIN_JOURNAL;
    public static final ForgeConfigSpec.BooleanValue TOASTS_ENABLED, TOASTS_FINANCE, TOASTS_WAR, REQ_TOASTS, REQ_ONLY_STUCK, REQ_ON_LOGIN, REQ_REPLACE_CB;
    public static final ForgeConfigSpec.IntValue REQ_SCAN_SEC, REQ_GRACE_SEC, REQ_REMIND_MIN;
    public static final ForgeConfigSpec.BooleanValue MKT_ENABLED, AUC_ENABLED, MKT_OWN_BUY;
    public static final ForgeConfigSpec.IntValue DASH_SURPLUS;
    public static final ForgeConfigSpec.IntValue MKT_MAX_LISTINGS, AUC_MAX_LOTS, AUC_MIN_MIN, AUC_MAX_MIN, AUC_SNIPE_SEC, MKT_MAX_UNITS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("fulfil");
        FULFIL_ENABLED = b.comment("Master switch for the 'Fulfil' buttons").define("enabled", true);
        CREATIVE_FREE = b.comment("Owner/officer in creative mode fulfils requests for free").define("creativeFree", true);
        SURVIVAL_PAID = b.comment("Paid fulfilment in survival (master switch for both pay sources below)").define("survivalPaid", true);
        PAY_TREASURY = b.comment("Pay source 'treasury': owner/officers pay from the WarNTaxes colony treasury").define("payFromTreasury", true);
        PAY_WALLET = b.comment("Pay source 'wallet': the player pays from their own SDM Economy wallet").define("payFromWallet", true);
        WALLET_MEMBERS = b.comment("Any colony member (not only owner/officers) may pay requests from their own wallet").define("walletByMembers", true);
        CURRENCY = b.comment("SDM Economy currency id").define("currency", "sdm_coin");
        MULTIPLIER = b.comment("Final price multiplier").defineInRange("priceMultiplier", 1.0, 0.0, 1000.0);
        USE_COLONYBUY_PRICES = b.comment("Use colonybuy prices.json (+SDM Shop sell-back floor, blacklist) when colonybuy is installed").define("useColonybuyPrices", true);
        ALLOW_FALLBACK = b.comment("Items without a colonybuy price use the fallback formula (blacklisted items stay refused). false = not buyable")
            .define("allowFallbackPrice", true);
        FALLBACK_BASE = b.comment("Fallback unit price = base x rarity factor").defineInRange("fallbackBasePrice", 5.0, 0.0, 1.0E9);
        RARITY_UNCOMMON = b.defineInRange("fallbackUncommonFactor", 3.0, 0.0, 1.0E6);
        RARITY_RARE = b.defineInRange("fallbackRareFactor", 10.0, 0.0, 1.0E6);
        RARITY_EPIC = b.defineInRange("fallbackEpicFactor", 30.0, 0.0, 1.0E6);
        TOOL_BASE = b.comment("Tool requests (worker asks for a pickaxe/axe/...): price = toolBasePrice x toolTierFactor^tier",
            "(wood 0, stone 1, iron 2, diamond 3, netherite 4). Used when colonybuy has no price or blacklists tools").defineInRange("toolBasePrice", 10.0, 0.0, 1.0E9);
        TOOL_TIER_FACTOR = b.defineInRange("toolTierFactor", 3.0, 1.0, 100.0);
        ALLOW_NBT = b.comment("Allow paid fulfilment of items with NBT (creative ignores this)").define("allowNbtItems", false);
        MAX_PER_REQUEST = b.comment("Max items delivered for one request in one go").defineInRange("maxItemsPerRequest", 640, 1, 100000);
        b.pop();
        b.push("finance");
        FIN_ENABLED = b.comment("Master switch for the Finance tab").define("enabled", true);
        FIN_DEPOSIT = b.comment("Deposit into the WarNTaxes treasury from wallet / tax / inventory").define("allowDeposit", true);
        FIN_DEPOSIT_MEMBERS = b.comment("Any colony member may deposit (false = owner/officers only)").define("depositByMembers", true);
        FIN_WITHDRAW = b.comment("Owner/officers may withdraw from the treasury").define("allowWithdraw", true);
        FIN_CLAIM = b.comment("Owner/officers may claim colony tax (same rules as /claimtax)").define("allowClaimTax", true);
        FIN_DEBT = b.comment("Owner/officers may pay tax debt (same rules as /taxdebt pay)").define("allowPayDebt", true);
        FIN_TRANSFER = b.comment("Players may send money from their wallet to an online player").define("allowTransfer", true);
        FIN_MAX = b.comment("Max amount of one operation").defineInRange("maxAmount", 1000000, 1, Integer.MAX_VALUE);
        FIN_JOURNAL = b.comment("How many recent operations per colony the journal keeps (memory only, cleared on restart; all ops also go to the server log)").defineInRange("journalSize", 20, 0, 200);
        b.pop();
        b.push("toasts");
        TOASTS_ENABLED = b.comment("Show colonyboard notifications as toasts above the chat (clients with colonyboard 1.3+). false = old chat messages").define("enabled", true);
        TOASTS_FINANCE = b.comment("Toast on finance operations (deposit/withdraw/tax/debt/transfer, denials)").define("finance", true);
        TOASTS_WAR = b.comment("Toast colony members when war/siege/raid starts or ends (polled every 5 s)").define("war", true);
        REQ_TOASTS = b.comment("1.6.1: glass toast for EVERY new open request (any hut, any request type), one batched toast per scan with Confirm / Open request.",
            "Sent to online colony members who may pay (owner/officers; members too when walletByMembers). Clients with colonyboard 1.6+ only").define("requests", true);
        REQ_ONLY_STUCK = b.comment("Only requests nobody in the colony can deliver (the 'player must bring it' ones, same as the MineColonies 'needs materials' bubble).",
            "false = every open request, including ones the courier/warehouse is about to deliver (noisy)").define("requestsOnlyStuck", true);
        REQ_SCAN_SEC = b.comment("How often open requests are checked, seconds").defineInRange("requestsScanSeconds", 10, 2, 600);
        REQ_GRACE_SEC = b.comment("A request must stay open this long before it is toasted (filters requests the colony resolves by itself in a moment)").defineInRange("requestsGraceSeconds", 15, 0, 3600);
        REQ_REMIND_MIN = b.comment("Re-toast still-open requests every N minutes (one summary toast). 0 = never, only new requests").defineInRange("requestsRemindMinutes", 0, 0, 1440);
        REQ_ON_LOGIN = b.comment("Summary toast of all open requests ~10 s after login").define("requestsOnLogin", true);
        REQ_REPLACE_CB = b.comment("Hide colonybuy's own 'Колонии нужны материалы' prompt for 1.6 clients (this watcher covers it; colonybuy has a 5 min cooldown and builder-only filter)").define("replaceColonybuyPrompt", true);
        b.pop();
        b.push("market");
        MKT_ENABLED = b.comment("Colony market: owner/officers put warehouse goods on sale, any player buys with SDM wallet money, money goes to the colony treasury").define("enabled", true);
        AUC_ENABLED = b.comment("Colony auctions: owner/officers put a warehouse lot up for timed bidding, winning bid goes to the colony treasury").define("auctionEnabled", true);
        MKT_OWN_BUY = b.comment("Members of the selling colony may buy / bid on their own colony's lots").define("ownColonyMayBuy", true);
        MKT_MAX_LISTINGS = b.comment("Max active market listings per colony").defineInRange("maxListingsPerColony", 30, 1, 500);
        AUC_MAX_LOTS = b.comment("Max active auctions per colony").defineInRange("maxAuctionsPerColony", 10, 1, 200);
        AUC_MIN_MIN = b.comment("Shortest auction, minutes").defineInRange("auctionMinMinutes", 5, 1, 10080);
        AUC_MAX_MIN = b.comment("Longest auction, minutes").defineInRange("auctionMaxMinutes", 1440, 1, 10080);
        AUC_SNIPE_SEC = b.comment("A bid in the last N seconds extends the auction to N seconds (0 = off)").defineInRange("antiSnipeSeconds", 60, 0, 3600);
        MKT_MAX_UNITS = b.comment("Max items moved from the warehouse into one listing / lot").defineInRange("maxItemsPerLot", 4096, 1, 1000000);
        b.pop();
        b.push("dashboard");
        DASH_SURPLUS = b.comment("1.7 J -> Склад: an item counts as surplus when the warehouse holds at least this many and no stuck request needs it").defineInRange("surplusMin", 512, 1, 1000000);
        b.pop();
        SPEC = b.build();
    }
    private BoardConfig() {}
}
