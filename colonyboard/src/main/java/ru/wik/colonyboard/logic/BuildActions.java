package ru.wik.colonyboard.logic;

/**
 * What the Build tab may offer for a placed hut, and whether a confirm packet may call
 * MineColonies {@code requestUpgrade}.
 *
 * <p>The rules follow MineColonies 1.1.1237, not a shortcut:
 * {@code AbstractBuilding.requestUpgrade} chooses BUILD when the hut is level 0 and its parent
 * is missing or still level 0, otherwise UPGRADE when the hut is below its max and the parent
 * allows it; {@code BuildingBarracksTower.requestUpgrade} instead requires the barracks to be
 * strictly higher and skips the research gate; {@code requestWorkOrder} then refuses a second
 * building order at the same position, a builder that cannot build the target, a hut farther
 * than every builder when no builder is picked, and a hut outside the build height.
 * {@code AbstractColonyServerMessage} allows the hut button only with {@code Action.MANAGE_HUTS}.
 *
 * <p>A blueprint preview is not a building ({@code placed == false}) and produces no row.
 * A hut that already has a building work order is not offered again; it stays in the queue.
 */
public final class BuildActions {
    public enum Mode { HUT, BARRACKS_TOWER, UNSUPPORTED }
    public enum Kind { START, UPGRADE }
    /** Work-order type {@code requestUpgrade} will actually queue. */
    public enum Native { BUILD, UPGRADE }
    public enum Deny {
        NONE, ALREADY_QUEUED, MAX_LEVEL, PARENT, RESEARCH_LOCKED, RESEARCH_LEVEL,
        BUILDER, TOO_FAR, TOO_HIGH, TOO_LOW, OUTSIDE, UNSUPPORTED, UNLOADED, DATA,
        PERMISSION, WRONG_DIMENSION, FOREIGN, NO_COLONY, STALE, NOT_PLACED, NATIVE_REFUSED, NEED_BARRACKS, BUSY
    }
    public enum Gate { OK, WRONG_DIMENSION, FOREIGN, MISSING }

    private BuildActions() {}

    /** Facts the server measured. Builder flags must be for {@link #probeTarget}. */
    public static final class Facts {
        public boolean placed = true;
        public Mode mode = Mode.HUT;
        public int level;
        public int maxLevel = 5;
        public boolean parentPresent;
        public int parentLevel;
        public int parentMax = 5;
        public boolean hasResearch;
        public double researchStrength;
        public boolean researchKnown = true;
        public boolean orderAtHut;
        public boolean canBeBuiltByBuilder = true;
        public boolean canBeResolved = true;
        public boolean tooFar;
        public boolean checksKnown = true;
        public boolean heightKnown = true;
        public boolean tooHigh;
        public boolean tooLow;
        public boolean anchorInside = true;
        public boolean anchorKnown = true;
        public boolean chunkLoaded = true;
        public boolean cornersKnown = true;
        public boolean manageHuts = true;

        public static Facts placed(int level, int max) {
            Facts f = new Facts();
            f.level = level;
            f.maxLevel = max;
            return f;
        }
    }

    public record Offer(boolean show, Kind kind, Native nativeType, int target, Deny deny) {}

    public record Act(Deny deny, boolean callNative) {}

