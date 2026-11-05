package ru.wik.colonyboard.logic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * MineColonies colony ids are unique per dimension, not globally (WarNTaxes logs the same fact).
 * Saved rows must keep the dimension. A legacy row with only an id may be bound when exactly one
 * loaded colony has that id. Zero matches or several matches are not guessed.
 */
public final class DimensionColony {
    public enum Status { BOUND, AMBIGUOUS, UNKNOWN }

    public record Bind(Status status, String dimension) {
        public boolean bound() { return status == Status.BOUND && dimension != null && !dimension.isEmpty(); }
    }

    private DimensionColony() {}

    public static String key(String dimension, int colonyId) {
        return (dimension == null ? "" : dimension) + "#" + colonyId;
    }

    public static Bind bind(String storedDimension, List<String> loadedDimensionsWithSameId) {
        if (storedDimension != null && !storedDimension.isEmpty()) return new Bind(Status.BOUND, storedDimension);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (loadedDimensionsWithSameId != null) {
            for (String d : loadedDimensionsWithSameId) if (d != null && !d.isEmpty()) unique.add(d);
        }
        if (unique.size() == 1) return new Bind(Status.BOUND, new ArrayList<>(unique).get(0));
        if (unique.size() >= 2) return new Bind(Status.AMBIGUOUS, null);
        return new Bind(Status.UNKNOWN, null);
    }

    /** A missing colony is "gone" only when its dimension is loaded and that id is absent there. */
    public static boolean confirmedGone(boolean dimensionLoaded, boolean colonyPresent) {
        return dimensionLoaded && !colonyPresent;
    }
}
