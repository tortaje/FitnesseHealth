package io.github.tortaje.fitnesse.health;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Liveness: the JVM is alive and has no deadlocked threads. */
public final class LivenessProbe {

    public record Result(boolean up, List<String> deadlockedThreads) {

        public Result {
            deadlockedThreads = List.copyOf(deadlockedThreads);
        }

        public int httpStatus() {
            return up ? 200 : 503;
        }

        public Map<String, Object> toJsonMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", up ? "UP" : "DOWN");
            if (!up) {
                map.put("message", "Deadlock detected");
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("threads", deadlockedThreads);
                map.put("details", details);
            }
            return map;
        }
    }

    private final ThreadMXBean threads;

    public LivenessProbe(ThreadMXBean threads) {
        this.threads = threads;
    }

    public Result check() {
        long[] ids = threads.findDeadlockedThreads();
        if (ids == null || ids.length == 0) {
            return new Result(true, List.of());
        }
        List<String> names = new ArrayList<>();
        ThreadInfo[] infos = threads.getThreadInfo(ids);
        if (infos != null) {
            for (ThreadInfo info : infos) {
                if (info != null) {
                    names.add(info.getThreadName());
                }
            }
        }
        return new Result(false, names);
    }
}
