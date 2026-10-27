package io.github.tortaje.fitnesse.health.jmx;

import java.util.Map;

public class NoCriticalCheck implements NoCriticalCheckMBean {
    @Override public Map<String, Object> check() { return Map.of("status", "UP"); }
}
