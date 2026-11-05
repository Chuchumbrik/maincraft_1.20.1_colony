package ru.wik.colonybuildspeed;

import java.util.Objects;

/**
 * One logical builder cycle mines at most {@code blocksPerCycle} ordinary solids.
 *
 * <p>MineColonies arms the wait in {@code hasNotDelayed}: the first call sets {@code delay} and
 * returns without breaking; after {@code waitingForSomething} counts that delay down, the next
 * call breaks once via {@code mineBlock} (drops, tool wear, XP). Calling {@code mineBlock} again
 * in that same visit would only arm a new wait. This cycle therefore lets the first eligible
 * solid pay the native wait, then marks {@code hasDelayed} already satisfied for each further
 * eligible solid so that one native {@code mineBlock} call actually breaks it. The cap is a
 * maximum. A tool miss, full inventory, walk, foreign claim, unloaded chunk, or unsafe block
 * stops the burst and leaves that block for the vanilla one-block path. Already broken blocks
 * are not mined again.
 */
public final class Demolish {
    private Demolish() {
    }

    public enum Reason {
        OK,
        DISABLED,
        NOT_BUILDER,
        WRONG_STAGE,
        NO_ORDER,
        UNLOADED,
        AIR,
        FLUID,
        BLOCK_ENTITY,
        DOOR,
        BED,
        CHEST,
        DOUBLE,
        DECORATION,
        STRUCTURIZE,
        UNDESTROYABLE,
        FEET,
        REACH,
        FOREIGN_CLAIM,
        BUDGET,
        WALK,
        INVENTORY,
        IDLE,
        NEEDS,
        COMPLETE,
        OTHER,
        TOOL,
        STILL_THERE,
        NO_PROGRESS,
        DEPTH,
        EXCEPTION,
        UNSAFE
    }

    public enum Signal {
        MINE_OR_SAME,
        INVENTORY,
        IDLE,
        NEEDS,
        COMPLETE,
        OTHER
    }

    public enum StepKind {
        BREAK,
        ADVANCED,
        WALK,
        INVENTORY,
        IDLE,
        NEEDS,
        COMPLETE,
        OTHER
    }

    public record Pos(int x, int y, int z) {
    }

    public static final class Step {
        public final StepKind kind;
        public final Pos block;
        public final Pos progress;
        public final Object state;

        public Step(StepKind kind, Pos block, Pos progress, Object state) {
            this.kind = kind;
            this.block = block;
            this.progress = progress;
            this.state = state;
        }
    }

    public static final class Decision {
        public final int mined;
        public final Reason reason;
        public final Step step;

        public Decision(int mined, Reason reason, Step step) {
            this.mined = mined;
            this.reason = reason;
            this.step = step;
        }
    }

    /** Facts gathered by the mixin. Tests set them directly. */
    public static final class BlockView {
        public boolean enabled = true;
        public boolean demolish = true;
        public boolean buildersOnly = true;
        public boolean builder = true;
        public boolean clearOrRemove = true;
        public boolean hasWorkOrder = true;
        public boolean loaded = true;
        public boolean air;
        public boolean fluid;
        public boolean blockEntity;
        public boolean door;
        public boolean bed;
        public boolean chest;
        public boolean doubleBlock;
        public boolean decoration;
        public boolean structurize;
        public boolean undestroyable;
        public boolean feet;
        public boolean inReach = true;
        public boolean foreignClaim;
    }

    public interface Host {
        int enter();

        void exit();

        Pos progress();

        Pos blockToMine();

        Step structureStep();

        boolean mineNative(Pos pos);

        void satisfyDelay();

        void disarmDelay();

        Reason inspect(Pos pos);

        boolean gone(Pos pos);

        void afterEachBreak();
    }

    /** Mirrors {@code AbstractEntityAIBasic.hasNotDelayed} from MineColonies 1.1.1237. */
    public static final class MiningClock {
        public boolean hasDelayed;
        public int delay;
        public int arms;
        public int proceeds;

        /** @return true when the caller must wait and must not break on this call */
        public boolean armOrWait(int time) {
            if (!hasDelayed) {
                delay = time;
                hasDelayed = true;
                arms++;
                return true;
            }
            hasDelayed = false;
            proceeds++;
            return false;
        }

        /** Next {@link #armOrWait} takes the proceed branch and does not arm a new wait. */
        public void satisfyOnce() {
            hasDelayed = true;
        }
    }

    public static int stepCap(int budget) {
        return budget * 3 + 2;
    }

