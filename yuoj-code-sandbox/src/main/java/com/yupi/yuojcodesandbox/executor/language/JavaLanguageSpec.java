package com.yupi.yuojcodesandbox.executor.language;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class JavaLanguageSpec implements LanguageSpec {
    public String sourceFileName() { return "Main.java"; }
    public List<String> compileCommand(Path workspacePath) {
        return Arrays.asList("javac", "-encoding", "utf-8", workspacePath.resolve(sourceFileName()).toString());
    }
    public List<String> nativeRunCommand(Path workspacePath) {
        return Arrays.asList("java", "-Xmx256m", "-Dfile.encoding=UTF-8", "-cp", workspacePath.toString(), "Main");
    }
    public List<String> containerRunCommand(String workspacePath) {
        return Arrays.asList("java", "-Xmx80m", "-cp", workspacePath, "Main");
    }
}
