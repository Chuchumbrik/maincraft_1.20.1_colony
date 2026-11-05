package ru.wik.colonyboard.logic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static ru.wik.colonyboard.logic.BuildActions.*;

class BuildActionsTest {
    @Test void placedUnstartedIsSeparateFromTheQueue() {
        Facts placed = Facts.placed(0, 5);
        Offer start = preview(placed);
        assertTrue(start.show());
        assertEquals(Kind.START, start.kind());
        assertEquals(Native.BUILD, start.nativeType());
        assertEquals(1, start.target());
        assertEquals(Deny.NONE, start.deny());

        Facts queued = Facts.placed(0, 5);
        queued.orderAtHut = true;
        Offer hidden = preview(queued);
        assertFalse(hidden.show());
        assertEquals(Deny.ALREADY_QUEUED, hidden.deny());

        Facts previewOnly = Facts.placed(0, 5);
        previewOnly.placed = false;
        assertFalse(preview(previewOnly).show());
        assertEquals(Deny.NOT_PLACED, preview(previewOnly).deny());
    }

    @Test void upgradeStaysUpgradeAndMaxIsNotOffered() {
        Facts up = Facts.placed(2, 5);
        Offer o = preview(up);
        assertTrue(o.show());
        assertEquals(Kind.UPGRADE, o.kind());
        assertEquals(Native.UPGRADE, o.nativeType());
        assertEquals(3, o.target());
        assertEquals(Deny.NONE, o.deny());

        Facts max = Facts.placed(5, 5);
        assertFalse(preview(max).show());
        assertEquals(Deny.MAX_LEVEL, preview(max).deny());

        Facts existingUpgrade = Facts.placed(2, 5);
        existingUpgrade.orderAtHut = true;
        assertFalse(preview(existingUpgrade).show());
        assertEquals(Deny.ALREADY_QUEUED, preview(existingUpgrade).deny());
    }

    @Test void researchParentAndBuilderBlockWithoutCallingNative() {
        Facts locked = Facts.placed(0, 5);
        locked.hasResearch = true;
        locked.researchStrength = 0;
        assertEquals(Deny.RESEARCH_LOCKED, preview(locked).deny());
        assertFalse(commit(locked, 0, Kind.START, Gate.OK).callNative());

        Facts needMore = Facts.placed(2, 5);
        needMore.hasResearch = true;
        needMore.researchStrength = 2;
        assertEquals(Deny.RESEARCH_LEVEL, preview(needMore).deny());

        Facts open = Facts.placed(2, 5);
        open.hasResearch = true;
        open.researchStrength = 3;
        assertEquals(Deny.NONE, preview(open).deny());

        Facts parent = Facts.placed(2, 5);
        parent.parentPresent = true;
        parent.parentLevel = 2;
        parent.parentMax = 5;
        assertEquals(Deny.PARENT, preview(parent).deny());

        Facts parentAllows = Facts.placed(2, 5);
        parentAllows.parentPresent = true;
        parentAllows.parentLevel = 3;
        parentAllows.parentMax = 5;
        assertEquals(Deny.NONE, preview(parentAllows).deny());

        Facts noBuilder = Facts.placed(0, 5);
        noBuilder.canBeBuiltByBuilder = false;
        noBuilder.canBeResolved = false;
        assertEquals(Deny.BUILDER, preview(noBuilder).deny());

        Facts self = Facts.placed(0, 5);
        self.canBeBuiltByBuilder = true;
        self.canBeResolved = false;
        assertEquals(Deny.NONE, preview(self).deny());
        assertEquals(Native.BUILD, preview(self).nativeType());
    }

    @Test void levelZeroChildOfBuiltParentIsStillAStartButNativeUpgrade() {
        Facts child = Facts.placed(0, 5);
        child.parentPresent = true;
        child.parentLevel = 2;
        child.parentMax = 5;
        Offer o = preview(child);
        assertEquals(Kind.START, o.kind());
        assertEquals(Native.UPGRADE, o.nativeType());
        assertEquals(1, o.target());
        assertEquals(Deny.NONE, o.deny());
    }

    @Test void barracksTowerUsesItsOwnRuleAndIgnoresResearch() {
        Facts tower = Facts.placed(0, 5);
        tower.mode = Mode.BARRACKS_TOWER;
        tower.parentPresent = true;
        tower.parentLevel = 1;
        tower.hasResearch = true;
        tower.researchStrength = 0;
        Offer o = preview(tower);
        assertEquals(Deny.NONE, o.deny());
        assertEquals(Native.BUILD, o.nativeType());

        Facts blocked = Facts.placed(1, 5);
        blocked.mode = Mode.BARRACKS_TOWER;
        blocked.parentPresent = true;
        blocked.parentLevel = 1;
        assertEquals(Deny.NEED_BARRACKS, preview(blocked).deny());
        assertFalse(commit(blocked, 1, Kind.UPGRADE, Gate.OK).callNative());
    }

