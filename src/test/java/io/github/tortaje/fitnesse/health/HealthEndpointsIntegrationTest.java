package io.github.tortaje.fitnesse.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthEndpointsIntegrationTest {

    @TempDir Path root;
    private EmbeddedFitNesse fitnesse;
    private final Logger rootLogger = Logger.getLogger("");
    private Level savedLevel;

    @BeforeEach
    void start() throws Exception {
        savedLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARNING);
        fitnesse = EmbeddedFitNesse.start(root, new Properties());
    }

    @AfterEach
    void stop() throws Exception {
        try {
            if (fitnesse != null) {
                fitnesse.close();
            }
        } finally {
            rootLogger.setLevel(savedLevel);
        }
    }

    @Test
    void liveViaResponderKey() throws Exception {
        HttpResponse<String> r = fitnesse.get("/?health-live");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void readyViaResponderKey() throws Exception {
        HttpResponse<String> r = fitnesse.get("/?health-ready");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).startsWith("{\"status\":\"UP\",\"timestamp\":")
                .contains("\"fitnesse\":{\"status\":\"UP\",\"critical\":true");
    }

    @Test
    void realPathIs404WithoutAuthenticator() throws Exception {
        assertThat(fitnesse.get("/health/ready").statusCode()).isEqualTo(404);
    }
}
