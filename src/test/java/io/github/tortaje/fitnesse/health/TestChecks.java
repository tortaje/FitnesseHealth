package io.github.tortaje.fitnesse.health;

import java.time.Duration;
import java.util.Optional;

/** Factories for HealthCheck test doubles. */
final class TestChecks {

    private TestChecks() {
    }

    interface Body {
        CheckResult run() throws Exception;
    }

    static HealthCheck of(String name, Optional<Boolean> critical, Body body) {
        return new HealthCheck() {
            @Override public String name() { return name; }
            @Override public Optional<Boolean> declaredCritical() { return critical; }
            @Override public CheckResult check() throws Exception { return body.run(); }
        };
    }

    static HealthCheck up(String name) {
        return of(name, Optional.empty(), CheckResult::up);
    }

    static HealthCheck down(String name, String message) {
        return of(name, Optional.empty(), () -> CheckResult.down(message));
    }

    static HealthCheck throwing(String name, Exception ex) {
        return of(name, Optional.empty(), () -> { throw ex; });
    }

    static HealthCheck sleeping(String name, Duration d) {
        return of(name, Optional.empty(), () -> {
            Thread.sleep(d.toMillis());
            return CheckResult.up();
        });
    }

    static HealthCheck withCritical(HealthCheck check, boolean critical) {
        return of(check.name(), Optional.of(critical), check::check);
    }
}
