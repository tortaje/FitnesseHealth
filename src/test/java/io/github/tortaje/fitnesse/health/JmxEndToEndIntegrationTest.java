package io.github.tortaje.fitnesse.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tortaje.fitnesse.health.jmx.SwitchableCheck;
import java.lang.management.ManagementFactory;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmxEndToEndIntegrationTest {

    @TempDir Path root;
    private EmbeddedFitNesse fitnesse;
    private final SwitchableCheck check = new SwitchableCheck();
    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private ObjectName name;
    private final Logger rootLogger = Logger.getLogger("");
    private Level savedLevel;

    @BeforeEach
    void register() throws Exception {
        savedLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARNING);
        name = new ObjectName("fitnesse.health:type=HealthCheck,name=demo");
        server.registerMBean(check, name);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try {
            if (fitnesse != null) {
                fitnesse.close();
            }
        } finally {
            try {
                if (server.isRegistered(name)) {
                    server.unregisterMBean(name);
                }
            } finally {
                rootLogger.setLevel(savedLevel);
            }
        }
    }

    private void startFitNesse(String... keyValues) throws Exception {
        Properties props = new Properties();
        props.setProperty("health.check.cacheTtl", "0");
        for (int i = 0; i < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        fitnesse = EmbeddedFitNesse.start(root, props);
    }

    @Test
    void criticalDownGives503() throws Exception {
        startFitNesse();
        check.status = "UP";
        assertThat(fitnesse.get("/?health-ready").statusCode()).isEqualTo(200);
        check.status = "DOWN";
        HttpResponse<String> r = fitnesse.get("/?health-ready");
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).startsWith("{\"status\":\"DOWN\"")
                .contains("\"demo\":{\"status\":\"DOWN\",\"critical\":true");
    }

    @Test
    void nonCriticalOverrideGivesDegraded200() throws Exception {
        startFitNesse("health.check.demo.critical", "false");
        check.status = "DOWN";
        HttpResponse<String> r = fitnesse.get("/?health-ready");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).startsWith("{\"status\":\"DEGRADED\"");
    }

    @Test
    void detailsAreExposed() throws Exception {
        startFitNesse();
        check.tools = 12;
        HttpResponse<String> r = fitnesse.get("/?health-ready");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"details\":{\"tools\":12}");
    }
}
