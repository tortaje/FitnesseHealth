package io.github.tortaje.fitnesse.health;

import java.util.LinkedHashMap;
import java.util.Map;

public record ComponentReport(String name, ComponentStatus status, boolean critical, long durationMs,
                              boolean cached, String message, Map<String, Object> details) {

    public Map<String, Object> toJsonMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.name());
        m.put("critical", critical);
        m.put("durationMs", durationMs);
        if (cached) {
            m.put("cached", true);
        }
        if (message != null && !message.isEmpty()) {
            m.put("message", message);
        }
        if (details != null && !details.isEmpty()) {
            m.put("details", details);
        }
        return m;
    }
}
