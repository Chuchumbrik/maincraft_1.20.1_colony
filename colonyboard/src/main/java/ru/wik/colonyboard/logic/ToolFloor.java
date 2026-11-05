package ru.wik.colonyboard.logic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 1.7.1 dig-tool floor (pick / axe / shovel / hoe), new requests only.
 * off=0, stone=1, iron=2, diamond=3. Iron needs town hall &gt;= 2, diamond &gt;= 3.
 * The effective floor is also capped by the hut's max equipment level.
 * Default is off. Per-hut mode reads the hut value; colony mode ignores stored hut values.
 */
public final class ToolFloor {
    public static final int OFF = 0, STONE = 1, IRON = 2, DIAMOND = 3;
    public static final int MODE_COLONY = 0, MODE_HUT = 1;

    private ToolFloor() {}

    public static int sanitizeMode(int mode) { return mode == MODE_HUT ? MODE_HUT : MODE_COLONY; }

    public static int sanitizeFloor(int floor) { return Math.min(DIAMOND, Math.max(OFF, floor)); }

    /**
     * @param hutMax equipment cap of the hut. {@link Integer#MAX_VALUE} (or any value &gt;= MAX/4) means unlimited.
     *               Pass that sentinel when the floor is stored for the whole colony and no hut is known yet.
     */
    public static int clamp(int floor, int townHallLevel, int hutMax) {
        if (floor <= OFF) return OFF;
        floor = sanitizeFloor(floor);
        if (floor >= DIAMOND && townHallLevel < 3) floor = IRON;
        if (floor >= IRON && townHallLevel < 2) floor = STONE;
        if (hutMax < Integer.MAX_VALUE / 4) floor = Math.min(floor, Math.max(OFF, hutMax));
        return Math.max(OFF, floor);
    }

    /**
     * Min level to write onto a newly constructed dig-tool request.
     * Non-dig tools and an off floor return {@code requested} unchanged.
     */
    public static int raise(boolean digTool, int mode, int colonyFloor, Integer hutFloor,
                            int requested, int townHallLevel, int hutMax) {
        if (!digTool) return requested;
        int floor = sanitizeMode(mode) == MODE_HUT ? (hutFloor == null ? OFF : hutFloor) : colonyFloor;
        floor = clamp(floor, townHallLevel, hutMax);
        if (floor <= OFF) return requested;
        return Math.max(requested, floor);
    }

    /** Constructor max on the MineColonies request wins, so min never exceeds max. */
    public static int capToRequestMax(int raised, Integer requestMax) {
        if (requestMax == null) return raised;
        return Math.min(raised, requestMax);
    }

    /** Drop floors whose block position is no longer a building of this colony. */
    public static Map<Long, Integer> purge(Map<Long, Integer> stored, Set<Long> livePositions) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        if (stored == null || livePositions == null) return out;
        for (Map.Entry<Long, Integer> e : stored.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || !livePositions.contains(e.getKey())) continue;
            int f = sanitizeFloor(e.getValue());
            if (f > OFF) out.put(e.getKey(), f);
        }
        return out;
    }
}
