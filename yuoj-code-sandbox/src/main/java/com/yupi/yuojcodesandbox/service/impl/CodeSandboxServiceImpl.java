package com.yupi.yuojcodesandbox.service.impl;

import com.yupi.yuojcodesandbox.executor.impl.JavaDockerCodeSandboxExecutor;
import com.yupi.yuojcodesandbox.executor.impl.CppDockerCodeSandboxExecutor;

import com.yupi.yuojcodesandbox.executor.CodeSandboxExecutor;
import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yupi.yuojcodesandbox.service.CodeSandboxService;
import org.springframework.stereotype.Service;

@Service
public class CodeSandboxServiceImpl implements CodeSandboxService {
    private final JavaDockerCodeSandboxExecutor javaDockerCodeSandboxExecutor;
    private final CppDockerCodeSandboxExecutor cppDockerCodeSandboxExecutor;

    public CodeSandboxServiceImpl(JavaDockerCodeSandboxExecutor javaDockerCodeSandboxExecutor,
                                  CppDockerCodeSandboxExecutor cppDockerCodeSandboxExecutor) {
        this.javaDockerCodeSandboxExecutor = javaDockerCodeSandboxExecutor;
        this.cppDockerCodeSandboxExecutor = cppDockerCodeSandboxExecutor;
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        CodeSandboxExecutor codeSandbox;
        switch (request.getLanguage()) {
            case "cpp":
                codeSandbox = cppDockerCodeSandboxExecutor;
                break;
            case "java":
            default:
                codeSandbox = javaDockerCodeSandboxExecutor;
        }
        return codeSandbox.executeCode(request);
    }
}