    public static Reason blockReason(BlockView v) {
        if (v == null || !v.enabled || !v.demolish) {
            return Reason.DISABLED;
        }
        if (v.buildersOnly && !v.builder) {
            return Reason.NOT_BUILDER;
        }
        if (!v.clearOrRemove) {
            return Reason.WRONG_STAGE;
        }
        if (!v.hasWorkOrder) {
            return Reason.NO_ORDER;
        }
        if (!v.loaded) {
            return Reason.UNLOADED;
        }
        if (v.air) {
            return Reason.AIR;
        }
        if (v.fluid) {
            return Reason.FLUID;
        }
        if (v.blockEntity) {
            return Reason.BLOCK_ENTITY;
        }
        if (v.door) {
            return Reason.DOOR;
        }
        if (v.bed) {
            return Reason.BED;
        }
        if (v.chest) {
            return Reason.CHEST;
        }
        if (v.doubleBlock) {
            return Reason.DOUBLE;
        }
        if (v.decoration) {
            return Reason.DECORATION;
        }
        if (v.structurize) {
            return Reason.STRUCTURIZE;
        }
        if (v.undestroyable) {
            return Reason.UNDESTROYABLE;
        }
        if (v.feet) {
            return Reason.FEET;
        }
        if (!v.inReach) {
            return Reason.REACH;
        }
        if (v.foreignClaim) {
            return Reason.FOREIGN_CLAIM;
        }
        return Reason.OK;
    }

    public static StepKind classify(Signal signal, Pos beforeMine, Pos afterMine, Pos beforeProgress, Pos afterProgress) {
        return switch (signal) {
            case INVENTORY -> StepKind.INVENTORY;
            case IDLE -> StepKind.IDLE;
            case NEEDS -> StepKind.NEEDS;
            case COMPLETE -> StepKind.COMPLETE;
            case OTHER -> StepKind.OTHER;
            case MINE_OR_SAME -> {
                boolean progressMoved = !Objects.equals(beforeProgress, afterProgress);
                boolean mineMoved = afterMine != null && !afterMine.equals(beforeMine);
                if (mineMoved) {
                    yield StepKind.BREAK;
                }
                if (afterMine == null && progressMoved) {
                    yield StepKind.ADVANCED;
                }
                yield StepKind.WALK;
            }
        };
    }

    /**
     * Called after the first eligible solid of this visit has already been broken by native
     * {@code mineBlock} (the wait for that block has elapsed). Mines further solids up to {@code budget}.
     */
    public static Decision continueAfterFirst(Host host, int budget) {
        if (budget <= 1) {
            return new Decision(1, Reason.BUDGET, null);
        }
        int depth = host.enter();
        try {
            if (depth != 1) {
                return new Decision(1, Reason.DEPTH, null);
            }
            int mined = 1;
            int steps = 0;
            int cap = stepCap(budget);
            Pos progress = host.progress();
            Pos mine = host.blockToMine();
            while (mined < budget) {
                if (++steps > cap) {
                    return new Decision(mined, Reason.NO_PROGRESS, null);
                }
                Pos beforeProgress = progress;
                Pos beforeMine = mine;
                Step step = host.structureStep();
                progress = step.progress;
                mine = step.block;
                switch (step.kind) {
                    case INVENTORY -> {
                        return new Decision(mined, Reason.INVENTORY, step);
                    }
                    case IDLE -> {
                        return new Decision(mined, Reason.IDLE, step);
                    }
                    case NEEDS -> {
                        return new Decision(mined, Reason.NEEDS, step);
                    }
                    case COMPLETE -> {
                        return new Decision(mined, Reason.COMPLETE, step);
                    }
                    case OTHER -> {
                        return new Decision(mined, Reason.OTHER, step);
                    }
                    case WALK -> {
                        return new Decision(mined, Reason.WALK, step);
                    }
                    case ADVANCED -> {
                        if (Objects.equals(beforeProgress, progress) && Objects.equals(beforeMine, mine)) {
                            return new Decision(mined, Reason.NO_PROGRESS, step);
                        }
                    }
                    case BREAK -> {
                        if (mine == null || Objects.equals(beforeMine, mine) && Objects.equals(beforeProgress, progress)) {
                            return new Decision(mined, Reason.WALK, step);
                        }
                        Reason why = host.inspect(mine);
                        if (why != Reason.OK) {
                            return new Decision(mined, why, step);
                        }
                        boolean broke = false;
                        try {
                            host.satisfyDelay();
                            broke = host.mineNative(mine);
                            if (broke && !host.gone(mine)) {
                                broke = false;
                                return new Decision(mined, Reason.STILL_THERE, step);
                            }
                        } catch (Throwable t) {
                            host.disarmDelay();
                            if (host.gone(mine)) {
                                mined++;
                            }
                            return new Decision(mined, Reason.EXCEPTION, null);
                        } finally {
                            if (!broke) {
                                host.disarmDelay();
                            }
                        }
                        if (!broke) {
                            return new Decision(mined, Reason.TOOL, step);
                        }
                        mined++;
                        host.afterEachBreak();
                    }
                    default -> {
                        return new Decision(mined, Reason.OTHER, step);
                    }
                }
            }
            return new Decision(mined, Reason.BUDGET, null);
        } finally {
            host.exit();
        }
    }
}