    public static String lang(Deny d) {
        return switch (d) {
            case NONE -> "";
            case ALREADY_QUEUED -> "colonyboard.build.deny.queued";
            case MAX_LEVEL -> "colonyboard.build.deny.max";
            case PARENT -> "colonyboard.build.deny.parent";
            case RESEARCH_LOCKED -> "colonyboard.build.deny.research";
            case RESEARCH_LEVEL -> "colonyboard.build.deny.researchlevel";
            case BUILDER -> "colonyboard.build.deny.builder";
            case TOO_FAR -> "colonyboard.build.deny.far";
            case TOO_HIGH -> "colonyboard.build.deny.high";
            case TOO_LOW -> "colonyboard.build.deny.low";
            case OUTSIDE -> "colonyboard.build.deny.outside";
            case UNSUPPORTED -> "colonyboard.build.deny.unsupported";
            case UNLOADED -> "colonyboard.build.deny.unloaded";
            case DATA -> "colonyboard.build.deny.data";
            case PERMISSION -> "colonyboard.build.deny.permission";
            case WRONG_DIMENSION -> "colonyboard.build.deny.dimension";
            case FOREIGN -> "colonyboard.build.deny.foreign";
            case NO_COLONY -> "colonyboard.build.deny.nocolony";
            case STALE -> "colonyboard.build.deny.stale";
            case NOT_PLACED -> "colonyboard.build.deny.missing";
            case NATIVE_REFUSED -> "colonyboard.build.deny.native";
            case NEED_BARRACKS -> "colonyboard.build.deny.barracks";
            case BUSY -> "colonyboard.build.deny.busy";
        };
    }

    /** Colony id is only the colony in the player's current dimension. Same id elsewhere is not a fallback. */
    public static Gate gate(boolean foundInPlayerDimension, boolean member, boolean sameIdInOtherDimension) {
        if (foundInPlayerDimension && member) return Gate.OK;
        if (foundInPlayerDimension) return Gate.FOREIGN;
        if (sameIdInOtherDimension) return Gate.WRONG_DIMENSION;
        return Gate.MISSING;
    }

    public static boolean sameColony(String viewDim, int viewId, String hutDim, int hutId) {
        return viewDim != null && !viewDim.isEmpty() && viewDim.equals(hutDim) && viewId == hutId;
    }

    /** Target level a probe of {@code canBeBuiltByBuilder} / {@code canBeResolved} must use, or -1 when no order would be queued. */
    public static int probeTarget(Facts f) {
        Native n = nativeKind(f);
        if (n == null) return -1;
        return n == Native.BUILD ? 1 : f.level + 1;
    }

    /** Work-order type {@code requestUpgrade} would queue, ignoring builder, height and permission. */
    public static Native structuralNative(Facts f) { return nativeKind(f); }

    public static Offer preview(Facts f) {
        if (f == null || !f.placed) return hidden(Deny.NOT_PLACED);
        if (f.orderAtHut) return hidden(Deny.ALREADY_QUEUED);
        if (f.level >= f.maxLevel) return hidden(Deny.MAX_LEVEL);
        Kind kind = f.level <= 0 ? Kind.START : Kind.UPGRADE;
        if (f.mode == Mode.UNSUPPORTED) return new Offer(true, kind, null, Math.max(f.level + 1, 1), Deny.UNSUPPORTED);
        if (!f.manageHuts) return new Offer(true, kind, nativeKind(f), shownTarget(f), Deny.PERMISSION);
        Deny structural = structuralDeny(f);
        Native nativeType = structural == Deny.NONE ? nativeKind(f) : null;
        int target = nativeType == Native.BUILD ? 1 : f.level + 1;
        if (structural != Deny.NONE) return new Offer(true, kind, nativeType, target, structural);
        if (!f.checksKnown) return new Offer(true, kind, nativeType, target, Deny.DATA);
        if (!f.canBeBuiltByBuilder && !f.canBeResolved) return new Offer(true, kind, nativeType, target, Deny.BUILDER);
        if (f.tooFar) return new Offer(true, kind, nativeType, target, Deny.TOO_FAR);
        if (!f.anchorKnown) return new Offer(true, kind, nativeType, target, Deny.DATA);
        if (!f.anchorInside) return new Offer(true, kind, nativeType, target, Deny.OUTSIDE);
        if (!f.chunkLoaded) return new Offer(true, kind, nativeType, target, Deny.UNLOADED);
        if (!f.cornersKnown || !f.heightKnown) return new Offer(true, kind, nativeType, target, Deny.DATA);
        if (f.tooHigh) return new Offer(true, kind, nativeType, target, Deny.TOO_HIGH);
        if (f.tooLow) return new Offer(true, kind, nativeType, target, Deny.TOO_LOW);
        return new Offer(true, kind, nativeType, target, Deny.NONE);
    }

