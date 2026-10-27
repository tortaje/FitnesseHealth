package io.github.tortaje.fitnesse.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FitNesseRootCheckTest {

    @TempDir
    Path tmp;

    @Test
    void existingDirectoryIsUp() {
        assertThat(new FitNesseRootCheck(() -> tmp).check().status()).isEqualTo(ComponentStatus.UP);
    }

    @Test
    void missingDirectoryIsDown() {
        Path missing = tmp.resolve("FitNesseRoot");
        CheckResult r = new FitNesseRootCheck(() -> missing).check();
        assertThat(r.status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(r.message()).isEqualTo("Wiki root not accessible: " + missing);
    }

    @Test
    void throwingSupplierIsDown() {
        CheckResult r = new FitNesseRootCheck(() -> { throw new IllegalStateException("boom"); }).check();
        assertThat(r.status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(r.message()).isEqualTo("IllegalStateException: boom");
    }

    @Test
    void identity() {
        FitNesseRootCheck c = new FitNesseRootCheck(() -> tmp);
        assertThat(c.name()).isEqualTo(FitNesseRootCheck.NAME).isEqualTo("fitnesse");
        assertThat(c.declaredCritical()).contains(true);
    }
}
