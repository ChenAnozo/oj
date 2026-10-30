package com.yupi.yuojcodesandbox.executor.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Wall-clock diagnostics only; does not change verdict timing or deadlines. */
public final class SandboxTiming implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SandboxTiming.class);
    private final String task;
    private final String stage;
    private final long started = System.nanoTime();

    public SandboxTiming(Path workspace, String stage) {
        this.task = workspace == null ? "unassigned" : workspace.getFileName().toString();
        this.stage = stage;
    }

    @Override
    public void close() {
        log.info("sandbox_timing task={} stage={} elapsed_us={}", task, stage,
                TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
    }
}
