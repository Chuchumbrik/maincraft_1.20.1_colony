package ru.wik.colonyboard.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Wrap and pack labels by measured width so a verb is not cut off mid-word. */
public final class TextWrap {
    private TextWrap() {}

    public static List<String> wrap(String text, int maxPx, ToIntFunction<String> width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        int max = Math.max(1, maxPx);
        for (String para : text.split("\n", -1)) {
            if (para.isEmpty()) { lines.add(""); continue; }
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ", -1)) {
                if (word.isEmpty()) continue;
                if (width.applyAsInt(word) > max) {
                    if (cur.length() > 0) { lines.add(cur.toString()); cur.setLength(0); }
                    String rest = word;
                    while (width.applyAsInt(rest) > max && rest.length() > 1) {
                        int cut = fit(rest, max, width);
                        lines.add(rest.substring(0, Math.max(1, cut)));
                        rest = rest.substring(Math.max(1, cut));
                    }
                    cur.append(rest);
                    continue;
                }
                if (cur.length() == 0) cur.append(word);
                else if (width.applyAsInt(cur + " " + word) <= max) cur.append(' ').append(word);
                else { lines.add(cur.toString()); cur.setLength(0); cur.append(word); }
            }
            if (cur.length() > 0) lines.add(cur.toString());
        }
        return lines;
    }

    /** Pack button widths into rows. A button wider than the row stays whole on its own row. */
    public static List<List<Integer>> pack(int[] widths, int gap, int maxWidth) {
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        int used = 0;
        int gapPx = Math.max(0, gap);
        int max = Math.max(1, maxWidth);
        if (widths == null) return rows;
        for (int i = 0; i < widths.length; i++) {
            int w = Math.max(1, widths[i]);
            int need = cur.isEmpty() ? w : used + gapPx + w;
            if (!cur.isEmpty() && need > max) {
                rows.add(cur);
                cur = new ArrayList<>();
                used = 0;
            }
            cur.add(i);
            used = cur.size() == 1 ? w : used + gapPx + w;
        }
        if (!cur.isEmpty()) rows.add(cur);
        return rows;
    }

    private static int fit(String word, int max, ToIntFunction<String> width) {
        int lo = 1, hi = word.length() - 1, best = 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (width.applyAsInt(word.substring(0, mid)) <= max) { best = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return best;
    }
}
