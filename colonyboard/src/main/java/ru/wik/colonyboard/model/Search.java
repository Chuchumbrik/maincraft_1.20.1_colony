package ru.wik.colonyboard.model;

import java.util.Locale;

/** 1.7: case-insensitive "all words must match" search used by the J search boxes. */
public final class Search {
    private Search() {}

    public static boolean matches(String query, String... fields) {
        if (query == null || query.isBlank()) return true;
        StringBuilder sb = new StringBuilder();
        for (String f : fields) if (f != null) sb.append(f).append(' ');
        String hay = sb.toString().toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String w : query.toLowerCase(Locale.ROOT).replace('ё', 'е').trim().split("\\s+"))
            if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }
}
