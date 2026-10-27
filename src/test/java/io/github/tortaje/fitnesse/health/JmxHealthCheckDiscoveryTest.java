package io.github.tortaje.fitnesse.health;

import static io.github.tortaje.fitnesse.health.ComponentStatus.DOWN;
import static io.github.tortaje.fitnesse.health.ComponentStatus.UP;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tortaje.fitnesse.health.jmx.GoodCheck;
import io.github.tortaje.fitnesse.health.jmx.NoCriticalCheck;
import io.github.tortaje.fitnesse.health.jmx.StringCriticalCheck;
import io.github.tortaje.fitnesse.health.jmx.ThrowingCheck;
import io.github.tortaje.fitnesse.health.jmx.WrongSignatureCheck;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JmxHealthCheckDiscoveryTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private final List<ObjectName> registered = new ArrayList<>();
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getLogger(JmxHealthCheckDiscovery.class.getName());
    private boolean parentHandlers;
    private Handler handler;
    private final JmxHealthCheckDiscovery discovery = new JmxHealthCheckDiscovery(server);

    @BeforeEach
    void captureLogs() {
        parentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        handler = new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(r);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
    }

    @AfterEach
    void cleanup() throws Exception {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(parentHandlers);
        for (ObjectName n : registered) {
            if (server.isRegistered(n)) {
                server.unregisterMBean(n);
            }
        }
    }

    private ObjectName register(Object mbean, String nameValue) throws Exception {
        return registerFull(mbean, "fitnesse.health:type=HealthCheck,name=" + nameValue);
    }

    private ObjectName register(Object mbean, String nameValue, String extra) throws Exception {
        return registerFull(mbean, "fitnesse.health:type=HealthCheck,name=" + nameValue + "," + extra);
    }

    private ObjectName registerFull(Object mbean, String full) throws Exception {
        ObjectName n = new ObjectName(full);
        server.registerMBean(mbean, n);
        registered.add(n);
        return n;
    }

    private List<HealthCheck> discover() {
        return discovery.discover();
    }

    private HealthCheck single(List<HealthCheck> l) {
        assertThat(l).hasSize(1);
        return l.get(0);
    }

    private GoodCheck returning(Supplier<Map<String, Object>> s) {
        return new GoodCheck(s);
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test void conformingMBean() throws Exception {
        register(new GoodCheck(), "mcp");
        var c = single(discover());
        assertThat(c.name()).isEqualTo("mcp");
        assertThat(c.declaredCritical()).contains(false);
        assertThat(c.check()).isEqualTo(new CheckResult(UP, "ok", Map.of("tools", 12)));
    }

    @Test void missingIsCriticalMeansEmpty() throws Exception {
        register(new NoCriticalCheck(), "mcp");
        assertThat(single(discover()).declaredCritical()).isEmpty();
    }

    @Test void statusIsTrimmedAndCaseInsensitive() throws Exception {
        register(returning(() -> map("status", " up ")), "mcp");
        assertThat(single(discover()).check().status()).isEqualTo(UP);
    }

    @Test void nonStringStatusIsDown() throws Exception {
        register(returning(() -> map("status", Boolean.TRUE)), "mcp");
        assertThat(single(discover()).check()).isEqualTo(CheckResult.down("Invalid status: true"));
    }

    @Test void missingStatusIsDown() throws Exception {
        register(returning(HashMap::new), "mcp");
        assertThat(single(discover()).check()).isEqualTo(CheckResult.down("Missing status"));
    }

    @Test void nullMapIsDown() throws Exception {
        register(returning(() -> null), "mcp");
        assertThat(single(discover()).check()).isEqualTo(CheckResult.down("check() returned null"));
    }

    @Test void unknownStatusIsDown() throws Exception {
        register(returning(() -> map("status", "WARN")), "mcp");
        assertThat(single(discover()).check()).isEqualTo(CheckResult.down("Invalid status: WARN"));
    }

    @Test void throwingCheckIsDown() throws Exception {
        register(new ThrowingCheck(), "mcp");
        assertThat(single(discover()).check())
                .isEqualTo(CheckResult.down("IllegalStateException: boom"));
    }

    @Test void wrongSignatureIsDown() throws Exception {
        register(new WrongSignatureCheck(), "mcp");
        assertThat(single(discover()).check())
                .isEqualTo(CheckResult.down("MBean does not expose Map check()"));
    }

    @Test void nonBooleanCriticalIsCritical() throws Exception {
        register(new StringCriticalCheck(), "mcp");
        assertThat(single(discover()).declaredCritical()).isEqualTo(Optional.of(true));
    }

    @Test void unregisteredMBeanDisappears() throws Exception {
        ObjectName n = register(new GoodCheck(), "mcp");
        assertThat(discover()).hasSize(1);
        server.unregisterMBean(n);
        assertThat(discover()).isEmpty();
    }

    @Test void invalidNameIgnored() throws Exception {
        register(new GoodCheck(), "\"bad name!\"");
        assertThat(discover()).isEmpty();
        assertThat(discover()).isEmpty();
        assertThat(warnings).hasSize(1);
    }

    @Test void reservedNameIsIgnored() throws Exception {
        register(new GoodCheck(), "fitnesse");
        assertThat(discover()).isEmpty();
        assertThat(discover()).isEmpty();
        assertThat(warnings).hasSize(1);
    }

    @Test void quotedValidNameIsUnquoted() throws Exception {
        register(new GoodCheck(), "\"mcp\"");
        assertThat(single(discover()).name()).isEqualTo("mcp");
    }

    @Test void duplicateNameKeepsFirstCanonical() throws Exception {
        register(new GoodCheck(), "mcp");
        register(new NoCriticalCheck(), "mcp", "instance=2");
        var c = single(discover());
        assertThat(c.name()).isEqualTo("mcp");
        // canonical order puts "instance=2,..." first: it is the one without Critical
        assertThat(c.declaredCritical()).isEmpty();
        discover();
        assertThat(warnings).hasSize(1);
    }

    @Test void unregisteredBetweenDiscoverAndCheck() throws Exception {
        ObjectName n = register(new GoodCheck(), "mcp");
        var c = single(discover());
        server.unregisterMBean(n);
        assertThat(c.check()).isEqualTo(CheckResult.down("Check no longer registered"));
        assertThat(c.check().status()).isEqualTo(DOWN);
    }
}
