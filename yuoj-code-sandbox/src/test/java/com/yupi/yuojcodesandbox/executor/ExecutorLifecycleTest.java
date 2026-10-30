package com.yupi.yuojcodesandbox.executor;

import com.yupi.yuojcodesandbox.executor.language.JavaLanguageSpec;
import com.yupi.yuojcodesandbox.executor.runner.CodeRunner;
import com.yupi.yuojcodesandbox.executor.runner.NativeCodeRunner;
import com.yupi.yuojcodesandbox.executor.impl.JavaNativeCodeSandboxExecutor;
import com.yupi.yuojcodesandbox.executor.impl.CppNativeCodeSandboxExecutor;
import com.yupi.yuojcodesandbox.executor.support.*;
import com.yupi.yuojcodesandbox.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutorLifecycleTest {
    @TempDir Path root;
    CodeWorkspace workspaces;
    NativeProcessExecutor processes;
    CodeRunner runner;
    Path workspace;
    ExecutionResponseAssembler responses;

    @BeforeEach void setUp() throws Exception {
        workspace = Files.createDirectory(root.resolve("code workspace"));
        workspaces = spy(new CodeWorkspace());
        doReturn(workspace).when(workspaces).create();
        processes = mock(NativeProcessExecutor.class);
        runner = mock(CodeRunner.class);
        responses = spy(new ExecutionResponseAssembler());
        ExecuteMessage compile = message(0, "", "", 1L, null);
        when(processes.execute(anyList(), eq(workspace), eq(30_000L))).thenReturn(compile);
        when(runner.run(eq(workspace), any(), anyList()))
                .thenReturn(Collections.singletonList(message(0, "3", "", 12L, 2048L)));
    }

    AbstractCodeSandboxExecutor executor() {
        return new AbstractCodeSandboxExecutor(new JavaLanguageSpec(), runner, workspaces, processes, responses) {};
    }
    ExecuteCodeRequest request() {
        return ExecuteCodeRequest.builder().code("class Main {}").inputList(Arrays.asList("1 2")).build();
    }

    @Test void successCleansWorkspaceAndUsesArgumentList() throws Exception {
        ExecuteCodeResponse result = executor().executeCode(request());
        assertEquals(Arrays.asList("3"), result.getOutputList());
        assertEquals(2L, result.getJudgeInfo().getMemory());
        assertFalse(Files.exists(workspace));
        verify(processes).execute(Arrays.asList("javac", "-encoding", "utf-8",
                workspace.resolve("Main.java").toString()), workspace, 30_000L);
    }

    @Test void realJavaCompilationAndExecutionInSpacePath() {
        NativeProcessExecutor realProcesses = new NativeProcessExecutor();
        JavaNativeCodeSandboxExecutor executor = new JavaNativeCodeSandboxExecutor(
                new NativeCodeRunner(realProcesses), workspaces, realProcesses, new ExecutionResponseAssembler());
        ExecuteCodeRequest request = ExecuteCodeRequest.builder().language("java")
                .code("public class Main { public static void main(String[] args) { "
                        + "if (args.length != 0) throw new IllegalStateException(\"Unexpected arguments\"); "
                        + "java.util.Scanner in = new java.util.Scanner(System.in); "
                        + "System.out.println(in.nextInt() + in.nextInt()); } }")
                .inputList(Arrays.asList("1 2", "1 3")).build();
        ExecuteCodeResponse result = executor.executeCode(request);
        assertEquals(2, result.getStatus(), result.getMessage());
        assertEquals(Arrays.asList("3", "4"), result.getOutputList());
        assertFalse(Files.exists(workspace));
    }

    @Test void realJavaReadsUtf8MultilineInputUntilEof() {
        NativeProcessExecutor realProcesses = new NativeProcessExecutor();
        JavaNativeCodeSandboxExecutor executor = new JavaNativeCodeSandboxExecutor(
                new NativeCodeRunner(realProcesses), workspaces, realProcesses, new ExecutionResponseAssembler());
        List<String> inputs = Arrays.asList("", "  中文\r\n\n末行", "1  2\n3\t4\n");
        String source = "public class Main { public static void main(String[] args) throws Exception { "
                + "if (args.length != 0) throw new IllegalStateException(); "
                + "java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); "
                + "byte[] buffer = new byte[1024]; int count; "
                + "while ((count = System.in.read(buffer)) != -1) bytes.write(buffer, 0, count); "
                + "System.out.print(java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())); } }";
        ExecuteCodeResponse result = executor.executeCode(ExecuteCodeRequest.builder()
                .language("java").code(source).inputList(inputs).build());
        assertEquals(2, result.getStatus(), result.getMessage());
        List<String> expected = new ArrayList<>();
        for (String input : inputs) {
            expected.add(Base64.getEncoder().encodeToString(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        assertEquals(expected, result.getOutputList());
        assertFalse(Files.exists(workspace));
    }

    @Test void realCppCinReadsUntilEofWithoutArguments() throws Exception {
        NativeProcessExecutor realProcesses = new NativeProcessExecutor();
        try {
            ExecuteMessage probe = realProcesses.execute(Arrays.asList("g++", "--version"), root, 5000);
            Assumptions.assumeTrue(probe.getExitValue() != null && probe.getExitValue() == 0, "g++ unavailable");
        } catch (java.io.IOException e) { Assumptions.assumeTrue(false, "g++ unavailable"); }
        CppNativeCodeSandboxExecutor executor = new CppNativeCodeSandboxExecutor(
                new NativeCodeRunner(realProcesses), workspaces, realProcesses, new ExecutionResponseAssembler());
        String source = "#include <iostream>\nint main(int argc, char** argv) { "
                + "if (argc != 1) return 13; int n, sum = 0; "
                + "while (std::cin >> n) sum += n; std::cout << sum; }";
        ExecuteCodeResponse result = executor.executeCode(ExecuteCodeRequest.builder()
                .language("cpp").code(source).inputList(Arrays.asList("1  2\n3\t4", "")).build());
        assertEquals(2, result.getStatus(), result.getMessage());
        assertEquals(Arrays.asList("10", "0"), result.getOutputList());
        assertFalse(Files.exists(workspace));
    }

    @Test void compileFailureCleansWorkspaceWithoutRunning() throws Exception {
        when(processes.execute(anyList(), any(), anyLong())).thenReturn(message(1, "", "invalid source", 1L, null));
        ExecuteCodeResponse response = executor().executeCode(request());
        assertEquals(3, response.getStatus());
        assertTrue(response.getMessage().contains("invalid source"));
        assertFalse(Files.exists(workspace));
        verifyNoInteractions(runner);
    }

    @Test void compileTimeoutCleansWorkspace() throws Exception {
        when(processes.execute(anyList(), any(), anyLong())).thenReturn(message(null, "", "", 30_000L, null));
        assertTrue(executor().executeCode(request()).getMessage().contains("timed out"));
        assertFalse(Files.exists(workspace));
        verifyNoInteractions(runner);
    }

    @Test void compileExceptionCleansWorkspace() throws Exception {
        when(processes.execute(anyList(), any(), anyLong())).thenThrow(new java.io.IOException("compiler absent"));
        assertEquals("compiler absent", executor().executeCode(request()).getMessage());
        assertFalse(Files.exists(workspace));
    }

    @Test void saveFailureCleansWorkspace() throws Exception {
        doThrow(new java.io.IOException("disk full")).when(workspaces).save(any(), anyString(), anyString());
        assertEquals("disk full", executor().executeCode(request()).getMessage());
        assertFalse(Files.exists(workspace));
    }

    @Test void runFailureCleansWorkspace() throws Exception {
        when(runner.run(any(), any(), anyList())).thenThrow(new IllegalStateException("container failed"));
        assertEquals("container failed", executor().executeCode(request()).getMessage());
        assertFalse(Files.exists(workspace));
    }

    @Test void assemblyFailureCleansWorkspace() {
        doThrow(new IllegalStateException("assembly failed")).when(responses).assemble(anyList());
        assertEquals("assembly failed", executor().executeCode(request()).getMessage());
        assertFalse(Files.exists(workspace));
    }

    @Test void assemblerKeepsTimeoutAndRuntimeErrorContract() {
        ExecuteCodeResponse timeout = responses.assemble(Arrays.asList(message(null, "", "", 5000L, 4096L)));
        assertEquals(2, timeout.getStatus());
        assertEquals(Arrays.asList(""), timeout.getOutputList());
        assertEquals(5000L, timeout.getJudgeInfo().getTime());
        ExecuteCodeResponse failure = responses.assemble(Arrays.asList(message(1, "", "exception", 2L, 0L)));
        assertEquals(3, failure.getStatus());
        assertNull(failure.getOutputList());
        assertEquals("exception", failure.getMessage());
    }

    @Test void cleanupFailureDoesNotReplaceResult() {
        doThrow(new IllegalStateException("cleanup failed")).when(workspaces).cleanup(workspace);
        assertEquals(Arrays.asList("3"), executor().executeCode(request()).getOutputList());
    }

    @Test void cleanupRemovesNestedArtifactsAndToleratesMissingDirectory() throws Exception {
        CodeWorkspace failingCleanup = new CodeWorkspace() {
            @Override public Path create() { return workspace; }
        };
        // A non-empty directory passed to cleanup is recursively removed, including nested artifacts.
        Files.createDirectories(workspace.resolve("nested"));
        Files.write(workspace.resolve("nested/artifact"), new byte[]{1});
        failingCleanup.cleanup(workspace);
        failingCleanup.cleanup(workspace); // Missing directory is logged, never propagated.
        assertFalse(Files.exists(workspace));
    }

    static ExecuteMessage message(Integer exit, String out, String err, Long time, Long memory) {
        ExecuteMessage value = new ExecuteMessage();
        value.setExitValue(exit); value.setMessage(out); value.setErrorMessage(err);
        value.setTime(time); value.setMemory(memory);
        return value;
    }
}
