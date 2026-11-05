package ru.wik.colonyboard.logic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PackageALogicTest {
    @Test void protocolStaysOptionalForOldClients() {
        assertEquals("6", Protocol.MAIN);
        assertEquals("1", Protocol.GLASS);
        assertEquals("1", Protocol.AUTO);
        assertEquals("1", Protocol.DASH);
        assertEquals("1", Protocol.TIER);
    }

    @Test void explicitColonyIdNeverFallsBack() {
        assertEquals(ColonyTarget.Kind.EXPLICIT, ColonyTarget.resolve(true, true, true, true, true));
        assertEquals(ColonyTarget.Kind.REJECT, ColonyTarget.resolve(true, false, false, true, true));
        assertEquals(ColonyTarget.Kind.REJECT, ColonyTarget.resolve(true, true, false, true, true));
        assertEquals(ColonyTarget.Kind.OWNED, ColonyTarget.resolve(false, false, false, true, true));
        assertEquals(ColonyTarget.Kind.REJECT, ColonyTarget.resolve(false, false, false, false, false));
        assertEquals(ColonyTarget.Kind.REJECT, ColonyTarget.resolve(false, false, false, true, false));
    }

    @Test void dimensionBindDoesNotGuess() {
        assertEquals(DimensionColony.Status.BOUND, DimensionColony.bind("minecraft:the_nether", List.of("minecraft:overworld")).status());
        assertEquals("minecraft:the_nether", DimensionColony.bind("minecraft:the_nether", List.of("minecraft:overworld")).dimension());
        assertEquals("minecraft:overworld", DimensionColony.bind("", List.of("minecraft:overworld")).dimension());
        assertEquals(DimensionColony.Status.AMBIGUOUS, DimensionColony.bind(null, List.of("minecraft:overworld", "minecraft:the_nether")).status());
        assertEquals(DimensionColony.Status.UNKNOWN, DimensionColony.bind("", List.of()).status());
        assertFalse(DimensionColony.confirmedGone(false, false));
        assertTrue(DimensionColony.confirmedGone(true, false));
        assertFalse(DimensionColony.confirmedGone(true, true));
        assertEquals("minecraft:the_nether#3", DimensionColony.key("minecraft:the_nether", 3));
    }

    @Test void toolFloorGatesAndCap() {
        assertEquals(ToolFloor.OFF, ToolFloor.clamp(ToolFloor.OFF, 5, 3));
        assertEquals(ToolFloor.STONE, ToolFloor.clamp(ToolFloor.DIAMOND, 0, 3));
        assertEquals(ToolFloor.STONE, ToolFloor.clamp(ToolFloor.IRON, 1, 3));
        assertEquals(ToolFloor.IRON, ToolFloor.clamp(ToolFloor.DIAMOND, 2, 3));
        assertEquals(ToolFloor.DIAMOND, ToolFloor.clamp(ToolFloor.DIAMOND, 3, 3));
        assertEquals(ToolFloor.STONE, ToolFloor.clamp(ToolFloor.DIAMOND, 5, 1));
        assertEquals(ToolFloor.OFF, ToolFloor.clamp(ToolFloor.IRON, 5, 0));
        assertEquals(ToolFloor.DIAMOND, ToolFloor.clamp(ToolFloor.DIAMOND, 5, Integer.MAX_VALUE));
    }

    @Test void toolFloorOnlyRaisesNewDigRequests() {
        assertEquals(0, ToolFloor.raise(true, ToolFloor.MODE_COLONY, ToolFloor.OFF, ToolFloor.DIAMOND, 0, 5, 3));
        assertEquals(2, ToolFloor.raise(true, ToolFloor.MODE_COLONY, ToolFloor.IRON, null, 0, 2, 3));
        assertEquals(2, ToolFloor.raise(true, ToolFloor.MODE_COLONY, ToolFloor.DIAMOND, null, 0, 2, 3));
        assertEquals(1, ToolFloor.raise(true, ToolFloor.MODE_HUT, ToolFloor.DIAMOND, ToolFloor.STONE, 0, 5, 3));
        assertEquals(0, ToolFloor.raise(true, ToolFloor.MODE_HUT, ToolFloor.DIAMOND, null, 0, 5, 3));
        assertEquals(4, ToolFloor.raise(false, ToolFloor.MODE_COLONY, ToolFloor.DIAMOND, null, 4, 5, 3));
        assertEquals(3, ToolFloor.raise(true, ToolFloor.MODE_COLONY, ToolFloor.STONE, null, 3, 5, 3));
        assertEquals(2, ToolFloor.capToRequestMax(3, 2));
    }

    @Test void removedHutFloorsAreDropped() {
        Map<Long, Integer> stored = Map.of(1L, ToolFloor.IRON, 2L, ToolFloor.DIAMOND, 3L, ToolFloor.OFF);
        Map<Long, Integer> left = ToolFloor.purge(stored, Set.of(1L, 3L));
        assertEquals(Map.of(1L, ToolFloor.IRON), left);
        assertEquals(ToolFloor.MODE_COLONY, ToolFloor.sanitizeMode(9));
        assertEquals(ToolFloor.DIAMOND, ToolFloor.sanitizeFloor(99));
    }

    @Test void payBeforeItemsAndBidRefund() {
        assertFalse(PayGuard.deliverAfterCharge(false, true, true));
        assertFalse(PayGuard.deliverAfterCharge(true, false, true));
        assertFalse(PayGuard.deliverAfterCharge(true, true, false));
        assertTrue(PayGuard.deliverAfterCharge(true, true, true));
        assertEquals(40, PayGuard.refundOf(100, 60));
        assertEquals(0, PayGuard.refundOf(100, 100));
        assertEquals(0, PayGuard.refundOf(0, 0));
        assertFalse(PayGuard.commitBid(true, false));
        assertTrue(PayGuard.commitBid(true, true));
        assertTrue(PayGuard.commitBid(false, false));
        assertEquals(25, PayGuard.shortfall(100, 50, 25));
        assertEquals(0, PayGuard.shortfall(100, 100, 0));
        assertFalse(PayGuard.fitsTreasuryInt(Integer.MAX_VALUE + 1L));
        assertTrue(PayGuard.fitsTreasuryInt(Integer.MAX_VALUE));
    }
}
