package com.yupi.yuojcodesandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.yupi.yuojcodesandbox.executor.runner.DockerImageValidator;

@SpringBootTest
class YuojCodeSandboxApplicationTests {
    // The startup validator is tested separately with a mocked Docker client.
    @MockBean DockerImageValidator imageValidator;

    @Test
    void contextLoads() {
    }

}
