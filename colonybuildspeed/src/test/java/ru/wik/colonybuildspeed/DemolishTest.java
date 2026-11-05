package ru.wik.colonybuildspeed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static ru.wik.colonybuildspeed.Demolish.*;

class DemolishTest {
    @Test void defaultsStayAtOneCycleOfFourAndReachFive() {
        assertEquals(4, ConfigDefaults.BLOCKS);
        assertEquals(5.0, ConfigDefaults.REACH);
        assertEquals(10.0, ConfigDefaults.REACH_MAX);
        assertTrue(ConfigDefaults.ENABLED);
        assertTrue(ConfigDefaults.BUILDERS_ONLY);
        assertFalse(ConfigDefaults.DECORATE);
        assertTrue(ConfigDefaults.DEMOLISH);
        assertTrue(ConfigDefaults.REACH < ConfigDefaults.REACH_MAX);
    }

    @Test void ordinarySolidPassesAndHazardsStayNative() {
        assertEquals(Reason.OK, blockReason(ok()));
        assertEquals(Reason.DISABLED, blockReason(flag(v -> v.enabled = false)));
        assertEquals(Reason.DISABLED, blockReason(flag(v -> v.demolish = false)));
        assertEquals(Reason.NOT_BUILDER, blockReason(flag(v -> v.builder = false)));
        assertEquals(Reason.OK, blockReason(flag(v -> {
            v.builder = false;
            v.buildersOnly = false;
        })));
        assertEquals(Reason.WRONG_STAGE, blockReason(flag(v -> v.clearOrRemove = false)));
        assertEquals(Reason.NO_ORDER, blockReason(flag(v -> v.hasWorkOrder = false)));
        assertEquals(Reason.UNLOADED, blockReason(flag(v -> v.loaded = false)));
        assertEquals(Reason.AIR, blockReason(flag(v -> v.air = true)));
        assertEquals(Reason.FLUID, blockReason(flag(v -> v.fluid = true)));
        assertEquals(Reason.BLOCK_ENTITY, blockReason(flag(v -> v.blockEntity = true)));
        assertEquals(Reason.DOOR, blockReason(flag(v -> v.door = true)));
        assertEquals(Reason.BED, blockReason(flag(v -> v.bed = true)));
        assertEquals(Reason.CHEST, blockReason(flag(v -> v.chest = true)));
        assertEquals(Reason.DOUBLE, blockReason(flag(v -> v.doubleBlock = true)));
        assertEquals(Reason.DECORATION, blockReason(flag(v -> v.decoration = true)));
        assertEquals(Reason.STRUCTURIZE, blockReason(flag(v -> v.structurize = true)));
        assertEquals(Reason.UNDESTROYABLE, blockReason(flag(v -> v.undestroyable = true)));
        assertEquals(Reason.FEET, blockReason(flag(v -> v.feet = true)));
        assertEquals(Reason.REACH, blockReason(flag(v -> v.inReach = false)));
        assertEquals(Reason.FOREIGN_CLAIM, blockReason(flag(v -> v.foreignClaim = true)));
    }

    @Test void classifyBreakAdvanceAndWalk() {
        Pos a = new Pos(1, 2, 3);
        Pos b = new Pos(4, 2, 3);
        Pos p0 = new Pos(0, 0, 0);
        Pos p1 = new Pos(1, 0, 0);
        assertEquals(StepKind.BREAK, classify(Signal.MINE_OR_SAME, a, b, p0, p0));
        assertEquals(StepKind.ADVANCED, classify(Signal.MINE_OR_SAME, a, null, p0, p1));
        assertEquals(StepKind.WALK, classify(Signal.MINE_OR_SAME, a, a, p0, p0));
        assertEquals(StepKind.WALK, classify(Signal.MINE_OR_SAME, null, null, p0, p0));
        assertEquals(StepKind.INVENTORY, classify(Signal.INVENTORY, a, b, p0, p1));
        assertEquals(StepKind.IDLE, classify(Signal.IDLE, null, null, null, null));
        assertEquals(StepKind.NEEDS, classify(Signal.NEEDS, null, null, null, null));
        assertEquals(StepKind.COMPLETE, classify(Signal.COMPLETE, null, null, null, null));
        assertEquals(StepKind.OTHER, classify(Signal.OTHER, a, b, p0, p1));
    }

    @Test void unsatisfiedClockDoesNotTurnFourCallsIntoFourBreaks() {
        MiningClock clock = new MiningClock();
        int breaks = 0;
        for (int i = 0; i < 4; i++) {
            if (!clock.armOrWait(30)) {
                breaks++;
            }
        }
        assertEquals(2, breaks);
        assertEquals(2, clock.arms);
        assertEquals(2, clock.proceeds);
    }

