package com.yupi.yuojbackendjudgeservice.judge;

import cn.hutool.json.JSONUtil;
import com.yupi.yuojbackendcommon.exception.BusinessException;
import com.yupi.yuojbackendjudgeservice.judge.strategy.*;
import com.yupi.yuojbackendmodel.codesandbox.JudgeInfo;
import com.yupi.yuojbackendmodel.codesandbox.ExecuteCodeResponse;
import com.yupi.yuojbackendjudgeservice.judge.codesandbox.CodeSandboxClient;
import com.yupi.yuojbackendmodel.dto.judge.*;
import com.yupi.yuojbackendmodel.dto.question.JudgeCase;
import com.yupi.yuojbackendmodel.dto.question.JudgeConfig;
import com.yupi.yuojbackendmodel.entity.QuestionSubmit;
import com.yupi.yuojbackendmodel.enums.JudgeInfoMessageEnum;
import com.yupi.yuojbackendserviceclient.service.QuestionFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JudgeTaskTest {
    private QuestionFeignClient questions;
    private JudgeServiceImpl judge;

    @BeforeEach
    void prepare() {
        questions = mock(QuestionFeignClient.class);
        judge = spy(new JudgeServiceImpl());
        ReflectionTestUtils.setField(judge, "questionFeignClient", questions);
        ReflectionTestUtils.setField(judge, "judgeManager", new JudgeManager());
        ReflectionTestUtils.setField(judge, "type", "example");
    }

    @Test
    void unclaimedTaskStopsBeforeExecutionOrUpdates() {
        when(questions.claimJudgeTask(any())).thenReturn(
                ClaimJudgeTaskResponse.builder().claimed(false).build());
        assertDoesNotThrow(() -> judge.doJudge(1L));
        verify(judge, never()).createCodeSandboxClient();
        verify(questions).claimJudgeTask(argThat(r -> Long.valueOf(1L).equals(r.getQuestionSubmitId())));
        verifyNoMoreInteractions(questions);
    }

    @Test
    void invalidResponseIsRejected() {
        assertThrows(BusinessException.class, () -> judge.doJudge(1L));
        when(questions.claimJudgeTask(any())).thenReturn(
                ClaimJudgeTaskResponse.builder().claimed(true).build());
        assertThrows(BusinessException.class, () -> judge.doJudge(1L));
        when(questions.claimJudgeTask(any())).thenReturn(ClaimJudgeTaskResponse.builder()
                .claimed(true).task(JudgeTaskDTO.builder().questionSubmitId(2L).build()).build());
        assertThrows(BusinessException.class, () -> judge.doJudge(1L));
        verify(questions, never()).updateQuestionSubmitById(any());
    }

    @Test
    void compilationFailureIsWrittenBack() {
        CodeSandboxClient sandbox = prepareSandbox();
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        JudgeInfo info = new JudgeInfo();
        info.setMessage(JudgeInfoMessageEnum.COMPILE_ERROR.getValue());
        response.setJudgeInfo(info);
        when(sandbox.executeCode(any())).thenReturn(response);
        judge.doJudge(1L);
        ArgumentCaptor<QuestionSubmit> result = ArgumentCaptor.forClass(QuestionSubmit.class);
        verify(questions).updateQuestionSubmitById(result.capture());
        assertEquals(3, result.getValue().getStatus());
        assertEquals(JudgeInfoMessageEnum.COMPILE_ERROR.getValue(),
                JSONUtil.toBean(result.getValue().getJudgeInfo(), JudgeInfo.class).getMessage());
    }

    @Test
    void sandboxFailureIsWrittenBackAsSystemError() {
        CodeSandboxClient sandbox = prepareSandbox();
        when(sandbox.executeCode(any())).thenThrow(new IllegalStateException("unavailable"));
        assertDoesNotThrow(() -> judge.doJudge(1L));
        verify(questions).updateQuestionSubmitById(argThat(s -> s.getId().equals(1L) && s.getStatus() == 4));
        verify(questions, never()).getQuestionSubmitById(anyLong());
    }

    private CodeSandboxClient prepareSandbox() {
        JudgeCase judgeCase = new JudgeCase();
        judgeCase.setInput("input");
        judgeCase.setOutput("output");
        JudgeTaskDTO task = JudgeTaskDTO.builder().questionSubmitId(1L).questionId(2L)
                .language("java").code("code").judgeConfig(new JudgeConfig())
                .judgeCaseList(Collections.singletonList(judgeCase)).build();
        when(questions.claimJudgeTask(any())).thenReturn(
                ClaimJudgeTaskResponse.builder().claimed(true).task(task).build());
        when(questions.updateQuestionSubmitById(any())).thenReturn(true);
        CodeSandboxClient sandbox = mock(CodeSandboxClient.class);
        doReturn(sandbox).when(judge).createCodeSandboxClient();
        return sandbox;
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "cpp"})
    void claimedTaskWritesResultWithoutQueryingRecord(String language) {
        JudgeCase judgeCase = new JudgeCase();
        judgeCase.setInput("same");
        judgeCase.setOutput("same");
        JudgeTaskDTO task = JudgeTaskDTO.builder().questionSubmitId(1L).questionId(2L)
                .language(language).code("code").judgeConfig(new JudgeConfig())
                .judgeCaseList(Collections.singletonList(judgeCase)).build();
        when(questions.claimJudgeTask(any())).thenReturn(
                ClaimJudgeTaskResponse.builder().claimed(true).task(task).build());
        when(questions.updateQuestionSubmitById(any())).thenReturn(true);
        assertDoesNotThrow(() -> judge.doJudge(1L));
        ArgumentCaptor<QuestionSubmit> result = ArgumentCaptor.forClass(QuestionSubmit.class);
        verify(questions).updateQuestionSubmitById(result.capture());
        assertEquals(2L, result.getValue().getQuestionId());
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(),
                JSONUtil.toBean(result.getValue().getJudgeInfo(), JudgeInfo.class).getMessage());
        verify(questions).claimJudgeTask(any());
        verify(questions, never()).getQuestionSubmitById(anyLong());
        verifyNoMoreInteractions(questions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "cpp"})
    void strategiesUseTypedConfigAndPreserveJudgmentOrder(String language) {
        JudgeContext context = new JudgeContext();
        context.setLanguage(language);
        JudgeInfo info = new JudgeInfo();
        info.setTime(100L);
        info.setMemory(100L);
        context.setJudgeInfo(info);
        JudgeConfig config = new JudgeConfig();
        context.setJudgeConfig(config);
        JudgeCase judgeCase = new JudgeCase();
        judgeCase.setInput("input");
        judgeCase.setOutput("expected");
        context.setJudgeCaseList(Collections.singletonList(judgeCase));
        context.setInputList(Collections.singletonList("input"));
        context.setOutputList(Collections.singletonList("wrong"));
        JudgeManager manager = new JudgeManager();

        config.setMemoryLimit(99L);
        config.setTimeLimit(99L);
        assertEquals(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue(), manager.doJudge(context).getMessage());
        config.setMemoryLimit(100L);
        assertEquals(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(), manager.doJudge(context).getMessage());
        config.setTimeLimit(100L);
        assertEquals(JudgeInfoMessageEnum.WRONG_ANSWER.getValue(), manager.doJudge(context).getMessage());
        context.setOutputList(Collections.emptyList());
        assertEquals(JudgeInfoMessageEnum.WRONG_ANSWER.getValue(), manager.doJudge(context).getMessage());
        context.setOutputList(Collections.singletonList("expected"));
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(), manager.doJudge(context).getMessage());
    }
}
