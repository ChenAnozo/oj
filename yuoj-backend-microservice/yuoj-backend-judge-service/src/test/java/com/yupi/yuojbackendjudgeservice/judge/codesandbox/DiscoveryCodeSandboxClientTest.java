package com.yupi.yuojbackendjudgeservice.judge.codesandbox;

import com.yupi.yuojbackendjudgeservice.judge.codesandbox.feign.CodeSandboxFeignClient;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.feign.CodeSandboxFeignConfiguration;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl.DiscoveryCodeSandboxClient;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.impl.HttpCodeSandboxClient;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeRequest;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeResponse;
import feign.Feign;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.http.ResponseEntity;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryCodeSandboxClientTest {
    private final CodeSandboxFeignClient wire = mock(CodeSandboxFeignClient.class);
    private final DiscoveryCodeSandboxClient client = new DiscoveryCodeSandboxClient(wire, "test-secret");
    private final ExecuteCodeRequest request = ExecuteCodeRequest.builder().language("java")
            .code("class Main {}").inputList(Collections.singletonList("1 2")).build();

    @Test
    void preservesBodyAuthAndSubmissionIdentity() {
        ExecuteCodeResponse body = new ExecuteCodeResponse();
        when(wire.executeCode(request, "test-secret", "123")).thenReturn(
                ResponseEntity.ok().header("X-Sandbox-Instance", "sandbox-2").body(body));
        try (MDC.MDCCloseable ignored = MDC.putCloseable("questionSubmitId", "123")) {
            assertSame(body, client.executeCode(request));
        }
        verify(wire).executeCode(request, "test-secret", "123");
        verifyNoMoreInteractions(wire);
    }

    @Test
    void missingResponseIsNotTreatedAsAnExecutionResult() {
        when(wire.executeCode(request, "test-secret", "direct")).thenReturn(ResponseEntity.ok().build());
        assertThrows(IllegalStateException.class, () -> client.executeCode(request));
    }

    @Test
    void doesNotCatchOrRetryExecutionFailure() {
        when(wire.executeCode(request, "test-secret", "direct")).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> client.executeCode(request));
        verify(wire, times(1)).executeCode(request, "test-secret", "direct");
    }

    @Test
    void transportFailureIsAttemptedOnlyOnceByRealFeignClient() {
        AtomicInteger attempts = new AtomicInteger();
        CodeSandboxFeignConfiguration config = new CodeSandboxFeignConfiguration();
        CodeSandboxFeignClient transport = Feign.builder().contract(new SpringMvcContract())
                .encoder((value, type, template) -> template.body("{}"))
                .retryer(config.sandboxRetryer()).options(config.sandboxRequestOptions(2000, 90000))
                .client((req, options) -> { attempts.incrementAndGet(); throw new IOException("connection failed"); })
                .target(CodeSandboxFeignClient.class, "http://sandbox.invalid");
        assertThrows(RetryableException.class, () -> transport.executeCode(request, "test-secret", "123"));
        assertEquals(1, attempts.get());
    }

    @Test
    void configuredTimeoutsAreValidated() {
        CodeSandboxFeignConfiguration config = new CodeSandboxFeignConfiguration();
        assertEquals(90000, config.sandboxRequestOptions(2000, 90000).readTimeoutMillis());
        assertThrows(IllegalArgumentException.class, () -> config.sandboxRequestOptions(0, 90000));
    }

    @Test
    void fixedUrlClientRequiresConfiguredSecret() {
        for (String secret : new String[] { null, "", "   " }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new HttpCodeSandboxClient("http://localhost:8090/executeCode", secret));
        }
    }

    @Test
    void fixedUrlAndDiscoveryModesAreExplicit() {
        assertSame(client, CodeSandboxClientFactory.newInstance("discovery", "", "", client));
        assertTrue(CodeSandboxClientFactory.newInstance("remote", "http://localhost:8090/executeCode", "s")
                instanceof HttpCodeSandboxClient);
        assertThrows(IllegalArgumentException.class, () -> CodeSandboxClientFactory.newInstance("discovery"));
    }
}
