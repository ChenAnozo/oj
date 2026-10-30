package com.yupi.yuojcodesandbox.executor.runner;

/** Per-executor Docker policy, independent of language command rules. */
public final class DockerExecutionConfig {
    public static final String WORKSPACE = "/app";
    private final String image;
    private final long timeoutMs;
    public DockerExecutionConfig(String image, long timeoutMs) {
        this.image = image;
        this.timeoutMs = timeoutMs;
    }
    public String getImage() { return image; }
    public long getTimeoutMs() { return timeoutMs; }
    public long getMemoryBytes() { return 100_000_000L; }
    public long getMemorySwap() { return 0L; }
    public long getCpuCount() { return 1L; }
}
