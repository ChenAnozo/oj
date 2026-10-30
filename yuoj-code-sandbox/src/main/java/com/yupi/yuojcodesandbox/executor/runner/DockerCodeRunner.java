package com.yupi.yuojcodesandbox.executor.runner;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.yupi.yuojcodesandbox.executor.language.LanguageSpec;
import com.yupi.yuojcodesandbox.executor.support.CodeWorkspace;
import com.yupi.yuojcodesandbox.executor.support.SandboxTiming;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DockerCodeRunner implements CodeRunner {
    private static final Logger log = LoggerFactory.getLogger(DockerCodeRunner.class);
    private final DockerClient client;
    private final DockerExecutionConfig config;
    private final CodeWorkspace workspaces;

    public DockerCodeRunner(DockerClient client, DockerExecutionConfig config, CodeWorkspace workspaces) {
        this.client = client;
        this.config = config;
        this.workspaces = workspaces;
    }

    @Override
    public List<ExecuteMessage> run(Path workspacePath, LanguageSpec language, List<String> inputs) throws Exception {
        List<ExecuteMessage> results = new ArrayList<>();
        String container = null;
        try {
            for (String input : inputs) {
                if (container == null) { container = createContainer(workspacePath); }
                int caseNumber = results.size() + 1;
                log.info("[沙箱任务 {}] Docker 用例 {}/{} 开始，容器={}",
                        workspacePath.getFileName(), caseNumber, inputs.size(), container);
                Path inputPath;
                try (SandboxTiming timing = new SandboxTiming(workspacePath, "input_prepare")) {
                    inputPath = workspaces.createInputFile(workspacePath, input);
                }
                try {
                    ExecuteMessage result = execute(container, language, inputPath);
                    results.add(result);
                    log.info("[沙箱任务 {}] Docker 用例 {}/{} 完成，退出码={}，超时={}，耗时={} ms，内存={} KB",
                            workspacePath.getFileName(), caseNumber, inputs.size(), result.getExitValue(),
                            result.getExitValue() == null, result.getTime(), result.getMemory() / 1024);
                    if (result.getExitValue() == null) {
                        // Terminate the program before deleting its input or starting another case.
                        remove(container, workspacePath);
                        container = null;
                    }
                } catch (Exception | Error e) {
                    cleanupContainer(container, workspacePath);
                    container = null;
                    throw e;
                } finally {
                    try (SandboxTiming timing = new SandboxTiming(workspacePath, "input_cleanup")) {
                        workspaces.cleanupInputFile(inputPath);
                    }
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (container != null) { cleanupContainer(container, workspacePath); }
        }
    }

    private String createContainer(Path workspacePath) {
        HostConfig host = new HostConfig()
                .withBinds(new Bind(workspaces.dockerBindPath(workspacePath).toString(), new Volume(DockerExecutionConfig.WORKSPACE)))
                .withMemory(config.getMemoryBytes()).withMemorySwap(config.getMemorySwap())
                .withCpuCount(config.getCpuCount());
        String container;
        try (SandboxTiming timing = new SandboxTiming(workspacePath, "container_create")) {
            container = client.createContainerCmd(config.getImage()).withHostConfig(host)
                .withCmd("sh", "-c", "while :; do sleep 3600; done")
                .withAttachStdin(true).withAttachStdout(true).withAttachStderr(true)
                .withTty(true).withNetworkDisabled(true).withReadonlyRootfs(true).exec().getId();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            // An image can be deleted after startup; report the failure without auto-pulling or retrying.
            throw new IllegalStateException("Docker image unavailable during container creation: " + config.getImage(), e);
        }
        try {
            try (SandboxTiming timing = new SandboxTiming(workspacePath, "container_start")) {
                client.startContainerCmd(container).exec();
            }
            log.info("[沙箱任务 {}] 容器已启动，容器={}，镜像={}", workspacePath.getFileName(), container, config.getImage());
            return container;
        } catch (RuntimeException e) {
            cleanupContainer(container, workspacePath);
            throw e;
        }
    }

    private ExecuteMessage execute(String container, LanguageSpec language, Path inputPath) throws Exception {
        AtomicLong maxMemory = new AtomicLong();
        StringBuffer stdout = new StringBuffer();
        StringBuffer stderr = new StringBuffer();
        // Adapter owns the underlying HTTP stream; close() cancels the subscription.
        try (ResultCallback.Adapter<Statistics> stats = new ResultCallback.Adapter<Statistics>() {
            public void close() {
                try (SandboxTiming timing = new SandboxTiming(inputPath.getParent(), "stats_stream_close")) { super.close(); }
                catch (Exception e) { log.warn("Stats subscription cleanup failed: {}", container, e); }
            }
            public void onNext(Statistics sample) {
                if (sample.getMemoryStats() != null && sample.getMemoryStats().getUsage() != null) {
                    maxMemory.accumulateAndGet(sample.getMemoryStats().getUsage(), Math::max);//原子化
                }
            }
            public void onError(Throwable error) { log.debug("Memory sampling ended: {}", container, error); }
        }; ExecStartResultCallback output = new ExecStartResultCallback() {
            public void close() {
                try (SandboxTiming timing = new SandboxTiming(inputPath.getParent(), "output_stream_close")) { super.close(); }
                catch (Exception e) { log.warn("Output subscription cleanup failed: {}", container, e); }
            }
            public void onNext(Frame frame) {
                String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (frame.getStreamType() == StreamType.STDERR) { stderr.append(text); }
                else { stdout.append(text); }
            }
        }) {
            com.github.dockerjava.api.command.StatsCmd command = client.statsCmd(container);
            try {
                try (SandboxTiming timing = new SandboxTiming(inputPath.getParent(), "stats_open")) {
                    command.exec(stats);
                }
                // Only the fixed shell script is interpreted; user input remains file content.
                List<String> args = new ArrayList<>(Arrays.asList("sh", "-c", "exec \"$@\" < \"$0\"",
                        DockerExecutionConfig.WORKSPACE + "/" + inputPath.getFileName()));
                args.addAll(language.containerRunCommand(DockerExecutionConfig.WORKSPACE));
                ExecCreateCmdResponse exec;
                try (SandboxTiming timing = new SandboxTiming(inputPath.getParent(), "exec_create")) {
                    exec = client.execCreateCmd(container).withCmd(args.toArray(new String[0]))
                        .withTty(false).withAttachStdin(false).withAttachStdout(true).withAttachStderr(true).exec();
                }
                long start = System.nanoTime();
                boolean completed;
                long elapsed;
                try (SandboxTiming timing = new SandboxTiming(inputPath.getParent(), "exec_run")) {
                    completed = client.execStartCmd(exec.getId()).withTty(false).exec(output)
                        .awaitCompletion(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
                    elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }
                ExecuteMessage result = new ExecuteMessage();
                result.setTime(completed ? elapsed : Math.max(config.getTimeoutMs(), elapsed));
                result.setMessage(stdout.toString().trim());
                result.setErrorMessage(stderr.toString().trim());
                result.setMemory(maxMemory.get());
                if (completed) {
                    Long exit;
                    try (SandboxTiming timing = new SandboxTiming(inputPath.getParent(), "exec_inspect")) {
                        exit = client.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
                    }
                    if (exit == null) { throw new IllegalStateException("Docker execution has no exit code: " + exec.getId()); }
                    result.setExitValue(exit.intValue());
                }
                return result;
            } finally {
                try (SandboxTiming timing = new SandboxTiming(inputPath.getParent(), "stats_command_close")) { command.close(); }
                catch (Exception e) { log.warn("Stats command cleanup failed: {}", container, e); }
            }
        }
    }

    private void remove(String container, Path workspacePath) {
        try (SandboxTiming timing = new SandboxTiming(workspacePath, "container_remove")) {
            client.removeContainerCmd(container).withForce(true).exec();
        }
        log.info("容器已清理，容器={}", container);
    }

    private void cleanupContainer(String container, Path workspacePath) {
        try { remove(container, workspacePath); }
        catch (Exception e) { log.warn("Container cleanup failed: {}", container, e); }
    }
}
