package com.yupi.yuojcodesandbox.executor.support;

import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Component
public class NativeProcessExecutor {
    private static final Logger log = LoggerFactory.getLogger(NativeProcessExecutor.class);
    public static final long COMPILE_TIMEOUT_MS = 30_000;
    public static final long RUN_TIMEOUT_MS = 5_000;

    public ExecuteMessage execute(List<String> command, Path workspacePath, long timeoutMs) throws Exception {
        return execute(command, workspacePath, timeoutMs, "");
    }

    public ExecuteMessage execute(List<String> command, Path workspacePath, long timeoutMs, String stdin) throws Exception {
        Objects.requireNonNull(stdin, "stdin");
        Process process = new ProcessBuilder(command).directory(workspacePath.toFile()).start();
        ExecutorService streams = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "sandbox-io");
            thread.setDaemon(true);
            return thread;
        });
        long start = System.nanoTime();
        try {
            Future<String> stdout = streams.submit(() -> read(process.getInputStream()));
            Future<String> stderr = streams.submit(() -> read(process.getErrorStream()));
            // Writing must not block the thread responsible for enforcing the timeout.
            Future<Void> inputWriter = streams.submit(() -> {
                try (OutputStream input = process.getOutputStream()) {
                    input.write(stdin.getBytes(StandardCharsets.UTF_8));
                    input.flush();
                }
                return null; // Closing the stream delivers EOF, including for empty input.
            });
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (!completed) { terminate(process); }
            try {
                inputWriter.get(2, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException && !process.isAlive()) {
                    // A program may exit without consuming all input; preserve its exit code.
                    log.debug("Program closed stdin before all input was consumed", e.getCause());
                } else {
                    throw e;
                }
            }
            ExecuteMessage result = new ExecuteMessage();
            result.setExitValue(completed ? process.exitValue() : null);
            result.setTime(completed ? elapsed : Math.max(timeoutMs, elapsed));
            result.setMessage(stdout.get(2, TimeUnit.SECONDS));
            result.setErrorMessage(stderr.get(2, TimeUnit.SECONDS));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            terminate(process);
            close(process.getInputStream());
            close(process.getErrorStream());
            close(process.getOutputStream());
            streams.shutdownNow();
            try { streams.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private String read(InputStream stream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) { output.append('\n'); }
                output.append(line);
                first = false;
            }
        }
        return output.toString();
    }

    private void terminate(Process process) {
        if (!process.isAlive()) { return; }
        // Keep Java 8 source compatibility; use ProcessHandle descendants when available on Java 9+.
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object handle = Process.class.getMethod("toHandle").invoke(process);
            try (Stream<?> descendants = (Stream<?>) handleType.getMethod("descendants").invoke(handle)) {
                descendants.forEach(child -> {
                    try { handleType.getMethod("destroyForcibly").invoke(child); }
                    catch (Exception e) { log.warn("Child process termination failed", e); }
                });
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            log.debug("Process descendants unavailable on this Java runtime");
        } catch (Exception e) {
            log.warn("Process descendants inspection failed", e);
        }
        process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void close(Closeable resource) {
        try { resource.close(); }
        catch (IOException e) { log.debug("Process stream cleanup failed", e); }
    }
}
