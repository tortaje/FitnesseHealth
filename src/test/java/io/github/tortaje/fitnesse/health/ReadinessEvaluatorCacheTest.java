package io.github.tortaje.fitnesse.health;

import static io.github.tortaje.fitnesse.health.TestChecks.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReadinessEvaluatorCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final List<CountDownLatch> latches = new ArrayList<>();
    private ReadinessEvaluator evaluator;
    private ExecutorService callers;

    private ReadinessEvaluator evaluator(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        evaluator = new ReadinessEvaluator(new HealthConfig(Map.of(), p), clock);
        return evaluator;
    }

    private CountDownLatch latch() {
        CountDownLatch l = new CountDownLatch(1);
        latches.add(l);
        return l;
    }

    private final Logger evalLogger = Logger.getLogger(ReadinessEvaluator.class.getName());
    private boolean oldUseParent;

    @org.junit.jupiter.api.BeforeEach
    void silenceLogger() {
        oldUseParent = evalLogger.getUseParentHandlers();
        evalLogger.setUseParentHandlers(false);
    }

    @AfterEach
    void tearDown() {
        evalLogger.setUseParentHandlers(oldUseParent);
        latches.forEach(CountDownLatch::countDown);
        if (callers != null) {
            callers.shutdownNow();
        }
        if (evaluator != null) {
            evaluator.close();
        }
    }

    private static HealthCheck counting(String name, AtomicInteger calls, TestChecks.Body body) {
        return of(name, Optional.empty(), () -> {
            calls.incrementAndGet();
            return body.run();
        });
    }

    @Test void cachedWithinTtl() {
        AtomicInteger calls = new AtomicInteger();
        var check = counting("a", calls, CheckResult::up);
        var ev = evaluator("health.check.cacheTtl", "10000");
        var first = ev.evaluate(List.of(check)).components().get("a");
        var second = ev.evaluate(List.of(check)).components().get("a");
        assertThat(calls).hasValue(1);
        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.durationMs()).isEqualTo(first.durationMs());
        assertThat(second.status()).isEqualTo(first.status());
    }

    @Test void reevaluatedAfterTtl() {
        AtomicInteger calls = new AtomicInteger();
        var check = counting("a", calls, CheckResult::up);
        var ev = evaluator("health.check.cacheTtl", "10000");
        ev.evaluate(List.of(check));
        clock.advance(Duration.ofMillis(10_001));
        var second = ev.evaluate(List.of(check)).components().get("a");
        assertThat(calls).hasValue(2);
        assertThat(second.cached()).isFalse();
    }

    @Test void downIsCachedToo() {
        AtomicInteger calls = new AtomicInteger();
        var check = counting("a", calls, () -> CheckResult.down("boom"));
        var ev = evaluator("health.check.cacheTtl", "10000");
        ev.evaluate(List.of(check));
        var second = ev.evaluate(List.of(check)).components().get("a");
        assertThat(calls).hasValue(1);
        assertThat(second.status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(second.message()).isEqualTo("boom");
        assertThat(second.cached()).isTrue();
    }

    @Test void zeroTtlDisablesCache() {
        AtomicInteger calls = new AtomicInteger();
        var check = counting("a", calls, CheckResult::up);
        var ev = evaluator("health.check.cacheTtl", "0");
        ev.evaluate(List.of(check));
        var second = ev.evaluate(List.of(check)).components().get("a");
        assertThat(calls).hasValue(2);
        assertThat(second.cached()).isFalse();
    }

    @Test void concurrentRequestsShareOneExecution() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = latch();
        CountDownLatch started = new CountDownLatch(1);
        var check = counting("a", calls, () -> {
            started.countDown();
            release.await();
            return CheckResult.up();
        });
        var ev = evaluator("health.check.cacheTtl", "0", "health.check.timeout", "10000");
        List<Thread> threads = java.util.Collections.synchronizedList(new ArrayList<>());
        callers = Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r);
            threads.add(t);
            return t;
        });
        List<Future<ReadinessReport>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(callers.submit(() -> ev.evaluate(List.of(check))));
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) { // wait until every caller is parked on the shared run
            boolean allWaiting;
            synchronized (threads) {
                allWaiting = threads.size() == 5 && threads.stream().allMatch(t ->
                        t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TIMED_WAITING);
            }
            if (allWaiting) {
                break;
            }
            assertThat(System.nanoTime()).isLessThan(limit);
            Thread.sleep(5);
        }
        release.countDown();
        for (Future<ReadinessReport> f : futures) {
            var report = f.get(5, TimeUnit.SECONDS);
            assertThat(report.status()).isEqualTo(GlobalStatus.UP);
            assertThat(report.components().get("a").status()).isEqualTo(ComponentStatus.UP);
            assertThat(report.components().get("a").cached()).isFalse();
        }
        assertThat(calls).hasValue(1);
    }

    @Test void stuckCheckIsNotRelaunched() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = latch();
        CountDownLatch done = latch();
        var check = counting("a", calls, () -> {
            try {
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // ignore interruption on purpose
                    }
                }
                return CheckResult.up();
            } finally {
                done.countDown();
            }
        });
        var ev = evaluator("health.check.cacheTtl", "0", "health.check.timeout", "100");
        var first = ev.evaluate(List.of(check)).components().get("a");
        assertThat(first.status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(first.message()).isEqualTo("Timeout after 100 ms");

        var second = ev.evaluate(List.of(check)).components().get("a");
        assertThat(second.message()).isEqualTo("Previous check still running");
        assertThat(second.status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(second.durationMs()).isZero();
        assertThat(second.cached()).isFalse();
        assertThat(calls).hasValue(1);

        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ComponentReport third;
        do {
            third = ev.evaluate(List.of(check)).components().get("a");
            assertThat(System.nanoTime()).isLessThan(limit);
        } while ("Previous check still running".equals(third.message()));
        assertThat(calls).hasValue(2);
        assertThat(third.status()).isEqualTo(ComponentStatus.UP);
    }

    @Test void saturatedPoolStillAnswersInTime() {
        var ev = evaluator("health.check.threads", "1", "health.check.timeout", "200",
                "health.check.cacheTtl", "0");
        List<HealthCheck> checks = List.of(sleeping("a", Duration.ofSeconds(5)),
                sleeping("b", Duration.ofSeconds(5)));
        long t0 = System.nanoTime();
        var r = ev.evaluate(checks);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(1));
        assertThat(r.components().get("a").message()).isEqualTo("Timeout after 200 ms");
        assertThat(r.components().get("b").message()).isEqualTo("Timeout after 200 ms");
        // b may have been picked up by the freed worker just before being cancelled, in which case it is
        // briefly (and correctly) "still running" until its interrupt unwinds; it must then be relaunchable.
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ComponentReport again;
        do {
            again = ev.evaluate(checks).components().get("b");
            assertThat(System.nanoTime()).isLessThan(limit);
        } while ("Previous check still running".equals(again.message()));
        assertThat(again.message()).isEqualTo("Timeout after 200 ms");
    }

    @Test void vanishedComponentIsPurged() {
        AtomicInteger bCalls = new AtomicInteger();
        var a = up("a");
        var b = counting("b", bCalls, CheckResult::up);
        var ev = evaluator("health.check.cacheTtl", "10000");
        ev.evaluate(List.of(a, b));
        assertThat(ev.evaluate(List.of(a)).components().keySet()).containsExactly("a");
        var r = ev.evaluate(List.of(a, b));
        assertThat(bCalls).hasValue(2);
        assertThat(r.components().get("b").cached()).isFalse();
    }

    @Test void stuckComponentSurvivesPurge() {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = latch();
        var stuck = counting("b", calls, () -> {
            while (true) {
                try {
                    release.await();
                    return CheckResult.up();
                } catch (InterruptedException ignored) {
                    // ignore interruption on purpose
                }
            }
        });
        var a = up("a");
        var ev = evaluator("health.check.cacheTtl", "0", "health.check.timeout", "100");
        assertThat(ev.evaluate(List.of(a, stuck)).components().get("b").message()).isEqualTo("Timeout after 100 ms");
        ev.evaluate(List.of(a)); // b vanishes from the list while its run is still in flight
        var again = ev.evaluate(List.of(a, stuck)).components().get("b");
        assertThat(again.message()).isEqualTo("Previous check still running");
        assertThat(calls).hasValue(1);
    }

    @Test void submitFailureAfterCloseDoesNotLeaveBrokenState() {
        var ev = evaluator();
        ev.close();
        var check = up("a");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ev.evaluate(List.of(check)))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ev.evaluate(List.of(check)))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }

    @Test void staleReportConsumedLateDoesNotLogReversedTransition() throws Exception {
        List<String> messages = java.util.Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { messages.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        evalLogger.addHandler(handler);
        try {
            AtomicInteger aCalls = new AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
            CountDownLatch release = latch();
            var a = counting("a", aCalls, () -> fail.get() ? CheckResult.down("x") : CheckResult.up());
            var slow = of("b", Optional.empty(), () -> {
                release.await();
                return CheckResult.up();
            });
            var ev = evaluator("health.check.cacheTtl", "0", "health.check.timeout", "10000");
            ev.evaluate(List.of(a)); // a is UP: first result, nothing logged
            callers = Executors.newFixedThreadPool(2);
            // X runs a (UP) but is still waiting on slow b when Y produces DOWN for a
            Future<ReadinessReport> x = callers.submit(() -> ev.evaluate(List.of(slow, a)));
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (aCalls.get() < 2) {
                assertThat(System.nanoTime()).isLessThan(limit);
                Thread.sleep(5);
            }
            Thread.sleep(50); // let a's UP run complete (it is instantaneous); Y starts after
            fail.set(true);
            assertThat(ev.evaluate(List.of(a)).components().get("a").status()).isEqualTo(ComponentStatus.DOWN);
            release.countDown();
            x.get(5, TimeUnit.SECONDS);
            List<String> aLogs;
            synchronized (messages) {
                aLogs = messages.stream().filter(m -> m.startsWith("a:")).toList();
            }
            assertThat(aLogs).containsExactly("a: UP -> DOWN (x)");
        } finally {
            evalLogger.removeHandler(handler);
        }
    }

    @Test void logsTransitionsOnce() {
        Logger logger = Logger.getLogger(ReadinessEvaluator.class.getName());
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel() == Level.INFO) {
                    synchronized (messages) {
                        messages.add(r.getMessage());
                    }
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Level old = logger.getLevel();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            AtomicInteger n = new AtomicInteger();
            var check = of("mcp", Optional.empty(), () ->
                    n.incrementAndGet() <= 2 ? CheckResult.up() : CheckResult.down("x"));
            var ev = evaluator("health.check.cacheTtl", "0");
            for (int i = 0; i < 4; i++) {
                ev.evaluate(List.of(check));
            }
            synchronized (messages) {
                assertThat(messages).containsExactly("mcp: UP -> DOWN (x)", "global: UP -> DOWN");
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(old);
        }
    }
}
