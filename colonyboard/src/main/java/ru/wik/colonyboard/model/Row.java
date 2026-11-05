package ru.wik.colonyboard.model;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * One line of the board. requestId != null -> row can be fulfilled.
 * 1.7: jump >= 0 -> clicking the row opens that J tab (dashboard); ref = work order id / action index (-1 = none).
 */
public record Row(Component title, Component detail, BlockPos building, boolean blocking,
                  int sortKey, int citizenEntityId, BlockPos citizenPos, UUID requestId, int jump, int ref) {
    public Row(Component title, Component detail, BlockPos building, boolean blocking, int sortKey, int citizenEntityId, BlockPos citizenPos, UUID requestId) {
        this(title, detail, building, blocking, sortKey, citizenEntityId, citizenPos, requestId, -1, -1);
    }
    public Row(Component title, Component detail, BlockPos building, boolean blocking, int sortKey, int citizenEntityId, BlockPos citizenPos) {
        this(title, detail, building, blocking, sortKey, citizenEntityId, citizenPos, null, -1, -1);
    }
    public boolean canHighlight() { return citizenEntityId >= 0 || citizenPos != null; }
}
