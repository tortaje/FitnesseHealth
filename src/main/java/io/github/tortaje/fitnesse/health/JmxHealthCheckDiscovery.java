package io.github.tortaje.fitnesse.health;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.management.MalformedObjectNameException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/** Discovers MBeans registered under {@value #PATTERN} and adapts them to health checks. */
public final class JmxHealthCheckDiscovery {

    static final String PATTERN = "fitnesse.health:type=HealthCheck,*";

    private static final Logger LOG = Logger.getLogger(JmxHealthCheckDiscovery.class.getName());
    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9_-]+");

    private final MBeanServer server;
    private final ObjectName pattern;
    private final Set<ObjectName> warned = ConcurrentHashMap.newKeySet();

    public JmxHealthCheckDiscovery(MBeanServer server) {
        this.server = server;
        try {
            this.pattern = new ObjectName(PATTERN);
        } catch (MalformedObjectNameException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<HealthCheck> discover() {
        List<ObjectName> names = new ArrayList<>(server.queryNames(pattern, null));
        names.sort(Comparator.comparing(ObjectName::getCanonicalName));
        Set<String> seen = new HashSet<>();
        List<HealthCheck> checks = new ArrayList<>();
        for (ObjectName on : names) {
            String raw = on.getKeyProperty("name");
            String name = raw != null && raw.startsWith("\"") ? ObjectName.unquote(raw) : raw;
            if (name == null || !VALID_NAME.matcher(name).matches()) {
                warnOnce(on, "Ignoring health MBean " + on + ": invalid component name");
                continue;
            }
            if (FitNesseRootCheck.NAME.equals(name)) {
                warnOnce(on, "Ignoring health MBean " + on + ": component name '" + name + "' is reserved");
                continue;
            }
            if (!seen.add(name)) {
                warnOnce(on, "Ignoring health MBean " + on + ": duplicate component name '" + name + "'");
                continue;
            }
            checks.add(new JmxHealthCheck(server, on, name, warned));
        }
        return checks;
    }

    private void warnOnce(ObjectName on, String text) {
        if (warned.add(on)) {
            LOG.log(Level.WARNING, text);
        }
    }
}
