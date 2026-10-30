package com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl;

import com.yupi.yuojbackendjudgeservice.judge.codesandbox.CodeSandboxClient;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.feign.CodeSandboxFeignClient;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeRequest;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryCodeSandboxClient implements CodeSandboxClient {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryCodeSandboxClient.class);
    private final CodeSandboxFeignClient client;
    private final String authSecret;

    public DiscoveryCodeSandboxClient(CodeSandboxFeignClient client,
            @Value("${codesandbox.auth-secret}") String authSecret) {
        this.client = client;
        this.authSecret = authSecret;
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        String submissionId = MDC.get("questionSubmitId");
        if (submissionId == null) submissionId = "direct";
        ResponseEntity<ExecuteCodeResponse> response = client.executeCode(request, authSecret, submissionId);
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Sandbox returned no successful execution response");
        }
        log.info("sandbox_call submission={} instance={}", submissionId,
                response.getHeaders().getFirst("X-Sandbox-Instance"));
        return response.getBody();
    }
}