    @Test void satisfiedExtrasBreakWithoutANewArm() {
        MiningClock clock = new MiningClock();
        assertTrue(clock.armOrWait(30));
        assertFalse(clock.armOrWait(30));
        for (int i = 0; i < 3; i++) {
            clock.satisfyOnce();
            assertFalse(clock.armOrWait(30));
            assertEquals(30, clock.delay);
        }
        assertEquals(1, clock.arms);
        assertEquals(4, clock.proceeds);
        assertFalse(clock.hasDelayed);
    }

    @Test void budgetOneDoesNotTouchTheWorld() {
        Yard yard = Yard.solids(6);
        yard.breakNativeFirst();
        Decision decision = continueAfterFirst(yard, 1);
        assertEquals(Reason.BUDGET, decision.reason);
        assertEquals(1, decision.mined);
        assertEquals(0, yard.steps);
        assertEquals(0, yard.mineCalls);
        assertEquals(1, yard.drops);
    }

    @Test void fourSolidsBreakOnceEachAndTwoRemain() {
        Yard yard = Yard.solids(6);
        yard.breakNativeFirst();
        Decision decision = continueAfterFirst(yard, 4);
        assertEquals(Reason.BUDGET, decision.reason);
        assertEquals(4, decision.mined);
        assertEquals(4, yard.drops);
        assertEquals(4, yard.xp);
        assertEquals(4, yard.wear);
        assertEquals(4, yard.broken.size());
        assertEquals(3, yard.mineCalls);
        assertEquals(3, yard.satisfyCalls);
        assertEquals(1, yard.clock.arms);
        assertEquals(4, yard.clock.proceeds);
        assertTrue(yard.steps <= stepCap(4));
        assertEquals(0, yard.depth);
        assertFalse(yard.clock.hasDelayed);
        assertFalse(yard.broken.contains(yard.cells.get(4).pos));
        assertFalse(yard.broken.contains(yard.cells.get(5).pos));
    }

    @Test void hazardStopsBeforeTheNativeMine() {
        Yard yard = Yard.solids(4);
        yard.cells.set(1, yard.cells.get(1).as(Reason.DOOR));
        yard.breakNativeFirst();
        Decision decision = continueAfterFirst(yard, 4);
        assertEquals(Reason.DOOR, decision.reason);
        assertEquals(1, decision.mined);
        assertEquals(0, yard.mineCalls);
        assertEquals(1, yard.drops);
        assertSame(decision.step.state, "mine");
    }

    @Test void missingToolDoesNotDuplicateAndResumeBreaksOnce() {
        Yard yard = Yard.solids(4);
        yard.failMineCall = 1;
        yard.breakNativeFirst();
        Decision decision = continueAfterFirst(yard, 4);
        assertEquals(Reason.TOOL, decision.reason);
        assertEquals(1, yard.drops);
        assertFalse(yard.clock.hasDelayed);
        assertEquals(0, yard.depth);
        yard.failMineCall = -1;
        yard.breakNativeCurrent();
        assertEquals(2, yard.drops);
        assertEquals(2, yard.xp);
        assertEquals(2, yard.wear);
        Decision rest = continueAfterFirst(yard, 4);
        assertEquals(3, rest.mined);
        assertEquals(4, yard.drops);
        assertEquals(4, yard.broken.size());
    }

    @Test void fullInventoryWalkingAndIdleStopTheBurst() {
        Yard full = Yard.solids(3);
        full.breakNativeFirst();
        full.inventoryFull = true;
        Decision inventory = continueAfterFirst(full, 4);
        assertEquals(Reason.INVENTORY, inventory.reason);
        assertEquals(0, full.mineCalls);

        Yard walk = Yard.solids(3);
        walk.breakNativeFirst();
        walk.walking = true;
        Decision walking = continueAfterFirst(walk, 4);
        assertEquals(Reason.WALK, walking.reason);
        assertEquals(1, walk.drops);

        Yard idle = Yard.solids(3);
        idle.breakNativeFirst();
        idle.order = false;
        Decision stopped = continueAfterFirst(idle, 4);
        assertEquals(Reason.IDLE, stopped.reason);
        assertEquals(1, idle.drops);
    }

