package io.github.tortaje.fitnesse.health.jmx;

import java.util.Map;

public interface ThrowingCheckMBean {
    Map<String, Object> check();
}
