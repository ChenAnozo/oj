package com.yupi.yuojcodesandbox.executor;

import com.yupi.yuojcodesandbox.enums.JudgeInfoMessageEnum;
import com.yupi.yuojcodesandbox.executor.language.LanguageSpec;
import com.yupi.yuojcodesandbox.executor.runner.CodeRunner;
import com.yupi.yuojcodesandbox.executor.support.CodeWorkspace;
import com.yupi.yuojcodesandbox.executor.support.ExecutionResponseAssembler;
import com.yupi.yuojcodesandbox.executor.support.NativeProcessExecutor;
import com.yupi.yuojcodesandbox.executor.support.SandboxTiming;
import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Common lifecycle; language rules and execution environments are composed. */
public abstract class AbstractCodeSandboxExecutor implements CodeSandboxExecutor {
    private static final Logger log = LoggerFactory.getLogger(AbstractCodeSandboxExecutor.class);
    private final LanguageSpec language;
    private final CodeRunner runner;
    private final CodeWorkspace workspaces;
    private final NativeProcessExecutor processes;
    private final ExecutionResponseAssembler responses;

    protected AbstractCodeSandboxExecutor(LanguageSpec language, CodeRunner runner, CodeWorkspace workspaces,
                                          NativeProcessExecutor processes, ExecutionResponseAssembler responses) {
        this.language = language;
        this.runner = runner;
        this.workspaces = workspaces;
        this.processes = processes;
        this.responses = responses;
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        Path workspacePath = null;
        ExecuteCodeResponse response = null;
        long started = System.nanoTime();
        SandboxTiming totalTiming = null;
        JudgeInfoMessageEnum stage = JudgeInfoMessageEnum.COMPILE_ERROR;
        try {
            workspacePath = workspaces.create();
            totalTiming = new SandboxTiming(workspacePath, "submission_total");
            log.info("[沙箱任务 {}] 开始，语言={}，执行器={}，用例数={}", workspacePath.getFileName(),
                    request.getLanguage(), getClass().getSimpleName(), request.getInputList().size());
            try (SandboxTiming timing = new SandboxTiming(workspacePath, "source_save")) {
                workspaces.save(workspacePath, language.sourceFileName(), request.getCode());
            }
            log.info("[沙箱任务 {}] 代码已保存，文件={}", workspacePath.getFileName(), workspacePath.resolve(language.sourceFileName()));
            log.info("[沙箱任务 {}] 开始编译，超时限制={} ms", workspacePath.getFileName(), NativeProcessExecutor.COMPILE_TIMEOUT_MS);
            ExecuteMessage compiled;
            try (SandboxTiming timing = new SandboxTiming(workspacePath, "compile")) {
                compiled = processes.execute(language.compileCommand(workspacePath), workspacePath,
                        NativeProcessExecutor.COMPILE_TIMEOUT_MS);
            }
            if (compiled.getExitValue() == null) {
                log.warn("[沙箱任务 {}] 编译超时，耗时={} ms", workspacePath.getFileName(), compiled.getTime());
                return response = responses.error("Compilation timed out after 30000 ms", stage);
            }
            if (compiled.getExitValue() != 0) {
                log.warn("[沙箱任务 {}] 编译失败，退出码={}", workspacePath.getFileName(), compiled.getExitValue());
                return response = responses.error("编译失败: " + compiled.getErrorMessage(), stage);
            }
            log.info("[沙箱任务 {}] 编译完成，耗时={} ms", workspacePath.getFileName(), compiled.getTime());
            stage = JudgeInfoMessageEnum.RUNTIME_ERROR;
            try (SandboxTiming timing = new SandboxTiming(workspacePath, "runner_total")) {
                response = responses.assemble(runner.run(workspacePath, language, request.getInputList()));
            }
            log.info("[沙箱任务 {}] 结果汇总，状态={}，最大运行耗时={} ms，最大内存={} KB",
                    workspacePath.getFileName(), response.getStatus(), response.getJudgeInfo().getTime(),
                    response.getJudgeInfo().getMemory());
            return response;
        } catch (Exception e) {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            log.warn("Sandbox execution failed at {}: {}", stage, workspacePath, e);
            return response = responses.error(e.getMessage(), stage);
        } finally {
            try (SandboxTiming timing = new SandboxTiming(workspacePath, "workspace_cleanup")) {
                workspaces.cleanup(workspacePath);
            }
            catch (Exception e) { log.warn("Workspace cleanup failed: {}", workspacePath, e); }
            if (totalTiming != null) { totalTiming.close(); }
            log.info("[沙箱任务 {}] 结束，状态={}，总耗时={} ms",
                    workspacePath == null ? "未创建工作目录" : workspacePath.getFileName(),
                    response == null ? null : response.getStatus(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
}
