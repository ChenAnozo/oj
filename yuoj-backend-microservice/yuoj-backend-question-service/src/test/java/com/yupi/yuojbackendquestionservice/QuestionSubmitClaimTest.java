package com.yupi.yuojbackendquestionservice;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yupi.yuojbackendcommon.common.ErrorCode;
import com.yupi.yuojbackendcommon.exception.BusinessException;
import com.yupi.yuojbackendcommon.exception.GlobalExceptionHandler;
import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskResponse;
import com.yupi.yuojbackendmodel.entity.Question;
import com.yupi.yuojbackendquestionservice.controller.inner.QuestionInnerController;
import com.yupi.yuojbackendquestionservice.mapper.QuestionSubmitMapper;
import com.yupi.yuojbackendquestionservice.rabbitmq.MyMessageProducer;
import com.yupi.yuojbackendquestionservice.service.QuestionService;
import com.yupi.yuojbackendquestionservice.service.QuestionSubmitService;
import com.yupi.yuojbackendquestionservice.service.impl.QuestionSubmitServiceImpl;
import com.yupi.yuojbackendquestionservice.websocket.JudgeWebSocketServer;
import com.yupi.yuojbackendserviceclient.service.JudgeFeignClient;
import com.yupi.yuojbackendserviceclient.service.UserFeignClient;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立内存数据库上的真实 MyBatis 和 Spring 事务测试，不连接业务基础设施。 */
@SpringJUnitConfig(QuestionSubmitClaimTest.Config.class)
class QuestionSubmitClaimTest {
    @Autowired QuestionSubmitService service;
    @Autowired QuestionService questions;
    @Autowired JudgeWebSocketServer notifications;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired QuestionInnerController controller;
    private JdbcTemplate jdbc;
    private Question question;

    @BeforeEach
    void prepare() {
        reset(questions, notifications);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS question_submit (id BIGINT PRIMARY KEY, "
                + "language VARCHAR(20), code TEXT, judgeInfo TEXT, status INT, questionId BIGINT, "
                + "userId BIGINT, createTime TIMESTAMP, updateTime TIMESTAMP, isDelete INT DEFAULT 0)");
        jdbc.execute("DELETE FROM question_submit");
        jdbc.update("INSERT INTO question_submit(id,language,code,status,questionId) VALUES(1,'java','class Main {}',0,2)");
        question = new Question();
        question.setId(2L);
        question.setJudgeCase("[{\"input\":\"\",\"output\":\"\"}]");
        question.setJudgeConfig("{}");
        when(questions.getById(2L)).thenReturn(question);
    }

    @Test
    void claimReturnsDataAndNotifiesOnlyAfterCommit() {
        new TransactionTemplate(transactionManager).execute(status -> {
            ClaimJudgeTaskResponse result = service.claimJudgeTask(1L);
            assertTrue(result.isClaimed());
            assertEquals(1L, result.getTask().getQuestionSubmitId());
            assertEquals(2L, result.getTask().getQuestionId());
            assertEquals("java", result.getTask().getLanguage());
            assertEquals("class Main {}", result.getTask().getCode());
            assertEquals("", result.getTask().getJudgeCaseList().get(0).getInput());
            assertNull(result.getTask().getJudgeConfig().getTimeLimit());
            verifyNoInteractions(notifications);
            return null;
        });
        assertEquals(1, state());
        verify(notifications).sendMessage(eq(1L), argThat(value ->
                "status".equals(((java.util.Map<?, ?>) value).get("type"))));
        assertFalse(service.claimJudgeTask(1L).isClaimed());
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void outerRollbackDiscardsClaimAndNotification() {
        new TransactionTemplate(transactionManager).execute(status -> {
            assertTrue(service.claimJudgeTask(1L).isClaimed());
            status.setRollbackOnly();
            return null;
        });
        assertEquals(0, state());
        verifyNoInteractions(notifications);
    }

    @Test
    void missingQuestionRollsBackClaim() {
        when(questions.getById(2L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.claimJudgeTask(1L));
        assertEquals(0, state());
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "broken", "[null]", "[{\"input\":\"a\"}]"})
    void invalidCasesRollBackClaim(String cases) {
        question.setJudgeCase(cases);
        assertThrows(BusinessException.class, () -> service.claimJudgeTask(1L));
        assertEquals(0, state());
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "broken", "{\"timeLimit\":\"invalid\"}"})
    void invalidConfigRollsBackClaim(String config) {
        question.setJudgeConfig(config);
        assertThrows(BusinessException.class, () -> service.claimJudgeTask(1L));
        assertEquals(0, state());
        verifyNoInteractions(notifications);
    }

    @Test
    void invalidSubmissionRollsBackClaim() {
        jdbc.update("UPDATE question_submit SET code='' WHERE id=1");
        assertThrows(BusinessException.class, () -> service.claimJudgeTask(1L));
        assertEquals(0, state());
        jdbc.update("UPDATE question_submit SET code='code',language='unknown' WHERE id=1");
        assertThrows(BusinessException.class, () -> service.claimJudgeTask(1L));
        assertEquals(0, state());
    }

    @Test
    void deletedMissingAndFinishedTasksCannotBeClaimed() {
        assertFalse(service.claimJudgeTask(999L).isClaimed());
        jdbc.update("UPDATE question_submit SET isDelete=1 WHERE id=1");
        assertFalse(service.claimJudgeTask(1L).isClaimed());
        jdbc.update("UPDATE question_submit SET isDelete=0,status=2 WHERE id=1");
        assertNull(service.claimJudgeTask(1L).getTask());
        verifyNoInteractions(questions, notifications);
    }

    @Test
    void concurrentConsumersHaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return service.claimJudgeTask(1L).isClaimed();
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners);
            assertEquals(1, state());
            verify(notifications, times(1)).sendMessage(eq(1L), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedNotificationDoesNotFailCommittedClaim() {
        doThrow(new IllegalStateException("disconnected"))
                .when(notifications).sendMessage(eq(1L), any());
        assertTrue(service.claimJudgeTask(1L).isClaimed());
        assertEquals(1, state());
    }

    @Test
    void httpEndpointUsesRequestAndResponseContract() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/inner/judge_task/claim").contentType("application/json")
                .content("{\"questionSubmitId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.task.questionSubmitId").value(1))
                .andExpect(jsonPath("$.task.judgeCaseList[0].input").value(""));
        for (String body : new String[]{"{}", "{\"questionSubmitId\":0}", "{\"questionSubmitId\":-1}"}) {
            mvc.perform(post("/inner/judge_task/claim").contentType("application/json").content(body))
                    .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));
        }
        assertThrows(BusinessException.class, () -> controller.claimJudgeTask(null));
    }

    private int state() {
        return jdbc.queryForObject("SELECT status FROM question_submit WHERE id=1", Integer.class);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackageClasses = QuestionSubmitMapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:claim;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(false);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean QuestionSubmitServiceImpl service() { return new QuestionSubmitServiceImpl(); }
        @Bean QuestionService questions() { return mock(QuestionService.class); }
        @Bean JudgeWebSocketServer notifications() { return mock(JudgeWebSocketServer.class); }
        @Bean UserFeignClient userFeignClient() { return mock(UserFeignClient.class); }
        @Bean JudgeFeignClient judgeFeignClient() { return mock(JudgeFeignClient.class); }
        @Bean MyMessageProducer producer() { return mock(MyMessageProducer.class); }
        @Bean org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate() {
            return mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        }
        @Bean QuestionInnerController controller() { return new QuestionInnerController(); }
    }
}
