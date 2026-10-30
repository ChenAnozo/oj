package com.yupi.yuojcodesandbox.executor;

import com.yupi.yuojcodesandbox.executor.support.NativeProcessExecutor;
import com.yupi.yuojcodesandbox.executor.language.*;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeProcessExecutorTest {
    @TempDir Path root;
    final NativeProcessExecutor executor = new NativeProcessExecutor();

    List<String> command(String mode, String... args) {
        String executable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Paths.get("target/test-classes").toAbsolutePath().toString();
        List<String> command = new ArrayList<>(Arrays.asList(executable, "-cp", classpath, Fixture.class.getName(), mode));
        command.addAll(Arrays.asList(args));
        return command;
    }

    @Test void handlesSpacePathsAndDrainsBothStreams() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("with spaces"));
        ExecuteMessage result = executor.execute(command("output", "argument with spaces"), workspace, 5000);
        assertEquals(0, result.getExitValue());
        assertTrue(result.getMessage().startsWith("\nargument with spaces"));
        assertTrue(result.getMessage().endsWith("out9999"));
        assertTrue(result.getErrorMessage().endsWith("err9999"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1 2", "1  2\n3\t4\n", "  中文\r\n\n尾行  ", "\n\n"})
    void stdinPreservesExactBytesAndDeliversEof(String input) throws Exception {
        ExecuteMessage result = executor.execute(command("echo"), root, 5000, input);
        assertEquals(0, result.getExitValue());
        assertEquals(Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8)), result.getMessage());
        assertIoThreadsStopped();
    }

    String largeInput() {
        char[] chars = new char[2 * 1024 * 1024];
        Arrays.fill(chars, 'x');
        return new String(chars);
    }

    @Test void largeInputAndOutputDoNotDeadlock() throws Exception {
        String input = largeInput();
        ExecuteMessage result = executor.execute(command("duplex"), root, 10_000, input);
        assertEquals(0, result.getExitValue());
        assertTrue(result.getMessage().endsWith("bytes=" + input.length()));
        assertTrue(result.getErrorMessage().endsWith("err49999"));
        assertIoThreadsStopped();
    }

    @Test void programNotReadingLargeInputStillTimesOut() throws Exception {
        Path marker = root.resolve("pid");
        long start = System.nanoTime();
        ExecuteMessage result = executor.execute(command("sleep", marker.toString()), root, 1000, largeInput());
        assertNull(result.getExitValue());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 6);
        assertDead(marker);
        assertIoThreadsStopped();
    }

    @Test void earlyExitPreservesCodeDespiteUnconsumedInput() throws Exception {
        ExecuteMessage result = executor.execute(command("exit"), root, 5000, largeInput());
        assertEquals(7, result.getExitValue());
        assertIoThreadsStopped();
    }

    void assertIoThreadsStopped() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(thread -> thread.isAlive() && thread.getName().equals("sandbox-io"))) { return; }
            Thread.sleep(10);
        }
        fail("Sandbox I/O tasks survived process cleanup");
    }

    @Test void timeoutKillsProcess() throws Exception {
        Path marker = root.resolve("pid");
        ExecuteMessage result = executor.execute(command("sleep", marker.toString()), root, 1000);
        assertNull(result.getExitValue());
        assertTrue(result.getTime() >= 1000);
        assertDead(marker);
    }

    @Test void interruptRestoresFlagAndKillsProcess() throws Exception {
        Path marker = root.resolve("pid");
        AtomicBoolean restored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { executor.execute(command("sleep", marker.toString()), root, 10_000, largeInput()); }
            catch (InterruptedException e) { restored.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(marker) && System.nanoTime() < deadline) { Thread.sleep(10); }
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertTrue(restored.get());
        assertDead(marker);
        assertIoThreadsStopped();
    }

    @Test void timeoutKillsIdentifiableChildProcess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("java.version").startsWith("1.8"));
        Path parent = root.resolve("parent");
        Path child = root.resolve("child");
        ExecuteMessage result = executor.execute(command("child", parent.toString(), child.toString()), root, 1500);
        assertNull(result.getExitValue());
        assertTrue(Files.exists(parent));
        assertTrue(Files.exists(child));
        assertDead(parent);
        assertDead(child);
    }

    void assertDead(Path marker) throws Exception {
        if (System.getProperty("java.version").startsWith("1.8")) { return; }
        if (Files.exists(marker)) {
            String pid = new String(Files.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8);
            Class<?> type = Class.forName("java.lang.ProcessHandle");
            Optional<?> handle = (Optional<?>) type.getMethod("of", long.class).invoke(null, Long.parseLong(pid));
            if (handle.isPresent()) { assertEquals(false, type.getMethod("isAlive").invoke(handle.get())); }
        }
    }

    @Test void languageRulesKeepEnvironmentSpecificCommands() {
        Path workspace = root.resolve("with spaces");
        LanguageSpec java = new JavaLanguageSpec();
        assertTrue(java.nativeRunCommand(workspace).contains("-Xmx256m"));
        assertEquals(Arrays.asList("java", "-Xmx80m", "-cp", "/app", "Main"), java.containerRunCommand("/app"));
        assertEquals(Arrays.asList("/app/Main"), new CppLanguageSpec().containerRunCommand("/app"));
        assertTrue(new CppLanguageSpec().compileCommand(workspace).contains(workspace.resolve("Main.cpp").toString()));
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("exit")) {
                System.exit(7);
            } else if (args[0].equals("echo") || args[0].equals("duplex")) {
                if (args[0].equals("duplex")) {
                    for (int i = 0; i < 50_000; i++) {
                        System.out.println("out" + i);
                        System.err.println("err" + i);
                    }
                }
                java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = System.in.read(buffer)) != -1) { data.write(buffer, 0, count); }
                if (args[0].equals("echo")) {
                    System.out.print(Base64.getEncoder().encodeToString(data.toByteArray()));
                } else { System.out.println("bytes=" + data.size()); }
            } else if (args[0].equals("sleep") || args[0].equals("child")) {
                String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
                Files.write(Paths.get(args[1]), pid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (args[0].equals("child")) {
                    String executable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
                    new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                            Fixture.class.getName(), "sleep", args[2]).inheritIO().start();
                }
                Thread.sleep(60_000);
            } else {
                System.out.println();
                System.out.println(args[1]);
                for (int i = 0; i < 10_000; i++) {
                    System.out.println("out" + i);
                    System.err.println("err" + i);
                }
            }
        }
    }
}
