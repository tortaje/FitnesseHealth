package io.github.tortaje.fitnesse.health.jmx;

import java.util.Map;

public interface SwitchableCheckMBean {
    Map<String, Object> check();

    boolean isCritical();
}
