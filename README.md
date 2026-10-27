# fitnesse-health

[![Maven Central](https://img.shields.io/maven-central/v/io.github.tortaje/fitnesse-health.svg)](https://central.sonatype.com/artifact/io.github.tortaje/fitnesse-health)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

Source: <https://github.com/tortaje/FitnesseHealth>

FitNesse plugin (Java 17, FitNesse 20260313) that exposes two JSON health endpoints for Kubernetes:

- **liveness**: is the process alive (deadlock detection);
- **readiness**: are the components (FitNesse itself and the checks registered by other plugins, for example MCP) operational.

Business plugins register their check through JMX, **with no Maven dependency** on `fitnesse-health`.

## Installation

1. Build the JAR: `mvn package` produces `target/fitnesse-health.jar`.
2. Copy `fitnesse-health.jar` into the FitNesse `plugins/` directory. The plugin is discovered automatically (`ServiceLoader`) and the `health-live` and `health-ready` responders are registered.
3. Optional: to get the `/health/live` and `/health/ready` URLs, add to `plugins.properties`:

   ```properties
   Authenticator=io.github.tortaje.fitnesse.health.HealthRoutingAuthenticator
   ```

> **Warning**: the `Authenticator=` line **replaces** FitNesse authentication (`-a`, Credentials file): FitNesse gives this property priority over `-a`. With `-a`, either configure `health.delegateAuthenticator` (see "Authentication delegation"), or do not declare `Authenticator=` and use the `/?health-live` and `/?health-ready` URLs, which never require credentials.

Without this line, only the `/?health-live` and `/?health-ready` URLs are available; `/health/*` returns 404.

## Endpoints

| URL | Availability |
|---|---|
| `/?health-live`, `/?health-ready` | Always |
| `/health/live`, `/health/ready` | When `HealthRoutingAuthenticator` is declared |

These endpoints require no authentication. Responses carry `Content-Type: application/json; charset=utf-8` and `Cache-Control: no-store`.

### Liveness

Runs no business check. Without deadlock: `200 {"status":"UP"}`. With a deadlock: `503` and `{"status":"DOWN","message":"Deadlock detected","details":{"threads":[...]}}`.

### Readiness

| Situation | `status` | HTTP |
|---|---|---|
| All components `UP` | `UP` | 200 |
| Only non-critical components `DOWN` | `DEGRADED` | 200 |
| At least one critical component `DOWN` | `DOWN` | 503 |

The built-in `fitnesse` component (critical) is always present: it checks that the wiki root directory (`FitNesseRoot`) exists and is readable. It runs on its own thread (`fitnesse-health-root`), outside the shared pool.

```json
{
  "status": "DEGRADED",
  "timestamp": "2026-10-27T12:00:00Z",
  "components": {
    "fitnesse": { "status": "UP",   "critical": true,  "durationMs": 1 },
    "mcp":      { "status": "UP",   "critical": true,  "durationMs": 12, "cached": true,
                  "details": { "tools": 12 } },
    "llm":      { "status": "DOWN", "critical": false, "durationMs": 2000,
                  "message": "Timeout after 2000 ms" }
  }
}
```

Checks run in a dedicated pool (`fitnesse-health-N` threads), in parallel, with a timeout. A check that exceeds its timeout is `DOWN` (`Timeout after <n> ms`); while it is still running, it is not restarted (`Previous check still running`). A check still queued when its timeout expires is reported as `Timeout after <n> ms` without having run: `health.check.threads` must therefore be greater than or equal to the number of plugin components. Each result is cached for `cacheTtl` ms per component.

## Configuration

Keys go in `plugins.properties` (or as FitNesse parameters).

| Key | Default | Purpose |
|---|---|---|
| `health.check.timeout` | `2000` (ms) | Global check timeout |
| `health.check.cacheTtl` | `10000` (ms) | Global cache TTL |
| `health.check.threads` | `4` | Execution pool size |
| `health.check.<name>.critical` | value of the MBean's `isCritical()` | Criticality override |
| `health.check.<name>.timeout` | global value | Component timeout |
| `health.check.<name>.cacheTtl` | global value | Component TTL |
| `health.check.critical` | `true` if the MBean declares nothing | Global fallback criticality |
| `health.check.enabled` | `true` | Global fallback enablement |
| `health.check.<name>.enabled` | `true` | `false` excludes the component from the JSON and the aggregation |
| `health.delegateAuthenticator` | `fitnesse.authentication.PromiscuousAuthenticator` | Real authenticator behind `HealthRoutingAuthenticator` |

These keys also apply to the built-in `fitnesse` component.

**Precedence** (for each component setting): the component key wins over the global key; for the same key, the environment variable wins over `plugins.properties`. Full order: component key (environment, then `plugins.properties`), then global key (environment, then `plugins.properties`), then the MBean's `isCritical()` (for criticality), then the default. Example: `health.check.mcp.timeout` in `plugins.properties` wins over `HEALTH_CHECK_TIMEOUT`. The global keys `health.check.critical` and `health.check.enabled` act as fallbacks, like `timeout` and `cacheTtl`. When a criticality is configured, `isCritical()` is not called.

**Environment variables**: key in upper case, every non-alphanumeric character (`.`, `-`) replaced by `_`, with no splitting of mixed case. Examples: `health.check.mcp.critical` becomes `HEALTH_CHECK_MCP_CRITICAL`; `health.check.cacheTtl` becomes `HEALTH_CHECK_CACHETTL`.

An invalid value (non-numeric, negative, unreadable boolean) is ignored with a warning, and the next source is used.

### Authentication delegation

`HealthRoutingAuthenticator` intercepts `/health/*` and delegates every other request to the authenticator configured by `health.delegateAuthenticator`.

The delegate is instantiated the way FitNesse does it: public `(Properties)` constructor first (it receives the same properties as the plugin), then public no-arg constructor. The class is loaded through the context classloader (the FitNesse plugins one).

**Limitation**: FitNesse authenticators that require other constructor arguments (`OneUserAuthenticator`, `MultiUserAuthenticator`) still cannot be used as delegates as is; to keep `-a`, an adapter class with a `(Properties)` or no-arg constructor is needed.

## Registering a check from another plugin (MCP example)

The business plugin imports **no class** from `fitnesse-health`: **no Maven dependency** is needed. It only has to register a standard MBean on the platform JMX server. If `fitnesse-health` is not installed, the MBean exists with no effect.

Convention rules:

- the object name is `fitnesse.health:type=HealthCheck,name=<name>`, with `<name>` matching `[a-zA-Z0-9_-]+` (it is the component identifier in the JSON and the configuration);
- the MBean interface must be **public** and named `<ImplClass>MBean` (standard MBean rule);
- `Map<String,Object> check()` is mandatory; `boolean isCritical()` is optional (absent: critical).

Values of the map returned by `check()`:

| Key | Type | Purpose |
|---|---|---|
| `status` | `"UP"` or `"DOWN"` (case-insensitive) | Mandatory |
| `message` | `String` | Optional |
| any other key | `String`, `Number`, `Boolean`, `List`, `Map` or `null` | Copied into `details`; any other type is rendered with `toString()` |

The following are `DOWN` with an explicit message: exception in `check()`, `null` map, missing or invalid `status`, missing `check()` operation or wrong signature. An `isCritical` that does not return a boolean is treated as critical.

> **No secrets** in `message` or `details`: they are exposed as is, without authentication. Exception messages thrown by a check are also returned as is on the unauthenticated endpoint (they may contain hosts or URLs): the plugin must catch and rephrase sensitive exceptions.

The name `fitnesse` is reserved for the built-in component: an MBean with that name is ignored (single warning).

Interface:

```java
package com.example.mcp;

import java.util.Map;

public interface McpHealthCheckMBean {
    Map<String, Object> check();

    boolean isCritical();
}
```

Implementation:

```java
package com.example.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

public class McpHealthCheck implements McpHealthCheckMBean {
    private final McpServer server;

    public McpHealthCheck(McpServer server) {
        this.server = server;
    }

    @Override
    public Map<String, Object> check() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean up = server.isRunning();
        result.put("status", up ? "UP" : "DOWN");
        if (!up) {
            result.put("message", "MCP server not running");
        }
        result.put("tools", server.toolCount());
        return result;
    }

    @Override
    public boolean isCritical() {
        return true;
    }
}
```

Registration, in the MCP plugin's `PluginFeatureFactory`, once the server is ready:

```java
import java.lang.management.ManagementFactory;
import javax.management.ObjectName;

ManagementFactory.getPlatformMBeanServer().registerMBean(
        new McpHealthCheck(server),
        new ObjectName("fitnesse.health:type=HealthCheck,name=mcp"));
```

The plugin can remove its check at any time with `unregisterMBean`.

## Kubernetes

```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 8080
  periodSeconds: 10
  timeoutSeconds: 3
readinessProbe:
  httpGet:
    path: /health/ready
    port: 8080
  periodSeconds: 10
  timeoutSeconds: 3
```

Without the `Authenticator=` line, use the `path: /?health-live` and `path: /?health-ready` variants.

**Timeouts**: the readinessProbe `timeoutSeconds` must be **greater** than the check timeout (default 2 s, so 3 s recommended). The Kubernetes default (1 s) is too short. If you increase `health.check.timeout`, increase `timeoutSeconds` accordingly.

## JVM

Recommendation: start FitNesse with `-XX:+ExitOnOutOfMemoryError`, so the container restarts instead of staying in a degraded state after an `OutOfMemoryError`.

## Logging

`java.util.logging`. `INFO` level for every state change of a component or of the global state; `WARNING` for a non-compliant MBean and for an invalid configuration value. No per-request logging.

## Development

`mvn verify` compiles, runs the tests (including integration with an embedded FitNesse) and produces the JAR.

## Release

The `release` profile attaches the sources and javadoc JARs, signs the artifacts with GPG and uploads them to Maven Central:

```bash
mvn -Prelease clean deploy
```

Prerequisites: a non-`SNAPSHOT` version, a GPG key available to `gpg`, and a Central Portal user token declared in `~/.m2/settings.xml` under the server id `central`. The upload stops once the deployment is validated; publish it from <https://central.sonatype.com>.

## License

[Apache License 2.0](LICENSE).