    /**
     * Re-checked on the server at click time. {@code callNative} is true only when this click may
     * invoke {@code requestUpgrade(player, ZERO)} — never for a second order, a stale level, or a failed gate.
     */
    public static Act commit(Facts now, int expectedLevel, Kind expectedKind, Gate gate) {
        if (gate == Gate.WRONG_DIMENSION) return stop(Deny.WRONG_DIMENSION);
        if (gate == Gate.FOREIGN) return stop(Deny.FOREIGN);
        if (gate == Gate.MISSING || gate == null) return stop(Deny.NO_COLONY);
        if (now == null || !now.placed) return stop(Deny.NOT_PLACED);
        if (now.orderAtHut) return stop(Deny.ALREADY_QUEUED);
        if (!now.manageHuts) return stop(Deny.PERMISSION);
        if (now.level != expectedLevel) return stop(Deny.STALE);
        Offer o = preview(now);
        if (!o.show) return stop(o.deny == Deny.NONE ? Deny.STALE : o.deny);
        if (o.kind != expectedKind) return stop(Deny.STALE);
        if (o.deny != Deny.NONE) return stop(o.deny);
        return new Act(Deny.NONE, true);
    }

    /** One bad work-order view must not drop the rest. Null results are skipped. Returns how many threw. */
    public static <T, R> int mapSkippingFaults(Iterable<T> items, java.util.function.Function<T, R> fn, java.util.List<R> out) {
        int bad = 0;
        if (items == null) return 0;
        for (T item : items) {
            try {
                R row = fn.apply(item);
                if (row != null) out.add(row);
            } catch (Throwable ignored) { bad++; }
        }
        return bad;
    }

    public static java.util.List<Long> uniquePositions(java.util.List<Long> positions) {
        java.util.List<Long> out = new java.util.ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        if (positions == null) return out;
        for (Long p : positions) if (p != null && seen.add(p)) out.add(p);
        return out;
    }

    private static int shownTarget(Facts f) {
        Native n = nativeKind(f);
        if (n == Native.BUILD) return 1;
        return f.level + 1;
    }

    /** Null when research, parent, max, or "not a hut" means requestUpgrade would not queue this building. */
    private static Native nativeKind(Facts f) {
        if (f == null || !f.placed || f.orderAtHut || f.mode == Mode.UNSUPPORTED || f.level >= f.maxLevel) return null;
        if (f.mode == Mode.BARRACKS_TOWER) {
            if (!f.parentPresent || f.level >= f.parentLevel) return null;
            return f.level <= 0 ? Native.BUILD : Native.UPGRADE;
        }
        if (structuralDeny(f) != Deny.NONE) return null;
        if (f.level <= 0 && (!f.parentPresent || f.parentLevel <= 0)) return Native.BUILD;
        return Native.UPGRADE;
    }

    private static Deny structuralDeny(Facts f) {
        if (f.mode == Mode.BARRACKS_TOWER) {
            if (!f.parentPresent || f.level >= f.maxLevel || f.level >= f.parentLevel) return Deny.NEED_BARRACKS;
            return Deny.NONE;
        }
        if (!f.researchKnown) return Deny.DATA;
        if (f.hasResearch && f.researchStrength < 1.0) return Deny.RESEARCH_LOCKED;
        if (f.hasResearch && f.researchStrength <= f.level) return Deny.RESEARCH_LEVEL;
        if (f.level <= 0 && (!f.parentPresent || f.parentLevel <= 0)) return Deny.NONE;
        if (f.level < f.maxLevel && (!f.parentPresent || f.level < f.parentLevel || f.parentLevel >= f.parentMax)) return Deny.NONE;
        if (f.level >= f.maxLevel) return Deny.MAX_LEVEL;
        return Deny.PARENT;
    }

    private static Offer hidden(Deny d) { return new Offer(false, null, null, 0, d); }
    private static Act stop(Deny d) { return new Act(d, false); }
}
