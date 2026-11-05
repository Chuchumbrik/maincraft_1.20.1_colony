package ru.wik.colonyboard.logic;

/**
 * Which colony a player packet may touch.
 * An explicit colony id (the J screen always sends one) is accepted only when that id exists
 * in the player's current dimension and the player is a member. It never falls through to a
 * different colony the player happens to own. Id &lt; 0 means "the colony I own here".
 */
public final class ColonyTarget {
    public enum Kind { EXPLICIT, OWNED, REJECT }

    private ColonyTarget() {}

    public static Kind resolve(boolean explicitId, boolean foundInPlayerDimension, boolean memberOfFound,
                               boolean ownedInPlayerDimension, boolean memberOfOwned) {
        if (explicitId) return foundInPlayerDimension && memberOfFound ? Kind.EXPLICIT : Kind.REJECT;
        return ownedInPlayerDimension && memberOfOwned ? Kind.OWNED : Kind.REJECT;
    }
}
