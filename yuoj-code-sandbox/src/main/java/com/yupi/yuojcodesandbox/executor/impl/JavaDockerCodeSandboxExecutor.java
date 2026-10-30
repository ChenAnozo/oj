package com.yupi.yuojcodesandbox.executor.impl;

import com.github.dockerjava.api.DockerClient;
import com.yupi.yuojcodesandbox.executor.AbstractCodeSandboxExecutor;
import com.yupi.yuojcodesandbox.executor.language.JavaLanguageSpec;
import com.yupi.yuojcodesandbox.executor.runner.DockerCodeRunner;
import com.yupi.yuojcodesandbox.executor.runner.DockerExecutionConfig;
import com.yupi.yuojcodesandbox.executor.support.CodeWorkspace;
import com.yupi.yuojcodesandbox.executor.support.NativeProcessExecutor;
import com.yupi.yuojcodesandbox.executor.support.ExecutionResponseAssembler;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class JavaDockerCodeSandboxExecutor extends AbstractCodeSandboxExecutor {
    public JavaDockerCodeSandboxExecutor(DockerClient client, CodeWorkspace workspaces,
                     NativeProcessExecutor processes, ExecutionResponseAssembler responses,
                     @Value("${codesandbox.java-image:openjdk:8-alpine}") String image) {
        super(new JavaLanguageSpec(),
                new DockerCodeRunner(client, new DockerExecutionConfig(image, 5_000), workspaces),
                workspaces, processes, responses);
    }
}
