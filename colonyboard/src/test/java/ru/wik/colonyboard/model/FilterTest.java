package ru.wik.colonyboard.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FilterTest {
    @Test void worstFactor() {
        assertEquals(1, Filter.worstFactor(new double[]{0.9, 0.2, 1.5}, new double[]{1, 1, 5}));
        assertEquals(0, Filter.worstFactor(new double[]{0.5, 0.9}, new double[]{4, 1}));
        assertEquals(-1, Filter.worstFactor(new double[]{1.0, 2.0}, new double[]{1, 1}));
    }
}
