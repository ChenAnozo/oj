package com.yupi.yuojcodesandbox.controller;

import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yupi.yuojcodesandbox.service.CodeSandboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RestController
public class CodeSandboxController {
    private static final Logger log = LoggerFactory.getLogger(CodeSandboxController.class);
    private static final String AUTH_REQUEST_HEADER = "auth";
    @Value("${codesandbox.auth-secret}")
    private String authSecret;

    @Value("${codesandbox.instance-id:standalone}")
    private String instanceId = "standalone";

    private final CodeSandboxService codeSandboxService;
    private final Semaphore executionSlots;
    private final long queueWaitMs;

    public CodeSandboxController(CodeSandboxService codeSandboxService,
            @Value("${codesandbox.max-concurrent-executions:2}") int maxConcurrent,
            @Value("${codesandbox.queue-wait-ms:10000}") long queueWaitMs) {
        if (maxConcurrent < 1 || queueWaitMs < 0) {
            throw new IllegalArgumentException("Sandbox capacity must be positive and queue wait nonnegative");
        }
        this.codeSandboxService = codeSandboxService;
        this.executionSlots = new Semaphore(maxConcurrent, true);
        this.queueWaitMs = queueWaitMs;
    }

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }

    @PostMapping("/executeCode")
    public ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest,
                                          HttpServletRequest request, HttpServletResponse response) {
        if (executeCodeRequest == null) {
            throw new RuntimeException("Execute code request is null(执行代码请求为空)");
        }
        String key = request.getHeader(AUTH_REQUEST_HEADER);
        if (authSecret == null || authSecret.trim().isEmpty() || !authSecret.equals(key)) {
            response.setStatus(403);
            return null;
        }
        response.setHeader("X-Sandbox-Instance", instanceId);
        boolean acquired;
        try {
            acquired = executionSlots.tryAcquire(queueWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(503);
            return null;
        }
        if (!acquired) {
            response.setStatus(429);
            return null;
        }
        String submissionId = request.getHeader("X-Sandbox-Submission-Id");
        if (submissionId == null || !submissionId.matches("[0-9]{1,19}")) submissionId = "direct";
        log.info("sandbox_execute submission={} instance={} language={}", submissionId, instanceId,
                executeCodeRequest.getLanguage());
        try {
            return codeSandboxService.executeCode(executeCodeRequest);
        } finally {
            log.info("sandbox_execute_done submission={} instance={}", submissionId, instanceId);
            executionSlots.release();
        }
    }
}
