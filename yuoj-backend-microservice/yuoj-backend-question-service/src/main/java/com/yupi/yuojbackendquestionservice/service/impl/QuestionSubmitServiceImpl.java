package com.yupi.yuojbackendquestionservice.service.impl;

import cn.hutool.json.JSONUtil;
import com.yupi.yuojbackendquestionservice.diagnostics.SubmissionTiming;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yuojbackendcommon.common.ErrorCode;
import com.yupi.yuojbackendcommon.constant.CommonConstant;
import com.yupi.yuojbackendcommon.exception.BusinessException;
import com.yupi.yuojbackendcommon.utils.SqlUtils;
import com.yupi.yuojbackendmodel.codesandbox.JudgeInfo;
import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskResponse;
import com.yupi.yuojbackendmodel.dto.judge.JudgeTaskDTO;
import com.yupi.yuojbackendmodel.dto.question.JudgeCase;
import com.yupi.yuojbackendmodel.dto.question.JudgeConfig;
import com.yupi.yuojbackendmodel.dto.questionsubmit.QuestionSubmitAddRequest;
import com.yupi.yuojbackendmodel.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.yupi.yuojbackendmodel.entity.Question;
import com.yupi.yuojbackendmodel.entity.QuestionSubmit;
import com.yupi.yuojbackendmodel.entity.User;
import com.yupi.yuojbackendmodel.enums.JudgeInfoMessageEnum;
import com.yupi.yuojbackendmodel.enums.QuestionSubmitLanguageEnum;
import com.yupi.yuojbackendmodel.enums.QuestionSubmitStatusEnum;
import com.yupi.yuojbackendmodel.vo.QuestionSubmitVO;
import com.yupi.yuojbackendquestionservice.mapper.QuestionSubmitMapper;
import com.yupi.yuojbackendquestionservice.rabbitmq.MyMessageProducer;
import com.yupi.yuojbackendquestionservice.service.QuestionService;
import com.yupi.yuojbackendquestionservice.service.QuestionSubmitService;
import com.yupi.yuojbackendquestionservice.websocket.JudgeWebSocketServer;
import com.yupi.yuojbackendserviceclient.service.JudgeFeignClient;
import com.yupi.yuojbackendserviceclient.service.UserFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * @author 93273
 * @description 针对表【question_submit(题目提交)】的数据库操作Service实现
 * @createDate 2026-03-07 21:24:30
 */
