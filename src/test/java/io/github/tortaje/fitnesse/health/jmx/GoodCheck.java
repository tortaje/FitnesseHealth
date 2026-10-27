package io.github.tortaje.fitnesse.health.jmx;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class GoodCheck implements GoodCheckMBean {
    private final Supplier<Map<String, Object>> result;

    public GoodCheck() {
        this(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "UP");
            m.put("message", "ok");
            m.put("tools", 12);
            return m;
        });
    }

    public GoodCheck(Supplier<Map<String, Object>> result) {
        this.result = result;
    }

    @Override public Map<String, Object> check() { return result.get(); }

    @Override public boolean isCritical() { return false; }
}
