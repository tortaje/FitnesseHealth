package io.github.tortaje.fitnesse.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fitnesse.FitNesseContext;
import fitnesse.Responder;
import fitnesse.authentication.Authenticator;
import fitnesse.http.Request;
import fitnesse.http.Response;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class HealthRoutingAuthenticatorTest {

    /** Request whose resource is set directly. */
    static final class TestRequest extends Request {
        TestRequest(String resource) {
            super();
            setResource(resource);
        }
    }

    static final Responder MARKER = new Responder() {
        @Override
        public Response makeResponse(FitNesseContext context, Request request) {
            return null;
        }
    };

    static final Responder ORIGINAL = new Responder() {
        @Override
        public Response makeResponse(FitNesseContext context, Request request) {
            return null;
        }
    };

    /** Delegate returning a marker responder. */
    public static class MarkerAuthenticator extends Authenticator {
        public MarkerAuthenticator() {}

        @Override
        public Responder authenticate(FitNesseContext context, Request request, Responder privileged) {
            return MARKER;
        }

        @Override
        public boolean isAuthenticated(String username, String password) {
            return "marker".equals(username);
        }
    }

    /** Delegate built through the (Properties) constructor, as ComponentFactory would. */
    public static class PropertiesAuthenticator extends Authenticator {
        static volatile Properties received;

        public PropertiesAuthenticator(Properties properties) {
            received = properties;
        }

        @Override
        public Responder authenticate(FitNesseContext context, Request request, Responder privileged) {
            return MARKER;
        }

        @Override
        public boolean isAuthenticated(String username, String password) {
            return "props".equals(username);
        }
    }

    private final HealthRoutingAuthenticator auth = new HealthRoutingAuthenticator(new MarkerAuthenticator());

    private Responder route(String resource) throws Exception {
        return auth.authenticate(null, new TestRequest(resource), ORIGINAL);
    }

    @Test
    void routesHealthPaths() throws Exception {
        assertThat(route("health/live")).isInstanceOf(LivenessResponder.class);
        assertThat(route("health/ready")).isInstanceOf(ReadinessResponder.class);
        assertThat(route("health/ready/")).isInstanceOf(ReadinessResponder.class);
    }

    @Test
    void delegatesOtherPaths() throws Exception {
        assertThat(route("FrontPage")).isSameAs(MARKER);
        assertThat(route("health")).isSameAs(MARKER);
        assertThat(route("health/other")).isSameAs(MARKER);
    }

    @Test
    void delegateIsConfigurable() {
        Properties p = new Properties();
        p.setProperty("health.delegateAuthenticator", MarkerAuthenticator.class.getName());
        HealthRoutingAuthenticator configured = new HealthRoutingAuthenticator(p);
        assertThat(configured.isAuthenticated("marker", "x")).isTrue();
        assertThat(configured.isAuthenticated("other", "x")).isFalse();
    }

    @Test
    void delegateWithPropertiesConstructorReceivesProperties() {
        Properties p = new Properties();
        p.setProperty("health.delegateAuthenticator", PropertiesAuthenticator.class.getName());
        p.setProperty("some.key", "v");
        HealthRoutingAuthenticator configured = new HealthRoutingAuthenticator(p);
        assertThat(PropertiesAuthenticator.received).isSameAs(p);
        assertThat(configured.isAuthenticated("props", "x")).isTrue();
    }

    @Test
    void unknownDelegateClassFailsFast() {
        Properties p = new Properties();
        p.setProperty("health.delegateAuthenticator", "does.not.Exist");
        assertThatThrownBy(() -> new HealthRoutingAuthenticator(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does.not.Exist");
    }
}
