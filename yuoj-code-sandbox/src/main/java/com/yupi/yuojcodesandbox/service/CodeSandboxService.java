package com.yupi.yuojcodesandbox.service;

import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;

public interface CodeSandboxService {
    ExecuteCodeResponse executeCode(ExecuteCodeRequest request);
}
