package io.github.tortaje.fitnesse.health.jmx;

import java.util.Map;

public class ThrowingCheck implements ThrowingCheckMBean {
    @Override public Map<String, Object> check() { throw new IllegalStateException("boom"); }
}
