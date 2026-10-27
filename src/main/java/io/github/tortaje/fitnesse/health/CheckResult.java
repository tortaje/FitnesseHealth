package io.github.tortaje.fitnesse.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record CheckResult(ComponentStatus status, String message, Map<String, Object> details) {

    public CheckResult {
        details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static CheckResult up() {
        return new CheckResult(ComponentStatus.UP, null, null);
    }

    public static CheckResult down(String message) {
        return new CheckResult(ComponentStatus.DOWN, message, null);
    }

    /** DOWN result described as "SimpleClassName: message" (no suffix when the message is null). */
    public static CheckResult fromException(Throwable t) {
        String name = t.getClass().getSimpleName();
        return down(t.getMessage() == null ? name : name + ": " + t.getMessage());
    }
}
