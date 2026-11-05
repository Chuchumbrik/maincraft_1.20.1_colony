package ru.wik.colonyboard.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchTest {
    @Test void words() {
        assertTrue(Search.matches("", "anything"));
        assertTrue(Search.matches("  ", "anything"));
        assertTrue(Search.matches("дуб доск", "[Стройка] Дубовые доски ×64", "Хижина строителя 2"));
        assertTrue(Search.matches("ЕЛК", "Ёлка"));
        assertFalse(Search.matches("камень", "Дубовые доски"));
        assertTrue(Search.matches("строит", "x", "Хижина строителя"));
        assertFalse(Search.matches("доски кирка", "Дубовые доски"));
    }
}
