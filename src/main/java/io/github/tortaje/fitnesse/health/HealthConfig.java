package io.github.tortaje.fitnesse.health;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Resolves plugin configuration: environment, then properties, then declared value, then default. */
public final class HealthConfig {
  private static final Logger LOG = Logger.getLogger(HealthConfig.class.getName());

  static final long DEFAULT_TIMEOUT_MS = 2000;
  static final long DEFAULT_CACHE_TTL_MS = 10000;
  static final int DEFAULT_THREADS = 4;
  static final String DEFAULT_AUTHENTICATOR = "fitnesse.authentication.PromiscuousAuthenticator";

  private final Map<String, String> env;
  private final Properties properties;
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  public HealthConfig(Map<String, String> env, Properties properties) {
    this.env = env == null ? Map.of() : env;
    this.properties = properties == null ? new Properties() : properties;
  }

  public static HealthConfig fromSystem(Properties properties) {
    return new HealthConfig(System.getenv(), properties);
  }

  public Duration timeout(String component) {
    return Duration.ofMillis(
        resolve(component, "timeout", HealthConfig::positiveLong).orElse(DEFAULT_TIMEOUT_MS));
  }

  public Duration cacheTtl(String component) {
    return Duration.ofMillis(
        resolve(component, "cacheTtl", HealthConfig::nonNegativeLong).orElse(DEFAULT_CACHE_TTL_MS));
  }

  public int threads() {
    return resolveKey("health.check.threads", HealthConfig::positiveInt).orElse(DEFAULT_THREADS);
  }

  public boolean enabled(String component) {
    return resolve(component, "enabled", HealthConfig::bool).orElse(true);
  }

  /** Configured criticality (component key, then global key), if any. */
  public Optional<Boolean> criticalOverride(String component) {
    return resolve(component, "critical", HealthConfig::bool);
  }

  public boolean critical(String component, Optional<Boolean> declared) {
    return criticalOverride(component).orElse(declared == null ? true : declared.orElse(true));
  }

  public String delegateAuthenticator() {
    return resolveKey("health.delegateAuthenticator", s -> s.isBlank() ? null : s.trim())
        .orElse(DEFAULT_AUTHENTICATOR);
  }

  public static String envName(String key) {
    StringBuilder sb = new StringBuilder(key.length());
    for (char ch : key.toCharArray()) {
      sb.append(Character.isLetterOrDigit(ch) && ch < 128 ? Character.toUpperCase(ch) : '_');
    }
    return sb.toString();
  }

  private <T> Optional<T> resolve(String component, String prop, Function<String, T> parser) {
    Optional<T> specific = resolveKey("health.check." + component + "." + prop, parser);
    return specific.isPresent() ? specific : resolveKey("health.check." + prop, parser);
  }

  private <T> Optional<T> resolveKey(String key, Function<String, T> parser) {
    Optional<T> fromEnv = parse(key, env.get(envName(key)), parser);
    return fromEnv.isPresent() ? fromEnv : parse(key, properties.getProperty(key), parser);
  }

  private <T> Optional<T> parse(String key, String raw, Function<String, T> parser) {
    if (raw == null) {
      return Optional.empty();
    }
    T value = null;
    try {
      value = parser.apply(raw);
    } catch (RuntimeException e) {
      // treated as unparsable below
    }
    if (value == null && warned.add(key + "=" + raw)) {
      LOG.log(Level.WARNING, "Ignoring invalid value for {0}: {1}", new Object[] {key, raw});
    }
    return Optional.ofNullable(value);
  }

  private static Long positiveLong(String s) {
    long v = Long.parseLong(s.trim());
    return v > 0 ? v : null;
  }

  private static Long nonNegativeLong(String s) {
    long v = Long.parseLong(s.trim());
    return v >= 0 ? v : null;
  }

  private static Integer positiveInt(String s) {
    int v = Integer.parseInt(s.trim());
    return v > 0 ? v : null;
  }

  private static Boolean bool(String s) {
    String t = s.trim();
    if (t.equalsIgnoreCase("true")) {
      return Boolean.TRUE;
    }
    if (t.equalsIgnoreCase("false")) {
      return Boolean.FALSE;
    }
    return null;
  }
}
