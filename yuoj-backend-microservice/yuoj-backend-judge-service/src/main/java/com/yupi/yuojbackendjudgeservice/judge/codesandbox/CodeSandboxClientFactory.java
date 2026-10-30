package com.yupi.yuojbackendjudgeservice.judge.codesandbox;


import com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl.LocalCodeSandboxClient;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl.HttpCodeSandboxClient;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl.ThirdPartyCodeSandboxClient;

public class CodeSandboxClientFactory {

    public static CodeSandboxClient newInstance(String type, String url, String authSecret,
                                                CodeSandboxClient discoveryClient) {
        if ("discovery".equals(type)) {
            if (discoveryClient == null) throw new IllegalArgumentException("Discovery client is required");
            return discoveryClient;
        }
        return newInstance(type, url, authSecret);
    }

    public static CodeSandboxClient newInstance(String type, String url, String authSecret) {
        if ("remote".equals(type)) {
            return new HttpCodeSandboxClient(url, authSecret);
        }
        return newInstance(type);
    }

    public static CodeSandboxClient newInstance(String type) {
        switch (type) {
            case "example":
                return new LocalCodeSandboxClient();
            case "remote":
                return new HttpCodeSandboxClient();
            case "thirdParty":
                return new ThirdPartyCodeSandboxClient();
            case "discovery":
                throw new IllegalArgumentException("Use the injected discovery client");
            default:
                return new LocalCodeSandboxClient();
        }
    }
}
