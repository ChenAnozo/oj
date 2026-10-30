package com.yupi.yuojbackendjudgeservice.judge.codesandbox.feign;

import feign.Request;
import feign.Retryer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

// Deliberately not @Configuration: these beans belong only to this Feign client.
public class CodeSandboxFeignConfiguration {
    @Bean
    public Retryer sandboxRetryer() {
        // A transport failure does not prove that submitted code was never executed.
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Request.Options sandboxRequestOptions(
            @Value("${codesandbox.connect-timeout-ms:2000}") int connectTimeout,
            @Value("${codesandbox.read-timeout-ms:90000}") int readTimeout) {
        if (connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalArgumentException("Sandbox timeouts must be positive");
        }
        return new Request.Options(connectTimeout, readTimeout, false);
    }
}
