package io.github.tortaje.fitnesse.health;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;

public record ReadinessReport(GlobalStatus status, Instant timestamp, SortedMap<String, ComponentReport> components) {

    public int httpStatus() {
        return status == GlobalStatus.DOWN ? 503 : 200;
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> comps = new LinkedHashMap<>();
        components.forEach((name, report) -> comps.put(name, report.toJsonMap()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.name());
        m.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp));
        m.put("components", comps);
        return m;
    }
}
