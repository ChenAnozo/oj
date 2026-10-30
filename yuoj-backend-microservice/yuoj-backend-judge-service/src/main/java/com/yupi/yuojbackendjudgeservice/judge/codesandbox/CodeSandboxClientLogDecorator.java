package com.yupi.yuojbackendjudgeservice.judge.codesandbox;

import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeResponse;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeSandboxClientLogDecorator implements CodeSandboxClient {
    private final CodeSandboxClient codeSandboxClient;

    public CodeSandboxClientLogDecorator(CodeSandboxClient codeSandboxClient) {
        this.codeSandboxClient = codeSandboxClient;
    }


    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        log.info("代码沙箱 请求信息：" + executeCodeRequest.getCode());
        ExecuteCodeResponse executeCodeResponse = codeSandboxClient.executeCode(executeCodeRequest);
        log.info("代码沙箱 响应信息：" + executeCodeResponse);
        return executeCodeResponse;
    }
}
