package io.github.tortaje.fitnesse.health;

import static io.github.tortaje.fitnesse.health.TestChecks.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReadinessEvaluatorTest {

    private ReadinessEvaluator evaluator;

    private ReadinessEvaluator evaluator(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        evaluator = new ReadinessEvaluator(new HealthConfig(Map.of(), p), Clock.systemUTC());
        return evaluator;
    }

    private final java.util.logging.Logger evalLogger =
            java.util.logging.Logger.getLogger(ReadinessEvaluator.class.getName());
    private boolean oldUseParent;

    @org.junit.jupiter.api.BeforeEach
    void silenceLogger() {
        oldUseParent = evalLogger.getUseParentHandlers();
        evalLogger.setUseParentHandlers(false);
    }

    @AfterEach
    void tearDown() {
        evalLogger.setUseParentHandlers(oldUseParent);
        if (evaluator != null) {
            evaluator.close();
        }
    }

    @Test void allUpIsUp200() {
        var r = evaluator().evaluate(List.of(up("b"), up("a")));
        assertThat(r.status()).isEqualTo(GlobalStatus.UP);
        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.components().keySet()).containsExactly("a", "b");
    }

    @Test void nonCriticalDownIsDegraded200() {
        var r = evaluator().evaluate(List.of(withCritical(down("llm", "x"), false)));
        assertThat(r.status()).isEqualTo(GlobalStatus.DEGRADED);
        assertThat(r.httpStatus()).isEqualTo(200);
    }

    @Test void criticalDownIsDown503() {
        var r = evaluator().evaluate(List.of(down("mcp", "x")));
        assertThat(r.status()).isEqualTo(GlobalStatus.DOWN);
        assertThat(r.httpStatus()).isEqualTo(503);
        assertThat(r.components().get("mcp").message()).isEqualTo("x");
    }

    @Test void emptyListIsUp() {
        var r = evaluator().evaluate(List.of());
        assertThat(r.status()).isEqualTo(GlobalStatus.UP);
        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.components()).isEmpty();
    }

    @Test void exceptionBecomesDown() {
        var r = evaluator().evaluate(List.of(throwing("s3", new IllegalStateException("boom"))));
        assertThat(r.components().get("s3").status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(r.components().get("s3").message()).isEqualTo("IllegalStateException: boom");
    }

    @Test void exceptionWithoutMessageHasNoNull() {
        var r = evaluator().evaluate(List.of(throwing("s3", new IllegalStateException())));
        assertThat(r.components().get("s3").message()).isEqualTo("IllegalStateException");
    }

    @Test void timeoutBecomesDown() {
        evaluator("health.check.timeout", "200");
        long t0 = System.nanoTime();
        var r = evaluator.evaluate(List.of(sleeping("llm", Duration.ofSeconds(5))));
        assertThat(r.components().get("llm").message()).isEqualTo("Timeout after 200 ms");
        assertThat(r.components().get("llm").status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1000));
    }

    @Test void checksRunInParallel() {
        evaluator("health.check.timeout", "2000");
        long t0 = System.nanoTime();
        var r = evaluator.evaluate(List.of(
                sleeping("a", Duration.ofMillis(300)),
                sleeping("b", Duration.ofMillis(300)),
                sleeping("c", Duration.ofMillis(300))));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(800));
        assertThat(r.status()).isEqualTo(GlobalStatus.UP);
    }

    @Test void disabledComponentIsExcluded() {
        var r = evaluator("health.check.s3.enabled", "false").evaluate(List.of(up("s3"), up("a")));
        assertThat(r.components().keySet()).containsExactly("a");
    }

    @Test void configOverridesDeclaredCriticality() {
        var r = evaluator("health.check.mcp.critical", "false").evaluate(List.of(down("mcp", "x")));
        assertThat(r.status()).isEqualTo(GlobalStatus.DEGRADED);
        assertThat(r.components().get("mcp").critical()).isFalse();
    }

    @Test void jsonShape() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
        evaluator = new ReadinessEvaluator(new HealthConfig(Map.of(), new Properties()), fixed);
        var r = evaluator.evaluate(List.of(up("a")));
        Map<String, Object> json = r.toJsonMap();
        assertThat(json.keySet()).containsExactly("status", "timestamp", "components");
        assertThat(json.get("status")).isEqualTo("UP");
        assertThat(json.get("timestamp")).isEqualTo("2026-10-27T12:00:00Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((Map<String, Object>) json.get("components")).get("a");
        assertThat(a.keySet()).containsExactly("status", "critical", "durationMs");
        assertThat(a.get("status")).isEqualTo("UP");
        assertThat(a.get("critical")).isEqualTo(true);
    }

    @Test void durationIsMeasuredPerComponent() {
        evaluator("health.check.timeout", "2000");
        var r = evaluator.evaluate(List.of(sleeping("a-slow", Duration.ofMillis(400)), up("b-fast")));
        assertThat(r.components().get("a-slow").durationMs()).isGreaterThanOrEqualTo(350);
        assertThat(r.components().get("b-fast").durationMs()).isLessThan(200);
    }

    @Test void nullResultBecomesDown() {
        var r = evaluator().evaluate(List.of(of("n", java.util.Optional.empty(), () -> null)));
        assertThat(r.components().get("n").status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(r.components().get("n").message()).isEqualTo("check() returned null");
    }

    @Test void hungPluginCheckCannotStarveRootCheck() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        evaluator("health.check.threads", "1", "health.check.timeout", "300");
        try {
            HealthCheck stuck = of("stuck", java.util.Optional.of(false), () -> {
                while (true) {
                    try {
                        release.await();
                        return CheckResult.up();
                    } catch (InterruptedException ignored) {
                        // ignores interruption on purpose
                    }
                }
            });
            var r = evaluator.evaluate(List.of(stuck, up(FitNesseRootCheck.NAME)));
            assertThat(r.components().get("fitnesse").status()).isEqualTo(ComponentStatus.UP);
            assertThat(r.components().get("stuck").status()).isEqualTo(ComponentStatus.DOWN);
            assertThat(r.status()).isEqualTo(GlobalStatus.DEGRADED);
            assertThat(r.httpStatus()).isEqualTo(200);
        } finally {
            release.countDown();
        }
    }

    @Test void declaredCriticalNotCalledWhenConfigured() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HealthCheck counting = new HealthCheck() {
            @Override public String name() { return "mcp"; }
            @Override public java.util.Optional<Boolean> declaredCritical() {
                calls.incrementAndGet();
                return java.util.Optional.of(true);
            }
            @Override public CheckResult check() { return CheckResult.up(); }
        };
        var r = evaluator("health.check.mcp.critical", "false").evaluate(List.of(counting));
        assertThat(calls).hasValue(0);
        assertThat(r.components().get("mcp").critical()).isFalse();
    }

    @Test void declaredCriticalCalledOncePerEvaluationWithoutOverride() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HealthCheck counting = new HealthCheck() {
            @Override public String name() { return "mcp"; }
            @Override public java.util.Optional<Boolean> declaredCritical() {
                calls.incrementAndGet();
                return java.util.Optional.of(false);
            }
            @Override public CheckResult check() { return CheckResult.up(); }
        };
        var r = evaluator().evaluate(List.of(counting));
        assertThat(calls).hasValue(1);
        assertThat(r.components().get("mcp").critical()).isFalse();
    }
}
