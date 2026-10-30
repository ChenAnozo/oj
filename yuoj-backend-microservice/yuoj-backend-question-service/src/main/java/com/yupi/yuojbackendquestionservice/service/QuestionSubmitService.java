package com.yupi.yuojbackendquestionservice.service;

import com.yupi.yuojbackendmodel.dto.judge.ClaimJudgeTaskResponse;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.yupi.yuojbackendmodel.dto.questionsubmit.QuestionSubmitAddRequest;
import com.yupi.yuojbackendmodel.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.yupi.yuojbackendmodel.entity.QuestionSubmit;
import com.yupi.yuojbackendmodel.entity.User;
import com.yupi.yuojbackendmodel.vo.QuestionSubmitVO;


/**
 * @author 93273
 * @description 针对表【question_submit(题目提交)】的数据库操作Service
 * @createDate 2026-03-07 21:24:30
 */
public interface QuestionSubmitService extends IService<QuestionSubmit> {

    /** 原子领取任务，数据准备失败时回滚领取。 */
    ClaimJudgeTaskResponse claimJudgeTask(long questionSubmitId);
    /**
     * 题目提交
     *
     * @param questionSubmitAddRequest
     * @param loginUser
     * @return
     */
    long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser);

    QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest);

    QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, User loginUser);
    Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage, User loginUser);

    boolean updateSubmitAndNotify(QuestionSubmit questionSubmit);


    boolean compareAndSetStatus(long id, Integer expectStatus, Integer newStatus);

    boolean updateStatusCasNoNotify(long id, Integer expectStatus, Integer newStatus);

    long countProcessingByUserId(long userId);

}
