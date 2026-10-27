package io.github.tortaje.fitnesse.health.jmx;

import java.util.Map;

public class StringCriticalCheck implements StringCriticalCheckMBean {
    @Override public Map<String, Object> check() { return Map.of("status", "UP"); }

    @Override public String getCritical() { return "yes"; }
}
