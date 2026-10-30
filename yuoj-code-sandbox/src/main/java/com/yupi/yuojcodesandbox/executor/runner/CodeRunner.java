package com.yupi.yuojcodesandbox.executor.runner;

import com.yupi.yuojcodesandbox.executor.language.LanguageSpec;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import java.nio.file.Path;
import java.util.List;

public interface CodeRunner {
    List<ExecuteMessage> run(Path workspace, LanguageSpec language, List<String> inputs) throws Exception;
}
