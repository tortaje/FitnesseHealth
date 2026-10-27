package io.github.tortaje.fitnesse.health;

import fitnesse.FitNesseContext;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Process-wide entry point shared by the responders and the authenticator. */
public final class HealthService {

    private static HealthService instance;

    private final FitNesseContext context;
    private final ReadinessEvaluator evaluator;
    private final JmxHealthCheckDiscovery discovery;
    private final LivenessProbe liveness;

    private HealthService(FitNesseContext context) {
        this.context = context;
        this.evaluator = new ReadinessEvaluator(HealthConfig.fromSystem(context.getProperties()), Clock.systemUTC());
        this.discovery = new JmxHealthCheckDiscovery(ManagementFactory.getPlatformMBeanServer());
        this.liveness = new LivenessProbe(ManagementFactory.getThreadMXBean());
    }

    public static synchronized HealthService forContext(FitNesseContext context) {
        if (instance == null) {
            instance = new HealthService(context);
        }
        return instance;
    }

    public ReadinessReport readiness() {
        List<HealthCheck> checks = new ArrayList<>();
        checks.add(new FitNesseRootCheck(() -> Path.of(context.getRootPagePath())));
        checks.addAll(discovery.discover());
        return evaluator.evaluate(checks);
    }

    public LivenessProbe.Result liveness() {
        return liveness.check();
    }

    static synchronized void reset() {
        if (instance != null) {
            instance.evaluator.close();
            instance = null;
        }
    }
}
