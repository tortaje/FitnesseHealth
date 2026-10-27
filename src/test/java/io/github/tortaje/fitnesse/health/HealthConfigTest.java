package io.github.tortaje.fitnesse.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class HealthConfigTest {

  HealthConfig cfg(Map<String, String> env, String... kv) {
    Properties p = new Properties();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return new HealthConfig(env, p);
  }

  @Test void defaults() {
    var c = cfg(Map.of());
    assertThat(c.timeout("x")).isEqualTo(Duration.ofMillis(2000));
    assertThat(c.cacheTtl("x")).isEqualTo(Duration.ofMillis(10000));
    assertThat(c.threads()).isEqualTo(4);
    assertThat(c.enabled("x")).isTrue();
    assertThat(c.critical("x", Optional.empty())).isTrue();
    assertThat(c.delegateAuthenticator()).isEqualTo("fitnesse.authentication.PromiscuousAuthenticator");
  }

  @Test void componentOverridesGlobal() {
    var c = cfg(Map.of(), "health.check.timeout", "3000", "health.check.mcp.timeout", "500");
    assertThat(c.timeout("mcp")).isEqualTo(Duration.ofMillis(500));
    assertThat(c.timeout("s3")).isEqualTo(Duration.ofMillis(3000));
  }

  @Test void precedenceEnvThenPropertiesThenDeclaredThenDefault() {
    assertThat(cfg(Map.of("HEALTH_CHECK_MCP_CRITICAL", "true"), "health.check.mcp.critical", "false")
        .critical("mcp", Optional.of(false))).isTrue();
    assertThat(cfg(Map.of(), "health.check.mcp.critical", "false").critical("mcp", Optional.of(true))).isFalse();
    assertThat(cfg(Map.of()).critical("mcp", Optional.of(false))).isFalse();
  }

  @Test void envNameConversion() {
    assertThat(HealthConfig.envName("health.check.my-llm.cacheTtl")).isEqualTo("HEALTH_CHECK_MY_LLM_CACHETTL");
  }

  @Test void invalidValuesFallBackToNextSource() {
    var c = cfg(Map.of("HEALTH_CHECK_TIMEOUT", "abc"), "health.check.timeout", "-5", "health.check.mcp.critical", "maybe");
    assertThat(c.timeout("x")).isEqualTo(Duration.ofMillis(2000));
    assertThat(c.critical("mcp", Optional.of(false))).isFalse();
  }

  @Test void zeroTimeoutAndThreadsInvalidZeroTtlValid() {
    var c = cfg(Map.of(), "health.check.timeout", "0", "health.check.threads", "0", "health.check.cacheTtl", "0");
    assertThat(c.timeout("x")).isEqualTo(Duration.ofMillis(2000));
    assertThat(c.threads()).isEqualTo(4);
    assertThat(c.cacheTtl("x")).isEqualTo(Duration.ZERO);
  }

  @Test void disabledComponent() {
    assertThat(cfg(Map.of(), "health.check.s3.enabled", "false").enabled("s3")).isFalse();
  }
}
