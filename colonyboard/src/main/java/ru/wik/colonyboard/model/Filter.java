package ru.wik.colonyboard.model;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Filter {
    private Filter() {}

    /** building == null means all buildings. Blocking rows first, then by sortKey (higher = worse). */
    public static List<Row> apply(List<Row> rows, BlockPos building, boolean blockingOnly) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (blockingOnly && !r.blocking()) continue;
            if (building != null && !building.equals(r.building())) continue;
            out.add(r);
        }
        out.sort(Comparator.comparing((Row r) -> !r.blocking()).thenComparing(Row::sortKey, Comparator.reverseOrder()));
        return out;
    }

    /** Pick the most negative happiness factor: largest (1 - factor) * weight among factors below 1. */
    public static int worstFactor(double[] factors, double[] weights) {
        int best = -1; double bestScore = 0;
        for (int i = 0; i < factors.length; i++) {
            if (factors[i] >= 1.0) continue;
            double s = (1.0 - factors[i]) * weights[i];
            if (s > bestScore) { bestScore = s; best = i; }
        }
        return best;
    }
}
