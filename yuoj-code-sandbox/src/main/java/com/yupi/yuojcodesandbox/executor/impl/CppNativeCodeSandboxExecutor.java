package com.yupi.yuojcodesandbox.executor.impl;

import com.yupi.yuojcodesandbox.executor.AbstractCodeSandboxExecutor;
import com.yupi.yuojcodesandbox.executor.language.CppLanguageSpec;
import com.yupi.yuojcodesandbox.executor.runner.NativeCodeRunner;
import com.yupi.yuojcodesandbox.executor.support.CodeWorkspace;
import com.yupi.yuojcodesandbox.executor.support.NativeProcessExecutor;
import com.yupi.yuojcodesandbox.executor.support.ExecutionResponseAssembler;
import org.springframework.stereotype.Component;

@Component
public class CppNativeCodeSandboxExecutor extends AbstractCodeSandboxExecutor {
    public CppNativeCodeSandboxExecutor(NativeCodeRunner runner, CodeWorkspace workspaces,
                     NativeProcessExecutor processes, ExecutionResponseAssembler responses) {
        super(new CppLanguageSpec(), runner, workspaces, processes, responses);
    }
}