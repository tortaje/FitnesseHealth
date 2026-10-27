package io.github.tortaje.fitnesse.health.jmx;

import java.util.Map;

public interface NoCriticalCheckMBean {
    Map<String, Object> check();
}
