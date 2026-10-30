package com.yupi.yuojbackendquestionservice;

import com.yupi.yuojbackendcommon.exception.BusinessException;
import com.yupi.yuojbackendmodel.entity.Question;
import com.yupi.yuojbackendquestionservice.service.impl.QuestionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class QuestionCaseSizeTest {
    @Test
    void acceptsLargeCaseAndRejectsAboveByteLimit() {
        QuestionServiceImpl service = new QuestionServiceImpl();
        Question question = new Question();
        question.setJudgeCase(String.join("", Collections.nCopies(1024 * 1024, "a")));
        assertDoesNotThrow(() -> service.validQuestion(question, false));
        question.setJudgeCase(question.getJudgeCase() + "a");
        assertThrows(BusinessException.class, () -> service.validQuestion(question, false));
    }

    @Test
    void configuredLimitCountsUtf8Bytes() {
        QuestionServiceImpl service = new QuestionServiceImpl();
        ReflectionTestUtils.setField(service, "judgeCaseMaxBytes", 9);
        Question question = new Question();
        question.setJudgeCase("\u6d4b\u6d4b\u6d4b");
        assertDoesNotThrow(() -> service.validQuestion(question, false));
        question.setJudgeCase(question.getJudgeCase() + "a");
        assertThrows(BusinessException.class, () -> service.validQuestion(question, false));
    }
}
