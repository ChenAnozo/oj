package com.yupi.yuojcodesandbox.executor.language;

import java.nio.file.Path;
import java.util.List;

/** Language rules only; execution and container policy belong to runners. */
public interface LanguageSpec {
    String sourceFileName();
    List<String> compileCommand(Path workspacePath);
    List<String> nativeRunCommand(Path workspacePath);
    List<String> containerRunCommand(String workspacePath);
}
