package io.github.tortaje.fitnesse.health;

import fitnesse.ConfigurationParameter;
import fitnesse.ContextConfigurator;
import fitnesse.FitNesseContext;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Test helper: a real FitNesse server on a free port. */
final class EmbeddedFitNesse implements AutoCloseable {

    private final FitNesseContext context;
    private final int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private EmbeddedFitNesse(FitNesseContext context, int port) {
        this.context = context;
        this.port = port;
    }

    static EmbeddedFitNesse start(Path root, Properties props) throws Exception {
        Files.createDirectories(root.resolve("FitNesseRoot"));
        ServerSocket socket = new ServerSocket(0);
        int port = socket.getLocalPort();
        try {
            FitNesseContext context = ContextConfigurator.systemDefaults()
                    .withRootPath(root.toString())
                    .withParameter(ConfigurationParameter.PORT, Integer.toString(port))
                    .updatedWith(props)
                    .makeFitNesseContext();
            context.fitNesse.start(socket);
            return new EmbeddedFitNesse(context, port);
        } catch (Exception | Error e) {
            socket.close();
            HealthService.reset();
            throw e;
        }
    }

    int port() {
        return port;
    }

    HttpResponse<String> get(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() throws Exception {
        try {
            context.fitNesse.stop();
        } finally {
            HealthService.reset();
        }
    }
}
