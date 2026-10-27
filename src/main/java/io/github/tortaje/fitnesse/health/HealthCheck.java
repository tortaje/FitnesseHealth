package io.github.tortaje.fitnesse.health;

import java.util.Optional;

/** A single named health check. */
public interface HealthCheck {
    String name();

    Optional<Boolean> declaredCritical();

    CheckResult check() throws Exception;
}
