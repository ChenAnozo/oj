package com.yupi.yuojcodesandbox.executor.impl;

import com.yupi.yuojcodesandbox.executor.AbstractCodeSandboxExecutor;
import com.yupi.yuojcodesandbox.executor.language.JavaLanguageSpec;
import com.yupi.yuojcodesandbox.executor.runner.NativeCodeRunner;
import com.yupi.yuojcodesandbox.executor.support.CodeWorkspace;
import com.yupi.yuojcodesandbox.executor.support.NativeProcessExecutor;
import com.yupi.yuojcodesandbox.executor.support.ExecutionResponseAssembler;
import org.springframework.stereotype.Component;

@Component
public class JavaNativeCodeSandboxExecutor extends AbstractCodeSandboxExecutor {
    public JavaNativeCodeSandboxExecutor(NativeCodeRunner runner, CodeWorkspace workspaces,
                     NativeProcessExecutor processes, ExecutionResponseAssembler responses) {
        super(new JavaLanguageSpec(), runner, workspaces, processes, responses);
    }
}