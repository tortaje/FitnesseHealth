package io.github.tortaje.fitnesse.health.jmx;

import java.util.Map;

public interface StringCriticalCheckMBean {
    Map<String, Object> check();

    String getCritical();
}
