package io.github.tortaje.fitnesse.health.jmx;

import java.util.LinkedHashMap;
import java.util.Map;

public class SwitchableCheck implements SwitchableCheckMBean {
    public volatile String status = "UP";
    public volatile Integer tools;

    @Override public Map<String, Object> check() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        if (tools != null) {
            m.put("tools", tools);
        }
        return m;
    }

    @Override public boolean isCritical() { return true; }
}
