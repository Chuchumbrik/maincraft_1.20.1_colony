package ru.wik.colonyboard.logic;

/**
 * Money moves before items. A failed or partial delivery refunds what was not kept.
 * An auction bid replaces the previous escrow only after that refund succeeds.
 */
public final class PayGuard {
    private PayGuard() {}

    public static boolean fitsTreasuryInt(long cost) { return cost >= 0 && cost <= Integer.MAX_VALUE; }

    /** Items may move only after the request is still open, the block entity is alive, and the charge stuck. */
    public static boolean deliverAfterCharge(boolean requestStillOpen, boolean entityAlive, boolean charged) {
        return requestStillOpen && entityAlive && charged;
    }

    /** How much of a pre-charge to give back so the player pays exactly {@code actualCost}. */
    public static long refundOf(long charged, long actualCost) {
        if (charged <= 0) return 0;
        long kept = Math.max(0, actualCost);
        return kept >= charged ? 0 : charged - kept;
    }

    public static boolean commitBid(boolean hadPreviousBidder, boolean previousRefundSucceeded) {
        return !hadPreviousBidder || previousRefundSucceeded;
    }

    /** Wallet was charged {@code charged}. Returns how much is still unaccounted (must be refunded). */
    public static long shortfall(long charged, long treasuryAdded, long parkedForLater) {
        long kept = Math.max(0, treasuryAdded) + Math.max(0, parkedForLater);
        return Math.max(0, charged - kept);
    }
}
