package ru.wik.colonyboard.server;

import com.minecolonies.api.colony.IColony;
import ru.wik.colonyboard.logic.DimensionColony;

/** Dimension + colony id. MineColonies ids repeat across dimensions. */
public final class ColonyKey {
    private ColonyKey() {}

    public static String dimension(IColony c) {
        try { return c.getDimension().location().toString(); }
        catch (Throwable t) { return ""; }
    }

    public static String of(IColony c) {
        return DimensionColony.key(dimension(c), c.getID());
    }
}
