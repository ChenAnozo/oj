package com.yupi.yuojcodesandbox.executor.impl;

import com.github.dockerjava.api.DockerClient;
import com.yupi.yuojcodesandbox.executor.AbstractCodeSandboxExecutor;
import com.yupi.yuojcodesandbox.executor.language.CppLanguageSpec;
import com.yupi.yuojcodesandbox.executor.runner.DockerCodeRunner;
import com.yupi.yuojcodesandbox.executor.runner.DockerExecutionConfig;
import com.yupi.yuojcodesandbox.executor.support.CodeWorkspace;
import com.yupi.yuojcodesandbox.executor.support.NativeProcessExecutor;
import com.yupi.yuojcodesandbox.executor.support.ExecutionResponseAssembler;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class CppDockerCodeSandboxExecutor extends AbstractCodeSandboxExecutor {
    public CppDockerCodeSandboxExecutor(DockerClient client, CodeWorkspace workspaces,
                     NativeProcessExecutor processes, ExecutionResponseAssembler responses,
                     @Value("${codesandbox.cpp-image:gcc:latest}") String image) {
        super(new CppLanguageSpec(),
                new DockerCodeRunner(client, new DockerExecutionConfig(image, 10_000), workspaces),
                workspaces, processes, responses);
    }
}
