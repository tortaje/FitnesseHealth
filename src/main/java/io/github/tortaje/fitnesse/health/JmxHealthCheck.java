package io.github.tortaje.fitnesse.health;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;

/** Adapts a standard MBean following the fitnesse.health JMX convention to a {@link HealthCheck}. */
final class JmxHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(JmxHealthCheckDiscovery.class.getName());

    private final MBeanServer server;
    private final ObjectName objectName;
    private final String name;
    private final Set<ObjectName> warned;

    JmxHealthCheck(MBeanServer server, ObjectName objectName, String name, Set<ObjectName> warned) {
        this.server = server;
        this.objectName = objectName;
        this.name = name;
        this.warned = warned;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<Boolean> declaredCritical() {
        try {
            Object value = server.getAttribute(objectName, "Critical");
            return Optional.of(value instanceof Boolean b ? b : Boolean.TRUE);
        } catch (AttributeNotFoundException e) {
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of(true);
        }
    }

    @Override
    public CheckResult check() {
        try {
            if (!hasCheckOperation()) {
                warnOnce("MBean " + objectName + " does not expose Map check()");
                return CheckResult.down("MBean does not expose Map check()");
            }
            Object result = server.invoke(objectName, "check", null, null);
            return convert(result);
        } catch (InstanceNotFoundException e) {
            return CheckResult.down("Check no longer registered");
        } catch (MBeanException e) {
            return CheckResult.fromException(unwrap(e.getTargetException(), e));
        } catch (RuntimeMBeanException e) {
            return CheckResult.fromException(unwrap(e.getTargetException(), e));
        } catch (ReflectionException e) {
            return CheckResult.fromException(unwrap(e.getTargetException(), e));
        } catch (Exception e) {
            return CheckResult.fromException(e);
        }
    }

    private static Throwable unwrap(Throwable cause, Throwable fallback) {
        return cause != null ? cause : fallback;
    }

    private boolean hasCheckOperation() throws Exception {
        for (MBeanOperationInfo op : server.getMBeanInfo(objectName).getOperations()) {
            if (op.getName().equals("check") && op.getSignature().length == 0 && returnsMap(op)) {
                return true;
            }
        }
        return false;
    }

    private static boolean returnsMap(MBeanOperationInfo op) {
        try {
            return Map.class.isAssignableFrom(Class.forName(op.getReturnType(), false, null));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static CheckResult convert(Object result) {
        if (!(result instanceof Map<?, ?> map)) {
            return CheckResult.down("check() returned null");
        }
        Object status = map.get("status");
        if (status == null) {
            return CheckResult.down("Missing status");
        }
        ComponentStatus parsed = null;
        if (status instanceof String s) {
            String normalized = s.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("UP")) {
                parsed = ComponentStatus.UP;
            } else if (normalized.equals("DOWN")) {
                parsed = ComponentStatus.DOWN;
            }
        }
        if (parsed == null) {
            return CheckResult.down("Invalid status: " + status);
        }
        Object message = map.get("message");
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!key.equals("status") && !key.equals("message")) {
                details.put(key, e.getValue());
            }
        }
        return new CheckResult(parsed, message == null ? null : String.valueOf(message), details);
    }

    private void warnOnce(String text) {
        if (warned.add(objectName)) {
            LOG.log(Level.WARNING, text);
        }
    }
}
