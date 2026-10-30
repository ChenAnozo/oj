package com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl;


import com.yupi.yuojbackendjudgeservice.judge.codesandbox.CodeSandboxClient;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeRequest;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeResponse;

/**
 * 第三方代码沙箱客户端（调用网上现成的代码沙箱）
 */
public class ThirdPartyCodeSandboxClient implements CodeSandboxClient {
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.out.println("第三方代码沙箱");
        return null;
    }
}
