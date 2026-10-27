package io.github.tortaje.fitnesse.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class LivenessProbeTest {

    @Test
    void noDeadlockIsUp() {
        LivenessProbe.Result r = new LivenessProbe(ManagementFactory.getThreadMXBean()).check();
        assertThat(r.up()).isTrue();
        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.toJsonMap()).containsOnlyKeys("status").containsEntry("status", "UP");
    }

    /**
     * A real deadlock would stay for the whole JVM and turn every other liveness assertion DOWN, so the
     * MXBean is faked: it reports two parked (not deadlocked) daemon threads as deadlocked.
     */
    @Test
    void deadlockIsDown() throws Exception {
        ThreadMXBean real = ManagementFactory.getThreadMXBean();
        CountDownLatch release = new CountDownLatch(1);
        Thread a = parked("dl-a", release);
        Thread b = parked("dl-b", release);
        try {
            ThreadMXBean fake = (ThreadMXBean) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {ThreadMXBean.class}, (proxy, method, args) -> {
                        if (method.getName().equals("findDeadlockedThreads")) {
                            return new long[] {a.getId(), b.getId()};
                        }
                        return method.invoke(real, args);
                    });
            LivenessProbe.Result r = new LivenessProbe(fake).check();
            assertThat(r.up()).isFalse();
            assertThat(r.deadlockedThreads()).containsExactlyInAnyOrder("dl-a", "dl-b");
            assertThat(r.httpStatus()).isEqualTo(503);
            assertThat(r.toJsonMap()).containsEntry("message", "Deadlock detected").containsEntry("status", "DOWN");
            assertThat(Json.write(r.toJsonMap())).contains("\"details\":{\"threads\":[");
        } finally {
            release.countDown();
        }
    }

    private static Thread parked(String name, CountDownLatch release) {
        Thread t = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
