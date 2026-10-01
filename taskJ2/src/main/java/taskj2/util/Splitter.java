package taskj2.util;

import java.util.ArrayList;
import java.util.List;

public final class Splitter {

    private Splitter() {
    }

    public static List<String> split80(String s) {
        List<String> parts = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return parts;
        }
        for (int i = 0; i < s.length(); i += 80) {
            parts.add(s.substring(i, Math.min(i + 80, s.length())));
        }
        return parts;
    }
}
