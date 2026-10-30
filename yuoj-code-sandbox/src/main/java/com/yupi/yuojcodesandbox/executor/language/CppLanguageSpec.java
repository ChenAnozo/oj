package com.yupi.yuojcodesandbox.executor.language;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CppLanguageSpec implements LanguageSpec {
    public String sourceFileName() { return "Main.cpp"; }
    public List<String> compileCommand(Path workspacePath) {
        return Arrays.asList("g++", "-static", "-std=c++11", "-O2", "-o",
                workspacePath.resolve("Main").toString(), workspacePath.resolve(sourceFileName()).toString());
    }
    public List<String> nativeRunCommand(Path workspacePath) {
        return Collections.singletonList(workspacePath.resolve("Main").toString());
    }
    public List<String> containerRunCommand(String workspacePath) {
        return Collections.singletonList(workspacePath + "/Main");
    }
}
