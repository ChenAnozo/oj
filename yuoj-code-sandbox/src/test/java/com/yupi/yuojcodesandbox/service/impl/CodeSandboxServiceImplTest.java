package com.yupi.yuojcodesandbox.service.impl;

import com.yupi.yuojcodesandbox.executor.impl.JavaDockerCodeSandboxExecutor;
import com.yupi.yuojcodesandbox.executor.impl.CppDockerCodeSandboxExecutor;

import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeSandboxServiceImplTest {
    private final JavaDockerCodeSandboxExecutor javaSandbox = mock(JavaDockerCodeSandboxExecutor.class);
    private final CppDockerCodeSandboxExecutor cppSandbox = mock(CppDockerCodeSandboxExecutor.class);
    private final CodeSandboxServiceImpl service = new CodeSandboxServiceImpl(javaSandbox, cppSandbox);

    @Test
    void routesCppAndReturnsOriginalResponse() {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder().language("cpp").build();
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        when(cppSandbox.executeCode(request)).thenReturn(response);
        assertSame(response, service.executeCode(request));
        verify(cppSandbox).executeCode(request);
        verifyNoInteractions(javaSandbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "python", ""})
    void defaultsToJava(String language) {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder().language(language).build();
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        when(javaSandbox.executeCode(request)).thenReturn(response);
        assertSame(response, service.executeCode(request));
        verify(javaSandbox).executeCode(request);
        verifyNoInteractions(cppSandbox);
    }

    @Test
    void preservesExecutionFailure() {
        ExecuteCodeRequest request = ExecuteCodeRequest.builder().language("java").build();
        RuntimeException failure = new RuntimeException("execution failed");
        when(javaSandbox.executeCode(request)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.executeCode(request)));
    }
}