@Service
@Slf4j
public class QuestionSubmitServiceImpl extends ServiceImpl<QuestionSubmitMapper, QuestionSubmit>
        implements QuestionSubmitService {
    @Resource
    private QuestionService questionService;

    @Resource
    private UserFeignClient userFeignClient;

    @Resource
    @Lazy
    private JudgeFeignClient judgeService;

    @Resource
    private MyMessageProducer myMessageProducer;

    @Resource
    private JudgeWebSocketServer judgeWebSocketServer;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ClaimJudgeTaskResponse claimJudgeTask(long questionSubmitId) {
        if (questionSubmitId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean claimed = updateStatusCasNoNotify(questionSubmitId,
                QuestionSubmitStatusEnum.WAITING.getValue(),
                QuestionSubmitStatusEnum.RUNNING.getValue());
        if (!claimed) {
            return ClaimJudgeTaskResponse.builder().claimed(false).build();
        }

        QuestionSubmit submit = getById(questionSubmitId);
        if (submit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交信息不存在");
        }
        Question question = questionService.getById(submit.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        JudgeTaskDTO task = buildJudgeTask(submit, question);

        Map<String, Object> message = new HashMap<>();
        message.put("questionSubmitId", questionSubmitId);
        message.put("status", QuestionSubmitStatusEnum.RUNNING.getValue());
        message.put("type", "status");
        message.put("statusText", QuestionSubmitStatusEnum.RUNNING.getText());
        registerAfterCommitNotify(questionSubmitId, message);
        return ClaimJudgeTaskResponse.builder().claimed(true).task(task).build();
    }

    private JudgeTaskDTO buildJudgeTask(QuestionSubmit submit, Question question) {
        if (QuestionSubmitLanguageEnum.getEnumByValue(submit.getLanguage()) == null
                || StringUtils.isBlank(submit.getCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "提交代码或语言无效");
        }
        List<JudgeCase> judgeCases;
        JudgeConfig judgeConfig;
        try {
            // 要求实际的数组和对象，避免宽松转换把损坏数据当成空配置。
            if (!JSONUtil.isTypeJSONArray(question.getJudgeCase())
                    || !JSONUtil.isTypeJSONObject(question.getJudgeConfig())) {
                throw new IllegalArgumentException("判题数据格式错误");
            }
            judgeCases = JSONUtil.toList(question.getJudgeCase(), JudgeCase.class);
            judgeConfig = JSONUtil.toBean(question.getJudgeConfig(), JudgeConfig.class);
        } catch (Exception e) {
            log.error("判题数据解析失败，questionId={}", question.getId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "判题数据解析失败");
        }
        if (CollectionUtils.isEmpty(judgeCases) || judgeConfig == null
                || judgeCases.stream().anyMatch(c -> c == null
                || c.getInput() == null || c.getOutput() == null)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "判题数据不完整");
        }
        return JudgeTaskDTO.builder()
                .questionSubmitId(submit.getId()).questionId(submit.getQuestionId())
                .language(submit.getLanguage()).code(submit.getCode())
                .judgeCaseList(judgeCases).judgeConfig(judgeConfig).build();
    }
    /**
     * 提交题目
     *
     * @param questionSubmitAddRequest
     * @param loginUser
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser) {


        try (SubmissionTiming timing = new SubmissionTiming("service_body",
                questionSubmitAddRequest.getQuestionId())) {
            String language = questionSubmitAddRequest.getLanguage();
            QuestionSubmitLanguageEnum languageEnum = QuestionSubmitLanguageEnum.getEnumByValue(language);
            if (languageEnum == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "编程语言错误");
            }


            long questionId = questionSubmitAddRequest.getQuestionId();

            // 判断实体是否存在，根据类别获取实体
            timing.stage("question_read");
            Question question = questionService.getById(questionId);
            if (question == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
            }
            // 是否已提交题目
            long userId = loginUser.getId();
            // 每个用户串行提交题目
            QuestionSubmit questionSubmit = new QuestionSubmit();
            questionSubmit.setUserId(userId);
            questionSubmit.setQuestionId(questionId);
            questionSubmit.setCode(questionSubmitAddRequest.getCode());
            questionSubmit.setLanguage(language);
            //设置初始状态
            questionSubmit.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
            questionSubmit.setJudgeInfo("{}");
            timing.stage("submission_insert");
            boolean save = this.save(questionSubmit);

            if (!save) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "数据插入失败");
            }
            timing.stage("question_counter_update");
            questionService.incrSubmitNum(questionId);

            Long questionSubmitId = questionSubmit.getId();
            timing.submissionId(questionSubmitId);

            timing.stage("register_after_commit");
            registerAfterCommitPublish(questionSubmitId);
    //        CompletableFuture.runAsync(()->{
    //            judgeService.doJudge(questionSubmitId);
    //        });

            timing.success();
            return questionSubmit.getId();
        }
    }

    /**
     * 把判题消息的发送注册到当前事务的提交后回调中：
     * 只有事务真正提交成功才会发送，避免“读早于提交”和“回滚后留下孤儿消息”。
     */
    private void registerAfterCommitPublish(Long questionSubmitId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishJudgeMessages(questionSubmitId);
                }
            });
        } else {
            // 防御性降级：正常情况下本方法带 @Transactional，不会走到这里
            publishJudgeMessages(questionSubmitId);
        }
    }

    private void publishJudgeMessages(Long questionSubmitId) {
        String id = String.valueOf(questionSubmitId);
        try (SubmissionTiming timing = new SubmissionTiming("after_commit_publish", 0)) {
            timing.submissionId(questionSubmitId);
            timing.stage("execute_publish");
            myMessageProducer.sendMessage("code_exchange", "my_routingKey", id);
            timing.stage("delay_publish");
            myMessageProducer.sendDelayMessage(id);
            timing.success();
        } catch (Exception e) {
            log.error("判题消息发送失败，questionSubmitId={}", questionSubmitId, e);
        }
    }

    @Override
    public QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest) {
        QueryWrapper<QuestionSubmit> queryWrapper = new QueryWrapper<>();
        if (questionSubmitQueryRequest == null) {
            return queryWrapper;
        }

        String language = questionSubmitQueryRequest.getLanguage();
        Integer status = questionSubmitQueryRequest.getStatus();
        Long questionId = questionSubmitQueryRequest.getQuestionId();
        Long userId = questionSubmitQueryRequest.getUserId();
        String sortField = questionSubmitQueryRequest.getSortField();
        String sortOrder = questionSubmitQueryRequest.getSortOrder();

        queryWrapper.eq(StringUtils.isNotBlank(language), "language", language);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(questionId), "questionId", questionId);
        queryWrapper.eq("isDelete", false);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);

        return queryWrapper;
    }

    @Override
    public QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, User loginUser) {
        /**
         * 将QuestionSumbit转化为QuestionSumbitVO,
         * 并且脱敏
         */
        QuestionSubmitVO questionSubmitVO = QuestionSubmitVO.objToVo(questionSubmit);
        // 脱敏：仅本人和管理员能看见自己（提交 userId 和登录用户 id 不同）提交的代码
        long userId = loginUser.getId();
        // 处理脱敏
        if (userId != questionSubmit.getUserId() && !userFeignClient.isAdmin(loginUser)) {
            questionSubmitVO.setCode(null);
        }
        return questionSubmitVO;
    }

    @Override
    public Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage, User loginUser) {

        List<QuestionSubmit> questionSubmitList = questionSubmitPage.getRecords();
        Page<QuestionSubmitVO> questionSubmitVOPage = new Page<>(questionSubmitPage.getCurrent(), questionSubmitPage.getSize(), questionSubmitPage.getTotal());
        if (CollectionUtils.isEmpty(questionSubmitList)) {
            return questionSubmitVOPage;
        }
        List<QuestionSubmitVO> questionSubmitVOList =questionSubmitList.stream().
                map(questionSubmit -> getQuestionSubmitVO(questionSubmit, loginUser)).
                collect(Collectors.toList());
        questionSubmitVOPage.setRecords(questionSubmitVOList);
        return questionSubmitVOPage;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean updateSubmitAndNotify(QuestionSubmit questionSubmit) {
        boolean result = updateById(questionSubmit);
        if (result && questionSubmit.getStatus() != null) {
            if (QuestionSubmitStatusEnum.SUCCEED.getValue().equals(questionSubmit.getStatus())
                    && questionSubmit.getQuestionId() != null
                    && questionSubmit.getJudgeInfo() != null) {
                JudgeInfo judgeInfo = JSONUtil.toBean(questionSubmit.getJudgeInfo(), JudgeInfo.class);
                if (judgeInfo != null && JudgeInfoMessageEnum.ACCEPTED.getValue().equals(judgeInfo.getMessage())) {
                    questionService.incrAcceptedNum(questionSubmit.getQuestionId());
                }
            }

            Long questionSubmitId = questionSubmit.getId();
            Integer status = questionSubmit.getStatus();
            Map<String, Object> msg = new HashMap<>();
            msg.put("questionSubmitId", questionSubmitId);
            msg.put("status", status);

            if (QuestionSubmitStatusEnum.SUCCEED.getValue().equals(status)
                || QuestionSubmitStatusEnum.FAILED.getValue().equals(status)
                || QuestionSubmitStatusEnum.SYSTEM_ERROR.getValue().equals(status)) {
                msg.put("type", "result");
                if (questionSubmit.getJudgeInfo() != null) {
                    msg.put("judgeInfo", JSONUtil.parseObj(questionSubmit.getJudgeInfo()));
                }
            } else {
                msg.put("type", "status");
                msg.put("statusText", QuestionSubmitStatusEnum.getEnumByValue(status).getText());
            }

            registerAfterCommitNotify(questionSubmitId, msg);
        }
        return result;
    }

    private void registerAfterCommitNotify(Long questionSubmitId, Map<String, Object> msg) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifySafely(questionSubmitId, msg);
                }
            });
        } else {
            notifySafely(questionSubmitId, msg);
        }
    }

    private void notifySafely(Long questionSubmitId, Map<String, Object> msg) {
        try {
            judgeWebSocketServer.sendMessage(questionSubmitId, msg);
        } catch (Exception e) {
            log.error("判题通知发送失败，questionSubmitId={}", questionSubmitId, e);
        }
    }

    @Override
    public boolean compareAndSetStatus(long id, Integer expectStatus, Integer newStatus) {
        UpdateWrapper<QuestionSubmit> uw = new UpdateWrapper<>();
        uw.eq("id", id).eq("status", expectStatus).set("status", newStatus);

        boolean success = this.update(uw);

        if (success && newStatus != null) {
            Map<String, Object> msg = new HashMap<>();
            msg.put("questionSubmitId", id);
            msg.put("status", newStatus);
            msg.put("type", "status");
            QuestionSubmitStatusEnum statusEnum = QuestionSubmitStatusEnum.getEnumByValue(newStatus);
            msg.put("statusText", statusEnum != null ? statusEnum.getText() : "");
            judgeWebSocketServer.sendMessage(id, msg);
        }

        return success;


    }

    @Override
    public boolean updateStatusCasNoNotify(long id, Integer expectStatus, Integer newStatus) {
        UpdateWrapper<QuestionSubmit> uw = new UpdateWrapper<>();
        uw.eq("id", id).eq("status", expectStatus).set("status", newStatus);
        return this.update(uw);
    }

    @Override
    public long countProcessingByUserId(long userId) {
        QueryWrapper<QuestionSubmit> qw = new QueryWrapper<>();
        qw.eq("userId", userId);
        qw.in("status", QuestionSubmitStatusEnum.WAITING.getValue(),
                QuestionSubmitStatusEnum.RUNNING.getValue());
        return this.count(qw);
    }

}
