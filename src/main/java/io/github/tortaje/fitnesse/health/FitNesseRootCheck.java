package io.github.tortaje.fitnesse.health;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/** Built-in "fitnesse" check: the wiki root directory exists and is readable. */
public final class FitNesseRootCheck implements HealthCheck {

    public static final String NAME = "fitnesse";

    private final Supplier<Path> rootDirectory;

    public FitNesseRootCheck(Supplier<Path> rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Boolean> declaredCritical() {
        return Optional.of(true);
    }

    @Override
    public CheckResult check() {
        try {
            Path p = rootDirectory.get();
            return Files.isDirectory(p) && Files.isReadable(p)
                    ? CheckResult.up()
                    : CheckResult.down("Wiki root not accessible: " + p);
        } catch (RuntimeException e) {
            return CheckResult.fromException(e);
        }
    }
}
