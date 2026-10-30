package com.yupi.yuojcodesandbox.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yupi.yuojcodesandbox.model.JudgeInfo;
import com.yupi.yuojcodesandbox.service.CodeSandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CodeSandboxControllerTest {
    private final CodeSandboxService service = mock(CodeSandboxService.class);
    private final CodeSandboxController controller = new CodeSandboxController(service, 1, 0);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private ExecuteCodeRequest request;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "authSecret", "test-only-sandbox-secret");
        mvc = standaloneSetup(controller).build();
        request = ExecuteCodeRequest.builder().code("class Main {}").language("java")
                .inputList(Collections.singletonList("1 2")).build();
    }

    @Test
    void healthReturnsOk() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(content().string("ok"));
        verifyNoInteractions(service);
    }

    @Test
    void missingOrInvalidAuthReturnsForbidden() throws Exception {
        String body = mapper.writeValueAsString(request);
        mvc.perform(post("/executeCode").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(content().string(""));
        mvc.perform(post("/executeCode").header("auth", "invalid")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(content().string(""));
        verifyNoInteractions(service);
    }

    @Test
    void unconfiguredSecretRejectsRequests() throws Exception {
        String body = mapper.writeValueAsString(request);
        for (String secret : new String[] { null, "", "   " }) {
            org.springframework.test.util.ReflectionTestUtils.setField(controller, "authSecret", secret);
            mvc.perform(post("/executeCode").header("auth", "test-only-sandbox-secret")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void authorizedRequestPreservesJsonContract() throws Exception {
        JudgeInfo info = new JudgeInfo();
        info.setTime(12L);
        info.setMemory(64L);
        ExecuteCodeResponse response = ExecuteCodeResponse.builder()
                .outputList(Collections.singletonList("3")).message("ok").status(2).judgeInfo(info).build();
        when(service.executeCode(request)).thenReturn(response);
        mvc.perform(post("/executeCode").header("auth", "test-only-sandbox-secret")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Sandbox-Instance", "standalone"))
                .andExpect(content().json(mapper.writeValueAsString(response), true));
        verify(service).executeCode(request);
    }

    @Test
    void emptyBodyReturnsBadRequest() throws Exception {
        mvc.perform(post("/executeCode").header("auth", "test-only-sandbox-secret")
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void directNullRequestRetainsGuard() {
        assertThrows(RuntimeException.class, () -> controller.executeCode(null,
                new MockHttpServletRequest(), new MockHttpServletResponse()));
        verifyNoInteractions(service);
    }

    @Test
    void busyInstanceRejectsExcessWorkAndReleasesCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.executeCode(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
            return ExecuteCodeResponse.builder().status(2).build();
        });
        String body = mapper.writeValueAsString(request);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                mvc.perform(post("/executeCode").header("auth", "test-only-sandbox-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        try {
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
            mvc.perform(post("/executeCode").header("auth", "test-only-sandbox-secret")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
        } finally { release.countDown(); }
        running.get(5, TimeUnit.SECONDS);
        mvc.perform(post("/executeCode").header("auth", "test-only-sandbox-secret")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(service, times(2)).executeCode(any());
    }

    @Test
    void executionExceptionDoesNotLeakCapacity() throws Exception {
        when(service.executeCode(any())).thenThrow(new IllegalStateException("executor failed"))
                .thenReturn(ExecuteCodeResponse.builder().status(2).build());
        String body = mapper.writeValueAsString(request);
        assertThrows(Exception.class, () -> mvc.perform(post("/executeCode").header("auth", "test-only-sandbox-secret")
                .contentType(MediaType.APPLICATION_JSON).content(body)));
        mvc.perform(post("/executeCode").header("auth", "test-only-sandbox-secret")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(service, times(2)).executeCode(any());
    }
}