    @Test void reachAndUnloadedChunkAreNotMinedInTheBurst() {
        Yard far = Yard.solids(3);
        far.cells.set(1, far.cells.get(1).as(Reason.REACH));
        far.breakNativeFirst();
        assertEquals(Reason.REACH, continueAfterFirst(far, 4).reason);
        assertEquals(0, far.mineCalls);

        Yard chunk = Yard.solids(3);
        chunk.cells.set(1, chunk.cells.get(1).as(Reason.UNLOADED));
        chunk.breakNativeFirst();
        assertEquals(Reason.UNLOADED, continueAfterFirst(chunk, 4).reason);
        assertEquals(1, chunk.drops);
    }

    @Test void nestedEntryDoesNotResetTheBudgetOrMineTwice() {
        Yard yard = Yard.solids(4);
        yard.breakNativeFirst();
        yard.reenter = true;
        Decision decision = continueAfterFirst(yard, 4);
        assertEquals(Reason.DEPTH, yard.nested);
        assertEquals(Reason.BUDGET, decision.reason);
        assertEquals(4, decision.mined);
        assertEquals(4, yard.drops);
        assertEquals(0, yard.depth);
    }

    @Test void committedFailureIsNotMinedAgain() {
        Yard yard = Yard.solids(3);
        yard.breakNativeFirst();
        yard.throwOnNext = true;
        yard.throwAfterCommit = true;
        Decision decision = continueAfterFirst(yard, 4);
        assertEquals(Reason.EXCEPTION, decision.reason);
        assertEquals(2, decision.mined);
        assertEquals(2, yard.drops);
        assertEquals(1, yard.mineCalls);
        assertFalse(yard.clock.hasDelayed);
        assertEquals(0, yard.depth);
    }

    @Test void uncommittedFailureLeavesTheBlockForOneNativeBreak() {
        Yard yard = Yard.solids(3);
        yard.breakNativeFirst();
        yard.throwOnNext = true;
        Decision decision = continueAfterFirst(yard, 4);
        assertEquals(Reason.EXCEPTION, decision.reason);
        assertEquals(1, decision.mined);
        assertEquals(1, yard.drops);
        assertFalse(yard.clock.hasDelayed);
        yard.throwOnNext = false;
        yard.breakNativeCurrent();
        assertEquals(2, yard.drops);
        assertEquals(2, yard.broken.size());
    }

    @Test void reportedSuccessThatLeavesTheBlockDoesNotCount() {
        Yard yard = Yard.solids(3);
        yard.breakNativeFirst();
        yard.lieAboutBreak = true;
        Decision decision = continueAfterFirst(yard, 4);
        assertEquals(Reason.STILL_THERE, decision.reason);
        assertEquals(1, decision.mined);
        assertEquals(1, yard.drops);
        assertFalse(yard.clock.hasDelayed);
    }

    @Test void airGapDoesNotBurnTheWholeCapBeforeTheNextSolid() {
        Yard yard = new Yard();
        yard.cells.add(Cell.solid(0));
        yard.cells.add(Cell.air(1));
        yard.cells.add(Cell.air(2));
        yard.cells.add(Cell.solid(3));
        yard.breakNativeFirst();
        Decision decision = continueAfterFirst(yard, 4);
        assertEquals(2, decision.mined);
        assertEquals(2, yard.drops);
        assertTrue(yard.broken.contains(yard.cells.get(3).pos));
        assertTrue(yard.steps <= stepCap(4));
    }

    @Test void spinningAdvanceHitsTheCap() {
        int[] steps = {0};
        int[] depth = {0};
        Host spin = new Host() {
            int progress = -1;

            public int enter() { return ++depth[0]; }
            public void exit() { depth[0] = Math.max(0, depth[0] - 1); }
            public Pos progress() { return new Pos(progress, 0, 0); }
            public Pos blockToMine() { return null; }
            public Step structureStep() {
                steps[0]++;
                progress++;
                return new Step(StepKind.ADVANCED, null, new Pos(progress, 0, 0), "air");
            }
            public boolean mineNative(Pos pos) { fail("no mine"); return false; }
            public void satisfyDelay() { fail("no delay"); }
            public void disarmDelay() {}
            public Reason inspect(Pos pos) { return Reason.OK; }
            public boolean gone(Pos pos) { return false; }
            public void afterEachBreak() {}
        };
        Decision decision = continueAfterFirst(spin, 4);
        assertEquals(Reason.NO_PROGRESS, decision.reason);
        assertEquals(1, decision.mined);
        assertEquals(stepCap(4), steps[0]);
        assertEquals(0, depth[0]);
    }

    private static BlockView ok() {
        return new BlockView();
    }

