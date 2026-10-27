package io.github.tortaje.fitnesse.health;

import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.authentication.Authenticator;
import fitnesse.http.Request;
import java.util.Objects;
import java.util.Properties;

/**
 * Decorating authenticator: serves {@code /health/live} and {@code /health/ready} without
 * authentication and delegates everything else to the configured authenticator.
 */
public class HealthRoutingAuthenticator extends Authenticator {

    private final Authenticator delegate;

    /** Used by FitNesse's ComponentFactory. */
    public HealthRoutingAuthenticator(Properties properties) {
        this(createDelegate(HealthConfig.fromSystem(properties).delegateAuthenticator(), properties));
    }

    public HealthRoutingAuthenticator(Authenticator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Mirrors FitNesse's ComponentFactory: a {@code (Properties)} constructor first, then no-arg. */
    private static Authenticator createDelegate(String className, Properties properties) {
        try {
            Class<? extends Authenticator> type = loadClass(className).asSubclass(Authenticator.class);
            try {
                return type.getConstructor(Properties.class)
                        .newInstance(properties == null ? new Properties() : properties);
            } catch (NoSuchMethodException e) {
                return type.getConstructor().newInstance();
            }
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            throw new IllegalArgumentException("Cannot create delegate authenticator " + className, e);
        }
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null) {
            try {
                return Class.forName(className, true, loader);
            } catch (ClassNotFoundException e) {
                // fall back to this class's loader
            }
        }
        return Class.forName(className);
    }

    @Override
    public Responder authenticate(FitNesseContext context, Request request, Responder privilegedResponder)
            throws Exception {
        String resource = request.getResource();
        if (resource != null && resource.endsWith("/")) {
            resource = resource.substring(0, resource.length() - 1);
        }
        if ("health/live".equals(resource)) {
            return new LivenessResponder();
        }
        if ("health/ready".equals(resource)) {
            return new ReadinessResponder();
        }
        return delegate.authenticate(context, request, privilegedResponder);
    }

    @Override
    public boolean isAuthenticated(String username, String password) {
        return delegate.isAuthenticated(username, password);
    }
}
