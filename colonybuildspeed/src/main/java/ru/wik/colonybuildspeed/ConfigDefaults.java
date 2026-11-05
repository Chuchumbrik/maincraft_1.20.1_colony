package ru.wik.colonybuildspeed;

/**
 * Defaults shipped in the jar. A server toml may override them.
 * {@link #REACH} is 5. The spec allows up to {@link #REACH_MAX} (10), which is what the live
 * {@code colonybuildspeed-server.toml} on this pack sets. Do not treat 10 as the default.
 */
public final class ConfigDefaults {
    public static final boolean ENABLED = true;
    public static final int BLOCKS = 4;
    public static final int BLOCKS_MIN = 1;
    public static final int BLOCKS_MAX = 16;
    public static final double REACH = 5.0;
    public static final double REACH_MIN = 1.0;
    public static final double REACH_MAX = 10.0;
    public static final boolean BUILDERS_ONLY = true;
    public static final boolean DECORATE = false;
    public static final boolean DEMOLISH = true;

    private ConfigDefaults() {
    }
}
