package io.github.tortaje.fitnesse.health.jmx;

import java.util.Map;

public interface GoodCheckMBean {
    Map<String, Object> check();

    boolean isCritical();
}
