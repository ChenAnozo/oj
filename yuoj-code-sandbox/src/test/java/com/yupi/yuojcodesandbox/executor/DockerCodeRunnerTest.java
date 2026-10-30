package com.yupi.yuojcodesandbox.executor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.yupi.yuojcodesandbox.executor.language.*;
import com.yupi.yuojcodesandbox.executor.runner.*;
import com.yupi.yuojcodesandbox.executor.support.CodeWorkspace;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DockerCodeRunnerTest {
    @TempDir Path root;
    Path workspacePath;
    CodeWorkspace workspaces;
    List<String> observedInputs;
    DockerClient client;
    CreateContainerCmd create;
    StartContainerCmd start;
    RemoveContainerCmd remove;
    StatsCmd stats;
    ExecCreateCmd execCreate;
    ExecStartCmd execStart;
    Closeable statsStream, outputStream;
    AtomicInteger calls;

    @BeforeEach void setUp() throws Exception {
        workspacePath = Files.createDirectory(root.resolve("workspace with spaces"));
        workspaces = spy(new CodeWorkspace());
        observedInputs = new ArrayList<>();
        client = mock(DockerClient.class);
        create = mock(CreateContainerCmd.class, RETURNS_SELF);
        start = mock(StartContainerCmd.class, RETURNS_SELF);
        remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        stats = mock(StatsCmd.class, RETURNS_SELF);
        execCreate = mock(ExecCreateCmd.class, RETURNS_SELF);
        execStart = mock(ExecStartCmd.class, RETURNS_SELF);
        statsStream = mock(Closeable.class);
        outputStream = mock(Closeable.class);
        calls = new AtomicInteger();
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        when(client.inspectImageCmd(anyString())).thenReturn(inspectImage);
        when(client.createContainerCmd(anyString())).thenReturn(create);
        CreateContainerResponse first = new CreateContainerResponse(); first.setId("container1");
        CreateContainerResponse second = new CreateContainerResponse(); second.setId("container2");
        when(create.exec()).thenReturn(first, second);
        when(client.startContainerCmd(anyString())).thenReturn(start);
        when(client.removeContainerCmd(anyString())).thenReturn(remove);
        when(client.statsCmd(anyString())).thenReturn(stats);
        doAnswer(call -> {
            ResultCallback<Statistics> callback = call.getArgument(0);
            callback.onStart(statsStream);
            return callback;
        }).when(stats).exec(any());
        when(client.execCreateCmd(anyString())).thenReturn(execCreate);
        ExecCreateCmdResponse execResponse = mock(ExecCreateCmdResponse.class);
        when(execResponse.getId()).thenReturn("exec");
        doReturn(execResponse).when(execCreate).exec();
        when(client.execStartCmd(anyString())).thenReturn(execStart);
        doAnswer(call -> {
            try (java.util.stream.Stream<Path> files = Files.list(workspacePath)) {
                Path inputPath = files.filter(path -> path.getFileName().toString().startsWith(".stdin-"))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing stdin file"));
                observedInputs.add(new String(Files.readAllBytes(inputPath), StandardCharsets.UTF_8));
            }
            ExecStartResultCallback callback = call.getArgument(0);
            callback.onStart(outputStream);
            callback.onNext(new Frame(StreamType.STDOUT, " 3 \n".getBytes(StandardCharsets.UTF_8)));
            callback.onNext(new Frame(StreamType.STDERR, " warning \n".getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        }).when(execStart).exec(any(ExecStartResultCallback.class));
        InspectExecCmd inspect = mock(InspectExecCmd.class);
        when(client.inspectExecCmd(anyString())).thenReturn(inspect);
        InspectExecResponse state = mock(InspectExecResponse.class);
        when(state.getExitCodeLong()).thenReturn(0L);
        when(inspect.exec()).thenReturn(state);
    }

    List<ExecuteMessage> run(LanguageSpec language, DockerExecutionConfig config, String... inputs) throws Exception {
        CodeRunner runner = new DockerCodeRunner(client, config, workspaces);
        List<ExecuteMessage> results = runner.run(workspacePath, language, Arrays.asList(inputs));
        assertInputsCleaned();
        return results;
    }

    void assertInputsCleaned() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(workspacePath)) {
            assertEquals(0, files.count());
        }
    }

    void verifyWrappedCommands(String... program) {
        List<String[]> commands = mockingDetails(execCreate).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("withCmd"))
                .map(call -> (String[]) call.getRawArguments()[0])
                .collect(java.util.stream.Collectors.toList());
        assertFalse(commands.isEmpty());
        for (String[] command : commands) {
            assertEquals(Arrays.asList("sh", "-c", "exec \"$@\" < \"$0\""), Arrays.asList(command).subList(0, 3));
            assertTrue(command[3].startsWith("/app/.stdin-"));
            assertEquals(Arrays.asList(program), Arrays.asList(command).subList(4, command.length));
        }
        verify(execCreate, atLeastOnce()).withTty(false);
        verify(execCreate, atLeastOnce()).withAttachStdin(false);
        verify(execStart, atLeastOnce()).withTty(false);
    }

    @Test void sharesLifecycleAndCollectsSeparateOutputs() throws Exception {
        List<ExecuteMessage> result = run(new JavaLanguageSpec(), new DockerExecutionConfig("openjdk:8-alpine", 5000), "1 2", "1 3");
        assertEquals(2, result.size());
        assertEquals("3", result.get(0).getMessage());
        assertEquals("warning", result.get(0).getErrorMessage());
        assertEquals(0, result.get(0).getExitValue());
        verify(client, times(1)).createContainerCmd("openjdk:8-alpine");
        verify(client, never()).inspectImageCmd(anyString());
        verifyWrappedCommands("java", "-Xmx80m", "-cp", "/app", "Main");
        assertEquals(Arrays.asList("1 2", "1 3"), observedInputs);
        verify(stats, times(2)).close();
        verify(statsStream, atLeast(2)).close();
        verify(outputStream, atLeast(2)).close();
        verify(client).removeContainerCmd("container1");
        verify(remove).withForce(true);
        ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(host.capture());
        assertEquals(100_000_000L, host.getValue().getMemory());
        assertEquals("/app", host.getValue().getBinds()[0].getVolume().getPath());
    }

    @Test void cppUsesSameRunnerWithDifferentCommandAndImage() throws Exception {
        run(new CppLanguageSpec(), new DockerExecutionConfig("gcc:latest", 10000), "1 2");
        verify(client).createContainerCmd("gcc:latest");
        verifyWrappedCommands("/app/Main");
    }

    @Test void preservesUtf8WhitespaceEmptyInputAndShellCharacters() throws Exception {
        String input = "  中文\r\n\n a  b\t ' \" $() ; & < >";
        run(new JavaLanguageSpec(), new DockerExecutionConfig("java", 5000), input, "");
        assertEquals(Arrays.asList(input, ""), observedInputs);
        verifyWrappedCommands("java", "-Xmx80m", "-cp", "/app", "Main");
    }

    @Test void memorySamplingDoesNotLeakBetweenCasesOrRequests() throws Exception {
        doAnswer(call -> {
            ResultCallback<Statistics> callback = call.getArgument(0);
            callback.onStart(statsStream);
            Statistics sample = mock(Statistics.class, RETURNS_DEEP_STUBS);
            when(sample.getMemoryStats().getUsage()).thenReturn(calls.getAndIncrement() == 0 ? 4096L : 1024L);
            callback.onNext(sample);
            return callback;
        }).when(stats).exec(any());
        List<ExecuteMessage> first = run(new JavaLanguageSpec(), new DockerExecutionConfig("java", 5000), "1", "2");
        assertEquals(4096L, first.get(0).getMemory());
        assertEquals(1024L, first.get(1).getMemory());
        assertEquals(1024L, run(new JavaLanguageSpec(), new DockerExecutionConfig("java", 5000), "3").get(0).getMemory());
    }

    @Test void timeoutRemovesContainerBeforeNextCaseAndResetsOutput() throws Exception {
        doAnswer(call -> {
            ExecStartResultCallback callback = call.getArgument(0);
            callback.onStart(outputStream);
            if (calls.getAndIncrement() > 0) {
                callback.onNext(new Frame(StreamType.STDOUT, "second".getBytes(StandardCharsets.UTF_8)));
                callback.onComplete();
            }
            return callback;
        }).when(execStart).exec(any(ExecStartResultCallback.class));
        List<ExecuteMessage> result = run(new JavaLanguageSpec(), new DockerExecutionConfig("java", 1), "1", "2");
        assertNull(result.get(0).getExitValue());
        assertTrue(result.get(0).getTime() >= 1);
        assertEquals("", result.get(0).getMessage());
        assertEquals("second", result.get(1).getMessage());
        org.mockito.InOrder order = inOrder(client, workspaces);
        order.verify(client).createContainerCmd("java");
        order.verify(client).removeContainerCmd("container1");
        order.verify(workspaces).cleanupInputFile(any(Path.class));
        order.verify(client).createContainerCmd("java");
        order.verify(client).removeContainerCmd("container2");
    }

    @Test void executionExceptionClosesSubscriptionsAndRemovesContainer() throws Exception {
        when(execCreate.exec()).thenThrow(new IllegalStateException("execution failed"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> run(new JavaLanguageSpec(), new DockerExecutionConfig("java", 5000), "1"));
        assertEquals("execution failed", failure.getMessage());
        verify(statsStream).close();
        verify(stats).close();
        verify(client).removeContainerCmd("container1");
        assertInputsCleaned();
    }

    @Test void startupFailureRemovesCreatedContainer() {
        doThrow(new IllegalStateException("start failed")).when(start).exec();
        assertThrows(IllegalStateException.class,
                () -> run(new JavaLanguageSpec(), new DockerExecutionConfig("java", 5000), "1"));
        verify(client).removeContainerCmd("container1");
    }

    @Test void cleanupErrorsDoNotReplaceResultOrPrimaryFailure() throws Exception {
        doThrow(new IOException("stream close failed")).when(statsStream).close();
        doThrow(new IllegalStateException("command close failed")).when(stats).close();
        doThrow(new IllegalStateException("remove failed")).when(remove).exec();
        assertEquals("3", run(new JavaLanguageSpec(), new DockerExecutionConfig("java", 5000), "1").get(0).getMessage());
        when(execCreate.exec()).thenThrow(new IllegalStateException("primary failure"));
        assertEquals("primary failure", assertThrows(IllegalStateException.class,
                () -> run(new JavaLanguageSpec(), new DockerExecutionConfig("java", 5000), "1")).getMessage());
    }

    @Test void imageDeletedAfterStartupFailsAtCreationWithoutRetry() {
        when(create.exec()).thenThrow(new NotFoundException("missing"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> run(new JavaLanguageSpec(), new DockerExecutionConfig("missing", 5000), "1"))
                .getMessage().contains("Docker image unavailable"));
        verify(client, never()).inspectImageCmd(anyString());
        verify(create, times(1)).exec();
        verify(client, never()).startContainerCmd(anyString());
        verify(client, never()).removeContainerCmd(anyString());
    }
}
