package taskj1.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class Arguments {
    private final Map<String, String> values = new HashMap<>();

    public Arguments(String[] args, Set<String> valueOptions, Set<String> flagOptions) {
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if (values.containsKey(option)) {
                throw new IllegalArgumentException("Duplicate option: " + option);
            }
            if (flagOptions.contains(option)) {
                values.put(option, "true");
            } else if (valueOptions.contains(option)) {
                if (++i >= args.length || args[i].startsWith("--") || args[i].isEmpty()) {
                    throw new IllegalArgumentException("Missing value for " + option);
                }
                values.put(option, args[i]);
            } else {
                throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
    }

    public String required(String option) {
        String value = values.get(option);
        if (value == null) {
            throw new IllegalArgumentException("Required option: " + option);
        }
        return value;
    }

    public String value(String option, String fallback) {
        return values.getOrDefault(option, fallback);
    }

    public boolean flag(String option) {
        return values.containsKey(option);
    }

    public int integer(String option, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(value(option, Integer.toString(fallback)));
            if (value < minimum || value > maximum) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " must be in " + minimum + ".." + maximum);
        }
    }
}
