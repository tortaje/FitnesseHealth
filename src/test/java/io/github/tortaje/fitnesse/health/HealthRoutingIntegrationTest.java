package io.github.tortaje.fitnesse.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthRoutingIntegrationTest {

    @TempDir Path root;
    private EmbeddedFitNesse fitnesse;
    private final Logger rootLogger = Logger.getLogger("");
    private Level savedLevel;

    @BeforeEach
    void start() throws Exception {
        savedLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.WARNING);
        Path page = root.resolve("FitNesseRoot").resolve("FrontPage");
        Files.createDirectories(page);
        Files.writeString(page.resolve("content.txt"), "hello health");
        Properties props = new Properties();
        props.setProperty("Authenticator", HealthRoutingAuthenticator.class.getName());
        fitnesse = EmbeddedFitNesse.start(root, props);
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
    void realPathsServed() throws Exception {
        assertThat(fitnesse.get("/health/live").statusCode()).isEqualTo(200);
        assertThat(fitnesse.get("/health/ready").body()).contains("\"fitnesse\"");
    }

    @Test
    void wikiPagesStillServed() throws Exception {
        HttpResponse<String> r = fitnesse.get("/FrontPage");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("hello health");
    }

    @Test
    void responderKeysStillWork() throws Exception {
        assertThat(fitnesse.get("/?health-ready").statusCode()).isEqualTo(200);
    }
}
