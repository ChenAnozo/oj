package com.yupi.yuojcodesandbox.executor.runner;

import com.yupi.yuojcodesandbox.executor.language.LanguageSpec;
import com.yupi.yuojcodesandbox.executor.support.NativeProcessExecutor;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.*;

@Component
public class NativeCodeRunner implements CodeRunner {
    private static final Logger log = LoggerFactory.getLogger(NativeCodeRunner.class);
    private final NativeProcessExecutor processes;
    public NativeCodeRunner(NativeProcessExecutor processes) { this.processes = processes; }

    public List<ExecuteMessage> run(Path workspacePath, LanguageSpec language, List<String> inputs) throws Exception {
        List<ExecuteMessage> results = new ArrayList<>();
        for (String input : inputs) {
            int caseNumber = results.size() + 1;
            log.info("[沙箱任务 {}] 本地用例 {}/{} 开始", workspacePath.getFileName(), caseNumber, inputs.size());
            List<String> command = new ArrayList<>(language.nativeRunCommand(workspacePath));
            ExecuteMessage result = processes.execute(command, workspacePath, NativeProcessExecutor.RUN_TIMEOUT_MS, input);
            results.add(result);
            log.info("[沙箱任务 {}] 本地用例 {}/{} 完成，退出码={}，超时={}，耗时={} ms",
                    workspacePath.getFileName(), caseNumber, inputs.size(), result.getExitValue(),
                    result.getExitValue() == null, result.getTime());
        }
        return results;
    }
}
