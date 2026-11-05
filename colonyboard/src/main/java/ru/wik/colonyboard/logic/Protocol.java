package ru.wik.colonyboard.logic;

/**
 * Channel versions. Main stays at 6 so 1.5–1.7.0 clients still join.
 * glass / auto / dash / tier / build are optional ({@code acceptMissingOr}): a client that does not
 * register the channel is accepted and simply does not receive those packets.
 */
public final class Protocol {
    public static final String MAIN = "6";
    public static final String GLASS = "1";
    public static final String AUTO = "1";
    public static final String DASH = "1";
    public static final String TIER = "1";
    /** 1.7.3: optional channel for placing a build/upgrade order from J. */
    public static final String BUILD = "1";

    private Protocol() {}
}