    private static BlockView flag(java.util.function.Consumer<BlockView> edit) {
        BlockView view = ok();
        edit.accept(view);
        return view;
    }

    private static final class Cell {
        final Pos pos;
        final boolean air;
        final Reason bad;

        private Cell(Pos pos, boolean air, Reason bad) {
            this.pos = pos;
            this.air = air;
            this.bad = bad;
        }

        static Cell solid(int i) {
            return new Cell(new Pos(i, 1, 0), false, null);
        }

        static Cell air(int i) {
            return new Cell(new Pos(i, 1, 0), true, null);
        }

        Cell as(Reason reason) {
            return new Cell(pos, air, reason);
        }
    }

    private static final class Yard implements Host {
        final MiningClock clock = new MiningClock();
        final List<Cell> cells = new ArrayList<>();
        final Set<Pos> broken = new HashSet<>();
        int progress = -1;
        Pos block;
        int depth;
        int drops;
        int xp;
        int wear;
        int mineCalls;
        int satisfyCalls;
        int steps;
        boolean inventoryFull;
        boolean order = true;
        boolean walking;
        boolean tool = true;
        int failMineCall = -1;
        boolean throwOnNext;
        boolean throwAfterCommit;
        boolean lieAboutBreak;
        boolean reenter;
        Reason nested;

        static Yard solids(int n) {
            Yard yard = new Yard();
            for (int i = 0; i < n; i++) {
                yard.cells.add(Cell.solid(i));
            }
            return yard;
        }

        void breakNativeFirst() {
            block = cells.get(0).pos;
            breakNativeCurrent();
        }

        void breakNativeCurrent() {
            assertTrue(clock.armOrWait(40));
            assertFalse(clock.armOrWait(40));
            commit(block);
        }

        private void commit(Pos pos) {
            if (!broken.add(pos)) {
                fail("duplicate drop at " + pos);
            }
            drops++;
            xp++;
            wear++;
        }

        @Override public int enter() { return ++depth; }

        @Override public void exit() {
            depth = Math.max(0, depth - 1);
            if (depth == 0) {
                clock.hasDelayed = false;
            }
        }

        @Override public Pos progress() { return progress < 0 ? null : new Pos(progress, 0, 0); }

        @Override public Pos blockToMine() { return block; }

        @Override public Step structureStep() {
            steps++;
            if (reenter) {
                reenter = false;
                nested = continueAfterFirst(this, 4).reason;
            }
            Pos beforeMine = block;
            Pos beforeProgress = progress();
            String state = "mine";
            if (inventoryFull) {
                state = "inventory";
            } else if (!order) {
                state = "idle";
            } else if (walking) {
                state = "mine";
            } else {
                int cursor = progress + 1;
                if (cursor >= cells.size()) {
                    state = "complete";
                } else {
                    Cell cell = cells.get(cursor);
                    if (cell.air || broken.contains(cell.pos)) {
                        progress = cursor;
                        block = null;
                        state = "mine";
                    } else {
                        block = cell.pos;
                        state = "mine";
                    }
                }
            }
            Signal signal = switch (state) {
                case "inventory" -> Signal.INVENTORY;
                case "idle" -> Signal.IDLE;
                case "complete" -> Signal.COMPLETE;
                default -> Signal.MINE_OR_SAME;
            };
            StepKind kind = classify(signal, beforeMine, block, beforeProgress, progress());
            return new Step(kind, block, progress(), state.equals("mine") ? "mine" : state);
        }

        @Override public boolean mineNative(Pos pos) {
            mineCalls++;
            if (throwOnNext) {
                throwOnNext = false;
                if (throwAfterCommit) {
                    commit(pos);
                    throw new IllegalStateException("after drop");
                }
                throw new IllegalStateException("before drop");
            }
            if (!tool || mineCalls == failMineCall) {
                return false;
            }
            if (lieAboutBreak) {
                return true;
            }
            if (clock.armOrWait(40)) {
                return false;
            }
            if (broken.contains(pos)) {
                return true;
            }
            commit(pos);
            return true;
        }

        @Override public void satisfyDelay() {
            satisfyCalls++;
            clock.satisfyOnce();
        }

        @Override public void disarmDelay() { clock.hasDelayed = false; }

        @Override public Reason inspect(Pos pos) {
            for (Cell cell : cells) {
                if (cell.pos.equals(pos)) {
                    return cell.bad == null ? Reason.OK : cell.bad;
                }
            }
            return Reason.UNSAFE;
        }

        @Override public boolean gone(Pos pos) { return broken.contains(pos); }

        @Override public void afterEachBreak() {}
    }
}
