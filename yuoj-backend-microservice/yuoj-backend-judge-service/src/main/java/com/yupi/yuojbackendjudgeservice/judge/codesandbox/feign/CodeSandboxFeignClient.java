package com.yupi.yuojbackendjudgeservice.judge.codesandbox.feign;

import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeRequest;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "${codesandbox.service-id:yuoj-code-sandbox}", contextId = "codeSandbox",
        configuration = CodeSandboxFeignConfiguration.class)
public interface CodeSandboxFeignClient {
    @PostMapping(value = "/executeCode", consumes = "application/json")
    ResponseEntity<ExecuteCodeResponse> executeCode(@RequestBody ExecuteCodeRequest request,
            @RequestHeader("auth") String authSecret,
            @RequestHeader("X-Sandbox-Submission-Id") String submissionId);
}
