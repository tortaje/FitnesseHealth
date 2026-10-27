package io.github.tortaje.fitnesse.health;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Runs health checks in parallel with per-component timeouts and aggregates the result. */
public final class ReadinessEvaluator {

    private static final Logger LOG = Logger.getLogger(ReadinessEvaluator.class.getName());

    private final HealthConfig config;
    private final ConcurrentMap<String, ComponentState> states = new ConcurrentHashMap<>();
    private final Object transitionLock = new Object();
    private GlobalStatus lastGlobal;
    private final Clock clock;
    private final ThreadPoolExecutor pool;
    /** Dedicated to the critical built-in check so hung plugin checks can never starve it. */
    private final ThreadPoolExecutor rootPool;

    public ReadinessEvaluator(HealthConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        AtomicInteger counter = new AtomicInteger();
        int threads = config.threads();
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "fitnesse-health-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.rootPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "fitnesse-health-root");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** One execution of a check; shared by every request that arrives while it is in flight. */
    private static final class Run {
        final HealthCheck check;
        final Duration timeout;
        final long startNanos = System.nanoTime();
        final AtomicLong endNanos = new AtomicLong();
        final AtomicBoolean started = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        volatile Future<CheckResult> future;

        Run(HealthCheck check, Duration timeout) {
            this.check = check;
            this.timeout = timeout;
        }
    }

    private static final class ComponentState {
        Run run;                    // guarded by this
        ComponentReport cached;     // guarded by this
        Instant cachedAt;           // guarded by this
        ComponentStatus lastStatus; // guarded by this
        Run loggedRun;              // guarded by this: run whose result was already logged
        boolean removed;            // guarded by this: state was purged and must not be used
    }

    /** What to do for one component: an immediate report, or a run to wait for. */
    private record Plan(String name, HealthCheck check, ComponentState state, ComponentReport ready, Run run,
                        boolean owner, boolean critical) {
    }

    public ReadinessReport evaluate(List<HealthCheck> checks) {
        List<Plan> plans = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (HealthCheck check : checks) {
            names.add(check.name());
            if (config.enabled(check.name())) {
                plans.add(plan(check));
            }
        }
        SortedMap<String, ComponentReport> components = new TreeMap<>();
        boolean criticalDown = false;
        boolean anyDown = false;
        for (Plan p : plans) {
            ComponentReport report = p.ready() != null ? p.ready() : await(p);
            components.put(report.name(), report);
            if (report.status() == ComponentStatus.DOWN) {
                anyDown = true;
                criticalDown |= report.critical();
            }
        }
        purge(names);
        GlobalStatus global = criticalDown ? GlobalStatus.DOWN
                : anyDown ? GlobalStatus.DEGRADED : GlobalStatus.UP;
        logGlobalTransition(global);
        return new ReadinessReport(global, clock.instant(), components);
    }

    public void close() {
        pool.shutdownNow();
        rootPool.shutdownNow();
    }

    /** Decides under the component lock, never waiting on a future while holding it. */
    private Plan plan(HealthCheck check) {
        String name = check.name();
        // declaredCritical() may run plugin code: skip it when configuration decides, call it once otherwise
        boolean critical = config.criticalOverride(name).orElseGet(() -> config.critical(name, check.declaredCritical()));
        Duration ttl = config.cacheTtl(name);
        while (true) {
            ComponentState state = states.computeIfAbsent(name, k -> new ComponentState());
            synchronized (state) {
                if (state.removed) {
                    continue; // purged concurrently: take a fresh state
                }
                return planLocked(check, state, critical, ttl);
            }
        }
    }

    private Plan planLocked(HealthCheck check, ComponentState state, boolean critical, Duration ttl) {
        String name = check.name();
        {
            if (state.cached != null && clock.instant().isBefore(state.cachedAt.plus(ttl))) {
                ComponentReport c = state.cached;
                return new Plan(name, check, state, new ComponentReport(c.name(), c.status(), c.critical(),
                        c.durationMs(), true, c.message(), c.details()), null, false, critical);
            }
            Run run = state.run;
            if (run != null && !run.future.isDone()) {
                return new Plan(name, check, state, null, run, false, critical);
            }
            if (run != null && run.started.get() && !run.finished.get()) {
                CheckResult stuck = CheckResult.down("Previous check still running");
                ComponentReport stuckReport = new ComponentReport(name, stuck.status(), critical, 0L, false,
                        stuck.message(), stuck.details());
                logComponentTransition(state, stuckReport);
                return new Plan(name, check, state, stuckReport, null, false, critical);
            }
            Run fresh = new Run(check, config.timeout(name));
            ThreadPoolExecutor executor = FitNesseRootCheck.NAME.equals(name) ? rootPool : pool;
            Future<CheckResult> submitted = executor.submit(() -> {
                fresh.started.set(true);
                try {
                    return check.check();
                } finally {
                    fresh.endNanos.set(System.nanoTime());
                    fresh.finished.set(true);
                }
            });
            fresh.future = submitted;
            state.run = fresh; // only after a successful submit
            return new Plan(name, check, state, null, fresh, true, critical);
        }
    }

    private ComponentReport await(Plan p) {
        Run run = p.run();
        String name = p.name();
        boolean critical = p.critical();
        CheckResult result;
        boolean finished = true;
        boolean store = true;
        long deadline = run.startNanos + run.timeout.toNanos();
        try {
            result = run.future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            finished = false;
            run.future.cancel(true);
            result = CheckResult.down("Timeout after " + run.timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            result = CheckResult.fromException(e.getCause() != null ? e.getCause() : e);
        } catch (java.util.concurrent.CancellationException e) {
            // another request timed out and cancelled the shared run
            finished = false;
            result = CheckResult.down("Timeout after " + run.timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            finished = false;
            store = false;
            Thread.currentThread().interrupt();
            if (p.owner()) {
                run.future.cancel(true);
            }
            result = CheckResult.fromException(e);
        }
        if (result == null) {
            result = CheckResult.down("check() returned null");
        }
        long endNanos = finished ? run.endNanos.get() : System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endNanos - run.startNanos);
        ComponentReport report = new ComponentReport(name, result.status(), critical, durationMs, false,
                result.message(), result.details());
        if (store) {
            ComponentState state = p.state();
            Duration ttl = config.cacheTtl(name);
            synchronized (state) {
                if (state.run == run) {
                    if (!ttl.isZero()) {
                        state.cached = report;
                        state.cachedAt = clock.instant();
                    }
                    if (state.loggedRun != run) { // log once per run, when the result is produced
                        state.loggedRun = run;
                        logComponentTransition(state, report);
                    }
                }
            }
        }
        return report;
    }

    /** Must be called with the state lock held. */
    private void logComponentTransition(ComponentState state, ComponentReport report) {
        ComponentStatus previous = state.lastStatus;
        state.lastStatus = report.status();
        ComponentStatus from = previous;
        if (from == null && report.status() == ComponentStatus.UP) {
            return;
        }
        if (from != report.status()) {
            String msg = report.message();
            LOG.info(report.name() + ": " + (from == null ? "UNKNOWN" : from.name()) + " -> "
                    + report.status().name() + (msg == null || msg.isEmpty() ? "" : " (" + msg + ")"));
        }
    }

    /** Drops state of components no longer listed, unless a run of theirs is still in flight. */
    private void purge(Set<String> names) {
        for (var entry : states.entrySet()) {
            if (names.contains(entry.getKey())) {
                continue;
            }
            ComponentState state = entry.getValue();
            synchronized (state) {
                Run run = state.run;
                boolean inFlight = run != null
                        && (!run.future.isDone() || (run.started.get() && !run.finished.get()));
                if (!inFlight) {
                    state.removed = true;
                    states.remove(entry.getKey(), state);
                }
            }
        }
    }

    // Global transitions are last-writer-wins: concurrent requests may observe different global
    // states, and the most recent evaluation to finish is the one compared with the previous one.
    private void logGlobalTransition(GlobalStatus global) {
        GlobalStatus previous;
        synchronized (transitionLock) {
            previous = lastGlobal;
            lastGlobal = global;
        }
        if (previous != null && previous != global) {
            LOG.info("global: " + previous.name() + " -> " + global.name());
        }
    }
}