    @Test void unsupportedFailsClosed() {
        Facts odd = Facts.placed(0, 5);
        odd.mode = Mode.UNSUPPORTED;
        Offer o = preview(odd);
        assertTrue(o.show());
        assertEquals(Deny.UNSUPPORTED, o.deny());
        assertNull(o.nativeType());
        assertFalse(commit(odd, 0, Kind.START, Gate.OK).callNative());
    }

    @Test void unloadedAndMissingDataAreExplained() {
        Facts far = Facts.placed(0, 5);
        far.chunkLoaded = false;
        assertEquals(Deny.UNLOADED, preview(far).deny());
        assertFalse(commit(far, 0, Kind.START, Gate.OK).callNative());

        Facts outside = Facts.placed(0, 5);
        outside.anchorInside = false;
        assertEquals(Deny.OUTSIDE, preview(outside).deny());

        Facts high = Facts.placed(0, 5);
        high.tooHigh = true;
        assertEquals(Deny.TOO_HIGH, preview(high).deny());

        Facts unknown = Facts.placed(0, 5);
        unknown.checksKnown = false;
        assertEquals(Deny.DATA, preview(unknown).deny());
    }

    @Test void dimensionColonyAndPermissionDoNotFallThrough() {
        assertEquals(Gate.OK, gate(true, true, true));
        assertEquals(Gate.FOREIGN, gate(true, false, true));
        assertEquals(Gate.WRONG_DIMENSION, gate(false, false, true));
        assertEquals(Gate.MISSING, gate(false, false, false));
        assertFalse(sameColony("minecraft:overworld", 3, "minecraft:the_nether", 3));
        assertTrue(sameColony("minecraft:the_nether", 3, "minecraft:the_nether", 3));
        assertFalse(sameColony("", 3, "", 3));

        Facts ok = Facts.placed(0, 5);
        assertEquals(Deny.WRONG_DIMENSION, commit(ok, 0, Kind.START, Gate.WRONG_DIMENSION).deny());
        assertFalse(commit(ok, 0, Kind.START, Gate.WRONG_DIMENSION).callNative());
        assertEquals(Deny.FOREIGN, commit(ok, 0, Kind.START, Gate.FOREIGN).deny());
        assertEquals(Deny.NO_COLONY, commit(ok, 0, Kind.START, Gate.MISSING).deny());

        Facts noRight = Facts.placed(2, 5);
        noRight.manageHuts = false;
        assertEquals(Deny.PERMISSION, preview(noRight).deny());
        assertFalse(commit(noRight, 2, Kind.UPGRADE, Gate.OK).callNative());
    }

    @Test void staleAndSecondClickDoNotQueueAnotherOrder() {
        Facts built = Facts.placed(1, 5);
        Act stale = commit(built, 0, Kind.START, Gate.OK);
        assertEquals(Deny.STALE, stale.deny());
        assertFalse(stale.callNative());

        Facts again = Facts.placed(0, 5);
        again.orderAtHut = true;
        Act second = commit(again, 0, Kind.START, Gate.OK);
        assertEquals(Deny.ALREADY_QUEUED, second.deny());
        assertFalse(second.callNative());

        Act wrongKind = commit(Facts.placed(0, 5), 0, Kind.UPGRADE, Gate.OK);
        assertEquals(Deny.STALE, wrongKind.deny());
        assertFalse(wrongKind.callNative());

        Act go = commit(Facts.placed(0, 5), 0, Kind.START, Gate.OK);
        assertEquals(Deny.NONE, go.deny());
        assertTrue(go.callNative());

        Act up = commit(Facts.placed(2, 3), 2, Kind.UPGRADE, Gate.OK);
        assertTrue(up.callNative());

        Act maxed = commit(Facts.placed(5, 5), 5, Kind.UPGRADE, Gate.OK);
        assertEquals(Deny.MAX_LEVEL, maxed.deny());
        assertFalse(maxed.callNative());
    }

    @Test void oneFaultDoesNotDropTheOtherQueueRows() {
        List<String> rows = new ArrayList<>();
        List<String> in = List.of("townhall", "boom", "upgrade");
        int bad = mapSkippingFaults(in, s -> {
            if ("boom".equals(s)) throw new IllegalStateException("bad view");
            return s;
        }, rows);
        assertEquals(1, bad);
        assertEquals(List.of("townhall", "upgrade"), rows);
    }

    @Test void duplicatePositionsCollapseAndLabelsAreNotCut() {
        assertEquals(List.of(1L, 2L), uniquePositions(java.util.Arrays.asList(1L, 1L, 2L, null, 2L)));
        String phrase = "Размещено — не начато";
        List<String> lines = TextWrap.wrap(phrase, 8, String::length);
        assertTrue(lines.size() > 1);
        assertEquals(phrase.replace(" ", ""), String.join("", lines).replace(" ", ""));
        List<List<Integer>> packed = TextWrap.pack(new int[] {40, 40, 80}, 4, 90);
        assertEquals(List.of(List.of(0, 1), List.of(2)), packed);
        assertEquals(List.of(List.of(0)), TextWrap.pack(new int[] {200}, 4, 90));
        assertFalse(lang(Deny.NONE).length() > 0);
        for (Deny d : Deny.values()) if (d != Deny.NONE) assertFalse(lang(d).isBlank(), d.name());
    }
}
